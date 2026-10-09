package dev.applewatchandroid.bridge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central event bus and dispatcher bridging Android OS services, UI layers,
 * and the low-level Apple IDS transport session.
 */
public final class BridgeIpcDispatcher {
    private static final BridgeIpcDispatcher INSTANCE = new BridgeIpcDispatcher();

    public static BridgeIpcDispatcher getInstance() {
        return INSTANCE;
    }

    private volatile String notificationReplyLabel = "Reply";
    private volatile String notificationSendLabel = "Send";
    /** Android supplies localized UI labels; protocol identifiers remain stable. */
    public void setNotificationActionLabels(String reply, String send) {
        if (reply == null || send == null || reply.length() > 256 || send.length() > 256) {
            throw new IllegalArgumentException("Invalid notification action labels");
        }
        notificationReplyLabel = reply;
        notificationSendLabel = send;
    }

    public interface OnNotificationActionListener {
        void onDismiss(String publisherBulletinId, String recordId, String sectionId);
        void onReply(String publisherBulletinId, String recordId, String sectionId, String identifier, String replyText);
        void onLightsObserved(String publisherBulletinId, String sectionId, String replyToken, Boolean played);
    }

    /** Synchronous listeners must copy payloads retained beyond this call. */
    public interface OnApplicationMessageListener {
        void onApplicationMessage(BridgeApplicationEventCodec.Event event);
    }
    interface OnOperationStatusListener { void onOperationStatus(BridgeCommandCodec.Status status); }
    private final CopyOnWriteArrayList<OnOperationStatusListener> operationListeners = new CopyOnWriteArrayList<>();
    void addOperationListener(OnOperationStatusListener listener) { operationListeners.addIfAbsent(listener); }
    void removeOperationListener(OnOperationStatusListener listener) { operationListeners.remove(listener); }
    void dispatchOperationStatus(BridgeCommandCodec.Status status) {
        for (OnOperationStatusListener listener : operationListeners) {
            try { listener.onOperationStatus(status); } catch (RuntimeException ignored) { }
        }
    }
    private final CopyOnWriteArrayList<OnApplicationMessageListener> applicationListeners = new CopyOnWriteArrayList<>();
    interface OnMonogramPreferencesListener { void onMonogramPreferencesChanged(); }
    private final CopyOnWriteArrayList<OnMonogramPreferencesListener> monogramListeners = new CopyOnWriteArrayList<>();
    private final MonogramPreferenceObservation monograms = new MonogramPreferenceObservation();
    void addMonogramPreferencesListener(OnMonogramPreferencesListener listener) { monogramListeners.addIfAbsent(listener); }
    void removeMonogramPreferencesListener(OnMonogramPreferencesListener listener) { monogramListeners.remove(listener); }
    MonogramPreferenceObservation.Value monogramPreferences(UUID pair, UUID epoch) { return monograms.snapshot(pair, epoch); }
    private volatile MonogramPreferenceMirror.Snapshot monogramMirror;
    MonogramPreferenceMirror.Snapshot monogramMirror(UUID pair, UUID epoch) {
        var value = monogramMirror;
        return value != null && value.pair().equals(pair) && value.epoch().equals(epoch) ? value : null;
    }
    void observeMonogramMirror(MonogramPreferenceMirror.Snapshot next) {
        if (!isConnected || !next.writable(System.currentTimeMillis())) return;
        var old = monogramMirror;
        if (Objects.equals(old, next)) return;
        if (old != null && old.pair().equals(next.pair()) && old.epoch().equals(next.epoch()) && old.value() != null) {
            if (next.value() == null || next.value().sourceTimestamp() < old.value().sourceTimestamp()) return;
            if (next.value().sourceTimestamp() == old.value().sourceTimestamp()
                && (!Objects.equals(next.value().text(), old.value().text())
                    || old.value().origin() == MonogramPreferenceMirror.Origin.REMOTE
                    && next.value().origin() == MonogramPreferenceMirror.Origin.LOCAL)) return;
        }
        monogramMirror = next;
        for (var listener : monogramListeners) {
            try { listener.onMonogramPreferencesChanged(); } catch (RuntimeException ignored) { }
        }
    }
    interface OnPigmentPreferencesListener { void onPigmentPreferencesChanged(); }
    private final CopyOnWriteArrayList<OnPigmentPreferencesListener> pigmentListeners = new CopyOnWriteArrayList<>();
    private final PigmentPreferenceObservation pigments = new PigmentPreferenceObservation();
    private volatile PigmentPreferenceMirror.Snapshot pigmentMirror;
    PigmentPreferenceMirror.Snapshot pigmentMirror(UUID pair, UUID epoch) {
        var value = pigmentMirror;
        return value != null && value.pair().equals(pair) && value.epoch().equals(epoch) ? value : null;
    }
    void observePigmentMirror(PigmentPreferenceMirror.Snapshot next) {
        if (!isConnected) return;
        var old = pigmentMirror;
        if (Objects.equals(old, next)) return;
        if (old != null && old.pair().equals(next.pair()) && old.epoch().equals(next.epoch())) {
            if (next.value().sourceTimestamp() < old.value().sourceTimestamp()) return;
            if (old.automatic() != null && (next.automatic() == null
                    || next.automatic().sourceTimestamp() < old.automatic().sourceTimestamp())) return;
        }
        pigmentMirror = next;
        for (var listener : pigmentListeners) {
            try { listener.onPigmentPreferencesChanged(); } catch (RuntimeException ignored) { }
        }
    }
    void addPigmentPreferencesListener(OnPigmentPreferencesListener listener) { pigmentListeners.addIfAbsent(listener); }
    void removePigmentPreferencesListener(OnPigmentPreferencesListener listener) { pigmentListeners.remove(listener); }
    PigmentPreferenceObservation.Value pigmentPreferences(UUID pair, UUID epoch) { return pigments.snapshot(pair, epoch); }
    void observeNativePreferences(BridgeApplicationEventCodec.Event event, UUID pair, UUID epoch, long now) {
        if (!isConnected || pair == null || epoch == null ||
                !PigmentPreferenceCodec.isObservationEnvelope(event.topic, event.protobufType, event.response)) return;
        // Type 2 is NPSUserDefaultsBackupMsg: no source timestamp/two-way fields.
        // It cannot be treated as an ordered current preference/write baseline.
        byte[] payload = event.payload();
        try (var envelope = NtkPreferenceEnvelope.decode(payload, now)) {
            var colorReports = PigmentPreferenceCodec.decodeObservedLists(envelope);
            var textReports = MonogramPreferenceCodec.decodeObserved(envelope);
            boolean colorsChanged = pigments.observe(pair, epoch,
                    colorReports.getOrDefault(PigmentPreferenceCodec.KEY, List.of()), now);
            boolean textChanged = monograms.observe(pair, epoch, textReports, now);
            // Publish only after both projections have consumed the same owned receipt.
            if (colorsChanged) {
                for (var listener : pigmentListeners) {
                    try { listener.onPigmentPreferencesChanged(); } catch (RuntimeException ignored) { }
                }
            }
            if (textChanged) {
                for (var listener : monogramListeners) {
                    try { listener.onMonogramPreferencesChanged(); } catch (RuntimeException ignored) { }
                }
            }
        } catch (IllegalArgumentException invalid) { /* Keep the prior complete observation. */ }
        finally { java.util.Arrays.fill(payload, (byte) 0); }
    }
    public void addApplicationListener(OnApplicationMessageListener listener) {
        if (listener != null) applicationListeners.addIfAbsent(listener);
    }
    public void removeApplicationListener(OnApplicationMessageListener listener) {
        applicationListeners.remove(listener);
    }
    public void dispatchApplicationMessage(BridgeApplicationEventCodec.Event event) {
        if (IdsApplicationRoute.PREFERENCE_SYNC_SERVICE.equals(event.topic)
                && event.protobufType == 0 && !event.response && isConnected) {
            byte[] payload = event.payload();
            try {
                long now = System.currentTimeMillis();
                if (watchSettings.observe(WatchSettingsCodec.decodeObserved(payload, now), now)) {
                    for (OnWatchSettingsListener listener : watchSettingsListeners) {
                        try { listener.onWatchSettingsChanged(); } catch (RuntimeException ignored) { }
                    }
                }
            } catch (IllegalArgumentException invalid) { /* Keep prior valid reports. */ }
            finally { java.util.Arrays.fill(payload, (byte) 0); }
        }
        if (NanoSystemSettingsDiagnostics.TOPIC.equals(event.topic)
                && event.protobufType == NanoSystemSettingsDiagnostics.ABOUT_RESPONSE && event.response) {
            // OperationalWatchService accepts this only from its child HAL. The HAL
            // forwards About only after exact peer-response UUID/type correlation.
            byte[] payload = event.payload();
            try { observeAbout(NanoSystemSettingsDiagnostics.decodeAbout(payload), System.currentTimeMillis()); }
            catch (IllegalArgumentException invalid) { /* Retain the previous valid observation. */ }
            finally { java.util.Arrays.fill(payload, (byte) 0); }
        }
        if (IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(event.topic)) {
            byte[] payload = event.payload();
            try {
                BulletinActionDecoder.Action action = BulletinActionDecoder.decode(event.protobufType, event.response, payload);
                if (action != null && action.kind() == BulletinActionDecoder.Kind.DISMISS) {
                    dispatchNotificationDismiss(action.publisherId(), action.recordId(), action.sectionId());
                } else if (action != null && action.kind() == BulletinActionDecoder.Kind.REPLY) {
                    dispatchNotificationReply(action.publisherId(), action.recordId(), action.sectionId(), action.identifier(), action.replyText());
                }
                if (action != null && action.kind() == BulletinActionDecoder.Kind.LIGHTS_OBSERVED && action.played() != null) {
                    dispatchLightsObserved(action.publisherId(), action.sectionId(), action.replyToken(), action.played());
                }
            } catch (IllegalArgumentException invalid) {
                // Never execute an Android effect from malformed or unrecognized native data.
            } finally { java.util.Arrays.fill(payload, (byte) 0); }
        }
        for (OnApplicationMessageListener listener : applicationListeners) {
            try { listener.onApplicationMessage(event); }
            catch (Exception ignored) { /* Keep independent consumers alive. */ }
        }
    }

