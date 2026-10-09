package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class PinWaitErtmResponderTest {
    private static final int PHONE_CID = 0x40;
    private static final int WATCH_CID = 0x81;

    @Test
    public void delayedPinKeepsTheSameReceiveCursorAlive() {
        PinWaitErtmResponder wait = new PinWaitErtmResponder(WATCH_CID, 7, true);
        L2capErtmCodec.Frame first = L2capErtmCodec.decode(WATCH_CID, wait.heartbeat(0), true);
        assertTrue(first.supervisory);
        assertEquals(7, first.requestSequence);
        assertFalse(first.poll);
        assertFalse(first.finalBit);
        assertNull(wait.heartbeat(500));
        L2capErtmCodec.Frame later = L2capErtmCodec.decode(WATCH_CID, wait.heartbeat(80_000), true);
        assertEquals(7, later.requestSequence);
    }

    @Test
    public void peerPollGetsFinalReplyOnTheRemoteCid() {
        PinWaitErtmResponder wait = new PinWaitErtmResponder(WATCH_CID, 7, true);
        L2capErtmCodec.Frame poll = L2capErtmCodec.decode(PHONE_CID,
                L2capErtmCodec.encodeSupervisoryFrame(PHONE_CID,
                        L2capErtmCodec.SUPERVISORY_RR, 6, true, false, true), true);
        assertTrue(wait.consumes(poll));
        L2capErtmCodec.Frame reply = L2capErtmCodec.decode(WATCH_CID, wait.response(poll), true);
        assertTrue(reply.finalBit);
        assertFalse(reply.poll);
        assertEquals(7, reply.requestSequence);
    }

    @Test
    public void duplicateSaltFrameIsAckedButNewIkeBytesAreDeferred() {
        PinWaitErtmResponder wait = new PinWaitErtmResponder(WATCH_CID, 0, false);
        L2capErtmCodec.Frame duplicate = L2capErtmCodec.decode(PHONE_CID,
                L2capErtmCodec.encodeInformationFrame(PHONE_CID, 63, 6, new byte[]{1}, false), false);
        assertTrue(wait.consumes(duplicate));
        assertEquals(0, L2capErtmCodec.decode(WATCH_CID, wait.response(duplicate), false).requestSequence);
        L2capErtmCodec.Frame next = L2capErtmCodec.decode(PHONE_CID,
                L2capErtmCodec.encodeInformationFrame(PHONE_CID, 0, 6, new byte[]{2}, false), false);
        assertFalse(wait.consumes(next));
        assertNull(wait.response(next));
    }

    @Test
    public void rejectionIsRetainedForThePairingTransport() {
        PinWaitErtmResponder wait = new PinWaitErtmResponder(WATCH_CID, 7, false);
        L2capErtmCodec.Frame reject = L2capErtmCodec.decode(PHONE_CID,
                L2capErtmCodec.encodeSupervisoryFrame(PHONE_CID,
                        L2capErtmCodec.SUPERVISORY_SREJ, 5, false, false, false), false);
        assertFalse(wait.consumes(reject));
        assertNull(wait.response(reject));
    }
}
