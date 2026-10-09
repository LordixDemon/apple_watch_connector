package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.Locale;

/**
 * Bit-exact Watch Setup identifier and extended-metadata codec.
 *
 * <p>The current Ultra 2 advertisement carries a four-byte identifier and at
 * least seven metadata bytes. Metadata may grow in a later watchOS release,
 * so decoded objects retain the complete input instead of truncating it.</p>
 */
final class WatchSetupMetadataCodec {
    static final int IDENTIFIER_LENGTH = 4;
    static final int CURRENT_METADATA_LENGTH = 7;

    static final int NETWORK_RELAY_PAIRING_STRATEGY = 4;
    static final int ULTRA_2_PRODUCT_MAJOR = 7;
    static final int ULTRA_2_PRODUCT_MINOR = 5;

    private static final char[] INTEGER_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890"
                    .toCharArray();

    private WatchSetupMetadataCodec() {
    }

    static Identifier decodeIdentifier(
            byte[] packed) {
        if (packed == null
                || packed.length != IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(
                    "Watch Setup identifier must contain four bytes");
        }
        int first = unsigned(
                packed[0]);
        int second = unsigned(
                packed[1]);
        int third = unsigned(
                packed[2]);
        int fourth = unsigned(
                packed[3]);
        return new Identifier(
                first >>> 5,
                ((first & 0x1f) << 12)
                        | (second << 4)
                        | (third >>> 4),
                ((third & 0x0f) << 2)
                        | (fourth >>> 6),
                fourth & 0x3f,
                packed);
    }

    static byte[] encodeIdentifier(
            int pairingStrategy,
            int advertisingIdentifier,
            int deviceSize,
            int enclosureMaterial) {
        requireRange(
                pairingStrategy,
                0x07,
                "pairing strategy");
        requireRange(
                advertisingIdentifier,
                0x1ffff,
                "advertising identifier");
        requireRange(
                deviceSize,
                0x3f,
                "device size");
        requireRange(
                enclosureMaterial,
                0x3f,
                "enclosure material");
        return new byte[]{
                (byte) ((pairingStrategy << 5)
                        | ((advertisingIdentifier >>> 12)
                        & 0x1f)),
                (byte) ((advertisingIdentifier >>> 4)
                        & 0xff),
                (byte) (((advertisingIdentifier & 0x0f)
                        << 4)
                        | ((deviceSize >>> 2) & 0x0f)),
                (byte) (((deviceSize & 0x03) << 6)
                        | (enclosureMaterial & 0x3f))
        };
    }

    static ExtendedMetadata decodeExtendedMetadata(
            byte[] packed) {
        if (packed == null
                || packed.length < CURRENT_METADATA_LENGTH) {
            throw new IllegalArgumentException(
                    "Watch Setup metadata must contain at least seven bytes");
        }
        int first = unsigned(
                packed[0]);
        int second = unsigned(
                packed[1]);
        int third = unsigned(
                packed[2]);
        long encodedSystemVersion =
                ((long) (third & 0x07) << 29)
                        | ((long) unsigned(
                                packed[3]) << 21)
                        | ((long) unsigned(
                                packed[4]) << 13)
                        | ((long) unsigned(
                                packed[5]) << 5)
                        | ((long) unsigned(
                                packed[6]) >>> 3);
        return new ExtendedMetadata(
                first >>> 2,
                ((first & 0x03) << 5)
                        | (second >>> 3),
                ((second & 0x07) << 4)
                        | (third >>> 4),
                ((third >>> 3) & 1) != 0,
                encodedSystemVersion,
                (unsigned(
                        packed[6]) & 0x07) == 0,
                packed);
    }

