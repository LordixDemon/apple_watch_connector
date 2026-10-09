package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.Test;

public final class OrdinaryIkeAuthTest {
    @Test
    public void completesExactRoundTripWithAndroidInEitherRole() {
        runRoundTrip(true, true);
        runRoundTrip(false, false);
    }

    @Test
    public void encryptedInitiatorIdentifierNamesRecipientIdentity() {
        SecureRandom random = new SecureRandom();
        AuthFixture fixture = AuthFixture.create(random, true);
        OrdinaryIkeAuth.InitiatorRequest request = null;
        byte[] idBody = null;
        byte[] encryptedIdentifier = null;
        byte[] opened = null;
        byte[] expectedRecipientUuid = null;
        byte[] actualRecipientUuid = null;
        try {
            request = fixture.createRequest(random);
            idBody = Arrays.copyOfRange(
                    request.plaintext,
                    4,
                    4 + AppleIkeEncryptedKeyId
                            .ID_PAYLOAD_BODY_LENGTH);
            encryptedIdentifier =
                    AppleIkeEncryptedKeyId
                            .parseIdPayloadBody(idBody);
            opened = fixture.responderMaterial
                    .openEncryptedIdentity(
                            OrdinaryIkeAuth.DataClass.CLASS_D
                                    .serviceKeyId,
                            fixture.intermediateResult
                                    .updatedKeys.initiatorNonce,
                            fixture.intermediateResult
                                    .updatedKeys.responderNonce,
                            fixture.intermediateResult
                                    .updatedKeys.initiatorSpi,
                            fixture.intermediateResult
                                    .updatedKeys.responderSpi,
                            encryptedIdentifier);
            expectedRecipientUuid =
                    fixture.responderMaterial.identityUuid();
            actualRecipientUuid =
                    AppleIkeEncryptedKeyId
                            .extractAndValidateIdentityUuid(
                                    opened,
                                    expectedRecipientUuid);
            assertArrayEquals(
                    expectedRecipientUuid,
                    actualRecipientUuid);
        } finally {
            wipe(idBody);
            wipe(encryptedIdentifier);
            wipe(opened);
            wipe(expectedRecipientUuid);
            wipe(actualRecipientUuid);
            if (request != null) {
                request.destroy();
            }
            fixture.destroy();
        }
    }

    @Test
    public void rejectsWrongPreludePeerKeyAndAeadMutation() {
        SecureRandom random = new SecureRandom();
        AuthFixture fixture =
                AuthFixture.create(
                        random,
                        true);
        OrdinaryIkeAuth.InitiatorRequest request = null;
        OrdinaryIkeAuth.ResponderResult response = null;
        OrdinaryIkeAuth.PeerMaterial wrongPeer = null;
        AppleNetworkRelayPairingMaterial unrelated = null;
        try {
            request = fixture.createRequest(random);

            byte[] mutatedRequest =
                    request.packet.clone();
            mutatedRequest[
                    mutatedRequest.length - 1] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.respond(
                            random,
                            mutatedRequest));

            response =
                    fixture.respond(
                            random,
                            request.packet);
            OrdinaryIkeAuth.InitiatorRequest finalRequest =
                    request;
            OrdinaryIkeAuth.ResponderResult finalResponse =
                    response;
            byte[] wrongPreludeUuid =
                    fixture.responderPreludeUuid.clone();
            wrongPreludeUuid[14] ^= 1;
            byte[] wrongPrelude =
                    NrLinkBluetoothPrelude.encodeFreshModern(
                            wrongPreludeUuid,
                            false,
                            false);
            try {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> fixture.authenticate(
                                finalRequest,
                                finalResponse.packet,
                                wrongPrelude,
                                fixture.responderPeer));
            } finally {
                wipe(wrongPreludeUuid);
                wipe(wrongPrelude);
            }

