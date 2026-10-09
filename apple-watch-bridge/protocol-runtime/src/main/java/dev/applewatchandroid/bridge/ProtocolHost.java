package dev.applewatchandroid.bridge;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared protocol coordinator; no platform controller or OS services. */
class ProtocolHost {
    static final AtomicBoolean COMPANION_DISCOVERY = new AtomicBoolean();
    static final BlockingQueue<String> COMPANION_WATCH_SELECTIONS = new LinkedBlockingQueue<>(1);
    /**
     * SEED_STALE_FLOW commands queued by the command reader and drained on
     * the HAL thread next to the outbound app-message queue. A seeded flow
     * is persisted and immediately reset so the Watch identityservicesd
     * retries its ids-control-channel without waiting out its TCP RTO.
     */
    static final BlockingQueue<PendingStaleFlowSeed>
            PENDING_STALE_FLOW_SEEDS =
                    new LinkedBlockingQueue<>();

    /** Command-line seed for one Watch flow observed in an earlier run. */
    static final class PendingStaleFlowSeed {
        final OrdinaryIkeAuth.DataClass dataClass;
        final int localPort;
        final int remotePort;
        final long peerAck;
        final long peerTimestamp;
        final long lastSeenEpochMs;

        PendingStaleFlowSeed(
                OrdinaryIkeAuth.DataClass dataClass,
                int localPort,
                int remotePort,
                long peerAck,
                long peerTimestamp,
                long lastSeenEpochMs) {
            this.dataClass = dataClass;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.peerAck = peerAck;
            this.peerTimestamp = peerTimestamp;
            this.lastSeenEpochMs = lastSeenEpochMs;
        }
    }

    static final long HAL_INIT_TIMEOUT_MS = 15_000;
    static final long COMMAND_TIMEOUT_MS = 5_000;
    static final long SCAN_TIMEOUT_MS = 60_000;
    static final long CONNECT_TIMEOUT_MS = 10_000;
    static final long BT_CL_TIMEOUT_MS = 5_000;
    /** Window the Watch gets to answer the activation permit with ProxyActivation. */
    static final long ACTIVATION_REQUEST_GRACE_MS = 20_000;
    /** Window for collecting competing Apple proximity beacons before locking one. */
    /** Apple devices probed for terminusPairing before giving up on the scan. */
    static final int MAX_SETUP_CANDIDATES = 4;
    static final long IKE_SA_INIT_TIMEOUT_MS = 10_000;
    static final long IKE_INTERMEDIATE_TIMEOUT_MS = 20_000;
    static final long IKE_AUTH_TIMEOUT_MS = 15_000;
    static final long PIN_AUTH_METHOD_TIMEOUT_MS = 15_000;
    static final long PIN_INPUT_TIMEOUT_MS = 600_000;
    static final long PAIRING_IKE_TIMEOUT_MS = 25_000;
    static final long PAIRING_IKE_ACK_TIMEOUT_MS = 5_000;
    static final long SMP_RESPONSE_TIMEOUT_MS = 10_000;
    static final long SMP_ENCRYPTION_TIMEOUT_MS = 10_000;
    static final long MODERN_REGISTRATION_TIMEOUT_MS =
            40_000;
    static final long IDS_BOOTSTRAP_TIMEOUT_MS =
            120_000;
    static final long BOND_STORE_TIMEOUT_MS = 15_000;
    static final long PAIRING_SESSION_STORE_TIMEOUT_MS =
            15_000;
    static final long LOCAL_IDENTITY_INPUT_TIMEOUT_MS =
            5_000;
    static final long DISCONNECT_TIMEOUT_MS = 3_000;
    static final boolean TERMINUS_FCS_ENABLED = false;
    static final String BOND_SECRET_PREFIX =
            "BOND_SECRET_V1:";
    static final String PAIRING_SESSION_PREFIX =
            "PAIRING_SESSION_V2:";
    static final String RESTORED_PAIRING_SESSION_PREFIX =
            "RESTORED_PAIRING_SESSION_V2:";
    static final String LOCAL_IDENTITY_PREFIX =
            "LOCAL_IDENTITY_V1:";
    static final String LOCAL_IDS_PUBLIC_PREFIX = "LOCAL_IDS_PUBLIC_KEYS_V1:";

