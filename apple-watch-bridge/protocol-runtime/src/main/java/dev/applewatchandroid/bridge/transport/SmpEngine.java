package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BleSecureConnectionsCrypto;
import dev.applewatchandroid.bridge.BluetoothBondSecretRecord;
import dev.applewatchandroid.bridge.BluetoothLocalIdentity;
import dev.applewatchandroid.bridge.BluetoothSmpCodec;
import dev.applewatchandroid.bridge.HostException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Unified BLE Security Manager Protocol (SMP) initiator over {@link L2capChannelMux}.
 *
 * <p>Supports LE Secure Connections OOB (both one-way {@code resp.oob=0} and mutual
 * {@code resp.oob=1}), LE SC 20-round Passkey Entry, LE SC Numeric Comparison, and
 * LE Legacy ({@code SC=0}) pairing with full {@code EDIV}/{@code Rand} capture and
 * fail-closed bond persistence before local key distribution.</p>
 */
public final class SmpEngine {
    @FunctionalInterface
    public interface PasskeyProvider {
        int awaitSixDigitPasskey(long timeoutMs) throws Exception;
    }

    @FunctionalInterface
    public interface NumericComparisonApprover {
        boolean confirmSixDigitCode(int code, long timeoutMs) throws Exception;
    }

    @FunctionalInterface
    public interface BondCommitHook {
        void persistBondOrFail(byte[] bondSecretRecord, boolean secureConnections, int ediv, byte[] rand)
                throws Exception;
    }

    private static final int RAND_LENGTH = 8;
    private static final int PASSKEY_ROUNDS = 20;
    private static final int FAILURE_AUTHENTICATION_REQUIREMENTS = 0x03;
    private static final int FAILURE_OOB_NOT_AVAILABLE = 0x02;
    private static final int FAILURE_INVALID_PARAMETERS = 0x0A;

    private final L2capChannelMux mux;
    private final SecureRandom random;

    public SmpEngine(L2capChannelMux mux) {
        this(mux, new SecureRandom());
    }

    public SmpEngine(L2capChannelMux mux, SecureRandom random) {
        this.mux = Objects.requireNonNull(mux, "mux");
        this.random = Objects.requireNonNull(random, "random");
    }