    public interface OnFindMyPhoneListener {
        void onPingPhoneRequested();
    }

    public interface OnCallActionListener {
        void onCallAction(String callId, int action);
    }

    public interface OnDeviceStateListener {
        void onConnectionStateChanged(boolean connected, String deviceName);
        void onBatteryChanged(int percentage, boolean isCharging);
        void onActiveFaceChanged(String faceId);
    }

    private final List<OnNotificationActionListener> notificationListeners = new CopyOnWriteArrayList<>();
    private final List<OnFindMyPhoneListener> findMyPhoneListeners = new CopyOnWriteArrayList<>();
    private final List<OnCallActionListener> callActionListeners = new CopyOnWriteArrayList<>();
    private final List<OnDeviceStateListener> stateListeners = new CopyOnWriteArrayList<>();

    private volatile boolean isConnected = false;
    record BatteryObservation(Integer percentage, Boolean charging, long observedAt) { }
    record DeviceObservation(NanoSystemSettingsDiagnostics.About about, BatteryObservation battery) { }
    private volatile DeviceObservation deviceObservation = new DeviceObservation(null,
            new BatteryObservation(null, null, 0));
    private volatile String currentFaceId;
    private volatile ClockFaceObservationCodec.Observation clockFaces;
    interface OnClockFacesListener { void onClockFacesChanged(); }
    private final CopyOnWriteArrayList<OnClockFacesListener> clockFaceListeners = new CopyOnWriteArrayList<>();
    ClockFaceObservationCodec.Observation clockFaces() { return clockFaces; }
    void addClockFacesListener(OnClockFacesListener listener) { clockFaceListeners.addIfAbsent(listener); }
    void removeClockFacesListener(OnClockFacesListener listener) { clockFaceListeners.remove(listener); }
    void observeClockFaces(ClockFaceObservationCodec.Observation value) {
        if (!isConnected) return;
        var previous = clockFaces;
        if (previous != null && previous.pair().equals(value.pair()) && previous.epoch().equals(value.epoch())
                && value.observedAt() < previous.observedAt()) return;
        clockFaces = value;
        updateActiveFace(value.selectionKnown() ? value.selected() : null);
        for (OnClockFacesListener listener : clockFaceListeners) {
            try { listener.onClockFacesChanged(); } catch (RuntimeException ignored) { }
        }
    }
    interface OnWatchSettingsListener { void onWatchSettingsChanged(); }
    private final CopyOnWriteArrayList<OnWatchSettingsListener> watchSettingsListeners = new CopyOnWriteArrayList<>();
    private final WatchSettingsObservation watchSettings = new WatchSettingsObservation();
    java.util.Map<WatchSettingsCodec.Setting, WatchSettingsObservation.Value> watchSettings() {
        return watchSettings.snapshot();
    }
    void addWatchSettingsListener(OnWatchSettingsListener listener) { watchSettingsListeners.addIfAbsent(listener); }
    void removeWatchSettingsListener(OnWatchSettingsListener listener) { watchSettingsListeners.remove(listener); }
    interface OnPhoneFindStateListener { void onPhoneFindStateChanged(); }
    private final CopyOnWriteArrayList<OnPhoneFindStateListener> phoneFindListeners = new CopyOnWriteArrayList<>();
    private volatile PhoneFindObservation phoneFindObservation;
    PhoneFindObservation phoneFindObservation() { return phoneFindObservation; }
    void addPhoneFindListener(OnPhoneFindStateListener listener) { phoneFindListeners.addIfAbsent(listener); }
    void removePhoneFindListener(OnPhoneFindStateListener listener) { phoneFindListeners.remove(listener); }
    void observePhoneFind(PhoneFindObservation observation) {
        phoneFindObservation = observation;
        refreshPhoneFindState();
    }
    void refreshPhoneFindState() {
        for (OnPhoneFindStateListener listener : phoneFindListeners) {
            try { listener.onPhoneFindStateChanged(); } catch (RuntimeException ignored) { }
        }
    }

