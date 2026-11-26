package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Clean public codec facade exposing stateless HCI/L2CAP/ATT/BT_CL/ERTM/Prelude/IDSBTLink
 * primitives from {@code :core} to modular subpackages ({@code transport}, {@code strategy.*}).
 *
 * <p>Keeps low-level internal codecs package-private while providing strongly typed immutable
 * records and {@link AutoCloseable} wrappers for secret-bearing or stateful link helpers.</p>
 */
public final class BridgeCoreCodecs {
    public static final int ATT_FIXED_CID = LegacyWatchPairingCodec.ATT_FIXED_CID;
    public static final int LE_SIGNALING_CID = BluetoothLinkMaintenance.LE_SIGNALING_CID;
    public static final int SMP_FIXED_CID = BluetoothSmpCodec.FIXED_CID;
    public static final int BT_CL_SIGNALING_CID = HciCodec.BT_CL_SIGNALING_CID;
    public static final int BT_CL_CURRENT_VERSION = HciCodec.BT_CL_CURRENT_VERSION;

    public static final int BT_CL_REMOTE_SERVICES = HciCodec.BT_CL_REMOTE_SERVICES;
    public static final int BT_CL_COMMON_SERVICES = HciCodec.BT_CL_COMMON_SERVICES;
    public static final int BT_CL_CREATE_CHANNEL = HciCodec.BT_CL_CREATE_CHANNEL;
    public static final int BT_CL_ACCEPT_CHANNEL = HciCodec.BT_CL_ACCEPT_CHANNEL;
    public static final int BT_CL_SERVICE_ADDED = HciCodec.BT_CL_SERVICE_ADDED;
    public static final int BT_CL_SERVICE_REMOVED = HciCodec.BT_CL_SERVICE_REMOVED;
    public static final int BT_CL_REMOVE_CONFIRMATION = HciCodec.BT_CL_REMOVE_CONFIRMATION;
    public static final int BT_CL_SIGNALING_ERROR = HciCodec.BT_CL_SIGNALING_ERROR;
    public static final int BT_CL_VERSION_INFO = HciCodec.BT_CL_VERSION_INFO;
    public static final int BT_CL_TIME_SYNC_REQUEST = HciCodec.BT_CL_TIME_SYNC_REQUEST;
    public static final int BT_CL_TIME_SYNC_INFO = HciCodec.BT_CL_TIME_SYNC_INFO;
    public static final int BT_CL_TIME_SYNC_CORRECTION = HciCodec.BT_CL_TIME_SYNC_CORRECTION;

    public static final int TERMINUS_PAIRING_SERVICE_ID = HciCodec.TERMINUS_PAIRING_SERVICE_ID;
    public static final int TERMINUS_LINK_SERVICE_ID = HciCodec.TERMINUS_LINK_SERVICE_ID;
    public static final int IDS_BT_LINK_SERVICE_ID = HciCodec.IDS_BT_LINK_SERVICE_ID;
    public static final int TERMINUS_PAIRING_LOCAL_CID = HciCodec.TERMINUS_PAIRING_LOCAL_CID;
    public static final int TERMINUS_LINK_LOCAL_CID = HciCodec.TERMINUS_LINK_LOCAL_CID;
    public static final int IDS_BT_LINK_LOCAL_CID = HciCodec.IDS_BT_LINK_LOCAL_CID;

    public static final int ATT_OPCODE_ERROR_RSP = LegacyWatchPairingCodec.ATT_OPCODE_ERROR_RSP;
    public static final int ATT_OPCODE_EXCHANGE_MTU_RSP = LegacyWatchPairingCodec.ATT_OPCODE_EXCHANGE_MTU_RSP;
    public static final int ATT_OPCODE_FIND_BY_TYPE_VALUE_RSP = LegacyWatchPairingCodec.ATT_OPCODE_FIND_BY_TYPE_VALUE_RSP;
    public static final int ATT_OPCODE_READ_BY_TYPE_RSP = LegacyWatchPairingCodec.ATT_OPCODE_READ_BY_TYPE_RSP;
    public static final int ATT_OPCODE_READ_BY_GROUP_TYPE_RSP = LegacyWatchPairingCodec.ATT_OPCODE_READ_BY_GROUP_TYPE_RSP;
    public static final int ATT_OPCODE_WRITE_RSP = LegacyWatchPairingCodec.ATT_OPCODE_WRITE_RSP;
    public static final int ATT_ERR_REQUEST_NOT_SUPPORTED = LegacyWatchPairingCodec.ATT_ERR_REQUEST_NOT_SUPPORTED;
    public static final int ATT_ERR_ATTRIBUTE_NOT_FOUND = LegacyWatchPairingCodec.ATT_ERR_ATTRIBUTE_NOT_FOUND;
    public static final int ATT_ERR_UNSUPPORTED_GROUP_TYPE = LegacyWatchPairingCodec.ATT_ERR_UNSUPPORTED_GROUP_TYPE;

