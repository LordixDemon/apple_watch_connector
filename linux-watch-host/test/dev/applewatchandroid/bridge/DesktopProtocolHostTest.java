package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Standalone tests for shared desktop CLI parsing and owner confirmation flow. */
public final class DesktopProtocolHostTest {
    public static void main(String[] args) throws Exception {
        testParseArgs();
        testConfirmPairFlow();
        testProtocolJarFingerprint();
        System.out.println("Desktop protocol host: PASS");
    }

    private static void testParseArgs() {
        DesktopProtocolHost.ParsedArgs pair = DesktopProtocolHost.parseArgs(new String[]{"/bin/hci", "pair"});
        if (pair.mode() != DesktopProtocolHost.Mode.PAIR || pair.experimentalLegacy() || pair.confirmPairId() != null) {
            throw new AssertionError("pair parse mismatch");
        }
        DesktopProtocolHost.ParsedArgs legacy = DesktopProtocolHost.parseArgs(
                new String[]{"/bin/hci", "pair", "--experimental-legacy-pairing"});
        if (legacy.mode() != DesktopProtocolHost.Mode.PAIR || !legacy.experimentalLegacy()) {
            throw new AssertionError("legacy pair parse mismatch");
        }
        DesktopProtocolHost.ParsedArgs resume = DesktopProtocolHost.parseArgs(new String[]{"/bin/hci", "resume"});
        if (resume.mode() != DesktopProtocolHost.Mode.RESUME || resume.experimentalLegacy()) {
            throw new AssertionError("resume parse mismatch");
        }
        String pairId = UUID.randomUUID().toString();
        DesktopProtocolHost.ParsedArgs confirm = DesktopProtocolHost.parseArgs(
                new String[]{"/bin/hci", "confirm", pairId});
        if (confirm.mode() != DesktopProtocolHost.Mode.CONFIRM || !pairId.equals(confirm.confirmPairId())) {
            throw new AssertionError("confirm parse mismatch");
        }

        for (String[] invalid : new String[][]{
                {},
                {"/bin/hci"},
                {"/bin/hci", "unknown"},
                {"/bin/hci", "confirm"},
                {"/bin/hci", "confirm", pairId, "extra"},
                {"/bin/hci", "resume", "--experimental-legacy-pairing"},
                {"/bin/hci", "pair", "--unknown-flag"},
                {"/bin/hci", "pair", "--experimental-legacy-pairing", "extra"},
        }) {
            mustThrow(() -> DesktopProtocolHost.parseArgs(invalid));
        }
    }

