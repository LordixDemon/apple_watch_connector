package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One authenticated pair/epoch. The native Watch owns download/install persistence. */
final class SoftwareUpdateSession implements AutoCloseable {
    static final String PREFIX = "WATCH_UPDATE_V1:";
    static final String QUERY_COMMAND = "UPDATE_QUERY", SCAN_COMMAND = "UPDATE_SCAN";
    static final String DOWNLOAD_COMMAND = "UPDATE_DOWNLOAD:", INSTALL_COMMAND = "UPDATE_INSTALL:", TERMS_COMMAND = "UPDATE_TERMS:";
    private final UUID pair, epoch;
    private final String installedVersion, installedBuild;
    private final Path checkpoint;
    private final Set<String> pendingQueries = new LinkedHashSet<>();
    private SoftwareUpdateCodec.Descriptor descriptor;
    private String targetVersion, targetBuild, phase = "UNKNOWN";
    private Double progress;
    private Long error;
    private boolean known, scanCompleted, stalled, installAttempted;
    private long observedAt;

    SoftwareUpdateSession(UUID pair, UUID epoch, String version, String build, Path files) throws IOException {
        if (pair == null || epoch == null || !validVersion(version) || !validBuild(build) || files == null)
            throw new IllegalArgumentException("Missing authenticated update context");
        this.pair = pair; this.epoch = epoch; installedVersion = version; installedBuild = build;
        checkpoint = files.resolve("watch-update-checkpoint.json");
        restoreCheckpoint();
    }
    static boolean allows(String command) {
        if (QUERY_COMMAND.equals(command) || SCAN_COMMAND.equals(command)) return true;
        // Terms are accepted on the Watch, where the native agreement is displayed.
        for (String prefix : Set.of(DOWNLOAD_COMMAND, INSTALL_COMMAND))
            if (command != null && command.startsWith(prefix)) return validBuild(command.substring(prefix.length()));
        return false;
    }
    byte[] prepare(String command, long now) throws IOException {
        if (!allows(command)) throw new IllegalArgumentException("Unsupported update operation");
        if (QUERY_COMMAND.equals(command)) return SoftwareUpdateCodec.request(SoftwareUpdateCodec.QUERY, null);
        if (SCAN_COMMAND.equals(command)) {
            if (Set.of("DOWNLOADING_PREPARING", "INSTALLING", "VERIFYING_RESTART").contains(phase))
                throw new IllegalStateException("Native update is active");
            return SoftwareUpdateCodec.request(SoftwareUpdateCodec.SCAN, null);
        }
        if (!known || descriptor == null || observedAt <= 0 || now < observedAt || now - observedAt > 60_000
                || !command.endsWith(":" + descriptor.build()))
            throw new IllegalStateException("Refresh the current native update descriptor");
        String type;
        if (command.startsWith(TERMS_COMMAND)) type = SoftwareUpdateCodec.TERMS;
        else if (command.startsWith(DOWNLOAD_COMMAND)) {
            if (!phase.equals("AVAILABLE") || !descriptor.termsAccepted())
                throw new IllegalStateException("Download requires accepted native update terms");
            type = SoftwareUpdateCodec.DOWNLOAD;
        } else {
            if (!phase.equals("PREPARED") || installAttempted || !descriptor.termsAccepted()
                    || descriptor.build().equals(installedBuild))
                throw new IllegalStateException("Installation requires a prepared native update");
            type = SoftwareUpdateCodec.INSTALL;
        }
        byte[] original = descriptor.archive();
        try {
            byte[] payload = SoftwareUpdateCodec.request(type, original);
            if (type.equals(SoftwareUpdateCodec.INSTALL)) {
                // Commit before submission; ambiguous failures never cause automatic retries.
                try { persistCheckpoint(descriptor.version(), descriptor.build()); }
                catch (IOException failure) { Arrays.fill(payload, (byte) 0); throw failure; }
                installAttempted = true;
            }
            return payload;
        } finally { Arrays.fill(original, (byte) 0); }
    }
    void queued(String messageId, byte[] payload) {
        Map<?, ?> message = SoftwareUpdateCodec.message(payload);
        try {
            if (SoftwareUpdateCodec.QUERY.equals(message.get("MessageType"))) {
                UUID.fromString(messageId);
                pendingQueries.clear(); // Only the newest query can publish manager state.
                pendingQueries.add(messageId.toUpperCase(java.util.Locale.ROOT));
            }
        } finally { IdsMessageProtectionIdentity.wipeValues(message); }
    }
    boolean receive(byte[] payload, String responseId, long now) {
        if (now <= 0) throw new IllegalArgumentException("Invalid update observation time");
        Map<?, ?> message = SoftwareUpdateCodec.message(payload);
        SoftwareUpdateCodec.Descriptor next = null;
        try {
            String nextPhase = phase;
            boolean stateReply = message.containsKey("ManagerState");
            String type = message.get("MessageType") instanceof String text ? text : null;
            if (stateReply) {
                if (responseId == null || !pendingQueries.contains(responseId.toUpperCase(java.util.Locale.ROOT))) return false;
                if (!(message.get("ManagerState") instanceof Long state)) throw new IllegalArgumentException("Invalid native update state");
                nextPhase = managerPhase(state);
            } else if (!Set.of("ScanResult", "DownloadProgress", "InstallResult", "InstallationCanProceed",
                    "InstallationWillProceed", "UserDidAcceptTermsAndConditionsChanged", "ShowInstallError",
                    "ShowDownloadandPrepareError", "ShowDuetConditions").contains(type == null ? "" : type)) return false;
            Long nextError = SoftwareUpdateCodec.errorCode(message.get("Error"));
            if ((stateReply || "ScanResult".equals(type)) && !(message.get("Descriptor") instanceof byte[])
                    || message.containsKey("Descriptor") && !(message.get("Descriptor") instanceof byte[])
                    || message.containsKey("Download") && !(message.get("Download") instanceof byte[]))
                throw new IllegalArgumentException("Invalid native update object envelope");
            if (message.get("Descriptor") instanceof byte[] data) next = SoftwareUpdateCodec.descriptorOrNull(data);
            SoftwareUpdateCodec.Progress nextProgress = message.get("Download") instanceof byte[] data
                    ? SoftwareUpdateCodec.progress(data) : null;
            if (nextError != null) nextPhase = "FAILED";
            else if ("ScanResult".equals(type)) nextPhase = next == null ? "IDLE" : "AVAILABLE";
            else if ("InstallResult".equals(type)) nextPhase = "VERIFYING_RESTART";
            else if ("DownloadProgress".equals(type) && nextProgress != null) nextPhase = "DOWNLOADING_PREPARING";
            if (stateReply) pendingQueries.remove(responseId.toUpperCase(java.util.Locale.ROOT));
            else pendingQueries.clear(); // An older query cannot roll back a newer native notification.
            if (next != null && installAttempted && !next.build().equals(targetBuild)) {
                next.close(); next = null;
            }
            if (next != null) {
                if (descriptor != null) descriptor.close();
                descriptor = next; next = null;
                targetVersion = descriptor.version(); targetBuild = descriptor.build();
            } else if (message.containsKey("Descriptor")) {
                if (descriptor != null) descriptor.close(); descriptor = null;
                if (!installAttempted) { targetVersion = null; targetBuild = null; }
            }
            if (nextProgress != null) { progress = nextProgress.portion(); stalled = nextProgress.stalled(); }
            else if (stateReply) { progress = null; stalled = false; }
            error = nextError; phase = nextPhase; known = true; observedAt = now;
            if ("ScanResult".equals(type) && nextError == null) scanCompleted = true;
            if (installAttempted && targetBuild != null && targetBuild.equals(installedBuild)) {
                phase = "COMPLETE";
                installAttempted = false;
                try { Files.deleteIfExists(checkpoint); }
                catch (IOException ignored) { /* Matching authenticated build still proves completion. */ }
            }
            return true;
        } finally {
            if (next != null) next.close();
            IdsMessageProtectionIdentity.wipeValues(message);
        }
    }
    static String managerPhase(long state) {
        if (state < 1 || state > 10) throw new IllegalArgumentException("Unknown native manager state");
        return switch ((int) state) {
            case 1 -> "IDLE"; case 2 -> "CHECKING"; case 3 -> "AVAILABLE";
            case 4 -> "DOWNLOADING_PREPARING"; case 5 -> "WAITING_PREPARATION";
            case 6 -> "PREPARED"; case 7, 8 -> "WAITING_INSTALLATION";
            case 9 -> "INSTALLING"; case 10 -> "VERIFYING_RESTART";
            default -> throw new IllegalArgumentException("Unknown native manager state");
        };
    }
    String json() {
        return "{\"pairId\":\"" + pair + "\",\"epoch\":\"" + epoch + "\",\"known\":" + known
                + ",\"phase\":\"" + phase + "\",\"observedAt\":" + observedAt
                + ",\"installAttempted\":" + installAttempted
                + ",\"scanCompleted\":" + scanCompleted + ",\"targetVersion\":" + quote(targetVersion)
                + ",\"targetBuild\":" + quote(targetBuild) + ",\"installedVersion\":" + quote(installedVersion)
                + ",\"installedBuild\":" + quote(installedBuild) + ",\"downloadBytes\":" + number(descriptor == null ? -1 : descriptor.downloadBytes())
                + ",\"requiredSpaceBytes\":" + number(descriptor == null ? -1 : descriptor.requiredSpaceBytes())
                + ",\"progress\":" + progress + ",\"stalled\":" + stalled
                + ",\"termsAccepted\":" + (descriptor != null && descriptor.termsAccepted()) + ",\"errorCode\":" + error + "}";
    }
    private static String quote(String value) { return value == null ? "null" : "\"" + value + "\""; }
    private static String number(long value) { return value < 0 ? "null" : Long.toString(value); }
    private static boolean validVersion(String value) { return value != null && value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){0,3}"); }
    private static boolean validBuild(String value) { return value != null && value.matches("[0-9]{1,3}[A-Za-z][0-9]{1,6}[a-z]?"); }
    private void restoreCheckpoint() throws IOException {
        if (!Files.exists(checkpoint)) return;
        if (Files.isSymbolicLink(checkpoint) || Files.size(checkpoint) > 4096) throw new IOException("Invalid update checkpoint");
        Object root;
        try { root = BoundedJson.decode(Files.readAllBytes(checkpoint), 4096); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid update checkpoint", invalid); }
        if (!(root instanceof Map<?, ?> map) || !(map.get("schema") instanceof Number schema)
                || schema.doubleValue() != 1.0) throw new IOException("Invalid update checkpoint schema");
        if (!pair.toString().equals(map.get("pairId"))) return;
        if (!(map.get("targetVersion") instanceof String version) || !validVersion(version)
                || !(map.get("targetBuild") instanceof String build) || !validBuild(build)) throw new IOException("Invalid update target");
        targetVersion = version; targetBuild = build; installAttempted = true; phase = "VERIFYING_RESTART";
    }
    private void persistCheckpoint(String version, String build) throws IOException {
        Files.createDirectories(checkpoint.getParent());
        Path temporary = Files.createTempFile(checkpoint.getParent(), "watch-update-", ".pending");
        try {
            String json = "{\"schema\":1,\"pairId\":\"" + pair + "\",\"targetVersion\":\"" + version + "\",\"targetBuild\":\"" + build + "\"}";
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                var bytes = java.nio.ByteBuffer.wrap(json.getBytes(StandardCharsets.US_ASCII));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, checkpoint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() {
        if (descriptor != null) descriptor.close(); descriptor = null; pendingQueries.clear();
    }
}