    public static final int LEGACY_STYLE_OOB = LegacyWatchPairingCodec.STYLE_OOB;
    public static final int LEGACY_STYLE_PASSCODE = LegacyWatchPairingCodec.STYLE_PASSCODE;

    public static final int PRELUDE_FLAG_COMPANION_APL = NrLinkBluetoothPrelude.FLAG_COMPANION_APL;
    public static final int PRELUDE_STATE_PAIR_WITH_OOB_KEY = NrLinkBluetoothPrelude.PairingState.PAIR_WITH_OOB_KEY.wireValue();
    public static final int PRELUDE_STATE_PAIR_WITH_IDS_KEYS = NrLinkBluetoothPrelude.PairingState.PAIR_WITH_IDS_KEYS.wireValue();
    public static final int PRELUDE_STATE_PAIR_WITH_OOB_KEY_OR_IDS_KEYS =
            NrLinkBluetoothPrelude.PairingState.PAIR_WITH_OOB_KEY_OR_IDS_KEYS.wireValue();
    public static final int PRELUDE_STATE_HAS_COMPLETED_PAIRING =
            NrLinkBluetoothPrelude.PairingState.HAS_COMPLETED_PAIRING.wireValue();

    private BridgeCoreCodecs() {
    }

    // ---- HCI / L2CAP ----

    public record L2capFrame(int connectionHandle, int destinationCid, byte[] payload) {}

    public record DisconnectEvent(int status, int connectionHandle, int reason) {}

    public static final class AclReassemblerHandle {
        private final HciCodec.AclReassembler delegate = new HciCodec.AclReassembler();

        public L2capFrame accept(byte[] aclPacket) {
            HciCodec.L2capPdu pdu = delegate.accept(aclPacket);
            return pdu == null ? null : new L2capFrame(pdu.connectionHandle, pdu.destinationCid, pdu.payload);
        }

        public void reset() {
            delegate.reset();
        }
    }

    public static List<byte[]> buildLeAclL2capFragments(int handle, int cid, byte[] payload, int maxAclLength) {
        return HciCodec.buildLeAclL2capFragments(handle, cid, payload, maxAclLength);
    }

    public static DisconnectEvent parseDisconnectionComplete(byte[] hciEvent) {
        HciCodec.DisconnectionComplete dc = HciCodec.parseDisconnectionComplete(hciEvent);
        return dc == null ? null : new DisconnectEvent(dc.status, dc.connectionHandle, dc.reason);
    }

    public static byte[] buildDisconnectCommand(int connectionHandle) {
        return HciCodec.buildCommand(HciCodec.OPCODE_DISCONNECT, HciCodec.buildDisconnectParameters(connectionHandle));
    }

    public static String toHex(byte[] bytes) {
        return HciCodec.toHex(bytes);
    }

    // ---- ATT Client ----

    public record AttHandleRange(int startHandle, int endHandle) {}

    public record AttPrimaryService(int startHandle, int endHandle, byte[] uuidBytes, boolean extensiblePairService) {}

    public record AttCharacteristic(
            int declarationHandle,
            int properties,
            int valueHandle,
            byte[] uuidBytes,
            boolean extensiblePairControl) {}

    public record AttErrorInfo(int requestOpcode, int attributeHandle, int errorCode) {}

    public static byte[] buildAttExchangeMtuRequest(int clientRxMtu) {
        return LegacyWatchPairingCodec.GattAttClient.buildExchangeMtuRequest(clientRxMtu);
    }

    public static int parseAttExchangeMtuResponse(byte[] pdu) {
        return LegacyWatchPairingCodec.GattAttClient.parseExchangeMtuResponse(pdu);
    }

    public static byte[] buildAttFindExtensiblePairServiceRequest(int startHandle) {
        return LegacyWatchPairingCodec.GattAttClient.buildFindExtensiblePairServiceRequest(startHandle);
    }

