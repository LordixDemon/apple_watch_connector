package dev.applewatchandroid.bridge;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/** Uses the working portable protocol chain with a Linux controller, not Android emulation. */
public final class WindowsProtocolHost {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[1].equals("pair") || args[1].equals("resume") || args[1].equals("confirm"))) {
            throw new IllegalArgumentException("Expected HCI helper and pair/resume/confirm mode");
        }
        Path state = BridgePaths.files();
        Files.createDirectories(state);
        WindowsFiles.restrict(state);
        if (args[1].equals("confirm")) {
            if (args.length != 3) throw new IllegalArgumentException("Expected confirmed pairing ID");
            if (!Files.isRegularFile(state.resolve("identity/pairing.sealed"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("No existing pairing identity");
            }
            WindowsSecretStore secrets = new WindowsSecretStore(state.resolve("identity"), Path.of(args[0]));
            byte[] bytes = secrets.load("pairing.sealed");
            PairingSessionRecord record = null;
            try {
                record = PairingSessionRecord.parse(bytes);
                OperationalSessionPolicy.requireMatchingActivatedPair(record, args[2]);
                secrets.store("owner-confirmed.sealed", args[2].getBytes(StandardCharsets.US_ASCII));
                secrets.publishPair(record);
                System.out.println("LINUX_OWNER_CONFIRMED_V1:" + args[2]);
            } finally { if (record != null) record.destroy(); Arrays.fill(bytes, (byte) 0); }
            return;
        }
        if (args.length != 2) throw new IllegalArgumentException("Unexpected mode arguments");
        Files.createDirectories(BridgePaths.temporary());
        try (java.nio.channels.FileChannel lockFile = java.nio.channels.FileChannel.open(
                state.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             java.nio.channels.FileLock lease = lockFile.tryLock()) {
            if (lease == null) throw new IllegalStateException("Another process owns this session");
            // Acquire the process lease before first-time key creation: two app
            // launches must never independently replace the installation key.
            run(args[0], args[1].equals("resume"), new WindowsSecretStore(state.resolve("identity"), Path.of(args[0])));
        }
    }
    private static void run(String helper, boolean resume, WindowsSecretStore secrets) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        BlockingQueue<char[]> pins = new LinkedBlockingQueue<>(1);
        BlockingQueue<Boolean> bonds = new LinkedBlockingQueue<>(1), records = new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> identities = new LinkedBlockingQueue<>(1), ids = new LinkedBlockingQueue<>(1), restored = new LinkedBlockingQueue<>(1);
        BlockingQueue<OutboundAppMessage> outbound = new LinkedBlockingQueue<>(32);
        try (LinuxProtocolOutput output = new LinuxProtocolOutput(
                BridgePaths.files().resolve("protocol.log"), secrets, bonds, records, stop)) {
            LinuxIdentityStore installation = new LinuxIdentityStore(secrets, new SecureRandom());
            RootWifiNetworkReader.installPlatformReader(() -> { throw new java.io.IOException("Windows Wi-Fi provisioning is unavailable"); });
            identities.offer(installation.bluetoothRecord());
            ids.offer(installation.idsPublicBundle());
            String pair = resume ? restoreSession(secrets, restored) : null;
            COMPANION_DISCOVERY.set(!resume);
            HalCommandReader.start(stop, pins, bonds, records, identities, ids, restored, new LinkedBlockingQueue<>(1), outbound);
            try (WindowsBluetoothController controller = new WindowsBluetoothController(Path.of(helper))) {
                HalTransportSession session = new HalTransportSession(controller,
                        stop, pins, bonds, records, identities, ids, restored, outbound, true, pair);
                Thread cleanup = new Thread(() -> {
                    try { session.cleanup(); }
                    finally {
                        try { controller.close(); }
                        catch (java.io.IOException failure) { log("WINDOWS HCI CLEANUP FAILED"); }
                    }
                }, "windows-watch-cleanup");
                Runtime.getRuntime().addShutdownHook(cleanup);
                try {
                    session.initialize();
                    session.runTransportHandshake(false);
                } catch (HalTransportSession.StopRequested expected) {
                    log("LINUX SESSION STOPPED");
                } catch (Exception failure) {
                    log("WINDOWS SESSION FAILED: " + safeMessage(failure));
                    throw failure;
                } finally {
                    stop.set(true);
                    try { session.cleanup(); }
                    finally { Runtime.getRuntime().removeShutdownHook(cleanup); }
                }
            } // Always close an acquired broker, including initialization failure.
        } finally {
            stop.set(true);
            clearPrivateQueue(identities);
            clearPrivateQueue(ids);
            clearPrivateQueue(restored);
            char[] pin;
            while ((pin = pins.poll()) != null) Arrays.fill(pin, '\0');
        }
    }

    private static String restoreSession(WindowsSecretStore secrets, BlockingQueue<byte[]> restored) throws Exception {
        byte[] bytes = secrets.load("pairing.sealed");
        PairingSessionRecord parsed = null;
        try {
            parsed = PairingSessionRecord.parse(bytes);
            secrets.publishPair(parsed);
            String id = OperationalSessionPolicy.pairingId(parsed);
            System.out.println("LINUX_PAIR_ID_V1:" + id);
            System.out.println("LINUX_RECORD_STATE_V1:" + parsed.state());
            String operational = OperationalSessionPolicy.mayUseOperationalMode(parsed.state(), id,
                    secrets.ownerConfirmedPair(), false, parsed.hasObservedSetupEvidence()) ? id : null;
            if (!restored.offer(bytes)) throw new IllegalStateException("Restore queue unavailable");
            bytes = null; // Ownership transferred to the protocol engine.
            return operational;
        } finally {
            if (parsed != null) parsed.destroy();
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void clearPrivateQueue(BlockingQueue<byte[]> queue) {
        byte[] record;
        while ((record = queue.poll()) != null) Arrays.fill(record, (byte) 0);
    }
}
