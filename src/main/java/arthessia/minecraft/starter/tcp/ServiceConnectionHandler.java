package arthessia.minecraft.starter.tcp;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Component
public class ServiceConnectionHandler {

    @Value("${minecraft.motd:Reveille moi ! Et retente dans 1 minute !}")
    private String motd;

    @Value("${minecraft.listen.port:25565}")
    private int port;

    @Value("${minecraft.script.start}")
    private String scriptPath;

    @Value("${minecraft.script.shell:sh}")
    private String shell;

    private ServerSocket serverSocket;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    private Process minecraftProcess;

    @PostConstruct
    public void start() {
        executorService.submit(this::startServer);
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(port);
            log.info("Minecraft TCP server listening on port " + port);

            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    log.info("Connection received.");

                    DataInputStream in = new DataInputStream(clientSocket.getInputStream());

                    int packetLength = readVarInt(in); // taille du paquet handshake
                    int packetId = readVarInt(in);
                    log.info("packetLength: " + packetLength);
                    log.info("packetId: " + packetId);
                    if (packetId == 0x00) {
                        // Handshake
                        int protocolVersion = readVarInt(in);
                        log.info("protocolVersion: " + protocolVersion);
                        String serverAddress = readString(in);
                        log.info("serverAddress: " + serverAddress);
                        int serverPort = in.readUnsignedShort();
                        log.info("serverPort: " + serverPort);
                        int nextState = readVarInt(in);
                        log.info("nextState: " + nextState);

                        if (nextState == 1) {
                            // ---- STATUS REQUEST ----
                            int statusLength = readVarInt(in);
                            log.info("statusLength: " + statusLength);
                            int statusPacketId = readVarInt(in);
                            log.info("statusPacketId: " + statusPacketId);

                            if (statusPacketId == 0x00) {
                                log.info("Status request received from {}", serverAddress);
                                sendStatusResponse(clientSocket);
                            }

                            // ---- PING (optionnel) ----
                            if (in.available() > 0) {
                                try {
                                    int pingLength = readVarInt(in);
                                    log.info("pingLength: " + pingLength);
                                    int pingPacketId = readVarInt(in);
                                    log.info("pingPacketId: " + pingPacketId);

                                    if (pingPacketId == 0x01) {
                                        long payload = in.readLong();
                                        sendPongResponse(clientSocket, payload);
                                    }
                                } catch (IOException e) {
                                    log.debug("Client closed before sending ping (normal).");
                                }
                            }

                            clientSocket.close();
                        } else if (nextState == 2) {
                            // ---- LOGIN ----
                            log.info("Player connection request received.");
                            startMinecraftServer();
                            clientSocket.close();
                        }
                    }

                } catch (IOException e) {
                    if (running) {
                        log.error("Error during request parsing: " + e.getMessage());
                    } else {
                        log.info("Server socket closed, stopping server.");
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error during server start: " + e.getMessage());
        }
    }

    private void sendPongResponse(Socket socket, long payload) throws IOException {
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream response = new DataOutputStream(baos);

        writeVarInt(response, 0x01); // packet id (pong)
        response.writeLong(payload);

        byte[] packetData = baos.toByteArray();

        writeVarInt(out, packetData.length); // longueur du paquet
        out.write(packetData);
        out.flush();

        log.info("Pong response sent.");
    }

    private void startMinecraftServer() throws IOException {
        stopServer();

        String os = System.getProperty("os.name").toLowerCase();

        ProcessBuilder processBuilder;
        if (os.contains("win")) {
            log.info("Starting powershell script: {}", scriptPath);
            processBuilder = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", scriptPath);
        } else {
            log.info("Starting bash script: {}", scriptPath);
            processBuilder = new ProcessBuilder(shell, scriptPath);
        }

        try {
            minecraftProcess = processBuilder.start();

            // Lire la sortie et les erreurs du processus
            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(minecraftProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("Server -> {}", line);
                    }
                } catch (IOException e) {
                    log.error("Error during startup: {}", e.getMessage());
                }
            }).start();

        } catch (IOException e) {
            e.printStackTrace(); // Afficher l'erreur pour un diagnostic
        }

        new Thread(this::monitorMinecraftServer).start();
    }

    private String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void sendStatusResponse(Socket socket) throws IOException {
        String json = "{"
                + "\"version\":{\"name\":\"Sleeping...\",\"protocol\":999},"
                + "\"players\":{\"max\":0,\"online\":0},"
                + "\"description\":{\"text\":\"" + motd + "\"}"
                + "}";

        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream response = new DataOutputStream(baos);

        // Packet ID
        writeVarInt(response, 0x00);

        // JSON data
        writeVarInt(response, jsonBytes.length);
        response.write(jsonBytes);

        byte[] packetData = baos.toByteArray();

        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        // Write full packet length
        writeVarInt(out, packetData.length);
        out.write(packetData);
        out.flush();

        log.info("Status response sent ({} bytes).", packetData.length);
    }

    private int readVarInt(DataInputStream in) throws IOException {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) {
                throw new RuntimeException("VarInt trop long (corrompu ?)");
            }
        } while ((read & 0b10000000) != 0);

        return result;
    }

    private void writeVarInt(DataOutputStream out, int value) throws IOException {
        do {
            byte temp = (byte) (value & 0b01111111);
            value >>>= 7;
            if (value != 0) {
                temp |= 0b10000000;
            }
            out.writeByte(temp);
        } while (value != 0);
    }

    private void monitorMinecraftServer() {
        try {
            minecraftProcess.waitFor();
            log.info("Minecraft server stopped, restarting Spring TCP server...");

            restartServer();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void stopServer() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                log.info("Server socket closed.");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        executorService.shutdownNow();
    }

    private void restartServer() {
        running = true;
        executorService = Executors.newSingleThreadExecutor();
        start();
    }
}
