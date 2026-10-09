package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class L2capErtmSessionTest {
    private static final int A_CID = 0x0040;
    private static final int B_CID = 0x0041;

    @Test public void waitForFinalBlocksNewFramesAndTimerCopiesButKeepsAcknowledgements() {
        try (L2capErtmSession session = new L2capErtmSession(A_CID, B_CID, false)) {
            session.sendInformation(bytes(42));
            session.buildReceiverReadyPoll();
            assertTrue(session.transmitBlocked());
            assertThrows(IllegalStateException.class, () -> session.sendInformation(bytes(43)));
            assertTrue(session.copyUnacknowledged(32).isEmpty());
            assertEquals(null, session.copyOldestUnacknowledged());
            // An ordinary ACK is cumulative but does not answer the pending poll.
            acceptAndClose(session, supervisoryToA(L2capErtmCodec.SUPERVISORY_RR, 1, false, false));
            assertEquals(0, session.outstandingCount());
            assertTrue(session.transmitBlocked());
            acceptAndClose(session, supervisoryToA(L2capErtmCodec.SUPERVISORY_RR, 1, false, true));
            assertFalse(session.transmitBlocked());
            assertEquals(1, L2capErtmCodec.decode(B_CID, session.sendInformation(bytes(43)), false).txSequence);
        }
    }

    @Test public void informationFinalDeliversOnceAndCompletesRecoveryPoll() {
        try (L2capErtmSession session = new L2capErtmSession(A_CID, B_CID, false)) {
            for (int i = 0; i < 6; i++) session.sendInformation(bytes(i));
            session.buildReceiverReadyPoll();
            byte[] peer = L2capErtmCodec.encodeInformationFrame(A_CID, 0, 2, bytes(90), false);
            peer[0] |= (byte) 0x80;
            try (L2capErtmSession.InboundResult received = session.accept(peer)) {
                assertSingleBytes(bytes(90), received.deliveredSdus());
                assertEquals(4, received.immediateOutboundFrames().size());
                assertFalse(session.transmitBlocked());
                for (byte[] frame : received.immediateOutboundFrames()) {
                    assertEquals(1, L2capErtmCodec.decode(B_CID, frame, false).requestSequence);
                }
            }
            try (L2capErtmSession.InboundResult duplicate = session.accept(peer)) {
                assertTrue(duplicate.deliveredSdus().isEmpty());
                assertEquals(1, duplicate.immediateOutboundFrames().size());
                assertTrue(L2capErtmCodec.decode(B_CID, duplicate.immediateOutboundFrames().get(0), false).supervisory);
            }
        }
    }

    @Test public void solicitedFinalReplaysWholeRemainingWindowAcrossSequenceWrap() {
        try (L2capErtmSession session = new L2capErtmSession(A_CID, B_CID, false)) {
            for (int i = 0; i < 60; i++) {
                session.sendInformation(bytes(i));
                acceptAndClose(session, supervisoryToA(L2capErtmCodec.SUPERVISORY_RR,
                        (i + 1) % 64, false, false));
            }
            for (int i = 0; i < 30; i++) session.sendInformation(bytes(i));
            session.buildReceiverReadyPoll();
            try (L2capErtmSession.InboundResult result = session.accept(
                    supervisoryToA(L2capErtmCodec.SUPERVISORY_RR, 62, false, true))) {
                assertEquals(28, session.outstandingCount());
                assertEquals(28, result.immediateOutboundFrames().size());
                for (int i = 0; i < 28; i++) {
                    L2capErtmCodec.Frame frame = L2capErtmCodec.decode(B_CID,
                            result.immediateOutboundFrames().get(i), false);
                    assertEquals((62 + i) % 64, frame.txSequence);
                    assertArrayEquals(bytes(i + 2), frame.information);
                }
            }
            // Final cannot cause another whole-window replay once its poll was answered.
            try (L2capErtmSession.InboundResult duplicate = session.accept(
                    supervisoryToA(L2capErtmCodec.SUPERVISORY_RR, 62, false, true))) {
                assertTrue(duplicate.immediateOutboundFrames().isEmpty());
            }
        }
    }

    @Test public void selectiveRejectPollAcknowledgesOnlyPrefixAndReturnsRequestedFrameAndFinal() {
        try (L2capErtmSession session = new L2capErtmSession(A_CID, B_CID, false)) {
            for (int i = 0; i < 8; i++) session.sendInformation(bytes(i));
            try (L2capErtmSession.InboundResult selective = session.accept(
                    supervisoryToA(L2capErtmCodec.SUPERVISORY_SREJ, 5, true, false))) {
                assertEquals(3, session.outstandingCount());
                assertEquals(5, session.oldestOutstandingSequence());
                List<byte[]> frames = selective.immediateOutboundFrames();
                assertEquals(2, frames.size());
                assertEquals(5, L2capErtmCodec.decode(B_CID, frames.get(0), false).txSequence);
                L2capErtmCodec.Frame finalReply = L2capErtmCodec.decode(B_CID, frames.get(1), false);
                assertTrue(finalReply.supervisory);
                assertTrue(finalReply.finalBit);
                assertFalse(finalReply.poll);
            }
        }
    }

    @Test public void receiverBusySuppressesTimerRetriesUntilReceiverReady() {
        try (L2capErtmSession session = new L2capErtmSession(A_CID, B_CID, false)) {
            session.sendInformation(bytes(42));
            acceptAndClose(session, supervisoryToA(L2capErtmCodec.SUPERVISORY_RNR, 0, false, false));
            assertEquals(null, session.copyOldestUnacknowledged());
            assertTrue(session.copyUnacknowledged(32).isEmpty());
            session.buildReceiverReadyPoll();
            try (L2capErtmSession.InboundResult ready = session.accept(
                    supervisoryToA(L2capErtmCodec.SUPERVISORY_RR, 0, false, true))) {
                assertEquals(1, ready.immediateOutboundFrames().size());
                assertArrayEquals(bytes(42), L2capErtmCodec.decode(B_CID,
                        ready.immediateOutboundFrames().get(0), false).information);
            }
        }
    }

    @Test public void finalAfterActionedRejectDoesNotRepeatAlreadyRetransmittedFrames() {
        try (L2capErtmSession session = new L2capErtmSession(A_CID, B_CID, false)) {
            for (int i = 0; i < 8; i++) session.sendInformation(bytes(i));
            session.buildReceiverReadyPoll();
            try (L2capErtmSession.InboundResult reject = session.accept(
                    supervisoryToA(L2capErtmCodec.SUPERVISORY_REJ, 2, false, false))) {
                assertEquals(6, reject.immediateOutboundFrames().size());
            }
            try (L2capErtmSession.InboundResult finalReply = session.accept(
                    supervisoryToA(L2capErtmCodec.SUPERVISORY_RR, 2, false, true))) {
                assertTrue(finalReply.immediateOutboundFrames().isEmpty());
                assertEquals(6, session.outstandingCount());
            }
        }
    }

    @Test
    public void exchangesInformationAndPiggybacksAcknowledgements() {
        try (L2capErtmSession a =
                        new L2capErtmSession(
                                A_CID,
                                B_CID,
                                false);
                L2capErtmSession b =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            byte[] request =
                    a.sendInformation(
                            bytes(0x04, 0x10));
            assertEquals(1, a.outstandingCount());

            try (L2capErtmSession.InboundResult accepted =
                    b.accept(
                            request)) {
                assertTrue(
                        accepted.acknowledgementRecommended);
                assertSingleBytes(
                        bytes(0x04, 0x10),
                        accepted.deliveredSdus());
            }

            byte[] response =
                    b.sendInformation(
                            bytes(0x04, 0x20));
            L2capErtmCodec.Frame encodedResponse =
                    L2capErtmCodec.decode(
                            A_CID,
                            response,
                            false);
            assertEquals(0, encodedResponse.txSequence);
            assertEquals(1, encodedResponse.requestSequence);

            try (L2capErtmSession.InboundResult accepted =
                    a.accept(
                            response)) {
                assertSingleBytes(
                        bytes(0x04, 0x20),
                        accepted.deliveredSdus());
            }
            assertEquals(0, a.outstandingCount());
            assertEquals(1, a.expectedRemoteTxSequence());
        }
    }

    @Test
    public void enforcesWindowAndWrapsSixBitSequence() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            for (int index = 0;
                    index < L2capErtmSession.TARGET_TX_WINDOW;
                    index++) {
                session.sendInformation(
                        bytes(index));
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> session.sendInformation(
                            bytes(0xff)));

            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            32,
                            false,
                            false));
            assertEquals(0, session.outstandingCount());

            for (int index = 32;
                    index < 64;
                    index++) {
                session.sendInformation(
                        bytes(index));
            }
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            0,
                            false,
                            false));
            assertEquals(0, session.outstandingCount());
            assertEquals(0, session.nextTxSequence());

            byte[] wrapped =
                    session.sendInformation(
                            bytes(0xaa));
            assertEquals(
                    0,
                    L2capErtmCodec.decode(
                            B_CID,
                            wrapped,
                            false).txSequence);
            assertEquals(1, session.nextTxSequence());
        }
    }

    @Test
    public void ignoresIframeReqSeqThatJumpsFarAheadOfReceiverReady() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            session.sendInformation(bytes(0x01));
            session.sendInformation(bytes(0x02));
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            2,
                            false,
                            false));
            assertEquals(0, session.outstandingCount());
            session.sendInformation(bytes(0x03));
            session.sendInformation(bytes(0x04));
            assertEquals(2, session.outstandingCount());
            byte[] jumped = L2capErtmCodec.encodeInformationFrame(
                    A_CID,
                    0,
                    42,
                    bytes(0x55),
                    false);
            acceptAndClose(session, jumped);
            assertFalse(session.poisoned());
            assertEquals(2, session.outstandingCount());
        }
    }

    @Test
    public void ignoresReqSeqThatOvershootsTheOutstandingQueue() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            for (int index = 0;
                    index < L2capErtmSession.TARGET_TX_WINDOW;
                    index++) {
                session.sendInformation(
                        bytes(index));
            }
            assertEquals(32, session.nextTxSequence());
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            35,
                            false,
                            false));
            assertFalse(session.poisoned());
            assertEquals(
                    L2capErtmSession.TARGET_TX_WINDOW,
                    session.outstandingCount());
            assertEquals(32, session.nextTxSequence());
        }
    }

    @Test
    public void ignoresStaleReqSeqAfterSixBitWrap() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            for (int index = 0;
                    index < L2capErtmSession.TARGET_TX_WINDOW;
                    index++) {
                session.sendInformation(
                        bytes(index));
            }
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            32,
                            false,
                            false));
            for (int index = 32;
                    index < 64;
                    index++) {
                session.sendInformation(
                        bytes(index));
            }
            assertEquals(0, session.nextTxSequence());
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            4,
                            false,
                            false));
            assertFalse(session.poisoned());
            assertEquals(
                    L2capErtmSession.TARGET_TX_WINDOW,
                    session.outstandingCount());
        }
    }

    @Test
    public void suppressesDuplicateDeliveryAndRepeatsRr() {
        try (L2capErtmSession a =
                        new L2capErtmSession(
                                A_CID,
                                B_CID,
                                false);
                L2capErtmSession b =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            byte[] frame =
                    a.sendInformation(
                            bytes(0x04, 0x33));
            try (L2capErtmSession.InboundResult first =
                    b.accept(
                            frame)) {
                assertEquals(1, first.deliveredSdus().size());
                assertFalse(b.lastInboundSupervisory());
                assertEquals(0, b.lastInboundTxSequence());
                assertEquals(0, b.lastInboundRequestSequence());
                assertEquals(2, b.lastInboundInformationLength());
            }
            try (L2capErtmSession.InboundResult duplicate =
                    b.accept(
                            frame)) {
                assertTrue(
                        duplicate.deliveredSdus().isEmpty());
                List<byte[]> controls =
                        duplicate.immediateOutboundFrames();
                assertEquals(1, controls.size());
                L2capErtmCodec.Frame rr =
                        L2capErtmCodec.decode(
                                A_CID,
                                controls.get(0),
                                false);
                assertTrue(rr.supervisory);
                assertEquals(
                        L2capErtmCodec.SUPERVISORY_RR,
                        rr.supervisoryFunction);
                assertEquals(1, rr.requestSequence);
            }
            assertFalse(b.lastInboundSupervisory());
            assertEquals(0, b.lastInboundTxSequence());
            assertEquals(1, b.expectedRemoteTxSequence());
        }
    }

    @Test
    public void gapProducesRejectAndExactWindowRetransmission() {
        try (L2capErtmSession a =
                        new L2capErtmSession(
                                A_CID,
                                B_CID,
                                false);
                L2capErtmSession b =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            byte[] first =
                    a.sendInformation(
                            bytes(0x10));
            byte[] second =
                    a.sendInformation(
                            bytes(0x20));

            byte[] reject;
            try (L2capErtmSession.InboundResult gap =
                    b.accept(
                            second)) {
                assertTrue(
                        gap.deliveredSdus().isEmpty());
                List<byte[]> outbound =
                        gap.immediateOutboundFrames();
                assertEquals(1, outbound.size());
                reject = outbound.get(0);
                L2capErtmCodec.Frame decoded =
                        L2capErtmCodec.decode(
                                A_CID,
                                reject,
                                false);
                assertEquals(
                        L2capErtmCodec.SUPERVISORY_REJ,
                        decoded.supervisoryFunction);
                assertEquals(0, decoded.requestSequence);
            }

            try (L2capErtmSession.InboundResult retry =
                    a.accept(
                            reject)) {
                List<byte[]> frames =
                        retry.immediateOutboundFrames();
                assertEquals(2, frames.size());
                assertArrayEquals(first, frames.get(0));
                assertArrayEquals(second, frames.get(1));
            }

            try (L2capErtmSession.InboundResult delivered =
                    b.accept(
                            first)) {
                assertSingleBytes(
                        bytes(0x10),
                        delivered.deliveredSdus());
            }
            try (L2capErtmSession.InboundResult delivered =
                    b.accept(
                            second)) {
                assertSingleBytes(
                        bytes(0x20),
                        delivered.deliveredSdus());
            }
        }
    }

    @Test
    public void rejectCumulativelyAcknowledgesPriorFramesAndRetransmitsOnlyFromRequestSequence() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            byte[] f0 = session.sendInformation(bytes(0x10));
            byte[] f1 = session.sendInformation(bytes(0x11));
            byte[] f2 = session.sendInformation(bytes(0x12));
            byte[] f3 = session.sendInformation(bytes(0x13));
            byte[] f4 = session.sendInformation(bytes(0x14));
            assertEquals(5, session.outstandingCount());

            // REJ with reqSeq=3: acknowledges 0, 1, 2. Retransmits 3, 4 only.
            byte[] rej = supervisoryToA(
                    L2capErtmCodec.SUPERVISORY_REJ,
                    3,
                    false,
                    false);
            try (L2capErtmSession.InboundResult result = session.accept(rej)) {
                List<byte[]> retransmitted = result.immediateOutboundFrames();
                assertEquals(2, retransmitted.size());
                assertEquals(2, session.outstandingCount());
                assertEquals(3, session.oldestOutstandingSequence());

                L2capErtmCodec.Frame frame0 =
                        L2capErtmCodec.decode(A_CID, retransmitted.get(0), false);
                assertEquals(3, frame0.txSequence);
                L2capErtmCodec.Frame frame1 =
                        L2capErtmCodec.decode(A_CID, retransmitted.get(1), false);
                assertEquals(4, frame1.txSequence);
            }
        }
    }

    @Test
    public void handlesPollBusyAndSelectiveReject() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            byte[] first =
                    session.sendInformation(
                            bytes(0x40));
            byte[] second =
                    session.sendInformation(
                            bytes(0x41));

            try (L2capErtmSession.InboundResult poll =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_RR,
                                    0,
                                    true,
                                    false))) {
                List<byte[]> response =
                        poll.immediateOutboundFrames();
                assertEquals(1, response.size());
                L2capErtmCodec.Frame finalRr =
                        L2capErtmCodec.decode(
                                B_CID,
                                response.get(0),
                                false);
                assertTrue(finalRr.finalBit);
                assertFalse(finalRr.poll);
            }

            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RNR,
                            0,
                            false,
                            false));
            assertTrue(session.peerBusy());
            assertThrows(
                    IllegalStateException.class,
                    () -> session.sendInformation(
                            bytes(0x42)));

            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            0,
                            false,
                            false));
            assertFalse(session.peerBusy());

            try (L2capErtmSession.InboundResult selective =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_SREJ,
                                    1,
                                    false,
                                    false))) {
                List<byte[]> retransmission =
                        selective.immediateOutboundFrames();
                assertEquals(1, retransmission.size());
                assertArrayEquals(
                        second,
                        retransmission.get(0));
            }
            assertEquals(2, session.outstandingCount());
            assertFalse(
                    java.util.Arrays.equals(
                            first,
                            second));
        }
    }

    @Test
    public void srejDoesNotRetireTheMissingIFrame() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            byte[] first =
                    session.sendInformation(
                            bytes(0x10));
            byte[] second =
                    session.sendInformation(
                            bytes(0x20));
            byte[] third =
                    session.sendInformation(
                            bytes(0x30));
            try (L2capErtmSession.InboundResult firstReject =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_SREJ,
                                    1,
                                    false,
                                    false))) {
                assertArrayEquals(
                        second,
                        firstReject.immediateOutboundFrames().get(0));
            }
            assertEquals(3, session.outstandingCount());
            try (L2capErtmSession.InboundResult secondReject =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_SREJ,
                                    2,
                                    false,
                                    false))) {
                assertArrayEquals(
                        third,
                        secondReject.immediateOutboundFrames().get(0));
            }
            assertEquals(3, session.outstandingCount());
            try (L2capErtmSession.InboundResult retryFirst =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_SREJ,
                                    1,
                                    false,
                                    false))) {
                assertArrayEquals(
                        second,
                        retryFirst.immediateOutboundFrames().get(0));
            }
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            3,
                            false,
                            false));
            assertEquals(0, session.outstandingCount());
            assertFalse(
                    java.util.Arrays.equals(
                            first,
                            third));
        }
    }

    @Test
    public void iframePiggybackJumpRetiresOutstanding() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false);
                L2capErtmSession peer =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            byte[] first =
                    session.sendInformation(
                            bytes(0x10));
            byte[] second =
                    session.sendInformation(
                            bytes(0x20));
            byte[] third =
                    session.sendInformation(
                            bytes(0x30));
            assertEquals(3, session.outstandingCount());
            acceptAndClose(
                    peer,
                    first);
            acceptAndClose(
                    peer,
                    second);
            acceptAndClose(
                    peer,
                    third);
            byte[] jumped =
                    peer.sendInformation(
                            bytes(0x99));
            L2capErtmCodec.Frame encoded =
                    L2capErtmCodec.decode(
                            A_CID,
                            jumped,
                            false);
            assertEquals(3, encoded.requestSequence);
            try (L2capErtmSession.InboundResult ignored =
                    session.accept(
                            jumped)) {
                assertEquals(0, session.outstandingCount());
            }
        }
    }

    @Test
    public void iframeReqSeqPastWindowDoesNotHideLaterAck() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            session.sendInformation(bytes(0x10));
            session.sendInformation(bytes(0x20));
            assertEquals(2, session.outstandingCount());
            acceptAndClose(
                    session,
                    L2capErtmCodec.encodeInformationFrame(
                            A_CID,
                            0,
                            40,
                            bytes(0x99),
                            false));
            assertEquals(2, session.outstandingCount());
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            2,
                            false,
                            false));
            assertEquals(0, session.outstandingCount());
        }
    }

    @Test
    public void rrAtNextTxIsNotHiddenByLastInfoOutsideWindow() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            for (int index = 0; index < 9; index++) {
                session.sendInformation(bytes(0x10 + index));
            }
            acceptAndClose(
                    session,
                    L2capErtmCodec.encodeInformationFrame(
                            A_CID,
                            0,
                            9,
                            bytes(0x99),
                            false));
            assertEquals(0, session.outstandingCount());
            for (int index = 0; index < 32; index++) {
                session.sendInformation(bytes(0x40 + index));
            }
            assertEquals(32, session.outstandingCount());
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            40,
                            false,
                            false));
            assertEquals(1, session.outstandingCount());
            session.sendInformation(bytes(0x7e));
            assertEquals(2, session.outstandingCount());
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            42,
                            false,
                            true));
            assertEquals(0, session.outstandingCount());
        }
    }

    @Test
    public void staleRrBehindIframePiggybackDoesNotRewind() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false);
                L2capErtmSession peer =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            byte[] first =
                    session.sendInformation(
                            bytes(0x10));
            byte[] second =
                    session.sendInformation(
                            bytes(0x20));
            acceptAndClose(
                    peer,
                    first);
            acceptAndClose(
                    peer,
                    second);
            byte[] piggyback =
                    peer.sendInformation(
                            bytes(0x99));
            acceptAndClose(
                    session,
                    piggyback);
            assertEquals(0, session.outstandingCount());
            session.sendInformation(
                    bytes(0x30));
            assertEquals(1, session.outstandingCount());
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            0,
                            false,
                            false));
            assertEquals(1, session.outstandingCount());
        }
    }

    @Test
    public void staleRrBehindPiggybackDoesNotReplayRetiredFrames() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false);
                L2capErtmSession peer =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            byte[] first =
                    session.sendInformation(
                            bytes(0x10));
            byte[] second =
                    session.sendInformation(
                            bytes(0x20));
            acceptAndClose(
                    peer,
                    first);
            acceptAndClose(
                    peer,
                    second);
            acceptAndClose(
                    session,
                    peer.sendInformation(
                            bytes(0x99)));
            assertEquals(0, session.outstandingCount());
            try (L2capErtmSession.InboundResult stale =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_RR,
                                    0,
                                    false,
                                    true))) {
                assertEquals(
                        0,
                        stale.immediateOutboundFrames().size());
            }
        }
    }

    @Test
    public void staleRrBehindPiggybackDoesNotReplayWhileFramesAreOutstanding() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false);
                L2capErtmSession peer =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            acceptAndClose(peer, session.sendInformation(bytes(0x10)));
            acceptAndClose(peer, session.sendInformation(bytes(0x20)));
            acceptAndClose(peer, session.sendInformation(bytes(0x30)));
            acceptAndClose(session, peer.sendInformation(bytes(0x99)));
            assertEquals(0, session.outstandingCount());
            session.sendInformation(bytes(0x40));
            assertEquals(1, session.outstandingCount());
            acceptAndClose(session, peer.sendInformation(bytes(0x98)));
            try (L2capErtmSession.InboundResult stale =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_RR,
                                    0,
                                    false,
                                    true))) {
                assertEquals(0, stale.immediateOutboundFrames().size());
            }
            assertEquals(1, session.outstandingCount());
        }
    }

    @Test
    public void finalRrBehindOutstandingFrameReplaysRetiredPrefix() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            session.sendInformation(
                    bytes(0x10));
            session.sendInformation(
                    bytes(0x20));
            session.sendInformation(
                    bytes(0x30));
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            3,
                            false,
                            false));
            assertEquals(0, session.outstandingCount());
            session.sendInformation(
                    bytes(0x40));
            assertEquals(1, session.outstandingCount());
            try (L2capErtmSession.InboundResult behind =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_RR,
                                    1,
                                    false,
                                    true))) {
                assertEquals(
                        2,
                        behind.immediateOutboundFrames().size());
            }
            assertEquals(1, session.outstandingCount());
        }
    }

    @Test
    public void staleSrejAfterCumulativeAckRetransmitsFromHistory() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            session.sendInformation(
                    bytes(0x10));
            byte[] second =
                    session.sendInformation(
                            bytes(0x20));
            session.sendInformation(
                    bytes(0x30));
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            3,
                            false,
                            false));
            assertEquals(0, session.outstandingCount());
            try (L2capErtmSession.InboundResult stale =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_SREJ,
                                    1,
                                    false,
                                    false))) {
                assertEquals(
                        1,
                        stale.immediateOutboundFrames().size());
                assertArrayEquals(
                        second,
                        stale.immediateOutboundFrames().get(0));
            }
            assertEquals(0, session.outstandingCount());
            assertFalse(session.poisoned());
            session.buildReceiverReady();
        }
    }

    @Test
    public void wrappedOldRrAndSrejDoNotInjectPreviousCycleFrames() {
        try (L2capErtmSession session = new L2capErtmSession(A_CID, B_CID, false)) {
            // Populate history through a complete cycle, then reproduce
            // the live 325 window: outstanding 20..28, nextTx=29, RR=52.
            for (int index = 0; index < 84; index++) {
                session.sendInformation(bytes(index));
                acceptAndClose(session, supervisoryToA(
                        L2capErtmCodec.SUPERVISORY_RR, (index + 1) % 64, false, false));
            }
            for (int index = 20; index < 29; index++) {
                session.sendInformation(bytes(0x80 + index));
            }
            for (int request : new int[] {52, 53}) {
                try (L2capErtmSession.InboundResult rr = session.accept(supervisoryToA(
                        L2capErtmCodec.SUPERVISORY_RR, request, false, true))) {
                    assertTrue(rr.immediateOutboundFrames().isEmpty());
                }
                try (L2capErtmSession.InboundResult srej = session.accept(supervisoryToA(
                        L2capErtmCodec.SUPERVISORY_SREJ, request, false, false))) {
                    assertTrue(srej.immediateOutboundFrames().isEmpty());
                }
                assertEquals(9, session.outstandingCount());
                assertEquals(29, session.nextTxSequence());
            }
            // A genuine current-window SREJ must still replay current bytes.
            try (L2capErtmSession.InboundResult hole = session.accept(supervisoryToA(
                    L2capErtmCodec.SUPERVISORY_SREJ, 23, false, false))) {
                assertEquals(1, hole.immediateOutboundFrames().size());
                L2capErtmCodec.Frame replay = L2capErtmCodec.decode(
                        B_CID, hole.immediateOutboundFrames().get(0), false);
                assertEquals(23, replay.txSequence);
                assertArrayEquals(bytes(0x80 + 23), replay.information);
            }
            acceptAndClose(session, supervisoryToA(
                    L2capErtmCodec.SUPERVISORY_RR, 29, false, false));
            assertEquals(0, session.outstandingCount());
            assertFalse(session.poisoned());
        }
    }

    @Test
    public void srejAfterIframePiggybackJumpRetransmitsFromHistory() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false);
                L2capErtmSession peer =
                        new L2capErtmSession(
                                B_CID,
                                A_CID,
                                false)) {
            byte[] first =
                    session.sendInformation(
                            bytes(0x10));
            byte[] second =
                    session.sendInformation(
                            bytes(0x20));
            byte[] third =
                    session.sendInformation(
                            bytes(0x30));
            acceptAndClose(
                    peer,
                    first);
            acceptAndClose(
                    peer,
                    second);
            acceptAndClose(
                    peer,
                    third);
            byte[] jumped =
                    peer.sendInformation(
                            bytes(0x99));
            acceptAndClose(
                    session,
                    jumped);
            assertEquals(0, session.outstandingCount());
            try (L2capErtmSession.InboundResult hole =
                    session.accept(
                            supervisoryToA(
                                    L2capErtmCodec.SUPERVISORY_SREJ,
                                    1,
                                    false,
                                    false))) {
                assertArrayEquals(
                        L2capErtmCodec.updateRequestSequence(second, A_CID, 1, false),
                        hole.immediateOutboundFrames().get(0));
            }
            assertEquals(0, session.outstandingCount());
        }
    }

    @Test
    public void badAckOrSegmentedFramePoisonsSession() {
        try (L2capErtmSession staleAck =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            staleAck.sendInformation(
                    bytes(0x11));
            acceptAndClose(
                    staleAck,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            2,
                            false,
                            false));
            assertFalse(staleAck.poisoned());
            assertEquals(1, staleAck.outstandingCount());
            staleAck.buildReceiverReady();
        }

        try (L2capErtmSession segmented =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            byte[] startSar =
                    bytes(0x00, 0x40, 0x01);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> segmented.accept(
                            startSar));
            assertTrue(segmented.poisoned());
        }
    }

    @Test
    public void doesNotTreatReceiverReadySeventeenAheadOfNextTxAsAck() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            for (int index = 0; index < 12; index++) {
                session.sendInformation(bytes(index));
            }
            assertEquals(12, session.nextTxSequence());
            acceptAndClose(
                    session,
                    supervisoryToA(
                            L2capErtmCodec.SUPERVISORY_RR,
                            (12 + 17) % 64,
                            false,
                            true));
            assertEquals(12, session.outstandingCount());
            assertFalse(session.poisoned());
        }
    }

    @Test
    public void rejectsSduBeyondNegotiatedMps() {
        try (L2capErtmSession session =
                new L2capErtmSession(
                        A_CID,
                        B_CID,
                        false)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> session.sendInformation(
                            new byte[L2capErtmSession.TARGET_MPS + 1]));
        }
    }

    private static byte[] supervisoryToA(
            int function,
            int requestSequence,
            boolean poll,
            boolean finalBit) {
        return L2capErtmCodec.encodeSupervisoryFrame(
                A_CID,
                function,
                requestSequence,
                poll,
                finalBit,
                false);
    }

    private static void acceptAndClose(
            L2capErtmSession session,
            byte[] frame) {
        try (L2capErtmSession.InboundResult ignored =
                session.accept(
                        frame)) {
            // State transition is the assertion target.
        }
    }

    private static void assertSingleBytes(
            byte[] expected,
            List<byte[]> actual) {
        assertEquals(1, actual.size());
        assertArrayEquals(
                expected,
                actual.get(0));
    }

    private static byte[] bytes(
            int... values) {
        byte[] output =
                new byte[values.length];
        for (int index = 0;
                index < values.length;
                index++) {
            output[index] =
                    (byte) values[index];
        }
        return output;
    }
}
