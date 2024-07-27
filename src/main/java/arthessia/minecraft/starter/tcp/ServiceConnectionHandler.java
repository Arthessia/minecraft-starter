package arthessia.minecraft.starter.tcp;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Component
public class ServiceConnectionHandler {

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
                    BufferedInputStream in = new BufferedInputStream(clientSocket.getInputStream());
                    byte[] buffer = new byte[1024];
                    int bytesRead = in.read(buffer);
                    if (bytesRead > 0) {
                        String request = new String(buffer, 0, bytesRead);
                        if (isPingRequest(request)) {
                            log.info("Ping request received: " + request);
                        } else {
                            log.info("Player connection request received: " + request);
                            startMinecraftServer();
                            clientSocket.close();
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        e.printStackTrace();
                    } else {
                        log.info("Server socket closed, stopping server.");
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
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

    private boolean isPingRequest(String request) {
        // TODO : Not working
        return request.endsWith("☺");
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
