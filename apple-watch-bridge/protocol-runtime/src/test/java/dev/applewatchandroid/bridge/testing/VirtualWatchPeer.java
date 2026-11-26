package dev.applewatchandroid.bridge.testing;

import dev.applewatchandroid.bridge.BleSecureConnectionsCrypto;
import dev.applewatchandroid.bridge.BluetoothSmpCodec;
import dev.applewatchandroid.bridge.BridgeCoreCodecs;
import dev.applewatchandroid.bridge.IdsControlChannelCodec;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Reusable virtual Apple Watch peer simulator covering pairing and reconnection across
 * Strategies 1, 2, 3, and 4 over {@link FakeBluetoothController}.
 */
public final class VirtualWatchPeer implements FakeBluetoothController.PeerHandler {
    private static final byte[] EXTENSIBLE_PAIR_SERVICE_UUID_LE = new byte[]{
            (byte) 0x96, (byte) 0xC1, (byte) 0x9F, (byte) 0x55,
            0x31, (byte) 0xC9, 0x21, (byte) 0xB8,
            (byte) 0xC3, 0x4C, 0x5C, (byte) 0xB2,
            0x0F, 0x73, (byte) 0xA4, (byte) 0x9A
    };
    private static final byte[] EXTENSIBLE_PAIR_CONTROL_CHAR_UUID_LE = new byte[]{
            0x60, 0x34, (byte) 0x94, 0x34,
            0x71, 0x01, 0x0B, (byte) 0x81,
            0x0E, 0x40, (byte) 0xC8, (byte) 0x8A,
            0x23, 0x6A, 0x6C, 0x5F
    };

    private final PairingStrategyId strategy;
    private final byte[] peerAddr;
    private final int peerAddrType = 1;
    private final byte[] peerIdentityAddr;
    private final byte[] peerIrk;
    private final byte[] advertisingData;
    private final int rssi;
    private final boolean secureConnections;
    private final boolean opticalOob;
    private final byte[] opticalOobKey16;
    private final int passkey;
    private final int legacyEdiv;
    private final byte[] legacyRand;
    private final byte[] distributedLtk;
    private final int btClVersion;
    private final boolean useTerminusLink;
    private final boolean injectConcurrentWatchAttRead;
    private final boolean forceReadByGroupTypeFallback;

    private final SecureRandom rng = new SecureRandom();

    private boolean pairedReconnectMode;
    private byte[] localAddr;
    private BleSecureConnectionsCrypto.LocalOobMaterial watchEc;
    private byte[] preq;
    private byte[] pres;
    private byte[] pka;
    private byte[] dhKey;
    private byte[] lastNa;
    private byte[] lastNb;
    private byte[] scLtk;
    private byte[] legacyStk;
    private int passkeyRound;
    private int idsRemoteCid = 0x0046;
    private int terminusRemoteCid = 0x0045;
    private int idsCompanionLocalCid = BridgeCoreCodecs.IDS_BT_LINK_LOCAL_CID;

    // Observable telemetry for contract assertions
    public volatile byte[] writtenNanoPayload;
    public volatile boolean usedReadByGroupTypeFallback;
    public volatile boolean watchAttReadAnsweredWithIphone;
    public volatile int pairingFailedReason = -1;
    public volatile int localKeysReceivedCount;
    public volatile boolean reconnectEncryptionVerified;
    public volatile int idsHelloSentCount;
    public volatile int terminusPreludeRepliedCount;
    public volatile int lastTerminusPreludeRemoteState = -1;

