package arthessia.minecraft.starter.tcp;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Primitives du protocole Minecraft (handshake / status / login).
 * Sans état : uniquement des méthodes statiques.
 */
public final class MinecraftProtocol {

    /** Garde-fou : un paquet de handshake/status ne dépasse jamais quelques Ko. */
    public static final int MAX_PACKET_LENGTH = 1024 * 1024;

    @FunctionalInterface
    public interface PacketBody {
        void write(DataOutputStream out) throws IOException;
    }

    private MinecraftProtocol() {
    }

    public static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            byte b = in.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IOException("VarInt trop long (paquet corrompu ou client non Minecraft)");
            }
        }
    }

    public static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_PACKET_LENGTH) {
            throw new IOException("Longueur de chaîne invalide : " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Flux interrompu pendant la lecture d'une chaîne");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    /** Lit l'en-tête du prochain paquet (longueur + id) et renvoie son id. */
    public static int readPacketId(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > MAX_PACKET_LENGTH) {
            throw new IOException("Longueur de paquet invalide : " + length);
        }
        return readVarInt(in);
    }

    /** Écrit un paquet complet : longueur, id, puis le corps fourni. */
    public static void writePacket(OutputStream rawOut, int packetId, PacketBody body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(buffer);
        writeVarInt(packet, packetId);
        body.write(packet);
        packet.flush();

        byte[] data = buffer.toByteArray();
        DataOutputStream out = new DataOutputStream(rawOut);
        writeVarInt(out, data.length);
        out.write(data);
        out.flush();
    }

    /** Résultat du paquet de handshake (id 0x00, état initial). */
    public record Handshake(int protocolVersion, String serverAddress, int serverPort, int nextState) {

        public static Handshake read(DataInputStream in) throws IOException {
            return new Handshake(
                    readVarInt(in),
                    readString(in),
                    in.readUnsignedShort(),
                    readVarInt(in));
        }

        public boolean isStatus() {
            return nextState == 1;
        }

        /**
         * 2 = login, 3 = transfer (1.20.5+) : dans les deux cas, un joueur veut entrer.
         */
        public boolean isLogin() {
            return nextState == 2 || nextState == 3;
        }
    }
}