    public static List<AttHandleRange> parseAttFindByTypeValueResponse(byte[] pdu) {
        List<LegacyWatchPairingCodec.HandleRange> raw =
                LegacyWatchPairingCodec.GattAttClient.parseFindByTypeValueResponse(pdu);
        List<AttHandleRange> out = new ArrayList<>(raw.size());
        for (LegacyWatchPairingCodec.HandleRange r : raw) {
            out.add(new AttHandleRange(r.startHandle, r.endHandle));
        }
        return List.copyOf(out);
    }

    public static byte[] buildAttReadPrimaryServicesRequest(int startHandle, int endHandle) {
        return LegacyWatchPairingCodec.GattAttClient.buildReadPrimaryServicesRequest(startHandle, endHandle);
    }

    public static List<AttPrimaryService> parseAttReadPrimaryServicesResponse(byte[] pdu) {
        List<LegacyWatchPairingCodec.PrimaryService> raw =
                LegacyWatchPairingCodec.GattAttClient.parseReadPrimaryServicesResponse(pdu);
        List<AttPrimaryService> out = new ArrayList<>(raw.size());
        for (LegacyWatchPairingCodec.PrimaryService s : raw) {
            out.add(new AttPrimaryService(s.startHandle, s.endHandle, s.uuidBytes.clone(), s.isExtensiblePairService()));
        }
        return List.copyOf(out);
    }

    public static byte[] buildAttDiscoverCharacteristicsRequest(int startHandle, int endHandle) {
        return LegacyWatchPairingCodec.GattAttClient.buildDiscoverCharacteristicsRequest(startHandle, endHandle);
    }

    public static List<AttCharacteristic> parseAttDiscoverCharacteristicsResponse(byte[] pdu) {
        List<LegacyWatchPairingCodec.CharacteristicDeclaration> raw =
                LegacyWatchPairingCodec.GattAttClient.parseDiscoverCharacteristicsResponse(pdu);
        List<AttCharacteristic> out = new ArrayList<>(raw.size());
        for (LegacyWatchPairingCodec.CharacteristicDeclaration c : raw) {
            out.add(new AttCharacteristic(
                    c.declarationHandle, c.properties, c.valueHandle, c.uuidBytes.clone(),
                    c.isExtensiblePairControlCharacteristic()));
        }
        return List.copyOf(out);
    }

    public static byte[] buildAttWriteRequest(int valueHandle, byte[] value) {
        return LegacyWatchPairingCodec.GattAttClient.buildWriteRequest(valueHandle, value);
    }

    public static boolean isAttWriteResponse(byte[] pdu) {
        return LegacyWatchPairingCodec.GattAttClient.isWriteResponse(pdu);
    }

    public static AttErrorInfo parseAttErrorResponse(byte[] pdu) {
        LegacyWatchPairingCodec.AttError e = LegacyWatchPairingCodec.GattAttClient.parseErrorResponse(pdu);
        return new AttErrorInfo(e.requestOpcode, e.attributeHandle, e.errorCode);
    }

    public static byte[] encodeNanoControlPayload(int strategyWireId, int style) {
        return LegacyWatchPairingCodec.encodeControlPayload(strategyWireId, style);
    }

    // ---- BT_CL ----

    public record BtClFrame(int opcode, byte[] payload) {}

    public record BtClVersion(int version, long features) {}

    public record BtClService(int serviceId, int serviceType, byte[] name, int flags, boolean ertm, boolean fcs) {}

    public record BtClCreateChannel(int requesterLocalCid, int serviceId) {}

    public record BtClAcceptChannel(int status, int serviceId, int responderLocalCid) {}

    public static BtClService terminusLinkService() {
        return fromServiceRecord(HciCodec.terminusLinkService());
    }

    public static BtClService idsBtLinkService() {
        return fromServiceRecord(HciCodec.idsBtLinkService());
    }

    public static BtClService terminusPairingService() {
        return fromServiceRecord(HciCodec.terminusPairingService());
    }

    private static BtClService fromServiceRecord(HciCodec.BtClServiceRecord r) {
        return new BtClService(r.serviceId, r.serviceType, r.name(), r.flags, r.ertm(), r.fcs());
    }

    private static HciCodec.BtClServiceRecord toServiceRecord(BtClService s) {
        return new HciCodec.BtClServiceRecord(s.serviceId, s.serviceType, s.name, s.flags);
    }

    public static byte[] buildBtClVersionPdu() {
        return HciCodec.buildBtClVersionPdu();
    }

