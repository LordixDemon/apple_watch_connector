package dev.applewatchandroid.bridge;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Android NotificationListenerService that mirrors phone notifications to the Apple Watch,
 * dismisses Android notifications when swiped away on the watch, and sends quick replies.
 */
public final class AppleWatchNotificationListenerService
        extends NotificationListenerService
        implements BridgeIpcDispatcher.OnNotificationActionListener {

    static final String LOCAL_PROBE_CHANNEL = "watch-local-reply-probe";
    private final Map<String, StatusBarNotification> activeBulletins = new ConcurrentHashMap<>();
    private static volatile AppleWatchNotificationListenerService active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationMirrorQueue queue;
    private NotificationMirrorStore store;
    private String queuePairing;
    private boolean listenerReady;
    private boolean androidListenerConnected;
    private boolean reconciling;
    private UUID epoch;
    private record Flight(UUID revision, UUID epoch) { }
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Set<UUID> attempted = new HashSet<>();

    static void transportState(UUID readyEpoch) {
        var listener = active;
        if (listener != null) listener.main.post(() -> { if (active == listener) listener.setEpoch(readyEpoch); });
    }
    static void operationStatus(BridgeCommandCodec.Status status) {
        var listener = active;
        if (listener != null) listener.main.post(() -> { if (active == listener) listener.receipt(status); });
    }
    private boolean initializeQueue() {
        if (queue != null) return true;
        try {
            String pairing = new BridgeIdentityStore(this, ignored -> { }).operationalPairing();
            if (pairing == null) return false;
            store = new NotificationMirrorStore(this, pairing);
            queue = store.load();
            queuePairing = pairing;
            android.util.Log.i("WatchNotification", "MIRROR_JOURNAL_LOADED entries=" + queue.entries().size()
                    + " pending=" + queue.pending().size() + "; pairingBound=true contentLogged=false.");
            return true;
        } catch (Exception unavailable) {
            android.util.Log.w("WatchNotification", "MIRROR_JOURNAL_UNAVAILABLE; forwarding paused, existing file retained.");
            return false;
        }
    }
    /** Persist a complete next snapshot before replacing live state or transmitting it. */
    private boolean commit(java.util.function.Function<NotificationMirrorQueue, Boolean> change) {
        if (!initializeQueue()) return false;
        byte[] snapshot = null;
        NotificationMirrorQueue next = null;
        try {
            snapshot = queue.encode(); next = NotificationMirrorQueue.decode(snapshot);
            if (!change.apply(next)) return false;
            store.save(next);
            var old = queue; queue = next; next = null; old.destroy();
            android.util.Log.i("WatchNotification", "MIRROR_JOURNAL_COMMITTED entries=" + queue.entries().size()
                    + " pending=" + queue.pending().size() + "; contentLogged=false.");
            return true;
        } catch (Exception failed) {
            android.util.Log.w("WatchNotification", "MIRROR_COMMIT_FAILED; previous durable state retained, contentLogged=false.");
            return false;
        } finally { if (snapshot != null) Arrays.fill(snapshot, (byte) 0); if (next != null) next.destroy(); }
    }
    private void setEpoch(UUID readyEpoch) {
        if (!java.util.Objects.equals(epoch, readyEpoch)) { flights.clear(); attempted.clear(); epoch = readyEpoch; }
        if (readyEpoch != null && queue != null) {
            try {
                String pairing = new BridgeIdentityStore(this, ignored -> { }).operationalPairing();
                if (!java.util.Objects.equals(queuePairing, pairing)) {
                    queue.destroy(); queue = null; store = null; queuePairing = null;
                    listenerReady = false; flights.clear(); attempted.clear();
                }
            } catch (Exception unavailable) { epoch = null; return; }
        }
        if (readyEpoch != null && androidListenerConnected && !listenerReady) onListenerConnected();
        flush();
    }
    private void flush() {
        if (!listenerReady || epoch == null || !initializeQueue() || !epoch.equals(OperationalWatchService.readyEpoch())) return;
        var pending = queue.pending();
        Set<UUID> revisions = new HashSet<>();
        for (var e : pending) revisions.add(e.revision());
        attempted.retainAll(revisions);
        for (var entry : pending) {
            if (flights.size() >= 8) break;
            if (attempted.contains(entry.revision())) continue;
            UUID request = UUID.randomUUID();
            String command = (entry.removal() ? "REMOVE_BULLETIN:" : "SEND_BULLETIN:")
                    + Base64.encodeToString(entry.payload(), Base64.NO_WRAP);
            if (!OperationalWatchService.sendCommand(command, request, epoch)) break;
            attempted.add(entry.revision()); flights.put(request, new Flight(entry.revision(), epoch));
            android.util.Log.i("WatchNotification", "MIRROR_REQUEST_ENQUEUED id=" + request
                    + " removal=" + entry.removal() + "; receipt/effect unconfirmed.");
        }
    }
    private void receipt(BridgeCommandCodec.Status status) {
        Flight flight = flights.get(status.id());
        if (flight == null || !flight.epoch.equals(status.epoch()) || !flight.epoch.equals(epoch)) return;
        switch (status.stage()) {
            case APP_ACK_RECEIVED -> {
                flights.remove(status.id());
                boolean saved = commit(next -> next.acknowledge(flight.revision));
                android.util.Log.i("WatchNotification", "MIRROR_IDS_RECEIPT id=" + status.id()
                        + " persisted=" + saved + "; visible/applied inferred=false.");
                flush();
            }
            case REJECTED, EXPIRED, FAILED, UNKNOWN -> {
                flights.remove(status.id());
                // Retain durable work; retry after a new epoch, avoiding an unbounded hot loop.
                android.util.Log.i("WatchNotification", "MIRROR_RETRY_PENDING id=" + status.id() + "; nextEpoch=true.");
                flush();
            }
            default -> { }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        active = this;
        initializeQueue();
        BridgeIpcDispatcher.getInstance().addNotificationListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        BridgeIpcDispatcher.getInstance().removeNotificationListener(this);
        if (active == this) active = null;
        listenerReady = false; flights.clear(); attempted.clear();
        main.removeCallbacksAndMessages(null);
        if (queue != null) queue.destroy();
        activeBulletins.clear();
    }

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        androidListenerConnected = true;
        if (!initializeQueue()) return;
        StatusBarNotification[] snapshot;
        try { snapshot = getActiveNotifications(); }
        catch (RuntimeException denied) { listenerReady = false; return; }
        if (snapshot == null) { listenerReady = false; return; }
        Set<String> current = new HashSet<>();
        activeBulletins.clear();
        for (var sbn : snapshot) if (sbn != null && shouldForwardNotification(sbn)) current.add(sbn.getKey());
        // Reconcile deletions that occurred while this process/listener was absent.
        commit(next -> {
            boolean changed = false;
            for (var entry : next.entries()) if (!entry.removal() && !current.contains(entry.identity().androidKey()))
                changed |= next.remove(entry.identity().androidKey());
            return changed;
        });
        listenerReady = true;
        epoch = OperationalWatchService.readyEpoch();
        reconciling = true;
        try { for (var sbn : snapshot) onNotificationPosted(sbn); }
        finally { reconciling = false; }
        android.util.Log.i("WatchNotification", "MIRROR_RECONCILED active=" + activeBulletins.size()
                + " pending=" + queue.pending().size() + "; originalAndroidActions=true.");
        flush();
    }
    @Override public void onListenerDisconnected() {
        androidListenerConnected = false; listenerReady = false; activeBulletins.clear(); flights.clear(); attempted.clear();
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !shouldForwardNotification(sbn)) {
            return;
        }

        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if (extras == null) return;

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        String title = limited(titleCs, 4096);
        String text = limited(textCs, 16384);

        if (title.isEmpty() && text.isEmpty()) {
            return;
        }

        String pkg = sbn.getPackageName();
        String appName = resolveAppDisplayName(pkg);
        String bulletinKey = sbn.getKey();
        if (!initializeQueue()) return;
        activeBulletins.put(bulletinKey, sbn);
        // One durable forwarding path. Current PendingIntent is held only by Android/in memory.
        try {
            boolean reply = uniqueReplyTarget(n) != null;
            boolean known = queue.hasActive(bulletinKey);
            boolean sound = (!reconciling || !known) && ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0 || !known);
            byte[] fingerprint = fingerprint(title, text, appName, reply, sbn.getPostTime(), n.flags & Notification.FLAG_ONLY_ALERT_ONCE);
            try {
                commit(next -> next.upsert(bulletinKey, pkg, fingerprint, identity ->
                        BridgeIpcDispatcher.getInstance().createBulletinPayload(title.isEmpty() ? appName : title, text,
                                pkg, appName, sound, identity.publisherId(), identity.recordId(), identity.replyToken(),
                                reply ? NotificationMirrorQueue.replyIdentifier(identity) : null)));
            } finally { Arrays.fill(fingerprint, (byte) 0); }
            flush();
        } catch (Exception invalid) { android.util.Log.w("WatchNotification", "MIRROR_POST_REJECTED; contentLogged=false."); }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) {
            activeBulletins.remove(sbn.getKey());
            commit(next -> next.remove(sbn.getKey()));
            flush();
        }
    }

    @Override
    public void onDismiss(String publisherBulletinId, String recordId, String sectionId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> { if (active == this) onDismiss(publisherBulletinId, recordId, sectionId); }); return;
        }
        NotificationIdentityIndex.Identity identity = queue == null ? null : queue.match(publisherBulletinId, recordId, sectionId);
        if (identity == null || !activeBulletins.containsKey(identity.androidKey())) return;
        if (!commit(next -> next.claimDismiss(publisherBulletinId, recordId, sectionId))) return;
        try {
            cancelNotification(identity.androidKey());
            android.util.Log.i("WatchNotification", "DISMISS_ANDROID_REQUESTED; removal callback still required.");
        } catch (RuntimeException rejected) {
            android.util.Log.w("WatchNotification", "DISMISS_OUTCOME_UNKNOWN; claim retained to prevent replay.");
        }
    }

    @Override
    public void onReply(String publisherBulletinId, String recordId, String sectionId, String identifier, String replyText) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> { if (active == this) onReply(publisherBulletinId, recordId, sectionId, identifier, replyText); }); return;
        }
        if (replyText == null || replyText.trim().isEmpty() || replyText.length() > 4096) return;
        NotificationIdentityIndex.Identity identity = queue == null ? null : queue.match(publisherBulletinId, recordId, sectionId);
        if (identity == null || !NotificationMirrorQueue.replyIdentifier(identity).equals(identifier)) return;
        StatusBarNotification sbn = activeBulletins.get(identity.androidKey());
        if (sbn == null || sbn.getNotification().actions == null) return;
        ReplyTarget target = uniqueReplyTarget(sbn.getNotification());
        if (target == null || !commit(next -> next.claimReply(publisherBulletinId, recordId, sectionId, identifier))) return;
        sendQuickReply(target.action.actionIntent, target.input, replyText);
    }

    private record ReplyTarget(Notification.Action action, RemoteInput input) { }
    private ReplyTarget uniqueReplyTarget(Notification notification) {
        if (notification.actions == null) return null;
        ReplyTarget selected = null;
        // Ambiguous actions or multiple free-text inputs are not guessed.
        for (Notification.Action action : notification.actions) {
            if (action.actionIntent == null || action.getRemoteInputs() == null) continue;
            for (RemoteInput input : action.getRemoteInputs()) {
                if (!input.getAllowFreeFormInput()) continue;
                if (selected != null) return null;
                selected = new ReplyTarget(action, input);
            }
        }
        return selected;
    }

    @Override
    public void onLightsObserved(String publisherBulletinId, String sectionId, String replyToken, Boolean played) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> { if (active == this) onLightsObserved(publisherBulletinId, sectionId, replyToken, played); }); return;
        }
        NotificationIdentityIndex.Identity identity = queue == null ? null : queue.matchLights(publisherBulletinId, sectionId, replyToken);
        if (identity != null && played != null) {
            android.util.Log.i("WatchNotification", "LIGHTS_OBSERVED played=" + played
                    + " exactNotification=true; identifiers/content logged=false.");
        }
    }

    private void sendQuickReply(PendingIntent pendingIntent, RemoteInput input, String replyText) {
        try {
            Intent replyIntent = new Intent();
            Bundle bundle = new Bundle();
            bundle.putCharSequence(input.getResultKey(), replyText);
            RemoteInput.addResultsToIntent(
                    new RemoteInput[]{input},
                    replyIntent,
                    bundle);
            pendingIntent.send(this, 0, replyIntent);
            android.util.Log.i("WatchNotification", "REPLY_ANDROID_INTENT_SENT; recipient acceptance inferred=false, contentLogged=false.");
        } catch (Exception rejected) {
            android.util.Log.w("WatchNotification", "REPLY_OUTCOME_UNKNOWN; claim retained to prevent replay, contentLogged=false.");
        }
    }

    private boolean shouldForwardNotification(StatusBarNotification sbn) {
        if (sbn.isOngoing()) return false;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
        if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        String pkg = sbn.getPackageName();
        if ((getPackageName().equals(pkg) && !LOCAL_PROBE_CHANNEL.equals(n.getChannelId()))
                || "dev.applewatchandroid.companion.apple_watch_companion".equals(pkg)) return false;
        if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) return false;
        return true;
    }

    private static String limited(CharSequence value, int max) {
        if (value == null) return "";
        String text = value.toString().trim();
        int end = Math.min(max, text.length());
        if (end < text.length() && end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
    private static byte[] fingerprint(String title, String text, String appName, boolean reply, long postTime, int alertFlags) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        // Sender/action format migration changes input identity exactly once, including old journals.
        out.writeInt(2);
        // UTF-8 lengths distinguish boundaries, including non-ASCII notification text.
        for (String value : new String[]{title, text, appName}) {
            byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try { out.writeInt(encoded.length); out.write(encoded); } finally { Arrays.fill(encoded, (byte) 0); }
        }
        out.writeBoolean(reply); out.writeLong(postTime); out.writeInt(alertFlags);
        byte[] input = bytes.toByteArray();
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(input); }
        finally { Arrays.fill(input, (byte) 0); }
    }

    private String resolveAppDisplayName(String pkg) {
        if (pkg == null) return "Notification";
        if (pkg.contains("telegram")) return "Telegram";
        if (pkg.contains("whatsapp")) return "WhatsApp";
        if (pkg.contains("signal")) return "Signal";
        if (pkg.contains("messaging") || pkg.contains("mms")) return "Messages";
        if (pkg.contains("mail") || pkg.contains("gmail")) return "Mail";
        if (pkg.contains("calendar")) return "Calendar";
        return pkg;
    }
}
