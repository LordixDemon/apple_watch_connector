package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/**
 * Local bulletin CLI: encodes one BulletinDistributorCodec request with the
 * same field choices as BridgeIpcDispatcher.createBulletinPayload and hands it
 * to the running companion/daemon over its same-user control socket.
 * Contributed by Nocehi.
 */
public final class LinuxBulletinCli {
    private LinuxBulletinCli() {}

    public static void main(String[] args) throws IOException {
        String title = null, subtitle = null, body = null, section = null, sectionName = null;
        String socket = null, removeId = null;
        boolean sound = true, printOnly = false, status = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--title" -> title = value(args, ++i, arg);
                case "--subtitle" -> subtitle = value(args, ++i, arg);
                case "--body" -> body = value(args, ++i, arg);
                case "--section" -> section = value(args, ++i, arg);
                case "--section-name" -> sectionName = value(args, ++i, arg);
                case "--socket" -> socket = value(args, ++i, arg);
                case "--remove" -> removeId = value(args, ++i, arg);
                case "--silent" -> sound = false;
                case "--print" -> printOnly = true;
                case "--status" -> status = true;
                case "-h", "--help" -> { usage(); return; }
                default -> { System.err.println("Unknown argument: " + arg); usage(); System.exit(2); }
            }
        }
        String request;
        if (status) {
            request = "{\"method\":\"bulletinStatus\"}";
        } else if (removeId != null) {
            byte[] payload = new BulletinDistributorCodec.RemoveBulletinRequest(
                    removeId, removeId, section != null ? section : "com.apple.MobileSMS").encode();
            request = "{\"method\":\"removeBulletin\",\"payload\":\"" + base64(payload) + "\"}";
        } else {
            if (title == null && body == null) { usage(); System.exit(2); }
            String id = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            BulletinDistributorCodec.Bulletin bulletin = new BulletinDistributorCodec.Bulletin(
                    id, section, sectionName, title, subtitle, body,
                    id, id, UUID.randomUUID().toString(), sound, 2, now)
                    .withTurnsOnDisplay(sound);
            byte[] payload = new BulletinDistributorCodec.BulletinRequest(
                    bulletin, sound, now, BulletinDistributorCodec.UPDATE_TYPE_ADD).encode();
            String encoded = base64(payload);
            Arrays.fill(payload, (byte) 0);
            System.err.println("bulletinId=" + id + " payloadLen=" + (encoded.length() * 3 / 4));
            if (printOnly) { System.out.println(encoded); return; }
            request = "{\"method\":\"sendBulletin\",\"payload\":\"" + encoded + "\"}";
        }
        Path path = socket != null ? Path.of(socket) : defaultSocket();
        System.out.println(exchange(path, request));
    }

    private static String exchange(Path path, String request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(path))) {
            ByteBuffer out = ByteBuffer.wrap((request + "\n").getBytes(StandardCharsets.UTF_8));
            while (out.hasRemaining()) channel.write(out);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            ByteBuffer in = ByteBuffer.allocate(4096);
            while (channel.read(in) > 0) {
                response.write(in.array(), 0, in.position());
                in.clear();
                if (response.size() > 64 * 1024) break;
            }
            return response.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static Path defaultSocket() {
        String override = System.getenv("WATCH_COMPANION_CONTROL_SOCKET");
        if (override != null) return Path.of(override);
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime == null) throw new IllegalStateException("XDG_RUNTIME_DIR is not set");
        return Path.of(runtime, "watch-companion", "control.sock");
    }

    private static String base64(byte[] bytes) {
        return BridgeBase64.encodeToString(bytes, BridgeBase64.NO_WRAP);
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        return args[index];
    }

    private static void usage() {
        System.err.println("""
                LinuxBulletinCli --title T [--subtitle S] [--body B] [--section ID --section-name NAME] [--silent] [--print]
                LinuxBulletinCli --remove BULLETIN_ID [--section ID]
                LinuxBulletinCli --status
                Socket: $WATCH_COMPANION_CONTROL_SOCKET or $XDG_RUNTIME_DIR/watch-companion/control.sock""");
    }
}
