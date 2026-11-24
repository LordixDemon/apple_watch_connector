package dev.applewatchandroid.bridge;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * [EXPERIMENTAL] Runtime state machine for Apple's legacy Watch pairing protocol
 * ({@code NRExtensiblePair} strategies 1-3, pre-NetworkRelay watchOS 1-9).
 *
 * <p>Only invoked when {@link ProtocolHost#EXPERIMENTAL_LEGACY_PAIRING} is explicitly
 * enabled and a non-Strategy-4 Watch advertisement is locked. Keep completely isolated
 * from the verified Strategy 4 NetworkRelay pairing path.</p>
 */
final class LegacyPairingRunner {
    interface Transport {
        int connectionHandle();

        int peerAddressType();

        byte[] peerAddress();

        BluetoothLocalIdentity localIdentity();

        void checkStop() throws Exception;

        void sendL2cap(int cid, byte[] payload, String label) throws Exception;

        HalTransportSession.Packet nextPacket(long deadlineMs) throws Exception;

        HciCodec.L2capPdu reassembleAcl(byte[] acl);

        byte[] handlePeerAttRequest(byte[] request) throws Exception;

        void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) throws Exception;

        char[] pollPinInput();

        void persistBondSecretOrFail(byte[] bondSecretRecord) throws Exception;
    }

    static final class LegacyPairingResult {
        final int strategy;
        final int style;
        final boolean secureConnections;
        final boolean bondPersisted;
        final List<Integer> commonServices;
        final NrLinkBluetoothPrelude.PairingState terminusReconciledState;
        final NrLinkBluetoothPrelude.LocalRole terminusRole;
        final boolean idsBtLinkOpened;
        final int idsBtLinkFramesDecoded;

        LegacyPairingResult(
                int strategy,
                int style,
                boolean secureConnections,
                boolean bondPersisted,
                List<Integer> commonServices,
                NrLinkBluetoothPrelude.PairingState terminusReconciledState,
                NrLinkBluetoothPrelude.LocalRole terminusRole,
                boolean idsBtLinkOpened,
                int idsBtLinkFramesDecoded) {
            this.strategy = strategy;
            this.style = style;
            this.secureConnections = secureConnections;
            this.bondPersisted = bondPersisted;
            this.commonServices = commonServices == null ? List.of() : List.copyOf(commonServices);
            this.terminusReconciledState = terminusReconciledState;
            this.terminusRole = terminusRole;
            this.idsBtLinkOpened = idsBtLinkOpened;
            this.idsBtLinkFramesDecoded = idsBtLinkFramesDecoded;
        }
    }

    private static final int DEFAULT_LEGACY_ATT_MTU = 185;
    private static final int MAX_DEFERRED_SMP_PDUS = 16;

    private final Transport transport;
    private final SecureRandom random;
    private final BlockingQueue<byte[]> deferredSmpPdus = new LinkedBlockingQueue<>(MAX_DEFERRED_SMP_PDUS);
    private boolean disconnected;

    LegacyPairingRunner(Transport transport, SecureRandom random) {
        this.transport = transport;
        this.random = random;
    }

    LegacyPairingResult run(
            HciCodec.AdvertisingReport target,
            LegacyWatchPairingCodec.LegacyOpticalCode opticalCode) throws Exception {
        LegacyWatchPairingCodec.LegacyAdvertisement ad = target.legacySetup;
        if (ad == null) {
            throw new HostException("[EXPERIMENTAL LEGACY] Missing legacy advertisement metadata");
        }
        int strategy = ad.effectiveStrategy();
        int style = (opticalCode != null)
                ? LegacyWatchPairingCodec.STYLE_OOB
                : LegacyWatchPairingCodec.STYLE_PASSCODE;

        log(String.format(
                Locale.US,
                "[EXPERIMENTAL LEGACY] Starting unverified legacy pairing: strategy=%d style=%s "
                        + "hasUuid9AA4730F=%s rssi=%d (physical hardware unverified).",
                strategy,
                style == LegacyWatchPairingCodec.STYLE_OOB ? "OOB(0x01)" : "PASSCODE(0x02)",
                ad.hasExtensiblePairServiceUuid,
                target.rssi));

        try {
            if (strategy != LegacyWatchPairingCodec.STRATEGY_SIMULTANEOUS) {
                performGattControlWrite(strategy, style);
            } else {
                log("[EXPERIMENTAL LEGACY] Strategy 1 (Simultaneous): skipping GATT NANO control write.");
            }

            SmpBondSummary smp = performSmpBonding(style, opticalCode);
            return performPostSmpBtClHandoff(strategy, style, smp);
        } finally {
            clearDeferredSmpPdus();
        }
    }

    private void performGattControlWrite(int strategy, int style) throws Exception {
        transport.sendL2cap(
                LegacyWatchPairingCodec.ATT_FIXED_CID,
                LegacyWatchPairingCodec.GattAttClient.buildExchangeMtuRequest(DEFAULT_LEGACY_ATT_MTU),
                "[EXPERIMENTAL LEGACY] ATT EXCHANGE MTU");
        byte[] mtuRsp = awaitAttResponse(LEGACY_GATT_TIMEOUT_MS, "ATT Exchange MTU");
        if ((mtuRsp[0] & 0xff) == LegacyWatchPairingCodec.ATT_OPCODE_EXCHANGE_MTU_RSP) {
            int negotiatedMtu = LegacyWatchPairingCodec.GattAttClient.parseExchangeMtuResponse(mtuRsp);
            log("[EXPERIMENTAL LEGACY] ATT MTU negotiated: " + negotiatedMtu);
        } else {
            LegacyWatchPairingCodec.AttError mtuErr = LegacyWatchPairingCodec.GattAttClient.parseErrorResponse(mtuRsp);
            log("[EXPERIMENTAL LEGACY] ATT MTU exchange error ignored: err=0x"
                    + Integer.toHexString(mtuErr.errorCode));
        }

        LegacyWatchPairingCodec.HandleRange serviceRange = discoverExtensiblePairServiceRange();
        int controlValueHandle = discoverControlCharacteristicHandle(serviceRange);

        byte[] nanoPayload = LegacyWatchPairingCodec.encodeControlPayload(strategy, style);
        transport.sendL2cap(
                LegacyWatchPairingCodec.ATT_FIXED_CID,
                LegacyWatchPairingCodec.GattAttClient.buildWriteRequest(controlValueHandle, nanoPayload),
                String.format(Locale.US,
                        "[EXPERIMENTAL LEGACY] ATT WRITE NANO strategy=%d style=%d handle=0x%04X",
                        strategy, style, controlValueHandle));
        byte[] writeRsp = awaitAttResponse(LEGACY_GATT_TIMEOUT_MS, "NANO Control Write");
        if ((writeRsp[0] & 0xff) == LegacyWatchPairingCodec.ATT_OPCODE_ERROR_RSP) {
            LegacyWatchPairingCodec.AttError err = LegacyWatchPairingCodec.GattAttClient.parseErrorResponse(writeRsp);
            throw new HostException(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] Watch rejected NANO GATT write: handle=0x%04X err=0x%02X",
                    err.attributeHandle, err.errorCode));
        }
        if (!LegacyWatchPairingCodec.GattAttClient.isWriteResponse(writeRsp)) {
            throw new HostException("[EXPERIMENTAL LEGACY] Unexpected ATT response to NANO control write");
        }
        log("[EXPERIMENTAL LEGACY] GATT NANO control write acknowledged.");
    }

    private LegacyWatchPairingCodec.HandleRange discoverExtensiblePairServiceRange() throws Exception {
        transport.sendL2cap(
                LegacyWatchPairingCodec.ATT_FIXED_CID,
                LegacyWatchPairingCodec.GattAttClient.buildFindExtensiblePairServiceRequest(1),
                "[EXPERIMENTAL LEGACY] ATT FIND_BY_TYPE_VALUE 9AA4730F");
        byte[] findRsp = awaitAttResponse(LEGACY_GATT_TIMEOUT_MS, "ATT Find NRExtensiblePair Service");
        int opcode = findRsp[0] & 0xff;
        if (opcode == LegacyWatchPairingCodec.ATT_OPCODE_FIND_BY_TYPE_VALUE_RSP) {
            List<LegacyWatchPairingCodec.HandleRange> ranges =
                    LegacyWatchPairingCodec.GattAttClient.parseFindByTypeValueResponse(findRsp);
            return ranges.get(0);
        }
        LegacyWatchPairingCodec.AttError err = LegacyWatchPairingCodec.GattAttClient.parseErrorResponse(findRsp);
        if (err.errorCode != LegacyWatchPairingCodec.ATT_ERR_REQUEST_NOT_SUPPORTED
                && err.errorCode != LegacyWatchPairingCodec.ATT_ERR_UNSUPPORTED_GROUP_TYPE) {
            throw new HostException(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] NRExtensiblePair GATT service 9AA4730F not found (err=0x%02X)",
                    err.errorCode));
        }

        // Paginate Read By Group Type (0x10) when peer does not support Find By Type Value (0x06).
        int start = 1;
        while (start <= 0xFFFF) {
            transport.sendL2cap(
                    LegacyWatchPairingCodec.ATT_FIXED_CID,
                    LegacyWatchPairingCodec.GattAttClient.buildReadPrimaryServicesRequest(start, 0xFFFF),
                    "[EXPERIMENTAL LEGACY] ATT READ_BY_GROUP_TYPE primary services from " + start);
            byte[] rsp = awaitAttResponse(LEGACY_GATT_TIMEOUT_MS, "ATT Read Primary Services");
            if ((rsp[0] & 0xff) == LegacyWatchPairingCodec.ATT_OPCODE_ERROR_RSP) {
                break;
            }
            List<LegacyWatchPairingCodec.PrimaryService> services =
                    LegacyWatchPairingCodec.GattAttClient.parseReadPrimaryServicesResponse(rsp);
            int maxEnd = start;
            for (LegacyWatchPairingCodec.PrimaryService svc : services) {
                if (svc.isExtensiblePairService()) {
                    return new LegacyWatchPairingCodec.HandleRange(svc.startHandle, svc.endHandle);
                }
                if (svc.endHandle > maxEnd) {
                    maxEnd = svc.endHandle;
                }
            }
            if (maxEnd >= 0xFFFF) {
                break;
            }
            start = maxEnd + 1;
        }
        throw new HostException("[EXPERIMENTAL LEGACY] Watch does not expose NRExtensiblePair GATT service 9AA4730F");
    }

    private int discoverControlCharacteristicHandle(LegacyWatchPairingCodec.HandleRange serviceRange) throws Exception {
        int start = serviceRange.startHandle;
        while (start <= serviceRange.endHandle) {
            transport.sendL2cap(
                    LegacyWatchPairingCodec.ATT_FIXED_CID,
                    LegacyWatchPairingCodec.GattAttClient.buildDiscoverCharacteristicsRequest(
                            start, serviceRange.endHandle),
                    "[EXPERIMENTAL LEGACY] ATT DISCOVER CHARS");
            byte[] rsp = awaitAttResponse(LEGACY_GATT_TIMEOUT_MS, "ATT Discover Control Characteristic");
            if ((rsp[0] & 0xff) == LegacyWatchPairingCodec.ATT_OPCODE_ERROR_RSP) {
                break;
            }
            List<LegacyWatchPairingCodec.CharacteristicDeclaration> chars =
                    LegacyWatchPairingCodec.GattAttClient.parseDiscoverCharacteristicsResponse(rsp);
            int maxDecl = start;
            for (LegacyWatchPairingCodec.CharacteristicDeclaration ch : chars) {
                if (ch.isExtensiblePairControlCharacteristic()) {
                    return ch.valueHandle;
                }
                if (ch.declarationHandle > maxDecl) {
                    maxDecl = ch.declarationHandle;
                }
            }
            if (maxDecl >= serviceRange.endHandle) {
                break;
            }
            start = maxDecl + 1;
        }
        throw new HostException("[EXPERIMENTAL LEGACY] NRExtensiblePair control characteristic 5F6C6A23 not found");
    }

    private byte[] awaitAttResponse(long timeoutMs, String label) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + timeoutMs;
        while (BridgeClock.elapsedRealtime() < deadline) {
            transport.checkStop();
            HalTransportSession.Packet packet;
            try {
                packet = transport.nextPacket(deadline);
            } catch (TimeoutException expired) {
                break;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                failIfDisconnected(packet.bytes, label);
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = transport.reassembleAcl(packet.bytes);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (l2cap == null || l2cap.connectionHandle != transport.connectionHandle()) {
                if (l2cap != null) Arrays.fill(l2cap.payload, (byte) 0);
                continue;
            }
            if (l2cap.destinationCid == BluetoothSmpCodec.FIXED_CID) {
                deferSmpPdu(l2cap.payload);
                continue;
            }
            if (l2cap.destinationCid == LegacyWatchPairingCodec.ATT_FIXED_CID && l2cap.payload.length > 0) {
                int opcode = l2cap.payload[0] & 0xff;
                if (LegacyWatchPairingCodec.GattAttClient.isAttClientResponseOpcode(opcode)) {
                    return l2cap.payload;
                }
                byte[] reply = transport.handlePeerAttRequest(l2cap.payload);
                if (reply != null) {
                    transport.sendL2cap(LegacyWatchPairingCodec.ATT_FIXED_CID, reply, "ATT PEER REQUEST RSP");
                }
            } else {
                Arrays.fill(l2cap.payload, (byte) 0);
            }
        }
        throw new HostException("[EXPERIMENTAL LEGACY] Timed out waiting for " + label);
    }

    private SmpBondSummary performSmpBonding(
            int style,
            LegacyWatchPairingCodec.LegacyOpticalCode opticalCode) throws Exception {
        BluetoothSmpCodec.PairingFeatures request = (style == LegacyWatchPairingCodec.STYLE_OOB)
                ? BluetoothSmpCodec.createLegacyOobPairingRequest()
                : BluetoothSmpCodec.createLegacyPasskeyPairingRequest();
        byte[] preq = request.encode();
        log(String.format(
                Locale.US,
                "[EXPERIMENTAL LEGACY] SMP TX: Pairing Request io=0x%02X oob=%d auth=0x%02X keySize=%d",
                request.ioCapability, request.oobDataFlag, request.authenticationRequirements,
                request.maximumEncryptionKeySize));
        transport.sendL2cap(BluetoothSmpCodec.FIXED_CID, preq, "[EXPERIMENTAL LEGACY] SMP PAIRING REQUEST");

        byte[] pres = awaitSmpPdu(BluetoothSmpCodec.PAIRING_RESPONSE, SMP_RESPONSE_TIMEOUT_MS, "SMP Pairing Response", true);
        BluetoothSmpCodec.PairingFeatures response = BluetoothSmpCodec.parsePairingResponse(pres);
        boolean sc = (response.authenticationRequirements & BluetoothSmpCodec.AUTH_SECURE_CONNECTIONS) != 0;
        log(String.format(
                Locale.US,
                "[EXPERIMENTAL LEGACY] SMP RX: Pairing Response io=0x%02X oob=%d auth=0x%02X sc=%s initKeys=0x%02X respKeys=0x%02X",
                response.ioCapability, response.oobDataFlag, response.authenticationRequirements, sc,
                response.initiatorKeyDistribution, response.responderKeyDistribution));

        byte[] localAddress = null;
        byte[] localTypedAddress = null;
        byte[] peerTypedAddress = null;
        byte[] longTermKey = null;
        byte[] distributedLtk = null;
        byte[] peerIrk = null;
        byte[] peerIdentityAddr = null;
        int peerIdentityAddrType = -1;
        byte[] localIrk = null;
        byte[] bondSecretRecord = null;
        try {
            BluetoothLocalIdentity localId = transport.localIdentity();
            localAddress = localId.address();
            localTypedAddress = typedAddress(localAddress, localId.addressType());
            peerTypedAddress = typedAddress(transport.peerAddress(), transport.peerAddressType());

            if (sc) {
                longTermKey = performLeScAuthentication(request, response, style, opticalCode, localTypedAddress, peerTypedAddress);
                transport.startLeEncryption(new byte[8], 0, longTermKey);
            } else {
                byte[] stk = performLeLegacyAuthentication(
                        preq, pres, response, style, opticalCode,
                        localId.addressType(), localAddress,
                        transport.peerAddressType(), transport.peerAddress());
                try {
                    transport.startLeEncryption(new byte[8], 0, stk);
                } finally {
                    Arrays.fill(stk, (byte) 0);
                }
            }

            // Encrypted SMP Key Distribution
            int respDist = response.responderKeyDistribution;
            if (!sc && (respDist & BluetoothSmpCodec.KEY_DISTRIBUTION_ENCRYPTION) != 0) {
                byte[] encPdu = awaitSmpPdu(
                        BluetoothSmpCodec.ENCRYPTION_INFORMATION, SMP_RESPONSE_TIMEOUT_MS,
                        "SMP Responder Encryption Information", false);
                distributedLtk = BluetoothSmpCodec.parseEncryptionInformation(encPdu);
                Arrays.fill(encPdu, (byte) 0);
                byte[] cidPdu = awaitSmpPdu(
                        BluetoothSmpCodec.CENTRAL_IDENTIFICATION, SMP_RESPONSE_TIMEOUT_MS,
                        "SMP Responder Central Identification", false);
                BluetoothSmpCodec.CentralIdentification cid = BluetoothSmpCodec.parseCentralIdentification(cidPdu);
                cid.destroy();
                Arrays.fill(cidPdu, (byte) 0);
                longTermKey = distributedLtk;
            }
            if ((respDist & BluetoothSmpCodec.KEY_DISTRIBUTION_IDENTITY) != 0) {
                byte[] irkPdu = awaitSmpPdu(
                        BluetoothSmpCodec.IDENTITY_INFORMATION, SMP_RESPONSE_TIMEOUT_MS,
                        "SMP Responder Identity Information", false);
                peerIrk = BluetoothSmpCodec.parseIdentityInformation(irkPdu);
                Arrays.fill(irkPdu, (byte) 0);
                byte[] idAddrPdu = awaitSmpPdu(
                        BluetoothSmpCodec.IDENTITY_ADDRESS_INFORMATION, SMP_RESPONSE_TIMEOUT_MS,
                        "SMP Responder Identity Address", false);
                BluetoothSmpCodec.IdentityAddress peerId = BluetoothSmpCodec.parseIdentityAddressInformation(idAddrPdu);
                peerIdentityAddrType = peerId.addressType;
                peerIdentityAddr = peerId.address;
                Arrays.fill(idAddrPdu, (byte) 0);
            }
            if ((respDist & BluetoothSmpCodec.KEY_DISTRIBUTION_SIGNING) != 0) {
                byte[] signPdu = awaitSmpPdu(
                        BluetoothSmpCodec.SIGNING_INFORMATION, SMP_RESPONSE_TIMEOUT_MS,
                        "SMP Responder Signing Information", false);
                byte[] peerCsrk = BluetoothSmpCodec.parseSigningInformation(signPdu);
                Arrays.fill(peerCsrk, (byte) 0);
                Arrays.fill(signPdu, (byte) 0);
            }

            boolean bondPersisted = false;
            localIrk = localId.irk();
            if (longTermKey != null && peerIrk != null && peerIdentityAddr != null) {
                bondSecretRecord = BluetoothBondSecretRecord.encode(
                        localId.addressType(),
                        localAddress,
                        transport.peerAddressType(),
                        transport.peerAddress(),
                        peerIdentityAddrType,
                        peerIdentityAddr,
                        longTermKey,
                        localIrk,
                        peerIrk,
                        response.maximumEncryptionKeySize);
                try {
                    transport.persistBondSecretOrFail(bondSecretRecord);
                    bondPersisted = true;
                } catch (Exception persistenceFailure) {
                    transport.sendL2cap(
                            BluetoothSmpCodec.FIXED_CID,
                            BluetoothSmpCodec.pairingFailed(BluetoothSmpCodec.FAILURE_UNSPECIFIED_REASON),
                            "[EXPERIMENTAL LEGACY] SMP BOND STORE ABORT");
                    throw persistenceFailure;
                }
            }

            int initDist = response.initiatorKeyDistribution;
            if (!sc && (initDist & BluetoothSmpCodec.KEY_DISTRIBUTION_ENCRYPTION) != 0) {
                byte[] localLtk = new byte[16];
                byte[] localRand = new byte[8];
                random.nextBytes(localLtk);
                random.nextBytes(localRand);
                int localEdiv = random.nextInt(0x10000);
                try {
                    transport.sendL2cap(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.encryptionInformation(localLtk),
                            "[EXPERIMENTAL LEGACY] SMP LOCAL ENC INFO");
                    transport.sendL2cap(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.centralIdentification(localEdiv, localRand),
                            "[EXPERIMENTAL LEGACY] SMP LOCAL CENTRAL ID");
                } finally {
                    Arrays.fill(localLtk, (byte) 0);
                    Arrays.fill(localRand, (byte) 0);
                }
            }
            if ((initDist & BluetoothSmpCodec.KEY_DISTRIBUTION_IDENTITY) != 0) {
                transport.sendL2cap(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.identityInformation(localIrk),
                        "[EXPERIMENTAL LEGACY] SMP LOCAL IRK");
                transport.sendL2cap(BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.identityAddressInformation(localId.addressType(), localAddress),
                        "[EXPERIMENTAL LEGACY] SMP LOCAL ID ADDR");
            }
            if ((initDist & BluetoothSmpCodec.KEY_DISTRIBUTION_SIGNING) != 0) {
                byte[] localCsrk = new byte[16];
                random.nextBytes(localCsrk);
                try {
                    transport.sendL2cap(BluetoothSmpCodec.FIXED_CID, BluetoothSmpCodec.signingInformation(localCsrk),
                            "[EXPERIMENTAL LEGACY] SMP LOCAL SIGNING INFO");
                } finally {
                    Arrays.fill(localCsrk, (byte) 0);
                }
            }

            log(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] SMP BOND PASS: sc=%s keySize=%d bondPersisted=%s; "
                            + "secret/key/PIN bytes logged=false.",
                    sc, response.maximumEncryptionKeySize, bondPersisted));
            return new SmpBondSummary(sc, bondPersisted);
        } finally {
            Arrays.fill(preq, (byte) 0);
            Arrays.fill(pres, (byte) 0);
            wipe(localAddress);
            wipe(localTypedAddress);
            wipe(peerTypedAddress);
            wipe(longTermKey);
            wipe(distributedLtk);
            wipe(peerIrk);
            wipe(peerIdentityAddr);
            wipe(localIrk);
            wipe(bondSecretRecord);
        }
    }

    private byte[] performLeScAuthentication(
            BluetoothSmpCodec.PairingFeatures request,
            BluetoothSmpCodec.PairingFeatures response,
            int style,
            LegacyWatchPairingCodec.LegacyOpticalCode opticalCode,
            byte[] localTypedAddress,
            byte[] peerTypedAddress) throws Exception {
        BleSecureConnectionsCrypto.LocalOobMaterial localEc =
                BleSecureConnectionsCrypto.generateLocalOob(random);
        BleSecureConnectionsCrypto.F5Result f5 = null;
        byte[] localPubKey = null;
        byte[] peerPubKeyPdu = null;
        byte[] peerPubKey = null;
        byte[] localPubX = null;
        byte[] peerPubX = null;
        byte[] dhKey = null;
        byte[] finalNa = null;
        byte[] finalNb = null;
        byte[] ra = null;
        byte[] rb = null;
        byte[] macKey = null;
        byte[] ea = null;
        byte[] ebPdu = null;
        byte[] eb = null;
        byte[] expectedEb = null;
        try {
            localPubKey = localEc.publicKeyForSmp();
            localPubX = localEc.publicXLittleEndian();
            transport.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingPublicKey(localPubKey),
                    "[EXPERIMENTAL LEGACY] SMP PAIRING PUBLIC KEY");
            peerPubKeyPdu = awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_PUBLIC_KEY, SMP_RESPONSE_TIMEOUT_MS,
                    "SMP Pairing Public Key", false);
            peerPubKey = BluetoothSmpCodec.parsePairingPublicKey(peerPubKeyPdu);
            peerPubX = Arrays.copyOfRange(peerPubKey, 0, BleSecureConnectionsCrypto.P256_VALUE_LENGTH);
            dhKey = localEc.calculateDhKey(peerPubKey);

            if (style == LegacyWatchPairingCodec.STYLE_OOB
                    && opticalCode != null
                    && response.oobDataFlag == BluetoothSmpCodec.OOB_DATA_PRESENT) {
                finalNa = new byte[16];
                random.nextBytes(finalNa);
                transport.sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.pairingRandom(finalNa),
                        "[EXPERIMENTAL LEGACY] SMP OOB RANDOM");
                byte[] nbPdu = awaitSmpPdu(
                        BluetoothSmpCodec.PAIRING_RANDOM, SMP_RESPONSE_TIMEOUT_MS,
                        "SMP OOB Pairing Random", false);
                finalNb = BluetoothSmpCodec.parsePairingRandom(nbPdu);
                Arrays.fill(nbPdu, (byte) 0);
                ra = opticalCode.oobKey();
                rb = opticalCode.oobKey();
            } else {
                int passkey = awaitSixDigitPasskey();
                for (int round = 0; round < 20; round++) {
                    int z = BleSecureConnectionsCrypto.passkeyRoundZ(passkey, round);
                    byte[] na = new byte[16];
                    random.nextBytes(na);
                    byte[] ca = BleSecureConnectionsCrypto.f4(localPubX, peerPubX, na, z);
                    byte[] cbPdu = null;
                    byte[] cb = null;
                    byte[] nbPdu = null;
                    byte[] nb = null;
                    byte[] expectedCb = null;
                    try {
                        transport.sendL2cap(
                                BluetoothSmpCodec.FIXED_CID,
                                BluetoothSmpCodec.pairingConfirm(ca),
                                "[EXPERIMENTAL LEGACY] SMP PASSKEY CONFIRM r=" + round);
                        cbPdu = awaitSmpPdu(
                                BluetoothSmpCodec.PAIRING_CONFIRM, SMP_RESPONSE_TIMEOUT_MS,
                                "SMP Passkey Confirm round " + round, false);
                        cb = BluetoothSmpCodec.parsePairingConfirm(cbPdu);

                        transport.sendL2cap(
                                BluetoothSmpCodec.FIXED_CID,
                                BluetoothSmpCodec.pairingRandom(na),
                                "[EXPERIMENTAL LEGACY] SMP PASSKEY RANDOM r=" + round);
                        nbPdu = awaitSmpPdu(
                                BluetoothSmpCodec.PAIRING_RANDOM, SMP_RESPONSE_TIMEOUT_MS,
                                "SMP Passkey Random round " + round, false);
                        nb = BluetoothSmpCodec.parsePairingRandom(nbPdu);

                        expectedCb = BleSecureConnectionsCrypto.f4(peerPubX, localPubX, nb, z);
                        if (!MessageDigest.isEqual(cb, expectedCb)) {
                            transport.sendL2cap(
                                    BluetoothSmpCodec.FIXED_CID,
                                    BluetoothSmpCodec.pairingFailed(BluetoothSmpCodec.FAILURE_CONFIRM_VALUE),
                                    "[EXPERIMENTAL LEGACY] SMP PASSKEY CONFIRM MISMATCH");
                            throw new HostException(
                                    "[EXPERIMENTAL LEGACY] Watch SMP passkey commitment mismatch at round " + round);
                        }
                        if (round == 19) {
                            finalNa = na.clone();
                            finalNb = nb.clone();
                        }
                    } finally {
                        Arrays.fill(na, (byte) 0);
                        Arrays.fill(ca, (byte) 0);
                        wipe(cbPdu);
                        wipe(cb);
                        wipe(nbPdu);
                        wipe(nb);
                        wipe(expectedCb);
                    }
                }
                ra = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                rb = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                log("[EXPERIMENTAL LEGACY] SMP LE-SC 20-round passkey commitment completed; PIN bytes logged=false.");
            }

            f5 = BleSecureConnectionsCrypto.f5(dhKey, finalNa, finalNb, localTypedAddress, peerTypedAddress);
            macKey = f5.macKey();
            byte[] localIoCap = new byte[]{
                    (byte) request.ioCapability,
                    (byte) request.oobDataFlag,
                    (byte) request.authenticationRequirements
            };
            byte[] peerIoCap = new byte[]{
                    (byte) response.ioCapability,
                    (byte) response.oobDataFlag,
                    (byte) response.authenticationRequirements
            };
            ea = BleSecureConnectionsCrypto.f6(
                    macKey, finalNa, finalNb, rb, localIoCap, localTypedAddress, peerTypedAddress);
            transport.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingDhKeyCheck(ea),
                    "[EXPERIMENTAL LEGACY] SMP PAIRING DHKEY CHECK");

            ebPdu = awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_DHKEY_CHECK, SMP_RESPONSE_TIMEOUT_MS,
                    "SMP Pairing DHKey Check", false);
            eb = BluetoothSmpCodec.parsePairingDhKeyCheck(ebPdu);
            expectedEb = BleSecureConnectionsCrypto.f6(
                    macKey, finalNb, finalNa, ra, peerIoCap, peerTypedAddress, localTypedAddress);
            if (!MessageDigest.isEqual(eb, expectedEb)) {
                transport.sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.pairingFailed(BluetoothSmpCodec.FAILURE_DHKEY_CHECK_FAILED),
                        "[EXPERIMENTAL LEGACY] SMP DHKEY CHECK MISMATCH");
                throw new HostException("[EXPERIMENTAL LEGACY] Watch LE-SC DHKey Check verification failed");
            }
            return f5.longTermKey();
        } finally {
            localEc.destroy();
            if (f5 != null) f5.destroy();
            wipe(localPubKey);
            wipe(peerPubKeyPdu);
            wipe(peerPubKey);
            wipe(localPubX);
            wipe(peerPubX);
            wipe(dhKey);
            wipe(finalNa);
            wipe(finalNb);
            wipe(ra);
            wipe(rb);
            wipe(macKey);
            wipe(ea);
            wipe(ebPdu);
            wipe(eb);
            wipe(expectedEb);
        }
    }

    private byte[] performLeLegacyAuthentication(
            byte[] preq,
            byte[] pres,
            BluetoothSmpCodec.PairingFeatures response,
            int style,
            LegacyWatchPairingCodec.LegacyOpticalCode opticalCode,
            int localAddrType,
            byte[] localAddr,
            int peerAddrType,
            byte[] peerAddr) throws Exception {
        byte[] tk = null;
        byte[] mRand = new byte[16];
        byte[] mConfirm = null;
        byte[] sConfirmPdu = null;
        byte[] sConfirm = null;
        byte[] sRandPdu = null;
        byte[] sRand = null;
        byte[] expectedSConfirm = null;
        try {
            if (style == LegacyWatchPairingCodec.STYLE_OOB
                    && opticalCode != null
                    && response.oobDataFlag == BluetoothSmpCodec.OOB_DATA_PRESENT) {
                tk = opticalCode.oobKey();
            } else {
                int passkey = awaitSixDigitPasskey();
                tk = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
            }
            random.nextBytes(mRand);
            mConfirm = BleSecureConnectionsCrypto.legacyC1(
                    tk, mRand, preq, pres, localAddrType, localAddr, peerAddrType, peerAddr);
            transport.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingConfirm(mConfirm),
                    "[EXPERIMENTAL LEGACY] SMP LEGACY CONFIRM");
            sConfirmPdu = awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_CONFIRM, SMP_RESPONSE_TIMEOUT_MS,
                    "SMP Legacy Confirm", false);
            sConfirm = BluetoothSmpCodec.parsePairingConfirm(sConfirmPdu);

            transport.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingRandom(mRand),
                    "[EXPERIMENTAL LEGACY] SMP LEGACY RANDOM");
            sRandPdu = awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_RANDOM, SMP_RESPONSE_TIMEOUT_MS,
                    "SMP Legacy Random", false);
            sRand = BluetoothSmpCodec.parsePairingRandom(sRandPdu);

            expectedSConfirm = BleSecureConnectionsCrypto.legacyC1(
                    tk, sRand, preq, pres, localAddrType, localAddr, peerAddrType, peerAddr);
            if (!MessageDigest.isEqual(sConfirm, expectedSConfirm)) {
                transport.sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.pairingFailed(BluetoothSmpCodec.FAILURE_CONFIRM_VALUE),
                        "[EXPERIMENTAL LEGACY] SMP LEGACY CONFIRM MISMATCH");
                throw new HostException("[EXPERIMENTAL LEGACY] Watch LE Legacy SMP confirm value mismatch");
            }
            return BleSecureConnectionsCrypto.legacyS1(tk, sRand, mRand);
        } finally {
            wipe(tk);
            Arrays.fill(mRand, (byte) 0);
            wipe(mConfirm);
            wipe(sConfirmPdu);
            wipe(sConfirm);
            wipe(sRandPdu);
            wipe(sRand);
            wipe(expectedSConfirm);
        }
    }

    private LegacyPairingResult performPostSmpBtClHandoff(
            int strategy,
            int style,
            SmpBondSummary smp) throws Exception {
        byte[] versionTx = HciCodec.buildBtClVersionPdu();
        transport.sendL2cap(HciCodec.BT_CL_SIGNALING_CID, versionTx, "[EXPERIMENTAL LEGACY] BT_CL VERSION");
        HciCodec.BtClPdu versionRsp = awaitBtClOpcode(
                HciCodec.BT_CL_VERSION_INFO, HciCodec.BT_CL_CURRENT_VERSION, 0L, versionTx);
        HciCodec.VersionInfo peerVersion = HciCodec.parseVersionInfo(versionRsp);
        if (peerVersion.version <= 0 || peerVersion.version > 0x40) {
            throw new HostException("[EXPERIMENTAL LEGACY] Implausible Watch BT_CL version " + peerVersion.version);
        }
        log(String.format(
                Locale.US,
                "[EXPERIMENTAL LEGACY] BT_CL VERSION RX: version=%d features=0x%08X",
                peerVersion.version, peerVersion.features));

        List<HciCodec.BtClServiceRecord> offeredServices =
                (strategy == LegacyWatchPairingCodec.STRATEGY_SEQUENTIAL_DIRECT_IPSEC)
                        ? List.of(HciCodec.terminusLinkService(), HciCodec.idsBtLinkService())
                        : List.of(HciCodec.idsBtLinkService());
        byte[] remoteServicesTx = HciCodec.buildRemoteServicesPdu(
                peerVersion.version, peerVersion.features, offeredServices);
        List<Integer> commonServices;
        try {
            transport.sendL2cap(
                    HciCodec.BT_CL_SIGNALING_CID,
                    remoteServicesTx,
                    "[EXPERIMENTAL LEGACY] BT_CL REMOTE_SERVICES");
            HciCodec.BtClPdu commonPdu = awaitBtClOpcode(
                    HciCodec.BT_CL_COMMON_SERVICES, peerVersion.version, peerVersion.features, remoteServicesTx);
            commonServices = HciCodec.parseCommonServices(commonPdu);
        } finally {
            Arrays.fill(remoteServicesTx, (byte) 0);
        }
        log("[EXPERIMENTAL LEGACY] BT_CL COMMON_SERVICES RX: ids=" + commonServices);

        NrLinkBluetoothPrelude.PairingState reconciled = null;
        NrLinkBluetoothPrelude.LocalRole role = null;
        boolean idsBtLinkOpened = false;
        int idsFrames = 0;

        if (commonServices.contains(HciCodec.TERMINUS_LINK_SERVICE_ID)) {
            int localCid = HciCodec.TERMINUS_LINK_LOCAL_CID;
            byte[] createTx = HciCodec.buildCreateChannelPdu(
                    peerVersion.version, peerVersion.features, localCid, HciCodec.TERMINUS_LINK_SERVICE_ID);
            transport.sendL2cap(
                    HciCodec.BT_CL_SIGNALING_CID,
                    createTx,
                    "[EXPERIMENTAL LEGACY] BT_CL CREATE_CHANNEL terminusLink 0x0002");
            HciCodec.BtClPdu acceptPdu = awaitBtClOpcode(
                    HciCodec.BT_CL_ACCEPT_CHANNEL, peerVersion.version, peerVersion.features, null);
            HciCodec.AcceptChannel accept = HciCodec.parseAcceptChannel(acceptPdu);
            log(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] BT_CL ACCEPT terminusLink: status=%d localCID=0x%04X remoteCID=0x%04X",
                    accept.status, localCid, accept.responderLocalCid));
            if (accept.status == 0) {
                NrLinkBluetoothPrelude.PairingState localState = (style == LegacyWatchPairingCodec.STYLE_OOB)
                        ? NrLinkBluetoothPrelude.PairingState.PAIR_WITH_OOB_KEY
                        : NrLinkBluetoothPrelude.PairingState.PAIR_WITH_OOB_KEY_OR_IDS_KEYS;
                byte[] localUuid = new byte[16];
                random.nextBytes(localUuid);
                byte[] prelude = NrLinkBluetoothPrelude.encode(
                        localState, localUuid, NrLinkBluetoothPrelude.FLAG_COMPANION_APL);
                byte[] preludeFrame = L2capErtmCodec.encodeInformationFrame(
                        accept.responderLocalCid, 0, 0, prelude, TERMINUS_FCS_ENABLED);
                transport.sendL2cap(
                        accept.responderLocalCid,
                        preludeFrame,
                        "[EXPERIMENTAL LEGACY] TERMINUS PRELUDE (" + localState + ")");

                byte[] remotePreludeBytes = awaitErtmInformation(
                        localCid, accept.responderLocalCid, TERMINUS_FCS_ENABLED,
                        LEGACY_GATT_TIMEOUT_MS, "terminusLink prelude");
                transport.sendL2cap(
                        accept.responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(accept.responderLocalCid, 1, false, TERMINUS_FCS_ENABLED),
                        "[EXPERIMENTAL LEGACY] TERMINUS PRELUDE RR ACK");
                NrLinkBluetoothPrelude.Parsed remotePrelude = NrLinkBluetoothPrelude.parse(remotePreludeBytes);
                reconciled = NrLinkBluetoothPrelude.reconcile(localState, remotePrelude.state);
                byte[] remoteUuid = remotePrelude.uuid();
                role = NrLinkBluetoothPrelude.electLocalRole(localUuid, remoteUuid);
                Arrays.fill(localUuid, (byte) 0);
                Arrays.fill(remoteUuid, (byte) 0);
                log(String.format(
                        Locale.US,
                        "[EXPERIMENTAL LEGACY] TERMINUS PRELUDE PASS: local=%s remote=%s reconciled=%s role=%s; "
                                + "next milestone=legacy OOBK/IDS-key IKE (0xC4EB/0xC4EC/0xC4E1/0xC4AF).",
                        localState, remotePrelude.state, reconciled, role));
            }
        } else if (commonServices.contains(HciCodec.IDS_BT_LINK_SERVICE_ID)) {
            int localCid = HciCodec.IDS_BT_LINK_LOCAL_CID;
            byte[] createTx = HciCodec.buildCreateChannelPdu(
                    peerVersion.version, peerVersion.features, localCid, HciCodec.IDS_BT_LINK_SERVICE_ID);
            transport.sendL2cap(
                    HciCodec.BT_CL_SIGNALING_CID,
                    createTx,
                    "[EXPERIMENTAL LEGACY] BT_CL CREATE_CHANNEL com.apple.ids 0x0003");
            HciCodec.BtClPdu acceptPdu = awaitBtClOpcode(
                    HciCodec.BT_CL_ACCEPT_CHANNEL, peerVersion.version, peerVersion.features, null);
            HciCodec.AcceptChannel accept = HciCodec.parseAcceptChannel(acceptPdu);
            log(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] BT_CL ACCEPT com.apple.ids: status=%d localCID=0x%04X remoteCID=0x%04X",
                    accept.status, localCid, accept.responderLocalCid));
            if (accept.status == 0) {
                idsBtLinkOpened = true;
                idsFrames = collectIdsBtLinkFrames(localCid, accept.responderLocalCid);
            }
        } else {
            log("[EXPERIMENTAL LEGACY] Watch accepted neither terminusLink nor com.apple.ids in COMMON_SERVICES.");
        }

        return new LegacyPairingResult(
                strategy,
                style,
                smp.sc,
                smp.bondPersisted,
                commonServices,
                reconciled,
                role,
                idsBtLinkOpened,
                idsFrames);
    }

    private int collectIdsBtLinkFrames(int localCid, int remoteCid) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + LEGACY_POST_SMP_DIAGNOSTIC_WINDOW_MS;
        int rxSeq = 0;
        int totalFrames = 0;
        int totalPayloadBytes = 0;
        boolean checksumSeen = false;
        LegacyWatchPairingCodec.IdsBtLinkStreamDecoder decoder =
                new LegacyWatchPairingCodec.IdsBtLinkStreamDecoder();
        while (BridgeClock.elapsedRealtime() < deadline) {
            transport.checkStop();
            HalTransportSession.Packet packet;
            try {
                packet = transport.nextPacket(deadline);
            } catch (TimeoutException done) {
                break;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                if (isDisconnectedEvent(packet.bytes)) {
                    break;
                }
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = transport.reassembleAcl(packet.bytes);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (l2cap == null || l2cap.connectionHandle != transport.connectionHandle()) {
                continue;
            }
            if (l2cap.destinationCid == LegacyWatchPairingCodec.ATT_FIXED_CID) {
                byte[] reply = transport.handlePeerAttRequest(l2cap.payload);
                if (reply != null) {
                    transport.sendL2cap(LegacyWatchPairingCodec.ATT_FIXED_CID, reply, "ATT PEER REQUEST RSP");
                }
                continue;
            }
            if (l2cap.destinationCid != localCid) {
                continue;
            }
            L2capErtmCodec.Frame frame = L2capErtmCodec.decode(localCid, l2cap.payload, false);
            if (frame.supervisory) {
                if (frame.poll) {
                    transport.sendL2cap(
                            remoteCid,
                            L2capErtmCodec.encodeReceiverReady(remoteCid, rxSeq, true, false),
                            "[EXPERIMENTAL LEGACY] IDSBTLink RR FINAL");
                }
                continue;
            }
            rxSeq = (frame.txSequence + 1) % L2capErtmCodec.SEQUENCE_MODULUS;
            transport.sendL2cap(
                    remoteCid,
                    L2capErtmCodec.encodeReceiverReady(remoteCid, rxSeq, false, false),
                    "[EXPERIMENTAL LEGACY] IDSBTLink RR ACK");
            List<LegacyWatchPairingCodec.IdsBtLinkFrame> decoded = decoder.push(frame.information);
            for (LegacyWatchPairingCodec.IdsBtLinkFrame f : decoded) {
                totalFrames++;
                totalPayloadBytes += f.payload.length;
                checksumSeen |= f.checksumFlagged;
                Arrays.fill(f.payload, (byte) 0);
            }
            if (totalFrames > 0) {
                break;
            }
        }
        log(String.format(
                Locale.US,
                "[EXPERIMENTAL LEGACY] IDSBTLink RX: frames=%d payloadBytes=%d checksumFlagged=%s; "
                        + "payload bytes logged=false.",
                totalFrames, totalPayloadBytes, checksumSeen));
        return totalFrames;
    }

    private HciCodec.BtClPdu awaitBtClOpcode(
            int expectedOpcode,
            int peerVersion,
            long peerFeatures,
            byte[] lastOutboundPdu) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + BT_CL_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            transport.checkStop();
            HalTransportSession.Packet packet;
            try {
                packet = transport.nextPacket(deadline);
            } catch (TimeoutException expired) {
                break;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                failIfDisconnected(packet.bytes, "BT_CL opcode 0x" + Integer.toHexString(expectedOpcode));
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = transport.reassembleAcl(packet.bytes);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (l2cap == null || l2cap.connectionHandle != transport.connectionHandle()) {
                continue;
            }
            if (l2cap.destinationCid == LegacyWatchPairingCodec.ATT_FIXED_CID) {
                byte[] reply = transport.handlePeerAttRequest(l2cap.payload);
                if (reply != null) {
                    transport.sendL2cap(LegacyWatchPairingCodec.ATT_FIXED_CID, reply, "ATT PEER REQUEST RSP");
                }
                continue;
            }
            if (l2cap.destinationCid != HciCodec.BT_CL_SIGNALING_CID) {
                continue;
            }
            HciCodec.BtClPdu pdu = HciCodec.parseBtCl(peerVersion, peerFeatures, l2cap.payload);
            if (pdu.opcode == HciCodec.BT_CL_SIGNALING_ERROR && pdu.payload.length == 2
                    && lastOutboundPdu != null && pdu.payload[1] == 0x05) {
                transport.sendL2cap(HciCodec.BT_CL_SIGNALING_CID, lastOutboundPdu, "BT_CL RETRY AFTER 0x08");
                continue;
            }
            if (pdu.opcode == HciCodec.BT_CL_TIME_SYNC_REQUEST) {
                transport.sendL2cap(
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.buildTimeSyncInformation(peerVersion, peerFeatures),
                        "BT_CL TIME_SYNC_INFO");
                continue;
            }
            if (pdu.opcode == expectedOpcode) {
                return pdu;
            }
        }
        throw new HostException(String.format(
                Locale.US,
                "[EXPERIMENTAL LEGACY] Timed out waiting for BT_CL opcode 0x%02X",
                expectedOpcode));
    }

    private byte[] awaitErtmInformation(
            int localCid,
            int remoteCid,
            boolean fcsEnabled,
            long timeoutMs,
            String label) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + timeoutMs;
        while (BridgeClock.elapsedRealtime() < deadline) {
            transport.checkStop();
            HalTransportSession.Packet packet;
            try {
                packet = transport.nextPacket(deadline);
            } catch (TimeoutException expired) {
                break;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                failIfDisconnected(packet.bytes, label);
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = transport.reassembleAcl(packet.bytes);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (l2cap == null || l2cap.connectionHandle != transport.connectionHandle()) {
                continue;
            }
            if (l2cap.destinationCid != localCid) {
                continue;
            }
            L2capErtmCodec.Frame frame = L2capErtmCodec.decode(localCid, l2cap.payload, fcsEnabled);
            if (frame.supervisory) {
                if (frame.poll) {
                    transport.sendL2cap(
                            remoteCid,
                            L2capErtmCodec.encodeReceiverReady(remoteCid, 0, true, fcsEnabled),
                            "[EXPERIMENTAL LEGACY] ERTM RR FINAL");
                }
                continue;
            }
            return frame.information;
        }
        throw new HostException("[EXPERIMENTAL LEGACY] Timed out waiting for " + label);
    }

    private byte[] awaitSmpPdu(
            int expectedOpcode,
            long timeoutMs,
            String label,
            boolean allowSecurityRequest) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + timeoutMs;
        while (BridgeClock.elapsedRealtime() < deadline) {
            transport.checkStop();
            byte[] payload = deferredSmpPdus.poll();
            if (payload == null) {
                HalTransportSession.Packet packet;
                try {
                    packet = transport.nextPacket(deadline);
                } catch (TimeoutException expired) {
                    break;
                }
                if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                    failIfDisconnected(packet.bytes, label);
                    continue;
                }
                HciCodec.L2capPdu l2cap;
                try {
                    l2cap = transport.reassembleAcl(packet.bytes);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (l2cap == null || l2cap.connectionHandle != transport.connectionHandle()) {
                    if (l2cap != null) Arrays.fill(l2cap.payload, (byte) 0);
                    continue;
                }
                if (l2cap.destinationCid == LegacyWatchPairingCodec.ATT_FIXED_CID) {
                    byte[] reply = transport.handlePeerAttRequest(l2cap.payload);
                    if (reply != null) {
                        transport.sendL2cap(LegacyWatchPairingCodec.ATT_FIXED_CID, reply, "ATT PEER REQUEST RSP");
                    }
                    Arrays.fill(l2cap.payload, (byte) 0);
                    continue;
                }
                if (l2cap.destinationCid != BluetoothSmpCodec.FIXED_CID) {
                    Arrays.fill(l2cap.payload, (byte) 0);
                    continue;
                }
                payload = l2cap.payload;
            }

            int opcode = BluetoothSmpCodec.opcode(payload);
            if (opcode == BluetoothSmpCodec.PAIRING_FAILED) {
                int reason = BluetoothSmpCodec.parsePairingFailure(payload);
                Arrays.fill(payload, (byte) 0);
                throw new HostException(String.format(
                        Locale.US,
                        "[EXPERIMENTAL LEGACY] Watch rejected SMP during %s: reason=0x%02X (%s)",
                        label, reason, BluetoothSmpCodec.pairingFailureReasonName(reason)));
            }
            if (opcode == BluetoothSmpCodec.SECURITY_REQUEST && allowSecurityRequest) {
                BluetoothSmpCodec.parseSecurityRequest(payload);
                Arrays.fill(payload, (byte) 0);
                continue;
            }
            if (opcode == expectedOpcode) {
                return payload;
            }
            Arrays.fill(payload, (byte) 0);
            throw new HostException(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] Unexpected SMP opcode=0x%02X while waiting for %s",
                    opcode, label));
        }
        throw new HostException("[EXPERIMENTAL LEGACY] Timed out waiting for " + label);
    }

    private int awaitSixDigitPasskey() throws Exception {
        log("LEGACY_PIN_PROMPT_V1: [EXPERIMENTAL] Enter 6-digit passcode shown on Apple Watch via PIN:XXXXXX.");
        long deadline = BridgeClock.elapsedRealtime() + PIN_INPUT_TIMEOUT_MS;
        while (BridgeClock.elapsedRealtime() < deadline) {
            transport.checkStop();
            char[] pin = transport.pollPinInput();
            if (pin != null) {
                try {
                    if (pin.length != AppleWatchPairingCrypto.PIN_LENGTH) {
                        throw new HostException("[EXPERIMENTAL LEGACY] Legacy passcode must be 6 digits");
                    }
                    int value = 0;
                    for (char digit : pin) {
                        if (digit < '0' || digit > '9') {
                            throw new HostException("[EXPERIMENTAL LEGACY] Legacy passcode must contain only digits");
                        }
                        value = value * 10 + (digit - '0');
                    }
                    return value;
                } finally {
                    Arrays.fill(pin, '\0');
                }
            }
            HalTransportSession.Packet packet;
            try {
                packet = transport.nextPacket(Math.min(deadline, BridgeClock.elapsedRealtime() + 200));
            } catch (TimeoutException idle) {
                continue;
            }
            if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                failIfDisconnected(packet.bytes, "legacy passcode prompt");
                continue;
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = transport.reassembleAcl(packet.bytes);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (l2cap == null || l2cap.connectionHandle != transport.connectionHandle()) {
                continue;
            }
            if (l2cap.destinationCid == LegacyWatchPairingCodec.ATT_FIXED_CID) {
                byte[] reply = transport.handlePeerAttRequest(l2cap.payload);
                if (reply != null) {
                    transport.sendL2cap(LegacyWatchPairingCodec.ATT_FIXED_CID, reply, "ATT PEER REQUEST RSP");
                }
            } else if (l2cap.destinationCid == BluetoothSmpCodec.FIXED_CID) {
                int opcode = BluetoothSmpCodec.opcode(l2cap.payload);
                if (opcode == BluetoothSmpCodec.PAIRING_FAILED) {
                    int reason = BluetoothSmpCodec.parsePairingFailure(l2cap.payload);
                    Arrays.fill(l2cap.payload, (byte) 0);
                    throw new HostException(String.format(
                            Locale.US,
                            "[EXPERIMENTAL LEGACY] Watch aborted SMP while awaiting PIN: reason=0x%02X (%s)",
                            reason, BluetoothSmpCodec.pairingFailureReasonName(reason)));
                }
                Arrays.fill(l2cap.payload, (byte) 0);
            }
        }
        throw new HostException("[EXPERIMENTAL LEGACY] Timed out waiting for 6-digit Watch PIN");
    }

    private void deferSmpPdu(byte[] payload) throws HostException {
        if (!deferredSmpPdus.offer(payload)) {
            Arrays.fill(payload, (byte) 0);
            clearDeferredSmpPdus();
            throw new HostException("[EXPERIMENTAL LEGACY] SMP deferred queue overflow");
        }
    }

    private void clearDeferredSmpPdus() {
        byte[] pdu;
        while ((pdu = deferredSmpPdus.poll()) != null) {
            Arrays.fill(pdu, (byte) 0);
        }
    }

    private void failIfDisconnected(byte[] event, String phase) throws HostException {
        HciCodec.DisconnectionComplete done = HciCodec.parseDisconnectionComplete(event);
        if (done != null && done.connectionHandle == transport.connectionHandle()) {
            disconnected = true;
            throw new HostException(String.format(
                    Locale.US,
                    "[EXPERIMENTAL LEGACY] Watch disconnected during %s: status=0x%02X reason=0x%02X",
                    phase, done.status, done.reason));
        }
    }

    private boolean isDisconnectedEvent(byte[] event) {
        HciCodec.DisconnectionComplete done = HciCodec.parseDisconnectionComplete(event);
        return done != null && done.connectionHandle == transport.connectionHandle();
    }

    boolean wasDisconnected() {
        return disconnected;
    }

    private static byte[] typedAddress(byte[] address, int addressType) {
        byte[] typed = new byte[BleSecureConnectionsCrypto.DEVICE_ADDRESS_LENGTH];
        System.arraycopy(address, 0, typed, 0, 6);
        typed[6] = (byte) addressType;
        return typed;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class SmpBondSummary {
        final boolean sc;
        final boolean bondPersisted;

        SmpBondSummary(boolean sc, boolean bondPersisted) {
            this.sc = sc;
            this.bondPersisted = bondPersisted;
        }
    }
}
