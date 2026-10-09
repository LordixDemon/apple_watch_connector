package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import static dev.applewatchandroid.bridge.ClockFaceDeltaProtocolTest.*;
import static dev.applewatchandroid.bridge.ClockFaceDeltaPlanTest.*;
import static dev.applewatchandroid.bridge.ClockFaceDeltaSession.Stage.*;
import java.util.*;
import org.junit.Test;

public final class ClockFaceDeltaSessionTest {
    private static final UUID PAIR = UUID.fromString("5e111a6b-a56a-5b3a-b795-1c9799c1d92a");
    private static final UUID EPOCH = UUID.fromString("12345678-4444-4444-4444-123456789012");
    private static final String TOPIC = IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE;

    private ClockFaceDeltaSession session(ClockFaceDeltaPlan plan) {
        return new ClockFaceDeltaSession(PAIR, EPOCH, WATCH, SESSION, plan, 0);
    }

    private UUID send(ClockFaceDeltaSession session, int type, long sequence, long elapsed) {
        byte[] packet = session.prepare(header(PHONE, sequence), elapsed);
        try (var frame = ClockFaceSyncFrame.parse(packet)) {
            assertEquals(type, frame.messageId); assertEquals(SESSION, frame.sessionText()); assertFalse(frame.resetSync);
        }
        UUID id = UUID.randomUUID(); session.sent(id, elapsed + 1); return id;
    }
    private boolean respond(ClockFaceDeltaSession session, UUID id, int type, long elapsed) {
        return session.response(TOPIC, 4, id, reply(type, SESSION, WATCH, true, false, false, 0), NOW, elapsed);
    }
    private void completeWire(ClockFaceDeltaSession session) {
        assertTrue(respond(session, send(session, 0x66, 1, 1), 0x66, 3)); assertEquals(BATCH_READY, session.stage());
        assertTrue(respond(session, send(session, 0x67, 2, 4), 0x67, 6)); assertEquals(END_READY, session.stage());
        assertTrue(respond(session, send(session, 0x69, 3, 7), 0x69, 9)); assertEquals(READBACK_WAIT, session.stage());
    }

    @Test public void endResponseDoesNotApplyAndOnlyFreshActualMatchingCollectionCanApply() throws Exception {
        var base = baseline(); var plan = ClockFaceDeltaPlan.duplicate(base, FACE, NEW_FACE);
        var expected = apply(plan, base, NOW + 1);
        try (var session = session(plan)) {
            completeWire(session); assertFalse(session.terminal());
            assertFalse(session.observe(PAIR, EPOCH, base, 10)); assertEquals(READBACK_WAIT, session.stage());
            assertFalse(session.observe(UUID.randomUUID(), EPOCH, expected, 11));
            assertFalse(session.observe(PAIR, UUID.randomUUID(), expected, 12));
            expected.observedAt = NOW; assertFalse(session.observe(PAIR, EPOCH, expected, 13));
            expected.observedAt = NOW + 1; expected.hasResetBaseline = false;
            assertFalse(session.observe(PAIR, EPOCH, expected, 14));
            expected.hasResetBaseline = true; assertTrue(session.observe(PAIR, EPOCH, expected, 15));
            assertEquals(APPLIED, session.stage()); assertTrue(session.terminal());
            assertEquals(FACE, base.selected); assertEquals(1, base.configurations.size());
            assertThrows(IllegalStateException.class, plan::copyChanges);
        }
    }

    @Test public void nativeReadbackMismatchLeavesUnknownAndNeverRetriesMutation() throws Exception {
        var base = baseline();
        try (var session = session(ClockFaceDeltaPlan.duplicate(base, FACE, NEW_FACE))) {
            completeWire(session); base.observedAt = NOW + 1;
            assertTrue(session.observe(PAIR, EPOCH, base, 10)); assertEquals(UNKNOWN, session.stage());
            assertThrows(IllegalArgumentException.class, () -> session.prepare(header(PHONE, 4), 11));
        }
    }

