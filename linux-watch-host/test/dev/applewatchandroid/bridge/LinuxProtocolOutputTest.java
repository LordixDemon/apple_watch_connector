package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/** Tests the actual private/public boundary and log lifetime, without a Watch. */
public final class LinuxProtocolOutputTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("watch-output-");
        PrintStream console = System.out;
        ByteArrayOutputStream publicBytes = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(publicBytes, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        try {
            LinuxSecretStore secrets = new LinuxSecretStore(root.resolve("identity"));
            var bonds = new LinkedBlockingQueue<Boolean>(1);
            var records = new LinkedBlockingQueue<Boolean>(1);
            var stop = new AtomicBoolean();
            byte[] bond = BluetoothBondSecretRecord.encode(0, new byte[6], 1, new byte[6], 0,
                    new byte[6], new byte[16], new byte[16], new byte[16], 16);
            String envelope = BOND_SECRET_PREFIX + BridgeBase64.encodeToString(bond, BridgeBase64.NO_WRAP);
            try (var output = new LinuxProtocolOutput(root.resolve("protocol.log"), secrets, bonds, records, stop)) {
                System.out.println("ACTIVATION bodyPreview=PRIVATE_BODY");
                System.out.println(envelope);
                if (!Boolean.TRUE.equals(bonds.poll()) || !Arrays.equals(bond, secrets.load("bond.sealed"))) {
                    throw new AssertionError("Persistence ACK before durable readback");
                }
                System.out.println(PAIRING_SESSION_PREFIX + "INVALID_SECRET");
                if (!Boolean.FALSE.equals(records.poll())) throw new AssertionError("Invalid record was acknowledged");
            }
            if (System.out != capture) throw new AssertionError("Stdout not restored");
            String visible = publicBytes.toString(StandardCharsets.UTF_8);
            String journal = Files.readString(root.resolve("protocol.log"));
            for (String secret : new String[]{envelope, "INVALID_SECRET", "PRIVATE_BODY"}) {
                if (visible.contains(secret) || journal.contains(secret)) throw new AssertionError("Private envelope leaked");
            }
            if (!visible.contains("LINUX_SECRET_STORE_FAILED")) throw new AssertionError("Missing failure receipt");
            try (var output = new LinuxProtocolOutput(root.resolve("protocol.log"), secrets, bonds, records, stop)) {
                System.out.println(LOCAL_IDENTITY_PREFIX + "NEVER_PUBLIC");
            }
            if (!stop.get() || publicBytes.toString(StandardCharsets.UTF_8).contains("NEVER_PUBLIC")) {
                throw new AssertionError("Unexpected private envelope did not stop output");
            }
            try (var journalWriter = new LinuxProtocolOutput.Journal(root.resolve("small.log"), 10)) {
                journalWriter.append("123456");
                journalWriter.append("abcdef");
            }
            if (Files.size(root.resolve("small.log")) > 10 || !Files.exists(root.resolve("protocol.previous.log"))) {
                throw new AssertionError("Journal rotation failed");
            }
        } finally {
            System.setOut(console);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Linux protocol output: PASS");
    }
}