    static final class OutboundAppMessage {
        final String topic;
        final int protobufType;
        final byte[] payload;
        final boolean dataFrame;
        final BridgeCommandCodec.Request request;
        final HealthOutboundIpcCodec.Header health;

        OutboundAppMessage(String topic, int protobufType, byte[] payload) {
            this(topic, protobufType, payload, false);
        }

        OutboundAppMessage(String topic, int protobufType, byte[] payload, boolean dataFrame) {
            this(topic, protobufType, payload, dataFrame, null);
        }
        OutboundAppMessage(String topic, int protobufType, byte[] payload, boolean dataFrame,
                BridgeCommandCodec.Request request) {
            this(topic,protobufType,payload,dataFrame,request,null);
        }
        OutboundAppMessage(String topic,int protobufType,byte[] payload,boolean dataFrame,
                BridgeCommandCodec.Request request,HealthOutboundIpcCodec.Header health) {
            this.topic = topic;
            this.protobufType = protobufType;
            this.payload = payload == null ? null : payload.clone();
            this.dataFrame = dataFrame;
            this.request = request;
            this.health = health;
        }
    }

    static final OperationalRequestTracker OPERATIONAL_REQUESTS =
            new OperationalRequestTracker(256, ProtocolHost::publishCommandStatus);
    static final BlockingQueue<FindMyPhoneIpcCodec.Result> FIND_MY_PHONE_RESULTS =
            new java.util.concurrent.ArrayBlockingQueue<>(16);

