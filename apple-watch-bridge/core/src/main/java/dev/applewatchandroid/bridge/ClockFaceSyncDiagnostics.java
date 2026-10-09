package dev.applewatchandroid.bridge;

import java.util.Map;

/**
 * Shape of a NanoTimeKit {@code clockface.sync} data frame.
 *
 * <p>Key names and value kinds are logged. String contents and numeric
 * values are not: a face description is the Watch's private customization.</p>
 */
final class ClockFaceSyncDiagnostics {
    private ClockFaceSyncDiagnostics() {
    }

    static String summarize(byte[] payload) {
        if (payload == null) {
            return "payload=null";
        }
        StringBuilder summary = new StringBuilder();
        summary.append("payloadBytes=").append(payload.length);
        if (payload.length >= 3 && payload[1] == 0) {
            int messageId = payload[0] & 0xff;
            if (messageId == ClockFaceSyncAccept.BATCH) {
                summary.append(" type=0x67 batch");
                return summary.toString();
            }
            if (messageId == ClockFaceSyncAccept.END) {
                summary.append(" type=0x69 end");
                return summary.toString();
            }
        }
        if (payload.length >= 4
                && (payload[0] & 0xff) == 0x66
                && payload[1] == 0
                && payload[2] == 0) {
            // Live 15:47: type 0x66, then a protobuf. Two UUID strings name
            // the sync, and an NSKeyedArchiver body carries
            // senderSyncVersion. Not the face archive itself.
            summary.append(" type=0x66 uuids=").append(countUuidTexts(payload));
            if (indexOfAscii(payload, "senderSyncVersion") >= 0) {
                summary.append(" key=senderSyncVersion");
            }
            int plist = indexOfAscii(payload, "bplist00");
            if (plist >= 0) {
                summary.append(" plistBytes=").append(payload.length - plist);
            }
            return summary.toString();
        }
        if (isBinaryPlist(payload)) {
            try {
                Map<String, Object> dictionary =
                        BinaryPropertyListCodec.decodeDictionary(payload);
                summary.append(" root=dict keys=").append(dictionary.size());
                for (Map.Entry<String, Object> entry : dictionary.entrySet()) {
                    summary.append(' ')
                            .append(entry.getKey())
                            .append('=')
                            .append(shape(entry.getValue()));
                }
            } catch (RuntimeException undecodable) {
                summary.append(" bplistUndecodable");
            }
            return summary.toString();
        }
        if (payload.length >= 4
                && payload[0] == 'P'
                && payload[1] == 'K'
                && payload[2] == 3
                && payload[3] == 4) {
            summary.append(" zip");
            return summary.toString();
        }
        summary.append(" magic=");
        int shown = Math.min(4, payload.length);
        for (int index = 0; index < shown; index++) {
            summary.append(String.format("%02x", payload[index] & 0xff));
        }
        return summary.toString();
    }

    private static int countUuidTexts(byte[] payload) {
        int count = 0;
        for (int index = 0; index + 36 <= payload.length; index++) {
            if (isUuidText(payload, index)) {
                count++;
                index += 35;
            }
        }
        return count;
    }

    private static boolean isUuidText(byte[] payload, int offset) {
        for (int index = 0; index < 36; index++) {
            int value = payload[offset + index] & 0xff;
            boolean dash = index == 8 || index == 13 || index == 18 || index == 23;
            if (dash) {
                if (value != '-') {
                    return false;
                }
            } else if (!((value >= '0' && value <= '9')
                    || (value >= 'A' && value <= 'F')
                    || (value >= 'a' && value <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfAscii(byte[] payload, String needle) {
        byte[] encoded = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int index = 0; index + encoded.length <= payload.length; index++) {
            for (int match = 0; match < encoded.length; match++) {
                if (payload[index + match] != encoded[match]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static boolean isBinaryPlist(byte[] payload) {
        return payload.length >= 8
                && payload[0] == 'b'
                && payload[1] == 'p'
                && payload[2] == 'l'
                && payload[3] == 'i'
                && payload[4] == 's'
                && payload[5] == 't'
                && payload[6] == '0'
                && payload[7] == '0';
    }

    private static String shape(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof String string) {
            return "string(" + string.length() + ")";
        }
        if (value instanceof byte[] bytes) {
            String kind = "data";
            if (isBinaryPlist(bytes)) {
                kind = "bplist";
            } else if (bytes.length >= 4
                    && bytes[0] == 'P'
                    && bytes[1] == 'K'
                    && bytes[2] == 3
                    && bytes[3] == 4) {
                kind = "zip";
            }
            return kind + "(" + bytes.length + ")";
        }
        if (value instanceof Map<?, ?> map) {
            return "dict(" + map.size() + ")";
        }
        if (value instanceof Iterable<?> iterable) {
            int count = 0;
            for (Object ignored : iterable) {
                count++;
            }
            return "list(" + count + ")";
        }
        return value.getClass().getSimpleName();
    }
}