    public static BtClFrame parseBtCl(int peerVersion, long peerFeatures, byte[] l2capPayload) {
        HciCodec.BtClPdu pdu = HciCodec.parseBtCl(peerVersion, peerFeatures, l2capPayload);
        return new BtClFrame(pdu.opcode, pdu.payload);
    }

    public static BtClVersion parseBtClVersionInfo(BtClFrame frame) {
        HciCodec.VersionInfo v = HciCodec.parseVersionInfo(new HciCodec.BtClPdu(frame.opcode, frame.payload));
        return new BtClVersion(v.version, v.features);
    }

    public static byte[] buildBtClRemoteServicesPdu(int peerVersion, long peerFeatures, List<BtClService> services) {
        List<HciCodec.BtClServiceRecord> list = new ArrayList<>(services.size());
        for (BtClService s : services) {
            list.add(toServiceRecord(s));
        }
        return HciCodec.buildRemoteServicesPdu(peerVersion, peerFeatures, list);
    }

    public static List<BtClService> parseBtClRemoteServices(BtClFrame frame) {
        List<HciCodec.BtClServiceRecord> raw =
                HciCodec.parseRemoteServices(new HciCodec.BtClPdu(frame.opcode, frame.payload));
        List<BtClService> out = new ArrayList<>(raw.size());
        for (HciCodec.BtClServiceRecord r : raw) {
            out.add(fromServiceRecord(r));
        }
        return List.copyOf(out);
    }

    public static BtClService parseBtClServiceAdded(BtClFrame frame) {
        return fromServiceRecord(HciCodec.parseServiceAdded(new HciCodec.BtClPdu(frame.opcode, frame.payload)));
    }

    public static byte[] buildBtClServiceAddedPdu(int peerVersion, long peerFeatures, BtClService service) {
        return HciCodec.buildServiceAddedPdu(peerVersion, peerFeatures, toServiceRecord(service));
    }

    public static byte[] buildBtClCommonServicesPdu(int peerVersion, long peerFeatures, List<Integer> serviceIds) {
        return HciCodec.buildCommonServicesPdu(peerVersion, peerFeatures, serviceIds);
    }

    public static List<Integer> parseBtClCommonServices(BtClFrame frame) {
        return HciCodec.parseCommonServices(new HciCodec.BtClPdu(frame.opcode, frame.payload));
    }

    public static byte[] buildBtClCreateChannelPdu(int peerVersion, long peerFeatures, int localCid, int serviceId) {
        return HciCodec.buildCreateChannelPdu(peerVersion, peerFeatures, localCid, serviceId);
    }

    public static BtClCreateChannel parseBtClCreateChannel(BtClFrame frame) {
        HciCodec.CreateChannel cc = HciCodec.parseCreateChannel(new HciCodec.BtClPdu(frame.opcode, frame.payload));
        return new BtClCreateChannel(cc.requesterLocalCid, cc.serviceId);
    }

    public static byte[] buildBtClAcceptChannelPdu(
            int peerVersion, long peerFeatures, int status, int serviceId, int responderLocalCid) {
        return HciCodec.buildAcceptChannelPdu(peerVersion, peerFeatures, status, serviceId, responderLocalCid);
    }

    public static BtClAcceptChannel parseBtClAcceptChannel(BtClFrame frame) {
        HciCodec.AcceptChannel ac = HciCodec.parseAcceptChannel(new HciCodec.BtClPdu(frame.opcode, frame.payload));
        return new BtClAcceptChannel(ac.status, ac.serviceId, ac.responderLocalCid);
    }

    public static int parseBtClServiceRemoved(BtClFrame frame) {
        return HciCodec.parseServiceRemoved(new HciCodec.BtClPdu(frame.opcode, frame.payload));
    }

    public static byte[] buildBtClRemoveConfirmationPdu(int peerVersion, long peerFeatures, int serviceId, int status) {
        return HciCodec.buildRemoveConfirmationPdu(peerVersion, peerFeatures, serviceId, status);
    }

    public static byte[] buildBtClTimeSyncInfo(int peerVersion, long peerFeatures) {
        return HciCodec.buildTimeSyncInformation(peerVersion, peerFeatures);
    }

    public static byte[] buildBtClTimeSyncCorrection(int peerVersion, long peerFeatures, long monoNanos) {
        return HciCodec.buildTimeSyncCorrection(peerVersion, peerFeatures, monoNanos);
    }

    // ---- ERTM ----

    public static final class ErtmInbound implements AutoCloseable {
        private final L2capErtmSession.InboundResult delegate;

