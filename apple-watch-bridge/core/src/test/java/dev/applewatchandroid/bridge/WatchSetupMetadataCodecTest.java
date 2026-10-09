package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class WatchSetupMetadataCodecTest {
    @Test
    public void decodesPhysicalUltra2FixtureBitExactly() {
        WatchSetupMetadataCodec.Identifier identifier =
                WatchSetupMetadataCodec.decodeIdentifier(
                        hex(
                                "86 93 f4 ce"));
        WatchSetupMetadataCodec.ExtendedMetadata metadata =
                WatchSetupMetadataCodec.decodeExtendedMetadata(
                        hex(
                                "64 38 50 00 d0 10 00"));

        assertEquals(
                4,
                identifier.pairingStrategy);
        assertEquals(
                26943,
                identifier.advertisingIdentifier);
        assertEquals(
                19,
                identifier.deviceSize);
        assertEquals(
                14,
                identifier.enclosureMaterial);
        assertEquals(
                "26943",
                identifier.advertisingCode());
        assertEquals(
                "26943EOT",
                identifier.humanReadablePayload());

        assertEquals(
                25,
                metadata.pairingVersion);
        assertEquals(
                7,
                metadata.productVersionMajor);
        assertEquals(
                5,
                metadata.productVersionMinor);
        assertFalse(
                metadata.postFailsafeObliteration);
        assertEquals(
                0x001a_0200L,
                metadata.encodedSystemVersion);
        assertEquals(
                26,
                metadata.systemVersionMajor());
        assertEquals(
                2,
                metadata.systemVersionMinor());
        assertEquals(
                0,
                metadata.systemVersionPatch());
        assertEquals(
                "26.2.0",
                metadata.systemVersionString());
        assertTrue(
                metadata.hasCanonicalPadding);
        assertEquals(
                "Watch7,5",
                metadata.productType());
        assertTrue(
                WatchSetupMetadataCodec
                        .isUltra2NetworkRelayCandidate(
                                identifier,
                                metadata,
                                25,
                                26));
    }

    @Test
    public void independentPackersReproducePhysicalFixture() {
        assertArrayEquals(
                hex(
                        "86 93 f4 ce"),
                WatchSetupMetadataCodec.encodeIdentifier(
                        4,
                        26943,
                        19,
                        14));
        assertArrayEquals(
                hex(
                        "64 38 50 00 d0 10 00"),
                WatchSetupMetadataCodec
                        .encodeExtendedMetadata(
                                25,
                                7,
                                5,
                                false,
                                0x001a_0200L,
                                null));
    }

    @Test
    public void metadataParserPreservesFutureTrailingExtension() {
        byte[] packed =
                WatchSetupMetadataCodec
                        .encodeExtendedMetadata(
                                26,
                                7,
                                5,
                                true,
                                0x001a_0600L,
                                hex(
                                        "aa bb cc"));
        WatchSetupMetadataCodec.ExtendedMetadata decoded =
                WatchSetupMetadataCodec
                        .decodeExtendedMetadata(
                                packed);

        assertEquals(
                26,
                decoded.pairingVersion);
        assertTrue(
                decoded.postFailsafeObliteration);
        assertEquals(
                0x001a_0600L,
                decoded.encodedSystemVersion);
        assertEquals(
                "26.6.0",
                decoded.systemVersionString());
        assertArrayEquals(
                hex(
                        "aa bb cc"),
                decoded.trailingExtension());
        assertArrayEquals(
                packed,
                decoded.packed());
    }

    @Test
    public void targetSelectionDoesNotDependOnOneIdentifier() {
        byte[] changedIdentifier =
                WatchSetupMetadataCodec
                        .encodeIdentifier(
                                4,
                                51080,
                                19,
                                14);
        WatchSetupMetadataCodec.Identifier identifier =
                WatchSetupMetadataCodec
                        .decodeIdentifier(
                                changedIdentifier);
        WatchSetupMetadataCodec.ExtendedMetadata metadata =
                WatchSetupMetadataCodec
                        .decodeExtendedMetadata(
                                hex(
                                        "64 38 50 00 d0 10 00"));

        assertEquals(
                "51080",
                identifier.advertisingCode());
        assertTrue(
                WatchSetupMetadataCodec
                        .isUltra2NetworkRelayCandidate(
                                identifier,
                                metadata,
                                25,
                                26));
    }

    @Test
    public void rejectsTruncatedAndOutOfWidthInputs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WatchSetupMetadataCodec
                        .decodeIdentifier(
                                new byte[3]));
        assertThrows(
                IllegalArgumentException.class,
                () -> WatchSetupMetadataCodec
                        .decodeExtendedMetadata(
                                new byte[6]));
        assertThrows(
                IllegalArgumentException.class,
                () -> WatchSetupMetadataCodec
                        .encodeIdentifier(
                                8,
                                1,
                                1,
                                1));
        assertThrows(
                IllegalArgumentException.class,
                () -> WatchSetupMetadataCodec
                        .encodeExtendedMetadata(
                                64,
                                7,
                                5,
                                false,
                                0,
                                null));
    }

    private static byte[] hex(
            String value) {
        String compact =
                value.replace(
                        " ",
                        "");
        byte[] result =
                new byte[compact.length() / 2];
        for (int index = 0;
             index < result.length;
             index++) {
            result[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return result;
    }
}
