package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.math.ec.rfc7748.X448;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.List;

public final class IkeV2SessionCryptoTest {
    @Test
    public void opticalSaInitRejectsPinCapabilities() {
        byte[] spi = hex("0102030405060708");
        byte[] pin = selectedPairingSaInitResponse();
        assertThrows(IllegalArgumentException.class,
                () -> IkeV2SessionCrypto.parseOpticalPairingSaInitResponse(pin, spi));
        // Remove the two PIN-specific notifications from the selected response.
        byte[] optical = new byte[192];
        System.arraycopy(pin, 0, optical, 0, 184);
        System.arraycopy(pin, 202, optical, 184, 8);
        optical[27] = (byte) optical.length;
        assertArrayEquals(hex("1112131415161718"),
                IkeV2SessionCrypto.parseOpticalPairingSaInitResponse(optical, spi).responderSpi);
        assertThrows(IllegalArgumentException.class,
                () -> IkeV2SessionCrypto.parsePairingSaInitResponse(optical, spi));
    }

    @Test
    public void validatesPairingSecurePasswordAndPpkSelection() {
        byte[] initiatorSpi =
                hex("01 02 03 04 05 06 07 08");
        byte[] packet = selectedPairingSaInitResponse();

        IkeV2SessionCrypto.ControlSaInitResponse response =
                IkeV2SessionCrypto.parsePairingSaInitResponse(
                        packet,
                        initiatorSpi);

        assertArrayEquals(
                hex("11 12 13 14 15 16 17 18"),
                response.responderSpi);
        assertArrayEquals(
                hex("A0 A1 A2 A3 A4 A5 A6 A7"
                        + " A8 A9 AA AB AC AD AE AF"
                        + " B0 B1 B2 B3 B4 B5 B6 B7"
                        + " B8 B9 BA BB BC BD BE BF"),
                response.responderNonce);

        byte[] badMethod = packet.clone();
        badMethod[193] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> IkeV2SessionCrypto.parsePairingSaInitResponse(
                        badMethod,
                        initiatorSpi));