    private BridgeIpcDispatcher() {
    }

    // --- Listener Registration ---

    public void addNotificationListener(OnNotificationActionListener listener) {
        if (listener != null) notificationListeners.add(listener);
    }

    public void removeNotificationListener(OnNotificationActionListener listener) {
        notificationListeners.remove(listener);
    }

    public void addFindMyPhoneListener(OnFindMyPhoneListener listener) {
        if (listener != null) findMyPhoneListeners.add(listener);
    }

    public void removeFindMyPhoneListener(OnFindMyPhoneListener listener) {
        findMyPhoneListeners.remove(listener);
    }

    public void addCallActionListener(OnCallActionListener listener) {
        if (listener != null) callActionListeners.add(listener);
    }

    public void removeCallActionListener(OnCallActionListener listener) {
        callActionListeners.remove(listener);
    }

    public void addStateListener(OnDeviceStateListener listener) {
        if (listener != null) {
            stateListeners.add(listener);
        listener.onConnectionStateChanged(isConnected, "Apple Watch");
            BatteryObservation snapshot = deviceObservation.battery();
            if (snapshot.percentage() != null) listener.onBatteryChanged(snapshot.percentage(), Boolean.TRUE.equals(snapshot.charging()));
            if (currentFaceId != null) listener.onActiveFaceChanged(currentFaceId);
        }
    }

