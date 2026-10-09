package dev.applewatchandroid.bridge;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Typed, signature-protected Companion IPC. Binding never starts a HAL or a new pair. */
public final class CompanionBridgeService extends Service implements BridgeIpcDispatcher.OnDeviceStateListener,
        BridgeIpcDispatcher.OnOperationStatusListener, BridgeIpcDispatcher.OnPhoneFindStateListener,
        BridgeIpcDispatcher.OnWatchSettingsListener, BridgeIpcDispatcher.OnClockFacesListener,
        BridgeIpcDispatcher.OnPigmentPreferencesListener, BridgeIpcDispatcher.OnMonogramPreferencesListener {
    private static final String COMPANION = "dev.applewatchandroid.companion.apple_watch_companion";
    private static final int SUBSCRIBE = 1, GET_STATE = 2, COMMAND = 3;
    private static final int RESPONSE = 101, STATE = 102;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<IBinder, Client> clients = new HashMap<>();
    private Messenger endpoint;
    private NativeFaceTransferDispatcher faceTransfers;
    private final Runnable sessionListener = this::publishState;

    private final class Client {
        final Messenger messenger;
        final IBinder.DeathRecipient death;
        Client(Messenger messenger) {
            this.messenger = messenger;
            death = () -> main.post(() -> remove(messenger.getBinder()));
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        faceTransfers = new NativeFaceTransferDispatcher(this);
        CompanionSessionState.add(sessionListener);
        CompanionSessionState.refreshIdentity(this);
        endpoint = new Messenger(new Handler(Looper.getMainLooper()) {
            @Override public void handleMessage(Message message) { handleRequest(message); }
        });
        BridgeIpcDispatcher.getInstance().addStateListener(this);
        BridgeIpcDispatcher.getInstance().addOperationListener(this);
        BridgeIpcDispatcher.getInstance().addPhoneFindListener(this);
        BridgeIpcDispatcher.getInstance().addWatchSettingsListener(this);
        BridgeIpcDispatcher.getInstance().addClockFacesListener(this);
        BridgeIpcDispatcher.getInstance().addPigmentPreferencesListener(this);
        BridgeIpcDispatcher.getInstance().addMonogramPreferencesListener(this);
    }

    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }

    private boolean authorized(int uid) {
        String[] packages = getPackageManager().getPackagesForUid(uid);
        return packages != null && Arrays.asList(packages).contains(COMPANION)
                && getPackageManager().checkSignatures(uid, getApplicationInfo().uid)
                == PackageManager.SIGNATURE_MATCH;
    }

    private void handleRequest(Message message) {
        if (!authorized(message.sendingUid) || message.replyTo == null) return;
        String requestId = null;
        try {
            Bundle data = message.getData();
            if (data.getInt("version") != 1) throw new IllegalArgumentException("Unsupported IPC version");
            requestId = data.getString("requestId");
            if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId)) {
                throw new IllegalArgumentException("Invalid request identifier");
            }
            if (message.what == SUBSCRIBE) {
                IBinder binder = message.replyTo.getBinder();
                if (!clients.containsKey(binder)) {
                    if (clients.size() >= 4) throw new IllegalStateException("Client limit reached");
                    Client client = new Client(message.replyTo);
                    binder.linkToDeath(client.death, 0);
                    clients.put(binder, client);
                }
                reply(message.replyTo, RESPONSE, response(requestId, "SUBSCRIBED"));
                reply(message.replyTo, STATE, state());
                android.util.Log.i("WatchBridgeIpc", "SUBSCRIBED authorized Companion; connected="
                        + BridgeIpcDispatcher.getInstance().isConnected());
            } else if (message.what == GET_STATE) {
                Bundle result = state();
                result.putString("requestId", requestId);
                result.putString("status", "OBSERVED");
                reply(message.replyTo, RESPONSE, result);
            } else if (message.what == COMMAND) {
                String method = data.getString("method");
                if (handleConnectionCommand(method, data, requestId, message.replyTo)) return;
                Bundle transfer = faceTransfers.handle(method, data, requestId);
                if (transfer != null) { reply(message.replyTo, RESPONSE, transfer); return; }
                if ("stopPhonePing".equals(method)) {
                    reply(message.replyTo, RESPONSE, response(requestId,
                            OperationalWatchService.stopPhonePing() ? "STOPPED" : "UNAVAILABLE"));
                    return;
                }
                String command;
                UUID expectedCommandEpoch = null;
                String nativeFaceTarget = null;
                if ("pingWatch".equals(method)) command = "PING_WATCH";
                else if ("refreshWatchState".equals(method)) command = "REQUEST_DEVICE_ABOUT";
                else if ("refreshFaceCollection".equals(method)) command = "REQUEST_FACE_COLLECTION";
                else if ("syncWifi".equals(method)) command = WifiNetworkSyncCodec.COMMAND;
                else if ("setActiveFace".equals(method)) {
                    nativeFaceTarget = NtkFacePayloadCodec.uuid(text(data, "faceId", 36));
                    requireNativeFace(nativeFaceTarget, false);
                    command = ClockFaceDeltaCommand.SELECT + nativeFaceTarget;
                }
                else if ("duplicateNativeFace".equals(method)) {
                    String source = NtkFacePayloadCodec.uuid(text(data, "sourceFaceId", 36));
                    requireNativeFace(source, true);
                    nativeFaceTarget = requestId;
                    command = ClockFaceDeltaCommand.DUPLICATE + source + ":" + nativeFaceTarget;
                }
                else if ("removeNativeFace".equals(method)) {
                    nativeFaceTarget = NtkFacePayloadCodec.uuid(text(data, "faceId", 36));
                    requireNativeFace(nativeFaceTarget, false);
                    if (BridgeIpcDispatcher.getInstance().clockFaces().ordered().size() < 2)
                        throw new IllegalArgumentException("The final Watch face cannot be removed");
                    command = ClockFaceDeltaCommand.REMOVE + nativeFaceTarget;
                }
                else if ("reorderNativeFaces".equals(method)) {
                    var order = data.getStringArrayList("faceIds");
                    var current = BridgeIpcDispatcher.getInstance().clockFaces();
                    if (order == null || current == null || order.isEmpty()) throw new IllegalArgumentException("Missing face order");
                    requireNativeFace(order.get(0), false);
                    if (order.size() != current.ordered().size() || !new java.util.HashSet<>(order).equals(new java.util.HashSet<>(current.ordered())))
                        throw new IllegalArgumentException("Face order must be an exact permutation");
                    command = ClockFaceDeltaCommand.REORDER + String.join(",", order);
                }
                else if ("updateNativeFace".equals(method) || "addNativeFace".equals(method)) {
                    boolean add = "addNativeFace".equals(method);
                    nativeFaceTarget = add ? requestId : NtkFacePayloadCodec.uuid(text(data, "faceId", 36));
                    if (!add) requireNativeFace(nativeFaceTarget, false);
                    else {
                        var current = BridgeIpcDispatcher.getInstance().clockFaces();
                        if (current == null || current.ordered().isEmpty()) throw new IllegalArgumentException("Read faces before importing");
                        requireNativeFace(current.ordered().get(0), false);
                    }
                    byte[] payload = data.getByteArray(add ? "archive" : "configuration");
                    if (payload == null || payload.length == 0 || payload.length > ClockFaceDeltaCommand.MAX_PAYLOAD_BYTES)
                        throw new IllegalArgumentException("Missing or oversized native face payload");
                    command = (add ? ClockFaceDeltaCommand.ADD : ClockFaceDeltaCommand.UPDATE) + nativeFaceTarget
                            + ":" + Base64.encodeToString(payload, Base64.NO_WRAP);
                }
                else if ("setNativeMonogram".equals(method)) {
                    UUID pair = UUID.fromString(text(data, "pairId", 36));
                    expectedCommandEpoch = UUID.fromString(text(data, "epoch", 36));
                    if (!pair.toString().equals(OperationalWatchService.currentPairing())
                        || !expectedCommandEpoch.equals(OperationalWatchService.readyEpoch()))
                        throw new IllegalArgumentException("Monogram target changed");
                    command = MonogramPreferenceCommand.create(
                        BridgeIpcDispatcher.getInstance().monogramMirror(pair, expectedCommandEpoch), pair, expectedCommandEpoch,
                        text(data, "revision", 64), text(data, "text", 5), System.currentTimeMillis());
                }
                else if ("setNativePigmentVisibility".equals(method)) {
                    UUID pair = UUID.fromString(text(data, "pairId", 36));
                    expectedCommandEpoch = UUID.fromString(text(data, "epoch", 36));
                    if (!pair.toString().equals(OperationalWatchService.currentPairing())
                            || !expectedCommandEpoch.equals(OperationalWatchService.readyEpoch())
                            || !(data.get("sourceTimestamp") instanceof Double)
                            || !(data.get("automaticTimestamp") instanceof Double)) {
                        throw new IllegalArgumentException("Pigment target changed");
                    }
                    Bundle delta = data.getBundle("changes");
                    if (delta == null || delta.size() > PigmentPreferenceCodec.MAX_NAMES) throw new IllegalArgumentException("Invalid pigment changes");
                    Object nameRows = data.get("expectedNames");
                    Object automaticRows = data.get("expectedAutomaticNames");
                    if (!(nameRows instanceof java.util.ArrayList<?> names)
                            || names.size() > PigmentPreferenceCodec.MAX_NAMES
                            || names.stream().anyMatch(name -> !(name instanceof String))) {
                        throw new IllegalArgumentException("Missing complete pigment baseline names");
                    }
                    if (!(automaticRows instanceof java.util.ArrayList<?> automaticNames)
                            || automaticNames.size() > PigmentPreferenceCodec.MAX_NAMES
                            || automaticNames.stream().anyMatch(name -> !(name instanceof String))) {
                        throw new IllegalArgumentException("Missing complete automatic baseline names");
                    }
                    var changes = new java.util.LinkedHashMap<String, Boolean>();
                    for (String name : delta.keySet()) {
                        if (!(delta.get(name) instanceof Boolean flag)) throw new IllegalArgumentException("Invalid pigment visibility");
                        changes.put(name, flag);
                    }
                    command = PigmentPreferenceCommand.createMirror(
                            BridgeIpcDispatcher.getInstance().pigmentMirror(pair, expectedCommandEpoch),
                            pair, expectedCommandEpoch, data.getDouble("sourceTimestamp"),
                            names.stream().map(String.class::cast).collect(java.util.stream.Collectors.toList()),
                            data.getDouble("automaticTimestamp"),
                            automaticNames.stream().map(String.class::cast).collect(java.util.stream.Collectors.toList()),
                            changes, System.currentTimeMillis());
                }
                else if ("setWatchSetting".equals(method)) {
                    if (!(data.get("value") instanceof Boolean)) throw new IllegalArgumentException("Missing boolean setting value");
                    command = WatchSettingsCodec.command(text(data, "setting", 64), data.getBoolean("value"));
                }
                else if ("sendNotification".equals(method)) {
                    byte[] payload = BridgeIpcDispatcher.getInstance().createBulletinPayload(
                            text(data, "title", 4096), text(data, "message", 16384),
                            text(data, "sectionId", 256), text(data, "sectionDisplayName", 256), true);
                    try { command = "SEND_BULLETIN:" + Base64.encodeToString(payload, Base64.NO_WRAP); }
                    finally { Arrays.fill(payload, (byte) 0); }
                } else {
                    reply(message.replyTo, RESPONSE, response(requestId, "UNIMPLEMENTED"));
                    return;
                }
                if (!OperationalCommandPolicy.isAllowed(command)) throw new IllegalArgumentException("Invalid command");
                // This is a local queue receipt, never an application ACK or proof of applied state.
                Bundle receipt = response(requestId,
                        (expectedCommandEpoch == null
                                ? OperationalWatchService.sendCommand(command, UUID.fromString(requestId))
                                : OperationalWatchService.sendCommand(command, UUID.fromString(requestId), expectedCommandEpoch))
                                ? "QUEUED" : "UNAVAILABLE");
                if (nativeFaceTarget != null) receipt.putString("faceId", nativeFaceTarget);
                reply(message.replyTo, RESPONSE, receipt);
            } else throw new IllegalArgumentException("Unknown request");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            reply(message.replyTo, RESPONSE, response(requestId, "REJECTED"));
        } catch (RemoteException dead) {
            remove(message.replyTo.getBinder());
        }
    }

    private static String text(Bundle data, String key, int maxBytes) {
        String value = data.getString(key);
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("Missing or oversized field");
        }
        return value;
    }

    private static void requireNativeFace(String id, boolean duplicate) {
        var observation = BridgeIpcDispatcher.getInstance().clockFaces();
        long now = System.currentTimeMillis();
        if (observation == null || !observation.complete() || !BridgeIpcDispatcher.getInstance().isConnected()
                || observation.observedAt() > now || now - observation.observedAt() > 300_000
                || !observation.ordered().contains(id)
                || duplicate && observation.faces().stream().noneMatch(face -> face.id().equals(id)
                    && (face.archiveBase64() != null || face.packageDigest() != null))) {
            throw new IllegalArgumentException("Refresh native face collection before changing a known UUID");
        }
    }

    private Bundle state() {
        BridgeIpcDispatcher dispatcher = BridgeIpcDispatcher.getInstance();
        Bundle state = new Bundle();
        state.putInt("version", 1);
        state.putBoolean("connected", dispatcher.isConnected());
        state.putString("deviceName", "Apple Watch");
        state.putAll(CompanionSessionState.snapshot());
        state.putBoolean("bluetoothPermission", checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED);
        state.putLong("observedAt", System.currentTimeMillis());
        String pair = OperationalWatchService.currentPairing();
        UUID epoch = OperationalWatchService.readyEpoch();
        if (dispatcher.isConnected() && pair != null && epoch != null) {
            state.putString("faceCollectionPair", pair);
            state.putString("faceCollectionEpoch", epoch.toString());
            var monogram = dispatcher.monogramPreferences(UUID.fromString(pair), epoch);
            var textMirror = dispatcher.monogramMirror(UUID.fromString(pair), epoch);
            if (textMirror != null) {
                state.putInt("monogramMirrorVersion", 1);
                state.putString("monogramMirrorPair", textMirror.pair().toString());
                state.putString("monogramMirrorEpoch", textMirror.epoch().toString());
                state.putString("monogramMirrorRevision", textMirror.revision());
                state.putBoolean("monogramMirrorKnown", textMirror.value() != null);
                if (textMirror.value() != null) {
                    var entry = textMirror.value();
                    state.putString("monogramMirrorOrigin", entry.origin().name());
                    state.putDouble("monogramMirrorSourceTimestamp", entry.sourceTimestamp());
                    state.putLong("monogramMirrorUpdatedAt", entry.updatedAt());
                    if (entry.text() != null) state.putString("monogramMirrorText", entry.text());
                    if (entry.request() != null) state.putString("monogramMirrorRequestId", entry.request().toString());
                }
            }
            if (monogram != null) {
                state.putString("monogramPreferencePair", monogram.pair().toString());
                state.putString("monogramPreferenceEpoch", monogram.epoch().toString());
                state.putDouble("monogramPreferenceSourceTimestamp", monogram.sourceTimestamp());
                state.putLong("monogramPreferenceObservedAt", monogram.observedAt());
                if (monogram.text() != null) state.putString("monogramPreferenceText", monogram.text());
            }
            var pigments = dispatcher.pigmentPreferences(UUID.fromString(pair), epoch);
            var mirror = dispatcher.pigmentMirror(UUID.fromString(pair), epoch);
            if (mirror != null) {
                state.putInt("pigmentMirrorVersion", 2);
                state.putString("pigmentMirrorPair", mirror.pair().toString());
                state.putString("pigmentMirrorEpoch", mirror.epoch().toString());
                state.putString("pigmentMirrorOrigin", mirror.value().origin().name());
                state.putDouble("pigmentMirrorSourceTimestamp", mirror.value().sourceTimestamp());
                state.putLong("pigmentMirrorUpdatedAt", mirror.value().updatedAt());
                if (mirror.value().request() != null) state.putString("pigmentMirrorRequestId", mirror.value().request().toString());
                if (mirror.value().names() != null) state.putStringArrayList("pigmentMirrorNames", new java.util.ArrayList<>(mirror.value().names()));
                var automatic = mirror.automatic();
                if (automatic != null) {
                    state.putString("pigmentMirrorAutomaticOrigin", automatic.origin().name());
                    state.putDouble("pigmentMirrorAutomaticTimestamp", automatic.sourceTimestamp());
                    state.putLong("pigmentMirrorAutomaticUpdatedAt", automatic.updatedAt());
                    if (automatic.request() != null) state.putString("pigmentMirrorAutomaticRequestId", automatic.request().toString());
                    if (automatic.names() != null) state.putStringArrayList("pigmentMirrorAutomaticNames", new java.util.ArrayList<>(automatic.names()));
                }
            }
            if (pigments != null) {
                state.putString("pigmentPreferencePair", pigments.pair().toString());
                state.putString("pigmentPreferenceEpoch", pigments.epoch().toString());
                state.putDouble("pigmentPreferenceSourceTimestamp", pigments.sourceTimestamp());
                state.putLong("pigmentPreferenceObservedAt", pigments.observedAt());
                if (pigments.names() != null) state.putStringArrayList("pigmentPreferenceNames", new java.util.ArrayList<>(pigments.names()));
            }
        }
        for (var entry : dispatcher.watchSettings().entrySet()) {
            var value = entry.getValue();
            if (dispatcher.isConnected() && value.value() != null) {
                String prefix = "watchSetting_" + entry.getKey().name();
                state.putBoolean(prefix, value.value());
                state.putLong(prefix + "_observedAt", value.observedAt());
            }
        }
        state.putBoolean("phoneFindAvailable", OperationalWatchService.phonePingAvailable());
        state.putBoolean("phoneFlashPermission", checkSelfPermission(android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED);
        PhoneFindObservation phone = dispatcher.phoneFindObservation();
        state.putBoolean("phoneFindKnown", phone != null);
        if (phone != null) {
            state.putBoolean("phoneFindActive", phone.active());
            state.putInt("phoneFindBehavior", phone.behavior());
            state.putBoolean("phoneFindLocalProbe", phone.localProbe());
            state.putBoolean("phoneFindDidPlay", phone.didPlay());
            state.putLong("phoneFindObservedAt", phone.observedAt());
            if (phone.stopReason() != null) state.putString("phoneFindStopReason", phone.stopReason());
        }
        BridgeIpcDispatcher.DeviceObservation observation = dispatcher.deviceObservation();
        BridgeIpcDispatcher.BatteryObservation battery = observation.battery();
        // Explicit -1 clears a previously displayed reading on disconnect/absent data.
        state.putInt("batteryLevel", battery.percentage() == null ? -1 : battery.percentage());
        if (battery.charging() != null) state.putBoolean("isCharging", battery.charging());
        state.putBoolean("chargingObserved", battery.charging() != null);
        if (battery.observedAt() > 0) state.putLong("batteryObservedAt", battery.observedAt());
        NanoSystemSettingsDiagnostics.About about = observation.about();
        if (about != null) {
            state.putLong("aboutObservedAt", battery.observedAt());
            optionalLong(state, "availableStorageBytes", about.availableStorageBytes());
            optionalLong(state, "numberOfApps", about.apps());
            optionalLong(state, "numberOfSongs", about.songs());
            optionalLong(state, "numberOfPhotos", about.photos());
            optionalLong(state, "purgeableSpaceBytes", about.purgeableBytes());
            optionalLong(state, "userDeletableSpaceBytes", about.userDeletableBytes());
        }
        var faces = dispatcher.clockFaces();
        state.putBoolean("faceCollectionKnown", faces != null && dispatcher.isConnected());
        if (faces != null && dispatcher.isConnected()) {
            state.putString("faceCollectionPair", faces.pair().toString());
            state.putString("faceCollectionEpoch", faces.epoch().toString());
            state.putLong("faceCollectionObservedAt", faces.observedAt());
            byte[] catalog = new org.json.JSONObject(faces.complicationCatalog()).toString().getBytes(StandardCharsets.UTF_8);
            if (catalog.length <= NativeComplicationCatalog.MAX_BYTES) {
                state.putByteArray("faceComplicationCatalog", catalog);
                state.putBoolean("faceComplicationCatalogComplete", faces.complicationCatalogComplete());
            } else state.putBoolean("faceComplicationCatalogComplete", false);
            state.putBoolean("faceCollectionComplete", faces.complete());
            state.putBoolean("faceCollectionOrderKnown", faces.orderKnown());
            state.putBoolean("faceCollectionSelectionKnown", faces.selectionKnown());
            state.putStringArrayList("faceCollectionOrder", new java.util.ArrayList<>(faces.ordered()));
            if (faces.selectionKnown()) state.putString("activeFaceId", faces.selected());
            java.util.ArrayList<Bundle> rows = new java.util.ArrayList<>();
            for (var face : faces.faces()) {
                Bundle row = new Bundle();
                row.putString("id", face.id()); row.putString("bundle", face.bundle());
                row.putInt("configurationBytes", face.configurationBytes()); rows.add(row);
                row.putBoolean("archiveAvailable", face.archiveBase64() != null || face.packageDigest() != null);
                if (face.configuration() != null) row.putByteArray("configuration",
                        face.configuration().getBytes(StandardCharsets.UTF_8));
            }
            state.putParcelableArrayList("faceCollectionFaces", rows);
        }
        return state;
    }

    private static void optionalLong(Bundle state, String key, Long value) {
        if (value != null) state.putLong(key, value);
    }

    private boolean handleConnectionCommand(String method, Bundle data, String id, Messenger client) {
        if (!java.util.Set.of("connectWatch", "disconnectWatch", "beginPairing", "beginOpticalPairing", "replacePairOptically", "resumeSetup", "submitPin", "selectDiscoveredWatch",
                "activationResponse", "finishSetup", "confirmSetup", "auditStockBond", "importStockBond",
                "alignStockIdentity", "probeStockReconnect").contains(method == null ? "" : method)) return false;
        Bundle observed = CompanionSessionState.snapshot();
        if ("beginOpticalPairing".equals(method) || "replacePairOptically".equals(method)) {
            byte[] payload = data.getByteArray("opticalCode");
            try (OpticalPairingCode ignored = OpticalPairingCode.parse(payload)) { /* Validate before starting foreground setup. */ }
            catch (IllegalArgumentException invalid) {
                if (payload != null) java.util.Arrays.fill(payload, (byte) 0);
                reply(client, RESPONSE, response(id, "REJECTED"));
                return true;
            }
        }
        try {
        CompanionConnectionPolicy.Decision decision = CompanionConnectionPolicy.decide(method,
                new CompanionConnectionPolicy.State(
                    checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                    observed.getBoolean("identityKnown"), observed.getBoolean("hasPair"),
                    observed.getBoolean("activationConfirmed"), observed.getBoolean("operationalEligible"),
                    java.util.Objects.equals(observed.getString("pairId"), data.getString("pairId")),
                    OperationalWatchService.hasOwner() || "CONNECTING".equals(observed.getString("connectionStatus"))
                        || "STOPPING".equals(observed.getString("connectionStatus")),
                    BridgeSetupService.busy() || observed.getBoolean("setupRunning")));
        String status = decision.name();
        switch (decision) {
            case STOP_SESSION -> {
                if (OperationalWatchService.hasOwner()) { OperationalWatchService.stop(); status = "QUEUED"; }
                else status = BridgeSetupService.command(method, data) ? "QUEUED" : "UNAVAILABLE";
            }
            case FORWARD_SETUP -> status = BridgeSetupService.command(method, data) ? "QUEUED" : "REJECTED";
            case START_OPERATIONAL -> {
                try {
                    OperationalWatchService.start(this);
                    CompanionSessionState.connection("CONNECTING");
                    status = "QUEUED";
                } catch (RuntimeException unavailable) { status = "UNAVAILABLE"; }
            }
            case START_SETUP -> {
                Bundle starting = new Bundle();
                starting.putString("setupPhase", "STARTING"); starting.putBoolean("setupRunning", true);
                CompanionSessionState.setup(starting);
                try {
                    startForegroundService(new Intent(this, BridgeSetupService.class).putExtras(data));
                    status = "QUEUED";
                } catch (RuntimeException unavailable) {
                    starting.putBoolean("setupRunning", false); starting.putString("setupPhase", "FAILED");
                    CompanionSessionState.setup(starting);
                    status = "UNAVAILABLE";
                }
            }
            default -> { }
        }
        reply(client, RESPONSE, response(id, status));
        return true;
        } finally {
            byte[] payload = data.getByteArray("opticalCode");
            if (payload != null) Arrays.fill(payload, (byte) 0);
            data.remove("opticalCode");
        }
    }

    private static Bundle response(String id, String status) {
        Bundle result = new Bundle();
        result.putInt("version", 1); result.putString("requestId", id); result.putString("status", status);
        return result;
    }

    private void reply(Messenger client, int what, Bundle data) {
        Message reply = Message.obtain(null, what);
        reply.setData(data);
        try { client.send(reply); }
        catch (RemoteException dead) { remove(client.getBinder()); }
    }

    private void remove(IBinder binder) {
        Client client = clients.remove(binder);
        if (client != null) binder.unlinkToDeath(client.death, 0);
    }

    private void publishState() {
        main.post(() -> {
            Bundle snapshot = state();
            for (Client client : new java.util.ArrayList<>(clients.values())) reply(client.messenger, STATE, snapshot);
        });
    }

    @Override public void onConnectionStateChanged(boolean connected, String name) { publishState(); }
    @Override public void onBatteryChanged(int percentage, boolean charging) { publishState(); }
    @Override public void onActiveFaceChanged(String faceId) { publishState(); }
    @Override public void onPhoneFindStateChanged() { publishState(); }
    @Override public void onWatchSettingsChanged() { publishState(); }
    @Override public void onClockFacesChanged() { publishState(); }
    @Override public void onPigmentPreferencesChanged() { publishState(); }
    @Override public void onMonogramPreferencesChanged() { publishState(); }
    @Override public void onOperationStatus(BridgeCommandCodec.Status status) {
        main.post(() -> {
            Bundle receipt = response(status.id().toString(), status.stage().name());
            receipt.putString("epoch", status.epoch().toString());
            for (Client client : new java.util.ArrayList<>(clients.values())) reply(client.messenger, 103, receipt);
        });
    }

    @Override public void onDestroy() {
        faceTransfers.close();
        CompanionSessionState.remove(sessionListener);
        BridgeIpcDispatcher.getInstance().removeStateListener(this);
        BridgeIpcDispatcher.getInstance().removeOperationListener(this);
        BridgeIpcDispatcher.getInstance().removePhoneFindListener(this);
        BridgeIpcDispatcher.getInstance().removeWatchSettingsListener(this);
        BridgeIpcDispatcher.getInstance().removeClockFacesListener(this);
        BridgeIpcDispatcher.getInstance().removePigmentPreferencesListener(this);
        BridgeIpcDispatcher.getInstance().removeMonogramPreferencesListener(this);
        for (IBinder binder : new java.util.ArrayList<>(clients.keySet())) remove(binder);
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