    private VirtualWatchPeer(
            PairingStrategyId strategy,
            byte[] peerAddr,
            byte[] peerIdentityAddr,
            byte[] peerIrk,
            byte[] advertisingData,
            int rssi,
            boolean secureConnections,
            boolean opticalOob,
            byte[] opticalOobKey16,
            int passkey,
            int legacyEdiv,
            byte[] legacyRand,
            byte[] distributedLtk,
            int btClVersion,
            boolean useTerminusLink,
            boolean injectConcurrentWatchAttRead,
            boolean forceReadByGroupTypeFallback) {
        this.strategy = strategy;
        this.peerAddr = peerAddr.clone();
        this.peerIdentityAddr = peerIdentityAddr.clone();
        this.peerIrk = peerIrk.clone();
        this.advertisingData = advertisingData.clone();
        this.rssi = rssi;
        this.secureConnections = secureConnections;
        this.opticalOob = opticalOob;
        this.opticalOobKey16 = opticalOobKey16 == null ? null : opticalOobKey16.clone();
        this.passkey = passkey;
        this.legacyEdiv = legacyEdiv;
        this.legacyRand = legacyRand == null ? new byte[8] : legacyRand.clone();
        this.distributedLtk = distributedLtk.clone();
        this.btClVersion = btClVersion;
        this.useTerminusLink = useTerminusLink;
        this.injectConcurrentWatchAttRead = injectConcurrentWatchAttRead;
        this.forceReadByGroupTypeFallback = forceReadByGroupTypeFallback;
    }

