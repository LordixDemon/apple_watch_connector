package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class OperationalRequestTrackerTest {
    private final List<BridgeCommandCodec.Status> receipts = new ArrayList<>();
    private final OperationalRequestTracker tracker = new OperationalRequestTracker(2, receipts::add);
    private BridgeCommandCodec.Request request(UUID epoch, long deadline) {
        return new BridgeCommandCodec.Request(UUID.randomUUID(), epoch, deadline, "REQUEST_REGISTRY");
    }
    private BridgeCommandCodec.Stage last() { return receipts.get(receipts.size()-1).stage(); }

    @Test public void inventoryPayloadCorrelationOnlyCompletesMatchingLiveDiagnosticCommand() {
        UUID epoch = tracker.newEpoch();
        var inventory = new BridgeCommandCodec.Request(UUID.randomUUID(), epoch, 1000,
                SysdiagnoseArchiveInventory.COMMAND);
        tracker.accept(inventory, 0, () -> true);
        tracker.diagnosticInventoryResponse(inventory.id(), 1);
        assertEquals(BridgeCommandCodec.Stage.HAL_ACCEPTED, last());
        tracker.idsQueued(inventory, SysdiagnoseArchiveInventory.TOPIC, "inventory-ids", 7);
        tracker.diagnosticInventoryResponse(UUID.randomUUID(), 2);
        assertEquals(BridgeCommandCodec.Stage.IDS_QUEUED, last());
        tracker.diagnosticInventoryResponse(inventory.id(), 2);
        assertEquals(BridgeCommandCodec.Stage.APP_RESPONSE_RECEIVED, last());
        int count = receipts.size();
        tracker.diagnosticInventoryResponse(inventory.id(), 3);
        assertEquals(count, receipts.size());
        var ordinary = request(epoch, 1000);
        tracker.accept(ordinary, 0, () -> true);
        tracker.idsQueued(ordinary, SysdiagnoseArchiveInventory.TOPIC, "wrong-command", 7);
        tracker.diagnosticInventoryResponse(ordinary.id(), 2);
        assertEquals(BridgeCommandCodec.Stage.IDS_QUEUED, last());
    }

    @Test public void expiredOrDisconnectedInventoryCannotBeCompletedByLatePayload() {
        UUID epoch = tracker.newEpoch();
        var inventory = new BridgeCommandCodec.Request(UUID.randomUUID(), epoch, 1000,
                SysdiagnoseArchiveInventory.COMMAND);
        tracker.accept(inventory, 0, () -> true);
        tracker.idsQueued(inventory, SysdiagnoseArchiveInventory.TOPIC, "inventory-ids", 7);
        tracker.diagnosticInventoryResponse(inventory.id(), 1000);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        tracker.newEpoch();
        tracker.diagnosticInventoryResponse(inventory.id(), 1001);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
    }

    @Test public void inventoryPayloadCannotBypassExpectedTopicOrType() {
        for (boolean wrongTopic : new boolean[]{true, false}) {
            UUID epoch = tracker.newEpoch();
            var inventory = new BridgeCommandCodec.Request(UUID.randomUUID(), epoch, 1000,
                    SysdiagnoseArchiveInventory.COMMAND);
            tracker.accept(inventory, 0, () -> true);
            tracker.idsQueued(inventory, wrongTopic ? "other-topic" : SysdiagnoseArchiveInventory.TOPIC,
                    "inventory-ids", wrongTopic ? 7 : 9);
            tracker.diagnosticInventoryResponse(inventory.id(), 2);
            assertEquals(BridgeCommandCodec.Stage.IDS_QUEUED, last());
        }
    }

    @Test public void onlyExactApplicationAckCorrelatesAndNeverMeansApplied() {
        var request = request(tracker.newEpoch(), 60_000);
        assertTrue(tracker.accept(request, 0, () -> true));
        assertEquals(BridgeCommandCodec.Stage.HAL_ACCEPTED, last());
        tracker.idsQueued(request, "registry", "actual-message", 2);
        assertEquals(BridgeCommandCodec.Stage.IDS_QUEUED, last());
        tracker.appAck("other-topic", "actual-message", 1);
        tracker.appAck("registry", "other-message", 1);
        assertEquals(BridgeCommandCodec.Stage.IDS_QUEUED, last());
        tracker.appAck("registry", "actual-message", 1);
        assertEquals(BridgeCommandCodec.Stage.APP_ACK_RECEIVED, last());
        tracker.appResponse("registry", "actual-message", 2, false, 2);
        tracker.appResponse("registry", "actual-message", 3, true, 2);
        assertEquals(BridgeCommandCodec.Stage.APP_ACK_RECEIVED, last());
        tracker.appResponse("registry", "actual-message", 2, true, 2);
        assertEquals(BridgeCommandCodec.Stage.APP_RESPONSE_RECEIVED, last());
        assertFalse(Arrays.stream(BridgeCommandCodec.Stage.values()).anyMatch(s -> s.name().equals("APPLIED")));
    }

    @Test public void duplicateIdsDoNotEnqueueOrResendIncludingAfterAck() {
        var request = request(tracker.newEpoch(), 60_000);
        AtomicInteger offers = new AtomicInteger();
        assertTrue(tracker.accept(request, 0, () -> { offers.incrementAndGet(); return true; }));
        assertFalse(tracker.accept(request, 1, () -> { offers.incrementAndGet(); return true; }));
        tracker.idsQueued(request, "registry", "message", 2);
        tracker.appAck("registry", "message", 1);
        assertFalse(tracker.accept(request, 2, () -> { offers.incrementAndGet(); return true; }));
        assertEquals(1, offers.get());
        assertFalse(tracker.canSend(request, 2));
    }

    @Test public void disconnectExpiresUnsentButSentOutcomeIsUnknownAndOldEpochCannotReplay() {
        UUID epoch = tracker.newEpoch();
        var unsent = request(epoch, 60_000); var sent = request(epoch, 60_000);
        tracker.accept(unsent, 0, () -> true); tracker.accept(sent, 0, () -> true);
        tracker.idsQueued(sent, "registry", "message", 2);
        tracker.disconnect();
        assertEquals(BridgeCommandCodec.Stage.EXPIRED, receipts.get(receipts.size()-2).stage());
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        tracker.newEpoch();
        assertFalse(tracker.accept(unsent, 100, () -> { fail("old session enqueued"); return true; }));
        assertFalse(tracker.canSend(sent, 100));
    }

    @Test public void overflowRetainsReplayTombstonesUntilDeadlineAndAllowsFreshRequestsAfterExpiry() {
        UUID epoch = tracker.newEpoch();
        var first = request(epoch, 1000); var second = request(epoch, 1000);
        tracker.accept(first, 0, () -> true); tracker.accept(second, 0, () -> true);
        tracker.idsQueued(first, "registry", "one", 2); tracker.appAck("registry", "one", 1);
        assertFalse(tracker.accept(request(epoch, 1000), 1, () -> { fail("capacity overflow"); return true; }));
        assertEquals(BridgeCommandCodec.Stage.REJECTED, last());
        assertFalse(tracker.accept(first, 1000, () -> { fail("expired replay"); return true; }));
        assertTrue(tracker.accept(request(epoch, 2000), 1000, () -> true));
        assertFalse(tracker.canSend(second, 1000));
    }

    @Test public void timeoutAndAmbiguousSendFailureDoNotReportFailedDelivery() {
        UUID epoch = tracker.newEpoch();
        var request = request(epoch, 1000); tracker.accept(request, 0, () -> true);
        tracker.idsQueued(request, "registry", "one", 2);
        tracker.failed(request);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        var timeout = request(epoch, 1000); tracker.accept(timeout, 0, () -> true);
        tracker.idsQueued(timeout, "registry", "two", 2); tracker.expire(1000);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
    }

    @Test public void rejectedQueueDoesNotCreateAnAcceptedRequest() {
        UUID epoch = tracker.newEpoch(); var request = request(epoch, 1000);
        assertFalse(tracker.accept(request, 0, () -> false));
        assertEquals(BridgeCommandCodec.Stage.REJECTED, last());
        assertFalse(tracker.canSend(request, 0));
        assertFalse(tracker.accept(request(epoch, 70_000), 0, () -> { fail("excessive deadline"); return true; }));
    }

    @Test public void ackedRequestStillExpiresAndLateResponseCannotReviveIt() {
        var request = request(tracker.newEpoch(), 1000);
        tracker.accept(request, 0, () -> true); tracker.idsQueued(request, "clockface", "read", 101);
        tracker.appAck("clockface", "read", 1);
        assertTrue(tracker.expectsResponse("clockface", "read", 101, 999));
        tracker.appResponse("clockface", "read", 101, true, 1000);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        assertFalse(tracker.expectsResponse("clockface", "read", 101, 1001));
        tracker.appResponse("clockface", "read", 101, true, 2000);
        tracker.appAck("clockface", "read", 2000);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
    }

    @Test public void delayedAckCannotPromoteAnExpiredRequest() {
        var request = request(tracker.newEpoch(), 1000);
        tracker.accept(request, 0, () -> true); tracker.idsQueued(request, "registry", "read", 2);
        tracker.appAck("registry", "read", 1000);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        tracker.appResponse("registry", "read", 2, true, 1001);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
    }

    @Test public void disconnectAfterAckMakesOutcomeUnknownAndDoesNotResend() {
        var request = request(tracker.newEpoch(), 1000);
        AtomicInteger sent = new AtomicInteger();
        tracker.accept(request, 0, () -> { sent.incrementAndGet(); return true; });
        tracker.idsQueued(request, "clockface", "change", 102); tracker.appAck("clockface", "change", 1);
        tracker.disconnect();
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        tracker.appResponse("clockface", "change", 102, true, 2);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        assertFalse(tracker.accept(request, 2, () -> { sent.incrementAndGet(); return true; }));
        assertEquals(1, sent.get());
    }

    @Test public void failureAfterAckIsAmbiguousAndResponseBeforeDeadlineIsTerminal() {
        var epoch = tracker.newEpoch(); var failed = request(epoch, 1000);
        tracker.accept(failed, 0, () -> true); tracker.idsQueued(failed, "registry", "one", 2);
        tracker.appAck("registry", "one", 1); tracker.failed(failed);
        assertEquals(BridgeCommandCodec.Stage.UNKNOWN, last());
        var complete = request(epoch, 1000); tracker.accept(complete, 0, () -> true);
        tracker.idsQueued(complete, "registry", "two", 2);
        tracker.appAck("registry", "two", 1); tracker.appResponse("registry", "two", 2, true, 999);
        assertEquals(BridgeCommandCodec.Stage.APP_RESPONSE_RECEIVED, last());
        tracker.expire(1000); tracker.disconnect();
        assertEquals(BridgeCommandCodec.Stage.APP_RESPONSE_RECEIVED, last());
    }
}
