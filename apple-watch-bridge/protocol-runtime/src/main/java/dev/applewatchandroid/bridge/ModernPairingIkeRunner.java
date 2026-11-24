package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Modern NetworkRelay pairing IKEv2 / SPAKE2+ / optical exchange over {@code terminusPairing}.
 */
final class ModernPairingIkeRunner {
    static final String PAIRING_FINAL_ACK_LABEL =
            "PAIRING PRIVATE-NOTIFIES WATCH-REQUEST ACK";
    static final int MAX_PAIRING_WATCH_REQUEST_RETRIES = 4;

    private final HalTransportSession session;
    OpticalPairingCode opticalPairing;
    LegacyWatchPairingCodec.LegacyOpticalCode legacyOpticalPairing;

    ModernPairingIkeRunner(HalTransportSession session) {
        this.session = session;
        this.controlChannel = new ModernControlIkeChannel(session, this);
    }

    void closeOpticalCodes() {
        if (opticalPairing != null) {
            opticalPairing.close();
            opticalPairing = null;
        }
        if (legacyOpticalPairing != null) {
            legacyOpticalPairing.close();
            legacyOpticalPairing = null;
        }
    }
    void configureOpticalPairing(byte[] payload) {
        if (session.initializedByUs || opticalPairing != null || legacyOpticalPairing != null || session.operationalPairingId != null) {
            throw new IllegalStateException("Optical pairing configuration is unavailable");
        }
        try {
            opticalPairing = OpticalPairingCode.parse(payload);
        } catch (IllegalArgumentException modernInvalid) {
            if (!EXPERIMENTAL_LEGACY_PAIRING.get()) {
                throw modernInvalid;
            }
            legacyOpticalPairing = LegacyWatchPairingCodec.LegacyOpticalCode.parse(payload);
        }
    }

    private final ModernControlIkeChannel controlChannel;

    void probeControlSaInit(int responderLocalCid, byte[] watchSetupData) throws Exception {
        controlChannel.probeControlSaInit(responderLocalCid, watchSetupData);
    }





