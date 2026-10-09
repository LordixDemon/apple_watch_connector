package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import java.util.function.Consumer;

/**
 * Handles post-commit setup steps, coordinating PBBridge messages,
 * locale setup, time zone, Albert HTTPS activation proxy, and PairedSync.
 */
final class HalPostCommitOrchestrator implements AutoCloseable {
    private final Consumer<String> logger;
    private boolean closed;

    HalPostCommitOrchestrator(Consumer<String> logger) {
        this.logger = logger != null ? logger : (s -> {});
    }

    static AppleWatchPostCommitIdsAdapter.PreparedSend prepareActionSend(
            AppleWatchPostCommitCoordinator.Action action,
            AppleWatchPostCommitIdsAdapter adapter) {
        return prepareActionSend(action, adapter, null);
    }

    static AppleWatchPostCommitIdsAdapter.PreparedSend prepareActionSend(
            AppleWatchPostCommitCoordinator.Action action,
            AppleWatchPostCommitIdsAdapter adapter, WatchLocaleSnapshot watchLocale) {
        if (action == null || adapter == null) {
            throw new IllegalArgumentException("Action and post-commit adapter are required");
        }
        switch (action.type) {
            case SEND_PAIRING_MODE_NORMAL,
                 SEND_ACTIVATION_PERMIT,
                 SEND_ACTIVATION_RETRY,
                 SEND_NORMAL_AFTER_LANGUAGE_RELAUNCH,
                 SEND_PREPARE_INITIAL_SYNC,
                 SEND_PB_BRIDGE_NORMAL -> {
                return adapter.prepareSimple(action);
            }
            case SEND_COMPUTED_TIME_ZONE -> {
                String tz = TimeZone.getDefault().getID();
                return adapter.prepareComputedTimeZone(action, tz);
            }
            case SEND_LANGUAGE_AND_LOCALE -> {
                if (watchLocale == null) throw new IllegalStateException("Authenticated Watch language/locale is required; phone fallback disabled");
                List<String> langs = watchLocale.languages();
                String loc = watchLocale.locale();
                byte[] prefs = LocalePreferencesArchiveCodec.encodeSetupPreferences(langs, loc);
                try {
                    return adapter.prepareLanguageAndLocale(action, langs, loc, prefs);
                } finally {
                    Arrays.fill(prefs, (byte) 0);
                }
            }
            case SEND_PAIRED_SYNC_COMPLETION -> {
                double now = (System.currentTimeMillis() / 1000.0) - 978307200.0;
                return adapter.preparePairedSyncCompletion(action, now);
            }
            default -> throw new IllegalArgumentException("Action does not produce a simple prepared send: " + action.type);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("HalPostCommitOrchestrator is closed");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
