package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/** Consumes private persistence envelopes before stdout, Rust or the disk journal. */
final class LinuxProtocolOutput implements AutoCloseable {
    private static final int MAX_LINE = 512 * 1024;
    private final PrintStream previous;
    private final PrintStream filtered;
    private final Journal journal;

    LinuxProtocolOutput(Path path, DesktopSecretStore secrets, BlockingQueue<Boolean> bonds,
                        BlockingQueue<Boolean> records, AtomicBoolean stop) throws IOException {
        previous = System.out;
        journal = new Journal(path, 16 * 1024 * 1024);
        filtered = new PrintStream(new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();
            @Override public synchronized void write(int value) throws IOException {
                if (line.size() >= MAX_LINE) {
                    stop.set(true);
                    throw new IOException("Protocol line exceeded limit");
                }
                if (value != '\n') { line.write(value); return; }
                byte[] bytes = line.toByteArray();
                line.reset();
                try {
                    accept(new String(bytes, StandardCharsets.UTF_8), secrets, bonds, records);
                } catch (IOException failure) {
                    stop.set(true);
                    previous.println("LINUX_OUTPUT_FAILED");
                    throw failure;
                } finally { Arrays.fill(bytes, (byte) 0); }
            }
        }, true, StandardCharsets.UTF_8);
        System.setOut(filtered);
    }

    private void accept(String text, DesktopSecretStore secrets, BlockingQueue<Boolean> bonds,
                        BlockingQueue<Boolean> records) throws IOException {
        String prefix = text.startsWith(BOND_SECRET_PREFIX) ? BOND_SECRET_PREFIX
                : text.startsWith(PAIRING_SESSION_PREFIX) ? PAIRING_SESSION_PREFIX : null;
        if (prefix == null) {
            if (text.startsWith(LOCAL_IDENTITY_PREFIX) || text.startsWith(LOCAL_IDS_PUBLIC_PREFIX)
                    || text.startsWith(RESTORED_PAIRING_SESSION_PREFIX)) {
                throw new IOException("Unexpected private output envelope");
            }
            int preview = text.indexOf(" bodyPreview=");
            String visible = preview < 0 ? text : text.substring(0, preview) + "; body logged=false.";
            // System.out's Windows console encoding can differ from UTF-8.
            // Rust's event pipe has one explicit UTF-8 wire format.
            previous.write((visible + "\n").getBytes(StandardCharsets.UTF_8));
            previous.flush();
            journal.append(visible);
            return;
        }
        byte[] decoded = null;
        BlockingQueue<Boolean> acknowledgments = prefix.equals(BOND_SECRET_PREFIX) ? bonds : records;
        try {
            decoded = BridgeBase64.decode(text.substring(prefix.length()), BridgeBase64.NO_WRAP);
            if (prefix.equals(BOND_SECRET_PREFIX)) {
                BluetoothBondSecretRecord.validateSerialized(decoded);
                secrets.store("bond.sealed", decoded);
            } else {
                PairingSessionRecord parsed = PairingSessionRecord.parse(decoded);
                try {
                    secrets.store("pairing.sealed", decoded);
                    secrets.publishPair(parsed);
                    previous.println("LINUX_PAIR_ID_V1:" + OperationalSessionPolicy.pairingId(parsed));
                    previous.println("LINUX_RECORD_STATE_V1:" + parsed.state());
                } finally { parsed.destroy(); }
            }
            // store() has already performed fsync and authenticated readback.
            acknowledgments.offer(true);
        } catch (Exception failure) {
            acknowledgments.offer(false);
            previous.println("LINUX_SECRET_STORE_FAILED");
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            previous.flush();
        }
    }

    @Override public void close() throws IOException {
        // Never close the real stdout pipe owned by the launcher.
        System.setOut(previous);
        filtered.close();
        journal.close();
    }

    static final class Journal implements AutoCloseable {
        private final Path path;
        private final long limit;
        private OutputStream output;
        private long bytes;

        Journal(Path path, long limit) throws IOException {
            this.path = path;
            this.limit = limit;
            rotate();
        }

        private void rotate() throws IOException {
            if (output != null) output.close();
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Invalid protocol journal");
                }
                Files.move(path, path.resolveSibling("protocol.previous.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW);
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            bytes = 0;
        }

        synchronized void append(String line) throws IOException {
            byte[] encoded = (line + "\n").getBytes(StandardCharsets.UTF_8);
            if (encoded.length > limit) throw new IOException("Journal entry exceeded limit");
            if (bytes + encoded.length > limit) rotate();
            output.write(encoded);
            output.flush();
            bytes += encoded.length;
        }

        @Override public synchronized void close() throws IOException { output.close(); }
    }
}
