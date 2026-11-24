package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * Apple BT_CL signaling and time-sync PDU codec layered over fixed L2CAP
 * signaling CID {@code 0x003A}.
 */
final class BtClCodec {
    private static final int WATCH_BT_CL_FEATURES = 0x00001521;
    private static final int WATCH_BT_CL_EXTENDED_FEATURES = 0x00000000;
    private static final int WATCH_BT_CL_EXTENDED_FEATURES_V8 = 0x00000001;
    private static final int BT_CL_MAX_SERVICES = 24;
    private static final int BT_CL_MAX_SERVICE_NAME_LENGTH = 32;
    private static final byte[] TERMINUS_PAIRING_NAME =
            "com.apple.terminusPairing\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMINUS_LINK_NAME =
            "com.apple.terminusLink\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IDS_BT_LINK_NAME =
            "com.apple.ids\0".getBytes(StandardCharsets.UTF_8);

    private BtClCodec() {
    }

    static byte[] buildBtClVersionPdu() {
        byte[] payload = new byte[5];
        payload[0] = (byte) HciCodec.BT_CL_CURRENT_VERSION;
        HciCodec.putLe32(payload, 1, WATCH_BT_CL_FEATURES);
        return encodeBtCl(HciCodec.BT_CL_CURRENT_VERSION, HciCodec.BT_CL_VERSION_INFO, payload);
    }

    static byte[] buildRemoteServicesPdu(int peerVersion) {
        return buildRemoteServicesPdu(
                peerVersion,
                0,
                List.of(terminusPairingService()));
    }

    static byte[] buildRemoteServicesPdu(
            int peerVersion,
            List<HciCodec.BtClServiceRecord> services) {
        return buildRemoteServicesPdu(peerVersion, 0, services);
    }

    static byte[] buildRemoteServicesPdu(
            int peerVersion,
            long peerFeatures,
            List<HciCodec.BtClServiceRecord> services) {
        if (services == null
                || services.isEmpty()
                || services.size() > BT_CL_MAX_SERVICES) {
            throw new IllegalArgumentException(
                    "BT_CL service list must contain 1..24 records");
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(services.size());
        for (HciCodec.BtClServiceRecord service : services) {
            requireBtClService(service);
            payload.write(service.name.length + 5);
            writeBtClServiceBody(payload, service);
        }
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                HciCodec.BT_CL_REMOTE_SERVICES,
                payload.toByteArray());
    }

    static byte[] buildServiceAddedPdu(
            int peerVersion,
            HciCodec.BtClServiceRecord service) {
        return buildServiceAddedPdu(peerVersion, 0L, service);
    }