    static void publishCommandStatus(BridgeCommandCodec.Status status) {
        byte[] frame = BridgeCommandCodec.encode(status);
        try {
            System.out.println(BridgeCommandCodec.STATUS_PREFIX + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
            System.out.flush();
        } finally { Arrays.fill(frame, (byte) 0); }
    }

    static void publishHealthSendStatus(HealthOutboundIpcCodec.Header header,BridgeCommandCodec.Stage stage) {
        byte[] frame=BridgeCommandCodec.encode(new BridgeCommandCodec.Status(header.message(),header.epoch(),stage));
        try { System.out.println(HealthOutboundIpcCodec.STATUS_PREFIX+BridgeBase64.encodeToString(frame,BridgeBase64.NO_WRAP));System.out.flush(); }
        finally { Arrays.fill(frame,(byte)0); }
    }

    static void queueStructuredRequest(BridgeCommandCodec.Request request,
            BlockingQueue<OutboundAppMessage> queue) {
        byte[] payload = null;
        OutboundAppMessage message = null;
        try {
            String topic;
            int type;
            String command = request.command();
            if (command.equals("REQUEST_REGISTRY")) {
                topic = NanoRegistryPropertyCodec.CLASS_C_SERVICE;
                type = NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST;
                payload = NanoRegistryPropertyCodec.encode(new NanoRegistryPropertyCodec.PropertyRequest());
            } else if (command.equals("REQUEST_DEVICE_ABOUT")) {
                topic = NanoSystemSettingsDiagnostics.TOPIC;
                type = NanoSystemSettingsDiagnostics.ABOUT_REQUEST;
                payload = new byte[0];
            } else if (command.equals(NativeWatchReboot.COMMAND)) {
                topic = NativeWatchReboot.TOPIC;
                type = NativeWatchReboot.TYPE;
                payload = new byte[0];
            } else if (command.equals(SysdiagnoseArchiveInventory.COMMAND)) {
                topic = SysdiagnoseArchiveInventory.TOPIC;
                type = SysdiagnoseArchiveInventory.LIST_REQUEST;
                payload = request.id().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            } else if (command.equals(SysdiagnoseCollection.COMMAND)) {
                topic = SysdiagnoseArchiveInventory.TOPIC;
                type = SysdiagnoseCollection.START;
                payload = request.id().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            } else if (command.equals("REQUEST_FACE_COLLECTION")) {
                topic = IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE;
                type = ClockFaceSyncHeaderCodec.FULL_REQUEST;
                payload = new byte[0]; // Identity/counter/time reserved on HAL send thread.
            } else if (command.equals(WifiNetworkSyncCodec.COMMAND)) {
                topic = WifiNetworkSyncCodec.TOPIC;
                type = -1;
                payload = new byte[0]; // Credentials read only on the authenticated HAL send thread.
            } else if (ClockFaceDeltaCommand.matches(command)) {
                ClockFaceDeltaCommand.parse(command);
                topic = IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE;
                type = ClockFaceSyncAccept.START;
                payload = new byte[0]; // Derived only from a committed snapshot on the HAL send thread.
            } else if (command.equals("PING_WATCH")) {
                topic = IdsApplicationRoute.FIND_MY_LOCAL_SERVICE;
                type = FindMyLocalDeviceCodec.TYPE_PLAY_SOUND;
                payload = new byte[0]; // Timestamp is generated on the HAL send thread.
            } else if (PigmentPreferenceCommand.matches(command)) {
                PigmentPreferenceCommand.parse(command);
                topic = IdsApplicationRoute.PREFERENCE_SYNC_SERVICE; type = 0;
                payload = new byte[0]; // Rechecked/derived from the owned Watch observation on the send thread.
            } else if (MonogramPreferenceCommand.matches(command)) {
                MonogramPreferenceCommand.parse(command);
                topic = IdsApplicationRoute.PREFERENCE_SYNC_SERVICE; type = 0;
                payload = new byte[0]; // Durable native preference is prepared only on the owned send thread.
            } else if (command.startsWith(WatchSettingsCodec.COMMAND_PREFIX)) {
                topic = IdsApplicationRoute.PREFERENCE_SYNC_SERVICE; type = 0;
                payload = WatchSettingsCodec.encode(WatchSettingsCodec.parseCommand(command), System.currentTimeMillis());
            } else if (command.startsWith("SET_ACTIVE_FACE:")) {
                throw new IllegalArgumentException("Native face collection command is not implemented");
            } else if (command.startsWith("REMOVE_BULLETIN:")) {
                topic = IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE;
                type = BulletinDistributorCodec.TYPE_REMOVE_BULLETIN;
                payload = BridgeBase64.decode(command.substring("REMOVE_BULLETIN:".length()), BridgeBase64.NO_WRAP);
                BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(payload);
            } else {
                topic = IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE;
                type = BulletinDistributorCodec.TYPE_ADD_BULLETIN;
                payload = BridgeBase64.decode(command.substring("SEND_BULLETIN:".length()), BridgeBase64.NO_WRAP);
            }
            message = new OutboundAppMessage(topic, type, payload, command.equals("REQUEST_FACE_COLLECTION")
                    || command.equals(WifiNetworkSyncCodec.COMMAND) || ClockFaceDeltaCommand.matches(command), request);
            OutboundAppMessage outgoing = message;
            boolean accepted = OPERATIONAL_REQUESTS.accept(request, BridgeClock.elapsedRealtime(),
                    () -> offerOutbound(queue, outgoing));
            if (!accepted) Arrays.fill(message.payload, (byte) 0);
        } catch (RuntimeException invalid) {
            if (message != null) Arrays.fill(message.payload, (byte) 0);
            publishCommandStatus(new BridgeCommandCodec.Status(request.id(), request.epoch(), BridgeCommandCodec.Stage.REJECTED));
        } finally { if (payload != null) Arrays.fill(payload, (byte) 0); }
    }

    static boolean offerOutbound(BlockingQueue<OutboundAppMessage> queue, OutboundAppMessage message) {
        if (queue.offer(message)) return true;
        if (message.payload != null) Arrays.fill(message.payload, (byte) 0);
        log("COMMAND INPUT REJECTED: bounded outbound queue is full; values logged=false.");
        return false;
    }

    private static final long HOST_START_TIME_MS = BridgeClock.elapsedRealtime();
    private static volatile long lastLogTimeMs = HOST_START_TIME_MS;

    static void log(String message) {
        long now = BridgeClock.elapsedRealtime();
        long totalElapsed = now - HOST_START_TIME_MS;
        long delta = now - lastLogTimeMs;
        lastLogTimeMs = now;
        System.out.println(String.format(
                Locale.US,
                "[WatchHal] [T+%05dms|Δ%04dms] %s",
                totalElapsed,
                delta,
                message));
        System.out.flush();
    }

    static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "(no message)" : message;
    }

