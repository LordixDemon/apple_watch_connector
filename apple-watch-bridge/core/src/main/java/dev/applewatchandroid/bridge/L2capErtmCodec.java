package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Minimal Enhanced Retransmission Mode data-plane used by Apple BT_CL type-1
 * dynamic channels.
 *
 * <p>The private BT_CL CREATE/ACCEPT exchange allocates CIDs, but the payload
 * on those CIDs is not L2CAP Basic Mode. Apple configures flow mode 3 (ERTM)
 * with ERTM enabled. FCS is independently selected by the BT_CL service flags;
 * the terminusPairing service advertises flags {@code 0x01}, which means ERTM
 * enabled and FCS disabled. This codec intentionally implements only the
 * unsegmented I-frame and supervisory-frame subset needed for the first control
 * IKE exchange.</p>
 */
final class L2capErtmCodec {
    static final int SEQUENCE_MODULUS = 64;

    static final int SAR_UNSEGMENTED = 0;
    static final int SAR_START = 1;
    static final int SAR_END = 2;
    static final int SAR_CONTINUATION = 3;

    static final int SUPERVISORY_RR = 0;
    static final int SUPERVISORY_REJ = 1;
    static final int SUPERVISORY_RNR = 2;
    static final int SUPERVISORY_SREJ = 3;

    private static final int L2CAP_BASIC_HEADER_LENGTH = 4;
    private static final int ENHANCED_CONTROL_LENGTH = 2;
    private static final int FCS_LENGTH = 2;

    private static final int CONTROL_FRAME_TYPE = 0x0001;
    private static final int CONTROL_TXSEQ_SHIFT = 1;
    private static final int CONTROL_SUPERVISORY_SHIFT = 2;
    private static final int CONTROL_POLL = 0x0010;
    private static final int CONTROL_FINAL = 0x0080;
    private static final int CONTROL_REQSEQ_SHIFT = 8;
    private static final int CONTROL_SAR_SHIFT = 14;

    private L2capErtmCodec() {
    }

    static byte[] encodeInformationFrame(
            int destinationCid,
            int txSequence,
            int requestSequence,
            byte[] sdu,
            boolean fcsEnabled) {
        requireSequence("TxSeq", txSequence);
        requireSequence("ReqSeq", requestSequence);
        if (sdu == null) {
            throw new IllegalArgumentException("ERTM SDU is null");
        }
        int control = (txSequence << CONTROL_TXSEQ_SHIFT)
                | (requestSequence << CONTROL_REQSEQ_SHIFT)
                | (SAR_UNSEGMENTED << CONTROL_SAR_SHIFT);
        return encode(destinationCid, control, sdu, fcsEnabled);
    }

    static byte[] updateRequestSequence(
            byte[] frame,
            int destinationCid,
            int newRequestSequence,
            boolean fcsEnabled) {
        requireSequence("ReqSeq", newRequestSequence);
        if (frame == null || frame.length < ENHANCED_CONTROL_LENGTH) {
            throw new IllegalArgumentException("Invalid ERTM frame for ReqSeq update");
        }
        byte[] copy = frame.clone();
        copy[1] = (byte) ((copy[1] & 0xC0) | (newRequestSequence & 0x3F));
        if (fcsEnabled) {
            int bodyLen = copy.length - FCS_LENGTH;
            byte[] body = Arrays.copyOf(copy, bodyLen);
            int fcs = crc16(buildFcsInput(destinationCid, copy.length, body));
            copy[copy.length - 2] = (byte) (fcs & 0xFF);
            copy[copy.length - 1] = (byte) ((fcs >>> 8) & 0xFF);
        }
        return copy;
    }

    static byte[] encodeReceiverReady(
            int destinationCid,
            int requestSequence,
            boolean finalBit,
            boolean fcsEnabled) {
        return encodeSupervisoryFrame(
                destinationCid,
                SUPERVISORY_RR,
                requestSequence,
                false,
                finalBit,
                fcsEnabled);
    }

    static byte[] encodeSupervisoryFrame(
            int destinationCid,
            int supervisoryFunction,
            int requestSequence,
            boolean pollBit,
            boolean finalBit,
            boolean fcsEnabled) {
        requireSequence("ReqSeq", requestSequence);
        if (supervisoryFunction < SUPERVISORY_RR
                || supervisoryFunction
                > SUPERVISORY_SREJ
                || (pollBit && finalBit)) {
            throw new IllegalArgumentException(
                    "Invalid ERTM supervisory control");
        }
        int control = CONTROL_FRAME_TYPE
                | (supervisoryFunction
                << CONTROL_SUPERVISORY_SHIFT)
                | (requestSequence << CONTROL_REQSEQ_SHIFT);
        if (pollBit) {
            control |= CONTROL_POLL;
        }
        if (finalBit) {
            control |= CONTROL_FINAL;
        }
        return encode(
                destinationCid,
                control,
                new byte[0],
                fcsEnabled);
    }

