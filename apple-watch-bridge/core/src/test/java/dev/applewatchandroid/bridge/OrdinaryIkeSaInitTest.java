package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.Test;

public final class OrdinaryIkeSaInitTest {
    @Test
    public void emitsRecoveredOrdinaryRequestWireImage() {
        byte[] spi = sequence(1, 8);
        byte[] nonce = sequence(0x20, 32);
        byte[] publicKey = sequence(0x40, 56);

        byte[] request =
                OrdinaryIkeSaInit.buildRequest(
                        spi,
                        nonce,
                        publicKey);

        assertEquals(274, request.length);
        assertArrayEquals(spi, Arrays.copyOfRange(request, 0, 8));
        assertArrayEquals(new byte[8], Arrays.copyOfRange(request, 8, 16));
        assertArrayEquals(
                hex("21 20 22 08 00 00 00 00 00 00 01 12"),
                Arrays.copyOfRange(request, 16, 28));
        assertArrayEquals(
                hex("22 00 00 40 "
                        + "00 00 00 3c 01 01 00 06 "
                        + "03 00 00 0c 01 00 00 14 80 0e 01 00 "
                        + "03 00 00 08 01 00 00 1c "
                        + "03 00 00 08 02 00 00 07 "
                        + "03 00 00 08 06 00 00 24 "
                        + "03 00 00 08 04 00 00 20 "
                        + "00 00 00 08 04 00 00 1f"),
                Arrays.copyOfRange(request, 28, 92));

        OrdinaryIkeSaInit.Request parsed =
                OrdinaryIkeSaInit.parseRequest(request);
        assertArrayEquals(spi, parsed.initiatorSpi);
        assertArrayEquals(nonce, parsed.nonce);
        assertArrayEquals(publicKey, parsed.x448PublicKey);
    }

    @Test
    public void emitsRecoveredOrdinaryResponseWireImage() {
        byte[] initiatorSpi = sequence(1, 8);
        byte[] responderSpi = sequence(9, 8);
        byte[] nonce = sequence(0x30, 32);
        byte[] publicKey = sequence(0x60, 56);

        byte[] response =
                OrdinaryIkeSaInit.buildResponse(
                        initiatorSpi,
                        responderSpi,
                        nonce,
                        publicKey);

        assertEquals(266, response.length);
        assertArrayEquals(
                hex("21 20 22 20 00 00 00 00 00 00 01 0a"),
                Arrays.copyOfRange(response, 16, 28));
        assertArrayEquals(
                hex("22 00 00 30 "
                        + "00 00 00 2c 01 01 00 04 "
                        + "03 00 00 0c 01 00 00 14 80 0e 01 00 "
                        + "03 00 00 08 02 00 00 07 "
                        + "03 00 00 08 06 00 00 24 "
                        + "00 00 00 08 04 00 00 20"),
                Arrays.copyOfRange(response, 28, 76));

        OrdinaryIkeSaInit.Response parsed =
                OrdinaryIkeSaInit.parseResponse(
                        response,
                        initiatorSpi);
        assertArrayEquals(responderSpi, parsed.responderSpi);
        assertArrayEquals(nonce, parsed.nonce);
        assertArrayEquals(publicKey, parsed.x448PublicKey);
        assertEquals(
                OrdinaryIkeSaInit.CAPABILITY_CHILDLESS,
                parsed.capabilityFlags);
    }

    @Test
    public void classifiesUsePpkWithoutApplyingPairingPpk() {
        byte[] initiatorSpi = sequence(1, 8);
        byte[] response =
                OrdinaryIkeSaInit.buildResponse(
                        initiatorSpi,
                        sequence(9, 8),
                        sequence(0x30, 32),
                        sequence(0x60, 56));

        // The single target-profile optional Notify begins at byte 250;
        // only its public status type changes from Childless to USE_PPK.
        response[256] = 0x40;
        response[257] = 0x33;

        OrdinaryIkeSaInit.Response parsed =
                OrdinaryIkeSaInit.parseResponse(
                        response,
                        initiatorSpi);
        assertEquals(
                OrdinaryIkeSaInit.CAPABILITY_USE_PPK,
                parsed.capabilityFlags);
        assertFalse((parsed.capabilityFlags
                & OrdinaryIkeSaInit.CAPABILITY_CHILDLESS) != 0);
        assertTrue((parsed.capabilityFlags
                & OrdinaryIkeSaInit.CAPABILITY_USE_PPK) != 0);
    }