    public void removeStateListener(OnDeviceStateListener listener) {
        stateListeners.remove(listener);
    }

    // --- Dispatch incoming events from Watch ---

    public void dispatchNotificationDismiss(String publisherBulletinId, String recordId, String sectionId) {
        for (OnNotificationActionListener l : notificationListeners) {
            try {
                l.onDismiss(publisherBulletinId, recordId, sectionId);
            } catch (Exception ignored) {}
        }
    }

    public void dispatchNotificationReply(String publisherBulletinId, String recordId, String sectionId, String identifier, String replyText) {
        for (OnNotificationActionListener l : notificationListeners) {
            try {
                l.onReply(publisherBulletinId, recordId, sectionId, identifier, replyText);
            } catch (Exception ignored) {}
        }
    }

    public void dispatchLightsObserved(String publisherBulletinId, String sectionId, String replyToken, Boolean played) {
        for (OnNotificationActionListener l : notificationListeners) {
            try {
                l.onLightsObserved(publisherBulletinId, sectionId, replyToken, played);
            } catch (Exception ignored) {}
        }
    }

    public void dispatchPingPhone() {
        for (OnFindMyPhoneListener l : findMyPhoneListeners) {
            try {
                l.onPingPhoneRequested();
            } catch (Exception ignored) {}
        }
    }