    /**
     * Owner override: Albert returned 200 with a full activation record and
     * the Watch never reported a rejection, but also never declared
     * PBBProtoActivationSucceeded, so the post-commit coordinator parks in
     * "awaiting ProxyActivation" while the Watch waits for the setup drive.
     * The next poll checkpoints ACTIVATION_CONFIRMED to resume the flow.
     */
    static final java.util.concurrent.atomic.AtomicBoolean
            PENDING_FORCE_ACTIVATION_CONFIRMED =
            new java.util.concurrent.atomic.AtomicBoolean();

    static void requestForceActivationConfirmed() {
        PENDING_FORCE_ACTIVATION_CONFIRMED.set(true);
    }

    /**
     * Diagnostic: republish the PairedSync completion defaults on the live
     * session, bypassing the coordinator's one-shot send guard. Used to probe
     * whether a repeated publication makes the Watch apply the state and fire
     * its sync-completion chain.
     */
    static final java.util.concurrent.atomic.AtomicBoolean
            PENDING_PUBLISH_PAIRED_SYNC =
            new java.util.concurrent.atomic.AtomicBoolean();

    static final AtomicBoolean PENDING_CLOSE_IKE_SESSION = new AtomicBoolean();
    static final AtomicBoolean PENDING_IDS_KEY_PROBE = new AtomicBoolean();
    static final AtomicBoolean PENDING_IDS_CONTROL_OPEN = new AtomicBoolean();
    static final java.util.concurrent.atomic.AtomicReference<NativeApplicationServiceDiscovery>
            PENDING_NATIVE_SNAPSHOT_DISCOVERY = new java.util.concurrent.atomic.AtomicReference<>();

    static void requestPublishPairedSync() {
        PENDING_PUBLISH_PAIRED_SYNC.set(true);
    }

    /**
     * Diagnostic: queue one PBBridge type-19 sync progress/state update
     * (progress in [0,1], state uint32) for the live session.
     */
    static final java.util.concurrent.ConcurrentLinkedQueue<double[]>
            PENDING_SYNC_PROGRESS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    static void requestSyncProgress(double progress, int state) {
        PENDING_SYNC_PROGRESS.add(new double[] {progress, state});
    }

    /**
     * Owner override: the durable ACTIVATION_CONFIRMED checkpoint was forced
     * on an earlier Watch erase cycle, so the current (freshly reset) Watch
     * is unactivated while the pipeline believes otherwise and never
     * re-asserts CanBeginActivation. The next poll downgrades the durable
     * checkpoint to IS_PAIRED_COMMITTED (keys and bond are preserved) and
     * replays the activation drive.
     */
    static final java.util.concurrent.atomic.AtomicBoolean
            PENDING_REDRIVE_ACTIVATION =
            new java.util.concurrent.atomic.AtomicBoolean();

    static void requestRedriveActivation() {
        PENDING_REDRIVE_ACTIVATION.set(true);
    }

    /**
     * Owner override: the Watch's gizmoDidFinishActivating (PBBridge
     * protobuf 4) already arrived and was consumed on a previous live
     * session — the Watch finished Buddy and will not re-report — so the
     * in-memory coordinator of the current session never latches the
     * setup-finished evidence. The next poll replays
     * onGizmoDidFinishActivating into the active coordinator; the regular
     * gate still requires our PairedSync completion publication (sending
     * it when needed), so the checkpoint order is preserved.
     */
    static final java.util.concurrent.atomic.AtomicBoolean
            PENDING_FORCE_SETUP_OBSERVED =
            new java.util.concurrent.atomic.AtomicBoolean();