    @Test
    public void acceptsPhysicalWatchChildlessIkeProtocolId() {
        byte[] initiatorSpi = sequence(1, 8);
        byte[] response =
                OrdinaryIkeSaInit.buildResponse(
                        initiatorSpi,
                        sequence(9, 8),
                        sequence(0x30, 32),
                        sequence(0x60, 56));

        // Physical Watch7,5/watchOS 26.2 marks the otherwise empty
        // CHILDLESS status Notify as IKE-level protocol ID 1.
        response[254] = 1;

        OrdinaryIkeSaInit.Response parsed =
                OrdinaryIkeSaInit.parseResponse(
                        response,
                        initiatorSpi);
        assertEquals(
                OrdinaryIkeSaInit.CAPABILITY_CHILDLESS,
                parsed.capabilityFlags);
    }

    @Test
    public void rejectsIkeProtocolIdForUsePpk() {
        byte[] initiatorSpi = sequence(1, 8);
        byte[] response =
                OrdinaryIkeSaInit.buildResponse(
                        initiatorSpi,
                        sequence(9, 8),
                        sequence(0x30, 32),
                        sequence(0x60, 56));
        response[254] = 1;
        response[256] = 0x40;
        response[257] = 0x33;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OrdinaryIkeSaInit.parseResponse(
                        response,
                        initiatorSpi));
        assertTrue(failure.getMessage().contains("index 4"));
        assertTrue(failure.getMessage().contains("actual=0x4033"));
        assertTrue(failure.getMessage().contains("protocolId=1"));
    }

    @Test
    public void rejectsUnknownOptionalNotifyWithSafeMetadata() {
        byte[] initiatorSpi = sequence(1, 8);
        byte[] response =
                OrdinaryIkeSaInit.buildResponse(
                        initiatorSpi,
                        sequence(9, 8),
                        sequence(0x30, 32),
                        sequence(0x60, 56));
        response[256] = 0x7f;
        response[257] = 0x01;

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OrdinaryIkeSaInit.parseResponse(
                        response,
                        initiatorSpi));
        assertTrue(failure.getMessage().contains("index 4"));
        assertTrue(failure.getMessage().contains("actual=0x7F01"));
        assertTrue(failure.getMessage().contains("bodyLength=4"));
        assertTrue(failure.getMessage().contains("data bytes logged=false"));
    }

    @Test
    public void derivesIdenticalKeysForBothPreludeElectedRoles() {
        SecureRandom random = new SecureRandom();
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
        try {
            IkeV2SessionCrypto.IkeSaKeys initiatorKeys =
                    OrdinaryIkeSaInit
                            .deriveInitiatorKeys(
                                    initiator,
                                    response);
            IkeV2SessionCrypto.IkeSaKeys responderKeys =
                    OrdinaryIkeSaInit
                            .deriveResponderKeys(
                                    request,
                                    responder);
            assertArrayEquals(initiatorKeys.skD, responderKeys.skD);
            assertArrayEquals(initiatorKeys.skEi, responderKeys.skEi);
            assertArrayEquals(initiatorKeys.skEr, responderKeys.skEr);
            assertArrayEquals(initiatorKeys.skPi, responderKeys.skPi);
            assertArrayEquals(initiatorKeys.skPr, responderKeys.skPr);
        } finally {
            initiator.destroy();
            responder.destroy();
        }
    }

    @Test
    public void acceptsEndpointDerivedNatDetectionHashes() {
        byte[] initiatorSpi = sequence(1, 8);
        byte[] response =
                OrdinaryIkeSaInit.buildResponse(
                        initiatorSpi,
                        sequence(9, 8),
                        sequence(0x30, 32),
                        sequence(0x60, 56));

        System.arraycopy(
                sequence(0x70, 20),
                0,
                response,
                184,
                20);
        System.arraycopy(
                sequence(0x90, 20),
                0,
                response,
                212,
                20);

        OrdinaryIkeSaInit.Response parsed =
                OrdinaryIkeSaInit.parseResponse(
                        response,
                        initiatorSpi);
        assertArrayEquals(
                sequence(9, 8),
                parsed.responderSpi);
    }

    @Test
    public void rejectsProposalNotifyTypeOrHeaderMutation() {
        byte[] request =
                OrdinaryIkeSaInit.buildRequest(
                        sequence(1, 8),
                        sequence(0x20, 32),
                        sequence(0x40, 56));

        byte[] wrongKem = request.clone();
        wrongKem[75] = 0x25;
        assertThrows(
                IllegalArgumentException.class,
                () -> OrdinaryIkeSaInit.parseRequest(
                        wrongKem));

        byte[] wrongNotifyType = request.clone();
        wrongNotifyType[199] = 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> OrdinaryIkeSaInit.parseRequest(
                        wrongNotifyType));

        byte[] wrongFlags = request.clone();
        wrongFlags[19] = 0x20;
        assertThrows(
                IllegalArgumentException.class,
                () -> OrdinaryIkeSaInit.parseRequest(
                        wrongFlags));
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) (first + index);
        }
        return result;
    }

    private static byte[] hex(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(
                            index * 2,
                            index * 2 + 2),
                    16);
        }
        return output;
    }
}
