package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fixed-channel transactions must remain serviced while NetworkRelay owns its dynamic CID. */
final class BluetoothLinkMaintenance {
    static final int ATT_CID = 4;
    static final int LE_SIGNALING_CID = 5;

    private BluetoothLinkMaintenance() {}

    @FunctionalInterface interface AttributeReader {
        byte[] read(byte[] request) throws Exception;
    }

    /** Server transactions only: late discovery responses never start legacy setup writes. */
    static final class AttServer {
        private final AttributeReader attributes;
        private int connection = -1;
        private int mtu = 23;
        private int serviceChangedCccd;
        private int continuityCccd;

        AttServer(AttributeReader attributes) { this.attributes = attributes; }

        void reset() { connection = -1; mtu = 23; serviceChangedCccd = 0; continuityCccd = 0; }

        byte[] accept(int handle, byte[] pdu) throws Exception {
            if (connection != handle) { reset(); connection = handle; }
            if (pdu == null || pdu.length == 0) return null;
            int op = pdu[0] & 255;
            if (op == 0x1d) return pdu.length >= 3 ? new byte[]{0x1e} : null;
            if (op == 0x02) {
                if (pdu.length != 3 || u16(pdu, 1) < 23) return error(op, 0, 0x04);
                mtu = Math.min(251, u16(pdu, 1));
                return new byte[]{0x03, (byte)251, 0}; // Advertise server RX MTU, not the negotiated minimum.
            }
            if (op == 0x12 || op == 0x52) {
                if (pdu.length < 3) return op == 0x12 ? error(op, 0, 0x04) : null;
                int attribute = u16(pdu, 1);
                int code = 0;
                if (attribute != 9 && attribute != 20) code = attribute == 0 ? 1 : 3;
                else if (pdu.length != 5) code = 0x0d;
                else {
                    int value = u16(pdu, 3);
                    int supported = attribute == 9 ? 2 : 1;
                    if ((value & ~supported) != 0) code = 0x13;
                    else if (attribute == 9) serviceChangedCccd = value;
                    else continuityCccd = value;
                }
                return op == 0x52 ? null : code == 0 ? new byte[]{0x13} : error(op, attribute, code);
            }
            // No response to errors, responses, confirmations, notifications or commands.
            if ((op & 0x40) != 0 || (op & 1) != 0 || op == 0x1e) return null;
            int attribute = pdu.length >= 3 ? u16(pdu, 1) : 0;
            if (op == 0x0a && pdu.length == 3 && (attribute == 9 || attribute == 20)) {
                int value = attribute == 9 ? serviceChangedCccd : continuityCccd;
                return new byte[]{0x0b, (byte)value, (byte)(value >>> 8)};
            }
            if (op != 0x04 && op != 0x08 && op != 0x0a && op != 0x10) {
                return error(op, attribute, 0x06);
            }
            int length = op == 0x04 ? 5 : op == 0x0a ? 3 : 7;
            if (pdu.length != length) {
                // The local database has only 16-bit attribute types.
                if ((op == 0x08 || op == 0x10) && pdu.length == 21) return error(op, attribute, 0x0a);
                return error(op, attribute, 0x04);
            }
            if (attribute == 0 || (op != 0x0a && u16(pdu, 3) < attribute)) return error(op, attribute, 1);
            byte[] response = attributes.read(pdu);
            if (response == null) return error(op, attribute, 0x06);
            if (response.length <= mtu) return response;
            int responseOp = response[0] & 255;
            int size = mtu;
            if (responseOp == 0x09 || responseOp == 0x11 || responseOp == 0x05) {
                int entry = responseOp == 0x05 ? (response[1] == 1 ? 4 : 18) : response[1] & 255;
                if (entry == 0 || entry > mtu - 2) {
                    Arrays.fill(response, (byte)0);
                    return error(op, attribute, 0x06);
                }
                size = 2 + (mtu - 2) / entry * entry;
            }
            byte[] bounded = Arrays.copyOf(response, size);
            Arrays.fill(response, (byte)0);
            return bounded;
        }
    }

    /** Reject unsupported LE procedures explicitly, without changing working link parameters. */
    static List<byte[]> leSignalingReplies(byte[] pdu) {
        List<byte[]> replies = new ArrayList<>();
        if (pdu == null || pdu.length > 1024) return replies;
        for (int offset = 0, count = 0; offset + 4 <= pdu.length && count < 32; count++) {
            int code = pdu[offset] & 255;
            int id = pdu[offset + 1] & 255;
            int size = u16(pdu, offset + 2);
            if (size > pdu.length - offset - 4) break;
            // These are replies or one-way flow credits; never create a reject loop.
            if (id != 0 && code != 1 && code != 7 && code != 0x13 && code != 0x15
                    && code != 0x16 && code != 0x18 && code != 0x1a) {
                if (code == 0x12 && size == 8) replies.add(new byte[]{0x13, (byte)id, 2, 0, 1, 0});
                else replies.add(new byte[]{1, (byte)id, 2, 0, 0, 0});
            }
            offset += 4 + size;
        }
        return replies;
    }

    /** LE Remote Connection Parameter Request Negative Reply (HCI 0x2021). */
    static byte[] remoteParameterRequestRejection(byte[] event, int connectionHandle) {
        if (event == null || event.length != 13 || (event[0] & 255) != 0x3e
                || (event[1] & 255) != 11 || event[2] != 6 || u16(event, 3) != connectionHandle) return null;
        return HciCodec.buildCommand(0x2021, new byte[]{event[3], event[4], 0x3b});
    }

    private static byte[] error(int opcode, int handle, int code) {
        return new byte[]{1, (byte)opcode, (byte)handle, (byte)(handle >>> 8), (byte)code};
    }

    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
}