    @Test public void transportQueueAndAppAckCannotAdvanceTheWriteState() {
        try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
            UUID id = send(session, 0x66, 1, 1); assertEquals(START_WAIT, session.stage());
            assertThrows(IllegalArgumentException.class, () -> session.prepare(header(PHONE, 2), 3));
            session.tick(4); assertEquals(START_WAIT, session.stage());
            assertFalse(session.response(TOPIC, 4, UUID.randomUUID(), new byte[0], NOW, 5));
            assertEquals(START_WAIT, session.stage());
            assertTrue(respond(session, id, 0x66, 6)); assertEquals(BATCH_READY, session.stage());
        }
    }

    @Test public void wrongIdsIdentifierTopicSessionPeerTypeAndIndexDoNotAdvance() {
        try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
            UUID id = send(session, 0x66, 1, 1);
            byte[] good = reply(0x66, SESSION, WATCH, true, false, false, 0);
            assertFalse(session.response(TOPIC, 4, UUID.randomUUID(), good, NOW, 3));
            assertFalse(session.response("clockface.other", 4, id, good, NOW, 4));
            assertFalse(session.response(TOPIC, 0xd, id, good, NOW, 5));
            assertFalse(session.response(TOPIC, 4, id, reply(0x66, "P2026-10-06T04:30:00.001", WATCH, true, false, false, 0), NOW, 6));
            assertFalse(session.response(TOPIC, 4, id, reply(0x66, SESSION, PHONE, true, false, false, 0), NOW, 7));
            assertFalse(respond(session, id, 0x69, 8)); assertEquals(START_WAIT, session.stage());
            assertTrue(respond(session, id, 0x66, 9));
            UUID batch = send(session, 0x67, 2, 10);
            assertFalse(session.response(TOPIC, 4, batch, reply(0x67, SESSION, WATCH, true, false, false, 1), NOW, 12));
            assertEquals(BATCH_WAIT, session.stage()); assertTrue(respond(session, batch, 0x67, 13));
            assertEquals(END_READY, session.stage());
        }
    }

    @Test public void startRejectionAndBatchErrorStopBeforeFurtherPackets() {
        for (boolean error : List.of(false, true)) {
            try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
                UUID id = send(session, 0x66, 1, 1);
                assertTrue(session.response(TOPIC, 4, id, reply(0x66, SESSION, WATCH, error, error, false, 0), NOW, 3));
                assertEquals(REJECTED, session.stage());
                assertThrows(IllegalArgumentException.class, () -> session.prepare(header(PHONE, 2), 4));
            }
        }
        try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
            assertTrue(respond(session, send(session, 0x66, 1, 1), 0x66, 3));
            UUID id = send(session, 0x67, 2, 4);
            assertTrue(session.response(TOPIC, 4, id, reply(0x67, SESSION, WATCH, true, true, false, 0), NOW, 6));
            assertEquals(UNKNOWN, session.stage());
        }
    }

    @Test public void endErrorRollbackAndMalformedCorrelatedResponseNeverApply() {
        for (boolean error : List.of(false, true)) {
            try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
                assertTrue(respond(session, send(session, 0x66, 1, 1), 0x66, 3));
                assertTrue(respond(session, send(session, 0x67, 2, 4), 0x67, 6));
                UUID id = send(session, 0x69, 3, 7);
                assertTrue(session.response(TOPIC, 4, id, reply(0x69, SESSION, WATCH, true, error, !error, 0), NOW, 9));
                assertEquals(UNKNOWN, session.stage());
            }
        }
        try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
            UUID id = send(session, 0x66, 1, 1);
            assertTrue(session.response(TOPIC, 4, id, new byte[0], NOW, 3)); assertEquals(UNKNOWN, session.stage());
        }
    }

    @Test public void timeoutAtDeadlineDisconnectClockRegressionAndLatePacketsLeaveUnknown() {
        for (int mode = 0; mode < 3; mode++) {
            try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
                UUID id = send(session, 0x66, 1, 100);
                if (mode == 0) session.tick(90_000);
                if (mode == 1) session.disconnected();
                if (mode == 2) session.tick(99);
                assertEquals(UNKNOWN, session.stage());
                assertFalse(respond(session, id, 0x66, 90_001));
            }
        }
        try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
            completeWire(session); session.tick(180_009); assertEquals(UNKNOWN, session.stage());
        }
    }

    @Test public void reservedHeadersMustKeepIdentityAndIncreaseSequenceAndCannotBePreparedTwice() {
        try (var session = session(ClockFaceDeltaPlan.select(baseline(), FACE))) {
            assertThrows(IllegalArgumentException.class, () -> session.prepare(header(WATCH, 1), 1));
            session.prepare(header(PHONE, 1), 2);
            assertThrows(IllegalArgumentException.class, () -> session.prepare(header(PHONE, 2), 3));
            UUID id = UUID.randomUUID(); session.sent(id, 4); assertTrue(respond(session, id, 0x66, 5));
            assertThrows(IllegalArgumentException.class, () -> session.prepare(header(PHONE, 1), 6));
            byte[] changed = ClockFaceSyncHeaderCodec.header(UUID.randomUUID(), GENERATION, 2, NOW, Map.of(PHONE, 1L));
            assertThrows(IllegalArgumentException.class, () -> session.prepare(changed, 7));
            assertEquals(BATCH_READY, session.stage()); session.prepare(header(PHONE, 2), 8);
        }
        var closed = session(ClockFaceDeltaPlan.select(baseline(), FACE)); closed.close();
        assertEquals(UNKNOWN, closed.stage());
        assertThrows(IllegalArgumentException.class, () -> closed.prepare(header(PHONE, 1), 1));
    }
}