    void probeOpticalPairing(int cid, AuthenticatedTransportState control,
            byte[] watchSetupData) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] sharedSecret = opticalPairing.boundSharedSecret(watchSetupData);
        byte[] authData = opticalPairing.authenticationData();
        IkeV2SessionCrypto.IkeSaKeys initialKeys = null, pairingKeys = null;
        IkeV2SessionCrypto.AdditionalKeRequest additional = null;
        byte[] intAuthR = null, kemCiphertext = null;
        try {
            HalTransportSession.PairingTransportCursor cursor = new HalTransportSession.PairingTransportCursor(
                    control.nextOutboundTxSequence, control.nextExpectedWatchTxSequence);
            IkeV2SessionCrypto.IkeSaKeys controlKeys = control.intermediate.additionalKeResult.updatedKeys;
            try (OpticalAuthMethodNegotiation method = new OpticalAuthMethodNegotiation(controlKeys)) {
                PairingTransportResult<Integer> selection = exchangePairingIke(cid, cursor,
                        OpticalPskSession.controlMethodRequest(random, controlKeys),
                        "OPTICAL METHOD", PIN_AUTH_METHOD_TIMEOUT_MS, method::accept);
                cursor = selection.cursor;
                if (!method.pskSelected()) throw new HostException("Watch did not select optical PSK");
                if (selection.value >= 0) cursor = sendPairingIkeOneWayAndAwaitAck(cid, cursor,
                        IkeV2SessionCrypto.createWatchInformationalAcknowledgement(random, controlKeys, selection.value),
                        "OPTICAL METHOD ACK", PAIRING_IKE_TIMEOUT_MS);
            }
            log("OPTICAL METHOD PASS: PSK/1 selected on authenticated control SA; key/code logged=false.");
            IkeV2Codec.InitiatorState initiator = IkeV2Codec.createOpticalPairingSaInit(random);
            try {
                PairingTransportResult<IkeV2SessionCrypto.ControlSaInitResponse> init = exchangePairingIke(
                        cid, cursor, List.of(initiator.ikePacket), "OPTICAL IKE_SA_INIT", PAIRING_IKE_TIMEOUT_MS,
                        packet -> hasInitiatorSpi(packet, initiator.initiatorSpi)
                                ? IkeV2SessionCrypto.parseOpticalPairingSaInitResponse(packet, initiator.initiatorSpi) : null);
                cursor = init.cursor;
                initialKeys = IkeV2SessionCrypto.deriveInitialKeys(initiator, init.value);
                additional = IkeV2SessionCrypto.createAdditionalKeRequest(random, initiator, init.value, initialKeys);
                IkeV2SessionCrypto.IntermediateResponseAccumulator accumulator =
                        new IkeV2SessionCrypto.IntermediateResponseAccumulator(initialKeys);
                PairingTransportResult<byte[]> intermediate = exchangePairingIke(cid, cursor, additional.ikePackets,
                        "OPTICAL IKE_INTERMEDIATE", PAIRING_IKE_TIMEOUT_MS, accumulator::accept);
                cursor = intermediate.cursor; kemCiphertext = intermediate.value;
                pairingKeys = IkeV2SessionCrypto.completeAdditionalKeyExchange(additional, kemCiphertext, initialKeys).updatedKeys;
                intAuthR = accumulator.responderIntAuth();
                try (OpticalPskSession auth = new OpticalPskSession(initiator, init.value, pairingKeys,
                        additional.initiatorIntAuth, intAuthR, sharedSecret)) {
                    PairingTransportResult<IkeV2SessionCrypto.IkeAuthResponse> accepted = exchangePairingIke(
                            cid, cursor, auth.request(random), "OPTICAL IKE_AUTH", PAIRING_IKE_TIMEOUT_MS, auth::acceptResponse);
                    if (!auth.authenticated()) throw new HostException("Optical peer authentication incomplete");
                    cursor = accepted.cursor;
                }
                log("OPTICAL IKE_AUTH PASS: responder PSK AUTH verified; MID=2; bytes logged=false.");
            exchangePairingPrivateNotifies(cid, cursor, random, pairingKeys, authData,
                        OpticalPskSession.NEXT_INFORMATIONAL_MESSAGE_ID);
            } finally { HalTransportSession.wipe(initiator.x448PrivateKey); }
        } finally {
            HalTransportSession.wipe(sharedSecret); HalTransportSession.wipe(authData); HalTransportSession.wipe(intAuthR); HalTransportSession.wipe(kemCiphertext);
            if (initialKeys != null) initialKeys.destroy();
            if (pairingKeys != null) pairingKeys.destroy();
            if (additional != null) HalTransportSession.wipe(additional.initiatorIntAuth);
            opticalPairing.close();
        }
    }

    void probePairingSession(
            int responderLocalCid,
            PinTransportState pinState,
            AppleWatchPairingCrypto.PairingSecrets secrets)
            throws Exception {
        SecureRandom random = new SecureRandom();
        HalTransportSession.PairingTransportCursor cursor =
                new HalTransportSession.PairingTransportCursor(
                        pinState.nextOutboundTxSequence,
                        pinState.nextExpectedWatchTxSequence);
        IkeV2Codec.InitiatorState initiator =
                IkeV2Codec.createPairingSaInit(random);
        log("PAIRING IKE CONFIG: securePassword=SPAKE2+/0x2AF9; "
                + "PPK=mandatory/fixed-ID-type-1; "
                + "ENCR=AES-GCM-16/256; PRF=HMAC-SHA2-512; "
                + "DH=X448; AdditionalKE1=ML-KEM-1024; "
                + "requestChildless=true.");
        PairingTransportResult<byte[]> saInitResult =
                exchangePairingIke(
                        responderLocalCid,
                        cursor,
                        List.of(initiator.ikePacket),
                        "PAIRING IKE_SA_INIT",
                        PAIRING_IKE_TIMEOUT_MS,
                        packet -> {
                            if (!hasInitiatorSpi(
                                    packet,
                                    initiator.initiatorSpi)) {
                                log("PAIRING IKE_SA_INIT STALE DROP: "
                                        + "response initiator SPI does "
                                        + "not match current request; "
                                        + "packet bytes logged=false.");
                                return null;
                            }
                            return packet;
                        });
        cursor = saInitResult.cursor;

        IkeV2SessionCrypto.ControlSaInitResponse response;
        IkeV2SessionCrypto.IkeSaKeys initialKeys;
        try {
            response =
                    IkeV2SessionCrypto
                            .parsePairingSaInitResponse(
                                    saInitResult.value,
                                    initiator.initiatorSpi);
            initialKeys =
                    IkeV2SessionCrypto.deriveInitialKeys(
                            initiator,
                            response);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Pairing IKE_SA_INIT validation failed: "
                            + error.getMessage(),
                    error);
        }
        log("PAIRING IKE_SA_INIT PASS: responderSPI="
                + HciCodec.toHex(response.responderSpi)
                + "; Watch selected SPAKE2+, USE_PPK, childless, "
                + "and IKE_INTERMEDIATE; key bytes logged=false.");

        IkeV2SessionCrypto.AdditionalKeRequest additionalRequest;
        try {
            additionalRequest =
                    IkeV2SessionCrypto
                            .createAdditionalKeRequest(
                                    random,
                                    initiator,
                                    response,
                                    initialKeys);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Cannot create pairing ML-KEM-1024 request: "
                            + error.getMessage(),
                    error);
        }
        IkeV2SessionCrypto.IntermediateResponseAccumulator
                intermediateAccumulator =
                new IkeV2SessionCrypto
                        .IntermediateResponseAccumulator(
                                initialKeys);
        PairingTransportResult<byte[]> intermediateResult =
                exchangePairingIke(
                        responderLocalCid,
                        cursor,
                        additionalRequest.ikePackets,
                        "PAIRING IKE_INTERMEDIATE",
                        PAIRING_IKE_TIMEOUT_MS,
                        intermediateAccumulator::accept);
        cursor = intermediateResult.cursor;

        IkeV2SessionCrypto.AdditionalKeResult additionalResult;
        IkeV2SessionCrypto.IkeSaKeys ppkKeys;
        try {
            additionalResult =
                    IkeV2SessionCrypto
                            .completeAdditionalKeyExchange(
                                    additionalRequest,
                                    intermediateResult.value,
                                    initialKeys);
            ppkKeys =
                    IkeV2SessionCrypto
                            .applyMandatoryPairingPpk(
                                    additionalResult.updatedKeys,
                                    secrets.ppk);
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Pairing ML-KEM/PPK key schedule failed: "
                            + error.getMessage(),
                    error);
        }
        byte[] responderIntAuth =
                intermediateAccumulator.responderIntAuth();
        log("PAIRING IKE_INTERMEDIATE PASS: ML-KEM ciphertext="
                + intermediateResult.value.length
                + " bytes; mandatory PPK transformed "
                + "SK_d/SK_pi/SK_pr; SK_ei/SK_er unchanged; "
                + "secret/key bytes logged=false.");

        IkeV2SessionCrypto.PairingGspmInitiator gspm = null;
        try {
            gspm =
                    IkeV2SessionCrypto
                            .createPairingGspmInitiator(
                                    random,
                                    initiator,
                                    response,
                                    ppkKeys,
                                    additionalRequest
                                            .initiatorIntAuth,
                                    responderIntAuth,
                                    secrets.sharedSecret);

            IkeV2SessionCrypto.PairingExchangeRequest first =
                    gspm.createFirstRequest(random);
            log("PAIRING GSPM TX1: MID=2 payloads="
                    + "[IDi(NULL/physical-device),INITIAL_CONTACT,"
                    + "IDr(saltedPin),GSPM-X,"
                    + "PPK_IDENTITY(type=1)]; "
                    + "NO_PPK_AUTH=false(mandatory PPK); "
                    + "X="
                    + first.gspmDataLength
                    + " bytes; encryptedPackets="
                    + first.ikePackets.size()
                    + "; PIN/key/auth bytes logged=false.");
            PairingTransportResult<byte[]> firstResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            first.ikePackets,
                            "PAIRING GSPM-X",
                            PAIRING_IKE_TIMEOUT_MS,
                            packet -> packet);
            cursor = firstResult.cursor;

            IkeV2SessionCrypto.PairingExchangeRequest second =
                    gspm
                            .acceptFirstResponseAndCreateSecondRequest(
                                    random,
                                    firstResult.value);
            log("PAIRING GSPM RX1/TX2 PASS: Watch IDr and "
                    + "SPAKE2+ Y authenticated structurally; "
                    + "first-response PPK identity is optional; "
                    + "MID=3 Prover confirmation="
                    + second.gspmDataLength
                    + " bytes; confirmation bytes logged=false.");
            PairingTransportResult<byte[]> secondResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            second.ikePackets,
                            "PAIRING GSPM-CONFIRM-P",
                            PAIRING_IKE_TIMEOUT_MS,
                            packet -> packet);
            cursor = secondResult.cursor;

            IkeV2SessionCrypto.PairingExchangeRequest finalRequest =
                    gspm
                            .acceptSecondResponseAndCreateFinalAuthRequest(
                                    random,
                                    secondResult.value);
            log("PAIRING GSPM RX2/TX3 PASS: Watch confirmation "
                    + "verified in constant time; MID=4 "
                    + "AUTH(method=12)+PPK_IDENTITY(type=1), "
                    + "authData="
                    + finalRequest.authenticationDataLength
                    + " bytes; bytes logged=false.");
            PairingTransportResult<
                    IkeV2SessionCrypto.PairingAuthResponse>
                    finalResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            finalRequest.ikePackets,
                            "PAIRING GSPM-AUTH",
                            PAIRING_IKE_TIMEOUT_MS,
                            gspm::acceptFinalAuthResponse);
            cursor = finalResult.cursor;
            log("PAIRING IKE_AUTH PASS: responder secure-password "
                    + "AUTH verified; payloads="
                    + formatHexList(
                            finalResult.value.payloadTypes)
                    + " notifies="
                    + formatHexList(
                            finalResult.value.notifyTypes)
                    + " authData="
                    + finalResult.value
                            .authenticationDataLength
                    + " bytes; nextTxSeq="
                    + cursor.nextOutboundTxSequence
                    + " nextWatchTxSeq="
                    + cursor.nextExpectedWatchTxSequence
                    + ".");

            cursor = exchangePairingPrivateNotifies(
                    responderLocalCid,
                    cursor,
                    random,
                    ppkKeys,
                    secrets.authenticationData);
            log("PAIRING PRIVATE-NOTIFIES PASS: authenticated "
                    + "bidirectional Apple OOB/NetworkRelay exchange "
                    + "and Watch request acknowledgement complete; "
                    + "OOB/private/key bytes logged=false; nextTxSeq="
                    + cursor.nextOutboundTxSequence
                    + " nextWatchTxSeq="
                    + cursor.nextExpectedWatchTxSequence
                    + ".");
        } finally {
            if (gspm != null) {
                gspm.destroy();
            }
            Arrays.fill(responderIntAuth, (byte) 0);
            Arrays.fill(
                    intermediateResult.value,
                    (byte) 0);
        }
    }

    HalTransportSession.PairingTransportCursor exchangePairingPrivateNotifies(
            int responderLocalCid,
            HalTransportSession.PairingTransportCursor cursor,
            SecureRandom random,
        IkeV2SessionCrypto.IkeSaKeys ppkKeys,
            byte[] pendingIdsAuthData)
            throws Exception {
        return exchangePairingPrivateNotifies(responderLocalCid, cursor, random, ppkKeys,
                pendingIdsAuthData, IkeV2SessionCrypto.PAIRING_NOTIFIES_MESSAGE_ID);
    }

    HalTransportSession.PairingTransportCursor exchangePairingPrivateNotifies(
            int responderLocalCid, HalTransportSession.PairingTransportCursor cursor, SecureRandom random,
            IkeV2SessionCrypto.IkeSaKeys ppkKeys, byte[] pendingIdsAuthData, int messageId)
            throws Exception {
        BleSecureConnectionsCrypto.LocalOobMaterial
                localOob = null;
        AppleNetworkRelayPairingMaterial networkKeys =
                null;
        AppleNetworkRelayInnerAddresses addresses =
                null;
        ApplePairingNotifyPayloads.LocalBatch localBatch =
                null;
        List<ApplePairingNotifyPayloads.PrivateNotify>
                localNotifies = null;
        IkeV2SessionCrypto.PairingPrivateNotifyResponseAccumulator
                accumulator = null;
        IkeV2SessionCrypto.PairingPrivateNotifyResponse
                response = null;
        List<ApplePairingNotifyPayloads.PrivateNotify>
                peerNotifies = null;
        ApplePairingNotifyPayloads.PeerBatch peerBatch =
                null;
        PairingSessionRecord pairingSessionRecord =
                null;
        byte[] pairingGeneration = null;
        String localBluetoothCbUuid =
                UUID.randomUUID().toString();
        String localNrUuid =
                UUID.randomUUID().toString();
        String localIdsDeviceUuid =
                session.idsSession.requireAppIdsDeviceIdentifier();
        try {
            localOob =
                    BleSecureConnectionsCrypto
                            .generateLocalOob(random);
            networkKeys =
                    AppleNetworkRelayPairingMaterial
                            .generate(random);
            addresses =
                    AppleNetworkRelayInnerAddresses
                            .generate(random);
            localBatch =
                    ApplePairingNotifyPayloads
                            .createLocalBatch(
                                    localOob,
                                    networkKeys,
                                    addresses,
                                    IosCompanionProfile26_6
                                            .DEVICE_NAME,
                                    session.companionProfile().build,
                                    IosCompanionProfile26_6.wireIdsDeviceIdentifier(localIdsDeviceUuid));
            localNotifies = localBatch.notifies();
            IkeV2SessionCrypto.PairingPrivateNotifyRequest
                    request =
                    IkeV2SessionCrypto
                            .createPairingPrivateNotifyRequest(
                                    random,
                                    ppkKeys,
                                    localNotifies, messageId);
            log("PAIRING PRIVATE-NOTIFIES TX: MID=" + messageId + " types="
                    + formatHexList(request.notifyTypes)
                    + " lengths="
                    + request.dataLengths
                    + " encryptedPackets="
                    + request.ikePackets.size()
                    + "; OOB/private/key bytes logged=false.");

            accumulator =
                    new IkeV2SessionCrypto
                            .PairingPrivateNotifyResponseAccumulator(
                                    ppkKeys, messageId);
            PairingTransportResult<
                    IkeV2SessionCrypto
                            .PairingPrivateNotifyResponse>
                    notifyResult =
                    exchangePairingIke(
                            responderLocalCid,
                            cursor,
                            request.ikePackets,
                            "PAIRING PRIVATE-NOTIFIES",
                            PAIRING_IKE_TIMEOUT_MS,
                            accumulator::accept);
            cursor = notifyResult.cursor;
            response = notifyResult.value;
            log("PAIRING PRIVATE-NOTIFIES RX: Watch request MID="
                    + response.peerRequestMessageId
                    + " types="
                    + formatHexList(
                            response.notifyTypes())
                    + " lengths="
                    + response.dataLengths()
                    + "; companion request ACK="
                    + accumulator.requestAcknowledged()
                    + "; payload bytes logged=false.");

            peerNotifies = response.notifies();
            peerBatch =
                    ApplePairingNotifyPayloads
                            .parsePeerBatch(peerNotifies);
            log("PAIRING PRIVATE-NOTIFIES VALIDATED: protocol="
                    + peerBatch.protocolVersion
                    + " deviceNameChars="
                    + peerBatch.deviceName.length()
                    + " buildChars="
                    + peerBatch.buildVersion.length()
                    + " deviceType="
                    + peerBatch.deviceType
                    + " alwaysOnWifi="
                    + peerBatch.alwaysOnWifi
                    + " IDS="
                    + (peerBatch.idsDeviceId != null)
                    + "; LE-SC OOB and NetworkRelay layouts valid.");

            pairingGeneration = new byte[16];
            random.nextBytes(pairingGeneration);
            pairingSessionRecord =
                    PairingSessionRecord.createPreSmp(
                            pairingGeneration,
                            pendingIdsAuthData,
                            localOob,
                            networkKeys,
                            addresses,
                            peerBatch,
                            session.activeWatchSetupMetadata == null ? null : session.activeWatchSetupMetadata.productType(),
                            null,
                            localBluetoothCbUuid,
                            localNrUuid,
                            localIdsDeviceUuid);
            session.persistPairingSessionOrFail(
                    pairingSessionRecord);
            log("PAIRING SESSION CHECKPOINT PASS: generation "
                    + "and long-lived NetworkRelay material are "
                    + "atomically sealed before Watch ACK/SMP; "
                    + "secret bytes logged=false.");

            List<byte[]> acknowledgementPackets =
                    IkeV2SessionCrypto
                            .createWatchInformationalAcknowledgement(
                                    random,
                                    ppkKeys,
                                    response.peerRequestMessageId);
            try (IkeV2SessionCrypto.WatchRequestRetryMatcher retryMatcher =
                    response.createRetryMatcher()) {
                cursor = sendPairingIkeOneWayAndAwaitAck(
                        responderLocalCid,
                        cursor,
                        acknowledgementPackets,
                        PAIRING_FINAL_ACK_LABEL,
                        PAIRING_IKE_ACK_TIMEOUT_MS,
                        retryMatcher);
            }
            session.smpBonding.completeLeSecureConnectionsSmp(
                    localOob,
                    peerBatch,
                    pairingSessionRecord);
            session.normalLink.completeModernNormalRegistration(
                    HciCodec.BT_CL_CURRENT_VERSION);
            return cursor;
        } catch (IllegalArgumentException error) {
            throw new HostException(
                    "Pairing private-notify exchange failed: "
                            + error.getMessage(),
                    error);
        } finally {
            if (pairingSessionRecord != null) {
                pairingSessionRecord.destroy();
            }
            HalTransportSession.wipe(pairingGeneration);
            if (peerBatch != null) {
                peerBatch.destroy();
            }
            destroyPrivateNotifies(peerNotifies);
            if (accumulator != null) {
                accumulator.destroy();
            }
            if (response != null) {
                response.destroy();
            }
            destroyPrivateNotifies(localNotifies);
            if (localBatch != null) {
                localBatch.destroy();
            }
            if (addresses != null) {
                addresses.destroy();
            }
            if (networkKeys != null) {
                networkKeys.destroy();
            }
            if (localOob != null) {
                localOob.destroy();
            }
        }
    }

    static void destroyPrivateNotifies(
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        if (notifies == null) {
            return;
        }
        for (ApplePairingNotifyPayloads.PrivateNotify notify
                : notifies) {
            if (notify != null) {
                notify.destroy();
            }
        }
    }

    int sendPairingErtmIkePackets(
            int responderLocalCid,
            List<byte[]> packetsToSend,
            int initialOutboundTxSequence,
            int expectedWatchTxSequence,
            String label)
            throws Exception {
        int outboundTxSequence = initialOutboundTxSequence;
        for (int index = 0;
                index < packetsToSend.size();
                index++) {
            session.checkStop();
            byte[] ikePacket = packetsToSend.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmFrame =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            expectedWatchTxSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            log(String.format(
                    Locale.US,
                    "%s TX packet=%d/%d IKE=%d uIKE=%d "
                            + "ERTM=%d TxSeq=%d ReqSeq=%d; "
                            + "encrypted bytes logged=false",
                    label,
                    index + 1,
                    packetsToSend.size(),
                    ikePacket.length,
                    uikeFrame.length,
                    ertmFrame.length,
                    outboundTxSequence,
                    expectedWatchTxSequence));
            session.sendL2cap(
                    responderLocalCid,
                    ertmFrame,
                    "TERMINUS ERTM " + label);
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        return outboundTxSequence;
    }

    HalTransportSession.PairingTransportCursor sendPairingIkeOneWayAndAwaitAck(
            int responderLocalCid,
            HalTransportSession.PairingTransportCursor initialCursor,
            List<byte[]> packetsToSend,
            String label,
            long timeoutMs)
            throws Exception {
        return sendPairingIkeOneWayAndAwaitAck(
                responderLocalCid,
                initialCursor,
                packetsToSend,
                label,
                timeoutMs,
                null);
    }

    HalTransportSession.PairingTransportCursor completeOneWayAckWait(
            String label,
            int expectedAcknowledgement,
            int expectedWatchTxSequence,
            IkeV2Codec.UikeStreamDecoder retryDecoder,
            IkeV2SessionCrypto.WatchRequestRetryMatcher retryMatcher)
            throws HostException {
        if ((retryDecoder != null && retryDecoder.bufferedLength() > 0)
                || (retryMatcher != null && retryMatcher.hasPartialMatch())) {
            throw new HostException(
                    "Incomplete Watch retry while awaiting "
                            + label
                            + " acknowledgement");
        }
        log(String.format(
                Locale.US,
                "%s ACK PASS: ReqSeq=%d.",
                label,
                expectedAcknowledgement));
        if (PAIRING_FINAL_ACK_LABEL.equals(label)) {
            session.finalPairingAckAtMs = BridgeClock.elapsedRealtime();
            log("SMP HANDOFF: final IKE transport ACK received; " + session.pairingSmpDiagnostics());
        }
        return new HalTransportSession.PairingTransportCursor(
                expectedAcknowledgement,
                expectedWatchTxSequence);
    }

    HalTransportSession.PairingTransportCursor sendPairingIkeOneWayAndAwaitAck(
            int responderLocalCid,
            HalTransportSession.PairingTransportCursor initialCursor,
            List<byte[]> packetsToSend,
            String label,
            long timeoutMs,
            IkeV2SessionCrypto.WatchRequestRetryMatcher retryMatcher)
            throws Exception {
        if (packetsToSend == null
                || packetsToSend.isEmpty()) {
            throw new HostException(
                    label + " has no IKE packet");
        }
        int expectedWatchTxSequence =
                initialCursor.nextExpectedWatchTxSequence;
        int outboundTxSequence = sendPairingErtmIkePackets(
                responderLocalCid,
                packetsToSend,
                initialCursor.nextOutboundTxSequence,
                expectedWatchTxSequence,
                label);
        int expectedAcknowledgement =
                outboundTxSequence;
        long deadline =
                BridgeClock.elapsedRealtime() + timeoutMs;
        int watchRequestRetries = 0;
        IkeV2Codec.UikeStreamDecoder retryDecoder =
                retryMatcher == null ? null : new IkeV2Codec.UikeStreamDecoder();
        int maxRetryBufferBytes =
                retryMatcher == null ? 0 : retryMatcher.maxPacketLength() + 5;
        while (BridgeClock.elapsedRealtime() < deadline) {
            session.checkStop();
            Throwable callbackError = session.callbackFailure.get();
            if (callbackError != null) {
                throw new HostException(
                        "Bluetooth controller transport failed",
                        callbackError);
            }
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = session.smpBonding.awaitPairingTerminusPdu(deadline, label);
            } catch (TimeoutException complete) {
                break;
            }
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed "
                                + label
                                + " ERTM acknowledgement: "
                                + error.getMessage(),
                        error);
            } finally { HalTransportSession.wipe(l2cap.payload); }
            if (ertm.supervisory) {
                if (ertm.poll) {
                    session.sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR FINAL " + label);
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected " + label);
                }
                if (ertm.requestSequence == expectedAcknowledgement) {
                    return completeOneWayAckWait(
                            label,
                            expectedAcknowledgement,
                            expectedWatchTxSequence,
                            retryDecoder,
                            retryMatcher);
                }
                continue;
            }
            try {
                if (ertm.sar != L2capErtmCodec.SAR_UNSEGMENTED) {
                    throw new HostException(
                            "Segmented "
                                    + label
                                    + " ERTM I-frame is not expected");
                }
                int previousWatchTxSequence =
                        (expectedWatchTxSequence
                                + L2capErtmCodec.SEQUENCE_MODULUS
                                - 1)
                                % L2capErtmCodec.SEQUENCE_MODULUS;
                if (ertm.txSequence == previousWatchTxSequence) {
                    session.sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    false,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR DUPLICATE " + label);
                    if (ertm.requestSequence == expectedAcknowledgement) {
                        return completeOneWayAckWait(
                                label,
                                expectedAcknowledgement,
                                expectedWatchTxSequence,
                                retryDecoder,
                                retryMatcher);
                    }
                    continue;
                }
                if (retryMatcher == null) {
                    throw new HostException(
                            "Unexpected Watch I-frame while awaiting "
                                    + label
                                    + " acknowledgement");
                }
                if (ertm.txSequence != expectedWatchTxSequence) {
                    throw new HostException(
                            "Unexpected "
                                    + label
                                    + " Watch ERTM TxSeq="
                                    + ertm.txSequence
                                    + " expected="
                                    + expectedWatchTxSequence);
                }
                List<byte[]> decodedPackets;
                try {
                    decodedPackets = retryDecoder.push(ertm.information);
                } catch (IllegalArgumentException error) {
                    throw new HostException(
                            "Malformed "
                                    + label
                                    + " Watch retry uIKE frame: "
                                    + error.getMessage(),
                            error);
                }
                try {
                    if (retryDecoder.bufferedLength() > maxRetryBufferBytes) {
                        throw new HostException(
                                "Oversized Watch retry uIKE frame while awaiting "
                                        + label
                                        + " acknowledgement");
                    }
                    int completedRetriesInFrame = 0;
                    for (byte[] ikePacket : decodedPackets) {
                        IkeV2SessionCrypto.WatchRequestRetryStatus status =
                                retryMatcher.accept(ikePacket);
                        if (status == IkeV2SessionCrypto.WatchRequestRetryStatus.REJECTED) {
                            throw new HostException(
                                    "Unexpected Watch I-frame while awaiting "
                                            + label
                                            + " acknowledgement");
                        }
                        if (status == IkeV2SessionCrypto.WatchRequestRetryStatus.COMPLETE_RETRY) {
                            completedRetriesInFrame++;
                        }
                    }
                    expectedWatchTxSequence =
                            (expectedWatchTxSequence + 1)
                                    % L2capErtmCodec.SEQUENCE_MODULUS;
                    session.sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    false,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR ACK " + label);
                    for (int i = 0; i < completedRetriesInFrame; i++) {
                        if (++watchRequestRetries > MAX_PAIRING_WATCH_REQUEST_RETRIES) {
                            throw new HostException(
                                    "Too many Watch request retries while awaiting "
                                            + label
                                            + " acknowledgement");
                        }
                        log(String.format(
                                Locale.US,
                                "%s WATCH REQUEST RETRY RX: authenticated byte-identical retry #%d "
                                        + "TxSeq=%d ReqSeq=%d; resending cached acknowledgement; "
                                        + "encrypted bytes logged=false.",
                                label,
                                watchRequestRetries,
                                ertm.txSequence,
                                ertm.requestSequence));
                        outboundTxSequence = sendPairingErtmIkePackets(
                                responderLocalCid,
                                packetsToSend,
                                outboundTxSequence,
                                expectedWatchTxSequence,
                                label);
                        expectedAcknowledgement = outboundTxSequence;
                    }
                } finally {
                    for (byte[] ikePacket : decodedPackets) {
                        HalTransportSession.wipe(ikePacket);
                    }
                }
            } finally {
                HalTransportSession.wipe(ertm.information);
            }
        }
        throw new HostException(
                label
                        + " ERTM acknowledgement timed out after "
                        + timeoutMs
                        + " ms; expectedReqSeq="
                        + expectedAcknowledgement);
    }

    <T> PairingTransportResult<T> exchangePairingIke(
            int responderLocalCid,
            HalTransportSession.PairingTransportCursor initialCursor,
            List<byte[]> requestPackets,
            String label,
            long timeoutMs,
            PairingIkeProcessor<T> processor)
            throws Exception {
        if (requestPackets == null
                || requestPackets.isEmpty()
                || processor == null) {
            throw new HostException(
                    label + " has no request or response processor");
        }
        int outboundTxSequence =
                initialCursor.nextOutboundTxSequence;
        int requestSequence =
                initialCursor.nextExpectedWatchTxSequence;
        for (int index = 0;
                index < requestPackets.size();
                index++) {
            session.checkStop();
            byte[] ikePacket = requestPackets.get(index);
            byte[] uikeFrame =
                    IkeV2Codec.encodeUikeFrame(ikePacket);
            byte[] ertmRequest =
                    L2capErtmCodec.encodeInformationFrame(
                            responderLocalCid,
                            outboundTxSequence,
                            requestSequence,
                            uikeFrame,
                            TERMINUS_FCS_ENABLED);
            if (HalTransportSession.suppressPacketBytes(label)) {
                log(String.format(
                        Locale.US,
                        "%s TX packet=%d/%d IKE=%d uIKE=%d "
                                + "ERTM=%d TxSeq=%d ReqSeq=%d; "
                                + "packet bytes logged=false",
                        label,
                        index + 1,
                        requestPackets.size(),
                        ikePacket.length,
                        uikeFrame.length,
                        ertmRequest.length,
                        outboundTxSequence,
                        requestSequence));
            } else {
                log(String.format(
                        Locale.US,
                        "%s TX packet=%d/%d IKE=%d uIKE=%d "
                                + "ERTM=%d TxSeq=%d ReqSeq=%d "
                                + "rawEncryptedOrPublicIKE=%s",
                        label,
                        index + 1,
                        requestPackets.size(),
                        ikePacket.length,
                        uikeFrame.length,
                        ertmRequest.length,
                        outboundTxSequence,
                        requestSequence,
                        HciCodec.toHex(ikePacket)));
            }
            session.sendL2cap(
                    responderLocalCid,
                    ertmRequest,
                    "TERMINUS ERTM " + label);
            outboundTxSequence =
                    (outboundTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
        }
        int expectedRequestSequence =
                outboundTxSequence;
        int expectedWatchTxSequence =
                initialCursor.nextExpectedWatchTxSequence;
        int highestWatchAck =
                initialCursor.nextOutboundTxSequence;
        int l2capFrames = 0;
        int ikePackets = 0;
        IkeV2Codec.UikeStreamDecoder decoder =
                new IkeV2Codec.UikeStreamDecoder();
        long deadline =
                BridgeClock.elapsedRealtime() + timeoutMs;
        while (BridgeClock.elapsedRealtime() < deadline) {
            HciCodec.L2capPdu l2cap;
            try {
                l2cap = session.smpBonding.awaitPairingTerminusPdu(deadline, label);
            } catch (TimeoutException complete) {
                break;
            }
            l2capFrames++;
            L2capErtmCodec.Frame ertm;
            try {
                ertm = L2capErtmCodec.decode(
                        HciCodec.TERMINUS_LOCAL_CID,
                        l2cap.payload,
                        TERMINUS_FCS_ENABLED);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed "
                                + label
                                + " ERTM frame: "
                                + error.getMessage(),
                        error);
            } finally { HalTransportSession.wipe(l2cap.payload); }
            if (ertm.requestSequence
                    > expectedRequestSequence) {
                throw new HostException(
                        "Watch acknowledged unsent "
                                + label
                                + " ERTM sequence "
                                + ertm.requestSequence);
            }
            highestWatchAck = Math.max(
                    highestWatchAck,
                    ertm.requestSequence);
            if (ertm.supervisory) {
                log(String.format(
                        Locale.US,
                        "%s ERTM S-FRAME RX: function=%d "
                                + "ReqSeq=%d P=%d F=%d.",
                        label,
                        ertm.supervisoryFunction,
                        ertm.requestSequence,
                        ertm.poll ? 1 : 0,
                        ertm.finalBit ? 1 : 0));
                if (ertm.poll) {
                    session.sendL2cap(
                            responderLocalCid,
                            L2capErtmCodec.encodeReceiverReady(
                                    responderLocalCid,
                                    expectedWatchTxSequence,
                                    true,
                                    TERMINUS_FCS_ENABLED),
                            "TERMINUS ERTM RR FINAL "
                                    + label);
                }
                if (ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_REJ
                        || ertm.supervisoryFunction
                        == L2capErtmCodec.SUPERVISORY_SREJ) {
                    throw new HostException(
                            "Watch rejected " + label);
                }
                continue;
            }
            if (ertm.sar
                    != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new HostException(
                        "Segmented "
                                + label
                                + " ERTM I-frame is not expected");
            }
            int previousWatchTxSequence =
                    (expectedWatchTxSequence
                            + L2capErtmCodec.SEQUENCE_MODULUS
                            - 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            if (ertm.txSequence
                    == previousWatchTxSequence) {
                session.sendL2cap(
                        responderLocalCid,
                        L2capErtmCodec.encodeReceiverReady(
                                responderLocalCid,
                                expectedWatchTxSequence,
                                false,
                                TERMINUS_FCS_ENABLED),
                        "TERMINUS ERTM RR DUPLICATE "
                                + label);
                continue;
            }
            if (ertm.txSequence
                    != expectedWatchTxSequence) {
                throw new HostException(
                        "Unexpected "
                                + label
                                + " Watch ERTM TxSeq="
                                + ertm.txSequence
                                + " expected="
                                + expectedWatchTxSequence);
            }
            expectedWatchTxSequence =
                    (expectedWatchTxSequence + 1)
                            % L2capErtmCodec.SEQUENCE_MODULUS;
            session.sendL2cap(
                    responderLocalCid,
                    L2capErtmCodec.encodeReceiverReady(
                            responderLocalCid,
                            expectedWatchTxSequence,
                            false,
                            TERMINUS_FCS_ENABLED),
                    "TERMINUS ERTM RR ACK " + label);

            List<byte[]> decodedPackets;
            try {
                decodedPackets =
                        decoder.push(ertm.information);
            } catch (IllegalArgumentException error) {
                throw new HostException(
                        "Malformed "
                                + label
                                + " uIKE frame: "
                                + error.getMessage(),
                        error);
            }
            for (byte[] ikePacket : decodedPackets) {
                ikePackets++;
                if (HalTransportSession.suppressPacketBytes(label)) {
                    log(label
                            + " IKE RX #"
                            + ikePackets
                            + ": bytes="
                            + ikePacket.length
                            + "; packet bytes logged=false.");
                } else {
                    log(label
                            + " IKE RX #"
                            + ikePackets
                            + ": bytes="
                            + ikePacket.length
                            + " rawEncryptedOrPublicIKE="
                            + HciCodec.toHex(ikePacket));
                }
                T value;
                try {
                    value = processor.accept(ikePacket);
                } catch (RuntimeException error) {
                    throw new HostException(
                            label
                                    + " authentication/parse failed: "
                                    + error.getMessage(),
                            error);
                }
                if (value == null) {
                    continue;
                }
                if (highestWatchAck
                        != expectedRequestSequence) {
                    throw new HostException(
                            "Watch "
                                    + label
                                    + " response did not acknowledge "
                                    + "all request I-frames; highest="
                                    + highestWatchAck
                                    + " expected="
                                    + expectedRequestSequence);
                }
                return new PairingTransportResult<>(
                        value,
                        new HalTransportSession.PairingTransportCursor(
                                expectedRequestSequence,
                                expectedWatchTxSequence));
            }
        }
        throw new HostException(
                label
                        + " timed out after "
                        + timeoutMs
                        + " ms; L2CAP frames="
                        + l2capFrames
                        + " IKE packets="
                        + ikePackets
                        + " highestWatchReqSeq="
                        + highestWatchAck
                        + " expectedReqSeq="
                        + expectedRequestSequence
                        + " buffered="
                        + HciCodec.toHex(
                                decoder.bufferedBytes()));
    }

    static boolean hasInitiatorSpi(
            byte[] ikePacket,
            byte[] expectedInitiatorSpi) {
        if (ikePacket == null
                || expectedInitiatorSpi == null
                || expectedInitiatorSpi.length != 8
                || ikePacket.length < 8) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < 8; index++) {
            difference |= ikePacket[index]
                    ^ expectedInitiatorSpi[index];
        }
        return difference == 0;
    }

    interface PairingIkeProcessor<T> {
        T accept(byte[] packet);
    }

    static final class PairingTransportResult<T> {
        final T value;
        final HalTransportSession.PairingTransportCursor cursor;

        PairingTransportResult(
                T value,
                HalTransportSession.PairingTransportCursor cursor) {
            this.value = value;
            this.cursor = cursor;
        }
    }

    static final class IntermediateTransportState {
        final IkeV2SessionCrypto.AdditionalKeResult
                additionalKeResult;
        final byte[] initiatorIntAuth;
        final byte[] responderIntAuth;
        final int nextOutboundTxSequence;
        final int nextExpectedWatchTxSequence;

        IntermediateTransportState(
                IkeV2SessionCrypto.AdditionalKeResult
                        additionalKeResult,
                byte[] initiatorIntAuth,
                byte[] responderIntAuth,
                int nextOutboundTxSequence,
                int nextExpectedWatchTxSequence) {
            this.additionalKeResult = additionalKeResult;
            this.initiatorIntAuth =
                    initiatorIntAuth.clone();
            this.responderIntAuth =
                    responderIntAuth.clone();
            this.nextOutboundTxSequence =
                    nextOutboundTxSequence;
            this.nextExpectedWatchTxSequence =
                    nextExpectedWatchTxSequence;
        }
    }

    static final class AuthenticatedTransportState {
        final IntermediateTransportState intermediate;
        final int nextOutboundTxSequence;
        final int nextExpectedWatchTxSequence;

        AuthenticatedTransportState(
                IntermediateTransportState intermediate,
                int nextOutboundTxSequence,
                int nextExpectedWatchTxSequence) {
            this.intermediate = intermediate;
            this.nextOutboundTxSequence =
                    nextOutboundTxSequence;
            this.nextExpectedWatchTxSequence =
                    nextExpectedWatchTxSequence;
        }
    }

    static final class PinTransportState {
        final byte[] pinSalt;
        final int nextOutboundTxSequence;
        final int nextExpectedWatchTxSequence;

        PinTransportState(
                byte[] pinSalt,
                int nextOutboundTxSequence,
                int nextExpectedWatchTxSequence) {
            this.pinSalt = pinSalt.clone();
            this.nextOutboundTxSequence =
                    nextOutboundTxSequence;
            this.nextExpectedWatchTxSequence =
                    nextExpectedWatchTxSequence;
        }

        void destroy() {
            Arrays.fill(pinSalt, (byte) 0);
        }
    }
}