            unrelated =
                    AppleNetworkRelayPairingMaterial
                            .generate(random);
            wrongPeer =
                    peerFrom(unrelated);
            OrdinaryIkeAuth.PeerMaterial finalWrongPeer =
                    wrongPeer;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.authenticate(
                            finalRequest,
                            finalResponse.packet,
                            fixture.responderPrelude,
                            finalWrongPeer));

            byte[] mutatedResponse =
                    response.packet.clone();
            mutatedResponse[
                    mutatedResponse.length - 1] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.authenticate(
                            finalRequest,
                            mutatedResponse,
                            fixture.responderPrelude,
                            fixture.responderPeer));
        } finally {
            if (wrongPeer != null) {
                wrongPeer.destroy();
            }
            if (unrelated != null) {
                unrelated.destroy();
            }
            if (response != null) {
                response.destroy();
            }
            if (request != null) {
                request.destroy();
            }
            fixture.destroy();
        }
    }

    @Test
    public void keepsClassCServiceAndSigningKeysSeparateFromClassD() {
        SecureRandom random = new SecureRandom();
        AuthFixture fixture =
                AuthFixture.create(
                        random,
                        true);
        OrdinaryIkeAuth.InitiatorRequest request = null;
        OrdinaryIkeAuth.ResponderResult response = null;
        OrdinaryIkeAuth.InitiatorResult result = null;
        OrdinaryIkeAuth.PeerMaterial classDOnlyPeer = null;
        try {
            request = fixture.createRequest(
                    random,
                    OrdinaryIkeAuth.DataClass.CLASS_C);
            OrdinaryIkeAuth.InitiatorRequest finalRequest =
                    request;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.respond(
                            random,
                            finalRequest.packet,
                            OrdinaryIkeAuth.DataClass.CLASS_D));

            response = fixture.respond(
                    random,
                    request.packet,
                    OrdinaryIkeAuth.DataClass.CLASS_C);
            assertEquals(
                    OrdinaryIkeAuth.REQUEST_PACKET_LENGTH,
                    request.packet.length);
            assertEquals(
                    712,
                    response.packet.length);

            OrdinaryIkeAuth.ResponderResult finalResponse =
                    response;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.authenticate(
                            finalRequest,
                            finalResponse.packet,
                            fixture.responderPrelude,
                            fixture.responderPeer,
                            OrdinaryIkeAuth.DataClass.CLASS_D));

            byte[] identity =
                    fixture.responderMaterial.identityPayload();
            byte[] classD =
                    fixture.responderMaterial
                            .classDPublicKeysPayload();
            try {
                classDOnlyPeer =
                        OrdinaryIkeAuth.PeerMaterial
                                .fromPairingPayloads(
                                        identity,
                                        classD);
            } finally {
                wipe(identity);
                wipe(classD);
            }
            OrdinaryIkeAuth.PeerMaterial finalClassDOnlyPeer =
                    classDOnlyPeer;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> fixture.authenticate(
                            finalRequest,
                            finalResponse.packet,
                            fixture.responderPrelude,
                            finalClassDOnlyPeer,
                            OrdinaryIkeAuth.DataClass.CLASS_C));

            result = fixture.authenticate(
                    request,
                    response.packet,
                    fixture.responderPrelude,
                    fixture.responderPeer,
                    OrdinaryIkeAuth.DataClass.CLASS_C);
            assertAddressesEqual(
                    fixture.addresses,
                    result.addresses);
        } finally {
            if (classDOnlyPeer != null) {
                classDOnlyPeer.destroy();
            }
            if (result != null) {
                result.destroy();
            }
            if (response != null) {
                response.destroy();
            }
            if (request != null) {
                request.destroy();
            }
            fixture.destroy();
        }
    }

    private static void runRoundTrip(
            boolean androidIsInitiator,
            boolean exactUltraLengthFixture) {
        SecureRandom random = new SecureRandom();
        AuthFixture fixture =
                AuthFixture.create(
                        random,
                        exactUltraLengthFixture);
        OrdinaryIkeAuth.InitiatorRequest request = null;
        OrdinaryIkeAuth.ResponderResult response = null;
        OrdinaryIkeAuth.InitiatorResult result = null;
        try {
            byte[] androidUuid = androidIsInitiator
                    ? fixture.initiatorPreludeUuid
                    : fixture.responderPreludeUuid;
            byte[] watchUuid = androidIsInitiator
                    ? fixture.responderPreludeUuid
                    : fixture.initiatorPreludeUuid;
            assertEquals(
                    androidIsInitiator
                            ? NrLinkBluetoothPrelude.LocalRole.INITIATOR
                            : NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    NrLinkBluetoothPrelude.electLocalRole(
                            androidUuid,
                            watchUuid));

            request = fixture.createRequest(random);
            assertEquals(
                    OrdinaryIkeAuth.REQUEST_INNER_LENGTH,
                    request.plaintext.length);
            assertEquals(
                    OrdinaryIkeAuth.REQUEST_PACKET_LENGTH,
                    request.packet.length);
            assertEquals(
                    IkeV2SessionCrypto.PAYLOAD_SK,
                    request.packet[16] & 0xff);
            assertEquals(
                    IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                    request.packet[18] & 0xff);
            assertEquals(
                    IkeV2Codec.IKE_FLAG_INITIATOR,
                    request.packet[19] & 0xff);
            assertEquals(
                    OrdinaryIkeAuth.MESSAGE_ID,
                    be32(request.packet, 20));

            response =
                    fixture.respond(
                            random,
                            request.packet);
            String payloadStructure =
                    OrdinaryIkeAuth
                            .describeResponderPayloadStructure(
                                    response.plaintext);
            assertTrue(payloadStructure.contains(
                    exactUltraLengthFixture
                            ? "count=24"
                            : "count=23"));
            assertTrue(payloadStructure.contains(
                    "payloadTypes=[0x29,0x24,0x27,0x2F"));
            assertTrue(payloadStructure.contains("type=0xBDDA"));
            assertEquals(
                    exactUltraLengthFixture,
                    payloadStructure.contains("type=0xBDDF"));
            assertTrue(payloadStructure.contains(
                    "bodies logged=false"));
            assertArrayEquals(
                    request.initiatorChildSpi,
                    response.initiatorChildSpi);
            assertEquals(
                    IkeV2Codec.IKE_FLAG_RESPONSE,
                    response.packet[19] & 0xff);
            if (exactUltraLengthFixture) {
                assertEquals(
                        655,
                        response.plaintext.length);
                assertEquals(
                        712,
                        response.packet.length);
            }

            result =
                    fixture.authenticate(
                            request,
                            response.packet,
                            fixture.responderPrelude,
                            fixture.responderPeer);
            assertArrayEquals(
                    request.initiatorChildSpi,
                    result.initiatorChildSpi);
            assertArrayEquals(
                    response.responderChildSpi,
                    result.responderChildSpi);
            assertEquals(
                    exactUltraLengthFixture ? 2 : 1,
                    result.metadata.deviceType);
            assertFalse(
                    result.metadata.deviceName.isEmpty());

            assertAddressesEqual(
                    fixture.addresses,
                    result.addresses);
        } finally {
            if (result != null) {
                result.destroy();
            }
            if (response != null) {
                response.destroy();
            }
            if (request != null) {
                request.destroy();
            }
            fixture.destroy();
        }
    }

    private static void assertAddressesEqual(
            AppleNetworkRelayInnerAddresses expected,
            AppleNetworkRelayInnerAddresses actual) {
        byte[] expectedInitiatorD =
                expected.initiatorClassD();
        byte[] expectedResponderD =
                expected.responderClassD();
        byte[] expectedInitiatorC =
                expected.initiatorClassC();
        byte[] expectedResponderC =
                expected.responderClassC();
        byte[] actualInitiatorD =
                actual.initiatorClassD();
        byte[] actualResponderD =
                actual.responderClassD();
        byte[] actualInitiatorC =
                actual.initiatorClassC();
        byte[] actualResponderC =
                actual.responderClassC();
        try {
            assertArrayEquals(
                    expectedInitiatorD,
                    actualInitiatorD);
            assertArrayEquals(
                    expectedResponderD,
                    actualResponderD);
            assertArrayEquals(
                    expectedInitiatorC,
                    actualInitiatorC);
            assertArrayEquals(
                    expectedResponderC,
                    actualResponderC);
        } finally {
            wipe(expectedInitiatorD);
            wipe(expectedResponderD);
            wipe(expectedInitiatorC);
            wipe(expectedResponderC);
            wipe(actualInitiatorD);
            wipe(actualResponderD);
            wipe(actualInitiatorC);
            wipe(actualResponderC);
        }
    }

    private static OrdinaryIkeAuth.PeerMaterial peerFrom(
            AppleNetworkRelayPairingMaterial material) {
        byte[] identity = material.identityPayload();
        byte[] classD =
                material.classDPublicKeysPayload();
        byte[] classC =
                material.classCPublicKeysPayload();
        try {
            return OrdinaryIkeAuth.PeerMaterial
                    .fromPairingPayloads(
                            identity,
                            classD,
                            classC);
        } finally {
            wipe(identity);
            wipe(classD);
            wipe(classC);
        }
    }

    private static int be32(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 24)
                | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8)
                | (value[offset + 3] & 0xff);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class AuthFixture {
        final OrdinaryIkeSaInit.InitiatorState saInitiator;
        final OrdinaryIkeSaInit.ResponderState saResponder;
        final IkeV2SessionCrypto.IkeSaKeys initialInitiatorKeys;
        final IkeV2SessionCrypto.IkeSaKeys initialResponderKeys;
        final OrdinaryIkeIntermediate.InitiatorRequest
                intermediateRequest;
        final OrdinaryIkeIntermediate.ResponderResult
                intermediateResponse;
        final OrdinaryIkeIntermediate.InitiatorResult
                intermediateResult;
        final AppleNetworkRelayPairingMaterial initiatorMaterial;
        final AppleNetworkRelayPairingMaterial responderMaterial;
        final OrdinaryIkeAuth.PeerMaterial initiatorPeer;
        final OrdinaryIkeAuth.PeerMaterial responderPeer;
        final AppleNetworkRelayInnerAddresses addresses;
        final OrdinaryIkeAuth.ResponderProfile profile;
        final byte[] initiatorPreludeUuid;
        final byte[] responderPreludeUuid;
        final byte[] responderPrelude;

        private AuthFixture(
                OrdinaryIkeSaInit.InitiatorState saInitiator,
                OrdinaryIkeSaInit.ResponderState saResponder,
                IkeV2SessionCrypto.IkeSaKeys initialInitiatorKeys,
                IkeV2SessionCrypto.IkeSaKeys initialResponderKeys,
                OrdinaryIkeIntermediate.InitiatorRequest
                        intermediateRequest,
                OrdinaryIkeIntermediate.ResponderResult
                        intermediateResponse,
                OrdinaryIkeIntermediate.InitiatorResult
                        intermediateResult,
                AppleNetworkRelayPairingMaterial initiatorMaterial,
                AppleNetworkRelayPairingMaterial responderMaterial,
                OrdinaryIkeAuth.PeerMaterial initiatorPeer,
                OrdinaryIkeAuth.PeerMaterial responderPeer,
                AppleNetworkRelayInnerAddresses addresses,
                OrdinaryIkeAuth.ResponderProfile profile,
                byte[] initiatorPreludeUuid,
                byte[] responderPreludeUuid,
                byte[] responderPrelude) {
            this.saInitiator = saInitiator;
            this.saResponder = saResponder;
            this.initialInitiatorKeys =
                    initialInitiatorKeys;
            this.initialResponderKeys =
                    initialResponderKeys;
            this.intermediateRequest =
                    intermediateRequest;
            this.intermediateResponse =
                    intermediateResponse;
            this.intermediateResult =
                    intermediateResult;
            this.initiatorMaterial =
                    initiatorMaterial;
            this.responderMaterial =
                    responderMaterial;
            this.initiatorPeer = initiatorPeer;
            this.responderPeer = responderPeer;
            this.addresses = addresses;
            this.profile = profile;
            this.initiatorPreludeUuid =
                    initiatorPreludeUuid.clone();
            this.responderPreludeUuid =
                    responderPreludeUuid.clone();
            this.responderPrelude =
                    responderPrelude.clone();
        }

        static AuthFixture create(
                SecureRandom random,
                boolean ultraResponder) {
            OrdinaryIkeSaInit.InitiatorState saInitiator =
                    OrdinaryIkeSaInit.createInitiator(
                            random);
            OrdinaryIkeSaInit.Request parsedRequest =
                    OrdinaryIkeSaInit.parseRequest(
                            saInitiator.packet);
            OrdinaryIkeSaInit.ResponderState saResponder =
                    OrdinaryIkeSaInit.createResponder(
                            random,
                            parsedRequest);
            OrdinaryIkeSaInit.Response parsedResponse =
                    OrdinaryIkeSaInit.parseResponse(
                            saResponder.packet,
                            saInitiator.initiatorSpi);
            IkeV2SessionCrypto.IkeSaKeys initiatorInitial =
                    OrdinaryIkeSaInit.deriveInitiatorKeys(
                            saInitiator,
                            parsedResponse);
            IkeV2SessionCrypto.IkeSaKeys responderInitial =
                    OrdinaryIkeSaInit.deriveResponderKeys(
                            parsedRequest,
                            saResponder);
            assertArrayEquals(
                    initiatorInitial.skD,
                    responderInitial.skD);

            OrdinaryIkeIntermediate.InitiatorRequest
                    intermediateRequest =
                    OrdinaryIkeIntermediate
                            .createInitiatorRequest(
                                    random,
                                    initiatorInitial);
            OrdinaryIkeIntermediate.ResponderResult
                    intermediateResponse =
                    OrdinaryIkeIntermediate.respond(
                            random,
                            responderInitial,
                            intermediateRequest.packet);
            OrdinaryIkeIntermediate.InitiatorResult
                    intermediateResult =
                    OrdinaryIkeIntermediate
                            .completeInitiator(
                                    intermediateRequest,
                                    initiatorInitial,
                                    intermediateResponse.packet);

            AppleNetworkRelayPairingMaterial initiatorMaterial =
                    AppleNetworkRelayPairingMaterial
                            .generate(random);
            AppleNetworkRelayPairingMaterial responderMaterial =
                    AppleNetworkRelayPairingMaterial
                            .generate(random);
            OrdinaryIkeAuth.PeerMaterial initiatorPeer =
                    peerFrom(initiatorMaterial);
            OrdinaryIkeAuth.PeerMaterial responderPeer =
                    peerFrom(responderMaterial);
            AppleNetworkRelayInnerAddresses addresses =
                    AppleNetworkRelayInnerAddresses
                            .generate(random);
            byte[] initiatorUuid = new byte[16];
            byte[] responderUuid = new byte[16];
            initiatorUuid[15] = 1;
            responderUuid[15] = 2;
            byte[] responderPrelude =
                    NrLinkBluetoothPrelude.encodeFreshModern(
                            responderUuid,
                            false,
                            false);
            OrdinaryIkeAuth.ResponderProfile profile =
                    new OrdinaryIkeAuth.ResponderProfile(
                            addresses,
                            responderPrelude,
                            ultraResponder
                                    ? "Apple Watch"
                                    : "OnePlus 13",
                            ultraResponder
                                    ? "23U67x"
                                    : "CPH2655",
                            ultraResponder
                                    ? "12345678-1234-1234-1234-123456789012"
                                    : null,
                            ultraResponder ? 2 : 1,
                            true,
                            true);
            return new AuthFixture(
                    saInitiator,
                    saResponder,
                    initiatorInitial,
                    responderInitial,
                    intermediateRequest,
                    intermediateResponse,
                    intermediateResult,
                    initiatorMaterial,
                    responderMaterial,
                    initiatorPeer,
                    responderPeer,
                    addresses,
                    profile,
                    initiatorUuid,
                    responderUuid,
                    responderPrelude);
        }

        OrdinaryIkeAuth.InitiatorRequest createRequest(
                SecureRandom random) {
            return createRequest(
                    random,
                    OrdinaryIkeAuth.DataClass.CLASS_D);
        }

        OrdinaryIkeAuth.InitiatorRequest createRequest(
                SecureRandom random,
                OrdinaryIkeAuth.DataClass dataClass) {
            return OrdinaryIkeAuth.createInitiatorRequest(
                    dataClass,
                    random,
                    initiatorMaterial,
                    responderPeer,
                    saInitiator.packet,
                    intermediateResult.updatedKeys,
                    intermediateRequest.intAuthI,
                    intermediateResult.intAuthR);
        }

        OrdinaryIkeAuth.ResponderResult respond(
                SecureRandom random,
                byte[] requestPacket) {
            return respond(
                    random,
                    requestPacket,
                    OrdinaryIkeAuth.DataClass.CLASS_D);
        }

        OrdinaryIkeAuth.ResponderResult respond(
                SecureRandom random,
                byte[] requestPacket,
                OrdinaryIkeAuth.DataClass dataClass) {
            return OrdinaryIkeAuth
                    .authenticateAndRespond(
                            dataClass,
                            random,
                            responderMaterial,
                            initiatorPeer,
                            profile,
                            saInitiator.packet,
                            saResponder.packet,
                            intermediateResponse.updatedKeys,
                            intermediateResponse.intAuthI,
                            intermediateResponse.intAuthR,
                            requestPacket);
        }

        OrdinaryIkeAuth.InitiatorResult authenticate(
                OrdinaryIkeAuth.InitiatorRequest request,
                byte[] responsePacket,
                byte[] expectedPrelude,
                OrdinaryIkeAuth.PeerMaterial peer) {
            return authenticate(
                    request,
                    responsePacket,
                    expectedPrelude,
                    peer,
                    OrdinaryIkeAuth.DataClass.CLASS_D);
        }

        OrdinaryIkeAuth.InitiatorResult authenticate(
                OrdinaryIkeAuth.InitiatorRequest request,
                byte[] responsePacket,
                byte[] expectedPrelude,
                OrdinaryIkeAuth.PeerMaterial peer,
                OrdinaryIkeAuth.DataClass dataClass) {
            return OrdinaryIkeAuth.authenticateResponder(
                    dataClass,
                    initiatorMaterial,
                    peer,
                    expectedPrelude,
                    saInitiator.packet,
                    saResponder.packet,
                    intermediateResult.updatedKeys,
                    intermediateRequest.intAuthI,
                    intermediateResult.intAuthR,
                    request,
                    responsePacket);
        }

        void destroy() {
            wipe(initiatorPreludeUuid);
            wipe(responderPreludeUuid);
            wipe(responderPrelude);
            profile.destroy();
            addresses.destroy();
            initiatorPeer.destroy();
            responderPeer.destroy();
            initiatorMaterial.destroy();
            responderMaterial.destroy();
            intermediateResult.destroy();
            intermediateResponse.destroy();
            intermediateRequest.destroy();
            initialInitiatorKeys.destroy();
            initialResponderKeys.destroy();
            saInitiator.destroy();
            saResponder.destroy();
        }
    }
}
