package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HciCodec {
    static final int OPCODE_DISCONNECT = 0x0406;
    static final int OPCODE_RESET = 0x0C03;
    static final int OPCODE_SET_EVENT_MASK = 0x0C01;
    static final int OPCODE_READ_BUFFER_SIZE = 0x1005;
    static final int OPCODE_READ_BD_ADDR = 0x1009;
    static final int OPCODE_LE_SET_EVENT_MASK = 0x2001;
    static final int OPCODE_LE_READ_BUFFER_SIZE = 0x2002;
    static final int OPCODE_LE_SET_RANDOM_ADDRESS = 0x2005;
    static final int OPCODE_LE_SET_ADVERTISING_PARAMETERS = 0x2006;
    static final int OPCODE_LE_SET_ADVERTISING_DATA = 0x2008;
    static final int OPCODE_LE_SET_SCAN_RESPONSE_DATA = 0x2009;
    static final int OPCODE_LE_SET_ADVERTISE_ENABLE = 0x200A;
    static final int OPCODE_LE_SET_SCAN_PARAMETERS = 0x200B;
    static final int OPCODE_LE_SET_SCAN_ENABLE = 0x200C;
    static final int OPCODE_LE_CREATE_CONNECTION = 0x200D;
    static final int OPCODE_LE_CREATE_CONNECTION_CANCEL = 0x200E;
    static final int OPCODE_LE_START_ENCRYPTION = 0x2019;

    static final int BT_CL_SIGNALING_CID = 0x003A;
    static final int BT_CL_CURRENT_VERSION = 0x0B;
    static final int BT_CL_REMOTE_SERVICES = 0x01;
    static final int BT_CL_COMMON_SERVICES = 0x02;
    static final int BT_CL_CREATE_CHANNEL = 0x03;
    static final int BT_CL_ACCEPT_CHANNEL = 0x04;
    static final int BT_CL_SERVICE_ADDED = 0x05;
    static final int BT_CL_SERVICE_REMOVED = 0x06;
    static final int BT_CL_REMOVE_CONFIRMATION = 0x07;
    static final int BT_CL_SIGNALING_ERROR = 0x08;
    static final int BT_CL_VERSION_INFO = 0x09;
    static final int BT_CL_TIME_SYNC_REQUEST = 0x70;
    static final int BT_CL_TIME_SYNC_INFO = 0x71;
    static final int BT_CL_TIME_SYNC_CORRECTION = 0x72;
    static final int BT_CL_SERVICE_FLAG_ERTM = 1;
    static final int BT_CL_SERVICE_FLAG_FCS = 1 << 1;
    static final int BT_CL_COMPANION_SERVICE_TYPE = 1;
    static final int TERMINUS_PAIRING_SERVICE_ID = 0x0001;
    static final int TERMINUS_LINK_SERVICE_ID = 0x0002;
    static final int TERMINUS_PAIRING_LOCAL_CID = 0x0040;
    static final int TERMINUS_LINK_LOCAL_CID = 0x0041;

    // Kept for the already proven pairing path.
    static final int TERMINUS_SERVICE_ID = 0x0001;
    static final int TERMINUS_LOCAL_CID = 0x0040;
    static final int WATCH_MIN_RSSI_DBM = -90;

    private static final int MAX_CONNECTION_HANDLE = 0x0EFF;
    private static final int PB_FIRST_NON_FLUSHABLE = 0;
    private static final int PB_CONTINUATION = 1;
    private static final int PB_FIRST_AUTOMATICALLY_FLUSHABLE = 2;
    private static final int WATCH_BT_CL_FEATURES = 0x00001521;
    private static final int WATCH_BT_CL_EXTENDED_FEATURES = 0x00000000;
    private static final int WATCH_BT_CL_EXTENDED_FEATURES_V8 = 0x00000001;
    private static final int BT_CL_MAX_SERVICES = 24;
    private static final int BT_CL_MAX_SERVICE_NAME_LENGTH = 32;
    private static final byte[] TERMINUS_PAIRING_NAME =
            "com.apple.terminusPairing\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TERMINUS_LINK_NAME =
            "com.apple.terminusLink\0".getBytes(StandardCharsets.UTF_8);

    private HciCodec() {
    }

    static byte[] buildCommand(int opcode, byte[] parameters) {
        if (opcode < 0 || opcode > 0xFFFF) {
            throw new IllegalArgumentException("HCI opcode is outside uint16");
        }
        if (parameters.length > 0xFF) {
            throw new IllegalArgumentException("HCI command parameters exceed uint8");
        }
        byte[] command = new byte[3 + parameters.length];
        putLe16(command, 0, opcode);
        command[2] = (byte) parameters.length;
        System.arraycopy(parameters, 0, command, 3, parameters.length);
        return command;
    }

    static byte[] classicEventMask() {
        return new byte[]{
                (byte) 0x90,
                (byte) 0x80,
                0x04,
                0x02,
                0,
                0,
                0,
                0x20
        };
    }

    static byte[] leEventMask() {
        return new byte[]{0x03, 0, 0, 0, 0, 0, 0, 0};
    }

    static byte[] passiveScanParameters() {
        return new byte[]{0x00, 0x60, 0x00, 0x60, 0x00, 0x00, 0x00};
    }

    static byte[] activeScanParameters(int ownAddressType) {
        if (ownAddressType != 0 && ownAddressType != 1) {
            throw new IllegalArgumentException("Active scan needs a configured public or random address");
        }
        return new byte[]{0x01, 0x60, 0x00, 0x60, 0x00, (byte) ownAddressType, 0x00};
    }

    static byte[] scanEnableParameters(boolean enabled) {
        return new byte[]{(byte) (enabled ? 1 : 0), 0x00};
    }

    static byte[] advertisingParameters() {
        return new byte[]{
                0x20, 0x00, // min interval (20ms)
                0x30, 0x00, // max interval (30ms)
                0x00,       // adv type: ADV_IND (connectable undirected)
                0x00,       // own addr type: public
                0x00,       // peer addr type: public
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // peer addr
                0x07,       // channel map: 37, 38, 39
                0x00        // filter policy: allow all
        };
    }

    static byte[] advertisingData(byte[] applePayload, String localName) {
        byte[] data = new byte[32];
        int cursor = 1;
        // Flags: 02 01 1A
        data[cursor++] = 0x02;
        data[cursor++] = 0x01;
        data[cursor++] = 0x1A;
        if (applePayload != null && applePayload.length > 0 && cursor + 1 + applePayload.length <= 31) {
            data[cursor++] = (byte) (applePayload.length);
            System.arraycopy(applePayload, 0, data, cursor, applePayload.length);
            cursor += applePayload.length;
        }
        if (localName != null && !localName.isEmpty()) {
            byte[] nameBytes = localName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (cursor + 2 + nameBytes.length <= 31) {
                data[cursor++] = (byte) (nameBytes.length + 1);
                data[cursor++] = 0x09; // Complete Local Name
                System.arraycopy(nameBytes, 0, data, cursor, nameBytes.length);
                cursor += nameBytes.length;
            }
        }
        data[0] = (byte) (cursor - 1);
        return data;
    }

    static byte[] advertiseEnableParameters(boolean enabled) {
        return new byte[]{(byte) (enabled ? 1 : 0)};
    }

    static CommandComplete parseCommandComplete(byte[] event) {
        if (!hasDeclaredEventLength(event, 4) || unsigned(event[0]) != 0x0E) {
            return null;
        }
        int opcode = le16(event, 3);
        int status = unsigned(event[5]);
        return new CommandComplete(
                opcode,
                status,
                Arrays.copyOfRange(event, 5, unsigned(event[1]) + 2));
    }

    static CommandStatus parseCommandStatus(byte[] event) {
        if (!hasDeclaredEventLength(event, 4) || unsigned(event[0]) != 0x0F) {
            return null;
        }
        return new CommandStatus(
                le16(event, 4),
                unsigned(event[2]),
                unsigned(event[3]));
    }

    static int parseAclDataPacketLength(CommandComplete complete) {
        if (complete == null
                || complete.opcode != OPCODE_READ_BUFFER_SIZE
                || complete.status != 0
                || complete.returnParameters.length < 3) {
            throw new IllegalArgumentException(
                    "Expected successful HCI Read Buffer Size response");
        }
        int length = le16(complete.returnParameters, 1);
        if (length < 4) {
            throw new IllegalArgumentException(
                    "Controller ACL buffer cannot hold an L2CAP header");
        }
        return length;
    }

    static int parseLeAclDataPacketLength(CommandComplete complete) {
        if (complete == null
                || complete.opcode != OPCODE_LE_READ_BUFFER_SIZE
                || complete.status != 0
                || complete.returnParameters.length < 4) {
            throw new IllegalArgumentException(
                    "Expected successful HCI LE Read Buffer Size response");
        }
        int length = le16(complete.returnParameters, 1);
        if (length != 0 && length < 4) {
            throw new IllegalArgumentException(
                    "Controller LE ACL buffer cannot hold an L2CAP header");
        }
        return length;
    }

    static int parseAclDataPacketCount(CommandComplete complete) {
        parseAclDataPacketLength(complete);
        if (complete.returnParameters.length != 8) {
            throw new IllegalArgumentException("Truncated HCI Read Buffer Size response");
        }
        int count = le16(complete.returnParameters, 4);
        if (count == 0) throw new IllegalArgumentException("Controller has no ACL buffers");
        return count;
    }

    static int parseLeAclDataPacketCount(CommandComplete complete) {
        int length = parseLeAclDataPacketLength(complete);
        if (complete.returnParameters.length != 4) {
            throw new IllegalArgumentException("Invalid HCI LE Read Buffer Size response");
        }
        int count = unsigned(complete.returnParameters[3]);
        if (length == 0 && count != 0) {
            throw new IllegalArgumentException("Controller LE buffers have zero length");
        }
        return count;
    }

    static byte[] parseBdAddr(CommandComplete complete) {
        if (complete == null
                || complete.opcode != OPCODE_READ_BD_ADDR
                || complete.status != 0
                || complete.returnParameters.length != 7) {
            throw new IllegalArgumentException(
                    "Expected successful HCI Read BD_ADDR response");
        }
        return Arrays.copyOfRange(
                complete.returnParameters,
                1,
                7);
    }

    static List<CompletedPackets> parseNumberOfCompletedPackets(byte[] event) {
        if (!hasDeclaredEventLength(event, 1)
                || unsigned(event[0]) != 0x13) {
            return List.of();
        }
        int count = unsigned(event[2]);
        if (unsigned(event[1]) != 1 + count * 4) {
            return List.of();
        }
        List<CompletedPackets> completed = new ArrayList<>(count);
        int offset = 3;
        for (int index = 0; index < count; index++) {
            completed.add(new CompletedPackets(
                    le16(event, offset) & 0x0FFF,
                    le16(event, offset + 2)));
            offset += 4;
        }
        return List.copyOf(completed);
    }

    static Integer parseHardwareError(byte[] event) {
        if (!hasDeclaredEventLength(event, 1)
                || unsigned(event[0]) != 0x10) {
            return null;
        }
        return unsigned(event[2]);
    }

    static Integer parseDataBufferOverflow(byte[] event) {
        if (!hasDeclaredEventLength(event, 1)
                || unsigned(event[0]) != 0x1A) {
            return null;
        }
        return unsigned(event[2]);
    }

    static List<AdvertisingReport> parseLegacyAdvertisingReports(byte[] event) {
        if (!hasDeclaredEventLength(event, 2)
                || unsigned(event[0]) != 0x3E
                || unsigned(event[2]) != 0x02) {
            return List.of();
        }
        int end = unsigned(event[1]) + 2;
        int count = unsigned(event[3]);
        int offset = 4;
        List<AdvertisingReport> reports = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (offset + 9 > end) {
                return List.of();
            }
            int eventType = unsigned(event[offset]);
            int addressType = unsigned(event[offset + 1]);
            byte[] address = Arrays.copyOfRange(event, offset + 2, offset + 8);
            int dataLength = unsigned(event[offset + 8]);
            int dataStart = offset + 9;
            if (dataStart + dataLength + 1 > end) {
                return List.of();
            }
            byte[] data = Arrays.copyOfRange(
                    event,
                    dataStart,
                    dataStart + dataLength);
            int rssi = event[dataStart + dataLength];
            reports.add(new AdvertisingReport(
                    eventType,
                    addressType,
                    address,
                    rssi,
                    data,
                    parseWatchSetupPayload(data)));
            offset = dataStart + dataLength + 1;
        }
        if (offset != end) {
            return List.of();
        }
        return reports;
    }

    static WatchSetupPayload parseWatchSetupPayload(byte[] advertisingData) {
        int offset = 0;
        while (offset < advertisingData.length) {
            int fieldLength = unsigned(advertisingData[offset]);
            if (fieldLength == 0) {
                return null;
            }
            int fieldEnd = offset + 1 + fieldLength;
            if (fieldLength < 1 || fieldEnd > advertisingData.length) {
                return null;
            }
            int type = unsigned(advertisingData[offset + 1]);
            int valueStart = offset + 2;
            if (type == 0x16 && fieldEnd - valueStart >= 3) {
                int serviceUuid = le16(advertisingData, valueStart);
                int cursor = valueStart + 2;
                int encodedSubtype = unsigned(advertisingData[cursor++]);
                int subtype = encodedSubtype & 0x7F;
                int payloadEnd = fieldEnd;
                if ((encodedSubtype & 0x80) != 0) {
                    if (cursor >= fieldEnd) {
                        return null;
                    }
                    int explicitLength = unsigned(advertisingData[cursor++]) & 0x1F;
                    payloadEnd = cursor + explicitLength;
                    if (payloadEnd > fieldEnd) {
                        return null;
                    }
                }
                if (serviceUuid == 0xFE25 && subtype == 0x06) {
                    byte[] setupData = Arrays.copyOfRange(
                            advertisingData,
                            cursor,
                            payloadEnd);
                    if (setupData.length < 5 || (unsigned(setupData[0]) & 0xE0) != 0x20) {
                        return null;
                    }
                    byte[] identifier =
                            Arrays.copyOfRange(
                                    setupData,
                                    1,
                                    5);
                    byte[] metadata =
                            Arrays.copyOfRange(
                                    setupData,
                                    5,
                                    setupData.length);
                    WatchSetupMetadataCodec.Identifier
                            decodedIdentifier =
                            WatchSetupMetadataCodec
                                    .decodeIdentifier(
                                            identifier);
                    WatchSetupMetadataCodec.ExtendedMetadata
                            decodedMetadata = null;
                    if (metadata.length
                            >= WatchSetupMetadataCodec
                                    .CURRENT_METADATA_LENGTH) {
                        decodedMetadata =
                                WatchSetupMetadataCodec
                                        .decodeExtendedMetadata(
                                                metadata);
                    }
                    return new WatchSetupPayload(
                            setupData,
                            (unsigned(setupData[0]) >>> 5) & 0x07,
                            identifier,
                            metadata,
                            decodedIdentifier,
                            decodedMetadata);
                }
            }
            offset = fieldEnd;
        }
        return null;
    }

    static byte[] buildLeSetRandomAddressParameters(
            byte[] randomAddress) {
        BluetoothLocalIdentity.requireStaticRandomAddress(
                randomAddress);
        return randomAddress.clone();
    }

    static byte[] buildLeCreateConnectionParameters(
            AdvertisingReport target,
            int ownAddressType) {
        return buildLeCreateConnectionParameters(
                target.address,
                target.addressType,
                ownAddressType);
    }

    static byte[] buildLeCreateConnectionParameters(
            byte[] peerAddress,
            int peerAddressType,
            int ownAddressType) {
        if (peerAddress == null || peerAddress.length != 6) {
            throw new IllegalArgumentException("LE address must contain six bytes");
        }
        if (peerAddressType != 0 && peerAddressType != 1) {
            throw new IllegalArgumentException("Only public/random legacy LE address is accepted");
        }
        if (ownAddressType != 0 && ownAddressType != 1) {
            throw new IllegalArgumentException(
                    "Own LE address type must be public or random");
        }
        byte[] parameters = new byte[25];
        putLe16(parameters, 0, 0x0060);
        putLe16(parameters, 2, 0x0060);
        parameters[4] = 0x00;
        parameters[5] = (byte) peerAddressType;
        System.arraycopy(peerAddress, 0, parameters, 6, 6);
        parameters[12] = (byte) ownAddressType;
        putLe16(parameters, 13, 0x0018);
        putLe16(parameters, 15, 0x0028);
        putLe16(parameters, 17, 0x0000);
        putLe16(parameters, 19, 0x01F4);
        putLe16(parameters, 21, 0x0000);
        putLe16(parameters, 23, 0x0000);
        return parameters;
    }

    static LeConnectionComplete parseLeConnectionComplete(byte[] event) {
        if (!hasDeclaredEventLength(event, 2)
                || unsigned(event[0]) != 0x3E
                || unsigned(event[2]) != 0x01) {
            return null;
        }
        int status = unsigned(event[3]);
        if (status != 0) {
            return new LeConnectionComplete(status, -1, -1, new byte[0]);
        }
        if (unsigned(event[1]) < 19 || event.length < 21) {
            return null;
        }
        return new LeConnectionComplete(
                status,
                le16(event, 4) & 0x0FFF,
                unsigned(event[7]),
                Arrays.copyOfRange(event, 8, 14));
    }

    static DisconnectionComplete parseDisconnectionComplete(byte[] event) {
        if (!hasDeclaredEventLength(event, 4) || unsigned(event[0]) != 0x05) {
            return null;
        }
        return new DisconnectionComplete(
                unsigned(event[2]),
                le16(event, 3) & 0x0FFF,
                unsigned(event[5]));
    }

    static byte[] buildDisconnectParameters(int connectionHandle) {
        validateConnectionHandle(connectionHandle);
        return new byte[]{
                (byte) connectionHandle,
                (byte) (connectionHandle >>> 8),
                0x13
        };
    }

    static byte[] buildLeStartEncryptionParameters(
            int connectionHandle,
            byte[] longTermKey) {
        validateConnectionHandle(connectionHandle);
        if (longTermKey == null
                || longTermKey.length != 16) {
            throw new IllegalArgumentException(
                    "LE Long Term Key must be exactly 16 bytes");
        }
        byte[] parameters = new byte[28];
        putLe16(
                parameters,
                0,
                connectionHandle);
        // Random Number and EDIV are all zero for LE Secure Connections.
        System.arraycopy(
                longTermKey,
                0,
                parameters,
                12,
                longTermKey.length);
        return parameters;
    }

    static EncryptionChange parseEncryptionChange(
            byte[] event) {
        if (event == null || event.length < 6) {
            return null;
        }
        int eventCode = unsigned(event[0]);
        if ((eventCode != 0x08 && eventCode != 0x59)
                || !hasDeclaredEventLength(event, 4)) {
            return null;
        }
        return new EncryptionChange(
                unsigned(event[2]),
                le16(event, 3) & 0x0fff,
                unsigned(event[5]),
                eventCode == 0x59 && event.length >= 7
                        ? unsigned(event[6])
                        : -1);
    }

    static EncryptionChange
            parseEncryptionKeyRefreshComplete(
            byte[] event) {
        if (!hasDeclaredEventLength(event, 3)
                || unsigned(event[0]) != 0x30) {
            return null;
        }
        return new EncryptionChange(
                unsigned(event[2]),
                le16(event, 3) & 0x0fff,
                1,
                -1);
    }

    static byte[] buildBtClVersionPdu() {
        byte[] payload = new byte[5];
        payload[0] = (byte) BT_CL_CURRENT_VERSION;
        putLe32(payload, 1, WATCH_BT_CL_FEATURES);
        return encodeBtCl(BT_CL_CURRENT_VERSION, BT_CL_VERSION_INFO, payload);
    }

    static byte[] buildRemoteServicesPdu(int peerVersion) {
        return buildRemoteServicesPdu(
                peerVersion,
                0,
                List.of(terminusPairingService()));
    }

    static byte[] buildRemoteServicesPdu(
            int peerVersion,
            List<BtClServiceRecord> services) {
        return buildRemoteServicesPdu(peerVersion, 0, services);
    }

    static byte[] buildRemoteServicesPdu(
            int peerVersion,
            long peerFeatures,
            List<BtClServiceRecord> services) {
        if (services == null
                || services.isEmpty()
                || services.size() > BT_CL_MAX_SERVICES) {
            throw new IllegalArgumentException(
                    "BT_CL service list must contain 1..24 records");
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(services.size());
        for (BtClServiceRecord service : services) {
            requireBtClService(service);
            payload.write(service.name.length + 5);
            writeBtClServiceBody(payload, service);
        }
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                BT_CL_REMOTE_SERVICES,
                payload.toByteArray());
    }

    static byte[] buildServiceAddedPdu(
            int peerVersion,
            BtClServiceRecord service) {
        return buildServiceAddedPdu(peerVersion, 0L, service);
    }

    static byte[] buildServiceAddedPdu(
            int peerVersion,
            long peerFeatures,
            BtClServiceRecord service) {
        requireBtClService(service);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(
                service.name.length + 5);
        writeBtClServiceBody(payload, service);
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                BT_CL_SERVICE_ADDED,
                payload.toByteArray());
    }

    static BtClServiceRecord parseServiceAdded(BtClPdu pdu) {
        if (pdu == null || pdu.opcode != BT_CL_SERVICE_ADDED) {
            throw new IllegalArgumentException(
                    "Expected BT_CL SERVICE_ADDED");
        }
        return parseBtClServiceBody(
                pdu.payload,
                0,
                pdu.payload.length,
                true);
    }

    static List<BtClServiceRecord> parseRemoteServices(
            BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != BT_CL_REMOTE_SERVICES
                || pdu.payload.length < 1) {
            throw new IllegalArgumentException(
                    "Expected BT_CL REMOTE_SERVICES");
        }
        int count = unsigned(pdu.payload[0]);
        if (count > BT_CL_MAX_SERVICES) {
            throw new IllegalArgumentException(
                    "BT_CL service count exceeds 24");
        }
        List<BtClServiceRecord> services =
                new ArrayList<>(count);
        int cursor = 1;
        for (int index = 0; index < count; index++) {
            if (cursor >= pdu.payload.length) {
                throw new IllegalArgumentException(
                        "BT_CL service record length is missing");
            }
            int recordLength = unsigned(pdu.payload[cursor++]);
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
            putLe16(
                    payload,
                    1 + index * 2,
                    serviceId);
        }
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                BT_CL_COMMON_SERVICES,
                payload);
    }

    static byte[] buildCreateChannelPdu(int peerVersion) {
        return buildCreateChannelPdu(
                peerVersion,
                0L,
                TERMINUS_PAIRING_LOCAL_CID,
                TERMINUS_PAIRING_SERVICE_ID);
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
        putLe16(payload, 0, requesterLocalCid);
        putLe16(payload, 2, serviceId);
        return encodeBtCl(peerVersion, peerFeatures, BT_CL_CREATE_CHANNEL, payload);
    }

    static CreateChannel parseCreateChannel(BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != BT_CL_CREATE_CHANNEL
                || pdu.payload.length != 4) {
            throw new IllegalArgumentException(
                    "Expected four-byte BT_CL CREATE_CHANNEL");
        }
        int requesterLocalCid = le16(pdu.payload, 0);
        requireDynamicCid(
                requesterLocalCid,
                "BT_CL requester local CID");
        return new CreateChannel(
                requesterLocalCid,
                le16(pdu.payload, 2));
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
        putLe16(payload, 1, serviceId);
        putLe16(payload, 3, responderLocalCid);
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                BT_CL_ACCEPT_CHANNEL,
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
        putLe16(payload, 0, serviceId);
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                BT_CL_SERVICE_REMOVED,
                payload);
    }

    static int parseServiceRemoved(BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != BT_CL_SERVICE_REMOVED
                || pdu.payload.length != 2) {
            throw new IllegalArgumentException(
                    "Expected two-byte BT_CL SERVICE_REMOVED");
        }
        return le16(pdu.payload, 0);
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
        putLe16(payload, 0, serviceId);
        payload[2] = (byte) status;
        return encodeBtCl(
                peerVersion,
                peerFeatures,
                BT_CL_REMOVE_CONFIRMATION,
                payload);
    }

    static RemoveConfirmation parseRemoveConfirmation(
            BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != BT_CL_REMOVE_CONFIRMATION
                || pdu.payload.length != 3) {
            throw new IllegalArgumentException(
                    "Expected three-byte BT_CL REMOVE_CONFIRMATION");
        }
        return new RemoveConfirmation(
                le16(pdu.payload, 0),
                pdu.payload[2] & 0xff);
    }

    static boolean isBtClShortLength(int peerVersion, long peerFeatures, int opcode) {
        if (opcode == 0x08 || opcode == BT_CL_VERSION_INFO) {
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
            putLe16(result, 1, payload.length);
            System.arraycopy(payload, 0, result, 3, payload.length);
        }
        return result;
    }

    static byte[] buildTimeSyncInformation(int peerVersion) {
        return buildTimeSyncInformation(peerVersion, 0L);
    }

    static byte[] buildTimeSyncInformation(int peerVersion, long peerFeatures) {
        long nowMillis = System.currentTimeMillis();
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        int offsetMinutes = tz.getOffset(nowMillis) / 60000;
        return buildTimeSyncInformation(peerVersion, peerFeatures,
                nowMillis, System.nanoTime(), offsetMinutes);
    }

    static byte[] buildTimeSyncInformation(int peerVersion, long peerFeatures,
            long unixMillis, long monotonicNanos, int offsetMinutes) {
        // watchOS 23S303 bluetoothd: 1000da818 converts gettimeofday to
        // nanoseconds; 1000bedc0 serializes t1/m1 as little-endian uint64s.
        byte[] payload = new byte[18];
        putLe64(payload, 0, Math.multiplyExact(unixMillis, 1_000_000L));
        putLe64(payload, 8, monotonicNanos);
        putLe16(payload, 16, offsetMinutes);
        return encodeBtCl(peerVersion, peerFeatures, BT_CL_TIME_SYNC_INFO, payload);
    }

    static byte[] buildTimeSyncCorrection(int peerVersion, long peerFeatures,
            long monotonicNanos) {
        // Native 1000c0ae0 follows 0x71 with m2 and mach_timebase_info.
        // Android's nanoTime already uses nanoseconds, so its timebase is 1/1.
        byte[] payload = new byte[16];
        putLe64(payload, 0, monotonicNanos);
        putLe32(payload, 8, 1);
        putLe32(payload, 12, 1);
        return encodeBtCl(peerVersion, peerFeatures, BT_CL_TIME_SYNC_CORRECTION, payload);
    }

    static BtClPdu parseBtCl(int peerVersion, byte[] bytes) {
        return parseBtCl(peerVersion, 0L, bytes);
    }

    static BtClPdu parseBtCl(int peerVersion, long peerFeatures, byte[] bytes) {
        if (bytes.length < 2) {
            throw new IllegalArgumentException("BT_CL PDU is truncated");
        }
        int opcode = unsigned(bytes[0]);
        boolean shortLength = isBtClShortLength(peerVersion, peerFeatures, opcode);
        int headerLength = shortLength ? 2 : 3;
        if (bytes.length < headerLength) {
            throw new IllegalArgumentException("BT_CL header is truncated");
        }
        int declared = shortLength ? unsigned(bytes[1]) : le16(bytes, 1);
        if (declared != bytes.length - headerLength) {
            if (!shortLength && bytes.length >= 2 && unsigned(bytes[1]) == bytes.length - 2) {
                return new BtClPdu(opcode, Arrays.copyOfRange(bytes, 2, bytes.length));
            }
            throw new IllegalArgumentException(
                    "BT_CL length mismatch: declared="
                            + declared
                            + " actual="
                            + (bytes.length - headerLength));
        }
        return new BtClPdu(
                opcode,
                Arrays.copyOfRange(bytes, headerLength, bytes.length));
    }

    static VersionInfo parseVersionInfo(BtClPdu pdu) {
        if (pdu.opcode != BT_CL_VERSION_INFO || pdu.payload.length != 5) {
            throw new IllegalArgumentException("Expected five-byte BT_CL VERSION payload");
        }
        return new VersionInfo(
                unsigned(pdu.payload[0]),
                le32(pdu.payload, 1));
    }

    static List<Integer> parseCommonServices(BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != BT_CL_COMMON_SERVICES
                || pdu.payload.length < 1) {
            throw new IllegalArgumentException("Expected BT_CL COMMON_SERVICES");
        }
        int count = unsigned(pdu.payload[0]);
        if (count > BT_CL_MAX_SERVICES) {
            throw new IllegalArgumentException(
                    "COMMON_SERVICES count exceeds 24");
        }
        if (pdu.payload.length != 1 + count * 2) {
            throw new IllegalArgumentException("COMMON_SERVICES length mismatch");
        }
        List<Integer> services = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            services.add(le16(pdu.payload, 1 + index * 2));
        }
        return services;
    }

    static AcceptChannel parseAcceptChannel(BtClPdu pdu) {
        if (pdu == null
                || pdu.opcode != BT_CL_ACCEPT_CHANNEL
                || pdu.payload.length != 5) {
            throw new IllegalArgumentException("Expected five-byte BT_CL ACCEPT_CHANNEL");
        }
        return new AcceptChannel(
                unsigned(pdu.payload[0]),
                le16(pdu.payload, 1),
                le16(pdu.payload, 3));
    }

    static BtClServiceRecord terminusPairingService() {
        return terminusPairingService(BT_CL_SERVICE_FLAG_ERTM);
    }

    static BtClServiceRecord terminusPairingService(int flags) {
        return new BtClServiceRecord(
                TERMINUS_PAIRING_SERVICE_ID,
                BT_CL_COMPANION_SERVICE_TYPE,
                TERMINUS_PAIRING_NAME,
                flags);
    }

    static BtClServiceRecord terminusLinkService() {
        return new BtClServiceRecord(
                TERMINUS_LINK_SERVICE_ID,
                BT_CL_COMPANION_SERVICE_TYPE,
                TERMINUS_LINK_NAME,
                BT_CL_SERVICE_FLAG_ERTM);
    }

    private static void writeBtClServiceBody(
            ByteArrayOutputStream output,
            BtClServiceRecord service) {
        writeLe16(output, service.serviceId);
        output.write(service.serviceType);
        output.write(service.name.length);
        output.writeBytes(service.name);
        output.write(service.flags);
    }

    private static BtClServiceRecord parseBtClServiceBody(
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
        int serviceId = le16(bytes, offset);
        int serviceType = unsigned(bytes[offset + 2]);
        int nameLength = unsigned(bytes[offset + 3]);
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
                : unsigned(bytes[nameEnd]);
        return new BtClServiceRecord(
                serviceId,
                serviceType,
                Arrays.copyOfRange(
                        bytes,
                        offset + 4,
                        nameEnd),
                flags);
    }

    private static void requireBtClService(
            BtClServiceRecord service) {
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

    static byte[] buildAclL2cap(
            int connectionHandle,
            int destinationCid,
            byte[] l2capPayload) {
        List<byte[]> fragments = buildAclL2capFragments(
                connectionHandle,
                destinationCid,
                l2capPayload,
                0xFFFF);
        if (fragments.size() != 1) {
            throw new IllegalStateException(
                    "Unbounded L2CAP builder unexpectedly fragmented");
        }
        return fragments.get(0);
    }

    static List<byte[]> buildAclL2capFragments(
            int connectionHandle,
            int destinationCid,
            byte[] l2capPayload,
            int maximumAclDataLength) {
        return buildAclL2capFragments(connectionHandle, destinationCid, l2capPayload,
                maximumAclDataLength, false);
    }

    static List<byte[]> buildLeAclL2capFragments(
            int connectionHandle,
            int destinationCid,
            byte[] l2capPayload,
            int maximumAclDataLength) {
        return buildAclL2capFragments(connectionHandle, destinationCid, l2capPayload,
                maximumAclDataLength, true);
    }

    private static List<byte[]> buildAclL2capFragments(
            int connectionHandle,
            int destinationCid,
            byte[] l2capPayload,
            int maximumAclDataLength,
            boolean lowEnergy) {
        validateConnectionHandle(connectionHandle);
        if (destinationCid < 0 || destinationCid > 0xFFFF) {
            throw new IllegalArgumentException("L2CAP CID is outside uint16");
        }
        if (l2capPayload.length > 0xFFFF - 4) {
            throw new IllegalArgumentException("L2CAP payload is too large");
        }
        if (maximumAclDataLength < 4 || maximumAclDataLength > 0xFFFF) {
            throw new IllegalArgumentException(
                    "Invalid maximum HCI ACL data length");
        }

        byte[] l2cap = new byte[4 + l2capPayload.length];
        putLe16(l2cap, 0, l2capPayload.length);
        putLe16(l2cap, 2, destinationCid);
        System.arraycopy(
                l2capPayload,
                0,
                l2cap,
                4,
                l2capPayload.length);

        List<byte[]> fragments = new ArrayList<>();
        int offset = 0;
        while (offset < l2cap.length) {
            int fragmentLength = Math.min(
                    maximumAclDataLength,
                    l2cap.length - offset);
            // LE-U permits only a non-flushable first fragment, including
            // fixed signaling/SMP CIDs (Core Vol 4, Part E, 5.4.2).
            // Dynamic CIDs carry ERTM. A flushable first fragment can be
            // discarded by the controller while the ACL buffer is full, so
            // the peer keeps RR at the same ReqSeq and the window never
            // moves (live 0.2.211: TxSeq 30 retransmitted for minutes,
            // RR still reqSeq=30, CanBegin held).
            int pb = offset != 0
                    ? PB_CONTINUATION
                    : (lowEnergy || destinationCid >= 0x0040
                    ? PB_FIRST_NON_FLUSHABLE
                    : PB_FIRST_AUTOMATICALLY_FLUSHABLE);
            byte[] acl = new byte[4 + fragmentLength];
            putLe16(
                    acl,
                    0,
                    connectionHandle | (pb << 12));
            putLe16(acl, 2, fragmentLength);
            System.arraycopy(
                    l2cap,
                    offset,
                    acl,
                    4,
                    fragmentLength);
            fragments.add(acl);
            offset += fragmentLength;
        }
        return List.copyOf(fragments);
    }

    static String formatLeAddress(byte[] hciOrderAddress) {
        if (hciOrderAddress.length != 6) {
            return "<invalid-address>";
        }
        return String.format(
                Locale.US,
                "%02X:%02X:%02X:%02X:%02X:%02X",
                unsigned(hciOrderAddress[5]),
                unsigned(hciOrderAddress[4]),
                unsigned(hciOrderAddress[3]),
                unsigned(hciOrderAddress[2]),
                unsigned(hciOrderAddress[1]),
                unsigned(hciOrderAddress[0]));
    }

    static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 3);
        for (int index = 0; index < bytes.length; index++) {
            if (index > 0) {
                result.append(' ');
            }
            result.append(String.format(Locale.US, "%02X", unsigned(bytes[index])));
        }
        return result.toString();
    }

    private static boolean hasDeclaredEventLength(byte[] event, int minimumParameters) {
        return event != null
                && event.length >= minimumParameters + 2
                && unsigned(event[1]) >= minimumParameters
                && unsigned(event[1]) + 2 == event.length;
    }

    private static void validateConnectionHandle(int connectionHandle) {
        if (connectionHandle < 0 || connectionHandle > MAX_CONNECTION_HANDLE) {
            throw new IllegalArgumentException("Invalid HCI connection handle");
        }
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static int le16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    private static long le32(byte[] bytes, int offset) {
        return (long) unsigned(bytes[offset])
                | ((long) unsigned(bytes[offset + 1]) << 8)
                | ((long) unsigned(bytes[offset + 2]) << 16)
                | ((long) unsigned(bytes[offset + 3]) << 24);
    }

    private static void putLe16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void putLe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLe64(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            bytes[offset + i] = (byte) (value >>> (i * 8));
        }
    }

    private static void writeLe16(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    static final class AclReassembler {
        private final Map<Integer, PartialL2cap> partials = new HashMap<>();

        L2capPdu accept(byte[] acl) {
            if (acl == null || acl.length < 4) {
                throw new IllegalArgumentException("HCI ACL packet is truncated");
            }
            int handleAndFlags = le16(acl, 0);
            int handle = handleAndFlags & 0x0FFF;
            int pb = (handleAndFlags >>> 12) & 0x03;
            int declaredAclLength = le16(acl, 2);
            if (declaredAclLength != acl.length - 4) {
                throw new IllegalArgumentException("HCI ACL length mismatch");
            }
            byte[] fragment = Arrays.copyOfRange(acl, 4, acl.length);
            if (pb == PB_FIRST_NON_FLUSHABLE
                    || pb == PB_FIRST_AUTOMATICALLY_FLUSHABLE) {
                if (fragment.length < 4) {
                    throw new IllegalArgumentException("First L2CAP fragment is truncated");
                }
                int expected = 4 + le16(fragment, 0);
                if (fragment.length > expected) {
                    throw new IllegalArgumentException("L2CAP fragment has trailing bytes");
                }
                PartialL2cap partial = new PartialL2cap(expected);
                partial.output.writeBytes(fragment);
                if (fragment.length == expected) {
                    return finish(handle, partial);
                }
                partials.put(handle, partial);
                return null;
            }
            if (pb != PB_CONTINUATION) {
                throw new IllegalArgumentException("Unsupported HCI ACL PB flag " + pb);
            }
            PartialL2cap partial = partials.get(handle);
            if (partial == null) {
                throw new IllegalArgumentException("Orphan HCI ACL continuation");
            }
            if (partial.output.size() + fragment.length > partial.expectedLength) {
                partials.remove(handle);
                throw new IllegalArgumentException("L2CAP continuation exceeds declared length");
            }
            partial.output.writeBytes(fragment);
            if (partial.output.size() == partial.expectedLength) {
                partials.remove(handle);
                return finish(handle, partial);
            }
            return null;
        }

        void reset() {
            partials.clear();
        }

        private static L2capPdu finish(int handle, PartialL2cap partial) {
            byte[] l2cap = partial.output.toByteArray();
            return new L2capPdu(
                    handle,
                    le16(l2cap, 2),
                    Arrays.copyOfRange(l2cap, 4, l2cap.length));
        }
    }

    private static final class PartialL2cap {
        final int expectedLength;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        PartialL2cap(int expectedLength) {
            this.expectedLength = expectedLength;
        }
    }

    static final class CommandComplete {
        final int opcode;
        final int status;
        final byte[] returnParameters;

        CommandComplete(int opcode, int status, byte[] returnParameters) {
            this.opcode = opcode;
            this.status = status;
            this.returnParameters = returnParameters;
        }
    }

    static final class CommandStatus {
        final int opcode;
        final int status;
        final int commandCredits;

        CommandStatus(int opcode, int status, int commandCredits) {
            this.opcode = opcode;
            this.status = status;
            this.commandCredits = commandCredits;
        }
    }

    static final class CompletedPackets {
        final int connectionHandle;
        final int completedPackets;

        CompletedPackets(int connectionHandle, int completedPackets) {
            this.connectionHandle = connectionHandle;
            this.completedPackets = completedPackets;
        }
    }

    static final class AdvertisingReport {
        final int eventType;
        final int addressType;
        final byte[] address;
        final int rssi;
        final byte[] data;
        final WatchSetupPayload setup;

        AdvertisingReport(
                int eventType,
                int addressType,
                byte[] address,
                int rssi,
                byte[] data,
                WatchSetupPayload setup) {
            this.eventType = eventType;
            this.addressType = addressType;
            this.address = address;
            this.rssi = rssi;
            this.data = data;
            this.setup = setup;
        }

        boolean isExpectedTarget() {
            return setup != null
                    && setup.watchSetupData != null && setup.watchSetupData.length == 12
                    && setup.headerVersion == 1
                    && WatchSetupMetadataCodec
                    .isUltra2NetworkRelayCandidate(
                            setup.decodedIdentifier,
                            setup.decodedMetadata,
                            NanoRegistryClassDCodec
                                    .IOS_26_6_ULTRA2_PHONE_MIN_VERSION,
                            NanoRegistryClassDCodec
                                    .IOS_26_6_PHONE_MAX_VERSION)
                    && eventType == 0
                    && (addressType == 0 || addressType == 1)
                    && rssi != 127
                    && rssi >= WATCH_MIN_RSSI_DBM;
        }

        boolean isAppleDevice() {
            if (data == null || data.length < 4) {
                return false;
            }
            for (int i = 0; i <= data.length - 4; ) {
                int len = data[i] & 0xFF;
                if (len == 0 || i + 1 + len > data.length) {
                    break;
                }
                int type = data[i + 1] & 0xFF;
                if (type == 0xFF && len >= 3) {
                    int companyId = (data[i + 2] & 0xFF) | ((data[i + 3] & 0xFF) << 8);
                    if (companyId == 0x004C) {
                        return true;
                    }
                }
                if (type == 0x16 && len >= 3) {
                    int uuid = (data[i + 2] & 0xFF) | ((data[i + 3] & 0xFF) << 8);
                    if (uuid == 0xFE25) {
                        return true;
                    }
                }
                i += 1 + len;
            }
            return false;
        }

        String addressText() {
            return formatLeAddress(address);
        }
    }

    static final class WatchSetupPayload {
        final byte[] watchSetupData;
        final int headerVersion;
        final byte[] candidateIdentifier;
        final byte[] metadata;
        final WatchSetupMetadataCodec.Identifier
                decodedIdentifier;
        final WatchSetupMetadataCodec.ExtendedMetadata
                decodedMetadata;

        WatchSetupPayload(
                byte[] watchSetupData,
                int headerVersion,
                byte[] candidateIdentifier,
                byte[] metadata,
                WatchSetupMetadataCodec.Identifier
                        decodedIdentifier,
                WatchSetupMetadataCodec.ExtendedMetadata
                        decodedMetadata) {
            this.watchSetupData = watchSetupData;
            this.headerVersion = headerVersion;
            this.candidateIdentifier = candidateIdentifier;
            this.metadata = metadata;
            this.decodedIdentifier =
                    decodedIdentifier;
            this.decodedMetadata =
                    decodedMetadata;
        }
    }

    static final class LeConnectionComplete {
        final int status;
        final int connectionHandle;
        final int peerAddressType;
        final byte[] peerAddress;

        LeConnectionComplete(
                int status,
                int connectionHandle,
                int peerAddressType,
                byte[] peerAddress) {
            this.status = status;
            this.connectionHandle = connectionHandle;
            this.peerAddressType = peerAddressType;
            this.peerAddress = peerAddress;
        }
    }

    static final class DisconnectionComplete {
        final int status;
        final int connectionHandle;
        final int reason;

        DisconnectionComplete(int status, int connectionHandle, int reason) {
            this.status = status;
            this.connectionHandle = connectionHandle;
            this.reason = reason;
        }
    }

    static final class EncryptionChange {
        final int status;
        final int connectionHandle;
        final int encryptionEnabled;
        final int keySize;

        EncryptionChange(
                int status,
                int connectionHandle,
                int encryptionEnabled,
                int keySize) {
            this.status = status;
            this.connectionHandle = connectionHandle;
            this.encryptionEnabled = encryptionEnabled;
            this.keySize = keySize;
        }
    }

    static final class L2capPdu {
        final int connectionHandle;
        final int destinationCid;
        final byte[] payload;

        L2capPdu(int connectionHandle, int destinationCid, byte[] payload) {
            this.connectionHandle = connectionHandle;
            this.destinationCid = destinationCid;
            this.payload = payload;
        }
    }

    static final class BtClPdu {
        final int opcode;
        final byte[] payload;

        BtClPdu(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    static final class VersionInfo {
        final int version;
        final long features;

        VersionInfo(int version, long features) {
            this.version = version;
            this.features = features;
        }
    }

    static final class BtClServiceRecord {
        final int serviceId;
        final int serviceType;
        private final byte[] name;
        final int flags;

        BtClServiceRecord(
                int serviceId,
                int serviceType,
                byte[] name,
                int flags) {
            this.serviceId = serviceId;
            this.serviceType = serviceType;
            this.name = name == null ? null : name.clone();
            this.flags = flags;
            requireBtClService(this);
        }

        byte[] name() {
            return name.clone();
        }

        boolean ertm() {
            return (flags & BT_CL_SERVICE_FLAG_ERTM) != 0;
        }

        boolean fcs() {
            return (flags & BT_CL_SERVICE_FLAG_FCS) != 0;
        }
    }

    static final class CreateChannel {
        final int requesterLocalCid;
        final int serviceId;

        CreateChannel(
                int requesterLocalCid,
                int serviceId) {
            this.requesterLocalCid = requesterLocalCid;
            this.serviceId = serviceId;
        }
    }

    static final class AcceptChannel {
        final int status;
        final int serviceId;
        final int responderLocalCid;

        AcceptChannel(int status, int serviceId, int responderLocalCid) {
            this.status = status;
            this.serviceId = serviceId;
            this.responderLocalCid = responderLocalCid;
        }
    }

    static final class RemoveConfirmation {
        final int serviceId;
        final int status;

        RemoveConfirmation(
                int serviceId,
                int status) {
            this.serviceId = serviceId;
            this.status = status;
        }
    }
}