    public SmpBondResult pair(
            SmpAuthMode mode,
            PasskeyProvider passkeyProvider,
            NumericComparisonApprover numericApprover,
            BondCommitHook bondCommitHook,
            long responseTimeoutMs,
            long userInputTimeoutMs) throws Exception {
        Objects.requireNonNull(mode, "mode");
        HciTransport transport = mux.transport();
        transport.checkStop();

        BluetoothLocalIdentity localId = transport.localIdentity();
        byte[] peerAddr = transport.peerAddress();
        int peerAddrType = transport.peerAddressType();
        if (localId == null
                || peerAddr == null
                || peerAddr.length != 6
                || (peerAddrType != 0 && peerAddrType != 1)) {
            if (peerAddr != null) {
                wipe(peerAddr);
            }
            throw new HostException("SMP address/identity state is incomplete");
        }

        BluetoothSmpCodec.PairingFeatures request = buildPairingRequest(mode);
        byte[] preq = request.encode();
        byte[] pres = null;
        byte[] localAddr = null;
        byte[] localTypedAddr = null;
        byte[] peerTypedAddr = null;
        byte[] longTermKey = null;
        byte[] responderRand = new byte[RAND_LENGTH];
        byte[] peerIrk = null;
        byte[] peerIdentityAddr = null;
        byte[] localIrk = null;
        byte[] bondSecretRecord = null;
        int responderEdiv = 0;
        int peerIdentityAddrType = -1;

        try {
            localAddr = localId.address();
            localIrk = localId.irk();
            localTypedAddr = typedAddress(localAddr, localId.addressType());
            peerTypedAddr = typedAddress(peerAddr, peerAddrType);

            transport.log(String.format(
                    Locale.US,
                    "SMP TX: Pairing Request io=0x%02X oob=%d auth=0x%02X keySize=%d initKeys=0x%02X respKeys=0x%02X",
                    request.ioCapability,
                    request.oobDataFlag,
                    request.authenticationRequirements,
                    request.maximumEncryptionKeySize,
                    request.initiatorKeyDistribution,
                    request.responderKeyDistribution));
            mux.sendL2cap(BluetoothSmpCodec.FIXED_CID, preq, "SMP PAIRING REQUEST");

            pres = mux.awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_RESPONSE,
                    responseTimeoutMs,
                    "SMP Pairing Response",
                    true);
            BluetoothSmpCodec.PairingFeatures response = parseAndValidatePairingResponse(pres);
            transport.log(String.format(
                    Locale.US,
                    "SMP RX: Pairing Response io=0x%02X oob=%d auth=0x%02X keySize=%d initKeys=0x%02X respKeys=0x%02X",
                    response.ioCapability,
                    response.oobDataFlag,
                    response.authenticationRequirements,
                    response.maximumEncryptionKeySize,
                    response.initiatorKeyDistribution,
                    response.responderKeyDistribution));

            boolean effectiveSc =
                    (request.authenticationRequirements & BluetoothSmpCodec.AUTH_SECURE_CONNECTIONS) != 0
                            && (response.authenticationRequirements
                                    & BluetoothSmpCodec.AUTH_SECURE_CONNECTIONS) != 0;
            int encryptionKeySize = Math.min(request.maximumEncryptionKeySize, response.maximumEncryptionKeySize);

            if (effectiveSc) {
                longTermKey = performLeScAuthentication(
                        mode,
                        request,
                        response,
                        localTypedAddr,
                        peerTypedAddr,
                        passkeyProvider,
                        numericApprover,
                        responseTimeoutMs,
                        userInputTimeoutMs);
                transport.startLeEncryption(new byte[RAND_LENGTH], 0, longTermKey);
            } else {
                if (mode instanceof SmpAuthMode.LeScNumericComparison) {
                    sendPairingFailedBestEffort(FAILURE_AUTHENTICATION_REQUIREMENTS, "SMP NC REQUIRES SC");
                    throw new HostException("Watch cleared SC bit; Numeric Comparison cannot run in LE Legacy mode");
                }
                byte[] stk = performLeLegacyAuthentication(
                        mode,
                        preq,
                        pres,
                        response,
                        localId.addressType(),
                        localAddr,
                        peerAddrType,
                        peerAddr,
                        passkeyProvider,
                        responseTimeoutMs,
                        userInputTimeoutMs);
                try {
                    transport.startLeEncryption(new byte[RAND_LENGTH], 0, stk);
                } finally {
                    wipe(stk);
                }
            }

            // Phase 3: Receive Responder keys
            int respDist = response.responderKeyDistribution;
            if (!effectiveSc && (respDist & BluetoothSmpCodec.KEY_DISTRIBUTION_ENCRYPTION) != 0) {
                byte[] encPdu = mux.awaitSmpPdu(
                        BluetoothSmpCodec.ENCRYPTION_INFORMATION,
                        responseTimeoutMs,
                        "SMP Responder Encryption Information",
                        false);
                try {
                    longTermKey = BluetoothSmpCodec.parseEncryptionInformation(encPdu);
                } finally {
                    wipe(encPdu);
                }
                byte[] cidPdu = mux.awaitSmpPdu(
                        BluetoothSmpCodec.CENTRAL_IDENTIFICATION,
                        responseTimeoutMs,
                        "SMP Responder Central Identification",
                        false);
                BluetoothSmpCodec.CentralIdentification cid = null;
                try {
                    cid = BluetoothSmpCodec.parseCentralIdentification(cidPdu);
                    responderEdiv = cid.ediv;
                    byte[] capturedRand = cid.rand();
                    try {
                        System.arraycopy(capturedRand, 0, responderRand, 0, RAND_LENGTH);
                    } finally {
                        wipe(capturedRand);
                    }
                } finally {
                    if (cid != null) {
                        cid.destroy();
                    }
                    wipe(cidPdu);
                }
            }

            if ((respDist & BluetoothSmpCodec.KEY_DISTRIBUTION_IDENTITY) != 0) {
                byte[] irkPdu = mux.awaitSmpPdu(
                        BluetoothSmpCodec.IDENTITY_INFORMATION,
                        responseTimeoutMs,
                        "SMP Responder Identity Information",
                        false);
                try {
                    peerIrk = BluetoothSmpCodec.parseIdentityInformation(irkPdu);
                } finally {
                    wipe(irkPdu);
                }
                byte[] idAddrPdu = mux.awaitSmpPdu(
                        BluetoothSmpCodec.IDENTITY_ADDRESS_INFORMATION,
                        responseTimeoutMs,
                        "SMP Responder Identity Address",
                        false);
                try {
                    BluetoothSmpCodec.IdentityAddress peerIdentity =
                            BluetoothSmpCodec.parseIdentityAddressInformation(idAddrPdu);
                    peerIdentityAddrType = peerIdentity.addressType;
                    peerIdentityAddr = peerIdentity.address;
                } finally {
                    wipe(idAddrPdu);
                }
            }

            if ((respDist & BluetoothSmpCodec.KEY_DISTRIBUTION_SIGNING) != 0) {
                byte[] signPdu = mux.awaitSmpPdu(
                        BluetoothSmpCodec.SIGNING_INFORMATION,
                        responseTimeoutMs,
                        "SMP Responder Signing Information",
                        false);
                byte[] peerCsrk = null;
                try {
                    peerCsrk = BluetoothSmpCodec.parseSigningInformation(signPdu);
                } finally {
                    wipe(peerCsrk);
                    wipe(signPdu);
                }
            }

            // Fail-closed persistence BEFORE distributing local keys
            boolean bondPersisted = false;
            if (longTermKey != null && peerIrk != null && peerIdentityAddr != null) {
                bondSecretRecord = BluetoothBondSecretRecord.encode(
                        localId.addressType(),
                        localAddr,
                        peerAddrType,
                        peerAddr,
                        peerIdentityAddrType,
                        peerIdentityAddr,
                        longTermKey,
                        localIrk,
                        peerIrk,
                        encryptionKeySize);
                if (bondCommitHook != null) {
                    try {
                        bondCommitHook.persistBondOrFail(
                                bondSecretRecord.clone(),
                                effectiveSc,
                                responderEdiv,
                                responderRand.clone());
                        bondPersisted = true;
                    } catch (Exception persistenceFailure) {
                        sendPairingFailedBestEffort(
                                BluetoothSmpCodec.FAILURE_UNSPECIFIED_REASON,
                                "SMP BOND STORE ABORT");
                        throw persistenceFailure;
                    }
                }
            }

            // Distribute Initiator keys
            transport.checkStop();
            distributeInitiatorKeys(
                    effectiveSc,
                    response.initiatorKeyDistribution,
                    localId.addressType(),
                    localAddr,
                    localIrk);

            transport.log(String.format(
                    Locale.US,
                    "SMP BOND PASS: sc=%s keySize=%d bondPersisted=%s; secret bytes logged=false.",
                    effectiveSc,
                    encryptionKeySize,
                    bondPersisted));
            return new SmpBondResult(
                    effectiveSc,
                    encryptionKeySize,
                    responderEdiv,
                    responderRand,
                    longTermKey,
                    peerIrk,
                    peerIdentityAddrType,
                    peerIdentityAddr,
                    bondSecretRecord,
                    bondPersisted);
        } finally {
            wipe(peerAddr);
            wipe(preq);
            wipe(pres);
            wipe(localAddr);
            wipe(localTypedAddr);
            wipe(peerTypedAddr);
            wipe(longTermKey);
            wipe(responderRand);
            wipe(peerIrk);
            wipe(peerIdentityAddr);
            wipe(localIrk);
            wipe(bondSecretRecord);
        }
    }

    private byte[] performLeScAuthentication(
            SmpAuthMode mode,
            BluetoothSmpCodec.PairingFeatures request,
            BluetoothSmpCodec.PairingFeatures response,
            byte[] localTypedAddr,
            byte[] peerTypedAddr,
            PasskeyProvider passkeyProvider,
            NumericComparisonApprover numericApprover,
            long responseTimeoutMs,
            long userInputTimeoutMs) throws Exception {
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
            mux.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingPublicKey(localPubKey),
                    "SMP PAIRING PUBLIC KEY");
            peerPubKeyPdu = mux.awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_PUBLIC_KEY,
                    responseTimeoutMs,
                    "SMP Pairing Public Key",
                    false);
            peerPubKey = BluetoothSmpCodec.parsePairingPublicKey(peerPubKeyPdu);
            peerPubX = Arrays.copyOfRange(peerPubKey, 0, BleSecureConnectionsCrypto.P256_VALUE_LENGTH);
            try {
                dhKey = localEc.calculateDhKey(peerPubKey);
            } catch (IllegalArgumentException invalidKey) {
                sendPairingFailedBestEffort(FAILURE_INVALID_PARAMETERS, "SMP INVALID PUBLIC KEY");
                throw new HostException("Peer SMP P-256 public key is invalid", invalidKey);
            }

            if (mode instanceof SmpAuthMode.LeScOob oob) {
                finalNa = new byte[BleSecureConnectionsCrypto.AES_VALUE_LENGTH];
                random.nextBytes(finalNa);
                mux.sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec.pairingRandom(finalNa),
                        "SMP SC OOB PAIRING RANDOM");
                byte[] nbPdu = mux.awaitSmpPdu(
                        BluetoothSmpCodec.PAIRING_RANDOM,
                        responseTimeoutMs,
                        "SMP SC OOB Pairing Random",
                        false);
                try {
                    finalNb = BluetoothSmpCodec.parsePairingRandom(nbPdu);
                } finally {
                    wipe(nbPdu);
                }
                rb = oob.opticalOobKey16();
                // BT Core Vol 3 Part H Table 2.6: one-way OOB (resp.oob == 0) uses ra = 0[16];
                // mutual OOB (resp.oob == 1) uses ra = rb = opticalOobKey16.
                ra = (response.oobDataFlag == BluetoothSmpCodec.OOB_DATA_PRESENT)
                        ? oob.opticalOobKey16()
                        : new byte[BleSecureConnectionsCrypto.AES_VALUE_LENGTH];
            } else if (mode instanceof SmpAuthMode.LeScNumericComparison) {
                if (numericApprover == null) {
                    throw new HostException("NumericComparisonApprover is required for LE SC Numeric Comparison");
                }
                byte[] cbPdu = mux.awaitSmpPdu(
                        BluetoothSmpCodec.PAIRING_CONFIRM,
                        responseTimeoutMs,
                        "SMP Numeric Comparison Confirm",
                        false);
                byte[] cb = null;
                byte[] expectedCb = null;
                byte[] nbPdu = null;
                try {
                    cb = BluetoothSmpCodec.parsePairingConfirm(cbPdu);
                    finalNa = new byte[BleSecureConnectionsCrypto.AES_VALUE_LENGTH];
                    random.nextBytes(finalNa);
                    mux.sendL2cap(
                            BluetoothSmpCodec.FIXED_CID,
                            BluetoothSmpCodec.pairingRandom(finalNa),
                            "SMP NC PAIRING RANDOM");
                    nbPdu = mux.awaitSmpPdu(
                            BluetoothSmpCodec.PAIRING_RANDOM,
                            responseTimeoutMs,
                            "SMP Numeric Comparison Random",
                            false);
                    finalNb = BluetoothSmpCodec.parsePairingRandom(nbPdu);
                    expectedCb = BleSecureConnectionsCrypto.f4(peerPubX, localPubX, finalNb, 0);
                    if (!MessageDigest.isEqual(cb, expectedCb)) {
                        sendPairingFailedBestEffort(
                                BluetoothSmpCodec.FAILURE_CONFIRM_VALUE,
                                "SMP NC CONFIRM MISMATCH");
                        throw new HostException("Watch LE-SC Numeric Comparison confirm value mismatch");
                    }
                    int code = BleSecureConnectionsCrypto.g2NumericComparison(
                            localPubX, peerPubX, finalNa, finalNb);
                    boolean approved;
                    try {
                        approved = numericApprover.confirmSixDigitCode(code, userInputTimeoutMs);
                    } catch (Exception approverFailure) {
                        sendPairingFailedBestEffort(
                                BluetoothSmpCodec.FAILURE_NUMERIC_COMPARISON_FAILED,
                                "SMP NC APPROVER FAILURE");
                        throw approverFailure;
                    }
                    mux.transport().checkStop();
                    if (!approved) {
                        sendPairingFailedBestEffort(
                                BluetoothSmpCodec.FAILURE_NUMERIC_COMPARISON_FAILED,
                                "SMP NC USER REJECTED");
                        throw new HostException("User rejected SMP Numeric Comparison");
                    }
                } finally {
                    wipe(cbPdu);
                    wipe(cb);
                    wipe(expectedCb);
                    wipe(nbPdu);
                }
                ra = new byte[BleSecureConnectionsCrypto.AES_VALUE_LENGTH];
                rb = new byte[BleSecureConnectionsCrypto.AES_VALUE_LENGTH];
            } else if (mode instanceof SmpAuthMode.LeScPasskey) {
                int passkey = obtainSixDigitPasskey(passkeyProvider, userInputTimeoutMs);
                byte[][] finalNonces = runScPasskeyRounds(localPubX, peerPubX, passkey, responseTimeoutMs);
                finalNa = finalNonces[0];
                finalNb = finalNonces[1];
                ra = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
                rb = BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
            } else {
                throw new HostException("Unsupported LE SC auth mode: " + mode.getClass().getSimpleName());
            }

            f5 = BleSecureConnectionsCrypto.f5(dhKey, finalNa, finalNb, localTypedAddr, peerTypedAddr);
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
                    macKey, finalNa, finalNb, rb, localIoCap, localTypedAddr, peerTypedAddr);
            mux.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingDhKeyCheck(ea),
                    "SMP PAIRING DHKEY CHECK");

            ebPdu = mux.awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_DHKEY_CHECK,
                    responseTimeoutMs,
                    "SMP Pairing DHKey Check",
                    false);
            eb = BluetoothSmpCodec.parsePairingDhKeyCheck(ebPdu);
            expectedEb = BleSecureConnectionsCrypto.f6(
                    macKey, finalNb, finalNa, ra, peerIoCap, peerTypedAddr, localTypedAddr);
            if (!MessageDigest.isEqual(eb, expectedEb)) {
                sendPairingFailedBestEffort(
                        BluetoothSmpCodec.FAILURE_DHKEY_CHECK_FAILED,
                        "SMP DHKEY CHECK MISMATCH");
                throw new HostException("Watch LE-SC DHKey Check verification failed");
            }
            return f5.longTermKey();
        } finally {
            localEc.destroy();
            if (f5 != null) {
                f5.destroy();
            }
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

    private byte[][] runScPasskeyRounds(
            byte[] localPubX, byte[] peerPubX, int passkey, long responseTimeoutMs) throws Exception {
        byte[] finalNa = null;
        byte[] finalNb = null;
        boolean completed = false;
        try {
            for (int round = 0; round < PASSKEY_ROUNDS; round++) {
                int z = BleSecureConnectionsCrypto.passkeyRoundZ(passkey, round);
                byte[] na = new byte[BleSecureConnectionsCrypto.AES_VALUE_LENGTH];
                random.nextBytes(na);
                byte[] ca = BleSecureConnectionsCrypto.f4(localPubX, peerPubX, na, z);
                byte[] cbPdu = null;
                byte[] cb = null;
                byte[] nbPdu = null;
                byte[] nb = null;
                byte[] expectedCb = null;
                try {
                    mux.sendL2cap(
                            BluetoothSmpCodec.FIXED_CID,
                            BluetoothSmpCodec.pairingConfirm(ca),
                            "SMP PASSKEY CONFIRM r=" + round);
                    cbPdu = mux.awaitSmpPdu(
                            BluetoothSmpCodec.PAIRING_CONFIRM,
                            responseTimeoutMs,
                            "SMP Passkey Confirm round " + round,
                            false);
                    cb = BluetoothSmpCodec.parsePairingConfirm(cbPdu);

                    mux.sendL2cap(
                            BluetoothSmpCodec.FIXED_CID,
                            BluetoothSmpCodec.pairingRandom(na),
                            "SMP PASSKEY RANDOM r=" + round);
                    nbPdu = mux.awaitSmpPdu(
                            BluetoothSmpCodec.PAIRING_RANDOM,
                            responseTimeoutMs,
                            "SMP Passkey Random round " + round,
                            false);
                    nb = BluetoothSmpCodec.parsePairingRandom(nbPdu);

                    expectedCb = BleSecureConnectionsCrypto.f4(peerPubX, localPubX, nb, z);
                    if (!MessageDigest.isEqual(cb, expectedCb)) {
                        sendPairingFailedBestEffort(
                                BluetoothSmpCodec.FAILURE_CONFIRM_VALUE,
                                "SMP PASSKEY CONFIRM MISMATCH");
                        throw new HostException("Watch SMP passkey commitment mismatch at round " + round);
                    }
                    if (round == PASSKEY_ROUNDS - 1) {
                        finalNa = na.clone();
                        finalNb = nb.clone();
                    }
                } finally {
                    wipe(na);
                    wipe(ca);
                    wipe(cbPdu);
                    wipe(cb);
                    wipe(nbPdu);
                    wipe(nb);
                    wipe(expectedCb);
                }
            }
            completed = true;
            return new byte[][]{finalNa, finalNb};
        } finally {
            if (!completed) {
                wipe(finalNa);
                wipe(finalNb);
            }
        }
    }

    private byte[] performLeLegacyAuthentication(
            SmpAuthMode mode,
            byte[] preq,
            byte[] pres,
            BluetoothSmpCodec.PairingFeatures response,
            int localAddrType,
            byte[] localAddr,
            int peerAddrType,
            byte[] peerAddr,
            PasskeyProvider passkeyProvider,
            long responseTimeoutMs,
            long userInputTimeoutMs) throws Exception {
        byte[] tk = null;
        byte[] mRand = new byte[BleSecureConnectionsCrypto.AES_VALUE_LENGTH];
        byte[] mConfirm = null;
        byte[] sConfirmPdu = null;
        byte[] sConfirm = null;
        byte[] sRandPdu = null;
        byte[] sRand = null;
        byte[] expectedSConfirm = null;
        try {
            tk = resolveLegacyTk(mode, response, passkeyProvider, userInputTimeoutMs);
            random.nextBytes(mRand);
            mConfirm = BleSecureConnectionsCrypto.legacyC1(
                    tk, mRand, preq, pres, localAddrType, localAddr, peerAddrType, peerAddr);
            mux.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingConfirm(mConfirm),
                    "SMP LEGACY CONFIRM");
            sConfirmPdu = mux.awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_CONFIRM,
                    responseTimeoutMs,
                    "SMP Legacy Confirm",
                    false);
            sConfirm = BluetoothSmpCodec.parsePairingConfirm(sConfirmPdu);

            mux.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec.pairingRandom(mRand),
                    "SMP LEGACY RANDOM");
            sRandPdu = mux.awaitSmpPdu(
                    BluetoothSmpCodec.PAIRING_RANDOM,
                    responseTimeoutMs,
                    "SMP Legacy Random",
                    false);
            sRand = BluetoothSmpCodec.parsePairingRandom(sRandPdu);

            expectedSConfirm = BleSecureConnectionsCrypto.legacyC1(
                    tk, sRand, preq, pres, localAddrType, localAddr, peerAddrType, peerAddr);
            if (!MessageDigest.isEqual(sConfirm, expectedSConfirm)) {
                sendPairingFailedBestEffort(
                        BluetoothSmpCodec.FAILURE_CONFIRM_VALUE,
                        "SMP LEGACY CONFIRM MISMATCH");
                throw new HostException("Watch LE Legacy SMP confirm value mismatch");
            }
            return BleSecureConnectionsCrypto.legacyS1(tk, sRand, mRand);
        } finally {
            wipe(tk);
            wipe(mRand);
            wipe(mConfirm);
            wipe(sConfirmPdu);
            wipe(sConfirm);
            wipe(sRandPdu);
            wipe(sRand);
            wipe(expectedSConfirm);
        }
    }

    private byte[] resolveLegacyTk(
            SmpAuthMode mode,
            BluetoothSmpCodec.PairingFeatures response,
            PasskeyProvider passkeyProvider,
            long userInputTimeoutMs) throws Exception {
        if (mode instanceof SmpAuthMode.LeScOob oob) {
            if (response.oobDataFlag == BluetoothSmpCodec.OOB_DATA_PRESENT) {
                return oob.opticalOobKey16();
            }
            if (passkeyProvider != null) {
                int passkey = obtainSixDigitPasskey(passkeyProvider, userInputTimeoutMs);
                return BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
            }
            sendPairingFailedBestEffort(FAILURE_OOB_NOT_AVAILABLE, "SMP LEGACY OOB UNAVAILABLE");
            throw new HostException("Watch cleared SC and OOB flags with no PasskeyProvider fallback");
        }
        if (mode instanceof SmpAuthMode.LeLegacy legacy) {
            byte[] key = legacy.optionalOobKey16();
            if (key != null && response.oobDataFlag == BluetoothSmpCodec.OOB_DATA_PRESENT) {
                return key;
            }
            if (key != null) {
                wipe(key);
                if (passkeyProvider == null) {
                    sendPairingFailedBestEffort(FAILURE_OOB_NOT_AVAILABLE, "SMP LEGACY OOB UNAVAILABLE");
                    throw new HostException("Watch cleared OOB flag and no PasskeyProvider is available");
                }
            }
            int passkey = obtainSixDigitPasskey(passkeyProvider, userInputTimeoutMs);
            return BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
        }
        if (mode instanceof SmpAuthMode.LeScPasskey) {
            int passkey = obtainSixDigitPasskey(passkeyProvider, userInputTimeoutMs);
            return BleSecureConnectionsCrypto.passkeyToR128LittleEndian(passkey);
        }
        throw new HostException("Unsupported LE Legacy auth mode: " + mode.getClass().getSimpleName());
    }

    private int obtainSixDigitPasskey(PasskeyProvider passkeyProvider, long userInputTimeoutMs) throws Exception {
        if (passkeyProvider == null) {
            throw new HostException("PasskeyProvider is required for SMP passkey pairing");
        }
        final int passkey;
        try {
            passkey = passkeyProvider.awaitSixDigitPasskey(userInputTimeoutMs);
        } catch (Exception providerFailure) {
            sendPairingFailedBestEffort(
                    BluetoothSmpCodec.FAILURE_PASSKEY_ENTRY_FAILED,
                    "SMP PASSKEY ENTRY FAILED");
            throw providerFailure;
        }
        mux.transport().checkStop();
        if (passkey < 0 || passkey > 999_999) {
            sendPairingFailedBestEffort(
                    BluetoothSmpCodec.FAILURE_PASSKEY_ENTRY_FAILED,
                    "SMP INVALID PASSKEY RANGE");
            throw new HostException("Passkey must be in range 0..999999");
        }
        return passkey;
    }

    private void distributeInitiatorKeys(
            boolean effectiveSc,
            int initDist,
            int localAddrType,
            byte[] localAddr,
            byte[] localIrk) throws Exception {
        if (!effectiveSc && (initDist & BluetoothSmpCodec.KEY_DISTRIBUTION_ENCRYPTION) != 0) {
            byte[] localLtk = new byte[16];
            byte[] localRand = new byte[RAND_LENGTH];
            byte[] encInfoPdu = null;
            byte[] centralIdPdu = null;
            random.nextBytes(localLtk);
            random.nextBytes(localRand);
            int localEdiv = random.nextInt(0x10000);
            try {
                encInfoPdu = BluetoothSmpCodec.encryptionInformation(localLtk);
                centralIdPdu = BluetoothSmpCodec.centralIdentification(localEdiv, localRand);
                mux.sendL2cap(BluetoothSmpCodec.FIXED_CID, encInfoPdu, "SMP LOCAL ENC INFO");
                mux.sendL2cap(BluetoothSmpCodec.FIXED_CID, centralIdPdu, "SMP LOCAL CENTRAL ID");
            } finally {
                wipe(localLtk);
                wipe(localRand);
                wipe(encInfoPdu);
                wipe(centralIdPdu);
            }
        }
        if ((initDist & BluetoothSmpCodec.KEY_DISTRIBUTION_IDENTITY) != 0) {
            byte[] idInfoPdu = BluetoothSmpCodec.identityInformation(localIrk);
            byte[] idAddrPdu = BluetoothSmpCodec.identityAddressInformation(localAddrType, localAddr);
            try {
                mux.sendL2cap(BluetoothSmpCodec.FIXED_CID, idInfoPdu, "SMP LOCAL IRK");
                mux.sendL2cap(BluetoothSmpCodec.FIXED_CID, idAddrPdu, "SMP LOCAL ID ADDR");
            } finally {
                wipe(idInfoPdu);
                wipe(idAddrPdu);
            }
        }
        if ((initDist & BluetoothSmpCodec.KEY_DISTRIBUTION_SIGNING) != 0) {
            byte[] localCsrk = new byte[16];
            byte[] signPdu = null;
            random.nextBytes(localCsrk);
            try {
                signPdu = BluetoothSmpCodec.signingInformation(localCsrk);
                mux.sendL2cap(BluetoothSmpCodec.FIXED_CID, signPdu, "SMP LOCAL SIGNING INFO");
            } finally {
                wipe(localCsrk);
                wipe(signPdu);
            }
        }
    }

    private void sendPairingFailedBestEffort(int reason, String label) {
        byte[] failed = BluetoothSmpCodec.pairingFailed(reason);
        try {
            mux.sendL2cap(BluetoothSmpCodec.FIXED_CID, failed, label);
        } catch (Exception ignored) {
            // Preserve the primary protocol/persistence failure.
        } finally {
            wipe(failed);
        }
    }

    private static BluetoothSmpCodec.PairingFeatures buildPairingRequest(SmpAuthMode mode) {
        if (mode instanceof SmpAuthMode.LeScOob) {
            return BluetoothSmpCodec.createLegacyOobPairingRequest();
        }
        if (mode instanceof SmpAuthMode.LeScPasskey) {
            return BluetoothSmpCodec.createLegacyPasskeyPairingRequest();
        }
        if (mode instanceof SmpAuthMode.LeScNumericComparison) {
            return BluetoothSmpCodec.createNumericComparisonPairingRequest(false);
        }
        if (mode instanceof SmpAuthMode.LeLegacy legacy) {
            byte[] key = legacy.optionalOobKey16();
            if (key != null) {
                wipe(key);
                return BluetoothSmpCodec.createLegacyNonScOobPairingRequest();
            }
            return BluetoothSmpCodec.createLegacyNonScPasskeyPairingRequest();
        }
        throw new IllegalArgumentException("Unknown SmpAuthMode: " + mode);
    }

    private BluetoothSmpCodec.PairingFeatures parseAndValidatePairingResponse(byte[] pdu) throws HostException {
        if (pdu == null || pdu.length != 7 || (pdu[0] & 0xFF) != BluetoothSmpCodec.PAIRING_RESPONSE) {
            sendPairingFailedBestEffort(FAILURE_INVALID_PARAMETERS, "SMP INVALID PAIRING RESPONSE");
            throw new HostException("Watch Pairing Response PDU has invalid opcode or length");
        }
        BluetoothSmpCodec.PairingFeatures response = new BluetoothSmpCodec.PairingFeatures(
                pdu[0] & 0xFF,
                pdu[1] & 0xFF,
                pdu[2] & 0xFF,
                pdu[3] & 0xFF,
                pdu[4] & 0xFF,
                pdu[5] & 0xFF,
                pdu[6] & 0xFF);
        if (response.ioCapability < 0
                || response.ioCapability > 4
                || (response.oobDataFlag != 0 && response.oobDataFlag != 1)
                || (response.authenticationRequirements & 0xC2) != 0
                || (response.authenticationRequirements & 0x03) != BluetoothSmpCodec.AUTH_BONDING
                || response.maximumEncryptionKeySize < 7
                || response.maximumEncryptionKeySize > 16
                || (response.initiatorKeyDistribution & ~0x0F) != 0
                || (response.responderKeyDistribution & ~0x0F) != 0) {
            sendPairingFailedBestEffort(FAILURE_INVALID_PARAMETERS, "SMP INVALID PAIRING RESPONSE");
            throw new HostException("Watch Pairing Response has invalid feature values");
        }
        return response;
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
}