    static byte[] buildServiceAddedPdu(
            int peerVersion,
            long peerFeatures,
            HciCodec.BtClServiceRecord service) {
        requireBtClService(service);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(
                service.name.length + 5);
        writeBtClServiceBody(payload, service);
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                HciCodec.BT_CL_SERVICE_ADDED,
                payload.toByteArray());
    }

    static HciCodec.BtClServiceRecord parseServiceAdded(HciCodec.BtClPdu pdu) {
        if (pdu == null || pdu.opcode != HciCodec.BT_CL_SERVICE_ADDED) {
            throw new IllegalArgumentException(
                    "Expected BT_CL SERVICE_ADDED");
        }
        return parseBtClServiceBody(
                pdu.payload,
                0,
                pdu.payload.length,
                true);
    }

    static List<HciCodec.BtClServiceRecord> parseRemoteServices(
            HciCodec.BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != HciCodec.BT_CL_REMOTE_SERVICES
                || pdu.payload.length < 1) {
            throw new IllegalArgumentException(
                    "Expected BT_CL REMOTE_SERVICES");
        }
        int count = HciCodec.unsigned(pdu.payload[0]);
        if (count > BT_CL_MAX_SERVICES) {
            throw new IllegalArgumentException(
                    "BT_CL service count exceeds 24");
        }
        List<HciCodec.BtClServiceRecord> services =
                new ArrayList<>(count);
        int cursor = 1;
        for (int index = 0; index < count; index++) {
            if (cursor >= pdu.payload.length) {
                throw new IllegalArgumentException(
                        "BT_CL service record length is missing");
            }
            int recordLength = HciCodec.unsigned(pdu.payload[cursor++]);
            int recordEnd = cursor + recordLength;
            if (recordLength < 4
                    || recordEnd > pdu.payload.length) {
                throw new IllegalArgumentException(
                        "BT_CL service record exceeds its PDU");
            }
            services.add(parseBtClServiceBody(
                    pdu.payload,
                    cursor,
                    recordEnd,
                    false));
            cursor = recordEnd;
        }
        if (cursor != pdu.payload.length) {
            throw new IllegalArgumentException(
                    "BT_CL service list has trailing bytes");
        }
        return List.copyOf(services);
    }

    static byte[] buildCommonServicesPdu(
            int peerVersion,
            List<Integer> serviceIds) {
        return buildCommonServicesPdu(peerVersion, 0L, serviceIds);
    }

    static byte[] buildCommonServicesPdu(
            int peerVersion,
            long peerFeatures,
            List<Integer> serviceIds) {
        if (serviceIds == null
                || serviceIds.size() > BT_CL_MAX_SERVICES) {
            throw new IllegalArgumentException(
                    "BT_CL common-service count exceeds 24");
        }
        byte[] payload =
                new byte[1 + serviceIds.size() * 2];
        payload[0] = (byte) serviceIds.size();
        for (int index = 0;
                index < serviceIds.size();
                index++) {
            Integer serviceId = serviceIds.get(index);
            requireUnsigned16(
                    serviceId,
                    "BT_CL service ID");
            HciCodec.putLe16(
                    payload,
                    1 + index * 2,
                    serviceId);
        }
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                HciCodec.BT_CL_COMMON_SERVICES,
                payload);
    }

    static byte[] buildCreateChannelPdu(int peerVersion) {
        return buildCreateChannelPdu(
                peerVersion,
                0L,
                HciCodec.TERMINUS_PAIRING_LOCAL_CID,
                HciCodec.TERMINUS_PAIRING_SERVICE_ID);
    }

    static byte[] buildCreateChannelPdu(
            int peerVersion,
            int requesterLocalCid,
            int serviceId) {
        return buildCreateChannelPdu(peerVersion, 0L, requesterLocalCid, serviceId);
    }

    static byte[] buildCreateChannelPdu(
            int peerVersion,
            long peerFeatures,
            int requesterLocalCid,
            int serviceId) {
        requireDynamicCid(
                requesterLocalCid,
                "BT_CL requester local CID");
        requireUnsigned16(
                serviceId,
                "BT_CL service ID");
        byte[] payload = new byte[4];
        HciCodec.putLe16(payload, 0, requesterLocalCid);
        HciCodec.putLe16(payload, 2, serviceId);
        return encodeBtCl(peerVersion, peerFeatures, HciCodec.BT_CL_CREATE_CHANNEL, payload);
    }

    static HciCodec.CreateChannel parseCreateChannel(HciCodec.BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != HciCodec.BT_CL_CREATE_CHANNEL
                || pdu.payload.length != 4) {
            throw new IllegalArgumentException(
                    "Expected four-byte BT_CL CREATE_CHANNEL");
        }
        int requesterLocalCid = HciCodec.le16(pdu.payload, 0);
        requireDynamicCid(
                requesterLocalCid,
                "BT_CL requester local CID");
        return new HciCodec.CreateChannel(
                requesterLocalCid,
                HciCodec.le16(pdu.payload, 2));
    }

    static byte[] buildAcceptChannelPdu(
            int peerVersion,
            int status,
            int serviceId,
            int responderLocalCid) {
        return buildAcceptChannelPdu(peerVersion, 0L, status, serviceId, responderLocalCid);
    }

    static byte[] buildAcceptChannelPdu(
            int peerVersion,
            long peerFeatures,
            int status,
            int serviceId,
            int responderLocalCid) {
        if (status < 0 || status > 0xff) {
            throw new IllegalArgumentException(
                    "BT_CL channel status is outside uint8");
        }
        requireUnsigned16(
                serviceId,
                "BT_CL service ID");
        if (status == 0) {
            requireDynamicCid(
                    responderLocalCid,
                    "BT_CL responder local CID");
        } else {
            requireUnsigned16(
                    responderLocalCid,
                    "BT_CL responder local CID");
        }
        byte[] payload = new byte[5];
        payload[0] = (byte) status;
        HciCodec.putLe16(payload, 1, serviceId);
        HciCodec.putLe16(payload, 3, responderLocalCid);
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                HciCodec.BT_CL_ACCEPT_CHANNEL,
                payload);
    }

    static byte[] buildServiceRemovedPdu(
            int peerVersion,
            int serviceId) {
        return buildServiceRemovedPdu(peerVersion, 0L, serviceId);
    }

    static byte[] buildServiceRemovedPdu(
            int peerVersion,
            long peerFeatures,
            int serviceId) {
        requireUnsigned16(
                serviceId,
                "BT_CL service ID");
        byte[] payload = new byte[2];
        HciCodec.putLe16(payload, 0, serviceId);
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                HciCodec.BT_CL_SERVICE_REMOVED,
                payload);
    }

    static int parseServiceRemoved(HciCodec.BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != HciCodec.BT_CL_SERVICE_REMOVED
                || pdu.payload.length != 2) {
            throw new IllegalArgumentException(
                    "Expected two-byte BT_CL SERVICE_REMOVED");
        }
        return HciCodec.le16(pdu.payload, 0);
    }

    static byte[] buildRemoveConfirmationPdu(
            int peerVersion,
            int serviceId,
            int status) {
        return buildRemoveConfirmationPdu(peerVersion, 0L, serviceId, status);
    }

    static byte[] buildRemoveConfirmationPdu(
            int peerVersion,
            long peerFeatures,
            int serviceId,
            int status) {
        requireUnsigned16(
                serviceId,
                "BT_CL service ID");
        if (status < 0 || status > 0xff) {
            throw new IllegalArgumentException(
                    "BT_CL remove status is outside uint8");
        }
        byte[] payload = new byte[3];
        HciCodec.putLe16(payload, 0, serviceId);
        payload[2] = (byte) status;
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                HciCodec.BT_CL_REMOVE_CONFIRMATION,
                payload);
    }

    static HciCodec.RemoveConfirmation parseRemoveConfirmation(
            HciCodec.BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != HciCodec.BT_CL_REMOVE_CONFIRMATION
                || pdu.payload.length != 3) {
            throw new IllegalArgumentException(
                    "Expected three-byte BT_CL REMOVE_CONFIRMATION");
        }
        return new HciCodec.RemoveConfirmation(
                HciCodec.le16(pdu.payload, 0),
                pdu.payload[2] & 0xff);
    }

    static boolean isBtClShortLength(int peerVersion, long peerFeatures, int opcode) {
        if (opcode == 0x08 || opcode == HciCodec.BT_CL_VERSION_INFO) {
            return true;
        }
        return peerVersion < 8;
    }

    static byte[] encodeBtCl(int peerVersion, int opcode, byte[] payload) {
        return encodeBtCl(peerVersion, 0L, opcode, payload);
    }

    static byte[] encodeBtCl(int peerVersion, long peerFeatures, int opcode, byte[] payload) {
        boolean shortLength = isBtClShortLength(peerVersion, peerFeatures, opcode);
        int maximum = shortLength ? 0xFF : 0xFFFF;
        if (payload.length > maximum) {
            throw new IllegalArgumentException("BT_CL payload is too large");
        }
        byte[] result = new byte[(shortLength ? 2 : 3) + payload.length];
        result[0] = (byte) opcode;
        if (shortLength) {
            result[1] = (byte) payload.length;
            System.arraycopy(payload, 0, result, 2, payload.length);
        } else {
            HciCodec.putLe16(result, 1, payload.length);
            System.arraycopy(payload, 0, result, 3, payload.length);
        }
        return result;
    }

    static byte[] buildTimeSyncInformation(int peerVersion) {
        return buildTimeSyncInformation(peerVersion, 0L);
    }

    static byte[] buildTimeSyncInformation(int peerVersion, long peerFeatures) {
        long nowMillis = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        int offsetMinutes = tz.getOffset(nowMillis) / 60000;
        return buildTimeSyncInformation(peerVersion, peerFeatures,
                nowMillis, System.nanoTime(), offsetMinutes);
    }

    static byte[] buildTimeSyncInformation(int peerVersion, long peerFeatures,
            long unixMillis, long monotonicNanos, int offsetMinutes) {
        // watchOS 23S303 bluetoothd: 1000da818 converts gettimeofday to
        // nanoseconds; 1000bedc0 serializes t1/m1 as little-endian uint64s.
        byte[] payload = new byte[18];
        HciCodec.putLe64(payload, 0, Math.multiplyExact(unixMillis, 1_000_000L));
        HciCodec.putLe64(payload, 8, monotonicNanos);
        HciCodec.putLe16(payload, 16, offsetMinutes);
        return encodeBtCl(peerVersion, peerFeatures, HciCodec.BT_CL_TIME_SYNC_INFO, payload);
    }

    static byte[] buildTimeSyncCorrection(int peerVersion, long peerFeatures,
            long monotonicNanos) {
        // Native 1000c0ae0 follows 0x71 with m2 and mach_timebase_info.
        // Android's nanoTime already uses nanoseconds, so its timebase is 1/1.
        byte[] payload = new byte[16];
        HciCodec.putLe64(payload, 0, monotonicNanos);
        HciCodec.putLe32(payload, 8, 1);
        HciCodec.putLe32(payload, 12, 1);
        return encodeBtCl(peerVersion, peerFeatures, HciCodec.BT_CL_TIME_SYNC_CORRECTION, payload);
    }

    static HciCodec.BtClPdu parseBtCl(int peerVersion, byte[] bytes) {
        return parseBtCl(peerVersion, 0L, bytes);
    }

    static HciCodec.BtClPdu parseBtCl(int peerVersion, long peerFeatures, byte[] bytes) {
        if (bytes.length < 2) {
            throw new IllegalArgumentException("BT_CL PDU is truncated");
        }
        int opcode = HciCodec.unsigned(bytes[0]);
        boolean shortLength = isBtClShortLength(peerVersion, peerFeatures, opcode);
        int headerLength = shortLength ? 2 : 3;
        if (bytes.length < headerLength) {
            throw new IllegalArgumentException("BT_CL header is truncated");
        }
        int declared = shortLength ? HciCodec.unsigned(bytes[1]) : HciCodec.le16(bytes, 1);
        if (declared != bytes.length - headerLength) {
            if (!shortLength && bytes.length >= 2 && HciCodec.unsigned(bytes[1]) == bytes.length - 2) {
                return new HciCodec.BtClPdu(opcode, Arrays.copyOfRange(bytes, 2, bytes.length));
            }
            throw new IllegalArgumentException(
                    "BT_CL length mismatch: declared="
                            + declared
                            + " actual="
                            + (bytes.length - headerLength));
        }
        return new HciCodec.BtClPdu(
                opcode,
                Arrays.copyOfRange(bytes, headerLength, bytes.length));
    }

    static HciCodec.VersionInfo parseVersionInfo(HciCodec.BtClPdu pdu) {
        if (pdu.opcode != HciCodec.BT_CL_VERSION_INFO || pdu.payload.length != 5) {
            throw new IllegalArgumentException("Expected five-byte BT_CL VERSION payload");
        }
        return new HciCodec.VersionInfo(
                HciCodec.unsigned(pdu.payload[0]),
                HciCodec.le32(pdu.payload, 1));
    }

    static List<Integer> parseCommonServices(HciCodec.BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != HciCodec.BT_CL_COMMON_SERVICES
                || pdu.payload.length < 1) {
            throw new IllegalArgumentException("Expected BT_CL COMMON_SERVICES");
        }
        int count = HciCodec.unsigned(pdu.payload[0]);
        if (count > BT_CL_MAX_SERVICES) {
            throw new IllegalArgumentException(
                    "COMMON_SERVICES count exceeds 24");
        }
        if (pdu.payload.length != 1 + count * 2) {
            throw new IllegalArgumentException("COMMON_SERVICES length mismatch");
        }
        List<Integer> services = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            services.add(HciCodec.le16(pdu.payload, 1 + index * 2));
        }
        return services;
    }

    static HciCodec.AcceptChannel parseAcceptChannel(HciCodec.BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != HciCodec.BT_CL_ACCEPT_CHANNEL
                || pdu.payload.length != 5) {
            throw new IllegalArgumentException("Expected five-byte BT_CL ACCEPT_CHANNEL");
        }
        return new HciCodec.AcceptChannel(
                HciCodec.unsigned(pdu.payload[0]),
                HciCodec.le16(pdu.payload, 1),
                HciCodec.le16(pdu.payload, 3));
    }

    static HciCodec.BtClServiceRecord terminusPairingService() {
        return terminusPairingService(HciCodec.BT_CL_SERVICE_FLAG_ERTM);
    }

    static HciCodec.BtClServiceRecord terminusPairingService(int flags) {
        return new HciCodec.BtClServiceRecord(
                HciCodec.TERMINUS_PAIRING_SERVICE_ID,
                HciCodec.BT_CL_COMPANION_SERVICE_TYPE,
                TERMINUS_PAIRING_NAME,
                flags);
    }

    static HciCodec.BtClServiceRecord terminusLinkService() {
        return new HciCodec.BtClServiceRecord(
                HciCodec.TERMINUS_LINK_SERVICE_ID,
                HciCodec.BT_CL_COMPANION_SERVICE_TYPE,
                TERMINUS_LINK_NAME,
                HciCodec.BT_CL_SERVICE_FLAG_ERTM);
    }

    /** [EXPERIMENTAL LEGACY] Service record for com.apple.ids (IDSBTLink, service ID 0x0003). */
    static HciCodec.BtClServiceRecord idsBtLinkService() {
        return new HciCodec.BtClServiceRecord(
                HciCodec.IDS_BT_LINK_SERVICE_ID,
                HciCodec.BT_CL_COMPANION_SERVICE_TYPE,
                IDS_BT_LINK_NAME,
                HciCodec.BT_CL_SERVICE_FLAG_ERTM);
    }

    private static void writeBtClServiceBody(
            ByteArrayOutputStream output,
            HciCodec.BtClServiceRecord service) {
        HciCodec.writeLe16(output, service.serviceId);
        output.write(service.serviceType);
        output.write(service.name.length);
        output.writeBytes(service.name);
        output.write(service.flags);
    }

    private static HciCodec.BtClServiceRecord parseBtClServiceBody(
            byte[] bytes,
            int offset,
            int end,
            boolean allowAppleTrailingExtensions) {
        if (bytes == null
                || offset < 0
                || end < offset
                || end > bytes.length
                || end - offset < 4) {
            throw new IllegalArgumentException(
                    "BT_CL service body is truncated");
        }
        int serviceId = HciCodec.le16(bytes, offset);
        int serviceType = HciCodec.unsigned(bytes[offset + 2]);
        int nameLength = HciCodec.unsigned(bytes[offset + 3]);
        if (nameLength == 0
                || nameLength > BT_CL_MAX_SERVICE_NAME_LENGTH
                || end - offset < 4 + nameLength) {
            throw new IllegalArgumentException(
                    "BT_CL service name length is invalid");
        }
        int nameEnd = offset + 4 + nameLength;
        int remaining = end - nameEnd;
        if (!allowAppleTrailingExtensions && remaining > 1) {
            throw new IllegalArgumentException(
                    "BT_CL service record has trailing bytes");
        }
        int flags = remaining == 0
                ? 0
                : HciCodec.unsigned(bytes[nameEnd]);
        return new HciCodec.BtClServiceRecord(
                serviceId,
                serviceType,
                Arrays.copyOfRange(
                        bytes,
                        offset + 4,
                        nameEnd),
                flags);
    }

    static void requireBtClService(
            HciCodec.BtClServiceRecord service) {
        if (service == null) {
            throw new IllegalArgumentException(
                    "BT_CL service is null");
        }
        requireUnsigned16(
                service.serviceId,
                "BT_CL service ID");
        if (service.serviceType < 0
                || service.serviceType > 0xff) {
            throw new IllegalArgumentException(
                    "BT_CL service type is outside uint8");
        }
        if (service.name == null
                || service.name.length == 0
                || service.name.length
                > BT_CL_MAX_SERVICE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "BT_CL service name must contain 1..32 bytes");
        }
        if (service.flags < 0
                || service.flags > 0xff) {
            throw new IllegalArgumentException(
                    "BT_CL service flags are outside uint8");
        }
    }

    private static void requireUnsigned16(
            Integer value,
            String label) {
        if (value == null || value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(
                    label + " is outside uint16");
        }
    }

    private static void requireDynamicCid(
            int cid,
            String label) {
        if (cid < 0x0040 || cid > 0xffff) {
            throw new IllegalArgumentException(
                    label + " is not a dynamic L2CAP CID");
        }
    }
}
