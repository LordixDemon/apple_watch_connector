package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.security.SecureRandom;
import org.junit.Test;

public final class OrdinaryIkeIntermediateTest {
    @Test
    public void completesExactMlKem768RoundTrip() {
        SecureRandom random = new SecureRandom();
        Fixture fixture = createFixture(random);
        OrdinaryIkeIntermediate.InitiatorRequest request = null;
        OrdinaryIkeIntermediate.ResponderResult responder = null;
        OrdinaryIkeIntermediate.InitiatorResult initiator = null;
        try {
            request =
                    OrdinaryIkeIntermediate
                            .createInitiatorRequest(
                                    random,
                                    fixture.initialKeys);
            assertEquals(
                    OrdinaryIkeIntermediate.PUBLIC_KEY_LENGTH,
                    request.publicKey.length);
            assertEquals(
                    8 + OrdinaryIkeIntermediate.PUBLIC_KEY_LENGTH,
                    request.plaintext.length);
            assertEquals(
                    OrdinaryIkeIntermediate.REQUEST_PACKET_LENGTH,
                    request.packet.length);
            assertEquals(64, request.intAuthI.length);
            assertEquals(
                    OrdinaryIkeIntermediate.REQUEST_CANONICAL_LENGTH,
                    IkeV2SessionCrypto
                            .buildIntermediateIntAuthInput(
                                    fixture.initialKeys.initiatorSpi,
                                    fixture.initialKeys.responderSpi,
                                    false,
                                    IkeV2Codec.PAYLOAD_KE,
                                    0,
                                    request.plaintext)
                            .length);

            responder =
                    OrdinaryIkeIntermediate.respond(
                            random,
                            fixture.initialKeys,
                            request.packet);
            assertEquals(
                    OrdinaryIkeIntermediate.RESPONSE_PACKET_LENGTH,
                    responder.packet.length);
            assertEquals(
                    8 + OrdinaryIkeIntermediate.CIPHERTEXT_LENGTH,
                    responder.plaintext.length);
            assertEquals(
                    OrdinaryIkeIntermediate.RESPONSE_CANONICAL_LENGTH,
                    IkeV2SessionCrypto
                            .buildIntermediateIntAuthInput(
                                    fixture.initialKeys.initiatorSpi,
                                    fixture.initialKeys.responderSpi,
                                    true,
                                    IkeV2Codec.PAYLOAD_KE,
                                    0,
                                    responder.plaintext)
                            .length);

            initiator =
                    OrdinaryIkeIntermediate
                            .completeInitiator(
                                    request,
                                    fixture.initialKeys,
                                    responder.packet);
            assertArrayEquals(
                    request.intAuthI,
                    responder.intAuthI);
            assertArrayEquals(
                    responder.intAuthI,
                    initiator.intAuthI);
            assertArrayEquals(
                    responder.intAuthR,
                    initiator.intAuthR);
            assertArrayEquals(
                    responder.updatedKeys.skD,
                    initiator.updatedKeys.skD);
            assertArrayEquals(
                    responder.updatedKeys.skEi,
                    initiator.updatedKeys.skEi);
            assertArrayEquals(
                    responder.updatedKeys.skEr,
                    initiator.updatedKeys.skEr);
            assertArrayEquals(
                    responder.updatedKeys.skPi,
                    initiator.updatedKeys.skPi);
            assertArrayEquals(
                    responder.updatedKeys.skPr,
                    initiator.updatedKeys.skPr);
        } finally {
            if (initiator != null) {
                initiator.destroy();
            }
            if (responder != null) {
                responder.destroy();
            }
            if (request != null) {
                request.destroy();
            }
            fixture.destroy();
        }
    }

    @Test
    public void rejectsAuthenticatedMutationOrWrongKemMethod() {
        SecureRandom random = new SecureRandom();
        Fixture fixture = createFixture(random);
        OrdinaryIkeIntermediate.InitiatorRequest request =
                OrdinaryIkeIntermediate
                        .createInitiatorRequest(
                                random,
                                fixture.initialKeys);
        try {
            byte[] badTag = request.packet.clone();
            badTag[badTag.length - 1] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OrdinaryIkeIntermediate.respond(
                            random,
                            fixture.initialKeys,
                            badTag));

            byte[] wrongMethod =
                    OrdinaryIkeIntermediate.buildKePayload(
                            new byte[
                                    OrdinaryIkeIntermediate
                                            .PUBLIC_KEY_LENGTH]);
            wrongMethod[5] = 0x25;
            byte[] wrongMethodPacket =
                    IkeV2SessionCrypto
                            .encryptIntermediatePayload(
                                    random,
                                    fixture.initialKeys.initiatorSpi,
                                    fixture.initialKeys.responderSpi,
                                    fixture.initialKeys.skEi,
                                    false,
                                    wrongMethod,
                                    IkeV2Codec.PAYLOAD_KE,
                                    1280)
                            .get(0);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> OrdinaryIkeIntermediate.respond(
                            random,
                            fixture.initialKeys,
                            wrongMethodPacket));
        } finally {
            request.destroy();
            fixture.destroy();
        }
    }

    private static Fixture createFixture(
            SecureRandom random) {
        OrdinaryIkeSaInit.InitiatorState initiator =
                OrdinaryIkeSaInit.createInitiator(random);
        OrdinaryIkeSaInit.Request request =
                OrdinaryIkeSaInit.parseRequest(
                        initiator.packet);
        OrdinaryIkeSaInit.ResponderState responder =
                OrdinaryIkeSaInit.createResponder(
                        random,
                        request);
        OrdinaryIkeSaInit.Response response =
                OrdinaryIkeSaInit.parseResponse(
                        responder.packet,
                        initiator.initiatorSpi);
        IkeV2SessionCrypto.IkeSaKeys initialKeys =
                OrdinaryIkeSaInit
                        .deriveInitiatorKeys(
                                initiator,
                                response);
        return new Fixture(
                initiator,
                responder,
                initialKeys);
    }

    private static final class Fixture {
        final OrdinaryIkeSaInit.InitiatorState initiator;
        final OrdinaryIkeSaInit.ResponderState responder;
        final IkeV2SessionCrypto.IkeSaKeys initialKeys;

        Fixture(
                OrdinaryIkeSaInit.InitiatorState initiator,
                OrdinaryIkeSaInit.ResponderState responder,
                IkeV2SessionCrypto.IkeSaKeys initialKeys) {
            this.initiator = initiator;
            this.responder = responder;
            this.initialKeys = initialKeys;
        }

        void destroy() {
            initialKeys.destroy();
            initiator.destroy();
            responder.destroy();
        }
    }
}
