package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class PairedSyncPeerProgressTest {
    @Test public void separatePeerUpdatesAreRequiredBeforeCompletion() {
        var progress = new PairedSyncPeerProgress();
        accept(progress, 10, PairedSyncCodec.WATCH_SYNC_STATE_KEY,
                Map.of("version", 1L, "syncProgressState", 3L, "globalProgress", 100L));
        assertFalse(progress.complete());
        accept(progress, 11, PairedSyncCodec.WATCH_SYNC_CLIENT_STATE_KEY,
                Map.of("version", 1L, "syncProgressState", 3L));
        assertTrue(progress.complete());
    }

    @Test public void staleCompletionCannotReplaceNewerIncompleteState() {
        var progress = new PairedSyncPeerProgress();
        accept(progress, 20, PairedSyncCodec.WATCH_SYNC_STATE_KEY,
                Map.of("version", 1L, "syncProgressState", 2L, "globalProgress", 80L));
        accept(progress, 10, PairedSyncCodec.WATCH_SYNC_STATE_KEY,
                Map.of("version", 1L, "syncProgressState", 3L, "globalProgress", 100L));
        accept(progress, 21, PairedSyncCodec.WATCH_SYNC_CLIENT_STATE_KEY,
                Map.of("version", 1L, "syncProgressState", 3L));
        assertFalse(progress.complete());
    }

    @Test public void nonIntegerOrUnknownVersionStateDoesNotConfirmCompletion() {
        var progress = new PairedSyncPeerProgress();
        accept(progress, 10, PairedSyncCodec.WATCH_SYNC_STATE_KEY,
                Map.of("version", 1L, "syncProgressState", "3.5", "globalProgress", 100L));
        accept(progress, 11, PairedSyncCodec.WATCH_SYNC_CLIENT_STATE_KEY,
                Map.of("version", 2L, "syncProgressState", 3L));
        assertFalse(progress.complete());
    }

    @Test public void deletingPeerStateClearsEarlierCompletion() {
        var progress = new PairedSyncPeerProgress();
        var complete = PairedSyncCodec.initialSyncCompletion(10);
        try { progress.accept(complete); } finally { complete.destroy(); }
        assertTrue(progress.complete());
        var key = new PairedSyncCodec.UserDefaultsKey(PairedSyncCodec.WATCH_SYNC_STATE_KEY, null, true, 11.0);
        var removed = new PairedSyncCodec.UserDefaultsMessage(11, PairedSyncCodec.DOMAIN, List.of(key));
        try { progress.accept(removed); } finally { removed.destroy(); key.destroy(); }
        assertFalse(progress.complete());
    }

    private static void accept(PairedSyncPeerProgress progress, double time, String name,
            Map<String, Object> value) {
        byte[] bytes = BinaryPropertyListCodec.encodeDictionary(value);
        var key = new PairedSyncCodec.UserDefaultsKey(name, bytes, true, time);
        var message = new PairedSyncCodec.UserDefaultsMessage(time, PairedSyncCodec.DOMAIN, List.of(key));
        try { progress.accept(message); }
        finally { message.destroy(); key.destroy(); java.util.Arrays.fill(bytes, (byte) 0); }
    }
}