    public void dispatchCallAction(String callId, int action) {
        for (OnCallActionListener l : callActionListeners) {
            try {
                l.onCallAction(callId, action);
            } catch (Exception ignored) {}
        }
    }

    public void updateConnectionState(boolean connected, String deviceName) {
        if (!connected) deviceObservation = new DeviceObservation(null, new BatteryObservation(null, null, 0));
        if (!connected) { clockFaces = null; currentFaceId = null; }
        this.isConnected = connected;
        if (!connected) watchSettings.clear();
        if (!connected) { pigments.clear(); pigmentMirror = null; }
        if (!connected) { monograms.clear(); monogramMirror = null; }
        for (OnDeviceStateListener l : stateListeners) {
            try {
                l.onConnectionStateChanged(connected, deviceName);
            } catch (Exception ignored) {}
        }
    }

    public void updateBattery(int percent, boolean charging) {
        if (percent < 0 || percent > 100) throw new IllegalArgumentException("Invalid battery percentage");
        publishDevice(new DeviceObservation(null, new BatteryObservation(percent, charging, System.currentTimeMillis())));
    }

    void observeAbout(NanoSystemSettingsDiagnostics.About about, long observedAt) {
        Long capacity = about.batteryCapacity();
        // Exact MobileGestalt uses IOPSGetPercentRemaining; 101 is its failure sentinel.
        Integer percent = capacity != null && capacity >= 0 && capacity <= 100 ? capacity.intValue() : null;
        publishDevice(new DeviceObservation(about, new BatteryObservation(percent, about.charging(), observedAt)));
    }

    BatteryObservation batteryObservation() { return deviceObservation.battery(); }
    DeviceObservation deviceObservation() { return deviceObservation; }

    private void publishDevice(DeviceObservation device) {
        deviceObservation = device;
        BatteryObservation observation = device.battery();
        for (OnDeviceStateListener l : stateListeners) {
            try {
                l.onBatteryChanged(observation.percentage() == null ? -1 : observation.percentage(),
                        Boolean.TRUE.equals(observation.charging()));
            } catch (Exception ignored) {}
        }
    }

    public void updateActiveFace(String faceId) {
        this.currentFaceId = faceId;
        for (OnDeviceStateListener l : stateListeners) {
            try {
                l.onActiveFaceChanged(faceId);
            } catch (Exception ignored) {}
        }
    }

    // --- Outgoing Commands from Android / Companion to Watch ---

    public synchronized byte[] createBulletinPayload(
            String title,
            String message,
            String sectionId,
            String sectionDisplayName,
            boolean sound) {
        return createBulletinPayload(title, message, sectionId, sectionDisplayName, sound, null, null, null);
    }