        byte[] missingPpk = packet.clone();
        missingPpk[201] ^= 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> IkeV2SessionCrypto.parsePairingSaInitResponse(
                        missingPpk,
                        initiatorSpi));
    }

    @Test
    public void appliesRecoveredMandatoryPpkKeyTransform() {
        byte[] ppk = hex(
                "6b5a77e96b100f1c14139afe4b9ccc8e"
                        + "60164d40220df33104879859cc828881"
                        + "aa342997cfba8a2affe94d701ac58529"
                        + "a225b2a5f1f75920382c71be4619d00f");
        IkeV2SessionCrypto.IkeSaKeys primeKeys =
                new IkeV2SessionCrypto.IkeSaKeys(
                        sequence(0x00, 64),
                        sequence(0x20, 36),
                        sequence(0x50, 36),
                        sequence(0x40, 64),
                        sequence(0x80, 64),
                        sequence(0x10, 8),
                        sequence(0x30, 8),
                        sequence(0xa0, 32),
                        sequence(0xc0, 32));

        IkeV2SessionCrypto.IkeSaKeys keys =
                IkeV2SessionCrypto.applyMandatoryPairingPpk(
                        primeKeys,
                        ppk);

        assertArrayEquals(
                hex("e8aeada3ed5bc78aa3295ccd7c77e74e"
                        + "f5f2ae41f3fd1d8fad4ea7b26b2d635c"
                        + "247f85c8f3a4d1d9758b3f5edfc0a1b5"
                        + "2ffd25f3f75c016c220b7d1c846ca2e7"),
                keys.skD);
        assertArrayEquals(
                hex("916ae0706f404aee28ac7b5f7623f81c"
                        + "67ce16648bb26782150898c8c0d93b2d"
                        + "c584bab21776d3c18178244250a08190"
                        + "fef35af89740350f3bfb9cae2485de5a"),
                keys.skPi);
        assertArrayEquals(
                hex("65084450fbbc4e2785a0b5786f1d8cb1"
                        + "e6b34fb073660240091ab570f02de52a"
                        + "7b4326562e6f30f032d38dd93e0be58b"
                        + "534e0b23170736b66bb2477fadf80619"),
                keys.skPr);
        assertArrayEquals(primeKeys.skEi, keys.skEi);
        assertArrayEquals(primeKeys.skEr, keys.skEr);
        assertArrayEquals(primeKeys.initiatorSpi, keys.initiatorSpi);
        assertArrayEquals(primeKeys.responderSpi, keys.responderSpi);
    }

    @Test
    public void completesRecoveredThreeStepPairingGspmExchange() {
        SecureRandom random = new SecureRandom();
        byte[] initiatorSpi =
                hex("01 02 03 04 05 06 07 08");
        byte[] responderSpi =
                hex("11 12 13 14 15 16 17 18");
        byte[] initiatorNonce = sequence(0x20, 32);
        byte[] responderNonce = sequence(0x60, 32);
        IkeV2Codec.InitiatorState initiator =
                new IkeV2Codec.InitiatorState(
                        initiatorSpi,
                        initiatorNonce,
                        new byte[56],
                        new byte[56],
                        sequence(0x01, 282),
                        new byte[0]);
        IkeV2SessionCrypto.ControlSaInitResponse response =
                new IkeV2SessionCrypto.ControlSaInitResponse(
                        responderSpi,
                        responderNonce,
                        new byte[56],
                        sequence(0x31, 210));
        IkeV2SessionCrypto.IkeSaKeys ppkKeys =
                new IkeV2SessionCrypto.IkeSaKeys(
                        sequence(0x00, 64),
                        sequence(0x20, 36),
                        sequence(0x50, 36),
                        sequence(0x40, 64),
                        sequence(0x80, 64),
                        initiatorSpi,
                        responderSpi,
                        initiatorNonce,
                        responderNonce);
        byte[] intAuthI = sequence(0xa0, 64);
        byte[] intAuthR = sequence(0xe0, 64);
        AppleSpake2PlusProver prover = rfcVectorProver();
        IkeV2SessionCrypto.PairingGspmInitiator pairing =
                IkeV2SessionCrypto
                        .createPairingGspmInitiatorForTest(
                                random,
                                initiator,
                                response,
                                ppkKeys,
                                intAuthI,
                                intAuthR,
                                prover);
        try {
            IkeV2SessionCrypto.PairingExchangeRequest first =
                    pairing.createFirstRequest(random);
            assertEquals(
                    IkeV2SessionCrypto
                            .PAIRING_FIRST_GSPM_MESSAGE_ID,
                    first.messageId);
            assertEquals(
                    AppleSpake2PlusProver.SHARE_LENGTH,
                    first.gspmDataLength);
            IkeV2SessionCrypto.DecryptedIntermediatePart
                    firstPlaintext =
                    IkeV2SessionCrypto.decryptProtectedPacket(
                            first.ikePackets.get(0),
                            initiatorSpi,
                            responderSpi,
                            ppkKeys.skEi,
                            false,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            IkeV2SessionCrypto
                                    .PAIRING_FIRST_GSPM_MESSAGE_ID);
            assertEquals(35, firstPlaintext.firstInnerPayload);
            assertEquals(
                    List.of(35, 41, 36, 49, 41),
                    payloadTypes(
                            firstPlaintext.firstInnerPayload,
                            firstPlaintext.plaintext));
            assertArrayEquals(
                    AppleWatchPairingCrypto
                            .physicalInitiatorIdPayloadBody(),
                    payloadBodyAt(firstPlaintext.plaintext, 0));
            assertEquals(
                    IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT,
                    notifyTypeAt(firstPlaintext.plaintext, 1));
            assertArrayEquals(
                    AppleWatchPairingCrypto
                            .saltedPinIdPayloadBody(),
                    payloadBodyAt(firstPlaintext.plaintext, 2));
            assertArrayEquals(
                    rfcProverShare(),
                    payloadBodyAt(firstPlaintext.plaintext, 3));
            assertEquals(
                    IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY,
                    notifyTypeAt(firstPlaintext.plaintext, 4));
            assertArrayEquals(
                    hex("00 00 40 34 01"),
                    payloadBodyAt(firstPlaintext.plaintext, 4));

            byte[] rejectionPlaintext =
                    genericPayload(
                            IkeV2Codec.PAYLOAD_NONE,
                            hex("00 00 00 18"));
            byte[] rejection =
                    IkeV2SessionCrypto.encryptProtectedPayload(
                            random,
                            initiatorSpi,
                            responderSpi,
                            ppkKeys.skEr,
                            true,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            IkeV2SessionCrypto
                                    .PAIRING_FIRST_GSPM_MESSAGE_ID,
                            rejectionPlaintext,
                            41,
                            IkeV2SessionCrypto
                                    .CONTROL_MAX_IKE_PACKET_SIZE)
                            .get(0);
            IllegalArgumentException rejectionError =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> pairing
                                    .acceptFirstResponseAndCreateSecondRequest(
                                            random,
                                            rejection));
            assertEquals(
                    "Pairing GSPM rejected by Watch: "
                            + "Notify=24 (0x0018), protocolId=0, "
                            + "spiSize=0, dataLength=0",
                    rejectionError.getMessage());

            byte[] firstResponsePlaintext = concatenate(
                    genericPayload(
                            IkeV2SessionCrypto.PAYLOAD_GSPM,
                            AppleWatchPairingCrypto
                                    .saltedPinIdPayloadBody()),
                    genericPayload(
                            IkeV2Codec.PAYLOAD_NONE,
                            rfcVerifierShare()));
            byte[] firstResponse =
                    IkeV2SessionCrypto.encryptProtectedPayload(
                            random,
                            initiatorSpi,
                            responderSpi,
                            ppkKeys.skEr,
                            true,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            IkeV2SessionCrypto
                                    .PAIRING_FIRST_GSPM_MESSAGE_ID,
                            firstResponsePlaintext,
                            36,
                            IkeV2SessionCrypto
                                    .CONTROL_MAX_IKE_PACKET_SIZE)
                            .get(0);
            IkeV2SessionCrypto.PairingExchangeRequest second =
                    pairing
                            .acceptFirstResponseAndCreateSecondRequest(
                                    random,
                                    firstResponse);
            IkeV2SessionCrypto.DecryptedIntermediatePart
                    secondPlaintext =
                    IkeV2SessionCrypto.decryptProtectedPacket(
                            second.ikePackets.get(0),
                            initiatorSpi,
                            responderSpi,
                            ppkKeys.skEi,
                            false,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            IkeV2SessionCrypto
                                    .PAIRING_SECOND_GSPM_MESSAGE_ID);
            assertEquals(
                    IkeV2SessionCrypto.PAYLOAD_GSPM,
                    secondPlaintext.firstInnerPayload);
            assertArrayEquals(
                    rfcProverConfirmation(),
                    lastPayloadBody(secondPlaintext.plaintext));

            byte[] secondResponsePlaintext =
                    genericPayload(
                            IkeV2Codec.PAYLOAD_NONE,
                            rfcVerifierConfirmation());
            byte[] secondResponse =
                    IkeV2SessionCrypto.encryptProtectedPayload(
                            random,
                            initiatorSpi,
                            responderSpi,
                            ppkKeys.skEr,
                            true,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            IkeV2SessionCrypto
                                    .PAIRING_SECOND_GSPM_MESSAGE_ID,
                            secondResponsePlaintext,
                            IkeV2SessionCrypto.PAYLOAD_GSPM,
                            IkeV2SessionCrypto
                                    .CONTROL_MAX_IKE_PACKET_SIZE)
                            .get(0);
            IkeV2SessionCrypto.PairingExchangeRequest finalRequest =
                    pairing
                            .acceptSecondResponseAndCreateFinalAuthRequest(
                                    random,
                                    secondResponse);
            assertEquals(64, finalRequest.authenticationDataLength);
            IkeV2SessionCrypto.DecryptedIntermediatePart
                    finalPlaintext =
                    IkeV2SessionCrypto.decryptProtectedPacket(
                            finalRequest.ikePackets.get(0),
                            initiatorSpi,
                            responderSpi,
                            ppkKeys.skEi,
                            false,
                            IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                            IkeV2SessionCrypto
                                    .PAIRING_FINAL_AUTH_MESSAGE_ID);
            assertEquals(
                    List.of(39, 41),
                    payloadTypes(
                            finalPlaintext.firstInnerPayload,
                            finalPlaintext.plaintext));
            assertEquals(
                    IkeV2SessionCrypto
                            .AUTH_METHOD_GENERIC_SECURE_PASSWORD,
                    finalPlaintext.plaintext[4] & 0xFF);
            assertArrayEquals(
                    hex("5361cef8f2bad737b2dfe31af87f0514"
                            + "c38726178623977d70ca5ad4737a8e70"
                            + "5397d534e9f25c7d5bf0dff178c8070b"
                            + "08aea8149f9c07e4037bdf5ba1fdf60e"),
                    java.util.Arrays.copyOfRange(
                            finalPlaintext.plaintext,
                            8,
                            72));
            assertEquals(
                    IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY,
                    notifyTypeAt(finalPlaintext.plaintext, 1));
            assertArrayEquals(
                    hex("00 00 40 34 01"),
                    lastPayloadBody(finalPlaintext.plaintext));

            List<byte[]> finalResponse =
                    IkeV2SessionCrypto
                            .createPairingFinalAuthResponseForTest(
                                    random,
                                    initiator,
                                    response,
                                    ppkKeys,
                                    intAuthI,
                                    intAuthR,
                                    rfcProverShare(),
                                    rfcVerifierShare(),
                                    rfcSharedKey());
            IkeV2SessionCrypto.PairingAuthResponse authResponse =
                    pairing.acceptFinalAuthResponse(
                            finalResponse.get(0));
            assertEquals(
                    List.of(41, 39, 41),
                    authResponse.payloadTypes);
            assertEquals(
                    List.of(
                            IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT,
                            IkeV2SessionCrypto.NOTIFY_PPK_IDENTITY),
                    authResponse.notifyTypes);
            assertEquals(64, authResponse.authenticationDataLength);
            assertTrue(pairing.isComplete());
        } finally {
            pairing.destroy();
        }
    }

    @Test
    public void describesUnexpectedPairingPayloadsWithoutBodyData() {
        byte[] firstNotifyData =
                hex("00 00 40 34 01 91 92 93");
        byte[] secondNotifyData =
                hex("00 00 40 00 A1 A2 A3 A4");
        byte[] plaintext = concatenate(
                genericPayload(
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        firstNotifyData),
                genericPayload(
                        IkeV2Codec.PAYLOAD_NONE,
                        secondNotifyData));
        IkeV2SessionCrypto.DecryptedIntermediatePart part =
                new IkeV2SessionCrypto.DecryptedIntermediatePart(
                        false,
                        0,
                        0,
                        IkeV2Codec.PAYLOAD_NOTIFY,
                        0,
                        plaintext);

        String description =
                IkeV2SessionCrypto
                        .describePairingProtectedPayloadStructure(
                                part,
                                "AUTH");

        assertTrue(description.contains(
                "first=41, totalLength=24"));
        assertTrue(description.contains(
                "Notify(type=16436,protocolId=0,"
                        + "spiSize=0,dataLength=4"));
        assertTrue(description.contains(
                "Notify(type=16384,protocolId=0,"
                        + "spiSize=0,dataLength=4"));
        assertFalse(description.contains("919293"));
        assertFalse(description.contains("A1A2A3A4"));
    }

    @Test
    public void parsesPhysicalWatchControlSaInitResponse() {
        byte[] initiatorSpi =
                hex("90 97 98 04 CF 73 ED 99");
        byte[] packet = hex(
                "90 97 98 04 CF 73 ED 99"
                        + " 26 65 93 E8 BF 21 E0 D3"
                        + " 21 20 22 20 00 00 00 00 00 00 01 00"
                        + " 22 00 00 30"
                        + " 00 00 00 2C 01 01 00 04"
                        + " 03 00 00 0C 01 00 00 14 80 0E 01 00"
                        + " 03 00 00 08 02 00 00 07"
                        + " 03 00 00 08 06 00 00 25"
                        + " 00 00 00 08 04 00 00 20"
                        + " 28 00 00 40 00 20 00 00"
                        + " 85 35 26 87 2A 5B 29 0F"
                        + " 0F 81 E1 18 E4 5E 07 A2"
                        + " 39 C5 90 C7 09 15 3C DE"
                        + " 37 2D A2 FF E3 3B 26 CD"
                        + " 82 DF DC EE 10 51 30 3B"
                        + " E0 79 B0 BC 09 75 1A 4F"
                        + " 8E 4D 01 95 16 26 DE 61"
                        + " 29 00 00 24"
                        + " C9 87 9A A2 FF 20 D4 5D"
                        + " BD 91 2B E7 F3 2D 4B C6"
                        + " 37 18 11 0C 2D E9 D1 8F"
                        + " AD B7 16 A9 7C 67 C0 CC"
                        + " 29 00 00 1C 00 00 40 04"
                        + " 78 F8 EE 6E 5C 4D 3B 64"
                        + " 39 6F E8 19 C0 41 6F 62"
                        + " B3 7F DF 9A"
                        + " 29 00 00 1C 00 00 40 05"
                        + " 85 40 9B CA D2 35 CE 66"
                        + " 92 EC DB F5 A6 3B 72 AC"
                        + " 1D B3 20 AE"
                        + " 29 00 00 08 00 00 40 2E"
                        + " 29 00 00 08 01 00 40 22"
                        + " 00 00 00 08 00 00 40 36");

        IkeV2SessionCrypto.ControlSaInitResponse response =
                IkeV2SessionCrypto.parseControlSaInitResponse(
                        packet,
                        initiatorSpi);

        assertArrayEquals(
                hex("26 65 93 E8 BF 21 E0 D3"),
                response.responderSpi);
        assertArrayEquals(
                hex("C9 87 9A A2 FF 20 D4 5D"
                        + " BD 91 2B E7 F3 2D 4B C6"
                        + " 37 18 11 0C 2D E9 D1 8F"
                        + " AD B7 16 A9 7C 67 C0 CC"),
                response.responderNonce);
        assertEquals(56, response.responderX448PublicKey.length);
        assertArrayEquals(packet, response.packet);
    }

    @Test
    public void completesFragmentedMlKemIntermediateRoundTrip()
            throws Exception {
        SecureRandom random = new SecureRandom();
        IkeV2Codec.InitiatorState initiator =
                IkeV2Codec.createControlSaInit(random);

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
        IkeV2SessionCrypto.ControlSaInitResponse response =
                new IkeV2SessionCrypto.ControlSaInitResponse(
                        responderSpi,
                        responderNonce,
                        responderPublic,
                        new byte[0]);
        IkeV2SessionCrypto.IkeSaKeys initialKeys =
                IkeV2SessionCrypto.deriveInitialKeys(
                        initiator,
                        response);

        IkeV2SessionCrypto.AdditionalKeRequest request =
                IkeV2SessionCrypto.createAdditionalKeRequest(
                        random,
                        initiator,
                        response,
                        initialKeys);
        assertEquals(
                IkeV2SessionCrypto.ML_KEM_1024_PUBLIC_KEY_LENGTH,
                request.publicKey.length);
        assertEquals(2, request.ikePackets.size());
        ByteArrayOutputStream requestPlaintext =
                new ByteArrayOutputStream();
        for (int index = 0;
                index < request.ikePackets.size();
                index++) {
            byte[] packet = request.ikePackets.get(index);
            assertTrue(
                    packet.length
                            <= IkeV2SessionCrypto
                            .CONTROL_MAX_IKE_PACKET_SIZE);
            IkeV2SessionCrypto.DecryptedIntermediatePart part =
                    IkeV2SessionCrypto.decryptProtectedIntermediate(
                            packet,
                            initiator.initiatorSpi,
                            responderSpi,
                            initialKeys.skEi,
                            false);
            assertTrue(part.fragmented);
            assertEquals(index + 1, part.fragmentNumber);
            assertEquals(2, part.totalFragments);
            requestPlaintext.writeBytes(part.plaintext);
        }
        byte[] expectedRequestPayload =
                IkeV2SessionCrypto.buildKePayload(request.publicKey);
        assertArrayEquals(
                expectedRequestPayload,
                requestPlaintext.toByteArray());
        assertEquals(64, request.initiatorIntAuth.length);

        SecretWithEncapsulation encapsulated =
                IkeV2SessionCrypto.encapsulateForTest(
                        random,
                        request.publicKey);
        byte[] responderKePayload =
                IkeV2SessionCrypto.buildKePayload(
                        encapsulated.getEncapsulation());
        List<byte[]> responsePackets =
                IkeV2SessionCrypto.encryptIntermediatePayload(
                        random,
                        initiator.initiatorSpi,
                        responderSpi,
                        initialKeys.skEr,
                        true,
                        responderKePayload,
                        IkeV2Codec.PAYLOAD_KE,
                        IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
        assertEquals(2, responsePackets.size());

        IkeV2SessionCrypto.IntermediateResponseAccumulator accumulator =
                new IkeV2SessionCrypto.IntermediateResponseAccumulator(
                        initialKeys);
        assertNull(accumulator.accept(responsePackets.get(1)));
        byte[] ciphertext =
                accumulator.accept(responsePackets.get(0));
        assertArrayEquals(
                encapsulated.getEncapsulation(),
                ciphertext);
        assertEquals(64, accumulator.responderIntAuth().length);

        IkeV2SessionCrypto.AdditionalKeResult result =
                IkeV2SessionCrypto.completeAdditionalKeyExchange(
                        request,
                        ciphertext,
                        initialKeys);
        assertArrayEquals(
                encapsulated.getSecret(),
                result.sharedSecret);
        assertEquals(64, result.updatedKeys.skD.length);
        assertEquals(36, result.updatedKeys.skEi.length);
        assertEquals(36, result.updatedKeys.skEr.length);
        assertFalse(allZero(result.updatedKeys.skEi));
        assertNotEquals(
                HciCodec.toHex(initialKeys.skD),
                HciCodec.toHex(result.updatedKeys.skD));
        encapsulated.destroy();
    }

    @Test
    public void reconstructsRfc9242IntermediateInputBeforeEncryption() {
        byte[] plaintext =
                hex("00 00 00 08 00 25 00 00");
        byte[] input =
                IkeV2SessionCrypto.buildIntermediateIntAuthInput(
                        hex("01 02 03 04 05 06 07 08"),
                        hex("11 12 13 14 15 16 17 18"),
                        false,
                        IkeV2Codec.PAYLOAD_KE,
                        0,
                        plaintext);

        assertArrayEquals(
                hex("01 02 03 04 05 06 07 08"
                        + " 11 12 13 14 15 16 17 18"
                        + " 2E 20 2B 08"
                        + " 00 00 00 01"
                        + " 00 00 00 28"
                        + " 22 00 00 0C"
                        + " 00 00 00 08 00 25 00 00"),
                input);
    }

    @Test
    public void completesChildlessNullIkeAuthAndRejectsTampering()
            throws Exception {
        SecureRandom random = new SecureRandom();
        IkeV2Codec.InitiatorState initiator =
                IkeV2Codec.createControlSaInit(random);
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
                        initiator,
                        response);
        IkeV2SessionCrypto.AdditionalKeRequest additionalRequest =
                IkeV2SessionCrypto.createAdditionalKeRequest(
                        random,
                        initiator,
                        response,
                        initialKeys);
        SecretWithEncapsulation encapsulated =
                IkeV2SessionCrypto.encapsulateForTest(
                        random,
                        additionalRequest.publicKey);
        byte[] responderKePayload =
                IkeV2SessionCrypto.buildKePayload(
                        encapsulated.getEncapsulation());
        List<byte[]> intermediatePackets =
                IkeV2SessionCrypto.encryptIntermediatePayload(
                        random,
                        initiator.initiatorSpi,
                        responderSpi,
                        initialKeys.skEr,
                        true,
                        responderKePayload,
                        IkeV2Codec.PAYLOAD_KE,
                        IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
        IkeV2SessionCrypto.IntermediateResponseAccumulator
                intermediateAccumulator =
                new IkeV2SessionCrypto
                        .IntermediateResponseAccumulator(initialKeys);
        byte[] responderCiphertext = null;
        for (byte[] packet : intermediatePackets) {
            byte[] accepted =
                    intermediateAccumulator.accept(packet);
            if (accepted != null) {
                responderCiphertext = accepted;
            }
        }
        assertArrayEquals(
                encapsulated.getEncapsulation(),
                responderCiphertext);
        IkeV2SessionCrypto.AdditionalKeResult additionalResult =
                IkeV2SessionCrypto.completeAdditionalKeyExchange(
                        additionalRequest,
                        responderCiphertext,
                        initialKeys);
        byte[] intAuthR =
                intermediateAccumulator.responderIntAuth();

        IkeV2SessionCrypto.IkeAuthRequest authRequest =
                IkeV2SessionCrypto.createControlIkeAuthRequest(
                        random,
                        initiator,
                        response,
                        additionalResult.updatedKeys,
                        additionalRequest.initiatorIntAuth,
                        intAuthR);
        assertEquals(1, authRequest.ikePackets.size());
        assertEquals(64, authRequest.authenticationDataLength);
        IkeV2SessionCrypto.DecryptedIntermediatePart requestPlaintext =
                IkeV2SessionCrypto.decryptProtectedPacket(
                        authRequest.ikePackets.get(0),
                        initiator.initiatorSpi,
                        responderSpi,
                        additionalResult.updatedKeys.skEi,
                        false,
                        IkeV2SessionCrypto.EXCHANGE_IKE_AUTH,
                        IkeV2SessionCrypto.IKE_AUTH_MESSAGE_ID);
        assertFalse(requestPlaintext.fragmented);
        assertEquals(35, requestPlaintext.firstInnerPayload);
        assertEquals(41, requestPlaintext.plaintext[0] & 0xFF);
        assertEquals(13, requestPlaintext.plaintext[4] & 0xFF);
        assertEquals(36, requestPlaintext.plaintext[8] & 0xFF);
        assertEquals(
                IkeV2SessionCrypto.NOTIFY_INITIAL_CONTACT,
                ((requestPlaintext.plaintext[14] & 0xFF) << 8)
                        | (requestPlaintext.plaintext[15] & 0xFF));

        List<byte[]> authResponsePackets =
                IkeV2SessionCrypto.createControlIkeAuthResponseForTest(
                        random,
                        initiator,
                        response,
                        additionalResult.updatedKeys,
                        additionalRequest.initiatorIntAuth,
                        intAuthR);
        assertEquals(1, authResponsePackets.size());
        IkeV2SessionCrypto.IkeAuthResponseAccumulator
                authAccumulator =
                new IkeV2SessionCrypto.IkeAuthResponseAccumulator(
                        initiator,
                        response,
                        additionalResult.updatedKeys,
                        additionalRequest.initiatorIntAuth,
                        intAuthR);
        IkeV2SessionCrypto.IkeAuthResponse authResponse =
                authAccumulator.accept(authResponsePackets.get(0));
        assertEquals(
                List.of(36, 39),
                authResponse.payloadTypes);
        assertEquals(64, authResponse.authenticationDataLength);

        IkeV2SessionCrypto.PinAuthMethodRequest pinRequest =
                IkeV2SessionCrypto.createPinAuthMethodRequest(
                        random,
                        additionalResult.updatedKeys);
        assertEquals(1, pinRequest.ikePackets.size());
        assertArrayEquals(
                hex("00 00 00 0C 00 00 C5 45 01 00 01 02"),
                pinRequest.plaintext);
        IkeV2SessionCrypto.DecryptedIntermediatePart
                pinRequestPlaintext =
                IkeV2SessionCrypto.decryptProtectedPacket(
                        pinRequest.ikePackets.get(0),
                        initiator.initiatorSpi,
                        responderSpi,
                        additionalResult.updatedKeys.skEi,
                        false,
                        IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                        IkeV2SessionCrypto.PIN_METHOD_MESSAGE_ID);
        assertFalse(pinRequestPlaintext.fragmented);
        assertEquals(41, pinRequestPlaintext.firstInnerPayload);
        assertArrayEquals(
                pinRequest.plaintext,
                pinRequestPlaintext.plaintext);

        byte[] pinSalt = new byte[32];
        random.nextBytes(pinSalt);
        List<byte[]> pinResponsePackets =
                IkeV2SessionCrypto.createPinAuthMethodResponseForTest(
                        random,
                        additionalResult.updatedKeys,
                        pinSalt);
        IkeV2SessionCrypto.PinAuthMethodResponseAccumulator
                pinAccumulator =
                new IkeV2SessionCrypto
                        .PinAuthMethodResponseAccumulator(
                                additionalResult.updatedKeys);
        IkeV2SessionCrypto.PinAuthMethodResponse pinResponse =
                pinAccumulator.accept(pinResponsePackets.get(0));
        assertEquals(List.of(41), pinResponse.payloadTypes);
        assertEquals(
                List.of(
                        IkeV2SessionCrypto
                                .PRIVATE_NOTIFY_AUTH_METHOD_RESPONSE),
                pinResponse.notifyTypes);
        assertEquals(
                IkeV2SessionCrypto.PAIRING_AUTH_METHOD_PIN,
                pinResponse.authMethod);
        assertArrayEquals(pinSalt, pinResponse.pinSalt);
        assertEquals(-1, pinResponse.peerRequestMessageId);

        List<byte[]> emptyPinAcknowledgement =
                IkeV2SessionCrypto
                        .createPinAuthMethodEmptyResponseForTest(
                                random,
                                additionalResult.updatedKeys);
        assertEquals(57, emptyPinAcknowledgement.get(0).length);
        IkeV2SessionCrypto.PinAuthMethodResponseAccumulator
                asynchronousPinAccumulator =
                new IkeV2SessionCrypto
                        .PinAuthMethodResponseAccumulator(
                                additionalResult.updatedKeys);
        assertNull(asynchronousPinAccumulator.accept(
                emptyPinAcknowledgement.get(0)));
        assertTrue(
                asynchronousPinAccumulator.requestAcknowledged());

        List<byte[]> watchPinRequest =
                IkeV2SessionCrypto
                        .createPinAuthMethodWatchRequestForTest(
                                random,
                                additionalResult.updatedKeys,
                                pinSalt);
        IkeV2SessionCrypto.PinAuthMethodResponse
                asynchronousPinResponse =
                asynchronousPinAccumulator.accept(
                        watchPinRequest.get(0));
        assertEquals(
                IkeV2SessionCrypto.PIN_METHOD_WATCH_MESSAGE_ID,
                asynchronousPinResponse.peerRequestMessageId);
        assertArrayEquals(
                pinSalt,
                asynchronousPinResponse.pinSalt);

        List<byte[]> watchAcknowledgement =
                IkeV2SessionCrypto
                        .createWatchInformationalAcknowledgement(
                                random,
                                additionalResult.updatedKeys,
                                asynchronousPinResponse
                                        .peerRequestMessageId);
        assertEquals(1, watchAcknowledgement.size());
        assertEquals(57, watchAcknowledgement.get(0).length);
        assertEquals(
                0x28,
                watchAcknowledgement.get(0)[19] & 0xFF);
        IkeV2SessionCrypto.DecryptedIntermediatePart
                watchAcknowledgementPlaintext =
                IkeV2SessionCrypto
                        .decryptProtectedPacketWithDirectionFlags(
                                watchAcknowledgement.get(0),
                                initiator.initiatorSpi,
                                responderSpi,
                                additionalResult.updatedKeys.skEi,
                                0x28,
                                IkeV2SessionCrypto
                                        .EXCHANGE_INFORMATIONAL,
                                IkeV2SessionCrypto
                                        .PIN_METHOD_WATCH_MESSAGE_ID);
        assertEquals(
                IkeV2Codec.PAYLOAD_NONE,
                watchAcknowledgementPlaintext.firstInnerPayload);
        assertEquals(
                0,
                watchAcknowledgementPlaintext.plaintext.length);

        List<byte[]> missingSaltResponse =
                IkeV2SessionCrypto.encryptProtectedPayload(
                        random,
                        initiator.initiatorSpi,
                        responderSpi,
                        additionalResult.updatedKeys.skEr,
                        true,
                        IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                        IkeV2SessionCrypto.PIN_METHOD_MESSAGE_ID,
                        hex("00 00 00 0C 00 00 C5 46 01 00 01 02"),
                        41,
                        IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
        IkeV2SessionCrypto.PinAuthMethodResponseAccumulator
                missingSaltAccumulator =
                new IkeV2SessionCrypto
                        .PinAuthMethodResponseAccumulator(
                                additionalResult.updatedKeys);
        assertThrows(
                IllegalArgumentException.class,
                () -> missingSaltAccumulator.accept(
                        missingSaltResponse.get(0)));

        byte[] tampered = authResponsePackets.get(0).clone();
        tampered[tampered.length - 1] ^= 1;
        IkeV2SessionCrypto.IkeAuthResponseAccumulator
                tamperedAccumulator =
                new IkeV2SessionCrypto.IkeAuthResponseAccumulator(
                        initiator,
                        response,
                        additionalResult.updatedKeys,
                        additionalRequest.initiatorIntAuth,
                        intAuthR);
        assertThrows(
                IllegalArgumentException.class,
                () -> tamperedAccumulator.accept(tampered));
        encapsulated.destroy();
    }

    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sequence(int first, int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }

    private static byte[] selectedPairingSaInitResponse() {
        return hex(
                // Header: response, message ID zero, 210 bytes.
                "0102030405060708"
                        + "1112131415161718"
                        + "21202220"
                        + "00000000"
                        + "000000d2"
                        // Selected AES-GCM-256/SHA-512/ML-KEM-1024/X448.
                        + "22000030"
                        + "0000002c01010004"
                        + "0300000c01000014800e0100"
                        + "0300000802000007"
                        + "0300000806000025"
                        + "0000000804000020"
                        // X448 responder key.
                        + "28000040"
                        + "00200000"
                        + "000102030405060708090a0b0c0d0e0f"
                        + "101112131415161718191a1b1c1d1e1f"
                        + "202122232425262728292a2b2c2d2e2f"
                        + "3031323334353637"
                        // Responder nonce.
                        + "29000024"
                        + "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
                        + "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf"
                        // Required pairing capabilities.
                        + "2900000800004022"
                        + "2900000a000040282af9"
                        + "2900000800004033"
                        + "0000000800004036");
    }

    private static AppleSpake2PlusProver rfcVectorProver() {
        return AppleSpake2PlusProver.forTest(
                hex("bb8e1bbcf3c48f62c08db243652ae55d"
                        + "3e5586053fca77102994f23ad95491b3"),
                hex("7e945f34d78785b8a3ef44d0df5a1a9"
                        + "7d6b3b460409a345ca7830387a74b1dba"),
                hex("d1232c8e8693d02368976c174e208885"
                        + "1b8365d0d79a9eee709c6a05a2fad539"),
                ("SPAKE2+-P256-SHA256-HKDF-SHA256-"
                        + "HMAC-SHA256 Test Vectors")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "client".getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII),
                "server".getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] rfcProverShare() {
        return hex("04ef3bd051bf78a2234ec0df197f782806"
                + "0fe9856503579bb1733009042c15c0c1d"
                + "e127727f418b5966afadfdd95a6e4591d"
                + "171056b333dab97a79c7193e341727");
    }

    private static byte[] rfcVerifierShare() {
        return hex("04c0f65da0d11927bdf5d560c69e1d7d9"
                + "39a05b0e88291887d679fcadea75810f"
                + "b5cc1ca7494db39e82ff2f50665255d76"
                + "173e09986ab46742c798a9a68437b048");
    }

    private static byte[] rfcProverConfirmation() {
        return hex("926cc713504b9b4d76c9162ded04b549"
                + "3e89109f6d89462cd33adc46fda27527");
    }

    private static byte[] rfcVerifierConfirmation() {
        return hex("9747bcc4f8fe9f63defee53ac9b07876"
                + "d907d55047e6ff2def2e7529089d3e68");
    }

    private static byte[] rfcSharedKey() {
        return hex("0c5f8ccd1413423a54f6c1fb26ff0153"
                + "4a87f893779c6e68666d772bfd91f3e7");
    }

    private static byte[] genericPayload(
            int nextPayload,
            byte[] body) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(4 + body.length);
        output.write(nextPayload);
        output.write(0);
        output.write((body.length + 4) >>> 8);
        output.write(body.length + 4);
        output.writeBytes(body);
        return output.toByteArray();
    }

    private static List<Integer> payloadTypes(
            int firstPayload,
            byte[] plaintext) {
        java.util.ArrayList<Integer> result =
                new java.util.ArrayList<>();
        int payloadType = firstPayload;
        int offset = 0;
        while (payloadType != IkeV2Codec.PAYLOAD_NONE) {
            result.add(payloadType);
            int length =
                    ((plaintext[offset + 2] & 0xFF) << 8)
                            | (plaintext[offset + 3] & 0xFF);
            payloadType = plaintext[offset] & 0xFF;
            offset += length;
        }
        assertEquals(plaintext.length, offset);
        return result;
    }

    private static int notifyTypeAt(
            byte[] plaintext,
            int payloadIndex) {
        int offset = 0;
        for (int index = 0; index < payloadIndex; index++) {
            int length =
                    ((plaintext[offset + 2] & 0xFF) << 8)
                            | (plaintext[offset + 3] & 0xFF);
            offset += length;
        }
        return ((plaintext[offset + 6] & 0xFF) << 8)
                | (plaintext[offset + 7] & 0xFF);
    }

    private static byte[] lastPayloadBody(byte[] plaintext) {
        int offset = 0;
        int lastOffset = 0;
        while (offset < plaintext.length) {
            lastOffset = offset;
            int length =
                    ((plaintext[offset + 2] & 0xFF) << 8)
                            | (plaintext[offset + 3] & 0xFF);
            offset += length;
        }
        return java.util.Arrays.copyOfRange(
                plaintext,
                lastOffset + 4,
                plaintext.length);
    }

    private static byte[] payloadBodyAt(
            byte[] plaintext,
            int payloadIndex) {
        int offset = 0;
        for (int index = 0; index < payloadIndex; index++) {
            int length =
                    ((plaintext[offset + 2] & 0xFF) << 8)
                            | (plaintext[offset + 3] & 0xFF);
            offset += length;
        }
        int length =
                ((plaintext[offset + 2] & 0xFF) << 8)
                        | (plaintext[offset + 3] & 0xFF);
        return java.util.Arrays.copyOfRange(
                plaintext,
                offset + 4,
                offset + length);
    }

    private static byte[] concatenate(byte[]... arrays) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (byte[] array : arrays) {
            output.writeBytes(array);
        }
        return output.toByteArray();
    }

    private static byte[] hex(String value) {
        String normalized = value.replaceAll("\\s", "");
        if ((normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd hex length");
        }
        byte[] output = new byte[normalized.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    normalized.substring(index * 2, index * 2 + 2),
                    16);
        }
        return output;
    }
}