    static Frame decode(
            int destinationCid,
            byte[] l2capPayload,
            boolean fcsEnabled) {
        requireCid(destinationCid);
        int optionalFcsLength = fcsEnabled ? FCS_LENGTH : 0;
        if (l2capPayload == null
                || l2capPayload.length
                < ENHANCED_CONTROL_LENGTH + optionalFcsLength) {
            throw new IllegalArgumentException("ERTM frame is truncated");
        }

        int informationEnd =
                l2capPayload.length - optionalFcsLength;
        int receivedFcs = -1;
        if (fcsEnabled) {
            receivedFcs = le16(l2capPayload, informationEnd);
            byte[] covered = buildFcsInput(
                    destinationCid,
                    l2capPayload.length,
                    Arrays.copyOf(l2capPayload, informationEnd));
            int expectedFcs = crc16(covered);
            if (receivedFcs != expectedFcs) {
                throw new IllegalArgumentException(String.format(
                        java.util.Locale.US,
                        "ERTM FCS mismatch received=0x%04X expected=0x%04X",
                        receivedFcs,
                        expectedFcs));
            }
        }

        int control = le16(l2capPayload, 0);
        int requestSequence =
                (control >>> CONTROL_REQSEQ_SHIFT) & 0x3F;
        boolean finalBit = (control & CONTROL_FINAL) != 0;
        if ((control & CONTROL_FRAME_TYPE) != 0) {
            if (l2capPayload.length
                    != ENHANCED_CONTROL_LENGTH + optionalFcsLength) {
                throw new IllegalArgumentException(
                        "ERTM supervisory frame contains information bytes");
            }
            return new Frame(
                    true,
                    (control >>> CONTROL_SUPERVISORY_SHIFT) & 0x03,
                    (control & CONTROL_POLL) != 0,
                    finalBit,
                    requestSequence,
                    0,
                    SAR_UNSEGMENTED,
                    new byte[0],
                    fcsEnabled,
                    receivedFcs);
        }

        int txSequence = (control >>> CONTROL_TXSEQ_SHIFT) & 0x3F;
        int sar = (control >>> CONTROL_SAR_SHIFT) & 0x03;
        return new Frame(
                false,
                0,
                false,
                finalBit,
                requestSequence,
                txSequence,
                sar,
                Arrays.copyOfRange(
                        l2capPayload,
                        ENHANCED_CONTROL_LENGTH,
                        informationEnd),
                fcsEnabled,
                receivedFcs);
    }

    /**
     * Bluetooth L2CAP FCS: reflected CRC-16 polynomial 0xA001, initial value
     * zero. The transmitted uint16 is little-endian.
     */
    static int crc16(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("CRC input is null");
        }
        int crc = 0;
        for (byte value : bytes) {
            crc ^= value & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static byte[] encode(
            int destinationCid,
            int control,
            byte[] information,
            boolean fcsEnabled) {
        requireCid(destinationCid);
        int optionalFcsLength = fcsEnabled ? FCS_LENGTH : 0;
        int l2capPayloadLength =
                ENHANCED_CONTROL_LENGTH
                        + information.length
                        + optionalFcsLength;
        if (l2capPayloadLength > 0xFFFF) {
            throw new IllegalArgumentException(
                    "ERTM frame does not fit the L2CAP uint16 length");
        }

        ByteArrayOutputStream withoutFcs = new ByteArrayOutputStream(
                ENHANCED_CONTROL_LENGTH + information.length);
        writeLe16(withoutFcs, control);
        withoutFcs.writeBytes(information);

        byte[] bodyWithoutFcs = withoutFcs.toByteArray();
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(l2capPayloadLength);
        output.writeBytes(bodyWithoutFcs);
        if (fcsEnabled) {
            int fcs = crc16(buildFcsInput(
                    destinationCid,
                    l2capPayloadLength,
                    bodyWithoutFcs));
            writeLe16(output, fcs);
        }
        return output.toByteArray();
    }

    private static byte[] buildFcsInput(
            int destinationCid,
            int l2capPayloadLength,
            byte[] bodyWithoutFcs) {
        ByteArrayOutputStream covered = new ByteArrayOutputStream(
                L2CAP_BASIC_HEADER_LENGTH + bodyWithoutFcs.length);
        writeLe16(covered, l2capPayloadLength);
        writeLe16(covered, destinationCid);
        covered.writeBytes(bodyWithoutFcs);
        return covered.toByteArray();
    }

    private static int le16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static void writeLe16(
            ByteArrayOutputStream output,
            int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private static void requireCid(int cid) {
        if (cid < 0 || cid > 0xFFFF) {
            throw new IllegalArgumentException("Invalid L2CAP CID");
        }
    }

    private static void requireSequence(String label, int sequence) {
        if (sequence < 0 || sequence >= SEQUENCE_MODULUS) {
            throw new IllegalArgumentException(
                    label + " must be in the range 0..63");
        }
    }

    static final class Frame {
        final boolean supervisory;
        final int supervisoryFunction;
        final boolean poll;
        final boolean finalBit;
        final int requestSequence;
        final int txSequence;
        final int sar;
        final byte[] information;
        final boolean fcsPresent;
        final int fcs;

        Frame(
                boolean supervisory,
                int supervisoryFunction,
                boolean poll,
                boolean finalBit,
                int requestSequence,
                int txSequence,
                int sar,
                byte[] information,
                boolean fcsPresent,
                int fcs) {
            this.supervisory = supervisory;
            this.supervisoryFunction = supervisoryFunction;
            this.poll = poll;
            this.finalBit = finalBit;
            this.requestSequence = requestSequence;
            this.txSequence = txSequence;
            this.sar = sar;
            this.information =
                    Arrays.copyOf(information, information.length);
            this.fcsPresent = fcsPresent;
            this.fcs = fcs;
        }
    }
}
