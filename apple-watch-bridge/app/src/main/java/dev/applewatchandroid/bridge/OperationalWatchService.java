package dev.applewatchandroid.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only confirmed, activated pairs. Never creates a pair or runs setup/activation. */
public final class OperationalWatchService extends Service implements OperationalSessionAccess {
    static final String UI_EVENT = "dev.applewatchandroid.bridge.OPERATIONAL_SERVICE_EVENT";
    static final String ACTION_START = "dev.applewatchandroid.bridge.START_OPERATIONAL";
    static final String ACTION_STOP = "dev.applewatchandroid.bridge.STOP_OPERATIONAL";
    static final String ACTION_STOP_PHONE_PING = "dev.applewatchandroid.bridge.STOP_PHONE_PING";
    private static final String CHANNEL = BridgeConnectionNotification.CHANNEL;
    private static final int NOTIFICATION = 1;
    private static final String COMPANION = "dev.applewatchandroid.companion.apple_watch_companion";
    private static volatile OperationalWatchService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService sessionExecutor = Executors.newSingleThreadExecutor();

    // Only the serial observation worker reads/writes this checkpoint; it is not a native sync anchor.

    private final ThreadPoolExecutor commandExecutor = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32));
    private final AtomicBoolean sessionRunning = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile boolean connected;
    private volatile Process rootProcess;
    private volatile BufferedWriter rootInput;
    private volatile java.util.UUID transportEpoch;
    private BridgeIdentityStore identityStore;
    private OperationalHealthController health;
    private OperationalPhoneFinder phone;

    private volatile String sessionPairing;
    static String currentPairing() {
        OperationalWatchService service = active;
        return service != null && service.connected ? service.sessionPairing : null;
    }
    private int failures;
    private boolean initialWifiRequested;
    private final Runnable reconnect = this::startSession;

    static boolean isRunning() { return active != null && !active.stopping; }
    static boolean hasOwner() { return active != null; }
    static boolean isConnected() { return active != null && active.connected; }
    static java.util.UUID readyEpoch() {
        OperationalWatchService service = active;
        return service != null && service.connected ? service.transportEpoch : null;
    }
    static void start(Context context) {
        context.startForegroundService(new Intent(context, OperationalWatchService.class).setAction(ACTION_START));
    }
    static void stop() {
        OperationalWatchService service = active;
        if (service != null) service.requestStop("owner");
    }

    /** Accepted into a bounded local queue is not Watch delivery. No setup commands are accepted. */
    static boolean sendCommand(String command) {
        return sendCommand(command, java.util.UUID.randomUUID());
    }
    static boolean sendCommand(String command, java.util.UUID requestId) {
        OperationalWatchService service = active;
        return service != null && service.enqueueCommand(command, requestId);
    }
    static boolean sendCommand(String command, java.util.UUID requestId, java.util.UUID expectedEpoch) {
        OperationalWatchService service = active;
        return expectedEpoch != null && service != null && service.enqueueCommand(command, requestId, expectedEpoch);
    }

    private boolean enqueueCommand(String command) {
        return enqueueCommand(command, java.util.UUID.randomUUID());
    }
    private boolean enqueueCommand(String command, java.util.UUID requestId) {
        return enqueueCommand(command, requestId, null);
    }
    private boolean enqueueCommand(String command, java.util.UUID requestId, java.util.UUID expectedEpoch) {
        if (stopping || !connected || !OperationalCommandPolicy.isAllowed(command)) return false;
        BufferedWriter sessionInput = rootInput;
        java.util.UUID epoch = transportEpoch;
        if (epoch == null || sessionInput == null || requestId == null) return false;
        if (expectedEpoch != null && !expectedEpoch.equals(epoch)) return false;
        BridgeCommandCodec.Request request = new BridgeCommandCodec.Request(requestId, epoch,
                SystemClock.elapsedRealtime() + 60_000, command);
        try {
            commandExecutor.execute(() -> {
                byte[] frame = BridgeCommandCodec.encode(request);
                try {
                    synchronized (sessionInput) {
                        if (stopping || !connected || rootInput != sessionInput || !epoch.equals(transportEpoch)
                                || SystemClock.elapsedRealtime() >= request.deadlineMs()) {
                            operationStatus(new BridgeCommandCodec.Status(requestId, epoch, BridgeCommandCodec.Stage.EXPIRED));
                            return;
                        }
                        sessionInput.write(BridgeCommandCodec.REQUEST_PREFIX);
                        sessionInput.write(Base64.encodeToString(frame, Base64.NO_WRAP));
                        sessionInput.write('\n');
                        sessionInput.flush();
                    }
                } catch (IOException error) {
                    operationStatus(new BridgeCommandCodec.Status(requestId, epoch, BridgeCommandCodec.Stage.UNKNOWN));
                } finally { wipe(frame); }
            });
            return true;
        } catch (RejectedExecutionException full) {
            log("OPERATIONAL command queue full; command rejected, delivery unconfirmed.");
            return false;
        }
    }

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if ("dev.applewatchandroid.bridge.ACTION_STOP_BRIDGE".equals(intent.getAction())) {
                requestStop("app IPC");
            } else if ("dev.applewatchandroid.bridge.ACTION_PING_WATCH".equals(intent.getAction())) {
                if (!enqueueCommand("PING_WATCH")) log("PING rejected; no ready IDS session.");
            } else if ("dev.applewatchandroid.bridge.ACTION_SET_ACTIVE_FACE".equals(intent.getAction())) {
                String face = intent.getStringExtra("faceId");
                if (!enqueueCommand("SET_ACTIVE_FACE:" + (face == null ? "" : face))) log("Face command rejected.");
            } else if ("dev.applewatchandroid.bridge.ACTION_REMOVE_NOTIFICATION".equals(intent.getAction())) {
                String publisher = intent.getStringExtra("publisherId"), record = intent.getStringExtra("recordId"),
                        section = intent.getStringExtra("sectionId");
                if (publisher == null || publisher.isBlank() || record == null || record.isBlank()
                        || section == null || section.isBlank()) return;
                byte[] payload = new BulletinDistributorCodec.RemoveBulletinRequest(publisher, record, section).encode();
                try {
                    if (!enqueueCommand("REMOVE_BULLETIN:" + Base64.encodeToString(payload, Base64.NO_WRAP))) {
                        log("Bulletin removal rejected; native removal unconfirmed.");
                    }
                } finally { wipe(payload); }
            } else if ("dev.applewatchandroid.bridge.ACTION_SEND_NOTIFICATION".equals(intent.getAction())) {
                byte[] payload = BridgeIpcDispatcher.getInstance().createBulletinPayload(
                        text(intent, "title", "Alert"), text(intent, "message", ""),
                        text(intent, "sectionId", "com.apple.MobileSMS"),
                        text(intent, "sectionDisplayName", "Messages"), true,
                        intent.getStringExtra("publisherId"), intent.getStringExtra("recordId"),
                        intent.getStringExtra("replyToken"), intent.getStringExtra("replyActionId"));
                try {
                    if (!enqueueCommand("SEND_BULLETIN:" + Base64.encodeToString(payload, Base64.NO_WRAP))) {
                        log("Bulletin rejected; delivery unconfirmed.");
                    }
                } finally { wipe(payload); }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        BridgeIpcDispatcher.getInstance().setNotificationActionLabels(
                getString(R.string.reply), getString(R.string.send));
        active = this;
        identityStore = new BridgeIdentityStore(this, this::log);
        health = new OperationalHealthController(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.apple_watch_connection), NotificationManager.IMPORTANCE_LOW));
        phone = new OperationalPhoneFinder(this, () -> {
            if (!stopping) manager.notify(NOTIFICATION, notification(connected
                    ? getString(R.string.watch_connected) : getString(R.string.watch_disconnected)));
        });
        IntentFilter filter = new IntentFilter();
        filter.addAction("dev.applewatchandroid.bridge.ACTION_PING_WATCH");
        filter.addAction("dev.applewatchandroid.bridge.ACTION_SET_ACTIVE_FACE");
        filter.addAction("dev.applewatchandroid.bridge.ACTION_SEND_NOTIFICATION");
        filter.addAction("dev.applewatchandroid.bridge.ACTION_REMOVE_NOTIFICATION");
        filter.addAction("dev.applewatchandroid.bridge.ACTION_STOP_BRIDGE");
        // Same-app notification listener works even with no Activity. Companion authorization
        // will use a separate structured IPC boundary, not unrestricted raw commands.
        registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "dev.applewatchandroid.bridge.DISCOVER_NATIVE_SNAPSHOTS".equals(intent.getAction())) {
            // Fixed, read-only diagnostic through this non-exported service.
            // Share the existing writer/executor so no stdin frame interleaves.
            BufferedWriter input = rootInput;
            java.util.UUID epoch = transportEpoch;
            if (connected && !stopping && input != null && epoch != null) {
                try {
                    commandExecutor.execute(() -> {
                        try {
                            synchronized (input) {
                                if (!connected || stopping || rootInput != input || !epoch.equals(transportEpoch)) return;
                                input.write("DISCOVER_NATIVE_SNAPSHOTS\n");
                                input.flush();
                            }
                        } catch (IOException unavailable) {
                            log("APPLICATION SERVICE DISCOVERY: command delivery unconfirmed.");
                        }
                    });
                } catch (RejectedExecutionException full) {
                    log("APPLICATION SERVICE DISCOVERY: command queue full.");
                }
            } else log("APPLICATION SERVICE DISCOVERY: no ready operating session.");
            if (!sessionRunning.get()) { stopSelf(startId); return START_NOT_STICKY; }
            return START_STICKY;
        }
        if (intent != null && ACTION_STOP_PHONE_PING.equals(intent.getAction())) {
            phone.stop("owner stop action");
            if (!sessionRunning.get()) { stopSelf(startId); return START_NOT_STICKY; }
            return START_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            requestStop("notification");
            return START_NOT_STICKY;
        }
        if (intent == null && !getSharedPreferences("watch_operating_mode", MODE_PRIVATE)
                .getBoolean("operational_service_enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification(getString(R.string.connecting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        CompanionSessionState.connection("CONNECTING");
        CompanionSessionState.refreshIdentity(this);
        if (!getSharedPreferences("watch_operating_mode", MODE_PRIVATE).edit()
                .putBoolean("operational_service_enabled", true).commit()) {
            log("OPERATIONAL service start refused: cannot persist owner start request.");
            requestStop("storage error");
            return START_NOT_STICKY;
        }
        startSession();
        return START_STICKY;
    }

    private Notification notification(String state) {
        return BridgeConnectionNotification.build(this, state, connected, phone != null && phone.active());
    }

    private void publishState(String state, boolean ready) {
        if (!ready) { health.disconnect();transportEpoch = null; }
        connected = ready;
        AppleWatchNotificationListenerService.transportState(ready ? transportEpoch : null);
        CompanionSessionState.connection(ready ? "CONNECTED" : stopping ? "STOPPING" : "CONNECTING");
        BridgeIpcDispatcher.getInstance().updateConnectionState(ready, "Apple Watch");
        main.post(() -> {
            if (!ready && phone != null) phone.stop("transport disconnected");
            if (!stopping) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(state));
            sendBroadcast(new Intent(UI_EVENT).setPackage(getPackageName())
                    .putExtra("running", !stopping).putExtra("connected", ready));
            sendBroadcast(new Intent("dev.applewatchandroid.companion.ACTION_CONNECTION_STATE")
                    .setPackage(COMPANION).putExtra("connected", ready)
                    .putExtra("deviceName", "Apple Watch"));
        });
    }

    private void startSession() {
        if (stopping || !sessionRunning.compareAndSet(false, true)) return;
        sessionExecutor.execute(this::runSession);
    }

    private void runSession() {
        Process process = null;
        byte[] localIdentity = null;
        byte[] publicKeys = null;
        byte[] plaintext = null;
        CarPlayBluetoothHandoffClient handoff = null;
        boolean handoffMayBePrepared = false;
        boolean resumed = false;
        boolean retry = false;
        boolean ready = false;
        try {
            String pairing = identityStore.operationalPairing();
            sessionPairing = pairing;
            if (pairing == null) throw new IOException(getString(R.string.no_confirmed_activated_pair));
            plaintext = identityStore.restorePlaintext();
            PairingSessionRecord record = PairingSessionRecord.parse(plaintext);
            try { OperationalSessionPolicy.requireMatchingActivatedPair(record, pairing); }
            finally { record.destroy(); }
            publicKeys = identityStore.publicBundle();
            byte[] bound = IdsMessageProtectionIdentity.bindPublicBundle(publicKeys, identityStore.idsIdentifier());
            wipe(publicKeys);
            publicKeys = bound;
            localIdentity = identityStore.localIdentity();
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null) throw new IOException("Bluetooth adapter unavailable");
            int adapterState = adapter.getState();
            CarPlayBluetoothHandoffClient.Status status = CarPlayBluetoothHandoffClient.Status.unavailable();
            if (adapterState == BluetoothAdapter.STATE_ON) {
                handoff = new CarPlayBluetoothHandoffClient(getContentResolver());
                status = handoff.status();
            }
            CarPlayHandoffDecision.Mode mode = CarPlayHandoffDecision.decide(
                    adapterState == BluetoothAdapter.STATE_OFF, adapterState == BluetoothAdapter.STATE_ON,
                    status.available, status.wifiReady, status.vehicleMediaActive, status.prepared);
            if (mode == CarPlayHandoffDecision.Mode.REFUSE) throw new IOException("Bluetooth/CarPlay readiness gate refused startup");
            boolean cooperative = mode == CarPlayHandoffDecision.Mode.COOPERATIVE_CARPLAY;
            if (cooperative) {
                handoffMayBePrepared = true;
                CarPlayBluetoothHandoffClient.Status prepared = handoff.prepare();
                if (!prepared.available || !prepared.ready || !prepared.prepared) throw new IOException("CarPlay preparation failed");
            }
            if (stopping) return;
            publishState(getString(R.string.connecting), false);
            String entry = cooperative ? "RootBluetoothCarPlayLease" : "RootBluetoothHalHost";
            String command = "CLASSPATH=" + shellQuote(getApplicationInfo().sourceDir)
                    + " app_process " + shellQuote("-Dwatch.native.quic.path="
                        + getApplicationInfo().sourceDir + "!/lib/arm64-v8a/libwatch_replicator_quic.so")
                    + " /system/bin dev.applewatchandroid.bridge." + entry
                    + " --acknowledge-target CPH2653 --operational-pairing " + shellQuote(pairing);
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            rootProcess = process;
            rootInput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            // All credentials are validated before starting root; no missing-record fresh fallback.
            writeEncoded("LOCAL_IDENTITY_V1:", localIdentity);
            writeEncoded("LOCAL_IDS_PUBLIC_KEYS_V1:", publicKeys);
            writeEncoded("RESTORED_PAIRING_SESSION_V2:", plaintext);
            wipe(plaintext); plaintext = null;
            wipe(localIdentity); localIdentity = null;
            wipe(publicKeys); publicKeys = null;
            if (stopping) writeLine("STOP");
            log("OPERATIONAL SERVICE root started; setup=false; activation=false; freshPairing=false.");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("PAIRING_SESSION_V2:")) {
                        boolean stored = identityStore.storePairing(line.substring("PAIRING_SESSION_V2:".length()), pairing);
                        writeLine(stored ? "PAIRING-SESSION-STORED" : "PAIRING-SESSION-STORE-FAILED");
                        log("OPERATIONAL pairing checkpoint stored=" + stored + "; plaintext logged=false.");
                    } else if (line.startsWith("BOND_SECRET_V1:")) {
                        // Operational reconnect must retain its existing bond. Unexpected replacement
                        // material is refused, so it cannot silently replace the activated pair.
                        writeLine("BOND-STORE-FAILED");
                        log("OPERATIONAL unexpected new bond refused; existing keys retained.");
                    } else if (line.startsWith(FindMyPhoneIpcCodec.REQUEST_PREFIX)) {
                        phone.handlePhonePingRequest(line.substring(FindMyPhoneIpcCodec.REQUEST_PREFIX.length()));
                    } else if (line.startsWith(HealthDataEventCodec.PREFIX)) {
                        health.handleHealthData(line.substring(HealthDataEventCodec.PREFIX.length()));
                    } else if (line.startsWith(HealthPeerIdentityCodec.PREFIX)) {
                        health.handleHealthPeerIdentity(line.substring(HealthPeerIdentityCodec.PREFIX.length()));
                    } else if(line.startsWith(HealthOutboundIpcCodec.STATUS_PREFIX)) {
                        health.handleHealthSendStatus(line.substring(HealthOutboundIpcCodec.STATUS_PREFIX.length()));
                    } else if(line.startsWith(HealthRegistryObservationCodec.PREFIX)) {
                        health.handleHealthRegistryObservation(line.substring(HealthRegistryObservationCodec.PREFIX.length()));
                    } else if (line.startsWith(BridgeApplicationEventCodec.PREFIX)) {
                        handleApplicationEvent(line.substring(BridgeApplicationEventCodec.PREFIX.length()));
                    } else if (line.startsWith(MonogramMirrorIpcCodec.PREFIX)) {
                        byte[] frame = null;
                        try {
                            String encoded = line.substring(MonogramMirrorIpcCodec.PREFIX.length());
                            if (!ready || active != this || !connected || transportEpoch == null
                                    || encoded.length() > (MonogramMirrorIpcCodec.MAX_FRAME + 2) / 3 * 4)
                                throw new IllegalArgumentException("Monogram mirror outside ready epoch");
                            frame = Base64.decode(encoded, Base64.NO_WRAP);
                            var mirror = MonogramMirrorIpcCodec.decode(frame);
                            if (!mirror.pair().toString().equals(pairing) || !mirror.epoch().equals(transportEpoch))
                                throw new IllegalArgumentException("Foreign monogram mirror");
                            BridgeIpcDispatcher.getInstance().observeMonogramMirror(mirror);
                        } catch (IOException | IllegalArgumentException invalid) {
                            log("MONOGRAM MIRROR IPC refused; values logged=false.");
                        } finally { wipe(frame); }
                    } else if (line.startsWith(PigmentMirrorIpcCodec.PREFIX)) {
                        byte[] frame = null;
                        try {
                            String encoded = line.substring(PigmentMirrorIpcCodec.PREFIX.length());
                            if (!ready || active != this || !connected
                                    || encoded.length() > (PigmentMirrorIpcCodec.MAX_FRAME + 2) / 3 * 4)
                                throw new IllegalArgumentException("Mirror outside owned ready epoch");
                            frame = Base64.decode(encoded, Base64.NO_WRAP);
                            var mirror = PigmentMirrorIpcCodec.decode(frame);
                            if (!mirror.pair().toString().equals(pairing) || !mirror.epoch().equals(transportEpoch))
                                throw new IllegalArgumentException("Foreign pigment mirror");
                            BridgeIpcDispatcher.getInstance().observePigmentMirror(mirror);
                        } catch (IOException | IllegalArgumentException invalid) {
                            log("PIGMENT MIRROR IPC refused; values logged=false.");
                        } finally { wipe(frame); }
                    } else if (line.startsWith(ClockFaceObservationCodec.PREFIX)) {
                        byte[] frame = null;
                        try {
                            String encoded = line.substring(ClockFaceObservationCodec.PREFIX.length());
                            if (!ready || encoded.length() > (ClockFaceObservationCodec.MAX_FRAME + 2) / 3 * 4) {
                                throw new IllegalArgumentException("Face observation outside ready epoch");
                            }
                            frame = Base64.decode(encoded, Base64.NO_WRAP);
                            var observation = ClockFaceObservationCodec.decode(frame);
                            if (!observation.pair().toString().equals(pairing)
                                    || !observation.epoch().equals(transportEpoch)) {
                                throw new IllegalArgumentException("Foreign face observation");
                            }
                            BridgeIpcDispatcher.getInstance().observeClockFaces(observation);
                            log("OPERATIONAL native face observation: faces=" + observation.faces().size()
                                    + " complete=" + observation.complete() + " observedAt=" + observation.observedAt());
                        } catch (IOException | IllegalArgumentException invalid) {
                            log("OPERATIONAL face observation refused; payload logged=false.");
                        } finally { wipe(frame); }
                    } else if (line.startsWith(BridgeCommandCodec.STATUS_PREFIX)) {
                        byte[] statusFrame = null;
                        try {
                            String encoded = line.substring(BridgeCommandCodec.STATUS_PREFIX.length());
                            if (encoded.length() > 128) throw new IllegalArgumentException();
                            statusFrame = Base64.decode(encoded, Base64.NO_WRAP);
                            operationStatus(BridgeCommandCodec.decodeStatus(statusFrame));
                        } catch (IllegalArgumentException invalid) {
                            log("OPERATIONAL request status rejected; values logged=false.");
                        } finally { wipe(statusFrame); }
                    } else if (line.startsWith("BRIDGE_TRANSPORT_V1:READY:")) {
                        try {
                            transportEpoch = java.util.UUID.fromString(line.substring("BRIDGE_TRANSPORT_V1:READY:".length()));
                            ready = true;
                            failures = 0;
                            publishState(getString(R.string.watch_connected), true);
                            health.replayHealthInbox();
                            sendCommand("REQUEST_DEVICE_ABOUT", java.util.UUID.randomUUID(), transportEpoch);
                            if (!initialWifiRequested) {
                                initialWifiRequested = sendCommand(WifiNetworkSyncCodec.COMMAND, java.util.UUID.randomUUID(), transportEpoch);
                                log("WIFI initial synchronization queued=" + initialWifiRequested + "; Watch network application unconfirmed.");
                            }
                        } catch (IllegalArgumentException invalid) {
                            publishState(getString(R.string.invalid_transport_state), false);
                        }
                    } else if (line.equals("BRIDGE_TRANSPORT_V1:DOWN")) {
                        ready = false;
                        publishState(getString(R.string.reconnecting), false);
                        log("OPERATIONAL transport disconnected; application commands paused.");
                    } else if (line.startsWith("ACTIVATION-CHALLENGE-V1:") || line.startsWith("ACTIVATION-ALERT-V1:")) {
                        log("OPERATIONAL unexpected setup/activation event; stopping without new pairing.");
                        writeLine("STOP");
                    } else {
                        log(line);
                    }
                }
            }
            int exit = process.waitFor();
            log("OPERATIONAL SERVICE root exited=" + exit + "; setup success inferred=false.");
            retry = !stopping;
        } catch (Exception error) {
            log("OPERATIONAL SERVICE error: " + error.getClass().getSimpleName() + ": " + safeMessage(error));
            // Configuration/Keystore/pair validation errors require explicit user correction;
            // only an already started transport is eligible for automatic reconnect.
            retry = !stopping && process != null;
        } finally {
            wipe(localIdentity); wipe(publicKeys); wipe(plaintext);
            if (rootProcess == process) {
                rootInput = null;
                rootProcess = null;
            }
            if (process != null && process.isAlive()) process.destroy();
            if (handoffMayBePrepared && handoff != null) {
                CarPlayBluetoothHandoffClient.Status released = handoff.resume();
                resumed = released.available && !released.prepared;
                log("OPERATIONAL CarPlay handoff released=" + resumed);
            }
            sessionRunning.set(false);
            publishState(getString(R.string.watch_disconnected), false);
            if (retry && (!handoffMayBePrepared || resumed)) {
                long delay = OperationalCommandPolicy.reconnectDelayMs(++failures);
                log("OPERATIONAL reconnect scheduled in " + delay + "ms; same pair only; previous IDS ready=" + ready);
                main.postDelayed(reconnect, delay);
            } else {
                main.post(() -> {
                    if (stopping) stopSelf();
                    else requestStop("session ended");
                });
            }
        }
    }

    private void writeEncoded(String prefix, byte[] bytes) throws IOException {
        byte[] encoded = Base64.encode(bytes, Base64.NO_WRAP);
        try {
            BufferedWriter input = rootInput;
            if (input == null) throw new IOException("Root stdin unavailable");
            synchronized (input) {
                input.write(prefix);
                for (byte value : encoded) input.write(value & 0xff);
                input.write('\n');
                input.flush();
            }
        } finally { wipe(encoded); }
    }

    private void writeLine(String line) throws IOException {
        BufferedWriter input = rootInput;
        if (input == null) throw new IOException("Root stdin unavailable");
        synchronized (input) { input.write(line); input.write('\n'); input.flush(); }
    }

    private void handleApplicationEvent(String encoded) {
        byte[] bytes = null;
        try {
            if (encoded.length() > ((BridgeApplicationEventCodec.MAX_FRAME + 2) / 3) * 4) throw new IllegalArgumentException();
            bytes = Base64.decode(encoded, Base64.NO_WRAP);
            try (BridgeApplicationEventCodec.Event event = BridgeApplicationEventCodec.decode(bytes)) {
                // Use the owned child's current context, never the unscoped durable NPS cache.
                if (active == this && connected && sessionPairing != null && transportEpoch != null) {
                    BridgeIpcDispatcher.getInstance().observeNativePreferences(event,
                            java.util.UUID.fromString(sessionPairing), transportEpoch, System.currentTimeMillis());
                }
                BridgeIpcDispatcher.getInstance().dispatchApplicationMessage(event);
                log("APPLICATION IPC RX: topic=" + event.topic + " type=" + event.protobufType
                        + " response=" + event.response + " bytes=" + event.payloadLength() + "; payload logged=false.");
            }
        } catch (IllegalArgumentException malformed) {
            log("APPLICATION IPC RX rejected; payload logged=false.");
        } finally { wipe(bytes); }
    }

    /** Debug-only same-app hardware probe. It never emits a Watch result or claims native origin. */
    static void probePhonePing(int behavior, String token, long unixMs) {
        OperationalWatchService service = active;
        if (service == null) return;
        service.main.post(() -> {
            java.util.UUID epoch = service.transportEpoch;
            if (epoch == null || service.sessionPairing == null) return;
            try {
                var request = new FindMyPhoneIpcCodec.Request(epoch,
                        java.util.UUID.nameUUIDFromBytes(token.getBytes(StandardCharsets.UTF_8)).toString(), 1,
                        new FindMyLocalDeviceCodec.PlaySoundRequest(unixMs / 1000.0, behavior),
                        SystemClock.elapsedRealtime() + 10_000);
                if (!service.phone.phonePingAllowed(request, service.rootInput)) return;
                String probePair = java.util.UUID.nameUUIDFromBytes(
                        ("PROBE:" + service.sessionPairing).getBytes(StandardCharsets.UTF_8)).toString();
                service.phone.executePhonePing(request, probePair, true);
            } catch (IllegalArgumentException malformed) { service.log("PHONE FIND local probe rejected."); }
        });
    }

    static boolean phonePingAvailable() {
        OperationalWatchService service = active;
        return service != null && !service.stopping && service.phone != null;
    }

    /** Main-looper only, through signature-protected Companion IPC; never starts HAL. */
    static boolean stopPhonePing() {
        OperationalWatchService service = active;
        if (service == null || service.phone == null) return false;
        service.phone.stop("Companion stop action");
        return !service.phone.active();
    }

    private void operationStatus(BridgeCommandCodec.Status status) {
        AppleWatchNotificationListenerService.operationStatus(status);
        BridgeIpcDispatcher.getInstance().dispatchOperationStatus(status);
        log("OPERATIONAL REQUEST: id=" + status.id() + " stage=" + status.stage()
                + "; Watch-local effect not inferred.");
    }

    private void requestStop(String source) {
        if (stopping) return;
        stopping = true;
        main.removeCallbacks(reconnect);
        getSharedPreferences("watch_operating_mode", MODE_PRIVATE).edit()
                .putBoolean("operational_service_enabled", false).commit();
        log("OPERATIONAL SERVICE stop requested: " + source + "; pair retained.");
        publishState(getString(R.string.disconnecting), false);
        if (rootProcess == null) {
            if (!sessionRunning.get()) stopSelf();
            return;
        }
        // Do not queue STOP behind normal application commands.
        new Thread(() -> {
            try { writeLine("STOP"); }
            catch (IOException error) { log("OPERATIONAL STOP write failed; closing root process."); }
        }, "watch-service-stop").start();
        main.postDelayed(() -> {
            Process process = rootProcess;
            if (process != null && process.isAlive()) process.destroy();
        }, 10_000);
    }

    @Override public void onDestroy() {
        requestStop("service destroyed");
        try { unregisterReceiver(commandReceiver); } catch (IllegalArgumentException ignored) { }
        main.removeCallbacks(reconnect);
        commandExecutor.shutdownNow();
        health.close();
        if (phone != null) phone.close();
        sessionExecutor.shutdown();
        if (active == this) active = null;
        CompanionSessionState.connection("DISCONNECTED");
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void log(String line) {
        String entry = BridgeJournal.entry(line);
        CompanionSessionState.log(entry);
        try { BridgeJournal.append(this, entry); }
        catch (IOException error) { android.util.Log.e("WatchBridge", "Journal write failed"); }
        // No Activity reference is retained by the service.
        sendBroadcast(new Intent(UI_EVENT).setPackage(getPackageName()).putExtra("entry", entry)
                .putExtra("running", !stopping).putExtra("connected", connected));
    }
    private static String text(Intent intent, String key, String fallback) {
        String value = intent.getStringExtra(key);
        return value == null ? fallback : value;
    }
    private static String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private String safeMessage(Throwable error) { return error.getMessage() == null ? getString(R.string.no_message) : error.getMessage(); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }

    @Override public Context context() { return this; }
    @Override public Handler handler() { return main; }
    @Override public java.util.concurrent.Executor commands() { return commandExecutor; }
    @Override public boolean stopping() { return stopping; }
    @Override public boolean connected() { return connected; }
    @Override public java.util.UUID epoch() { return transportEpoch; }
    @Override public String pairing() { return sessionPairing; }
    @Override public BufferedWriter input() { return rootInput; }
    @Override public BridgeIdentityStore identities() { return identityStore; }
}
