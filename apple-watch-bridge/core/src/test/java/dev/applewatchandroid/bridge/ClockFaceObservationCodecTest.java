package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public final class ClockFaceObservationCodecTest {
    private static final UUID PAIR = UUID.fromString("5e111a6b-a56a-5b3a-b795-1c9799c1d92a");
    private static final UUID EPOCH = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private static final String FACE = "3122508d-c08f-536e-bc37-d7f82cb8675d";
    private ClockFaceCollection collection() {
        ClockFaceCollection value = new ClockFaceCollection();
        value.configurations.put(FACE, "{\"bundle id\":\"com.apple.NTKLeghornFaceBundle\"}".getBytes(StandardCharsets.UTF_8));
        value.ordered.add(FACE); value.selected = FACE;
        value.selectionKnown = true; value.orderKnown = true; value.hasResetBaseline = true;
        value.observedAt = 1791258842520L;
        return value;
    }
    @Test public void projectionRetainsNativeIdentitiesTimeCompletenessAndExactConfiguration() throws Exception {
        var original = ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, collection());
        byte[] frame = ClockFaceObservationCodec.encode(original);
        var decoded = ClockFaceObservationCodec.decode(frame);
        assertEquals(original, decoded); assertTrue(decoded.complete());
        assertEquals(FACE, decoded.selected()); assertEquals(1791258842520L, decoded.observedAt());
        assertTrue(new String(frame, StandardCharsets.UTF_8).contains("bundle id"));
        assertArrayEquals(collection().configurations.get(FACE), decoded.faces().get(0).configuration().getBytes(StandardCharsets.UTF_8));
        assertThrows(UnsupportedOperationException.class, () -> decoded.ordered().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.faces().clear());
    }
    @Test public void incompletePrefixAndUnknownSelectionNeverBecomeComplete() {
        var value = collection(); value.hasResetBaseline = false;
        assertFalse(ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, value).complete());
        value.hasResetBaseline = true; value.selectionKnown = false;
        assertFalse(ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, value).complete());
        value.selectionKnown = true; value.configurations.clear();
        assertFalse(ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, value).complete());
    }
    @Test public void emptyCompleteCollectionIsDistinctFromUnknown() {
        var value = new ClockFaceCollection(); value.hasResetBaseline = true; value.orderKnown = true; value.observedAt = 1;
        assertTrue(ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, value).complete());
        value.observedAt = 0;
        assertThrows(IllegalArgumentException.class, () -> ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, value));
    }
    @Test public void everyTruncationTrailingDataAndInvalidBooleanAreRefused() throws Exception {
        byte[] frame = ClockFaceObservationCodec.encode(ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, collection()));
        for (int i = 0; i < frame.length; i++) {
            byte[] truncated = Arrays.copyOf(frame, i);
            assertThrows(IOException.class, () -> ClockFaceObservationCodec.decode(truncated));
        }
        assertThrows(IOException.class, () -> ClockFaceObservationCodec.decode(Arrays.copyOf(frame, frame.length + 1)));
        frame[4 + 38 + 38 + 8] = 2;
        assertThrows(IOException.class, () -> ClockFaceObservationCodec.decode(frame));
        assertThrows(IOException.class, () -> ClockFaceObservationCodec.decode(new byte[ClockFaceObservationCodec.MAX_FRAME + 1]));
    }
    @Test public void duplicatesOversizedConfigurationsAndNoncanonicalFaceIdsAreRefused() {
        var face = new ClockFaceObservationCodec.Face(FACE, "", 1);
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceObservationCodec.Observation(PAIR, EPOCH,
                1, true, true, true, FACE, List.of(FACE, FACE), List.of(face)));
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceObservationCodec.Observation(PAIR, EPOCH,
                1, true, true, true, FACE, List.of(FACE), List.of(face, face)));
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceObservationCodec.Face(FACE.toUpperCase(Locale.ROOT), "", 1));
        assertThrows(IllegalArgumentException.class, () -> new ClockFaceObservationCodec.Face(FACE, "", 131073));
    }
    @Test public void disconnectedAndOlderObservationsCannotChangeNativeFaceState() {
        var dispatcher = BridgeIpcDispatcher.getInstance();
        dispatcher.updateConnectionState(false, "Watch");
        var first = ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, collection());
        dispatcher.observeClockFaces(first); assertNull(dispatcher.clockFaces());
        dispatcher.updateConnectionState(true, "Watch");
        try {
            dispatcher.observeClockFaces(first); assertEquals(FACE, dispatcher.getCurrentFaceId());
            var old = collection(); old.observedAt--;
            dispatcher.observeClockFaces(ClockFaceObservationCodec.fromCollection(PAIR, EPOCH, old));
            assertEquals(first, dispatcher.clockFaces());
        } finally { dispatcher.updateConnectionState(false, "Watch"); }
        assertNull(dispatcher.clockFaces()); assertNull(dispatcher.getCurrentFaceId());
    }
}
