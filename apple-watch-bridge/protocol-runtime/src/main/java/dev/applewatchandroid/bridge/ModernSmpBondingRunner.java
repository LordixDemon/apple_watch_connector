package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Modern BLE LE Secure Connections OOB SMP bonding over fixed CID {@code 0x0006}.
 */
final class ModernSmpBondingRunner {
    private final HalTransportSession session;

    ModernSmpBondingRunner(HalTransportSession session) {
        this.session = session;
    }
    void completeLeSecureConnectionsSmp(
            BleSecureConnectionsCrypto.LocalOobMaterial
                    localOob,
            ApplePairingNotifyPayloads.PeerBatch
                    peerBatch,
            PairingSessionRecord pairingSessionRecord)
            throws Exception {
        if (localOob == null
                || peerBatch == null
                || pairingSessionRecord == null
                || session.localIdentity == null
                || session.peerConnectionAddress == null
                || session.peerConnectionAddress.length != 6
                || (session.peerConnectionAddressType != 0
                && session.peerConnectionAddressType != 1)) {
            throw new HostException(
                    "SMP address/OOB state is incomplete");
        }

        AppleLeScOobData localOobData = null;
        AppleLeScOobData peerOobData = null;
        BleSecureConnectionsCrypto.F5Result f5Result =
                null;
        byte[] localOobSerialized = null;
        byte[] localIdentityAddress = null;
        byte[] peerOobSerialized = null;
        byte[] localPublicKey = null;
        byte[] peerPublicKey = null;
        byte[] peerPublicX = null;
        byte[] peerOobRandomizer = null;
        byte[] peerOobConfirmation = null;
        byte[] expectedPeerOobConfirmation = null;
        byte[] dhKey = null;
        byte[] localNonce = null;
        byte[] peerNonce = null;
        byte[] localTypedAddress = null;
        byte[] peerTypedAddress = null;
        byte[] macKey = null;
        byte[] longTermKey = null;
        byte[] localOobRandomizer = null;
        byte[] localIoCapability = null;
        byte[] peerIoCapability = null;
        byte[] localDhKeyCheck = null;
        byte[] peerDhKeyCheck = null;
        byte[] expectedPeerDhKeyCheck = null;
        byte[] peerIdentityResolvingKey = null;
        byte[] localIdentityResolvingKey = null;
        byte[] bondSecretRecord = null;
        byte[] responsePdu = null;
        byte[] peerPublicKeyPdu = null;
        byte[] peerNoncePdu = null;
        byte[] peerDhKeyCheckPdu = null;
        byte[] peerIdentityPdu = null;
        byte[] peerIdentityAddressPdu = null;
        PairingSessionRecord bondedSessionRecord =
                null;
        try {
            localIdentityAddress = session.localIdentity.address();
            localOobSerialized =
                    localOob.appleOobData();
            localOobData =
                    AppleLeScOobData.parse(
                            localOobSerialized);
            peerOobSerialized =
                    peerBatch.appleOobData();
            peerOobData =
                    AppleLeScOobData.parse(
                            peerOobSerialized);

            BluetoothSmpCodec.PairingFeatures request =
                    BluetoothSmpCodec
                            .createPairingRequest();
            log(String.format(
                    Locale.US,
                    "SMP TX: Pairing Request io=0x%02X "
                            + "oob=%d auth=0x%02X keySize=%d "
                            + "initiatorKeys=0x%02X "
                            + "responderKeys=0x%02X.",
                    request.ioCapability,
                    request.oobDataFlag,
                    request.authenticationRequirements,
                    request.maximumEncryptionKeySize,
                    request.initiatorKeyDistribution,
                    request.responderKeyDistribution));
            session.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    request.encode(),
                    "SMP PAIRING REQUEST");
            responsePdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_RESPONSE,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing Response");
            BluetoothSmpCodec.PairingFeatures response =
                    BluetoothSmpCodec
                            .parsePairingResponse(
                                    responsePdu);
            log(String.format(
                    Locale.US,
                    "SMP RX PASS: Pairing Response io=0x%02X "
                            + "oob=%d auth=0x%02X keySize=%d "
                            + "initiatorKeys=0x%02X "
                            + "responderKeys=0x%02X.",
                    response.ioCapability,
                    response.oobDataFlag,
                    response.authenticationRequirements,
                    response.maximumEncryptionKeySize,
                    response.initiatorKeyDistribution,
                    response.responderKeyDistribution));
            if (response.oobDataFlag
                    != BluetoothSmpCodec
                    .OOB_DATA_PRESENT
                    || (response
                    .authenticationRequirements
                    & BluetoothSmpCodec
                    .AUTH_SECURE_CONNECTIONS) == 0
                    || (response
                    .authenticationRequirements
                    & BluetoothSmpCodec
                    .AUTH_BONDING) == 0
                    || response.maximumEncryptionKeySize
                    != BluetoothSmpCodec
                    .MAXIMUM_ENCRYPTION_KEY_SIZE
                    || (response
                    .initiatorKeyDistribution
                    & BluetoothSmpCodec
                    .KEY_DISTRIBUTION_IDENTITY) == 0
                    || (response
                    .responderKeyDistribution
                    & BluetoothSmpCodec
                    .KEY_DISTRIBUTION_IDENTITY) == 0) {
                throw new HostException(
                        "Watch did not negotiate the required "
                                + "mutual-OOB SC identity bond");
            }

            localPublicKey =
                    localOob.publicKeyForSmp();
            session.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .pairingPublicKey(
                                    localPublicKey),
                    "SMP PAIRING PUBLIC KEY");
            peerPublicKeyPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_PUBLIC_KEY,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing Public Key");
            peerPublicKey =
                    BluetoothSmpCodec
                            .parsePairingPublicKey(
                                    peerPublicKeyPdu);
            peerPublicX =
                    Arrays.copyOfRange(
                            peerPublicKey,
                            0,
                            BleSecureConnectionsCrypto
                                    .P256_VALUE_LENGTH);
            peerOobRandomizer =
                    peerOobData.randomizerForAndroid();
            peerOobConfirmation =
                    peerOobData.confirmationForAndroid();
            expectedPeerOobConfirmation =
                    BleSecureConnectionsCrypto.f4(
                            peerPublicX,
                            peerPublicX,
                            peerOobRandomizer,
                            0);
            if (!MessageDigest.isEqual(
                    peerOobConfirmation,
                    expectedPeerOobConfirmation)) {
                session.sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec
                                .pairingFailed(
                                        BluetoothSmpCodec
                                                .FAILURE_CONFIRM_VALUE),
                        "SMP PEER OOB CONFIRMATION FAILURE");
                throw new HostException(
                        "Watch LE-SC OOB commitment "
                                + "does not match its SMP public key");
            }
            log("SMP OOB COMMITMENT PASS: Watch C547 "
                    + "confirmation matches Watch SMP P-256 "
                    + "public key; OOB/public-key bytes "
                    + "logged=false.");

            dhKey =
                    localOob.calculateDhKey(
                            peerPublicKey);
            localNonce =
                    new byte[
                            BleSecureConnectionsCrypto
                                    .AES_VALUE_LENGTH];
            new SecureRandom().nextBytes(localNonce);
            session.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .pairingRandom(
                                    localNonce),
                    "SMP PAIRING RANDOM");
            peerNoncePdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_RANDOM,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing Random");
            peerNonce =
                    BluetoothSmpCodec
                            .parsePairingRandom(
                                    peerNoncePdu);
            localTypedAddress =
                    HalTransportSession.typedAddress(
                            localIdentityAddress,
                            session.localIdentity.addressType());
            peerTypedAddress =
                    HalTransportSession.typedAddress(
                            session.peerConnectionAddress,
                            session.peerConnectionAddressType);
            f5Result =
                    BleSecureConnectionsCrypto.f5(
                            dhKey,
                            localNonce,
                            peerNonce,
                            localTypedAddress,
                            peerTypedAddress);
            macKey = f5Result.macKey();
            longTermKey =
                    f5Result.longTermKey();
            session.activeLongTermKey = longTermKey.clone();
            localOobRandomizer =
                    localOobData.randomizerForAndroid();
            localIoCapability =
                    new byte[]{
                            (byte) request.ioCapability,
                            (byte) request.oobDataFlag,
                            (byte) request
                                    .authenticationRequirements
                    };
            peerIoCapability =
                    new byte[]{
                            (byte) response.ioCapability,
                            (byte) response.oobDataFlag,
                            (byte) response
                                    .authenticationRequirements
                    };
            localDhKeyCheck =
                    BleSecureConnectionsCrypto.f6(
                            macKey,
                            localNonce,
                            peerNonce,
                            peerOobRandomizer,
                            localIoCapability,
                            localTypedAddress,
                            peerTypedAddress);
            session.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .pairingDhKeyCheck(
                                    localDhKeyCheck),
                    "SMP PAIRING DHKEY CHECK");
            peerDhKeyCheckPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .PAIRING_DHKEY_CHECK,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Pairing DHKey Check");
            peerDhKeyCheck =
                    BluetoothSmpCodec
                            .parsePairingDhKeyCheck(
                                    peerDhKeyCheckPdu);
            expectedPeerDhKeyCheck =
                    BleSecureConnectionsCrypto.f6(
                            macKey,
                            peerNonce,
                            localNonce,
                            localOobRandomizer,
                            peerIoCapability,
                            peerTypedAddress,
                            localTypedAddress);
            if (!MessageDigest.isEqual(
                    peerDhKeyCheck,
                    expectedPeerDhKeyCheck)) {
                throw new HostException(
                        "Watch LE-SC responder DHKey Check "
                                + "authentication failed");
            }
            log("SMP DHKEY CHECK PASS: fresh Na/Nb and "
                    + "both OOB randomizers authenticated; "
                    + "DHKey/MacKey/LTK/check bytes "
                    + "logged=false.");

            session.startLeEncryption(longTermKey);
            log("SMP LINK ENCRYPTION PASS: controller "
                    + "accepted SC LTK with RAND=0/EDIV=0; "
                    + "LTK bytes logged=false.");

            peerIdentityPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .IDENTITY_INFORMATION,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Identity Information");
            peerIdentityResolvingKey =
                    BluetoothSmpCodec
                            .parseIdentityInformation(
                                    peerIdentityPdu);
            peerIdentityAddressPdu =
                    awaitSmpPdu(
                            BluetoothSmpCodec
                                    .IDENTITY_ADDRESS_INFORMATION,
                            SMP_RESPONSE_TIMEOUT_MS,
                            "SMP Identity Address Information");
            BluetoothSmpCodec.IdentityAddress
                    peerIdentity =
                    BluetoothSmpCodec
                            .parseIdentityAddressInformation(
                                    peerIdentityAddressPdu);
            localIdentityResolvingKey =
                    session.localIdentity.irk();

            bondSecretRecord =
                    BluetoothBondSecretRecord.encode(
                            session.localIdentity.addressType(),
                            localIdentityAddress,
                            session.peerConnectionAddressType,
                            session.peerConnectionAddress,
                            peerIdentity.addressType,
                            peerIdentity.address,
                            longTermKey,
                            localIdentityResolvingKey,
                            peerIdentityResolvingKey,
                            response
                                    .maximumEncryptionKeySize);
            try {
                bondedSessionRecord =
                        pairingSessionRecord
                                .withBluetoothBond(
                                        bondSecretRecord);
                session.persistPairingSessionOrFail(
                        bondedSessionRecord);
                session.persistBondSecretOrFail(
                        bondSecretRecord);
            } catch (Exception error) {
                session.sendL2cap(
                        BluetoothSmpCodec.FIXED_CID,
                        BluetoothSmpCodec
                                .pairingFailed(
                                        BluetoothSmpCodec
                                                .FAILURE_UNSPECIFIED_REASON),
                        "SMP BOND STORE FAILURE ABORT");
                throw error;
            }
            log("SMP SESSION COMMIT PASS: durable record now "
                    + "contains the Bluetooth bond and omits both "
                    + "pending C547 objects before local identity "
                    + "distribution; pending IDS auth remains sealed.");

            session.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .identityInformation(
                                    localIdentityResolvingKey),
                    "SMP LOCAL IDENTITY INFORMATION");
            session.sendL2cap(
                    BluetoothSmpCodec.FIXED_CID,
                    BluetoothSmpCodec
                            .identityAddressInformation(
                                    session.localIdentity.addressType(),
                                    localIdentityAddress),
                    "SMP LOCAL IDENTITY ADDRESS INFORMATION");
            log("SMP LOCAL IDENTITY TX PASS: both local identity "
                    + "PDUs queued on the encrypted ACL; negotiated "
                    + "key distribution=0x03/0x03; Watch completion "
                    + "is not assumed until the post-SMP transition; "
                    + "IRK/LTK/address bytes logged=false.");
            if (session.activePairingSessionRecord != null) {
                session.activePairingSessionRecord.destroy();
                session.activePairingSessionRecord = null;
            }
            session.activePairingSessionRecord =
                    bondedSessionRecord;
            bondedSessionRecord = null;
            session.normalLink.modernRegistrationDeadlineMs =
                    BridgeClock.elapsedRealtime()
                            + MODERN_REGISTRATION_TIMEOUT_MS;
            log("MODERN REGISTRATION WATCHDOG: local deadline="
                    + MODERN_REGISTRATION_TIMEOUT_MS
                    + " ms from completed SMP bond; same encrypted "
                    + "ACL retained.");
        } finally {
            if (bondedSessionRecord != null) {
                bondedSessionRecord.destroy();
            }
            if (f5Result != null) {
                f5Result.destroy();
            }
            if (localOobData != null) {
                localOobData.destroy();
            }
            if (peerOobData != null) {
                peerOobData.destroy();
            }
            HalTransportSession.wipe(localOobSerialized);
            HalTransportSession.wipe(localIdentityAddress);
            HalTransportSession.wipe(peerOobSerialized);
            HalTransportSession.wipe(localPublicKey);
            HalTransportSession.wipe(peerPublicKey);
            HalTransportSession.wipe(peerPublicX);
            HalTransportSession.wipe(peerOobRandomizer);
            HalTransportSession.wipe(peerOobConfirmation);
            HalTransportSession.wipe(expectedPeerOobConfirmation);
            HalTransportSession.wipe(dhKey);
            HalTransportSession.wipe(localNonce);
            HalTransportSession.wipe(peerNonce);
            HalTransportSession.wipe(localTypedAddress);
            HalTransportSession.wipe(peerTypedAddress);
            HalTransportSession.wipe(macKey);
            HalTransportSession.wipe(longTermKey);
            HalTransportSession.wipe(localOobRandomizer);
            HalTransportSession.wipe(localIoCapability);
            HalTransportSession.wipe(peerIoCapability);
            HalTransportSession.wipe(localDhKeyCheck);
            HalTransportSession.wipe(peerDhKeyCheck);
            HalTransportSession.wipe(expectedPeerDhKeyCheck);
            HalTransportSession.wipe(peerIdentityResolvingKey);
            HalTransportSession.wipe(localIdentityResolvingKey);
            HalTransportSession.wipe(bondSecretRecord);
            HalTransportSession.wipe(responsePdu);
            HalTransportSession.wipe(peerPublicKeyPdu);
            HalTransportSession.wipe(peerNoncePdu);
            HalTransportSession.wipe(peerDhKeyCheckPdu);
            HalTransportSession.wipe(peerIdentityPdu);
            HalTransportSession.wipe(peerIdentityAddressPdu);
        }
    }

    byte[] awaitSmpPdu(
            int expectedOpcode,
            long timeoutMs,
            String label)
            throws Exception {
        long deadline =
                BridgeClock.elapsedRealtime() + timeoutMs;
        int securityRequests = 0;
        log("SMP WAIT BEGIN: " + label + "; " + session.pairingSmpDiagnostics());
        while (BridgeClock.elapsedRealtime() < deadline) {
            session.checkStop();
            Throwable callbackError = session.callbackFailure.get();
            if (callbackError != null) throw new HostException("Bluetooth controller transport failed", callbackError);
            byte[] payload = session.deferredSmpPdus.poll();
            if (payload == null) {
                HalTransportSession.Packet packet;
                try {
                    packet = session.nextPacket(deadline);
                } catch (TimeoutException complete) {
                    break;
                }
                try {
                    if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                        session.failIfPairingDisconnected(packet, label);
                        continue;
                    }
                    HciCodec.L2capPdu l2cap;
                    try {
                        l2cap = session.aclReassembler.accept(packet.bytes);
                    } catch (IllegalArgumentException error) {
                        log(label + " ACL DROP: " + error.getMessage());
                        continue;
                    }
                    if (l2cap == null) continue;
                    if (l2cap.connectionHandle != session.connectionHandle
                            || l2cap.destinationCid != BluetoothSmpCodec.FIXED_CID) {
                        if (l2cap.connectionHandle == session.connectionHandle) {
                            log(String.format(
                                    Locale.US,
                                    "SMP WAIT OTHER CID: cid=0x%04X bytes=%d; payload bytes logged=false.",
                                    l2cap.destinationCid,
                                    l2cap.payload.length));
                        }
                        HalTransportSession.wipe(l2cap.payload);
                        continue;
                    }
                    payload = l2cap.payload;
                    session.recordPairingSmpRx(payload, label);
                } finally { HalTransportSession.wipe(packet.bytes); }
            }
            try {
                if (expectedOpcode == BluetoothSmpCodec.PAIRING_RESPONSE
                        && payload.length > 0
                        && (payload[0] & 255) == BluetoothSmpCodec.SECURITY_REQUEST) {
                    final int authentication;
                    try {
                        authentication = BluetoothSmpCodec.parseSecurityRequest(payload);
                    } catch (IllegalArgumentException malformed) {
                        throw new HostException("Malformed SMP Security Request", malformed);
                    }
                    if (++securityRequests > 16) {
                        throw new HostException("Too many SMP Security Requests while awaiting " + label);
                    }
                    // The central's Pairing Request is already in flight. A peripheral
                    // Security Request is a trigger, not the feature response. Keep the
                    // original deadline and never start a second pairing procedure.
                    log(String.format(Locale.US,
                            "SMP SECURITY REQUEST RX: auth=0x%02X count=%d; "
                                    + "Pairing Request already submitted; resend=false; deadline unchanged.",
                            authentication, securityRequests));
                    continue;
                }
                return validateExpectedSmpPdu(payload, expectedOpcode, label);
            } finally { HalTransportSession.wipe(payload); }
        }
        throw new HostException(
                label
                        + " timed out after "
                        + timeoutMs
                        + " ms; securityRequests=" + securityRequests
                        + "; " + session.pairingSmpDiagnostics());
    }

    /** Preserve fixed-channel traffic that races the final authenticated IKE ACK. */
    HciCodec.L2capPdu awaitPairingTerminusPdu(long deadline, String phase) throws Exception {
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet = session.nextPacket(deadline);
            try {
                if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                    session.failIfPairingDisconnected(packet, phase);
                    continue;
                }
                HciCodec.L2capPdu l2cap;
                try {
                    l2cap = session.aclReassembler.accept(packet.bytes);
                } catch (IllegalArgumentException malformed) {
                    log(phase + " ACL DROP: " + malformed.getMessage());
                    continue;
                }
                if (session.retainPairingTerminusPdu(l2cap, phase)) return l2cap;
            } finally { HalTransportSession.wipe(packet.bytes); }
        }
        throw new TimeoutException("Pairing Terminus packet deadline elapsed");
    }

    byte[] validateExpectedSmpPdu(
            byte[] payload,
            int expectedOpcode,
            String label)
            throws HostException {
        int opcode;
        try {
            opcode =
                    BluetoothSmpCodec.opcode(
                            payload);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    label + " is malformed: "
                            + error.getMessage(),
                    error);
        }
        if (opcode
                == BluetoothSmpCodec.PAIRING_FAILED) {
            int reason =
                    BluetoothSmpCodec
                            .parsePairingFailure(
                                    payload);
            throw new HostException(String.format(
                    Locale.US,
                    "Watch returned SMP Pairing Failed "
                            + "reason=0x%02X (%s) while awaiting %s",
                    reason,
                    BluetoothSmpCodec.pairingFailureReasonName(reason),
                    label));
        }
        if (opcode != expectedOpcode) {
            throw new HostException(String.format(
                    Locale.US,
                    "Unexpected SMP opcode=0x%02X "
                            + "while awaiting %s",
                    opcode,
                    label));
        }
        log(String.format(
                Locale.US,
                "%s RX: opcode=0x%02X bytes=%d; "
                        + "payload bytes logged=false.",
                label,
                opcode,
                payload.length));
        return payload.clone();
    }
}
