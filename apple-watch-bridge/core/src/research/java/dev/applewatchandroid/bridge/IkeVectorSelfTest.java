package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.math.ec.rfc7748.X448;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * Device-side crypto/codec smoke test. It performs no Binder, Bluetooth, file,
 * settings, package, or network operation.
 */
public final class IkeVectorSelfTest {
    private IkeVectorSelfTest() {
    }

    public static void main(String[] args) {
        try {
            SecureRandom random = new SecureRandom();
            IkeV2Codec.InitiatorState state =
                    IkeV2Codec.createControlSaInit(random);
            IkeV2Codec.UikeStreamDecoder decoder =
                    new IkeV2Codec.UikeStreamDecoder();
            byte[] ertmBytes = L2capErtmCodec.encodeInformationFrame(
                    0x0307,
                    0,
                    0,
                    state.uikeFrame,
                    false);
            L2capErtmCodec.Frame ertm =
                    L2capErtmCodec.decode(
                            0x0307,
                            ertmBytes,
                            false);
            List<byte[]> packets = decoder.push(ertm.information);
            if (packets.size() != 1
                    || !Arrays.equals(state.ikePacket, packets.get(0))
                    || state.ikePacket.length
                    != IkeV2Codec.CONTROL_SA_INIT_LENGTH
                    || state.uikeFrame.length
                    != IkeV2Codec.CONTROL_UIKE_FRAME_LENGTH
                    || ertmBytes.length
                    != IkeV2Codec.CONTROL_UIKE_FRAME_LENGTH + 2
                    || ertm.supervisory
                    || ertm.txSequence != 0
                    || ertm.requestSequence != 0
                    || ertm.sar != L2capErtmCodec.SAR_UNSEGMENTED) {
                throw new IllegalStateException(
                        "Generated vector failed local round trip");
            }

            byte[] responderPrivate = new byte[56];
            X448.generatePrivateKey(random, responderPrivate);
            byte[] responderPublic = new byte[56];
            X448.generatePublicKey(
                    responderPrivate,
                    0,
                    responderPublic,
                    0);
            byte[] responderSpi = new byte[8];
            random.nextBytes(responderSpi);
            responderSpi[0] |= 1;
            byte[] responderNonce = new byte[32];
            random.nextBytes(responderNonce);
            byte[] syntheticSaInitResponse = new byte[256];
            random.nextBytes(syntheticSaInitResponse);
            IkeV2SessionCrypto.ControlSaInitResponse response =
                    new IkeV2SessionCrypto.ControlSaInitResponse(
                            responderSpi,
                            responderNonce,
                            responderPublic,
                            syntheticSaInitResponse);
            IkeV2SessionCrypto.IkeSaKeys initialKeys =
                    IkeV2SessionCrypto.deriveInitialKeys(
                            state,
                            response);
            IkeV2SessionCrypto.AdditionalKeRequest request =
                    IkeV2SessionCrypto.createAdditionalKeRequest(
                            random,
                            state,
                            response,
                            initialKeys);
            SecretWithEncapsulation encapsulated =
                    IkeV2SessionCrypto.encapsulateForTest(
                            random,
                            request.publicKey);
            List<byte[]> responsePackets =
                    IkeV2SessionCrypto.encryptIntermediatePayload(
                            random,
                            state.initiatorSpi,
                            responderSpi,
                            initialKeys.skEr,
                            true,
                            IkeV2SessionCrypto.buildKePayload(
                                    encapsulated.getEncapsulation()),
                            IkeV2Codec.PAYLOAD_KE,
                            IkeV2SessionCrypto
                                    .CONTROL_MAX_IKE_PACKET_SIZE);
            IkeV2SessionCrypto.IntermediateResponseAccumulator
                    accumulator =
                    new IkeV2SessionCrypto
                            .IntermediateResponseAccumulator(
                                    initialKeys);
            byte[] ciphertext = null;
            for (byte[] responsePacket : responsePackets) {
                byte[] complete =
                        accumulator.accept(responsePacket);
                if (complete != null) {
                    ciphertext = complete;
                }
            }
            IkeV2SessionCrypto.AdditionalKeResult result =
                    IkeV2SessionCrypto.completeAdditionalKeyExchange(
                            request,
                            ciphertext,
                            initialKeys);
            byte[] responderIntAuth =
                    accumulator.responderIntAuth();
            IkeV2SessionCrypto.IkeAuthRequest authRequest =
                    IkeV2SessionCrypto.createControlIkeAuthRequest(
                            random,
                            state,
                            response,
                            result.updatedKeys,
                            request.initiatorIntAuth,
                            responderIntAuth);
            List<byte[]> authResponsePackets =
                    IkeV2SessionCrypto
                            .createControlIkeAuthResponseForTest(
                                    random,
                                    state,
                                    response,
                                    result.updatedKeys,
                                    request.initiatorIntAuth,
                                    responderIntAuth);
            IkeV2SessionCrypto.IkeAuthResponseAccumulator
                    authAccumulator =
                    new IkeV2SessionCrypto
                            .IkeAuthResponseAccumulator(
                                    state,
                                    response,
                                    result.updatedKeys,
                                    request.initiatorIntAuth,
                                    responderIntAuth);
            IkeV2SessionCrypto.IkeAuthResponse authResponse =
                    authAccumulator.accept(
                            authResponsePackets.get(0));
            IkeV2SessionCrypto.PinAuthMethodRequest pinRequest =
                    IkeV2SessionCrypto.createPinAuthMethodRequest(
                            random,
                            result.updatedKeys);
            byte[] pinSalt = new byte[32];
            random.nextBytes(pinSalt);
            List<byte[]> pinRequestAcknowledgement =
                    IkeV2SessionCrypto
                            .createPinAuthMethodEmptyResponseForTest(
                                    random,
                                    result.updatedKeys);
            IkeV2SessionCrypto.PinAuthMethodResponseAccumulator
                    pinAccumulator =
                    new IkeV2SessionCrypto
                            .PinAuthMethodResponseAccumulator(
                                    result.updatedKeys);
            if (pinAccumulator.accept(
                    pinRequestAcknowledgement.get(0)) != null) {
                throw new IllegalStateException(
                        "Empty PIN-method acknowledgement was not empty");
            }
            List<byte[]> pinWatchRequest =
                    IkeV2SessionCrypto
                            .createPinAuthMethodWatchRequestForTest(
                                    random,
                                    result.updatedKeys,
                                    pinSalt);
            IkeV2SessionCrypto.PinAuthMethodResponse pinResponse =
                    pinAccumulator.accept(
                            pinWatchRequest.get(0));
            List<byte[]> pinWatchAcknowledgement =
                    IkeV2SessionCrypto
                            .createWatchInformationalAcknowledgement(
                                    random,
                                    result.updatedKeys,
                                    pinResponse.peerRequestMessageId);
            if (request.publicKey.length
                    != IkeV2SessionCrypto
                    .ML_KEM_1024_PUBLIC_KEY_LENGTH
                    || request.ikePackets.size() != 2
                    || responsePackets.size() != 2
                    || !Arrays.equals(
                    encapsulated.getSecret(),
                    result.sharedSecret)
                    || result.updatedKeys.skD.length != 64
                    || result.updatedKeys.skEi.length != 36
                    || result.updatedKeys.skEr.length != 36
                    || request.initiatorIntAuth.length != 64
                    || responderIntAuth.length != 64
                    || authRequest.ikePackets.size() != 1
                    || authRequest.authenticationDataLength != 64
                    || authResponse.authenticationDataLength != 64
                    || pinRequest.ikePackets.size() != 1
                    || !Arrays.equals(
                    pinRequest.plaintext,
                    new byte[]{
                            0,
                            0,
                            0,
                            12,
                            0,
                            0,
                            (byte) 0xC5,
                            0x45,
                            1,
                            0,
                            1,
                            2
                    })
                    || pinResponse.authMethod
                    != IkeV2SessionCrypto.PAIRING_AUTH_METHOD_PIN
                    || !Arrays.equals(pinSalt, pinResponse.pinSalt)
                    || !pinAccumulator.requestAcknowledged()
                    || pinResponse.peerRequestMessageId
                    != IkeV2SessionCrypto.PIN_METHOD_WATCH_MESSAGE_ID
                    || pinWatchAcknowledgement.size() != 1
                    || pinWatchAcknowledgement.get(0).length != 57
                    || (pinWatchAcknowledgement.get(0)[19] & 0xFF)
                    != 0x28) {
                throw new IllegalStateException(
                        "ML-KEM/IKE_AUTH/PIN-method round trip failed");
            }
            encapsulated.destroy();
            System.out.println(
                    "[WatchIkeSelfTest] PASS: X448 + 264-byte IKE_SA_INIT "
                            + "+ 269-byte uIKE + ERTM/no-FCS + "
                            + "AES-GCM/ML-KEM-1024 intermediate + "
                            + "RFC9242/NULL childless IKE_AUTH + "
                            + "bidirectional private notify "
                            + "0xC545/0xC546 PIN-method; "
                            + "external operations=0.");
        } catch (Throwable error) {
            System.out.println(
                    "[WatchIkeSelfTest] FAIL: "
                            + error.getClass().getSimpleName()
                            + ": "
                            + error.getMessage());
            System.exit(1);
        }
    }
}
