package arthessia.minecraft.starter.tcp;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import arthessia.minecraft.starter.tcp.MinecraftProtocol.Handshake;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Écoute sur le port Minecraft pendant que le vrai serveur est éteint.
 *
 * - Ping de la liste des serveurs -> répond un MOTD "en veille".
 * - Tentative de connexion d'un joueur -> répond un paquet Login Disconnect
 * ("le serveur démarre, patiente..."), libère le port, puis lance le script.
 * - Quand le processus Minecraft se termine, on se remet en écoute.
 *
 * Propriétés (application.yml) :
 * minecraft.listen.port, minecraft.script.start, minecraft.script.shell,
 * minecraft.motd, minecraft.message.starting,
 * minecraft.message.already-starting,
 * minecraft.client-timeout-ms, minecraft.rebind-delay-ms
 */
@Log4j2
@Component
public class ServiceConnectionHandler {

    private enum State {
        SLEEPING, STARTING
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int PACKET_STATUS_REQUEST = 0x00;
    private static final int PACKET_PING = 0x01;
    private static final int PACKET_LOGIN_START = 0x00;
    private static final int PACKET_LOGIN_DISCONNECT = 0x00;

    @Value("${minecraft.listen.port:25565}")
    private int port;

    @Value("${minecraft.script.start}")
    private String scriptPath;

    @Value("${minecraft.script.shell:sh}")
    private String shell;

    @Value("${minecraft.motd:§6Serveur en veille §8— §7connecte-toi pour le réveiller}")
    private String motd;

    @Value("${minecraft.message.starting:§6Le serveur démarre…§r\n§7Patiente une minute puis reconnecte-toi.}")
    private String startingMessage;

    @Value("${minecraft.message.already-starting:§6Démarrage déjà en cours…§r\n§7Encore un peu de patience !}")
    private String alreadyStartingMessage;

    /**
     * Un client qui ouvre la socket sans rien envoyer ne doit pas bloquer
     * indéfiniment.
     */
    @Value("${minecraft.client-timeout-ms:5000}")
    private int clientTimeoutMs;

    @Value("${minecraft.rebind-delay-ms:2000}")
    private long rebindDelayMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.SLEEPING);
    // Java 21+. En Java 17 : Executors.newCachedThreadPool().
    private final ExecutorService clientExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private volatile ServerSocket serverSocket;
    private volatile Process minecraftProcess;
    private volatile boolean shuttingDown;
    private Thread listener;

    @PostConstruct
    public void start() {
        listener = new Thread(this::listenLoop, "mc-waker-listener");
        listener.start();
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        closeListener();
        clientExecutor.shutdownNow();
        if (listener != null) {
            listener.interrupt();
        }
    }

    // ------------------------------------------------------------------ boucle

