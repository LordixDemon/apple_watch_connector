package dev.applewatchandroid.bridge;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Android NotificationListenerService that mirrors phone notifications to the Apple Watch,
 * syncs section metadata/icons, dismisses Android notifications when swiped away on the watch,
 * and dispatches quick replies and supplementary notification actions.
 */
public final class AppleWatchNotificationListenerService
        extends NotificationListenerService
        implements BridgeIpcDispatcher.OnNotificationActionListener {

    static final String LOCAL_PROBE_CHANNEL = "watch-local-reply-probe";
    private static final long[] BACKOFF_MS = {2_000L, 5_000L, 15_000L, 30_000L};
    private static final int MAX_ATTEMPTS_PER_EPOCH = BACKOFF_MS.length;
    private static final int MAX_SYNCED_SECTIONS = 64;

    private final Map<String, StatusBarNotification> activeBulletins = new ConcurrentHashMap<>();
    private final Map<String, String> activeAppNames = new ConcurrentHashMap<>();
    private static volatile AppleWatchNotificationListenerService active;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "watch-notification-io");
        thread.setDaemon(true);
        return thread;
    });

    // Guarded strictly by the single-thread `io` executor.
    private NotificationMirrorQueue queue;
    private NotificationMirrorStore store;
    private String queuePairing;
    private UUID epoch;
    private record Flight(UUID revision, UUID epoch) { }
    private record RetryState(int attempts, long nextAttemptMs) { }
    private final Map<UUID, Flight> flights = new HashMap<>();
    private final Map<UUID, RetryState> retries = new HashMap<>();
    private final Map<String, String> syncedSectionHashes = new LinkedHashMap<>();
    private final Runnable retryFlush = () -> enqueueIo(this::flush);

    // Written on `io` / binder lifecycle, read lock-free from IPC state snapshots.
    private volatile NotificationRelaySettings relaySettings;
    private volatile boolean listenerReady;
    private volatile boolean androidListenerConnected;
    private volatile int cachedPendingCount;
    private volatile UUID activeEpoch;
    private volatile boolean destroyed;

    static boolean listenerConnected() {
        var listener = active;
        return listener != null && listener.androidListenerConnected;
    }

    static boolean relayReady() {
        var listener = active;
        UUID current = listener == null ? null : listener.activeEpoch;
        return listener != null && listener.androidListenerConnected && listener.listenerReady
                && current != null && current.equals(OperationalWatchService.readyEpoch());
    }

    static int pendingCount() {
        var listener = active;
        return listener == null ? 0 : listener.cachedPendingCount;
    }

    static void settingsChanged() {
        var listener = active;
        if (listener != null) listener.enqueueIo(listener::reconcilePolicyChange);
        CompanionSessionState.notificationRelayChanged();
    }

    static void retryPendingNow() {
        var listener = active;
        if (listener != null) {
            listener.main.removeCallbacks(listener.retryFlush);
            listener.enqueueIo(() -> {
                listener.retries.clear();
                listener.flush();
            });
        }
    }

    static void transportState(UUID readyEpoch) {
        var listener = active;
        if (listener != null) listener.enqueueIo(() -> listener.setEpoch(readyEpoch));
    }

    static void operationStatus(BridgeCommandCodec.Status status) {
        var listener = active;
        if (listener != null) listener.enqueueIo(() -> listener.receipt(status));
    }

    private void enqueueIo(Runnable task) {
        if (destroyed || io.isShutdown()) return;
        try {
            io.execute(() -> {
                if (destroyed || active != this) return;
                task.run();
            });
        } catch (RuntimeException ignored) { }
    }

    private boolean initializeQueue() {
        if (queue != null) return true;
        try {
            String pairing = new BridgeIdentityStore(this, ignored -> { }).operationalPairing();
            if (pairing == null) return false;
            NotificationMirrorStore.purgeOtherPairings(this, pairing);
            store = new NotificationMirrorStore(this, pairing);
            queue = store.load();
            queuePairing = pairing;
            cachedPendingCount = queue.pending().size();
            android.util.Log.i("WatchNotification", "MIRROR_JOURNAL_LOADED entries=" + queue.entries().size()
                    + " pending=" + cachedPendingCount + "; pairingBound=true contentLogged=false.");
            return true;
        } catch (Exception unavailable) {
            android.util.Log.w("WatchNotification", "MIRROR_JOURNAL_UNAVAILABLE; forwarding paused, existing file retained.");
            return false;
        }
    }

    /** Persist a complete next snapshot off the main thread using single-pass encode. */
    private boolean commit(Function<NotificationMirrorQueue, Boolean> change) {
        if (!initializeQueue()) return false;
        byte[] snapshot = null;
        byte[] nextEncoded = null;
        NotificationMirrorQueue next = null;
        try {
            snapshot = queue.encode();
            next = NotificationMirrorQueue.decode(snapshot);
            if (!Boolean.TRUE.equals(change.apply(next))) return false;
            nextEncoded = next.encode();
            store.saveEncoded(nextEncoded);
            var old = queue;
            queue = next;
            next = null;
            old.destroy();
            cachedPendingCount = queue.pending().size();
            CompanionSessionState.notificationRelayChanged();
            android.util.Log.i("WatchNotification", "MIRROR_JOURNAL_COMMITTED entries=" + queue.entries().size()
                    + " pending=" + cachedPendingCount + "; contentLogged=false.");
            return true;
        } catch (Exception failed) {
            android.util.Log.w("WatchNotification", "MIRROR_COMMIT_FAILED; previous durable state retained, contentLogged=false.");
            return false;
        } finally {
            wipe(snapshot);
            wipe(nextEncoded);
            if (next != null) next.destroy();
        }
    }

    private void setEpoch(UUID readyEpoch) {
        if (!Objects.equals(epoch, readyEpoch)) {
            flights.clear();
            retries.clear();
            syncedSectionHashes.clear();
            main.removeCallbacks(retryFlush);
            epoch = readyEpoch;
            activeEpoch = readyEpoch;
        }
        if (readyEpoch != null && queue != null) {
            try {
                String pairing = new BridgeIdentityStore(this, ignored -> { }).operationalPairing();
                if (!Objects.equals(queuePairing, pairing)) {
                    queue.destroy(); queue = null; store = null; queuePairing = null;
                    cachedPendingCount = 0;
                    listenerReady = false; flights.clear(); retries.clear(); syncedSectionHashes.clear();
                }
            } catch (Exception unavailable) {
                epoch = null;
                activeEpoch = null;
                return;
            }
        }
        if (readyEpoch != null && androidListenerConnected && !listenerReady) {
            main.post(() -> { if (!destroyed && active == this && androidListenerConnected) onListenerConnected(); });
        }
        CompanionSessionState.notificationRelayChanged();
        flush();
    }

    private void flush() {
        if (!listenerReady || epoch == null || !initializeQueue() || !epoch.equals(OperationalWatchService.readyEpoch())) return;
        long now = SystemClock.elapsedRealtime();
        var pending = queue.pending();
        Set<UUID> revisions = new HashSet<>();
        for (var e : pending) revisions.add(e.revision());
        retries.keySet().retainAll(revisions);
        NotificationRelayPolicy policy = relaySettings.policy();
        long earliestRetryDelay = Long.MAX_VALUE;
        for (var entry : pending) {
            if (flights.size() >= 8) break;
            if (isRevisionInFlight(entry.revision())) continue;
            RetryState retryState = retries.get(entry.revision());
            if (retryState != null) {
                if (retryState.attempts >= MAX_ATTEMPTS_PER_EPOCH) continue;
                long remaining = retryState.nextAttemptMs - now;
                if (remaining > 0) {
                    earliestRetryDelay = Math.min(earliestRetryDelay, remaining);
                    continue;
                }
            }
            if (!entry.removal()) {
                String pkg = entry.identity().sectionId();
                String appName = activeAppNames.getOrDefault(pkg, resolveAppDisplayName(pkg));
                ensureSectionInfoSynced(pkg, appName, policy.allows(pkg));
            }
            UUID request = UUID.randomUUID();
            String command = (entry.removal() ? "REMOVE_BULLETIN:" : "SEND_BULLETIN:")
                    + Base64.encodeToString(entry.payload(), Base64.NO_WRAP);
            if (!OperationalWatchService.sendCommand(command, request, epoch)) {
                scheduleRetry(entry.revision(), now);
                break;
            }
            flights.put(request, new Flight(entry.revision(), epoch));
            android.util.Log.i("WatchNotification", "MIRROR_REQUEST_ENQUEUED id=" + request
                    + " removal=" + entry.removal() + "; receipt/effect unconfirmed.");
        }
        if (earliestRetryDelay != Long.MAX_VALUE) {
            main.removeCallbacks(retryFlush);
            main.postDelayed(retryFlush, earliestRetryDelay);
        }
    }

    private boolean isRevisionInFlight(UUID revision) {
        for (Flight flight : flights.values()) {
            if (flight.revision.equals(revision)) return true;
        }
        return false;
    }

    private void scheduleRetry(UUID revision, long now) {
        RetryState prev = retries.get(revision);
        int nextAttempts = (prev == null ? 0 : prev.attempts) + 1;
        if (nextAttempts > MAX_ATTEMPTS_PER_EPOCH) {
            retries.put(revision, new RetryState(nextAttempts, Long.MAX_VALUE));
            return;
        }
        long delay = BACKOFF_MS[Math.min(nextAttempts - 1, BACKOFF_MS.length - 1)];
        retries.put(revision, new RetryState(nextAttempts, now + delay));
        main.removeCallbacks(retryFlush);
        main.postDelayed(retryFlush, delay);
    }

    private void receipt(BridgeCommandCodec.Status status) {
        Flight flight = flights.get(status.id());
        if (flight == null || !flight.epoch.equals(status.epoch()) || !flight.epoch.equals(epoch)) return;
        switch (status.stage()) {
            case APP_ACK_RECEIVED -> {
                flights.remove(status.id());
                retries.remove(flight.revision);
                boolean saved = commit(next -> next.acknowledge(flight.revision));
                android.util.Log.i("WatchNotification", "MIRROR_IDS_RECEIPT id=" + status.id()
                        + " persisted=" + saved + "; visible/applied inferred=false.");
                flush();
            }
            case REJECTED, EXPIRED, FAILED, UNKNOWN -> {
                flights.remove(status.id());
                scheduleRetry(flight.revision, SystemClock.elapsedRealtime());
                RetryState state = retries.get(flight.revision);
                android.util.Log.i("WatchNotification", "MIRROR_RETRY_PENDING id=" + status.id()
                        + " attempt=" + (state == null ? 0 : state.attempts) + "; nextEpoch="
                        + (state != null && state.attempts > MAX_ATTEMPTS_PER_EPOCH) + ".");
                flush();
            }
            default -> { }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        active = this;
        relaySettings = new NotificationRelaySettings(this);
        BridgeIpcDispatcher.getInstance().setNotificationActionLabels(
                getString(R.string.reply), getString(R.string.send));
        enqueueIo(this::initializeQueue);
        BridgeIpcDispatcher.getInstance().addNotificationListener(this);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        BridgeIpcDispatcher.getInstance().removeNotificationListener(this);
        if (active == this) active = null;
        listenerReady = false;
        androidListenerConnected = false;
        activeEpoch = null;
        cachedPendingCount = 0;
        main.removeCallbacksAndMessages(null);
        activeBulletins.clear();
        activeAppNames.clear();
        io.execute(() -> {
            flights.clear();
            retries.clear();
            syncedSectionHashes.clear();
            if (queue != null) {
                queue.destroy();
                queue = null;
            }
        });
        io.shutdown();
        CompanionSessionState.notificationRelayChanged();
        super.onDestroy();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        androidListenerConnected = true;
        CompanionSessionState.notificationRelayChanged();
        StatusBarNotification[] snapshot;
        RankingMap rankingMap;
        try {
            snapshot = getActiveNotifications();
            rankingMap = getCurrentRanking();
        } catch (RuntimeException denied) {
            listenerReady = false;
            return;
        }
        if (snapshot == null) {
            listenerReady = false;
            return;
        }
        enqueueIo(() -> reconcileSnapshot(snapshot, rankingMap, true));
    }

    @Override
    public void onListenerDisconnected() {
        androidListenerConnected = false;
        listenerReady = false;
        activeBulletins.clear();
        activeAppNames.clear();
        enqueueIo(() -> {
            flights.clear();
            retries.clear();
            syncedSectionHashes.clear();
        });
        CompanionSessionState.notificationRelayChanged();
        super.onListenerDisconnected();
    }

    private void reconcilePolicyChange() {
        if (!androidListenerConnected) return;
        main.post(() -> {
            if (destroyed || active != this || !androidListenerConnected) return;
            StatusBarNotification[] snapshot;
            RankingMap rankingMap;
            try {
                snapshot = getActiveNotifications();
                rankingMap = getCurrentRanking();
            } catch (RuntimeException unavailable) {
                return;
            }
            if (snapshot != null) enqueueIo(() -> reconcileSnapshot(snapshot, rankingMap, false));
        });
    }

    private void reconcileSnapshot(StatusBarNotification[] snapshot, RankingMap rankingMap, boolean resetEpochIfUnset) {
        if (!initializeQueue()) return;
        NotificationRelayPolicy policy = relaySettings.policy();
        Set<String> currentAllowedKeys = new HashSet<>();
        List<PreparedPost> preparedPosts = new ArrayList<>();
        activeBulletins.clear();

        for (StatusBarNotification sbn : snapshot) {
            if (sbn == null) continue;
            String pkg = sbn.getPackageName();
            if (eligibleSource(sbn)) {
                String appName = resolveAppDisplayName(pkg);
                activeAppNames.put(pkg, appName);
                relaySettings.remember(pkg, appName);
            }
            NotificationListenerService.Ranking ranking = rankingFor(sbn, rankingMap);
            if (!shouldForwardNotification(sbn, policy, ranking, true)) continue;
            PreparedPost prepared = preparePost(sbn, ranking, policy, true);
            if (prepared == null) continue;
            currentAllowedKeys.add(sbn.getKey());
            preparedPosts.add(prepared);
        }

        try {
            commit(next -> {
                boolean changed = false;
                for (var entry : next.entries()) {
                    if (!entry.removal() && !currentAllowedKeys.contains(entry.identity().androidKey())) {
                        changed |= next.remove(entry.identity().androidKey());
                    }
                }
                for (PreparedPost post : preparedPosts) {
                    boolean known = next.hasActive(post.bulletinKey);
                    int updateType = known ? BulletinDistributorCodec.UPDATE_TYPE_MODIFY
                            : BulletinDistributorCodec.UPDATE_TYPE_ADD;
                    try {
                        boolean postChanged = next.upsert(post.bulletinKey, post.pkg, post.fingerprint, identity ->
                                BridgeIpcDispatcher.getInstance().createBulletinPayload(
                                        post.content.title().isEmpty() ? post.appName : post.content.title(),
                                        post.content.subtitle(),
                                        post.content.body(),
                                        post.pkg, post.appName,
                                        post.sound, post.turnsOnDisplay, updateType,
                                        identity.publisherId(), identity.recordId(), identity.replyToken(),
                                        post.reply ? NotificationMirrorQueue.replyIdentifier(identity) : null,
                                        buildSupplementaryActions(identity, post.actions)));
                        changed |= postChanged;
                        activeBulletins.put(post.bulletinKey, post.sbn);
                    } catch (RuntimeException rejected) {
                        activeBulletins.remove(post.bulletinKey);
                        android.util.Log.w("WatchNotification", "MIRROR_POST_REJECTED; contentLogged=false.");
                    }
                }
                return changed;
            });
        } finally {
            for (PreparedPost post : preparedPosts) wipe(post.fingerprint);
        }

        if (resetEpochIfUnset && epoch == null) {
            UUID currentReady = OperationalWatchService.readyEpoch();
            if (currentReady != null) {
                epoch = currentReady;
                activeEpoch = currentReady;
            }
        }
        listenerReady = true;
        CompanionSessionState.notificationRelayChanged();
        android.util.Log.i("WatchNotification", "MIRROR_RECONCILED active=" + activeBulletins.size()
                + " pending=" + cachedPendingCount + "; originalAndroidActions=true.");
        flush();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        onNotificationPosted(sbn, getCurrentRanking());
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
        if (sbn == null) return;
        enqueueIo(() -> handlePostedOnIo(sbn, rankingMap));
    }

    private void handlePostedOnIo(StatusBarNotification sbn, RankingMap rankingMap) {
        if (eligibleSource(sbn)) {
            String appName = resolveAppDisplayName(sbn.getPackageName());
            activeAppNames.put(sbn.getPackageName(), appName);
            relaySettings.remember(sbn.getPackageName(), appName);
        }
        NotificationRelayPolicy policy = relaySettings.policy();
        NotificationListenerService.Ranking ranking = rankingFor(sbn, rankingMap);
        if (!shouldForwardNotification(sbn, policy, ranking, false)) {
            if (queue != null && queue.hasActive(sbn.getKey()) && !policy.allows(sbn.getPackageName())) {
                handleRemovedOnIo(sbn.getKey());
            }
            return;
        }
        PreparedPost post = preparePost(sbn, ranking, policy, false);
        if (post == null) {
            if (queue != null && queue.hasActive(sbn.getKey())) handleRemovedOnIo(sbn.getKey());
            return;
        }
        if (!initializeQueue()) {
            wipe(post.fingerprint);
            return;
        }
        try {
            boolean known = queue.hasActive(post.bulletinKey);
            boolean unchanged = queue.entries().stream().anyMatch(entry -> !entry.removal()
                    && entry.identity().androidKey().equals(post.bulletinKey)
                    && Arrays.equals(entry.fingerprint(), post.fingerprint));
            int updateType = known ? BulletinDistributorCodec.UPDATE_TYPE_MODIFY
                    : BulletinDistributorCodec.UPDATE_TYPE_ADD;
            boolean saved = commit(next -> next.upsert(post.bulletinKey, post.pkg, post.fingerprint, identity ->
                    BridgeIpcDispatcher.getInstance().createBulletinPayload(
                            post.content.title().isEmpty() ? post.appName : post.content.title(),
                            post.content.subtitle(),
                            post.content.body(),
                            post.pkg, post.appName,
                            post.sound, post.turnsOnDisplay, updateType,
                            identity.publisherId(), identity.recordId(), identity.replyToken(),
                            post.reply ? NotificationMirrorQueue.replyIdentifier(identity) : null,
                            buildSupplementaryActions(identity, post.actions))));
            if (saved || unchanged) activeBulletins.put(post.bulletinKey, sbn);
            else activeBulletins.remove(post.bulletinKey);
            flush();
        } catch (Exception invalid) {
            android.util.Log.w("WatchNotification", "MIRROR_POST_REJECTED; contentLogged=false.");
        } finally {
            wipe(post.fingerprint);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) {
            String key = sbn.getKey();
            activeBulletins.remove(key);
            enqueueIo(() -> handleRemovedOnIo(key));
        }
    }

    private void handleRemovedOnIo(String androidKey) {
        activeBulletins.remove(androidKey);
        commit(next -> next.remove(androidKey));
        flush();
    }

    @Override
    public void onDismiss(String publisherBulletinId, String recordId, String sectionId) {
        enqueueIo(() -> {
            NotificationIdentityIndex.Identity identity = queue == null
                    ? null : queue.match(publisherBulletinId, recordId, sectionId);
            if (identity == null || !activeBulletins.containsKey(identity.androidKey())) return;
            if (!commit(next -> next.claimDismiss(publisherBulletinId, recordId, sectionId))) return;
            main.post(() -> {
                if (destroyed || active != this) return;
                try {
                    cancelNotification(identity.androidKey());
                    android.util.Log.i("WatchNotification", "DISMISS_ANDROID_REQUESTED; removal callback still required.");
                } catch (RuntimeException rejected) {
                    android.util.Log.w("WatchNotification", "DISMISS_OUTCOME_UNKNOWN; claim retained to prevent replay.");
                }
            });
        });
    }

    @Override
    public void onReply(String publisherBulletinId, String recordId, String sectionId, String identifier, String replyText) {
        if (replyText == null || replyText.trim().isEmpty() || replyText.length() > 4096) return;
        enqueueIo(() -> {
            NotificationIdentityIndex.Identity identity = queue == null
                    ? null : queue.match(publisherBulletinId, recordId, sectionId);
            if (identity == null || !NotificationMirrorQueue.replyIdentifier(identity).equals(identifier)) return;
            StatusBarNotification sbn = activeBulletins.get(identity.androidKey());
            if (sbn == null) return;
            ReplyTarget target = uniqueReplyTarget(sbn.getNotification());
            if (target == null || !commit(next -> next.claimReply(publisherBulletinId, recordId, sectionId, identifier))) return;
            sendQuickReply(target.action.actionIntent, target.input, replyText);
        });
    }

    @Override
    public void onSupplementaryAction(String publisherBulletinId, String recordId, String sectionId, String identifier) {
        enqueueIo(() -> {
            NotificationIdentityIndex.Identity identity = queue == null
                    ? null : queue.match(publisherBulletinId, recordId, sectionId);
            if (identity == null) return;
            int actionIndex = NotificationMirrorQueue.parseActionIndex(identity, identifier);
            if (actionIndex < 0) return;
            StatusBarNotification sbn = activeBulletins.get(identity.androidKey());
            if (sbn == null) return;
            ReplyTarget replyTarget = uniqueReplyTarget(sbn.getNotification());
            List<Notification.Action> actions = extractSupplementaryActions(sbn.getNotification(), replyTarget);
            if (actionIndex >= actions.size()) return;
            Notification.Action targetAction = actions.get(actionIndex);
            if (targetAction.actionIntent == null) return;
            if (!commit(next -> next.claimAction(publisherBulletinId, recordId, sectionId, identifier))) return;
            try {
                targetAction.actionIntent.send();
                android.util.Log.i("WatchNotification", "ACTION_ANDROID_INTENT_SENT; outcome logged=false.");
            } catch (Exception rejected) {
                android.util.Log.w("WatchNotification", "ACTION_OUTCOME_UNKNOWN; claim retained to prevent replay.");
            }
        });
    }

    @Override
    public void onLightsObserved(String publisherBulletinId, String sectionId, String replyToken, Boolean played) {
        enqueueIo(() -> {
            NotificationIdentityIndex.Identity identity = queue == null
                    ? null : queue.matchLights(publisherBulletinId, sectionId, replyToken);
            if (identity != null && played != null) {
                android.util.Log.i("WatchNotification", "LIGHTS_OBSERVED played=" + played
                        + " exactNotification=true; identifiers/content logged=false.");
            }
        });
    }

    private void ensureSectionInfoSynced(String pkg, String appName, boolean allowsNotifications) {
        if (epoch == null || !OperationalWatchService.isConnected()) return;
        String displayName = (appName == null || appName.isBlank()) ? pkg : appName;
        String stateHash = NotificationTextContent.limitUtf8(displayName, 256) + "\0" + allowsNotifications;
        if (stateHash.equals(syncedSectionHashes.get(pkg))) return;
        if (!syncedSectionHashes.containsKey(pkg) && syncedSectionHashes.size() >= MAX_SYNCED_SECTIONS) {
            var it = syncedSectionHashes.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        byte[] iconPng = relaySettings.loadSectionIconPng(pkg);
        BulletinDistributorCodec.SectionIcon icon = iconPng == null ? null
                : new BulletinDistributorCodec.SectionIcon(List.of(
                        new BulletinDistributorCodec.SectionIconVariant(3, iconPng, true)));
        byte[] encoded = null;
        try {
            encoded = new BulletinDistributorCodec.SetSectionInfoRequest(
                    new BulletinDistributorCodec.SectionInfo(pkg, NotificationTextContent.limitUtf8(displayName, 256),
                            allowsNotifications, icon)).encode();
            String command = "SEND_SECTION_INFO:" + Base64.encodeToString(encoded, Base64.NO_WRAP);
            if (OperationalWatchService.sendCommand(command, UUID.randomUUID(), epoch)) {
                syncedSectionHashes.put(pkg, stateHash);
            }
        } catch (IllegalArgumentException ignored) {
        } finally {
            wipe(encoded);
        }
    }

    private record ReplyTarget(Notification.Action action, RemoteInput input) { }

    private ReplyTarget uniqueReplyTarget(Notification notification) {
        List<Notification.Action> candidates = collectAllActions(notification);
        ReplyTarget fallback = null;
        int fallbackCount = 0;
        ReplyTarget semantic = null;
        int semanticCount = 0;
        for (Notification.Action action : candidates) {
            if (action.actionIntent == null || action.getRemoteInputs() == null) continue;
            for (RemoteInput input : action.getRemoteInputs()) {
                if (!input.getAllowFreeFormInput()) continue;
                fallback = new ReplyTarget(action, input);
                fallbackCount++;
                if (action.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) {
                    semantic = fallback;
                    semanticCount++;
                }
            }
        }
        if (semanticCount == 1) return semantic;
        return fallbackCount == 1 ? fallback : null;
    }

    private List<Notification.Action> extractSupplementaryActions(Notification notification, ReplyTarget replyTarget) {
        List<Notification.Action> all = collectAllActions(notification);
        List<Notification.Action> prioritized = new ArrayList<>();
        List<Notification.Action> normal = new ArrayList<>();
        for (Notification.Action action : all) {
            if (action == null || action.actionIntent == null || action.isAuthenticationRequired()) continue;
            if (replyTarget != null && action.actionIntent.equals(replyTarget.action.actionIntent)) continue;
            if (hasFreeFormRemoteInput(action)) continue;
            String title = action.title == null ? "" : NotificationTextContent.limitUtf8(action.title.toString(), 64);
            if (title.isEmpty()) continue;
            int semantic = action.getSemanticAction();
            if (semantic == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ
                    || semantic == Notification.Action.SEMANTIC_ACTION_ARCHIVE
                    || semantic == Notification.Action.SEMANTIC_ACTION_DELETE
                    || semantic == Notification.Action.SEMANTIC_ACTION_MUTE
                    || semantic == Notification.Action.SEMANTIC_ACTION_THUMBS_UP) {
                prioritized.add(action);
            } else {
                normal.add(action);
            }
        }
        prioritized.addAll(normal);
        if (prioritized.size() > NotificationMirrorQueue.MAX_SUPPLEMENTARY_ACTIONS) {
            return List.copyOf(prioritized.subList(0, NotificationMirrorQueue.MAX_SUPPLEMENTARY_ACTIONS));
        }
        return List.copyOf(prioritized);
    }

    private static List<Notification.Action> collectAllActions(Notification notification) {
        List<Notification.Action> combined = new ArrayList<>();
        Set<PendingIntent> seenIntents = new HashSet<>();
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (action != null && action.actionIntent != null && seenIntents.add(action.actionIntent)) {
                    combined.add(action);
                }
            }
        }
        try {
            for (Notification.Action action : new Notification.WearableExtender(notification).getActions()) {
                if (action != null && action.actionIntent != null && seenIntents.add(action.actionIntent)) {
                    combined.add(action);
                }
            }
        } catch (RuntimeException ignored) { }
        return combined;
    }

    private static boolean hasFreeFormRemoteInput(Notification.Action action) {
        RemoteInput[] inputs = action.getRemoteInputs();
        if (inputs == null) return false;
        for (RemoteInput input : inputs) {
            if (input != null && input.getAllowFreeFormInput()) return true;
        }
        return false;
    }

    private static List<BulletinDistributorCodec.SupplementaryAction> buildSupplementaryActions(
            NotificationIdentityIndex.Identity identity, List<Notification.Action> actions) {
        if (actions == null || actions.isEmpty()) return List.of();
        List<BulletinDistributorCodec.SupplementaryAction> encoded = new ArrayList<>(actions.size());
        for (int index = 0; index < actions.size() && index < NotificationMirrorQueue.MAX_SUPPLEMENTARY_ACTIONS; index++) {
            Notification.Action action = actions.get(index);
            String title = NotificationTextContent.limitUtf8(action.title.toString(), 64);
            boolean destructive = action.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_DELETE;
            encoded.add(new BulletinDistributorCodec.SupplementaryAction(
                    NotificationMirrorQueue.actionIdentifier(identity, index), title, destructive));
        }
        return List.copyOf(encoded);
    }

    private void sendQuickReply(PendingIntent pendingIntent, RemoteInput input, String replyText) {
        try {
            Intent replyIntent = new Intent();
            Bundle bundle = new Bundle();
            bundle.putCharSequence(input.getResultKey(), replyText);
            RemoteInput.addResultsToIntent(new RemoteInput[]{input}, replyIntent, bundle);
            RemoteInput.setResultsSource(replyIntent, RemoteInput.SOURCE_FREE_FORM_INPUT);
            pendingIntent.send(this, 0, replyIntent);
            android.util.Log.i("WatchNotification", "REPLY_ANDROID_INTENT_SENT; recipient acceptance inferred=false, contentLogged=false.");
        } catch (Exception rejected) {
            android.util.Log.w("WatchNotification", "REPLY_OUTCOME_UNKNOWN; claim retained to prevent replay, contentLogged=false.");
        }
    }

    private record PreparedPost(
            StatusBarNotification sbn,
            String bulletinKey,
            String pkg,
            String appName,
            NotificationTextContent content,
            boolean reply,
            List<Notification.Action> actions,
            boolean sound,
            boolean turnsOnDisplay,
            byte[] fingerprint) { }

    private PreparedPost preparePost(
            StatusBarNotification sbn,
            NotificationListenerService.Ranking ranking,
            NotificationRelayPolicy policy,
            boolean reconcilingSnapshot) {
        Notification n = sbn.getNotification();
        NotificationTextContent content;
        try { content = content(n); }
        catch (RuntimeException malformed) { return null; }
        if (content.empty()) return null;

        String pkg = sbn.getPackageName();
        String appName = activeAppNames.computeIfAbsent(pkg, this::resolveAppDisplayName);
        String bulletinKey = sbn.getKey();
        ReplyTarget replyTarget = uniqueReplyTarget(n);
        boolean reply = replyTarget != null;
        List<Notification.Action> actions = extractSupplementaryActions(n, replyTarget);
        boolean known = queue != null && queue.hasActive(bulletinKey);
        boolean silentRanking = isSilentRanking(ranking);
        boolean sound = !reconcilingSnapshot && !silentRanking
                && ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0 || !known);
        boolean turnsOnDisplay = sound && !isScreenOnSuppressible(ranking, policy);
        byte[] fingerprint;
        try {
            fingerprint = fingerprint(content.title(), content.subtitle(), content.body(),
                    appName, reply, actions, n.flags & Notification.FLAG_ONLY_ALERT_ONCE);
        } catch (Exception failed) {
            return null;
        }
        return new PreparedPost(sbn, bulletinKey, pkg, appName, content, reply, actions, sound, turnsOnDisplay, fingerprint);
    }

    private boolean shouldForwardNotification(
            StatusBarNotification sbn,
            NotificationRelayPolicy policy,
            NotificationListenerService.Ranking ranking,
            boolean reconcilingSnapshot) {
        if (!eligibleSource(sbn)) return false;
        if (ranking != null) {
            if (ranking.isSuspended() || ranking.isAmbient()) return false;
            if (ranking.getImportance() <= NotificationManager.IMPORTANCE_MIN) return false;
            if ((ranking.getSuppressedVisualEffects()
                    & NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) != 0) return false;
        }
        boolean unlocked = !reconcilingSnapshot && isPhoneUnlockedAndInteractive();
        return policy.allows(sbn.getPackageName(), unlocked);
    }

    private static NotificationListenerService.Ranking rankingFor(StatusBarNotification sbn, RankingMap map) {
        if (sbn == null || map == null) return null;
        NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
        return map.getRanking(sbn.getKey(), ranking) ? ranking : null;
    }

    private static boolean isSilentRanking(NotificationListenerService.Ranking ranking) {
        if (ranking == null) return false;
        return ranking.getImportance() <= NotificationManager.IMPORTANCE_LOW
                || !ranking.matchesInterruptionFilter()
                || (ranking.getSuppressedVisualEffects() & NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON) != 0;
    }

    private static boolean isScreenOnSuppressible(NotificationListenerService.Ranking ranking, NotificationRelayPolicy policy) {
        if (policy.onlyWhenScreenOff()) return false;
        return ranking != null && (ranking.getSuppressedVisualEffects()
                & NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON) != 0;
    }

    private boolean isPhoneUnlockedAndInteractive() {
        PowerManager pm = getSystemService(PowerManager.class);
        KeyguardManager km = getSystemService(KeyguardManager.class);
        return pm != null && pm.isInteractive() && (km == null || !km.isKeyguardLocked());
    }

    private boolean eligibleSource(StatusBarNotification sbn) {
        if (sbn.isOngoing()) return false;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
        if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if ((n.flags & Notification.FLAG_LOCAL_ONLY) != 0) return false;
        String pkg = sbn.getPackageName();
        boolean isLocalProbe = getPackageName().equals(pkg) && LOCAL_PROBE_CHANNEL.equals(n.getChannelId());
        if (!isLocalProbe) {
            if (getPackageName().equals(pkg)
                    || "dev.applewatchandroid.companion.apple_watch_companion".equals(pkg)) return false;
            if ("android".equals(pkg) || "com.android.systemui".equals(pkg)) return false;
            if (Notification.CATEGORY_PROGRESS.equals(n.category)
                    || Notification.CATEGORY_TRANSPORT.equals(n.category)
                    || Notification.CATEGORY_SERVICE.equals(n.category)
                    || Notification.CATEGORY_SYSTEM.equals(n.category)) return false;
            if (n.extras != null && n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return false;
        }
        return true;
    }

    private static String string(CharSequence value) { return value == null ? null : value.toString(); }

    private NotificationTextContent content(Notification notification) {
        Bundle extras = notification.extras;
        if (extras == null) return new NotificationTextContent("", "");
        String sender = null, message = null;
        var messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null) {
            var decoded = Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
            if (!decoded.isEmpty()) {
                var latest = decoded.get(decoded.size() - 1);
                String text = string(latest.getText());
                if (text != null && !text.isBlank()) {
                    message = text;
                    sender = latest.getSenderPerson() == null ? null : string(latest.getSenderPerson().getName());
                } else if (latest.getDataMimeType() != null && !latest.getDataMimeType().isBlank()) {
                    message = mediaPlaceholder(latest.getDataMimeType());
                    sender = latest.getSenderPerson() == null ? null : string(latest.getSenderPerson().getName());
                }
            }
        }
        String lines = null;
        var textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (textLines != null) {
            StringBuilder joined = new StringBuilder();
            for (CharSequence line : textLines) {
                if (line == null) continue;
                if (joined.length() > 0) joined.append('\n');
                joined.append(NotificationTextContent.limitUtf8(line.toString(), 16384));
                if (joined.length() >= 16384) break;
            }
            lines = joined.toString();
        }
        return NotificationTextContent.select(
                string(extras.getCharSequence(Notification.EXTRA_TITLE)),
                string(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)),
                string(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)),
                string(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                string(extras.getCharSequence(Notification.EXTRA_TEXT)),
                lines, sender, message);
    }

    private String mediaPlaceholder(String mimeType) {
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("image/")) return getString(R.string.notification_media_photo);
        if (lower.startsWith("video/")) return getString(R.string.notification_media_video);
        if (lower.startsWith("audio/")) return getString(R.string.notification_media_audio);
        return getString(R.string.notification_media_attachment);
    }

    private static byte[] fingerprint(
            String title, String subtitle, String text, String appName,
            boolean reply, List<Notification.Action> actions, int alertFlags) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeInt(3);
        for (String value : new String[]{title, subtitle == null ? "" : subtitle, text, appName}) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            try { out.writeInt(encoded.length); out.write(encoded); } finally { wipe(encoded); }
        }
        out.writeBoolean(reply);
        out.writeInt(actions.size());
        for (Notification.Action action : actions) {
            byte[] encoded = (action.title == null ? "" : action.title.toString()).getBytes(StandardCharsets.UTF_8);
            try { out.writeInt(encoded.length); out.write(encoded); } finally { wipe(encoded); }
        }
        out.writeInt(alertFlags);
        byte[] input = bytes.toByteArray();
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        finally { wipe(input); }
    }

    private String resolveAppDisplayName(String pkg) {
        try {
            return NotificationTextContent.limitUtf8(getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString(), 256);
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return pkg;
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
