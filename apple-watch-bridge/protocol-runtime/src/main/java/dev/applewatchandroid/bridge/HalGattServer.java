package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.Locale;
import static dev.applewatchandroid.bridge.ProtocolHost.log;

/**
 * Minimal BLE ATT server answering Watch-initiated GATT discovery and Continuity reads
 * during normal-link and setup connections.
 *
 * <p>Client ATT responses ({@code 0x01}, {@code 0x03}, {@code 0x05}, {@code 0x07},
 * {@code 0x09}, {@code 0x0B}, {@code 0x11}, {@code 0x13}) are ignored so an unsolicited
 * peer response never triggers an ATT send loop.</p>
 */
final class HalGattServer {
    private HalGattServer() {
    }

    static byte[] handleRequest(byte[] request) {
        if (request == null || request.length == 0) {
            return null;
        }
        int opcode = request[0] & 0xFF;
        if (opcode == 0x02) { // ATT_EXCHANGE_MTU_REQ
            int clientMtu = request.length >= 3
                    ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8))
                    : 23;
            int serverMtu = Math.max(23, Math.min(clientMtu, 251));
            return new byte[]{0x03, (byte) (serverMtu & 0xFF), (byte) ((serverMtu >> 8) & 0xFF)};
        }
        if (opcode == 0x10 && request.length >= 5) { // ATT_READ_BY_GROUP_TYPE_REQ
            int startHandle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            int endHandle = (request[3] & 0xFF) | ((request[4] & 0xFF) << 8);
            int uuid = request.length >= 7
                    ? ((request[5] & 0xFF) | ((request[6] & 0xFF) << 8))
                    : 0x2800;
            if (uuid == 0x2800) {
                if (startHandle <= 1 && endHandle >= 1) {
                    return new byte[]{
                            0x11, 0x06,
                            0x01, 0x00, 0x05, 0x00, 0x00, 0x18, // GAP handles 1..5
                            0x06, 0x00, 0x09, 0x00, 0x01, 0x18, // GATT handles 6..9
                            0x0A, 0x00, 0x10, 0x00, 0x0A, 0x18, // DIS handles 10..16
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE  // Continuity handles 17..20
                    };
                }
                if (startHandle <= 6 && endHandle >= 6) {
                    return new byte[]{
                            0x11, 0x06,
                            0x06, 0x00, 0x09, 0x00, 0x01, 0x18,
                            0x0A, 0x00, 0x10, 0x00, 0x0A, 0x18,
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE
                    };
                }
                if (startHandle <= 10 && endHandle >= 10) {
                    return new byte[]{
                            0x11, 0x06,
                            0x0A, 0x00, 0x10, 0x00, 0x0A, 0x18,
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE
                    };
                }
                if (startHandle <= 17 && endHandle >= 17) {
                    return new byte[]{
                            0x11, 0x06,
                            0x11, 0x00, 0x14, 0x00, 0x25, (byte) 0xFE
                    };
                }
            }
            return new byte[]{0x01, 0x10, (byte) (startHandle & 0xFF), (byte) ((startHandle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x08 && request.length >= 5) { // ATT_READ_BY_TYPE_REQ
            int startHandle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            int endHandle = (request[3] & 0xFF) | ((request[4] & 0xFF) << 8);
            int uuid = request.length >= 7
                    ? ((request[5] & 0xFF) | ((request[6] & 0xFF) << 8))
                    : 0;
            if (uuid == 0x2803) {
                if (startHandle <= 2 && endHandle >= 5) {
                    return new byte[]{
                            0x09, 0x07,
                            0x02, 0x00, 0x02, 0x03, 0x00, 0x00, 0x2A,
                            0x04, 0x00, 0x02, 0x05, 0x00, 0x01, 0x2A
                    };
                }
                if (startHandle <= 7 && endHandle >= 8) {
                    return new byte[]{
                            0x09, 0x07,
                            0x07, 0x00, 0x20, 0x08, 0x00, 0x05, 0x2A
                    };
                }
                if (startHandle <= 11 && endHandle >= 16) {
                    return new byte[]{
                            0x09, 0x07,
                            0x0B, 0x00, 0x02, 0x0C, 0x00, 0x29, 0x2A,
                            0x0D, 0x00, 0x02, 0x0E, 0x00, 0x24, 0x2A,
                            0x0F, 0x00, 0x02, 0x10, 0x00, 0x26, 0x2A
                    };
                }
                if (startHandle <= 18 && endHandle >= 20) {
                    return new byte[]{
                            0x09, 0x07,
                            0x12, 0x00, 0x12, 0x13, 0x00, 0x25, (byte) 0xFE
                    };
                }
            } else if (uuid == 0x2A00 && startHandle <= 3 && endHandle >= 3) {
                return new byte[]{0x09, 0x08, 0x03, 0x00, 'i', 'P', 'h', 'o', 'n', 'e'};
            } else if (uuid == 0x2A01 && startHandle <= 5 && endHandle >= 5) {
                return new byte[]{0x09, 0x04, 0x05, 0x00, 0x40, 0x00};
            } else if (uuid == 0x2A29 && startHandle <= 12 && endHandle >= 12) {
                return new byte[]{0x09, 0x0C, 0x0C, 0x00, 'A', 'p', 'p', 'l', 'e', ' ', 'I', 'n', 'c', '.'};
            } else if (uuid == 0x2A24 && startHandle <= 14 && endHandle >= 14) {
                return new byte[]{0x09, 0x0C, 0x0E, 0x00, 'i', 'P', 'h', 'o', 'n', 'e', '1', '6', ',', '2'};
            } else if (uuid == 0x2A26 && startHandle <= 16 && endHandle >= 16) {
                return new byte[]{0x09, 0x06, 0x10, 0x00, '2', '1', '.', '6'};
            } else if (uuid == 0xFE25 && startHandle <= 19 && endHandle >= 19) {
                return new byte[]{0x09, 0x07, 0x13, 0x00, 0x20, 0x00, 0x00, 0x00, 0x00};
            }
            return new byte[]{0x01, 0x08, (byte) (startHandle & 0xFF), (byte) ((startHandle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x0A && request.length >= 3) { // ATT_READ_REQ
            int handle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            if (handle == 3) {
                return new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e'};
            }
            if (handle == 5) {
                return new byte[]{0x0B, 0x40, 0x00};
            }
            if (handle == 12) {
                return new byte[]{0x0B, 'A', 'p', 'p', 'l', 'e', ' ', 'I', 'n', 'c', '.'};
            }
            if (handle == 14) {
                return new byte[]{0x0B, 'i', 'P', 'h', 'o', 'n', 'e', '1', '6', ',', '2'};
            }
            if (handle == 16) {
                return new byte[]{0x0B, '2', '1', '.', '6'};
            }
            if (handle == 19) {
                return new byte[]{0x0B, 0x20, 0x00, 0x00, 0x00, 0x00};
            }
            return new byte[]{0x01, 0x0A, (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x04 && request.length >= 5) { // ATT_FIND_INFO_REQ
            int startHandle = (request[1] & 0xFF) | ((request[2] & 0xFF) << 8);
            int endHandle = (request[3] & 0xFF) | ((request[4] & 0xFF) << 8);
            if (startHandle <= 9 && endHandle >= 9) {
                return new byte[]{0x05, 0x01, 0x09, 0x00, 0x02, 0x29};
            }
            if (startHandle <= 20 && endHandle >= 20) {
                return new byte[]{0x05, 0x01, 0x14, 0x00, 0x02, 0x29};
            }
            return new byte[]{0x01, 0x04, (byte) (startHandle & 0xFF), (byte) ((startHandle >> 8) & 0xFF), 0x0A};
        }
        if (opcode == 0x1B) { // ATT_HANDLE_VALUE_NTF
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            byte[] val = request.length > 3 ? Arrays.copyOfRange(request, 3, request.length) : new byte[0];
            log("ATT NOTIFICATION from Watch: handle=" + handle + " bytes=" + val.length);
            return null;
        }
        if (opcode == 0x1D) { // ATT_HANDLE_VALUE_IND
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            byte[] val = request.length > 3 ? Arrays.copyOfRange(request, 3, request.length) : new byte[0];
            log("ATT INDICATION from Watch: handle=" + handle + " bytes=" + val.length);
            return new byte[]{0x1E}; // ATT_HANDLE_VALUE_CFM
        }
        if (opcode == 0x12) { // ATT_WRITE_REQ
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            log("ATT WRITE REQ from Watch: handle=" + handle + " bytes=" + request.length);
            return new byte[]{0x13};
        }
        if (opcode == 0x52) { // ATT_WRITE_CMD
            int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 0;
            log("ATT WRITE CMD from Watch: handle=" + handle + " bytes=" + request.length);
            return null;
        }
        // Ignore unsolicited ATT responses (0x01, 0x03, 0x05, 0x07, 0x09, 0x0B, 0x11, 0x13, 0x1E).
        if (opcode == 0x01 || opcode == 0x03 || opcode == 0x05 || opcode == 0x07
                || opcode == 0x09 || opcode == 0x0B || opcode == 0x11 || opcode == 0x13 || opcode == 0x1E) {
            return null;
        }
        int handle = request.length >= 3 ? ((request[1] & 0xFF) | ((request[2] & 0xFF) << 8)) : 1;
        return new byte[]{0x01, (byte) opcode, (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF), 0x0A};
    }
}