    private static void testConfirmPairFlow() throws Exception {
        Path root = Files.createTempDirectory("watch-desktop-host-test-");
        PrintStream console = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            Path identityDir = root.resolve("identity");
            LinuxSecretStore secrets = new LinuxSecretStore(identityDir);
            UUID generationUuid = UUID.randomUUID();
            String pairId = generationUuid.toString();
            byte[] sealed = buildActivatedRecord(generationUuid);
            try {
                secrets.store("pairing.sealed", sealed);
            } finally {
                Arrays.fill(sealed, (byte) 0);
            }

            mustThrow(() -> DesktopProtocolHost.confirmPair(secrets, UUID.randomUUID().toString()));
            if (secrets.contains("owner-confirmed.sealed")) {
                throw new AssertionError("Mismatched pair wrote owner confirmation");
            }

            System.setOut(capture);
            DesktopProtocolHost.confirmPair(secrets, pairId);
            System.setOut(console);

            byte[] confirmed = secrets.load("owner-confirmed.sealed");
            try {
                if (!pairId.equals(new String(confirmed, StandardCharsets.US_ASCII))) {
                    throw new AssertionError("owner-confirmed.sealed mismatch");
                }
            } finally {
                Arrays.fill(confirmed, (byte) 0);
            }
            String pairJson = Files.readString(identityDir.resolve("pair-public.json"), StandardCharsets.UTF_8);
            if (!pairJson.contains("\"pairId\":\"" + pairId + "\"")
                    || !pairJson.contains("\"activationConfirmed\":true")
                    || !pairJson.contains("\"ownerConfirmed\":true")) {
                throw new AssertionError("pair-public.json incomplete: " + pairJson);
            }
            String stdout = captured.toString(StandardCharsets.UTF_8);
            if (!stdout.contains("LINUX_OWNER_CONFIRMED_V1:" + pairId)) {
                throw new AssertionError("Missing owner confirm wire line");
            }
        } finally {
            System.setOut(console);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void testProtocolJarFingerprint() throws Exception {
        Path root = Files.createTempDirectory("watch-desktop-fp-test-");
        try {
            if (!"unknown".equals(DesktopProtocolHost.protocolJarFingerprint(null))) {
                throw new AssertionError("Null directory should return unknown");
            }
            if (!"unknown".equals(DesktopProtocolHost.protocolJarFingerprint(root))) {
                throw new AssertionError("Missing manifest should return unknown");
            }
            Path manifest = root.resolve("protocol-manifest.json");
            String windowsSha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
            Files.writeString(
                    manifest,
                    "{\n  \"artifacts\": {\n    \"watch-windows-protocol.jar\": \"" + windowsSha + "\"\n  }\n}\n",
                    StandardCharsets.UTF_8);
            if (!"abcdef012345".equals(DesktopProtocolHost.protocolJarFingerprint(root))) {
                throw new AssertionError("Windows manifest fingerprint mismatch");
            }
            String linuxSha = "99887766554433221100ffeeddccbbaa99887766554433221100ffeeddccbbaa";
            Files.writeString(
                    manifest,
                    "{\"artifacts\":{\"watch-linux-protocol.jar\":\"" + linuxSha + "\"}}",
                    StandardCharsets.UTF_8);
            if (!"998877665544".equals(DesktopProtocolHost.protocolJarFingerprint(root))) {
                throw new AssertionError("Linux manifest fingerprint mismatch");
            }
            Files.writeString(
                    manifest,
                    "{\"artifacts\":{\"watch-windows-protocol.jar\":\"NOT_A_VALID_SHA\"}}",
                    StandardCharsets.UTF_8);
            if (!"unknown".equals(DesktopProtocolHost.protocolJarFingerprint(root))) {
                throw new AssertionError("Malformed manifest should return unknown");
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static byte[] buildActivatedRecord(UUID uuid) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] generation = ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
        var localOob = BleSecureConnectionsCrypto.generateLocalOob(random);
        var localMaterial = AppleNetworkRelayPairingMaterial.generate(random);
        var localAddresses = AppleNetworkRelayInnerAddresses.generate(random);
        var peerOob = BleSecureConnectionsCrypto.generateLocalOob(random);
        var peerMaterial = AppleNetworkRelayPairingMaterial.generate(random);
        var peerAddresses = AppleNetworkRelayInnerAddresses.generate(random);
        var peerSource = ApplePairingNotifyPayloads.createLocalBatch(
                peerOob, peerMaterial, peerAddresses, "Apple Watch Ultra 2", "23U67", "ids-device");
        List<ApplePairingNotifyPayloads.PrivateNotify> notifies = new ArrayList<>(peerSource.notifies().subList(0, 10));
        for (int i = 10; i < peerSource.notifies().size(); i++) peerSource.notifies().get(i).destroy();
        var peer = ApplePairingNotifyPayloads.parsePeerBatch(notifies);

        byte[] bond = BluetoothBondSecretRecord.encode(
                0, new byte[]{1, 2, 3, 4, 5, 6},
                1, new byte[]{11, 12, 13, 14, 15, 16},
                0, new byte[]{21, 22, 23, 24, 25, 26},
                new byte[16], new byte[16], new byte[16], 16);
        List<PairingSessionRecord> records = new ArrayList<>();
        AppleNetworkRelayInnerAddresses swapped = null;
        try {
            byte[] initD = localAddresses.responderClassD();
            byte[] respD = localAddresses.initiatorClassD();
            byte[] initC = localAddresses.responderClassC();
            byte[] respC = localAddresses.initiatorClassC();
            try {
                swapped = AppleNetworkRelayInnerAddresses.fromAuthoritative(initD, respD, initC, respC);
            } finally {
                Arrays.fill(initD, (byte) 0);
                Arrays.fill(respD, (byte) 0);
                Arrays.fill(initC, (byte) 0);
                Arrays.fill(respC, (byte) 0);
            }
            PairingSessionRecord cur = PairingSessionRecord.createPreSmp(
                    generation, new byte[]{0, 1, 2, 3}, localOob, localMaterial, localAddresses, peer,
                    "Watch7,5", "n210ap",
                    "00112233-4455-6677-8899-aabbccddeeff",
                    "10213243-5465-7687-98a9-bacbdcedfe0f",
                    "20314253-6475-8697-a8b9-cadbecfd0e1f");
            records.add(cur);
            cur = cur.withBluetoothBond(bond); records.add(cur);
            cur = cur.withPreludeNegotiated(NrLinkBluetoothPrelude.LocalRole.INITIATOR); records.add(cur);
            cur = cur.withClassDEstablished(swapped); records.add(cur);
            cur = cur.advanceTo(PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED); records.add(cur);
            cur = cur.markIdsAuthenticationAccepted(); records.add(cur);
            for (PairingSessionRecord.DurableState state : new PairingSessionRecord.DurableState[]{
                    PairingSessionRecord.DurableState.IDS_CONTROL_READY,
                    PairingSessionRecord.DurableState.IDS_DATA_READY,
                    PairingSessionRecord.DurableState.INITIAL_PROPERTIES_RECEIVED,
                    PairingSessionRecord.DurableState.READY_TO_COMMIT_IS_PAIRED
            }) {
                cur = cur.advanceTo(state);
                records.add(cur);
            }
            cur = cur.prepareIsPairedCommit(true); records.add(cur);
            cur = cur.confirmIsPairedCommit(true); records.add(cur);
            cur = cur.advanceTo(PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED); records.add(cur);
            return cur.serialize();
        } finally {
            records.forEach(PairingSessionRecord::destroy);
            if (swapped != null) swapped.destroy();
            Arrays.fill(bond, (byte) 0);
            Arrays.fill(generation, (byte) 0);
            peer.destroy();
            for (var n : notifies) n.destroy();
            peerSource.destroy();
            peerAddresses.destroy();
            peerMaterial.destroy();
            peerOob.destroy();
            localAddresses.destroy();
            localMaterial.destroy();
            localOob.destroy();
        }
    }

    private interface Action { void run() throws Exception; }

    private static void mustThrow(Action action) {
        try {
            action.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("Expected rejection");
    }
}