    /** Strategy 1 (watchOS 1–3 / Series 0): BT 4.0 SC=0, EDIV/Rand preserved, BT_CL v6, com.apple.ids. */
    public static VirtualWatchPeer s1(
            byte[] peerAddr, int passkey, int ediv, byte[] rand, byte[] peerIrk, byte[] distributedLtk) {
        byte[] ad = new byte[]{9, 0x09, '1', '1', '1', '1', '1', 'B', 'A', 'A'};
        byte[] idAddr = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC1};
        return new VirtualWatchPeer(
                PairingStrategyId.SIMULTANEOUS_S1, peerAddr, idAddr, peerIrk, ad, -52,
                false, false, null, passkey, ediv, rand, distributedLtk,
                6, false, false, false);
    }

    /** Strategy 2 (watchOS 3–5): GATT NANO 0x02 + concurrent Watch ATT_READ_REQ, LE SC Passkey, com.apple.ids. */
    public static VirtualWatchPeer s2(byte[] peerAddr, int passkey, byte[] peerIrk) {
        byte[] ad = new byte[18 + 10];
        ad[0] = 17;
        ad[1] = 0x06;
        System.arraycopy(EXTENSIBLE_PAIR_SERVICE_UUID_LE, 0, ad, 2, 16);
        byte[] name = new byte[]{9, 0x09, '5', '4', '3', '2', '1', 'C', 'B', 'C'};
        System.arraycopy(name, 0, ad, 18, name.length);
        byte[] idAddr = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC2};
        return new VirtualWatchPeer(
                PairingStrategyId.SEQUENTIAL_S2, peerAddr, idAddr, peerIrk, ad, -54,
                true, false, null, passkey, 0, new byte[8], new byte[16],
                11, false, true, true);
    }

    /** Strategy 3 (watchOS 6–9): FE25 without localName, GATT NANO 0x03, one-way Optical OOB, terminusLink. */
    public static VirtualWatchPeer s3Optical(byte[] peerAddr, byte[] fe25Ad, byte[] oobKey16, byte[] peerIrk) {
        byte[] idAddr = new byte[]{0x31, 0x32, 0x33, 0x34, 0x35, (byte) 0xC3};
        return new VirtualWatchPeer(
                PairingStrategyId.DIRECT_IPSEC_S3, peerAddr, idAddr, peerIrk, fe25Ad, -50,
                true, true, oobKey16, 0, 0, new byte[8], new byte[16],
                11, true, false, false);
    }

    /** Strategy 4 (watchOS 9/10+): modern FE25 advertising beacon for priority & isolation tests. */
    public static VirtualWatchPeer s4Beacon(byte[] peerAddr, byte[] modernFe25Ad, int rssi) {
        return new VirtualWatchPeer(
                PairingStrategyId.NETWORK_RELAY_S4, peerAddr, peerAddr, new byte[16], modernFe25Ad, rssi,
                true, false, null, 0, 0, new byte[8], new byte[16],
                11, true, false, false);
    }

    public void setPairedReconnectMode(boolean reconnectMode) {
        this.pairedReconnectMode = reconnectMode;
    }

    public byte[] peerAddress() {
        return peerAddr.clone();
    }

    public byte[] peerIrk() {
        return peerIrk.clone();
    }

    public byte[] expectedLtk() {
        return secureConnections
                ? (scLtk == null ? null : scLtk.clone())
                : distributedLtk.clone();
    }

    @Override
    public void onScanEnabled(FakeBluetoothController controller) {
        controller.emitAdvertisingReport(0, peerAddrType, peerAddr, rssi, advertisingData);
    }

    @Override
    public void onLeConnected(FakeBluetoothController controller, int handle, int addrType, byte[] addr) {
        if (!Arrays.equals(addr, peerAddr)) {
            return;
        }
        this.localAddr = controller.localRandomAddress();
        this.passkeyRound = 0;
    }

    @Override
    public boolean onLeStartEncryption(
            FakeBluetoothController controller, int handle, byte[] rand, int ediv, byte[] ltk) {
        if (pairedReconnectMode) {
            boolean match;
            if (secureConnections) {
                match = ediv == 0 && Arrays.equals(rand, new byte[8]) && MessageDigest.isEqual(scLtk, ltk);
            } else {
                match = ediv == legacyEdiv
                        && Arrays.equals(rand, legacyRand)
                        && MessageDigest.isEqual(distributedLtk, ltk);
            }
            reconnectEncryptionVerified = match;
            return match;
        }
        byte[] expected = secureConnections ? scLtk : legacyStk;
        if (expected == null || !MessageDigest.isEqual(expected, ltk)) {
            return false;
        }
        if (!secureConnections) {
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                    BluetoothSmpCodec.encryptionInformation(distributedLtk));
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                    BluetoothSmpCodec.centralIdentification(legacyEdiv, legacyRand));
        }
        controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                BluetoothSmpCodec.identityInformation(peerIrk));
        controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                BluetoothSmpCodec.identityAddressInformation(1, peerIdentityAddr));
        controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                BluetoothSmpCodec.signingInformation(new byte[16]));
        return true;
    }

    @Override
    public void onAclFromCompanion(FakeBluetoothController controller, int handle, int cid, byte[] payload) {
        if (cid == BridgeCoreCodecs.ATT_FIXED_CID) {
            handleAtt(controller, handle, payload);
        } else if (cid == BridgeCoreCodecs.SMP_FIXED_CID) {
            handleSmp(controller, handle, payload);
        } else if (cid == BridgeCoreCodecs.BT_CL_SIGNALING_CID) {
            handleBtCl(controller, handle, payload);
        } else if (cid == terminusRemoteCid) {
            handleTerminusErtm(controller, handle, payload);
        }
    }

    private void handleAtt(FakeBluetoothController controller, int handle, byte[] req) {
        int op = req[0] & 0xFF;
        if (op == 0x0B) {
            // Reply from companion's HalGattServer to Watch's concurrent ATT_READ_REQ(0x0003)!
            String value = new String(Arrays.copyOfRange(req, 1, req.length), StandardCharsets.US_ASCII);
            if ("iPhone".equals(value)) {
                watchAttReadAnsweredWithIphone = true;
            }
            return;
        }
        if (op == 0x02) { // ATT_EXCHANGE_MTU_REQ
            if (injectConcurrentWatchAttRead) {
                // Inject Watch-initiated ATT_READ_REQ(0x0003) concurrently right before replying!
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x0A, 0x03, 0x00});
            }
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x03, (byte) 185, 0x00});
        } else if (op == 0x06) { // ATT_FIND_BY_TYPE_VALUE_REQ
            if (forceReadByGroupTypeFallback) {
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID,
                        new byte[]{0x01, 0x06, 0x01, 0x00, 0x06});
            } else {
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID,
                        new byte[]{0x07, 0x20, 0x00, 0x24, 0x00});
            }
        } else if (op == 0x10) { // ATT_READ_BY_GROUP_TYPE_REQ
            usedReadByGroupTypeFallback = true;
            int start = (req[1] & 0xFF) | ((req[2] & 0xFF) << 8);
            if (start == 1) {
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID,
                        new byte[]{0x11, 0x06, 0x01, 0x00, 0x05, 0x00, 0x00, 0x18});
            } else {
                byte[] rsp = new byte[22];
                rsp[0] = 0x11;
                rsp[1] = 20;
                rsp[2] = 0x20; rsp[3] = 0x00;
                rsp[4] = 0x24; rsp[5] = 0x00;
                System.arraycopy(EXTENSIBLE_PAIR_SERVICE_UUID_LE, 0, rsp, 6, 16);
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID, rsp);
            }
        } else if (op == 0x08) { // ATT_READ_BY_TYPE_REQ
            byte[] rsp = new byte[23];
            rsp[0] = 0x09;
            rsp[1] = 21;
            rsp[2] = 0x21; rsp[3] = 0x00;
            rsp[4] = 0x1A;
            rsp[5] = 0x22; rsp[6] = 0x00;
            System.arraycopy(EXTENSIBLE_PAIR_CONTROL_CHAR_UUID_LE, 0, rsp, 7, 16);
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID, rsp);
        } else if (op == 0x12) { // ATT_WRITE_REQ
            writtenNanoPayload = Arrays.copyOfRange(req, 3, req.length);
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.ATT_FIXED_CID, new byte[]{0x13});
        }
    }

    private void handleSmp(FakeBluetoothController controller, int handle, byte[] pdu) {
        int op = pdu[0] & 0xFF;
        if (op == BluetoothSmpCodec.PAIRING_REQUEST) {
            preq = pdu.clone();
            int io = opticalOob
                    ? BluetoothSmpCodec.IO_CAPABILITY_NO_INPUT_NO_OUTPUT
                    : BluetoothSmpCodec.IO_CAPABILITY_DISPLAY_ONLY;
            // One-way optical OOB sets resp.oob=0 while initiator sent init.oob=1
            int oob = BluetoothSmpCodec.OOB_DATA_NOT_PRESENT;
            int auth = secureConnections
                    ? BluetoothSmpCodec.AUTH_SC_MITM_BOND
                    : BluetoothSmpCodec.AUTH_LEGACY_MITM_BOND;
            pres = new byte[]{
                    BluetoothSmpCodec.PAIRING_RESPONSE,
                    (byte) io,
                    (byte) oob,
                    (byte) auth,
                    16,
                    BluetoothSmpCodec.STANDARD_LE_KEY_DISTRIBUTION,
                    BluetoothSmpCodec.STANDARD_LE_KEY_DISTRIBUTION
            };
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID, pres.clone());
        } else if (op == BluetoothSmpCodec.PAIRING_PUBLIC_KEY) {
            pka = BluetoothSmpCodec.parsePairingPublicKey(pdu);
            watchEc = BleSecureConnectionsCrypto.generateLocalOob(rng);
            dhKey = watchEc.calculateDhKey(pka);
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                    BluetoothSmpCodec.pairingPublicKey(watchEc.publicKeyForSmp()));
            passkeyRound = 0;
        } else if (op == BluetoothSmpCodec.PAIRING_CONFIRM) {
            lastNb = new byte[16];
            rng.nextBytes(lastNb);
            if (secureConnections) {
                int z = BleSecureConnectionsCrypto.passkeyRoundZ(passkey, passkeyRound);
                byte[] cb = BleSecureConnectionsCrypto.f4(
                        watchEc.publicXLittleEndian(), Arrays.copyOfRange(pka, 0, 32), lastNb, z);
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                        BluetoothSmpCodec.pairingConfirm(cb));
            } else {
                byte[] tk = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                byte[] sConfirm = BleSecureConnectionsCrypto.legacyC1(
                        tk, lastNb, preq, pres, 1, localAddr, peerAddrType, peerAddr);
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                        BluetoothSmpCodec.pairingConfirm(sConfirm));
            }
        } else if (op == BluetoothSmpCodec.PAIRING_RANDOM) {
            lastNa = BluetoothSmpCodec.parsePairingRandom(pdu);
            if (opticalOob) {
                lastNb = new byte[16];
                rng.nextBytes(lastNb);
            }
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                    BluetoothSmpCodec.pairingRandom(lastNb));
            if (secureConnections && !opticalOob) {
                passkeyRound++;
            } else if (!secureConnections) {
                byte[] tk = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                legacyStk = BleSecureConnectionsCrypto.legacyS1(tk, lastNb, lastNa);
            }
        } else if (op == BluetoothSmpCodec.PAIRING_DHKEY_CHECK) {
            byte[] ea = BluetoothSmpCodec.parsePairingDhKeyCheck(pdu);
            byte[] localTyped = typed(localAddr, 1);
            byte[] peerTyped = typed(peerAddr, peerAddrType);
            BleSecureConnectionsCrypto.F5Result f5 =
                    BleSecureConnectionsCrypto.f5(dhKey, lastNa, lastNb, localTyped, peerTyped);
            byte[] macKey = f5.macKey();
            scLtk = f5.longTermKey();
            f5.destroy();
            byte[] ra = opticalOob ? new byte[16] : BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
            byte[] rb = opticalOob ? opticalOobKey16.clone() : BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
            byte[] localIo = new byte[]{preq[1], preq[2], preq[3]};
            byte[] peerIo = new byte[]{pres[1], pres[2], pres[3]};
            byte[] expectedEa = BleSecureConnectionsCrypto.f6(
                    macKey, lastNa, lastNb, rb, localIo, localTyped, peerTyped);
            if (!MessageDigest.isEqual(ea, expectedEa)) {
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                        BluetoothSmpCodec.pairingFailed(BluetoothSmpCodec.FAILURE_DHKEY_CHECK_FAILED));
                return;
            }
            byte[] eb = BleSecureConnectionsCrypto.f6(
                    macKey, lastNb, lastNa, ra, peerIo, peerTyped, localTyped);
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.SMP_FIXED_CID,
                    BluetoothSmpCodec.pairingDhKeyCheck(eb));
        } else if (op == BluetoothSmpCodec.PAIRING_FAILED) {
            pairingFailedReason = BluetoothSmpCodec.parsePairingFailure(pdu);
        } else if (op == BluetoothSmpCodec.ENCRYPTION_INFORMATION
                || op == BluetoothSmpCodec.CENTRAL_IDENTIFICATION
                || op == BluetoothSmpCodec.IDENTITY_INFORMATION
                || op == BluetoothSmpCodec.IDENTITY_ADDRESS_INFORMATION
                || op == BluetoothSmpCodec.SIGNING_INFORMATION) {
            localKeysReceivedCount++;
        }
    }

    private void handleBtCl(FakeBluetoothController controller, int handle, byte[] payload) {
        int parseVer = (payload[0] & 0xFF) == BridgeCoreCodecs.BT_CL_VERSION_INFO ? 11 : btClVersion;
        BridgeCoreCodecs.BtClFrame frame = BridgeCoreCodecs.parseBtCl(parseVer, 0L, payload);
        if (frame.opcode() == BridgeCoreCodecs.BT_CL_VERSION_INFO) {
            if (btClVersion < 8) {
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                        new byte[]{(byte) BridgeCoreCodecs.BT_CL_VERSION_INFO, 0x05, (byte) btClVersion, 0x01, 0, 0, 0});
            } else {
                controller.sendAclToCompanion(handle, BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                        BridgeCoreCodecs.buildBtClVersionPdu());
            }
        } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_REMOTE_SERVICES) {
            List<Integer> accepted = useTerminusLink
                    ? List.of(BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID)
                    : List.of(BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID);
            long feat = btClVersion < 8 ? 0x01L : 0L;
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                    BridgeCoreCodecs.buildBtClCommonServicesPdu(btClVersion, feat, accepted));
        } else if (frame.opcode() == BridgeCoreCodecs.BT_CL_CREATE_CHANNEL) {
            BridgeCoreCodecs.BtClCreateChannel cc = BridgeCoreCodecs.parseBtClCreateChannel(frame);
            long feat = btClVersion < 8 ? 0x01L : 0L;
            int rspCid = cc.serviceId() == BridgeCoreCodecs.TERMINUS_LINK_SERVICE_ID
                    ? terminusRemoteCid : idsRemoteCid;
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.BT_CL_SIGNALING_CID,
                    BridgeCoreCodecs.buildBtClAcceptChannelPdu(btClVersion, feat, 0, cc.serviceId(), rspCid));
            if (cc.serviceId() == BridgeCoreCodecs.IDS_BT_LINK_SERVICE_ID) {
                idsCompanionLocalCid = cc.requesterLocalCid();
                byte[] hello = buildMinimalWatchOsHello(
                        strategy == PairingStrategyId.SIMULTANEOUS_S1 ? "2.2" : "4.3.1",
                        strategy == PairingStrategyId.SIMULTANEOUS_S1 ? "13V420" : "15T567",
                        strategy == PairingStrategyId.SIMULTANEOUS_S1 ? "Watch1,2" : "Watch3,4");
                byte[] idsPacket = BridgeCoreCodecs.encodeIdsBtLinkPacket(hello);
                controller.sendAclToCompanion(handle, idsCompanionLocalCid,
                        BridgeCoreCodecs.encodeErtmInformationFrame(idsCompanionLocalCid, 0, 0, idsPacket, false));
                idsHelloSentCount++;
            }
        }
    }

    private void handleTerminusErtm(FakeBluetoothController controller, int handle, byte[] payload) {
        try (BridgeCoreCodecs.ErtmLink peerErtm = new BridgeCoreCodecs.ErtmLink(
                terminusRemoteCid, BridgeCoreCodecs.TERMINUS_LINK_LOCAL_CID, false);
             BridgeCoreCodecs.ErtmInbound in = peerErtm.accept(payload)) {
            if (in.deliveredSdus().isEmpty()) {
                return;
            }
            BridgeCoreCodecs.PreludeParsed req = BridgeCoreCodecs.parsePrelude(in.deliveredSdus().get(0));
            lastTerminusPreludeRemoteState = req.stateWireValue();
            int replyState = pairedReconnectMode
                    ? BridgeCoreCodecs.PRELUDE_STATE_HAS_COMPLETED_PAIRING
                    : BridgeCoreCodecs.PRELUDE_STATE_PAIR_WITH_OOB_KEY;
            byte[] watchUuid = new byte[16];
            Arrays.fill(watchUuid, (byte) 0x55);
            byte[] reply = BridgeCoreCodecs.encodePrelude(
                    replyState, watchUuid, BridgeCoreCodecs.PRELUDE_FLAG_COMPANION_APL);
            controller.sendAclToCompanion(handle, BridgeCoreCodecs.TERMINUS_LINK_LOCAL_CID,
                    BridgeCoreCodecs.encodeErtmInformationFrame(
                            BridgeCoreCodecs.TERMINUS_LINK_LOCAL_CID, 0, 1, reply, false));
            terminusPreludeRepliedCount++;
        }
    }

    /** Encodes a watchOS 1–5 Hello omitting TLVs 3, 4, and 5 (parsed by lenient legacy decoder). */
    public static byte[] buildMinimalWatchOsHello(String productVersion, String buildVersion, String model) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(IdsControlChannelCodec.TYPE_HELLO);
        writeU16String(out, "1");
        writeU16String(out, "Watch OS");
        writeU16String(out, productVersion);
        writeU16String(out, buildVersion);
        writeU16String(out, model);
        // pairingProtocolVersion (uint32 BE)
        out.write(0); out.write(0); out.write(0); out.write(2);
        // TLV 0: minCompatibilityVersion (4 bytes)
        out.write(0); out.write(0); out.write(4); out.write(0); out.write(0); out.write(0); out.write(1);
        // TLV 1: maxCompatibilityVersion (4 bytes)
        out.write(1); out.write(0); out.write(4); out.write(0); out.write(0); out.write(0); out.write(3);
        // TLV 2: instanceId (16 bytes); TLVs 3, 4, 5 intentionally omitted!
        UUID inst = UUID.randomUUID();
        out.write(2); out.write(0); out.write(16);
        long msb = inst.getMostSignificantBits();
        long lsb = inst.getLeastSignificantBits();
        for (int i = 56; i >= 0; i -= 8) out.write((int) (msb >>> i));
        for (int i = 56; i >= 0; i -= 8) out.write((int) (lsb >>> i));
        return out.toByteArray();
    }

    private static void writeU16String(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write((b.length >> 8) & 0xFF);
        out.write(b.length & 0xFF);
        out.writeBytes(b);
    }

    private static byte[] typed(byte[] addr, int type) {
        byte[] out = new byte[7];
        System.arraycopy(addr, 0, out, 0, 6);
        out[6] = (byte) type;
        return out;
    }
}
