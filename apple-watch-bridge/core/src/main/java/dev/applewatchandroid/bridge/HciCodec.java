package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
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
    static final int OPCODE_LE_READ_LOCAL_SUPPORTED_FEATURES = 0x2003;
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
    static final int OPCODE_LE_SET_DATA_LENGTH = 0x2022;
    static final int OPCODE_LE_WRITE_SUGGESTED_DEFAULT_DATA_LENGTH = 0x2024;
    static final int OPCODE_LE_READ_MAXIMUM_DATA_LENGTH = 0x202F;
    static final int OPCODE_LE_SET_DEFAULT_PHY = 0x2031;
    static final int OPCODE_LE_SET_PHY = 0x2032;

    static final int LE_PHY_1M_BIT = 0x01;
    static final int LE_PHY_2M_BIT = 0x02;
    static final int LE_MIN_DATA_OCTETS = 27;
    static final int LE_MAX_DATA_OCTETS = 251;
    static final int LE_MIN_DATA_TIME_US = 328;
    static final int LE_MAX_UNCODED_DATA_TIME_US = 2120;
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
    /** [EXPERIMENTAL LEGACY] BT_CL service ID for IDSBTLink (com.apple.ids). */
    static final int IDS_BT_LINK_SERVICE_ID = 0x0003;
    static final int TERMINUS_PAIRING_LOCAL_CID = 0x0040;
    static final int TERMINUS_LINK_LOCAL_CID = 0x0041;
    /** [EXPERIMENTAL LEGACY] Local L2CAP ERTM CID for com.apple.ids. */
    static final int IDS_BT_LINK_LOCAL_CID = 0x0042;

    // Kept for the already proven pairing path.
    static final int TERMINUS_SERVICE_ID = 0x0001;
    static final int TERMINUS_LOCAL_CID = 0x0040;
    static final int WATCH_MIN_RSSI_DBM = -90;

    private static final int MAX_CONNECTION_HANDLE = 0x0EFF;
    private static final int PB_FIRST_NON_FLUSHABLE = 0;
    private static final int PB_CONTINUATION = 1;
    private static final int PB_FIRST_AUTOMATICALLY_FLUSHABLE = 2;
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
        return HciLeCodec.leEventMask();
    }

    static byte[] leEventMask(boolean dataLengthChange, boolean phyUpdateComplete) {
        return HciLeCodec.leEventMask(dataLengthChange, phyUpdateComplete);
    }

    static byte[] passiveScanParameters() {
        return HciLeCodec.passiveScanParameters();
    }

    static byte[] activeScanParameters(int ownAddressType) {
        return HciLeCodec.activeScanParameters(ownAddressType);
    }

    static byte[] scanEnableParameters(boolean enabled) {
        return HciLeCodec.scanEnableParameters(enabled);
    }

    static byte[] advertisingParameters() {
        return HciLeCodec.advertisingParameters();
    }

    static byte[] advertisingData(byte[] applePayload, String localName) {
        return HciLeCodec.advertisingData(applePayload, localName);
    }

    static byte[] advertiseEnableParameters(boolean enabled) {
        return HciLeCodec.advertiseEnableParameters(enabled);
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
        return HciLeCodec.parseLegacyAdvertisingReports(event);
    }

    static WatchSetupPayload parseWatchSetupPayload(byte[] advertisingData) {
        return HciLeCodec.parseWatchSetupPayload(advertisingData);
    }

    static byte[] buildLeSetRandomAddressParameters(byte[] randomAddress) {
        return HciLeCodec.buildLeSetRandomAddressParameters(randomAddress);
    }

    static byte[] buildLeCreateConnectionParameters(AdvertisingReport target, int ownAddressType) {
        return HciLeCodec.buildLeCreateConnectionParameters(target, ownAddressType);
    }

    static byte[] buildLeCreateConnectionParameters(
            byte[] peerAddress,
            int peerAddressType,
            int ownAddressType) {
        return HciLeCodec.buildLeCreateConnectionParameters(
                peerAddress,
                peerAddressType,
                ownAddressType);
    }

    static LeConnectionComplete parseLeConnectionComplete(byte[] event) {
        return HciLeCodec.parseLeConnectionComplete(event);
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

    static byte[] buildLeStartEncryptionParameters(int connectionHandle, byte[] longTermKey) {
        return HciLeCodec.buildLeStartEncryptionParameters(connectionHandle, longTermKey);
    }

    static byte[] buildLeStartEncryptionParameters(
            int connectionHandle,
            byte[] randomNumber,
            int ediv,
            byte[] longTermKey) {
        return HciLeCodec.buildLeStartEncryptionParameters(
                connectionHandle,
                randomNumber,
                ediv,
                longTermKey);
    }

    static EncryptionChange parseEncryptionChange(byte[] event) {
        return HciLeCodec.parseEncryptionChange(event);
    }

    static EncryptionChange parseEncryptionKeyRefreshComplete(byte[] event) {
        return HciLeCodec.parseEncryptionKeyRefreshComplete(event);
    }

    static int recommendedUncodedTxTimeUs(int txOctets) {
        return HciLeCodec.recommendedUncodedTxTimeUs(txOctets);
    }

    static byte[] buildLeWriteSuggestedDefaultDataLengthParameters(int txOctets, int txTimeUs) {
        return HciLeCodec.buildLeWriteSuggestedDefaultDataLengthParameters(txOctets, txTimeUs);
    }

    static byte[] buildLeSetDataLengthParameters(int connectionHandle, int txOctets, int txTimeUs) {
        return HciLeCodec.buildLeSetDataLengthParameters(connectionHandle, txOctets, txTimeUs);
    }

    static byte[] buildLeSetDefaultPhyParameters(int allPhys, int txPhys, int rxPhys) {
        return HciLeCodec.buildLeSetDefaultPhyParameters(allPhys, txPhys, rxPhys);
    }

    static byte[] buildLeSetPhyParameters(
            int connectionHandle, int allPhys, int txPhys, int rxPhys, int phyOptions) {
        return HciLeCodec.buildLeSetPhyParameters(
                connectionHandle,
                allPhys,
                txPhys,
                rxPhys,
                phyOptions);
    }

    static LeLocalFeatures parseLeLocalSupportedFeatures(CommandComplete complete) {
        return HciLeCodec.parseLeLocalSupportedFeatures(complete);
    }

    static LeMaximumDataLength parseLeMaximumDataLength(CommandComplete complete) {
        return HciLeCodec.parseLeMaximumDataLength(complete);
    }

    static LeReadRemoteFeaturesComplete parseLeReadRemoteFeaturesComplete(byte[] event) {
        return HciLeCodec.parseLeReadRemoteFeaturesComplete(event);
    }

    static LeDataLengthChange parseLeDataLengthChange(byte[] event) {
        return HciLeCodec.parseLeDataLengthChange(event);
    }

    static LePhyUpdateComplete parseLePhyUpdateComplete(byte[] event) {
        return HciLeCodec.parseLePhyUpdateComplete(event);
    }

    static byte[] buildBtClVersionPdu() {
        return BtClCodec.buildBtClVersionPdu();
    }

    static byte[] buildRemoteServicesPdu(int peerVersion) {
        return BtClCodec.buildRemoteServicesPdu(peerVersion);
    }

    static byte[] buildRemoteServicesPdu(int peerVersion, List<BtClServiceRecord> services) {
        return BtClCodec.buildRemoteServicesPdu(peerVersion, services);
    }

    static byte[] buildRemoteServicesPdu(
            int peerVersion,
            long peerFeatures,
            List<BtClServiceRecord> services) {
        return BtClCodec.buildRemoteServicesPdu(peerVersion, peerFeatures, services);
    }

    static byte[] buildServiceAddedPdu(int peerVersion, BtClServiceRecord service) {
        return BtClCodec.buildServiceAddedPdu(peerVersion, service);
    }

    static byte[] buildServiceAddedPdu(
            int peerVersion,
            long peerFeatures,
            BtClServiceRecord service) {
        return BtClCodec.buildServiceAddedPdu(peerVersion, peerFeatures, service);
    }

    static BtClServiceRecord parseServiceAdded(BtClPdu pdu) {
        return BtClCodec.parseServiceAdded(pdu);
    }

    static List<BtClServiceRecord> parseRemoteServices(BtClPdu pdu) {
        return BtClCodec.parseRemoteServices(pdu);
    }

    static byte[] buildCommonServicesPdu(int peerVersion, List<Integer> serviceIds) {
        return BtClCodec.buildCommonServicesPdu(peerVersion, serviceIds);
    }

    static byte[] buildCommonServicesPdu(
            int peerVersion,
            long peerFeatures,
            List<Integer> serviceIds) {
        return BtClCodec.buildCommonServicesPdu(peerVersion, peerFeatures, serviceIds);
    }

    static byte[] buildCreateChannelPdu(int peerVersion) {
        return BtClCodec.buildCreateChannelPdu(peerVersion);
    }

    static byte[] buildCreateChannelPdu(
            int peerVersion,
            int requesterLocalCid,
            int serviceId) {
        return BtClCodec.buildCreateChannelPdu(peerVersion, requesterLocalCid, serviceId);
    }

    static byte[] buildCreateChannelPdu(
            int peerVersion,
            long peerFeatures,
            int requesterLocalCid,
            int serviceId) {
        return BtClCodec.buildCreateChannelPdu(
                peerVersion,
                peerFeatures,
                requesterLocalCid,
                serviceId);
    }

    static CreateChannel parseCreateChannel(BtClPdu pdu) {
        return BtClCodec.parseCreateChannel(pdu);
    }

    static byte[] buildAcceptChannelPdu(
            int peerVersion,
            int status,
            int serviceId,
            int responderLocalCid) {
        return BtClCodec.buildAcceptChannelPdu(peerVersion, status, serviceId, responderLocalCid);
    }

    static byte[] buildAcceptChannelPdu(
            int peerVersion,
            long peerFeatures,
            int status,
            int serviceId,
            int responderLocalCid) {
        return BtClCodec.buildAcceptChannelPdu(
                peerVersion,
                peerFeatures,
                status,
                serviceId,
                responderLocalCid);
    }

    static byte[] buildServiceRemovedPdu(int peerVersion, int serviceId) {
        return BtClCodec.buildServiceRemovedPdu(peerVersion, serviceId);
    }

    static byte[] buildServiceRemovedPdu(int peerVersion, long peerFeatures, int serviceId) {
        return BtClCodec.buildServiceRemovedPdu(peerVersion, peerFeatures, serviceId);
    }

    static int parseServiceRemoved(BtClPdu pdu) {
        return BtClCodec.parseServiceRemoved(pdu);
    }

    static byte[] buildRemoveConfirmationPdu(int peerVersion, int serviceId, int status) {
        return BtClCodec.buildRemoveConfirmationPdu(peerVersion, serviceId, status);
    }

    static byte[] buildRemoveConfirmationPdu(
            int peerVersion,
            long peerFeatures,
            int serviceId,
            int status) {
        return BtClCodec.buildRemoveConfirmationPdu(peerVersion, peerFeatures, serviceId, status);
    }

    static RemoveConfirmation parseRemoveConfirmation(BtClPdu pdu) {
        return BtClCodec.parseRemoveConfirmation(pdu);
    }

    static boolean isBtClShortLength(int peerVersion, long peerFeatures, int opcode) {
        return BtClCodec.isBtClShortLength(peerVersion, peerFeatures, opcode);
    }

    static byte[] encodeBtCl(int peerVersion, int opcode, byte[] payload) {
        return BtClCodec.encodeBtCl(peerVersion, opcode, payload);
    }

    static byte[] encodeBtCl(int peerVersion, long peerFeatures, int opcode, byte[] payload) {
        return BtClCodec.encodeBtCl(peerVersion, peerFeatures, opcode, payload);
    }

    static byte[] buildTimeSyncInformation(int peerVersion) {
        return BtClCodec.buildTimeSyncInformation(peerVersion);
    }

    static byte[] buildTimeSyncInformation(int peerVersion, long peerFeatures) {
        return BtClCodec.buildTimeSyncInformation(peerVersion, peerFeatures);
    }

    static byte[] buildTimeSyncInformation(
            int peerVersion,
            long peerFeatures,
            long unixMillis,
            long monotonicNanos,
            int offsetMinutes) {
        return BtClCodec.buildTimeSyncInformation(
                peerVersion,
                peerFeatures,
                unixMillis,
                monotonicNanos,
                offsetMinutes);
    }

    static byte[] buildTimeSyncCorrection(
            int peerVersion,
            long peerFeatures,
            long monotonicNanos) {
        return BtClCodec.buildTimeSyncCorrection(peerVersion, peerFeatures, monotonicNanos);
    }

    static BtClPdu parseBtCl(int peerVersion, byte[] bytes) {
        return BtClCodec.parseBtCl(peerVersion, bytes);
    }

    static BtClPdu parseBtCl(int peerVersion, long peerFeatures, byte[] bytes) {
        return BtClCodec.parseBtCl(peerVersion, peerFeatures, bytes);
    }

    static VersionInfo parseVersionInfo(BtClPdu pdu) {
        return BtClCodec.parseVersionInfo(pdu);
    }

    static List<Integer> parseCommonServices(BtClPdu pdu) {
        return BtClCodec.parseCommonServices(pdu);
    }

    static AcceptChannel parseAcceptChannel(BtClPdu pdu) {
        return BtClCodec.parseAcceptChannel(pdu);
    }

    static BtClServiceRecord terminusPairingService() {
        return BtClCodec.terminusPairingService();
    }

    static BtClServiceRecord terminusPairingService(int flags) {
        return BtClCodec.terminusPairingService(flags);
    }

    static BtClServiceRecord terminusLinkService() {
        return BtClCodec.terminusLinkService();
    }

    /** [EXPERIMENTAL LEGACY] Service record for com.apple.ids (IDSBTLink, service ID 0x0003). */
    static BtClServiceRecord idsBtLinkService() {
        return BtClCodec.idsBtLinkService();
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

    static boolean hasDeclaredEventLength(byte[] event, int minimumParameters) {
        return event != null
                && event.length >= minimumParameters + 2
                && unsigned(event[1]) >= minimumParameters
                && unsigned(event[1]) + 2 == event.length;
    }

    static void validateConnectionHandle(int connectionHandle) {
        if (connectionHandle < 0 || connectionHandle > MAX_CONNECTION_HANDLE) {
            throw new IllegalArgumentException("Invalid HCI connection handle");
        }
    }

    static int unsigned(byte value) {
        return value & 0xFF;
    }

    static int le16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    static long le32(byte[] bytes, int offset) {
        return (long) unsigned(bytes[offset])
                | ((long) unsigned(bytes[offset + 1]) << 8)
                | ((long) unsigned(bytes[offset + 2]) << 16)
                | ((long) unsigned(bytes[offset + 3]) << 24);
    }

    static void putLe16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    static void putLe32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    static void putLe64(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            bytes[offset + i] = (byte) (value >>> (i * 8));
        }
    }

    static void writeLe16(ByteArrayOutputStream output, int value) {
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
        final LegacyWatchPairingCodec.LegacyAdvertisement legacySetup;

        AdvertisingReport(
                int eventType,
                int addressType,
                byte[] address,
                int rssi,
                byte[] data,
                WatchSetupPayload setup) {
            this(
                    eventType,
                    addressType,
                    address,
                    rssi,
                    data,
                    setup,
                    LegacyWatchPairingCodec.parseAdvertisement(data, setup));
        }

        AdvertisingReport(
                int eventType,
                int addressType,
                byte[] address,
                int rssi,
                byte[] data,
                WatchSetupPayload setup,
                LegacyWatchPairingCodec.LegacyAdvertisement legacySetup) {
            this.eventType = eventType;
            this.addressType = addressType;
            this.address = address;
            this.rssi = rssi;
            this.data = data;
            this.setup = setup;
            this.legacySetup = legacySetup;
        }

        boolean isExpectedTarget() {
            return setup != null
                    && setup.watchSetupData != null && setup.watchSetupData.length == 12
                    && setup.headerVersion == 1
                    && WatchSetupMetadataCodec
                    .isNetworkRelayAdvertisement(
                            setup.decodedIdentifier,
                            setup.decodedMetadata)
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
        final byte[] name;
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
            BtClCodec.requireBtClService(this);
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

    static final class LeLocalFeatures {
        final boolean dataLengthExtension;
        final boolean phy2m;

        LeLocalFeatures(boolean dataLengthExtension, boolean phy2m) {
            this.dataLengthExtension = dataLengthExtension;
            this.phy2m = phy2m;
        }
    }

    static final class LeMaximumDataLength {
        final int maxTxOctets;
        final int maxTxTimeUs;
        final int maxRxOctets;
        final int maxRxTimeUs;

        LeMaximumDataLength(int maxTxOctets, int maxTxTimeUs, int maxRxOctets, int maxRxTimeUs) {
            this.maxTxOctets = maxTxOctets;
            this.maxTxTimeUs = maxTxTimeUs;
            this.maxRxOctets = maxRxOctets;
            this.maxRxTimeUs = maxRxTimeUs;
        }

        int suggestedTxOctets() {
            return Math.max(LE_MIN_DATA_OCTETS, Math.min(LE_MAX_DATA_OCTETS, maxTxOctets));
        }

        int suggestedTxTimeUs() {
            int octets = suggestedTxOctets();
            return Math.max(
                    LE_MIN_DATA_TIME_US,
                    Math.min(maxTxTimeUs, recommendedUncodedTxTimeUs(octets)));
        }
    }

    static final class LeReadRemoteFeaturesComplete {
        final int status;
        final int connectionHandle;
        final boolean dataLengthExtension;
        final boolean phy2m;

        LeReadRemoteFeaturesComplete(
                int status, int connectionHandle, boolean dataLengthExtension, boolean phy2m) {
            this.status = status;
            this.connectionHandle = connectionHandle;
            this.dataLengthExtension = dataLengthExtension;
            this.phy2m = phy2m;
        }
    }

    static final class LeDataLengthChange {
        final int connectionHandle;
        final int maxTxOctets;
        final int maxTxTimeUs;
        final int maxRxOctets;
        final int maxRxTimeUs;

        LeDataLengthChange(
                int connectionHandle,
                int maxTxOctets,
                int maxTxTimeUs,
                int maxRxOctets,
                int maxRxTimeUs) {
            this.connectionHandle = connectionHandle;
            this.maxTxOctets = maxTxOctets;
            this.maxTxTimeUs = maxTxTimeUs;
            this.maxRxOctets = maxRxOctets;
            this.maxRxTimeUs = maxRxTimeUs;
        }
    }

    static final class LePhyUpdateComplete {
        final int status;
        final int connectionHandle;
        final int txPhy;
        final int rxPhy;

        LePhyUpdateComplete(int status, int connectionHandle, int txPhy, int rxPhy) {
            this.status = status;
            this.connectionHandle = connectionHandle;
            this.txPhy = txPhy;
            this.rxPhy = rxPhy;
        }
    }
}