    static byte[] encodeExtendedMetadata(
            int pairingVersion,
            int productVersionMajor,
            int productVersionMinor,
            boolean postFailsafeObliteration,
            long encodedSystemVersion,
            byte[] trailingExtension) {
        requireRange(
                pairingVersion,
                0x3f,
                "pairing version");
        requireRange(
                productVersionMajor,
                0x7f,
                "product major version");
        requireRange(
                productVersionMinor,
                0x7f,
                "product minor version");
        if (encodedSystemVersion < 0
                || encodedSystemVersion > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                    "encoded system version is outside uint32");
        }
        byte[] trailing =
                trailingExtension == null
                        ? new byte[0]
                        : trailingExtension;
        byte[] packed =
                new byte[CURRENT_METADATA_LENGTH
                        + trailing.length];
        packed[0] =
                (byte) ((pairingVersion << 2)
                        | ((productVersionMajor >>> 5)
                        & 0x03));
        packed[1] =
                (byte) (((productVersionMajor & 0x1f)
                        << 3)
                        | ((productVersionMinor >>> 4)
                        & 0x07));
        packed[2] =
                (byte) (((productVersionMinor & 0x0f)
                        << 4)
                        | (postFailsafeObliteration
                        ? 0x08
                        : 0)
                        | ((encodedSystemVersion >>> 29)
                        & 0x07));
        packed[3] =
                (byte) ((encodedSystemVersion >>> 21)
                        & 0xff);
        packed[4] =
                (byte) ((encodedSystemVersion >>> 13)
                        & 0xff);
        packed[5] =
                (byte) ((encodedSystemVersion >>> 5)
                        & 0xff);
        packed[6] =
                (byte) ((encodedSystemVersion << 3)
                        & 0xf8);
        System.arraycopy(
                trailing,
                0,
                packed,
                CURRENT_METADATA_LENGTH,
                trailing.length);
        return packed;
    }

    static boolean isUltra2NetworkRelayCandidate(
            Identifier identifier,
            ExtendedMetadata metadata,
            int minimumPairingVersion,
            int maximumPairingVersion) {
        return identifier != null
                && metadata != null
                && identifier.pairingStrategy
                == NETWORK_RELAY_PAIRING_STRATEGY
                && metadata.productVersionMajor
                == ULTRA_2_PRODUCT_MAJOR
                && metadata.productVersionMinor
                == ULTRA_2_PRODUCT_MINOR
                && metadata.pairingVersion
                >= minimumPairingVersion
                && metadata.pairingVersion
                <= maximumPairingVersion
                && metadata.hasCanonicalPadding;
    }

    private static void requireRange(
            int value,
            int maximum,
            String label) {
        if (value < 0
                || value > maximum) {
            throw new IllegalArgumentException(
                    "Watch Setup "
                            + label
                            + " is outside its wire width");
        }
    }

    private static int unsigned(
            byte value) {
        return value & 0xff;
    }

    static final class Identifier {
        final int pairingStrategy;
        final int advertisingIdentifier;
        final int deviceSize;
        final int enclosureMaterial;
        private final byte[] packed;

        private Identifier(
                int pairingStrategy,
                int advertisingIdentifier,
                int deviceSize,
                int enclosureMaterial,
                byte[] packed) {
            this.pairingStrategy =
                    pairingStrategy;
            this.advertisingIdentifier =
                    advertisingIdentifier;
            this.deviceSize =
                    deviceSize;
            this.enclosureMaterial =
                    enclosureMaterial;
            this.packed =
                    packed.clone();
        }

        byte[] packed() {
            return packed.clone();
        }

        String advertisingCode() {
            if (advertisingIdentifier > 99_999) {
                throw new IllegalStateException(
                        "Advertising identifier does not fit five digits");
            }
            return String.format(
                    Locale.US,
                    "%05d",
                    advertisingIdentifier);
        }

        String humanReadablePayload() {
            if (pairingStrategy >= INTEGER_ALPHABET.length
                    || enclosureMaterial
                    >= INTEGER_ALPHABET.length
                    || deviceSize
                    >= INTEGER_ALPHABET.length) {
                throw new IllegalStateException(
                        "Watch Setup enum cannot use the current alphabet");
            }
            return advertisingCode()
                    + INTEGER_ALPHABET[pairingStrategy]
                    + INTEGER_ALPHABET[enclosureMaterial]
                    + INTEGER_ALPHABET[deviceSize];
        }
    }

    static final class ExtendedMetadata {
        final int pairingVersion;
        final int productVersionMajor;
        final int productVersionMinor;
        final boolean postFailsafeObliteration;
        final long encodedSystemVersion;
        final boolean hasCanonicalPadding;
        private final byte[] packed;

        private ExtendedMetadata(
                int pairingVersion,
                int productVersionMajor,
                int productVersionMinor,
                boolean postFailsafeObliteration,
                long encodedSystemVersion,
                boolean hasCanonicalPadding,
                byte[] packed) {
            this.pairingVersion =
                    pairingVersion;
            this.productVersionMajor =
                    productVersionMajor;
            this.productVersionMinor =
                    productVersionMinor;
            this.postFailsafeObliteration =
                    postFailsafeObliteration;
            this.encodedSystemVersion =
                    encodedSystemVersion;
            this.hasCanonicalPadding =
                    hasCanonicalPadding;
            this.packed =
                    packed.clone();
        }

        String productType() {
            return "Watch"
                    + productVersionMajor
                    + ","
                    + productVersionMinor;
        }

        int systemVersionMajor() {
            return (int) ((encodedSystemVersion >>> 16)
                    & 0xffff);
        }

        int systemVersionMinor() {
            return (int) ((encodedSystemVersion >>> 8)
                    & 0xff);
        }

        int systemVersionPatch() {
            return (int) (encodedSystemVersion
                    & 0xff);
        }

        String systemVersionString() {
            return systemVersionMajor()
                    + "."
                    + systemVersionMinor()
                    + "."
                    + systemVersionPatch();
        }

        byte[] packed() {
            return packed.clone();
        }

        byte[] trailingExtension() {
            return Arrays.copyOfRange(
                    packed,
                    CURRENT_METADATA_LENGTH,
                    packed.length);
        }
    }
}