    static void requestForceSetupObserved() {
        PENDING_FORCE_SETUP_OBSERVED.set(true);
    }

    /**
     * Owner override: the Watch activation state machine can be parked in a
     * state where CanBeginActivation is silently ignored (Setup no longer
     * observes the ability notification, or ActivationController sits in a
     * non-Idle state from a poisoned earlier drive). PBBridge message 15
     * (RetryActivation) forces _cleanup + Idle + _startActivation on the
     * Watch, so the next poll sends it and re-arms WAITING_SESSION_REQUEST.
     */
    static final java.util.concurrent.atomic.AtomicBoolean
            PENDING_RETRY_ACTIVATION =
            new java.util.concurrent.atomic.AtomicBoolean();

    static void requestRetryActivation() {
        PENDING_RETRY_ACTIVATION.set(true);
    }

    /**
     * Persists a raw activation artifact (archived Watch request or Albert
     * response body) for offline analysis. Best-effort diagnostics only.
     */
    static void captureActivationArtifact(String label, byte[] data) {
        if (data == null || data.length == 0) return;
        try {
            File dir = BridgePaths.files().resolve("activation-capture").toFile();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                log("ACTIVATION CAPTURE FAILED: cannot create " + dir.getAbsolutePath());
                return;
            }
            File out = java.nio.file.Files.createTempFile(dir.toPath(),
                    System.currentTimeMillis() + "-" + label + "-", ".bin").toFile();
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(data);
            }
            log("ACTIVATION CAPTURE: wrote " + out.getName() + " bytes=" + data.length);
        } catch (Exception failure) {
            log("ACTIVATION CAPTURE FAILED: " + safeMessage(failure));
        }
    }

    /**
     * Re-serializes a successful activation response body into the canonical
     * form Albert signed (see ActivationResponseCanonicalizer) before the
     * bytes are queued for the Watch. Non-activation payloads pass through.
     */
    static MobileActivationHttpProxy.ProxyResponse canonicalizeActivationResponse(
            MobileActivationHttpProxy.ProxyResponse response) {
        try {
            ActivationResponseCanonicalizer.Result transformed =
                    ActivationResponseCanonicalizer.maybeTransform(response.body());
            if (!transformed.changed()) {
                log("ACTIVATION CANONICALIZE SKIPPED: " + transformed.note());
                return response;
            }
            log("ACTIVATION BODY CANONICALIZED: variant=" + transformed.variant()
                    + " in=" + transformed.inputLength()
                    + " out=" + transformed.body().length + ".");
            captureActivationArtifact(
                    "activation-delivered-body",
                    transformed.body());
            MobileActivationHttpProxy.ProxyResponse rebuilt =
                    MobileActivationHttpProxy.proxyResponseOf(
                            response.statusCode(),
                            transformed.body(),
                            response.archivedHeaders());
            response.destroy();
            return rebuilt;
        } catch (Exception failure) {
            log("ACTIVATION CANONICALIZE FAILED: " + safeMessage(failure)
                    + "; delivering raw body.");
            return response;
        }
    }

    /** Printable-ASCII excerpt for HTTP/protobuf diagnostics; escapes the rest. */
    static String printablePreview(byte[] data, int maxLength) {
        if (data == null || data.length == 0) return "";
        StringBuilder out = new StringBuilder(Math.min(data.length, maxLength));
        int limit = Math.min(data.length, maxLength);
        for (int i = 0; i < limit; i++) {
            int b = data[i] & 0xff;
            if (b >= 0x20 && b <= 0x7e) {
                out.append((char) b);
            } else if (b == '\n' || b == '\r' || b == '\t') {
                out.append(' ');
            } else {
                out.append(String.format(Locale.US, "\\x%02x", b));
            }
        }
        if (data.length > limit) out.append("…");
        return out.toString();
    }

    static String formatHexList(List<Integer> values) {
        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index != 0) {
                output.append(',');
            }
            output.append(String.format(
                    Locale.US,
                    "0x%04X",
                    values.get(index)));
        }
        return output.append(']').toString();
    }

}
