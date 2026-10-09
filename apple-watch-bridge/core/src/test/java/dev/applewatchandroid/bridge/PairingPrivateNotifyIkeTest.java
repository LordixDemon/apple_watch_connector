package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class PairingPrivateNotifyIkeTest {
    @Test
    public void completesBidirectionalProtectedNotifyExchange() {
        SecureRandom random = new SecureRandom();
        IkeV2SessionCrypto.IkeSaKeys keys =
                testKeys();
        BleSecureConnectionsCrypto.LocalOobMaterial oob =
                BleSecureConnectionsCrypto.generateLocalOob(
                        random);
        AppleNetworkRelayPairingMaterial networkKeys =
                AppleNetworkRelayPairingMaterial.generate(
                        random);
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(
                        random);
        ApplePairingNotifyPayloads.LocalBatch batch =
                ApplePairingNotifyPayloads.createLocalBatch(
                        oob,
                        networkKeys,
                        addresses,
                        "Android",
                        "test-build",
                        null);
        List<ApplePairingNotifyPayloads.PrivateNotify>
                localNotifies = batch.notifies();
        List<ApplePairingNotifyPayloads.PrivateNotify>
                watchNotifies = new ArrayList<>(
                localNotifies.subList(0, 9));
        try {
            IkeV2SessionCrypto.PairingPrivateNotifyRequest
                    request =
                    IkeV2SessionCrypto
                            .createPairingPrivateNotifyRequest(
                                    random,
                                    keys,
                                    localNotifies);
            assertEquals(1, request.ikePackets.size());
            assertEquals(
                    types(localNotifies),
                    request.notifyTypes);
            assertEquals(
                    lengths(localNotifies),
                    request.dataLengths);
            IkeV2SessionCrypto.DecryptedIntermediatePart
                    requestPlaintext =
                    IkeV2SessionCrypto.decryptProtectedPacket(
                            request.ikePackets.get(0),
                            keys.initiatorSpi,
                            keys.responderSpi,
                            keys.skEi,
                            false,
                            IkeV2SessionCrypto
                                    .EXCHANGE_INFORMATIONAL,
                            IkeV2SessionCrypto
                                    .PAIRING_NOTIFIES_MESSAGE_ID);
            assertFalse(requestPlaintext.fragmented);
            assertEquals(41, requestPlaintext.firstInnerPayload);
            assertEquals(
                    types(localNotifies),
                    plaintextNotifyTypes(
                            requestPlaintext.plaintext));

            // Optical AUTH ends at MID=2, so its private-notify exchange is MID=3.
            IkeV2SessionCrypto.PairingPrivateNotifyRequest opticalRequest =
                    IkeV2SessionCrypto.createPairingPrivateNotifyRequest(random, keys, localNotifies, 3);
            assertEquals(41, IkeV2SessionCrypto.decryptProtectedPacket(
                    opticalRequest.ikePackets.get(0), keys.initiatorSpi, keys.responderSpi,
                    keys.skEi, false, IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL, 3).firstInnerPayload);
            IkeV2SessionCrypto.PairingPrivateNotifyResponseAccumulator opticalAccumulator =
                    new IkeV2SessionCrypto.PairingPrivateNotifyResponseAccumulator(keys, 3);
            // PIN ACK at MID=5 must not advance the optical exchange.
            byte[] pinAck = IkeV2SessionCrypto.createPairingPrivateNotifyEmptyResponseForTest(random, keys).get(0);
            assertThrows(IllegalArgumentException.class, () -> opticalAccumulator.accept(pinAck));
            byte[] opticalAck = IkeV2SessionCrypto.createPinAuthMethodEmptyResponseForTest(random, keys).get(0);
            assertNull(opticalAccumulator.accept(opticalAck));
            assertTrue(opticalAccumulator.requestAcknowledged());
            IkeV2SessionCrypto.PairingPrivateNotifyResponse opticalResponse = opticalAccumulator.accept(
                    IkeV2SessionCrypto.createPairingPrivateNotifyWatchRequestForTest(random, keys, watchNotifies).get(0));
            assertEquals(types(watchNotifies), opticalResponse.notifyTypes());
            opticalResponse.destroy();

            List<byte[]> acknowledgement =
                    IkeV2SessionCrypto
                            .createPairingPrivateNotifyEmptyResponseForTest(
                                    random,
                                    keys);
            List<byte[]> watchRequest =
                    IkeV2SessionCrypto
                            .createPairingPrivateNotifyWatchRequestForTest(
                                    random,
                                    keys,
                                    watchNotifies);
            IkeV2SessionCrypto
                    .PairingPrivateNotifyResponseAccumulator
                    accumulator =
                    new IkeV2SessionCrypto
                            .PairingPrivateNotifyResponseAccumulator(
                                    keys);
            assertNull(
                    accumulator.accept(
                            acknowledgement.get(0)));
            assertTrue(accumulator.requestAcknowledged());
            IkeV2SessionCrypto.PairingPrivateNotifyResponse
                    response =
                    accumulator.accept(
                            watchRequest.get(0));
            assertEquals(
                    IkeV2SessionCrypto
                            .PAIRING_NOTIFIES_WATCH_MESSAGE_ID,
                    response.peerRequestMessageId);
            assertEquals(
                    types(watchNotifies),
                    response.notifyTypes());
            assertEquals(
                    lengths(watchNotifies),
                    response.dataLengths());

            List<ApplePairingNotifyPayloads.PrivateNotify>
                    parsedNotifies = response.notifies();
            try {
                ApplePairingNotifyPayloads.PeerBatch peer =
                        ApplePairingNotifyPayloads
                                .parsePeerBatch(
                                        parsedNotifies);
                assertEquals(24, peer.protocolVersion);
                assertEquals("Android", peer.deviceName);
                peer.destroy();
            } finally {
                destroy(parsedNotifies);
            }

            List<byte[]> watchAck =
                    IkeV2SessionCrypto
                            .createWatchInformationalAcknowledgement(
                                    random,
                                    keys,
                                    response.peerRequestMessageId);
            assertEquals(0x28, watchAck.get(0)[19] & 0xff);
            IkeV2SessionCrypto.DecryptedIntermediatePart
                    watchAckPlaintext =
                    IkeV2SessionCrypto
                            .decryptProtectedPacketWithDirectionFlags(
                                    watchAck.get(0),
                                    keys.initiatorSpi,
                                    keys.responderSpi,
                                    keys.skEi,
                                    0x28,
                                    IkeV2SessionCrypto
                                            .EXCHANGE_INFORMATIONAL,
                                    IkeV2SessionCrypto
                                            .PAIRING_NOTIFIES_WATCH_MESSAGE_ID);
            assertEquals(0, watchAckPlaintext.plaintext.length);
            response.destroy();

            byte[] tampered = watchRequest.get(0).clone();
            tampered[tampered.length - 1] ^= 1;
            IkeV2SessionCrypto
                    .PairingPrivateNotifyResponseAccumulator
                    tamperedAccumulator =
                    new IkeV2SessionCrypto
                            .PairingPrivateNotifyResponseAccumulator(
                                    keys);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> tamperedAccumulator.accept(tampered));
        } finally {
            destroy(localNotifies);
            destroy(watchNotifies);
            batch.destroy();
            addresses.destroy();
            networkKeys.destroy();
            oob.destroy();
        }
    }

    private static IkeV2SessionCrypto.IkeSaKeys testKeys() {
        return new IkeV2SessionCrypto.IkeSaKeys(
                sequence(0x00, 64),
                sequence(0x20, 36),
                sequence(0x50, 36),
                sequence(0x40, 64),
                sequence(0x80, 64),
                sequence(0x10, 8),
                sequence(0x30, 8),
                sequence(0xa0, 32),
                sequence(0xc0, 32));
    }

    private static List<Integer> plaintextNotifyTypes(
            byte[] plaintext) {
        List<Integer> types = new ArrayList<>();
        int offset = 0;
        int payloadType = 41;
        while (payloadType != 0) {
            int next = plaintext[offset] & 0xff;
            int length =
                    ((plaintext[offset + 2] & 0xff) << 8)
                            | (plaintext[offset + 3] & 0xff);
            types.add(
                    ((plaintext[offset + 6] & 0xff) << 8)
                            | (plaintext[offset + 7] & 0xff));
            offset += length;
            payloadType = next;
        }
        assertEquals(plaintext.length, offset);
        return List.copyOf(types);
    }

    private static List<Integer> types(
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        List<Integer> output =
                new ArrayList<>(notifies.size());
        for (ApplePairingNotifyPayloads.PrivateNotify notify
                : notifies) {
            output.add(notify.type());
        }
        return List.copyOf(output);
    }

    private static List<Integer> lengths(
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        List<Integer> output =
                new ArrayList<>(notifies.size());
        for (ApplePairingNotifyPayloads.PrivateNotify notify
                : notifies) {
            output.add(notify.data().length);
        }
        return List.copyOf(output);
    }

    private static void destroy(
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        for (ApplePairingNotifyPayloads.PrivateNotify notify
                : notifies) {
            notify.destroy();
        }
    }

    private static byte[] sequence(int first, int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }
}