    private void listenLoop() {
        while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
            state.set(State.SLEEPING);
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(port));
                serverSocket = socket;
                log.info("En écoute sur le port {} — serveur Minecraft en veille.", port);
                acceptUntilStart(socket);
            } catch (IOException e) {
                if (!shuttingDown && state.get() != State.STARTING) {
                    log.error("Impossible d'écouter sur le port {} : {}", port, e.getMessage());
                    sleep(rebindDelayMs);
                }
            } finally {
                serverSocket = null;
            }

            if (shuttingDown) {
                return;
            }
            awaitMinecraftExit();
        }
    }

    private void acceptUntilStart(ServerSocket socket) throws IOException {
        while (state.get() == State.SLEEPING) {
            Socket client = socket.accept();
            clientExecutor.submit(() -> handleQuietly(client));
        }
    }

    /**
     * Bloque tant que le serveur Minecraft tourne, puis rend la main pour
     * réécouter.
     */
    private void awaitMinecraftExit() {
        Process process = minecraftProcess;
        if (process == null) {
            sleep(rebindDelayMs);
            return;
        }
        log.info("Serveur Minecraft en cours d'exécution (pid {}).", process.pid());
        try {
            int code = process.waitFor();
            log.info("Serveur Minecraft arrêté (code {}). Retour en mode veille.", code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            minecraftProcess = null;
        }
        sleep(rebindDelayMs); // laisse l'OS libérer le port
    }

    // ----------------------------------------------------------------- clients

    private void handleQuietly(Socket socket) {
        try (socket) {
            socket.setSoTimeout(clientTimeoutMs);
            socket.setTcpNoDelay(true);
            handle(socket);
        } catch (SocketTimeoutException e) {
            log.debug("Client inactif, connexion fermée.");
        } catch (IOException e) {
            log.debug("Connexion interrompue : {}", e.getMessage());
        }
    }

    private void handle(Socket socket) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

        int packetId = MinecraftProtocol.readPacketId(in);
        if (packetId != 0x00) {
            log.debug("Paquet initial inattendu (id {}), connexion ignorée.", packetId);
            return;
        }

        Handshake handshake = Handshake.read(in);
        log.debug("Handshake : protocole={} hôte={}:{} état={}",
                handshake.protocolVersion(), handshake.serverAddress(),
                handshake.serverPort(), handshake.nextState());

        if (handshake.isStatus()) {
            handleStatus(socket, in, handshake.protocolVersion());
        } else if (handshake.isLogin()) {
            handleLogin(socket, in);
        }
    }

    private void handleStatus(Socket socket, DataInputStream in, int protocolVersion) throws IOException {
        if (MinecraftProtocol.readPacketId(in) != PACKET_STATUS_REQUEST) {
            return;
        }
        MinecraftProtocol.writePacket(socket.getOutputStream(), 0x00,
                out -> MinecraftProtocol.writeString(out, buildStatusJson(protocolVersion)));
        log.info("Ping reçu, MOTD de veille envoyé.");

        // Ping/Pong : le client peut aussi fermer directement, c'est normal.
        try {
            if (MinecraftProtocol.readPacketId(in) == PACKET_PING) {
                long payload = in.readLong();
                MinecraftProtocol.writePacket(socket.getOutputStream(), PACKET_PING,
                        out -> out.writeLong(payload));
            }
        } catch (IOException e) {
            log.debug("Pas de ping après le status (normal).");
        }
    }

    private void handleLogin(Socket socket, DataInputStream in) throws IOException {
        String player = readPlayerName(in).orElse("un joueur");
        boolean weStartIt = state.compareAndSet(State.SLEEPING, State.STARTING);

        sendLoginDisconnect(socket, weStartIt ? startingMessage : alreadyStartingMessage);
        log.info("Connexion de {} : message de démarrage envoyé.", player);
        socket.close();

        if (weStartIt) {
            startMinecraftServer(player);
        }
    }

    /** Le nom du joueur est purement informatif : on tolère un échec de lecture. */
    private Optional<String> readPlayerName(DataInputStream in) {
        try {
            if (MinecraftProtocol.readPacketId(in) == PACKET_LOGIN_START) {
                return Optional.of(MinecraftProtocol.readString(in));
            }
        } catch (IOException e) {
            log.debug("Login Start non lisible : {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Paquet Login Disconnect : le client affiche ce texte dans l'écran
     * "Déconnecté" au lieu d'un message d'erreur réseau.
     */
    private void sendLoginDisconnect(Socket socket, String message) throws IOException {
        ObjectNode component = MAPPER.createObjectNode();
        component.put("text", message);
        String json = MAPPER.writeValueAsString(component);

        MinecraftProtocol.writePacket(socket.getOutputStream(), PACKET_LOGIN_DISCONNECT,
                out -> MinecraftProtocol.writeString(out, json));
    }

    private String buildStatusJson(int protocolVersion) {
        ObjectNode root = MAPPER.createObjectNode();
        // On renvoie le protocole du client : pas de croix rouge "version
        // incompatible".
        root.putObject("version")
                .put("name", "En veille")
                .put("protocol", protocolVersion);
        root.putObject("players")
                .put("max", 0)
                .put("online", 0);
        root.putObject("description")
                .put("text", motd);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"version\":{\"name\":\"En veille\",\"protocol\":0},"
                    + "\"players\":{\"max\":0,\"online\":0},"
                    + "\"description\":{\"text\":\"Serveur en veille\"}}";
        }
    }

    // -------------------------------------------------------------- processus

    private void startMinecraftServer(String player) {
        log.info("Réveil du serveur demandé par {}.", player);
        closeListener(); // libère le port avant que le vrai serveur ne le prenne

        ProcessBuilder builder = buildProcess();
        builder.redirectErrorStream(true);
        try {
            minecraftProcess = builder.start();
            pipeOutput(minecraftProcess);
        } catch (IOException e) {
            log.error("Échec du lancement du script {} : {}", scriptPath, e.getMessage(), e);
            minecraftProcess = null;
            state.set(State.SLEEPING); // la boucle se remettra en écoute
        }
    }

    private ProcessBuilder buildProcess() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        if (windows) {
            log.info("Lancement du script PowerShell : {}", scriptPath);
            return new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath);
        }
        log.info("Lancement du script shell : {} {}", shell, scriptPath);
        return new ProcessBuilder(shell, scriptPath);
    }

    private void pipeOutput(Process process) {
        Thread.ofVirtual().name("mc-output").start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[MC] {}", line);
                }
            } catch (IOException e) {
                log.debug("Flux du serveur fermé : {}", e.getMessage());
            }
        });
    }

    // ----------------------------------------------------------------- outils

    private void closeListener() {
        ServerSocket socket = serverSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close(); // débloque accept() dans listenLoop
                log.info("Port {} libéré.", port);
            } catch (IOException e) {
                log.warn("Fermeture de la socket d'écoute : {}", e.getMessage());
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