        private ErtmInbound(L2capErtmSession.InboundResult delegate) {
            this.delegate = delegate;
        }

        public List<byte[]> deliveredSdus() {
            return delegate.deliveredSdus();
        }

        public List<byte[]> immediateOutboundFrames() {
            return delegate.immediateOutboundFrames();
        }

        public boolean acknowledgementRecommended() {
            return delegate.acknowledgementRecommended;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    public static final class ErtmLink implements AutoCloseable {
        public static final int TARGET_MPS = L2capErtmSession.TARGET_MPS;
        private final L2capErtmSession session;

        public ErtmLink(int localCid, int remoteCid, boolean fcsEnabled) {
            this.session = new L2capErtmSession(localCid, remoteCid, fcsEnabled);
        }

        public byte[] sendInformation(byte[] sdu) {
            return session.sendInformation(sdu);
        }

        public ErtmInbound accept(byte[] l2capPayload) {
            return new ErtmInbound(session.accept(l2capPayload));
        }

        public byte[] buildAcknowledgementFrame() {
            return session.buildReceiverReady();
        }

        public byte[] buildReceiverReadyPoll() {
            return session.buildReceiverReadyPoll();
        }

        public boolean transmitBlocked() {
            return session.transmitBlocked();
        }

        @Override
        public void close() {
            session.close();
        }
    }

    public static byte[] encodeErtmInformationFrame(
            int destinationCid, int txSeq, int reqSeq, byte[] sdu, boolean fcsEnabled) {
        return L2capErtmCodec.encodeInformationFrame(destinationCid, txSeq, reqSeq, sdu, fcsEnabled);
    }

    public static byte[] encodeErtmReceiverReady(
            int destinationCid, int reqSeq, boolean finalBit, boolean fcsEnabled) {
        return L2capErtmCodec.encodeReceiverReady(destinationCid, reqSeq, finalBit, fcsEnabled);
    }

    // ---- NRLinkBluetooth Prelude ----

    public record PreludeParsed(int version, int stateWireValue, String stateName, int flags, byte[] uuid) {}

    public static byte[] encodePrelude(int stateWireValue, byte[] localUuid, int flags) {
        return NrLinkBluetoothPrelude.encode(
                NrLinkBluetoothPrelude.PairingState.fromWireValue(stateWireValue), localUuid, flags);
    }

    public static PreludeParsed parsePrelude(byte[] encoded) {
        NrLinkBluetoothPrelude.Parsed p = NrLinkBluetoothPrelude.parse(encoded);
        return new PreludeParsed(p.version, p.state.wireValue(), p.state.name(), p.flags, p.uuid());
    }

    public static int reconcilePreludeStates(int localStateWireValue, int remoteStateWireValue) {
        NrLinkBluetoothPrelude.PairingState reconciled = NrLinkBluetoothPrelude.reconcile(
                NrLinkBluetoothPrelude.PairingState.fromWireValue(localStateWireValue),
                NrLinkBluetoothPrelude.PairingState.fromWireValue(remoteStateWireValue));
        return reconciled.wireValue();
    }

    public static String electPreludeRole(byte[] localUuid, byte[] remoteUuid) {
        return NrLinkBluetoothPrelude.electLocalRole(localUuid, remoteUuid).name();
    }

    // ---- IDSBTLink framing ----

    public record IdsBtLinkPacket(byte[] payload, boolean checksumFlagged, int rawChecksum) {
        public void wipe() {
            Arrays.fill(payload, (byte) 0);
        }
    }

    public static byte[] encodeIdsBtLinkPacket(byte[] payload) {
        return LegacyWatchPairingCodec.encodeIdsBtLinkPacket(payload);
    }

    public static final class IdsBtLinkDecoder {
        private final LegacyWatchPairingCodec.IdsBtLinkStreamDecoder delegate =
                new LegacyWatchPairingCodec.IdsBtLinkStreamDecoder();

        public List<IdsBtLinkPacket> push(byte[] chunk) {
            List<LegacyWatchPairingCodec.IdsBtLinkFrame> raw = delegate.push(chunk);
            List<IdsBtLinkPacket> out = new ArrayList<>(raw.size());
            for (LegacyWatchPairingCodec.IdsBtLinkFrame f : raw) {
                out.add(new IdsBtLinkPacket(f.payload, f.checksumFlagged, f.rawChecksum));
            }
            return List.copyOf(out);
        }

        public int bufferedByteCount() {
            return delegate.bufferedByteCount();
        }
    }
}
