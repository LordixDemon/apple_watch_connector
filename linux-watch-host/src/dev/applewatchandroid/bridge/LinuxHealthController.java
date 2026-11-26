package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Desktop HealthDaemon & NanoSystemSettings read-only telemetry controller.
 * - Drives Restore -> Changes pulls (on connect, every 5 minutes, and on-demand via PULL_HEALTH)
 *   without advancing native sync anchors.
 * - Queries REQUEST_DEVICE_ABOUT for watch battery percentage and charging state.
 * - Decodes Quantity samples (latest + today's cumulative totals by UUID), Category samples,
 *   and Workout sample counts.
 * - Publishes an atomic JSON snapshot to health/health-latest.json (mode 0600 on POSIX).
 * Contributed by Nocehi.
 */
final class LinuxHealthController implements AutoCloseable {
    private static final double APPLE_EPOCH_OFFSET_SEC = 978_307_200.0;
    private static final long PERIODIC_PULL_MINUTES = 5L;
    private static final long MIN_PULL_GAP_MS = 10_000L;
    private static final Set<String> CUMULATIVE_TODAY_TYPES = Set.of(
            "HKQuantityTypeIdentifierStepCount",
            "HKQuantityTypeIdentifierActiveEnergyBurned",
            "HKQuantityTypeIdentifierBasalEnergyBurned",
            "HKQuantityTypeIdentifierDistanceWalkingRunning",
            "HKQuantityTypeIdentifierAppleExerciseTime",
            "HKQuantityTypeIdentifierAppleStandTime"
    );

    private final Path healthDir;
    private final DesktopSecretStore secrets;
    private final LinuxIdentityStore identityStore;
    private final BlockingQueue<ProtocolHost.OutboundAppMessage> outbound;
    private final AtomicBoolean stop;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "linux-health");
        t.setDaemon(true);
        return t;
    });

    private final HealthPeerIdentityStore peerStore;
    private final HealthSyncStateStore syncStore;

    private UUID currentEpoch;
    private UUID peerEpoch;
    private HealthRegistryObservationCodec.Observation registryObservation;
    private ScheduledFuture<?> periodicTask;
    private long lastPullAtMs;
    private String product;
    private String build;
    private String restorePhase = "NONE";
    private String changesPhase = "NONE";
    private String lastEvent = "INIT";
    private int inboundFrames;
    private int decodedObjects;
    private int quantitySamples;
    private int categorySamples;
    private int workoutSamples;
    private int unknownFields;

    private Integer batteryPercentage;
    private Boolean batteryCharging;
    private long batteryUpdatedAtMs;

    private final Map<String, MetricSample> latestMetrics = new LinkedHashMap<>();
    private final Map<String, CategorySample> latestCategories = new LinkedHashMap<>();
    private final Map<String, Map<UUID, CumulativeSample>> cumulativeByType = new LinkedHashMap<>();

    private record HealthContext(UUID pair, UUID local, UUID peer, UUID nrUuid, String peerBuild) {}
    private record MetricSample(String identifier, double value, String unit, long timestampMs, double endRefSec) {}
    private record CategorySample(String identifier, long value, long timestampMs, double endRefSec) {}
    private record CumulativeSample(double value, String unit, long timestampMs) {}

    LinuxHealthController(
            Path stateDir,
            DesktopSecretStore secrets,
            LinuxIdentityStore identityStore,
            BlockingQueue<ProtocolHost.OutboundAppMessage> outbound,
            AtomicBoolean stop) {
        this.healthDir = stateDir.resolve("health");
        this.secrets = secrets;
        this.identityStore = identityStore;
        this.outbound = outbound;
        this.stop = stop;
        this.peerStore = new HealthPeerIdentityStore(healthDir.resolve("peer-identity"));
        this.syncStore = new HealthSyncStateStore(healthDir.resolve("sync-state"));
        ProtocolHost.setHealthPullHandler(this::triggerPull);
        writeSnapshotQuietly();
    }

    void triggerPull() {
        executor.execute(() -> pullNow(true));
    }

    /**
     * Returns true ONLY for private Health IPC frames that must be consumed
     * inside the JVM without reaching stdout or protocol.log.
     * Application events (BridgeApplicationEventCodec.PREFIX) are observed
     * asynchronously and return false so DesktopApplicationEventProjector still
     * forwards battery/settings/app state to Rust/Flutter.
     */
    boolean handleLine(String line) {
        if (line.startsWith(HealthPeerIdentityCodec.PREFIX)) {
            String encoded = line.substring(HealthPeerIdentityCodec.PREFIX.length());
            executor.execute(() -> onPeerIdentity(encoded));
            return true;
        }
        if (line.startsWith(HealthRegistryObservationCodec.PREFIX)) {
            String encoded = line.substring(HealthRegistryObservationCodec.PREFIX.length());
            executor.execute(() -> onRegistryObservation(encoded));
            return true;
        }
        if (line.startsWith(HealthDataEventCodec.PREFIX)) {
            String encoded = line.substring(HealthDataEventCodec.PREFIX.length());
            executor.execute(() -> onHealthData(encoded));
            return true;
        }
        if (line.startsWith(HealthOutboundIpcCodec.STATUS_PREFIX)) {
            String encoded = line.substring(HealthOutboundIpcCodec.STATUS_PREFIX.length());
            executor.execute(() -> onSendStatus(encoded));
            return true;
        }
        if (line.startsWith(BridgeApplicationEventCodec.PREFIX)) {
            String encoded = line.substring(BridgeApplicationEventCodec.PREFIX.length());
            executor.execute(() -> onAppEvent(encoded));
            return false;
        }
        return false;
    }

    private void onAppEvent(String encoded) {
        byte[] frame = null, payload = null;
        try {
            if (encoded.length() > 131_072) return;
            frame = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
            try (var event = BridgeApplicationEventCodec.decode(frame)) {
                if (NanoSystemSettingsDiagnostics.TOPIC.equals(event.topic)
                        && event.protobufType == NanoSystemSettingsDiagnostics.ABOUT_RESPONSE
                        && event.response) {
                    payload = event.payload();
                    var about = NanoSystemSettingsDiagnostics.decodeAbout(payload);
                    if (about.batteryCapacity() != null) {
                        long cap = about.batteryCapacity();
                        if (cap >= 0 && cap <= 100) {
                            batteryPercentage = (int) cap;
                        }
                    }
                    if (about.charging() != null) {
                        batteryCharging = about.charging();
                    }
                    batteryUpdatedAtMs = System.currentTimeMillis();
                    lastEvent = "BATTERY_OBSERVED";
                    ProtocolHost.log("LINUX HEALTH battery observed: percent=" + batteryPercentage + " charging=" + batteryCharging);
                    writeSnapshotQuietly();
                }
            }
        } catch (Exception e) {
            ProtocolHost.log("LINUX HEALTH app event decode skipped: " + e.getClass().getSimpleName());
        } finally {
            wipe(frame);
            wipe(payload);
        }
    }

    private void requestDeviceAbout() {
        if (stop.get()) return;
        var outgoing = new ProtocolHost.OutboundAppMessage(
                NanoSystemSettingsDiagnostics.TOPIC,
                NanoSystemSettingsDiagnostics.ABOUT_REQUEST,
                new byte[0]);
        boolean queued = ProtocolHost.offerOutbound(outbound, outgoing);
        ProtocolHost.log("LINUX HEALTH battery query queued=" + queued);
    }

    private void ensurePeriodicPullScheduled() {
        if (periodicTask != null || stop.get()) return;
        periodicTask = executor.scheduleWithFixedDelay(
                () -> pullNow(false),
                PERIODIC_PULL_MINUTES,
                PERIODIC_PULL_MINUTES,
                TimeUnit.MINUTES);
    }

    private void pullNow(boolean manual) {
        if (stop.get() || currentEpoch == null || peerEpoch == null || registryObservation == null) return;
        long now = System.currentTimeMillis();
        if (manual && (now - lastPullAtMs) < MIN_PULL_GAP_MS) {
            requestDeviceAbout();
            return;
        }
        try {
            var ctx = readContext();
            var profile = syncStore.read(ctx.pair, ctx.local, ctx.peer);
            requestDeviceAbout();
            if (profile != null && profile.phase() == HealthSyncStateStore.Phase.REMOTE_FINISHED) {
                sendChangesPull(ctx, profile);
            } else {
                beginInitialHealthRestore();
            }
        } catch (Exception e) {
            ProtocolHost.log("LINUX HEALTH pull skipped: " + e.getClass().getSimpleName());
        }
    }

    private HealthContext readContext() throws Exception {
        byte[] bytes = secrets.load("pairing.sealed");
        PairingSessionRecord record = null;
        try {
            record = PairingSessionRecord.parse(bytes);
            String pairId = OperationalSessionPolicy.pairingId(record);
            OperationalSessionPolicy.requireMatchingActivatedPair(record, pairId);
            return new HealthContext(
                    UUID.fromString(pairId),
                    UUID.fromString(record.localIdsDeviceUuid()),
                    UUID.fromString(record.peerIdsDeviceId()),
                    UUID.fromString(record.nrUuid()),
                    record.peerBuildVersion());
        } finally {
            if (record != null) record.destroy();
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void onPeerIdentity(String encoded) {
        byte[] bytes = null;
        try {
            if (stop.get() || encoded.length() > ((HealthPeerIdentityCodec.MAX_FRAME + 2) / 3) * 4) return;
            bytes = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
            var ctx = readContext();
            try (var identity = HealthPeerIdentityCodec.decode(bytes)) {
                peerStore.store(identity, ctx.pair, identity.epoch, ctx.local, ctx.peer);
                currentEpoch = identity.epoch;
                peerEpoch = identity.epoch;
                build = ctx.peerBuild;
                lastEvent = "PEER_SAVED";
            }
            ProtocolHost.log("LINUX HEALTH peer identity saved; values logged=false.");
            writeSnapshotQuietly();
            beginInitialHealthRestore();
        } catch (Exception e) {
            lastEvent = "PEER_REJECTED:" + e.getClass().getSimpleName();
            ProtocolHost.log("LINUX HEALTH peer identity rejected: " + e.getClass().getSimpleName());
            writeSnapshotQuietly();
        } finally { wipe(bytes); }
    }

    private void onRegistryObservation(String encoded) {
        byte[] frame = null;
        try {
            if (stop.get() || encoded.length() > ((HealthRegistryObservationCodec.MAX_FRAME + 2) / 3) * 4) return;
            frame = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
            var observation = HealthRegistryObservationCodec.decode(frame);
            var ctx = readContext();
            observation.requireContext(ctx.pair, observation.epoch(), ctx.local, ctx.peer);
            registryObservation = observation;
            currentEpoch = observation.epoch();
            product = observation.product();
            build = observation.build() != null ? observation.build() : ctx.peerBuild;
            lastEvent = "REGISTRY_OBSERVED";
            ProtocolHost.log("LINUX HEALTH registry observed: product=" + product + " build=" + build);
            writeSnapshotQuietly();
            requestDeviceAbout();
            beginInitialHealthRestore();
        } catch (Exception e) {
            lastEvent = "REGISTRY_REJECTED:" + e.getClass().getSimpleName();
            ProtocolHost.log("LINUX HEALTH registry observation rejected: " + e.getClass().getSimpleName());
            writeSnapshotQuietly();
        } finally { wipe(frame); }
    }

    private void beginInitialHealthRestore() {
        byte[] peerKey = null, plaintext = null, encrypted = null;
        try {
            if (stop.get() || currentEpoch == null || peerEpoch == null || registryObservation == null) return;
            if (!currentEpoch.equals(peerEpoch) || !currentEpoch.equals(registryObservation.epoch())) return;
            var ctx = readContext();
            var binding = NativeHealthRegistryBinding.bind(
                    ctx.pair, currentEpoch, ctx.local, ctx.peer, registryObservation, ctx.nrUuid, ctx.peerBuild);
            peerKey = peerStore.read(ctx.pair, ctx.local, ctx.peer);
            if (peerKey == null) return;
            var existing = syncStore.initialize(
                    ctx.pair, ctx.local, ctx.peer, binding.registry(), binding.product(), binding.build(), UUID::randomUUID);
            restorePhase = existing.phase().name();
            if (existing.phase() == HealthSyncStateStore.Phase.REMOTE_FINISHED) {
                ensurePeriodicPullScheduled();
                writeSnapshotQuietly();
                sendChangesPull(ctx, existing);
                return;
            }
            if (existing.epoch() != null && existing.epoch().equals(currentEpoch)) {
                writeSnapshotQuietly();
                return;
            }
            long deadline = BridgeClock.elapsedRealtime() + 60_000L;
            var prepared = syncStore.prepare(
                    ctx.pair, ctx.local, ctx.peer, currentEpoch, deadline, UUID.randomUUID(), UUID.randomUUID());
            restorePhase = prepared.phase().name();
            plaintext = NativeHealthInitialRestoreCodec.encode(prepared);
            encrypted = identityStore.encryptHealthData(plaintext, peerKey);
            var header = new HealthOutboundIpcCodec.Header(
                    ctx.pair, ctx.local, ctx.peer, prepared.epoch(), prepared.message(), prepared.deadline());
            var outgoing = new ProtocolHost.OutboundAppMessage(
                    IdsApplicationRoute.HEALTH_SYNC_SERVICE, 1, encrypted, true, null, header);
            boolean queued = ProtocolHost.offerOutbound(outbound, outgoing);
            lastEvent = queued ? "RESTORE_QUEUED" : "RESTORE_QUEUE_FULL";
            ProtocolHost.log("LINUX HEALTH initial Restore queued=" + queued + " product=" + binding.product() + " build=" + binding.build());
            writeSnapshotQuietly();
        } catch (Exception e) {
            lastEvent = "RESTORE_ERR:" + e.getClass().getSimpleName() + ":" + safeReason(e.getMessage());
            ProtocolHost.log("LINUX HEALTH initial Restore unavailable: " + e.getClass().getSimpleName());
            writeSnapshotQuietly();
        } finally { wipe(peerKey); wipe(plaintext); wipe(encrypted); }
    }

    private void sendChangesPull(HealthContext ctx, HealthSyncStateStore.State profile) {
        byte[] peerKey = null, plaintext = null, encrypted = null;
        try {
            if (stop.get() || currentEpoch == null || peerEpoch == null || registryObservation == null) return;
            if (!currentEpoch.equals(peerEpoch) || !currentEpoch.equals(registryObservation.epoch())) return;
            long now = System.currentTimeMillis();
            if ((now - lastPullAtMs) < MIN_PULL_GAP_MS) return;
            peerKey = peerStore.read(ctx.pair, ctx.local, ctx.peer);
            if (peerKey == null) return;
            long deadline = BridgeClock.elapsedRealtime() + 60_000L;
            var prepared = new HealthChangesSessionStore.State(
                    ctx.pair, ctx.local, ctx.peer, profile.identity(),
                    UUID.randomUUID(), UUID.randomUUID(), currentEpoch,
                    now / 1000.0 - APPLE_EPOCH_OFFSET_SEC,
                    deadline, HealthChangesSessionStore.Phase.PREPARED, null, 0);
            lastPullAtMs = now;
            changesPhase = prepared.phase().name();
            plaintext = NativeHealthInitialChangesCodec.encode(prepared);
            encrypted = identityStore.encryptHealthData(plaintext, peerKey);
            var header = new HealthOutboundIpcCodec.Header(
                    ctx.pair, ctx.local, ctx.peer, prepared.epoch(), prepared.message(), prepared.deadline());
            var outgoing = new ProtocolHost.OutboundAppMessage(
                    IdsApplicationRoute.HEALTH_SYNC_SERVICE, 1, encrypted, true, null, header);
            boolean queued = ProtocolHost.offerOutbound(outbound, outgoing);
            lastEvent = queued ? "CHANGES_QUEUED" : "CHANGES_QUEUE_FULL";
            ProtocolHost.log("LINUX HEALTH Changes pull queued=" + queued);
            writeSnapshotQuietly();
        } catch (Exception e) {
            lastEvent = "CHANGES_ERR:" + e.getClass().getSimpleName() + ":" + safeReason(e.getMessage());
            ProtocolHost.log("LINUX HEALTH Changes pull unavailable: " + e.getClass().getSimpleName());
            writeSnapshotQuietly();
        } finally { wipe(peerKey); wipe(plaintext); wipe(encrypted); }
    }

    private void onSendStatus(String encoded) {
        byte[] bytes = null;
        try {
            if (encoded.length() > 128) return;
            bytes = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
            var ctx = readContext();
            var receipt = BridgeCommandCodec.decodeStatus(bytes);
            var state = syncStore.receipt(ctx.pair, ctx.local, ctx.peer, receipt);
            restorePhase = state.phase().name();
            lastEvent = "SEND_RECEIPT:" + receipt.stage();
            ProtocolHost.log("LINUX HEALTH send receipt=" + receipt.stage() + " restore=" + restorePhase);
            writeSnapshotQuietly();
        } catch (Exception e) {
            ProtocolHost.log("LINUX HEALTH send receipt ignored: " + e.getClass().getSimpleName());
        } finally { wipe(bytes); }
    }

    private void onHealthData(String encoded) {
        byte[] frameBytes = null, encrypted = null, peerKey = null, plaintext = null;
        try {
            if (encoded.length() > ((HealthDataEventCodec.MAX_FRAME + 2) / 3) * 4) return;
            frameBytes = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
            var ctx = readContext();
            try (var event = HealthDataEventCodec.decode(frameBytes)) {
                inboundFrames++;
                encrypted = event.encrypted();
                peerKey = event.peerKey();
                if (peerKey == null) peerKey = peerStore.read(ctx.pair, ctx.local, ctx.peer);
                if (peerKey == null) {
                    lastEvent = "DATA_NO_PEER_KEY";
                    writeSnapshotQuietly();
                    return;
                }
                plaintext = identityStore.decryptHealthData(encrypted, peerKey);
                try (var nativeFrame = NativeHealthSyncCodec.decodePlaintext(plaintext, event.responseTo != null)) {
                    lastEvent = "FRAME:" + NativeHealthSyncCodec.messageName(nativeFrame.messageId) + ":resp=" + nativeFrame.response;
                    if (nativeFrame.messageId == 1 || nativeFrame.messageId == 2 || nativeFrame.messageId == 7) {
                        try (var envelope = nativeFrame.envelope()) {
                            var identity = envelope.requireIdentity();
                            try (var restore = envelope.restore()) {
                                if (restore != null && nativeFrame.messageId == 1 && nativeFrame.response) {
                                    var header = NativeHealthRestoreCodec.requireHeader(restore);
                                    var state = syncStore.response(
                                            ctx.pair, ctx.local, ctx.peer, event.epoch, event.responseTo,
                                            BridgeClock.elapsedRealtime(), identity, header);
                                    restorePhase = state.phase().name();
                                    ProtocolHost.log("LINUX HEALTH Restore response phase=" + restorePhase + " status=" + header.status());
                                    if (state.phase() == HealthSyncStateStore.Phase.REMOTE_FINISHED) {
                                        ensurePeriodicPullScheduled();
                                        sendChangesPull(ctx, state);
                                    }
                                }
                            }
                            try (var changes = envelope.changeSet(); var status = envelope.status()) {
                                if (changes != null) {
                                    extractChanges(changes, ctx.peerBuild);
                                }
                                if (status != null && nativeFrame.messageId == 2 && nativeFrame.response
                                        && NativeHealthInitialChangesCodec.statusOnly(envelope)) {
                                    changesPhase = "REMOTE_STATUS";
                                }
                            }
                        }
                    }
                    ProtocolHost.log("LINUX HEALTH decoded frame=" + nativeFrame.messageId
                            + " qty=" + quantitySamples + " cat=" + categorySamples
                            + " workouts=" + workoutSamples + " metrics=" + latestMetrics.size());
                }
            }
            writeSnapshotQuietly();
        } catch (Exception e) {
            lastEvent = "DATA_ERR:" + e.getClass().getSimpleName();
            ProtocolHost.log("LINUX HEALTH data decode failed: " + e.getClass().getSimpleName());
            writeSnapshotQuietly();
        } finally {
            wipe(frameBytes);
            wipe(encrypted);
            wipe(peerKey);
            wipe(plaintext);
        }
    }

    private void extractChanges(NativeHealthChangesCodec.Node changeSet, String peerBuild) {
        for (var change : changeSet.children(1)) {
            var schema = NativeHealthObjectCodec.schemaFor(change);
            if (schema == null) continue;
            for (int i = 0; i < change.byteCount(4); i++) {
                byte[] data = change.bytesAt(4, i);
                try (var object = NativeHealthChangesCodec.decode(schema, data)) {
                    decodedObjects++;
                    var counts = NativeHealthObjectCodec.counts(object);
                    quantitySamples += counts.quantitySamples();
                    categorySamples += counts.categorySamples();
                    workoutSamples += counts.workoutSamples();
                    unknownFields += counts.unknownFields();
                    if (schema == NativeHealthChangesCodec.Schema.OBJECT_COLLECTION) {
                        for (var qty : object.children(4)) {
                            extractQuantity(qty, peerBuild);
                        }
                        for (var cat : object.children(3)) {
                            extractCategory(cat, peerBuild);
                        }
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unsupported/opaque object; skip safely without failing other objects.
                } finally { wipe(data); }
            }
        }
    }

    private void extractQuantity(NativeHealthChangesCodec.Node qty, String peerBuild) {
        var samples = qty.children(1);
        if (samples.isEmpty()) return;
        var header = NativeHealthObjectCodec.header(samples.get(0));
        if (!header.structurallyComplete()) return;
        var def = NativeHealthTypeCatalog.lookup(peerBuild, header.dataType());
        if (def == null || !def.quantity()) return;

        Double canonical = qty.doubleValue(2);
        Double originalVal = qty.doubleValue(3);
        String originalUnit = qty.string(4);
        Double value = null;
        String unit = null;
        if (canonical != null && Double.isFinite(canonical)) {
            value = canonical;
            unit = def.canonicalUnit();
        } else if (originalVal != null && Double.isFinite(originalVal)) {
            value = originalVal;
            unit = originalUnit != null ? originalUnit : def.canonicalUnit();
        } else {
            var series = qty.children(12);
            if (!series.isEmpty()) {
                Double last = series.get(series.size() - 1).doubleValue(2);
                if (last != null && Double.isFinite(last)) {
                    value = last;
                    unit = def.canonicalUnit();
                }
            }
        }
        if (value == null) return;

        double displayVal = value;
        String displayUnit = unit != null ? unit : "";
        if ("count/s".equals(displayUnit)) {
            displayVal = value * 60.0;
            displayUnit = def.identifier().contains("HeartRate") ? "bpm" : "count/min";
        } else if ("count/min".equals(displayUnit) && def.identifier().contains("HeartRate")) {
            displayUnit = "bpm";
        } else if ("%".equals(displayUnit) && value >= 0.0 && value <= 1.0) {
            displayVal = value * 100.0;
        }

        long tsMs = Math.round((header.endReferenceSeconds() + APPLE_EPOCH_OFFSET_SEC) * 1000.0);
        MetricSample prev = latestMetrics.get(def.identifier());
        if (prev == null || header.endReferenceSeconds() >= prev.endRefSec) {
            latestMetrics.put(def.identifier(), new MetricSample(
                    def.identifier(), displayVal, displayUnit, tsMs, header.endReferenceSeconds()));
        }
        if (CUMULATIVE_TODAY_TYPES.contains(def.identifier())) {
            cumulativeByType
                    .computeIfAbsent(def.identifier(), k -> new HashMap<>())
                    .put(header.uuid(), new CumulativeSample(displayVal, displayUnit, tsMs));
        }
    }

    private void extractCategory(NativeHealthChangesCodec.Node cat, String peerBuild) {
        var samples = cat.children(1);
        if (samples.isEmpty()) return;
        var header = NativeHealthObjectCodec.header(samples.get(0));
        if (!header.structurallyComplete()) return;
        var def = NativeHealthTypeCatalog.lookup(peerBuild, header.dataType());
        if (def == null || !def.category()) return;
        Long catVal = cat.int64(2);
        if (catVal == null) return;
        long tsMs = Math.round((header.endReferenceSeconds() + APPLE_EPOCH_OFFSET_SEC) * 1000.0);
        CategorySample prev = latestCategories.get(def.identifier());
        if (prev == null || header.endReferenceSeconds() >= prev.endRefSec) {
            latestCategories.put(def.identifier(), new CategorySample(
                    def.identifier(), catVal, tsMs, header.endReferenceSeconds()));
        }
    }

    private void writeSnapshotQuietly() {
        try {
            Files.createDirectories(healthDir);
            if (Files.getFileStore(healthDir).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(healthDir, PosixFilePermissions.fromString("rwx------"));
            }
            ZoneId zone = ZoneId.systemDefault();
            long startOfTodayMs = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();

            StringBuilder sb = new StringBuilder();
            sb.append('{');
            sb.append("\"updatedAtMs\":").append(System.currentTimeMillis());
            sb.append(",\"product\":").append(jsonStr(product));
            sb.append(",\"build\":").append(jsonStr(build));
            sb.append(",\"restorePhase\":").append(jsonStr(restorePhase));
            sb.append(",\"changesPhase\":").append(jsonStr(changesPhase));
            sb.append(",\"lastEvent\":").append(jsonStr(lastEvent));
            sb.append(",\"inboundFrames\":").append(inboundFrames);
            sb.append(",\"decodedObjects\":").append(decodedObjects);
            sb.append(",\"quantitySamples\":").append(quantitySamples);
            sb.append(",\"categorySamples\":").append(categorySamples);
            sb.append(",\"workoutSamples\":").append(workoutSamples);
            sb.append(",\"unknownFields\":").append(unknownFields);

            sb.append(",\"battery\":");
            if (batteryPercentage != null || batteryCharging != null) {
                sb.append('{')
                        .append("\"percentage\":").append(batteryPercentage == null ? "null" : batteryPercentage)
                        .append(",\"charging\":").append(batteryCharging == null ? "null" : batteryCharging)
                        .append(",\"updatedAtMs\":").append(batteryUpdatedAtMs)
                        .append('}');
            } else {
                sb.append("null");
            }

            sb.append(",\"todayTotals\":{");
            boolean firstTot = true;
            for (var entry : cumulativeByType.entrySet()) {
                double sum = 0.0;
                int count = 0;
                String unit = "";
                for (var s : entry.getValue().values()) {
                    if (s.timestampMs >= startOfTodayMs) {
                        sum += s.value;
                        count++;
                        if (unit.isEmpty()) unit = s.unit;
                    }
                }
                if (count == 0) continue;
                if (!firstTot) sb.append(',');
                firstTot = false;
                sb.append(jsonStr(entry.getKey())).append(":{")
                        .append("\"value\":").append(String.format(Locale.ROOT, "%.2f", sum))
                        .append(",\"unit\":").append(jsonStr(unit))
                        .append(",\"samples\":").append(count)
                        .append('}');
            }
            sb.append('}');

            sb.append(",\"metrics\":{");
            boolean first = true;
            for (var entry : latestMetrics.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                MetricSample m = entry.getValue();
                sb.append(jsonStr(m.identifier())).append(":{")
                        .append("\"value\":").append(String.format(Locale.ROOT, "%.2f", m.value()))
                        .append(",\"unit\":").append(jsonStr(m.unit()))
                        .append(",\"timestampMs\":").append(m.timestampMs())
                        .append('}');
            }
            sb.append('}');

            sb.append(",\"categories\":{");
            boolean firstCat = true;
            for (var entry : latestCategories.entrySet()) {
                if (!firstCat) sb.append(',');
                firstCat = false;
                CategorySample c = entry.getValue();
                sb.append(jsonStr(c.identifier())).append(":{")
                        .append("\"value\":").append(c.value())
                        .append(",\"timestampMs\":").append(c.timestampMs())
                        .append('}');
            }
            sb.append("}}\n");

            Path tmp = healthDir.resolve("health-latest.json.tmp");
            Path dst = healthDir.resolve("health-latest.json");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            }
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    private static String jsonStr(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String safeReason(String msg) {
        if (msg == null) return "";
        String clean = msg.replaceAll("[^A-Za-z0-9 _.:/\\-]", "");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    @Override
    public void close() {
        ProtocolHost.setHealthPullHandler(null);
        if (periodicTask != null) periodicTask.cancel(true);
        executor.shutdownNow();
    }
}
