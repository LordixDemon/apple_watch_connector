package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static dev.applewatchandroid.bridge.ProtocolHost.*;

/** Shared desktop lifecycle (CLI args, lock lease, session orchestration, and owner confirm). */
final class DesktopProtocolHost {
    enum Mode { PAIR, RESUME, CONFIRM }

    record ParsedArgs(Path helper, Mode mode, String confirmPairId, boolean experimentalLegacy) {}

    interface Platform {
        String platformTag();
        String cleanupThreadName();
        void restrictDirectory(Path state) throws IOException;
        boolean hasExistingIdentityForConfirm(Path state);
        DesktopSecretStore openSecretStore(Path identityDir, Path helper) throws Exception;
        BluetoothController openController(Path helper);
        RootWifiNetworkReader.Reader wifiReader();
    }

    private static final long MAX_MANIFEST_BYTES = 512 * 1024;
    private static final Pattern PROTOCOL_JAR_SHA_PATTERN = Pattern.compile(
            "\"watch-(?:windows|linux)-protocol\\.jar\"\\s*:\\s*\"([0-9a-f]{12})[0-9a-f]{52}\"");

    private DesktopProtocolHost() {}

    static String protocolJarFingerprint(Path protocolDirectory) {
        if (protocolDirectory == null) {
            return "unknown";
        }
        Path manifest = protocolDirectory.resolve("protocol-manifest.json");
        try {
            if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(manifest) > MAX_MANIFEST_BYTES) {
                return "unknown";
            }
            String content = Files.readString(manifest, StandardCharsets.UTF_8);
            Matcher matcher = PROTOCOL_JAR_SHA_PATTERN.matcher(content);
            return matcher.find() ? matcher.group(1) : "unknown";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String currentProtocolFingerprint() {
        try {
            var codeSource = DesktopProtocolHost.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return "unknown";
            }
            Path location = Path.of(codeSource.getLocation().toURI());
            Path directory = Files.isDirectory(location) ? location : location.getParent();
            return protocolJarFingerprint(directory);
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    static ParsedArgs parseArgs(String[] args) {
        if (args.length < 2 || !(args[1].equals("pair") || args[1].equals("resume") || args[1].equals("confirm"))) {
            throw new IllegalArgumentException("Expected HCI helper and pair/resume/confirm mode");
        }
        if (args[1].equals("confirm")) {
            if (args.length != 3) {
                throw new IllegalArgumentException("Expected confirmed pairing ID");
            }
            return new ParsedArgs(Path.of(args[0]), Mode.CONFIRM, args[2], false);
        }
        boolean experimentalLegacy = false;
        if (args.length == 3) {
            if (!args[1].equals("pair") || !"--experimental-legacy-pairing".equals(args[2])) {
                throw new IllegalArgumentException("Unexpected mode arguments");
            }
            experimentalLegacy = true;
        } else if (args.length != 2) {
            throw new IllegalArgumentException("Unexpected mode arguments");
        }
        return new ParsedArgs(
                Path.of(args[0]),
                args[1].equals("resume") ? Mode.RESUME : Mode.PAIR,
                null,
                experimentalLegacy);
    }

    static void runMain(String[] args, Platform platform) throws Exception {
        ParsedArgs parsed = parseArgs(args);
        Path state = BridgePaths.files();
        Files.createDirectories(state);
        platform.restrictDirectory(state);
        if (parsed.mode() == Mode.CONFIRM) {
            if (!platform.hasExistingIdentityForConfirm(state)) {
                throw new IllegalStateException("No existing pairing identity");
            }
            confirmPair(platform.openSecretStore(state.resolve("identity"), parsed.helper()), parsed.confirmPairId());
            return;
        }
        Files.createDirectories(BridgePaths.temporary());
        try (FileChannel lockFile = FileChannel.open(
                state.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lease = lockFile.tryLock()) {
            if (lease == null) {
                throw new IllegalStateException("Another process owns this session");
            }
            // Acquire the process lease before first-time key creation: two app
            // launches must never independently replace the installation key.
            runSession(
                    parsed.helper(),
                    parsed.mode() == Mode.RESUME,
                    parsed.experimentalLegacy(),
                    platform.openSecretStore(state.resolve("identity"), parsed.helper()),
                    platform);
        }
    }

    static void confirmPair(DesktopSecretStore secrets, String pairId) throws Exception {
        byte[] bytes = secrets.load("pairing.sealed");
        PairingSessionRecord record = null;
        try {
            record = PairingSessionRecord.parse(bytes);
            OperationalSessionPolicy.requireMatchingActivatedPair(record, pairId);
            secrets.store("owner-confirmed.sealed", pairId.getBytes(StandardCharsets.US_ASCII));
            secrets.publishPair(record);
            System.out.println("LINUX_OWNER_CONFIRMED_V1:" + pairId);
        } finally {
            if (record != null) record.destroy();
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void runSession(
            Path helper,
            boolean resume,
            boolean experimentalLegacy,
            DesktopSecretStore secrets,
            Platform platform) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        BlockingQueue<char[]> pins = new LinkedBlockingQueue<>(1);
        BlockingQueue<Boolean> bonds = new LinkedBlockingQueue<>(1), records = new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> identities = new LinkedBlockingQueue<>(1), ids = new LinkedBlockingQueue<>(1),
                restored = new LinkedBlockingQueue<>(1);
        BlockingQueue<OutboundAppMessage> outbound = new LinkedBlockingQueue<>(32);
        try (LinuxProtocolOutput output = new LinuxProtocolOutput(
                BridgePaths.files().resolve("protocol.log"), secrets, bonds, records, stop)) {
            log("DESKTOP PROTOCOL RUNTIME: platform=" + platform.platformTag()
                    + " jarSha12=" + currentProtocolFingerprint()
                    + " smpTimeoutMs=" + SMP_RESPONSE_TIMEOUT_MS
                    + " leLinkNegotiation=2M+DLE.");
            LinuxIdentityStore installation = new LinuxIdentityStore(secrets, new SecureRandom());
            RootWifiNetworkReader.installPlatformReader(platform.wifiReader());
            identities.offer(installation.bluetoothRecord());
            ids.offer(installation.idsPublicBundle());
            String pair = resume ? restoreSession(secrets, restored) : null;
            COMPANION_DISCOVERY.set(!resume);
            EXPERIMENTAL_LEGACY_PAIRING.set(experimentalLegacy && !resume);
            if (EXPERIMENTAL_LEGACY_PAIRING.get()) {
                log("[EXPERIMENTAL] Legacy Watch pairing (watchOS 1-9, strategies 1-3) enabled via CLI.");
            }
            HalCommandReader.start(stop, pins, bonds, records, identities, ids, restored,
                    new LinkedBlockingQueue<>(1), outbound);
            try (BluetoothController controller = platform.openController(helper)) {
                HalTransportSession session = new HalTransportSession(controller,
                        stop, pins, bonds, records, identities, ids, restored, outbound, true, pair);
                Thread cleanup = new Thread(() -> {
                    try { session.cleanup(); }
                    finally {
                        try { controller.close(); }
                        catch (IOException failure) { log(platform.platformTag() + " HCI CLEANUP FAILED"); }
                    }
                }, platform.cleanupThreadName());
                Runtime.getRuntime().addShutdownHook(cleanup);
                try {
                    session.initialize();
                    session.runTransportHandshake(false);
                } catch (HalTransportSession.StopRequested expected) {
                    log("LINUX SESSION STOPPED");
                } catch (Exception failure) {
                    log(platform.platformTag() + " SESSION FAILED: " + safeMessage(failure));
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
            ProtocolHost.resetSessionControls();
        }
    }

    private static String restoreSession(DesktopSecretStore secrets, BlockingQueue<byte[]> restored) throws Exception {
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