    public synchronized byte[] createBulletinPayload(String title, String message, String sectionId,
            String sectionDisplayName, boolean sound, String publisherId, String recordId, String replyToken) {
        return createBulletinPayload(title, message, sectionId, sectionDisplayName, sound, publisherId, recordId, replyToken, null);
    }
    public synchronized byte[] createBulletinPayload(String title, String message, String sectionId,
            String sectionDisplayName, boolean sound, String publisherId, String recordId, String replyToken, String replyActionId) {
        BulletinDistributorCodec.Bulletin bulletin = new BulletinDistributorCodec.Bulletin(
                UUID.randomUUID().toString(),
                sectionId,
                sectionDisplayName,
                title,
                null,
                message,
                publisherId != null ? publisherId : UUID.randomUUID().toString(),
                recordId != null ? recordId : UUID.randomUUID().toString(),
                replyToken != null ? replyToken : UUID.randomUUID().toString(),
                sound,
                2,
                System.currentTimeMillis());
        if (replyActionId != null) bulletin.withTextInputAction(new BulletinDistributorCodec.TextInputAction(
                replyActionId, notificationReplyLabel, notificationSendLabel));

        BulletinDistributorCodec.BulletinRequest request =
                new BulletinDistributorCodec.BulletinRequest(bulletin, sound, System.currentTimeMillis(), 0);

        // HAL owns the native session UUID/state/sequence and appends its trailer at send time.
        return request.encode();
    }

    public byte[] createActiveFaceSyncPayload(String faceId) {
        PreferencesSyncCodec.UserDefaultsMessage msg =
                PreferencesSyncCodec.createActiveFaceSync(faceId);
        return msg.encode();
    }

    public byte[] createPingWatchPayload() {
        return new FindMyLocalDeviceCodec.PlaySoundRequest(System.currentTimeMillis() / 1000.0, null).encode();
    }

    public byte[] createIncomingCallPayload(String callId, String callerName, String callerNumber, boolean isVideo) {
        TelephonyRelayCodec.IncomingCallAlert alert =
                new TelephonyRelayCodec.IncomingCallAlert(callId, callerName, callerNumber, isVideo);
        return alert.encode();
    }

    public boolean isConnected() {
        return isConnected;
    }

    public int getBatteryPercentage() {
        Integer value = deviceObservation.battery().percentage();
        return value == null ? -1 : value;
    }

    public boolean isCharging() {
        return Boolean.TRUE.equals(deviceObservation.battery().charging());
    }

    public interface OutboundTransportSink {
        void sendApplicationProtobuf(String topic, int protobufType, byte[] payload);
    }

    private OutboundTransportSink outboundSink;

    public void setOutboundSink(OutboundTransportSink sink) {
        this.outboundSink = sink;
    }

    public OutboundTransportSink getOutboundSink() {
        return outboundSink;
    }

    public boolean sendBulletin(
            String title,
            String message,
            String sectionId,
            String sectionDisplayName,
            boolean sound) {
        byte[] payload = createBulletinPayload(title, message, sectionId, sectionDisplayName, sound);
        if (outboundSink != null) {
            outboundSink.sendApplicationProtobuf(
                    IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE,
                    BulletinDistributorCodec.TYPE_ADD_BULLETIN,
                    payload);
            return true;
        }
        return false;
    }

    public boolean sendPingWatch() {
        byte[] payload = createPingWatchPayload();
        if (outboundSink != null) {
            outboundSink.sendApplicationProtobuf(
                    IdsApplicationRoute.FIND_MY_LOCAL_SERVICE,
                    FindMyLocalDeviceCodec.TYPE_PLAY_SOUND,
                    payload);
            return true;
        }
        return false;
    }

    public boolean sendActiveFace(String faceId) {
        byte[] payload = createActiveFaceSyncPayload(faceId);
        if (outboundSink != null) {
            outboundSink.sendApplicationProtobuf(
                    IdsApplicationRoute.PREFERENCE_SYNC_SERVICE,
                    0,
                    payload);
            return true;
        }
        return false;
    }

    public boolean sendIncomingCall(String callId, String callerName, String callerNumber, boolean isVideo) {
        byte[] payload = createIncomingCallPayload(callId, callerName, callerNumber, isVideo);
        if (outboundSink != null) {
            outboundSink.sendApplicationProtobuf(
                    IdsApplicationRoute.TELEPHONY_SERVICE,
                    1,
                    payload);
            return true;
        }
        return false;
    }

    public String getCurrentFaceId() {
        return currentFaceId;
    }
}
