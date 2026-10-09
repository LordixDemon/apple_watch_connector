package dev.applewatchandroid.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Stateful, unsegmented Enhanced Retransmission Mode scheduler for an Apple
 * normal Bluetooth pipe.
 *
 * <p>The negotiated Apple terminusLink profile uses a transmit window of 32
 * and an MPS of 2042 bytes. All currently observed ordinary NetworkRelay IKE
 * messages fit in one I-frame, so accepting SAR fragments here would hide a
 * protocol mismatch instead of helping the first bring-up.</p>
 */
final class L2capErtmSession implements AutoCloseable {
    static final int TARGET_MPS = 2042;
    static final int TARGET_TX_WINDOW = 32;

    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    private final int localCid;
    private final int remoteCid;
    private final boolean fcsEnabled;
    private final ArrayDeque<SentFrame> unacknowledged =
            new ArrayDeque<>();
    /**
     * I-frames just retired by a jumped piggyback {@code ReqSeq}. Live PIN
     * 0.2.126 Watch SREJ'd TxSeq 1/2 after that retire ({@code retained=5});
     * ignoring the hole left Check in a gap, TCP una stuck, then {@code 0x13}.
     * History is not outstanding: SREJ may clone these, RR/I-frame must not.
     */
    private final ArrayDeque<SentFrame> srejHistory =
            new ArrayDeque<>();

    private int nextTxSequence;
    private int expectedRemoteTxSequence;
    private boolean peerBusy;
    private boolean pollOutstanding;
    private boolean rejectActionedDuringPoll;
    private int selectiveActionedDuringPoll = -1;
    private boolean closed;
    private boolean poisoned;
    private boolean lastInboundSupervisory;
    private int lastInboundSupervisoryFunction = -1;
    private int lastInboundTxSequence = -1;
    private int lastInboundRequestSequence = -1;
    private int lastInboundInformationLength = -1;
    /** Last I-frame piggyback ReqSeq; stale RR behind this is ignored. */
    private int lastInformationReqSeq = -1;

    L2capErtmSession(
            int localCid,
            int remoteCid,
            boolean fcsEnabled) {
        requireCid("local", localCid);
        requireCid("remote", remoteCid);
        this.localCid = localCid;
        this.remoteCid = remoteCid;
        this.fcsEnabled = fcsEnabled;
    }

    /**
     * Builds and retains one exact I-frame until its ReqSeq acknowledgement.
     * The returned byte array is owned by the caller.
     */
    byte[] sendInformation(
            byte[] sdu) {
        requireOpen();
        if (sdu == null) {
            throw new IllegalArgumentException("ERTM SDU is null");
        }
        if (sdu.length > TARGET_MPS) {
            throw new IllegalArgumentException(
                    "ERTM SDU exceeds the negotiated 2042-byte MPS");
        }
        if (transmitBlocked()) {
            throw new IllegalStateException(
                    "ERTM transmission blocked by receiver-not-ready or pending Final");
        }
        if (unacknowledged.size() >= TARGET_TX_WINDOW) {
            throw new IllegalStateException(
                    "ERTM transmit window is full");
        }

        int sequence = nextTxSequence;
        byte[] encoded =
                L2capErtmCodec.encodeInformationFrame(
                        remoteCid,
                        sequence,
                        expectedRemoteTxSequence,
                        sdu,
                        fcsEnabled);
        unacknowledged.addLast(
                new SentFrame(
                        sequence,
                        encoded));
        nextTxSequence =
                increment(
                        nextTxSequence);
        return encoded.clone();
    }

