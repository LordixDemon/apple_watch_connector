package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class BluetoothSmpCodecTest {
    @Test
    public void buildsStrictSecureConnectionsOobRequest() {
        BluetoothSmpCodec.PairingFeatures request =
                BluetoothSmpCodec.createPairingRequest();

        assertArrayEquals(
                new byte[]{
                        0x01, 0x01, 0x01, 0x2d,
                        0x10, 0x07, 0x07
                },
                request.encode());
    }

    @Test
    public void parsesValidWatchPairingResponse() {
        BluetoothSmpCodec.PairingFeatures response =
                BluetoothSmpCodec.parsePairingResponse(
                        new byte[]{
                                0x02, 0x03, 0x01, 0x2d,
                                0x10, 0x07, 0x07
                        });

        assertEquals(3, response.ioCapability);
        assertEquals(1, response.oobDataFlag);
        assertEquals(0x2d,
                response.authenticationRequirements);
        assertEquals(16,
                response.maximumEncryptionKeySize);
    }

    @Test
    public void rejectsMalformedPairingResponseAndBuildsFailure() {
        assertThrows(
                IllegalArgumentException.class,
                () -> BluetoothSmpCodec
                        .parsePairingResponse(
                                new byte[]{
                                        0x02, 0x03, 0x02, 0x2d,
                                        0x10, 0x07, 0x07
                                }));
        byte[] failure =
                BluetoothSmpCodec.pairingFailed(
                        BluetoothSmpCodec
                                .FAILURE_UNSPECIFIED_REASON);
        assertArrayEquals(
                new byte[]{0x05, 0x08},
                failure);
        assertEquals(
                8,
                BluetoothSmpCodec
                        .parsePairingFailure(failure));
        assertEquals(
                "Unspecified Reason",
                BluetoothSmpCodec.pairingFailureReasonName(0x08));
        assertEquals(
                "Repeated Attempts",
                BluetoothSmpCodec.pairingFailureReasonName(0x09));
        assertEquals(
                "Key Rejected",
                BluetoothSmpCodec.pairingFailureReasonName(0x0f));
        assertEquals(
                "Unknown",
                BluetoothSmpCodec.pairingFailureReasonName(0xff));
    }

    @Test
    public void roundTripsSecretBearingSmpPdusWithoutFormatting() {
        byte[] publicKey = sequence(
                0x20,
                BleSecureConnectionsCrypto
                        .PUBLIC_KEY_LENGTH);
        assertArrayEquals(
                publicKey,
                BluetoothSmpCodec
                        .parsePairingPublicKey(
                                BluetoothSmpCodec
                                        .pairingPublicKey(
                                                publicKey)));

        byte[] nonce = sequence(0x40, 16);
        assertArrayEquals(
                nonce,
                BluetoothSmpCodec
                        .parsePairingRandom(
                                BluetoothSmpCodec
                                        .pairingRandom(nonce)));
        assertArrayEquals(
                nonce,
                BluetoothSmpCodec
                        .parsePairingDhKeyCheck(
                                BluetoothSmpCodec
                                        .pairingDhKeyCheck(
                                                nonce)));
        assertArrayEquals(
                nonce,
                BluetoothSmpCodec
                        .parseIdentityInformation(
                                BluetoothSmpCodec
                                        .identityInformation(
                                                nonce)));

        byte[] address =
                new byte[]{
                        1, 2, 3, 4, 5, 6
                };
        BluetoothSmpCodec.IdentityAddress parsed =
                BluetoothSmpCodec
                        .parseIdentityAddressInformation(
                                BluetoothSmpCodec
                                        .identityAddressInformation(
                                                1,
                                                address));
        assertEquals(1, parsed.addressType);
        assertArrayEquals(
                address,
                parsed.address);
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] output = new byte[length];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) (first + index);
        }
        return output;
    }
}
