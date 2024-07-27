# Spring TCP Application for Starting a Minecraft Server

## Introduction

This Spring Boot application is designed to start a Minecraft server when connections are detected on a specified TCP port (during server ping). The application listens on the port, and upon detecting a connection, it starts a Minecraft server by executing a specified script (either a PowerShell script on Windows or a shell script on Linux).

## Properties

* minecraft.script.start: Path to your script
* minecraft.listen.port: (default 25565) Listening port of the Spring boot app
* minecraft.script.shell: (default sh) Shell for your linux environment

## Project Structure

### ServiceConnectionHandler

The core of the application is the ServiceConnectionHandler class, annotated with @Component and @Log4j2. This class handles the logic for listening to TCP connections, starting the Minecraft server, and managing processes.

* `start()`: Method called during application initialization to start listening for TCP connections on the specified port.
* `startServer()`: Listens for connections on the port defined by minecraft.listen.port.
* `startMinecraftServer()`: Starts the Minecraft server by executing the script specified by minecraft.script.start.
* `isPingRequest(String request)`: Method to determine if a request is a "ping" (not fully implemented yet).
* `monitorMinecraftServer()`: Monitors the Minecraft server process and restarts the TCP server when the Minecraft server stops.
* `stopServer()`: Stops the TCP server.
* `restartServer()`: Restarts the TCP server.

# Docker and Docker Compose Configuration

## Structure

```
/path/to/project
├── docker-compose.yaml        # Docker Compose configuration file
├── entrypoint.sh              # Shell script to start the Minecraft server
├── server.jar                 # The Minecraft server jar file
├── [...]                      # Rest of your server files (worlds, plugins...)
└── app
    └── starter.jar            # The Spring Boot application jar file
```

## Base image

The application is deployed using Docker. The base Docker image used is amazoncorretto:22-alpine-jdk, providing a lightweight environment for Java.

## entrypoint.sh

The entrypoint.sh script is used to start the Minecraft server. The script simply executes the following command to start the server without a graphical interface (nogui):

``` sh
#!/bin/sh

java -jar /usr/src/myapp/server.jar nogui
```

This script is crucial as it allows the Spring Boot application to delegate the startup of the Minecraft server through a separate process, providing flexible process management.

## docker-compose.yaml

``` yaml
version: '3.3' # Specifies the version of Docker Compose file format to use

services:
  minecraft-survival-server: # The service name for the Minecraft server
    image: amazoncorretto:22-alpine-jdk # The Docker image with Amazon Corretto JDK 22 on Alpine Linux
    restart: always # Always restart the container if it stops
    ports:
      - 25565:25565 # Main Minecraft server port, accessible from the host
      - 85:8123 # Port for additional services or plugins, like Dynmap
      - 8100:8100 # Another port for additional services or plugins
      - 25575:25575 # Port for RCON or other management tools
    environment:
      minecraft.listen.port: 25565 # Environment variable to set the port the application listens on
      minecraft.script.start: /usr/src/myapp/entrypoint.sh # Path to the startup script for the Minecraft server
    volumes:
      - /path/to/your/minecraft/server:/usr/src/myapp # Maps the host directory to the container for server files and data persistence
    working_dir: /usr/src/myapp # Sets the working directory inside the container
    command: java -jar /usr/src/myapp/app/starter.jar # Command to run the Spring Boot application
```

# Usage Instructions

## Configure Minecraft Server Files

1. Place all necessary files, including server.jar, in the directory specified by the Docker volume /var/services/homes/yoann/database/Servers/Minecraft/Terralith.

## Set Up Docker Compose File

2. Ensure that the docker-compose.yml file is properly configured with the desired paths and ports.

## Start the Application with Docker Compose

3. Use the following command to start the application and the Minecraft server:

``` sh
docker-compose up -d # The -d flag starts the containers in detached mode.
```

## Access Logs and Terminal

4. To view the server logs, use:

``` sh
docker logs <container_id> # Replace <container_id> with your container's ID.
```

## Stop the Application

5. To stop the containers, use:

``` sh
docker-compose down
```

# Additional Notes

* Customization: You can customize the scripts and configurations based on your specific needs, such as adding plugins or modifying Minecraft server settings.
* Resource Management: Ensure that your host machine has sufficient resources (CPU, memory) to run the Minecraft server smoothly.
* Community Involvement: The isPingRequest method is currently not fully implemented and may not accurately differentiate between a server list ping and a player connection. Contributions and improvements from the community are welcome to enhance this feature and improve the overall project.