    /**
     * Accepts one complete L2CAP ERTM payload (the Basic header is excluded).
     *
     * <p>Returned retransmissions are byte-identical to the retained original
     * frame, as required by ERTM. Callers should close the result after
     * consuming it because its buffers can contain IKE or ESP material.</p>
     */
    InboundResult accept(
            byte[] l2capPayload) {
        requireOpen();
        L2capErtmCodec.Frame frame = null;
        try {
            frame =
                    L2capErtmCodec.decode(
                            localCid,
                            l2capPayload,
                            fcsEnabled);
            lastInboundSupervisory = frame.supervisory;
            lastInboundSupervisoryFunction =
                    frame.supervisory
                            ? frame.supervisoryFunction
                            : -1;
            lastInboundTxSequence =
                    frame.supervisory
                            ? -1
                            : frame.txSequence;
            lastInboundRequestSequence =
                    frame.requestSequence;
            lastInboundInformationLength =
                    frame.information.length;
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (logger != null) {
                if (frame.supervisory) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[ERTM RX cid=0x%04X] supervisory: func=%d reqSeq=%d poll=%b final=%b unacked=%d expectedRemote=%d nextTx=%d",
                            localCid,
                            frame.supervisoryFunction,
                            frame.requestSequence,
                            frame.poll,
                            frame.finalBit,
                            unacknowledged.size(),
                            expectedRemoteTxSequence,
                            nextTxSequence));
                } else {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[ERTM RX cid=0x%04X] iframe: txSeq=%d reqSeq=%d sduLen=%d expectedSeq=%d unacked=%d nextTx=%d",
                            localCid,
                            frame.txSequence,
                            frame.requestSequence,
                            frame.information.length,
                            expectedRemoteTxSequence,
                            unacknowledged.size(),
                            nextTxSequence));
                }
            }
            acknowledgeCumulativeRequestSequence(frame);
            if (frame.supervisory) {
                return acceptSupervisory(
                        frame);
            }
            if (frame.finalBit && pollOutstanding) {
                pollOutstanding = false;
                try (InboundResult received = acceptInformation(frame)) {
                    List<byte[]> recovery = received.immediateOutboundFrames();
                    if (!rejectActionedDuringPoll) recovery.addAll(copyUnacknowledged(TARGET_TX_WINDOW));
                    rejectActionedDuringPoll = false;
                    selectiveActionedDuringPoll = -1;
                    return new InboundResult(received.deliveredSdus(), recovery, received.acknowledgementRecommended);
                }
            }
            return acceptInformation(frame);
        } catch (RuntimeException failure) {
            poison();
            throw failure;
        } finally {
            if (frame != null) {
                Arrays.fill(
                        frame.information,
                        (byte) 0);
            }
        }
    }

    byte[] buildReceiverReady() {
        requireOpen();
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            logger.accept("[ERTM TX] RR: reqSeq=" + expectedRemoteTxSequence);
        }
        return L2capErtmCodec.encodeReceiverReady(
                remoteCid,
                expectedRemoteTxSequence,
                false,
                fcsEnabled);
    }

    byte[] buildReceiverReadyPoll() {
        requireOpen();
        if (!pollOutstanding) {
            rejectActionedDuringPoll = false;
            selectiveActionedDuringPoll = -1;
        }
        pollOutstanding = true;
        return L2capErtmCodec.encodeSupervisoryFrame(
                remoteCid,
                L2capErtmCodec.SUPERVISORY_RR,
                expectedRemoteTxSequence,
                true,
                false,
                fcsEnabled);
    }

    int outstandingCount() {
        return unacknowledged.size();
    }

    /**
     * Exact copy of the oldest unacked I-frame for a timeout retransmission.
     * Does not consume the retained slot: Watch V(R) may still be that TxSeq
     * after an I-frame piggyback advertised a jumped ReqSeq.
     */
    byte[] copyOldestUnacknowledged() {
        requireOpen();
        if (transmitBlocked() || unacknowledged.isEmpty()) {
            return null;
        }
        return copyForRetransmit(unacknowledged.peekFirst());
    }

    /**
     * Copies the oldest outstanding I-frames. A timeout that repeats only
     * TxSeq 26 never fills the holes the Watch SREJ'd further up the window.
     */
    java.util.List<byte[]> copyUnacknowledged(int limit) {
        requireOpen();
        java.util.ArrayList<byte[]> copies = new java.util.ArrayList<>();
        if (transmitBlocked() || limit <= 0) {
            return copies;
        }
        int copied = 0;
        for (SentFrame sent : unacknowledged) {
            if (copied >= limit) {
                break;
            }
            copies.add(copyForRetransmit(sent));
            copied++;
        }
        return copies;
    }

    private byte[] copyForRetransmit(SentFrame sent) {
        return L2capErtmCodec.updateRequestSequence(
                sent.encoded,
                remoteCid,
                expectedRemoteTxSequence,
                fcsEnabled);
    }

    int oldestOutstandingSequence() {
        return unacknowledged.isEmpty()
                ? -1
                : unacknowledged.peekFirst().sequence;
    }

    int nextTxSequence() {
        return nextTxSequence;
    }

    int expectedRemoteTxSequence() {
        return expectedRemoteTxSequence;
    }

    boolean peerBusy() {
        return peerBusy;
    }

    boolean transmitBlocked() {
        return peerBusy || pollOutstanding;
    }

    boolean poisoned() {
        return poisoned;
    }

    boolean lastInboundSupervisory() {
        requireOpen();
        return lastInboundSupervisory;
    }

    int lastInboundSupervisoryFunction() {
        requireOpen();
        return lastInboundSupervisoryFunction;
    }

    int lastInboundTxSequence() {
        requireOpen();
        return lastInboundTxSequence;
    }

    int lastInboundRequestSequence() {
        requireOpen();
        return lastInboundRequestSequence;
    }

    int lastInboundInformationLength() {
        requireOpen();
        return lastInboundInformationLength;
    }

    private InboundResult acceptInformation(
            L2capErtmCodec.Frame frame) {
        if (frame.sar
                != L2capErtmCodec.SAR_UNSEGMENTED) {
            throw new IllegalArgumentException(
                    "Segmented ERTM I-frame is outside the "
                            + "negotiated 2042-byte normal-pipe profile");
        }

        int forwardDistance =
                distance(
                        expectedRemoteTxSequence,
                        frame.txSequence);
        if (forwardDistance == 0) {
            byte[] delivered =
                    frame.information.clone();
            expectedRemoteTxSequence =
                    increment(
                            expectedRemoteTxSequence);
            return InboundResult.delivered(
                    delivered);
        }

        if (forwardDistance < TARGET_TX_WINDOW) {
            byte[] reject =
                    L2capErtmCodec.encodeSupervisoryFrame(
                            remoteCid,
                            L2capErtmCodec.SUPERVISORY_REJ,
                            expectedRemoteTxSequence,
                            false,
                            false,
                            fcsEnabled);
            return InboundResult.control(
                    reject,
                    false);
        }

        // The sequence is behind V(R): acknowledge the already delivered
        // range again without exposing duplicate application bytes.
        byte[] duplicateAck =
                L2capErtmCodec.encodeReceiverReady(
                        remoteCid,
                        expectedRemoteTxSequence,
                        false,
                        fcsEnabled);
        return InboundResult.control(
                duplicateAck,
                false);
    }

    private InboundResult acceptSupervisory(
            L2capErtmCodec.Frame frame) {
        List<byte[]> immediate =
                new ArrayList<>();
        boolean answersPoll = frame.finalBit && pollOutstanding;
        if (answersPoll) pollOutstanding = false;
        switch (frame.supervisoryFunction) {
            case L2capErtmCodec.SUPERVISORY_RR -> {
                peerBusy = false;
                if (answersPoll) {
                    // Core Vol3 PartA 8.6: a solicited Final completes recovery;
                    // replay every remaining frame, not just a four-frame prefix.
                    if (!rejectActionedDuringPoll) immediate.addAll(copyUnacknowledged(TARGET_TX_WINDOW));
                    rejectActionedDuringPoll = false;
                } else {
                    immediate.addAll(replayRetiredIfReceiverReadyBehind(frame.requestSequence));
                }
            }
            case L2capErtmCodec.SUPERVISORY_RNR -> peerBusy = true;
            case L2capErtmCodec.SUPERVISORY_REJ -> {
                peerBusy = false;
                if (!answersPoll || !rejectActionedDuringPoll) {
                    appendRetransmissionsFrom(frame.requestSequence, immediate);
                }
                if (answersPoll) rejectActionedDuringPoll = false;
                else if (pollOutstanding) rejectActionedDuringPoll = true;
                java.util.function.Consumer<String> rejLogger = diagnosticLogger;
                if (rejLogger != null) {
                    rejLogger.accept(String.format(
                            java.util.Locale.US,
                            "[ERTM RX] REJ reqSeq=%d retransmitting %d frames (unacked=%d)",
                            frame.requestSequence,
                            immediate.size(),
                            unacknowledged.size()));
                }
            }
            case L2capErtmCodec.SUPERVISORY_SREJ -> {
                peerBusy = false;
                SentFrame live =
                        findIn(
                                unacknowledged,
                                frame.requestSequence);
                SentFrame requested =
                        live != null
                                ? live
                                : isRetiredSequenceBehindWindow(frame.requestSequence)
                                        ? findNewest(
                                        srejHistory,
                                        frame.requestSequence)
                                        : null;
                java.util.function.Consumer<String> srejLogger = diagnosticLogger;
                if (requested == null) {
                    // Wrap-stale SREJ outside the last Tx window. Throwing
                    // here poisoned the IDS pipe (live watchOS 26.2 TxSeq 4
                    // after ReqSeq=11/14).
                    if (srejLogger != null) {
                        srejLogger.accept(String.format(
                                java.util.Locale.US,
                                "[ERTM RX] SREJ ignored: txSeq=%d not retained retained=%d history=%d",
                                frame.requestSequence,
                                unacknowledged.size(),
                                srejHistory.size()));
                    }
                } else if (!answersPoll || selectiveActionedDuringPoll != frame.requestSequence) {
                    immediate.add(
                            L2capErtmCodec.updateRequestSequence(
                                    requested.encoded,
                                    remoteCid,
                                    expectedRemoteTxSequence,
                                    fcsEnabled));
                    if (srejLogger != null) {
                        srejLogger.accept(String.format(
                                java.util.Locale.US,
                                "[ERTM TX] SREJ retransmit: txSeq=%d retained=%d history=%b",
                                frame.requestSequence,
                                unacknowledged.size(),
                                live == null));
                    }
                }
                if (answersPoll) selectiveActionedDuringPoll = -1;
                else if (pollOutstanding && requested != null) selectiveActionedDuringPoll = frame.requestSequence;
            }
            default -> throw new IllegalArgumentException(
                    "Unknown ERTM supervisory function");
        }

        if (frame.poll) {
            immediate.add(
                    L2capErtmCodec.encodeSupervisoryFrame(
                            remoteCid,
                            L2capErtmCodec.SUPERVISORY_RR,
                            expectedRemoteTxSequence,
                            false,
                            true,
                            fcsEnabled));
        }
        return new InboundResult(
                Collections.emptyList(),
                immediate,
                false);
    }

    /**
     * I-frame, RR and REJ ReqSeq values are cumulative acknowledgements
     * (Bluetooth Core Vol 3 Part A 8.6). SREJ names a hole and must not retire.
     *
     * <p>Live 0.2.123 Watch I-frames advertised {@code ReqSeq=10/32} while RR
     * lagged at {@code 2}. Core 8.6: the I-frame piggyback is {@code V(R)};
     * the delayed RR is stale. Live 0.2.124 trusted RR instead, never retired
     * the handshake window, held Check until {@code 0x13}.</p>
     */
    private void acknowledgeCumulativeRequestSequence(
            L2capErtmCodec.Frame frame) {
        if (frame.supervisory
                && frame.supervisoryFunction
                == L2capErtmCodec.SUPERVISORY_SREJ) {
            // P=0 only names a requested hole. P=1 also acknowledges its prefix.
            if (frame.poll) acknowledgeThrough(frame.requestSequence);
            return;
        }
        if (!frame.supervisory) {
            // Live 19:49: an I-frame piggyback of ReqSeq=63 while the
            // outstanding base was 30 (distance 33, past the window) was
            // stored as the latest V(R). Later RR values that actually
            // acked TxSeq 30 were then discarded as stale-behind that 63,
            // and the window stayed full.
            if (cumulativeAckInWindow(frame.requestSequence)) {
                lastInformationReqSeq =
                        frame.requestSequence;
            }
            acknowledgeThrough(
                    frame.requestSequence);
            return;
        }
        if (frame.supervisoryFunction
                == L2capErtmCodec.SUPERVISORY_REJ) {
            acknowledgeThrough(
                    frame.requestSequence);
            return;
        }
        if (lastInformationReqSeqStillInWindow()) {
            int behindPiggyback =
                    distance(
                            frame.requestSequence,
                            lastInformationReqSeq);
            if (behindPiggyback > 0
                    && behindPiggyback < TARGET_TX_WINDOW) {
                java.util.function.Consumer<String> logger = diagnosticLogger;
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[ERTM RR-STALE cid=0x%04X] supervisory reqSeq=%d ignored: lastInfoReqSeq=%d behind=%d",
                            localCid,
                            frame.requestSequence,
                            lastInformationReqSeq,
                            behindPiggyback));
                }
                return;
            }
        }
        acknowledgeThrough(
                frame.requestSequence);
    }

    /**
     * An I-frame piggyback is only a watermark while it still falls inside
     * the outstanding window. Live 21:40: RR reqSeq=42, nextTx=42, unacked=2,
     * but lastInfoReqSeq=9 made distance(42, 9)=31 and the RR was dropped.
     */
    private boolean lastInformationReqSeqStillInWindow() {
        return lastInformationReqSeq >= 0
                && cumulativeAckInWindow(lastInformationReqSeq);
    }

    private boolean cumulativeAckInWindow(int requestSequence) {
        int base = unacknowledged.isEmpty()
                ? nextTxSequence
                : unacknowledged.peekFirst().sequence;
        return distance(base, requestSequence) <= unacknowledged.size();
    }

    private void acknowledgeThrough(
            int requestSequence) {
        int base =
                unacknowledged.isEmpty()
                        ? nextTxSequence
                        : unacknowledged.peekFirst().sequence;
        int acknowledged =
                distance(
                        base,
                        requestSequence);
        if (acknowledged > 0
                && acknowledged <= unacknowledged.size()) {
            for (int index = 0;
                    index < acknowledged;
                    index++) {
                retireToHistory(
                        unacknowledged.removeFirst());
            }
            java.util.function.Consumer<String> logger = diagnosticLogger;
            if (logger != null) {
                logger.accept(String.format(
                        java.util.Locale.US,
                        "[ERTM ACK cid=0x%04X] retired=%d through reqSeq=%d unacked=%d",
                        localCid,
                        acknowledged,
                        requestSequence,
                        unacknowledged.size()));
            }
            return;
        }
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            logger.accept(String.format(
                    java.util.Locale.US,
                    "[ERTM ACK IGNORED cid=0x%04X] reqSeq=%d base=%d dist=%d unacked=%d nextTx=%d lastInfoReqSeq=%d",
                    localCid,
                    requestSequence,
                    base,
                    acknowledged,
                    unacknowledged.size(),
                    nextTxSequence,
                    lastInformationReqSeq));
        }
        // Duplicate, already-acked, or wrap-stale ReqSeq. Live Watch kept
        // advertising ReqSeq=54 after our TxSeq wrapped 63→0; treating that
        // as "unsent" poisoned the whole IDS pipe.
    }

    private void appendRetransmissionsFrom(
            int requestSequence,
            List<byte[]> destination) {
        for (SentFrame sent : unacknowledged) {
            int offset =
                    distance(
                            requestSequence,
                            sent.sequence);
            if (offset < TARGET_TX_WINDOW) {
                destination.add(
                        L2capErtmCodec.updateRequestSequence(
                                sent.encoded,
                                remoteCid,
                                expectedRemoteTxSequence,
                                fcsEnabled));
            }
        }
    }

    private void retireToHistory(
            SentFrame sent) {
        srejHistory.addLast(
                sent);
        // Live 0.2.198: the Watch SREJ'd TxSeq 31–57 after those frames had
        // already left the 32-deep history. A full sequence cycle stays
        // replayable so a selective reject can still be answered.
        while (srejHistory.size() > L2capErtmCodec.SEQUENCE_MODULUS) {
            srejHistory.removeFirst().destroy();
        }
    }

    private static SentFrame findIn(
            ArrayDeque<SentFrame> frames,
            int sequence) {
        for (SentFrame sent : frames) {
            if (sent.sequence == sequence) {
                return sent;
            }
        }
        return null;
    }

    private static SentFrame findNewest(
            ArrayDeque<SentFrame> frames,
            int sequence) {
        SentFrame found = null;
        for (SentFrame sent : frames) {
            if (sent.sequence == sequence) {
                found = sent;
            }
        }
        return found;
    }

    private void requireOpen() {
        if (poisoned) {
            throw new IllegalStateException(
                    "ERTM session is poisoned");
        }
        if (closed) {
            throw new IllegalStateException(
                    "ERTM session is closed");
        }
    }

    private void poison() {
        poisoned = true;
        destroyRetained();
    }

    private void destroyRetained() {
        while (!unacknowledged.isEmpty()) {
            unacknowledged.removeFirst().destroy();
        }
        while (!srejHistory.isEmpty()) {
            srejHistory.removeFirst().destroy();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        destroyRetained();
    }

    /**
     * The peer's RR names a TxSeq we already retired. Replay a short prefix
     * from history so a stalled activation record can be sent again.
     */
    private java.util.List<byte[]> replayRetiredIfReceiverReadyBehind(
            int requestSequence) {
        java.util.ArrayList<byte[]> copies = new java.util.ArrayList<>();
        int oldest =
                unacknowledged.isEmpty()
                        ? nextTxSequence
                        : unacknowledged.peekFirst().sequence;
        if (requestSequence == oldest) {
            return copies;
        }
        // A later I-frame already piggybacked a higher ReqSeq. This RR is
        // the delayed ack from live 0.2.123, not a hole. Replaying it while
        // newer frames are still outstanding floods the window and the real
        // TxSeq never gets acked (live 0.2.218: RR reqSeq=24, lastInfo=38,
        // unacked TxSeq=38).
        if (lastInformationReqSeqStillInWindow()) {
            int behindPiggyback =
                    distance(
                            requestSequence,
                            lastInformationReqSeq);
            if (behindPiggyback > 0
                    && behindPiggyback < TARGET_TX_WINDOW) {
                return copies;
            }
        }
        int ahead = distance(oldest, requestSequence);
        if (ahead > 0 && ahead <= unacknowledged.size()) {
            return copies;
        }
        int missing = distance(requestSequence, oldest);
        // Live 325: base=20, nextTx=29, delayed RR=52. Distance 32 is
        // ambiguous across the six-bit wrap, not proof of a retired hole.
        // Replaying the previous cycle's 52..59 made the peer request
        // 23..60 and eventually advertise ReqSeq=62 past our nextTx=53.
        if (missing == 0 || missing >= TARGET_TX_WINDOW
                || missing + unacknowledged.size() > TARGET_TX_WINDOW) {
            return copies;
        }
        int sequence = requestSequence;
        int limit = Math.min(8, missing);
        for (int index = 0; index < limit; index++) {
            SentFrame sent = findNewest(srejHistory, sequence);
            if (sent != null) {
                copies.add(copyForRetransmit(sent));
            }
            sequence = increment(sequence);
        }
        if (!copies.isEmpty() && diagnosticLogger != null) {
            diagnosticLogger.accept(String.format(
                    java.util.Locale.US,
                    "[ERTM TX] RR replay: from reqSeq=%d frames=%d missing=%d nextTx=%d",
                    requestSequence,
                    copies.size(),
                    missing,
                    nextTxSequence));
        }
        return copies;
    }

    private boolean isRetiredSequenceBehindWindow(int sequence) {
        int oldest = unacknowledged.isEmpty()
                ? nextTxSequence
                : unacknowledged.peekFirst().sequence;
        int behind = distance(sequence, oldest);
        return behind > 0 && behind < TARGET_TX_WINDOW
                && behind + unacknowledged.size() <= TARGET_TX_WINDOW;
    }

    private static int increment(
            int sequence) {
        return (sequence + 1)
                % L2capErtmCodec.SEQUENCE_MODULUS;
    }

    private static int distance(
            int from,
            int to) {
        return (to
                - from
                + L2capErtmCodec.SEQUENCE_MODULUS)
                % L2capErtmCodec.SEQUENCE_MODULUS;
    }

    private static void requireCid(
            String label,
            int cid) {
        if (cid < 0 || cid > 0xffff) {
            throw new IllegalArgumentException(
                    "Invalid " + label + " L2CAP CID");
        }
    }

    static final class InboundResult implements AutoCloseable {
        private final List<byte[]> deliveredSdus;
        private final List<byte[]> immediateOutboundFrames;
        final boolean acknowledgementRecommended;
        private boolean destroyed;

        private InboundResult(
                List<byte[]> deliveredSdus,
                List<byte[]> immediateOutboundFrames,
                boolean acknowledgementRecommended) {
            this.deliveredSdus =
                    copyAndWipe(
                            deliveredSdus);
            this.immediateOutboundFrames =
                    copyAndWipe(
                            immediateOutboundFrames);
            this.acknowledgementRecommended =
                    acknowledgementRecommended;
        }

        static InboundResult delivered(
                byte[] sdu) {
            return new InboundResult(
                    Collections.singletonList(
                            sdu),
                    Collections.emptyList(),
                    true);
        }

        static InboundResult control(
                byte[] frame,
                boolean acknowledgementRecommended) {
            return new InboundResult(
                    Collections.emptyList(),
                    Collections.singletonList(
                            frame),
                    acknowledgementRecommended);
        }

        List<byte[]> deliveredSdus() {
            requireAlive();
            return deepCopy(
                    deliveredSdus);
        }

        List<byte[]> immediateOutboundFrames() {
            requireAlive();
            return deepCopy(
                    immediateOutboundFrames);
        }

        private void requireAlive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "ERTM inbound result was destroyed");
            }
        }

        @Override
        public void close() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            wipeAll(
                    deliveredSdus);
            wipeAll(
                    immediateOutboundFrames);
        }

        private static List<byte[]> deepCopy(
                List<byte[]> values) {
            List<byte[]> output =
                    new ArrayList<>(
                            values.size());
            for (byte[] value : values) {
                output.add(
                        value.clone());
            }
            return output;
        }

        private static List<byte[]> copyAndWipe(
                List<byte[]> values) {
            List<byte[]> output =
                    deepCopy(
                            values);
            for (byte[] value : values) {
                Arrays.fill(
                        value,
                        (byte) 0);
            }
            return output;
        }

        private static void wipeAll(
                List<byte[]> values) {
            for (byte[] value : values) {
                Arrays.fill(
                        value,
                        (byte) 0);
            }
            values.clear();
        }
    }

    private static final class SentFrame {
        final int sequence;
        final byte[] encoded;

        SentFrame(
                int sequence,
                byte[] encoded) {
            this.sequence = sequence;
            this.encoded =
                    encoded.clone();
        }

        void destroy() {
            Arrays.fill(
                    encoded,
                    (byte) 0);
        }
    }
}
