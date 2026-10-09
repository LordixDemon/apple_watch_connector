package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;
import org.bouncycastle.asn1.x9.X9ECParameters;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Bluetooth LE Secure Connections P-256 primitives using SMP wire byte order.
 *
 * <p>All public-key coordinates, randomizers, confirmations, and DHKey values
 * accepted or returned here are little-endian, matching the SMP packets and
 * Android's native stack. Secret values are never converted to text.</p>
 */
final class BleSecureConnectionsCrypto {
    static final int AES_VALUE_LENGTH = 16;
    static final int P256_VALUE_LENGTH = 32;
    static final int PUBLIC_KEY_LENGTH = 64;
    static final int DEVICE_ADDRESS_LENGTH = 7;
    static final int IO_CAPABILITY_LENGTH = 3;

    private static final byte[] F5_SALT_BIG_ENDIAN = new byte[]{
            0x6c, (byte) 0x88, (byte) 0x83, (byte) 0x91,
            (byte) 0xaa, (byte) 0xf5, (byte) 0xa5, 0x38,
            0x60, 0x37, 0x0b, (byte) 0xdb,
            0x5a, 0x60, (byte) 0x83, (byte) 0xbe
    };
    private static final byte[] F5_KEY_ID_BIG_ENDIAN =
            new byte[]{0x62, 0x74, 0x6c, 0x65}; // "btle"

    private static final X9ECParameters P256 =
            CustomNamedCurves.getByName("secp256r1");
    private static final ECDomainParameters DOMAIN =
            new ECDomainParameters(
                    P256.getCurve(),
                    P256.getG(),
                    P256.getN(),
                    P256.getH(),
                    P256.getSeed());

    private BleSecureConnectionsCrypto() {
    }

    /**
     * Bluetooth Core f4(U, V, X, Z), with every multi-octet input and the
     * result represented in SMP little-endian order.
     */
    static byte[] f4(
            byte[] uLittleEndian,
            byte[] vLittleEndian,
            byte[] xLittleEndian,
            int z) {
        requireLength(
                "f4 U",
                uLittleEndian,
                P256_VALUE_LENGTH);
        requireLength(
                "f4 V",
                vLittleEndian,
                P256_VALUE_LENGTH);
        requireLength(
                "f4 X",
                xLittleEndian,
                AppleLeScOobData.VALUE_LENGTH);
        if ((z & ~0xff) != 0) {
            throw new IllegalArgumentException(
                    "f4 Z must fit in one byte");
        }

        byte[] keyBigEndian = reverseCopy(xLittleEndian);
        byte[] messageBigEndian =
                new byte[P256_VALUE_LENGTH * 2 + 1];
        byte[] uBigEndian = reverseCopy(uLittleEndian);
        byte[] vBigEndian = reverseCopy(vLittleEndian);
        byte[] macBigEndian =
                new byte[AppleLeScOobData.VALUE_LENGTH];
        try {
            System.arraycopy(
                    uBigEndian,
                    0,
                    messageBigEndian,
                    0,
                    P256_VALUE_LENGTH);
            System.arraycopy(
                    vBigEndian,
                    0,
                    messageBigEndian,
                    P256_VALUE_LENGTH,
                    P256_VALUE_LENGTH);
            messageBigEndian[messageBigEndian.length - 1] =
                    (byte) z;

            CMac cmac = new CMac(AESEngine.newInstance());
            cmac.init(new KeyParameter(keyBigEndian));
            cmac.update(
                    messageBigEndian,
                    0,
                    messageBigEndian.length);
            cmac.doFinal(macBigEndian, 0);
            return reverseCopy(macBigEndian);
        } finally {
            wipe(keyBigEndian);
            wipe(messageBigEndian);
            wipe(uBigEndian);
            wipe(vBigEndian);
            wipe(macBigEndian);
        }
    }

    /**
     * Bluetooth Core f5(W, N1, N2, A1, A2). Inputs and both outputs use SMP
     * little-endian order.
     */
    static F5Result f5(
            byte[] dhKeyLittleEndian,
            byte[] n1LittleEndian,
            byte[] n2LittleEndian,
            byte[] a1LittleEndian,
            byte[] a2LittleEndian) {
        requireLength(
                "f5 DHKey",
                dhKeyLittleEndian,
                P256_VALUE_LENGTH);
        requireLength(
                "f5 N1",
                n1LittleEndian,
                AES_VALUE_LENGTH);
        requireLength(
                "f5 N2",
                n2LittleEndian,
                AES_VALUE_LENGTH);
        requireLength(
                "f5 A1",
                a1LittleEndian,
                DEVICE_ADDRESS_LENGTH);
        requireLength(
                "f5 A2",
                a2LittleEndian,
                DEVICE_ADDRESS_LENGTH);

        byte[] dhKeyBigEndian =
                reverseCopy(dhKeyLittleEndian);
        byte[] n1BigEndian =
                reverseCopy(n1LittleEndian);
        byte[] n2BigEndian =
                reverseCopy(n2LittleEndian);
        byte[] a1BigEndian =
                reverseCopy(a1LittleEndian);
        byte[] a2BigEndian =
                reverseCopy(a2LittleEndian);
        byte[] tBigEndian =
                new byte[AES_VALUE_LENGTH];
        byte[] messageBigEndian =
                new byte[1 + 4 + 16 + 16 + 7 + 7 + 2];
        byte[] macKeyBigEndian =
                new byte[AES_VALUE_LENGTH];
        byte[] ltkBigEndian =
                new byte[AES_VALUE_LENGTH];
        byte[] macKeyLittleEndian = null;
        byte[] ltkLittleEndian = null;
        try {
            calculateCmacBigEndian(
                    F5_SALT_BIG_ENDIAN,
                    dhKeyBigEndian,
                    tBigEndian);
            buildF5Message(
                    messageBigEndian,
                    0,
                    n1BigEndian,
                    n2BigEndian,
                    a1BigEndian,
                    a2BigEndian);
            calculateCmacBigEndian(
                    tBigEndian,
                    messageBigEndian,
                    macKeyBigEndian);
            buildF5Message(
                    messageBigEndian,
                    1,
                    n1BigEndian,
                    n2BigEndian,
                    a1BigEndian,
                    a2BigEndian);
            calculateCmacBigEndian(
                    tBigEndian,
                    messageBigEndian,
                    ltkBigEndian);
            macKeyLittleEndian =
                    reverseCopy(macKeyBigEndian);
            ltkLittleEndian =
                    reverseCopy(ltkBigEndian);
            return new F5Result(
                    macKeyLittleEndian,
                    ltkLittleEndian);
        } finally {
            wipe(dhKeyBigEndian);
            wipe(n1BigEndian);
            wipe(n2BigEndian);
            wipe(a1BigEndian);
            wipe(a2BigEndian);
            wipe(tBigEndian);
            wipe(messageBigEndian);
            wipe(macKeyBigEndian);
            wipe(ltkBigEndian);
            wipe(macKeyLittleEndian);
            wipe(ltkLittleEndian);
        }
    }

    /**
     * Bluetooth Core f6(W, N1, N2, R, IOcap, A1, A2), in SMP
     * little-endian order.
     */
    static byte[] f6(
            byte[] macKeyLittleEndian,
            byte[] n1LittleEndian,
            byte[] n2LittleEndian,
            byte[] rLittleEndian,
            byte[] ioCapabilityLittleEndian,
            byte[] a1LittleEndian,
            byte[] a2LittleEndian) {
        requireLength(
                "f6 MacKey",
                macKeyLittleEndian,
                AES_VALUE_LENGTH);
        requireLength(
                "f6 N1",
                n1LittleEndian,
                AES_VALUE_LENGTH);
        requireLength(
                "f6 N2",
                n2LittleEndian,
                AES_VALUE_LENGTH);
        requireLength(
                "f6 R",
                rLittleEndian,
                AES_VALUE_LENGTH);
        requireLength(
                "f6 IOcap",
                ioCapabilityLittleEndian,
                IO_CAPABILITY_LENGTH);
        requireLength(
                "f6 A1",
                a1LittleEndian,
                DEVICE_ADDRESS_LENGTH);
        requireLength(
                "f6 A2",
                a2LittleEndian,
                DEVICE_ADDRESS_LENGTH);

        byte[] keyBigEndian =
                reverseCopy(macKeyLittleEndian);
        byte[] n1BigEndian =
                reverseCopy(n1LittleEndian);
        byte[] n2BigEndian =
                reverseCopy(n2LittleEndian);
        byte[] rBigEndian =
                reverseCopy(rLittleEndian);
        byte[] ioCapabilityBigEndian =
                reverseCopy(ioCapabilityLittleEndian);
        byte[] a1BigEndian =
                reverseCopy(a1LittleEndian);
        byte[] a2BigEndian =
                reverseCopy(a2LittleEndian);
        byte[] messageBigEndian =
                new byte[16 + 16 + 16 + 3 + 7 + 7];
        byte[] macBigEndian =
                new byte[AES_VALUE_LENGTH];
        try {
            int offset = 0;
            offset = copy(
                    n1BigEndian,
                    messageBigEndian,
                    offset);
            offset = copy(
                    n2BigEndian,
                    messageBigEndian,
                    offset);
            offset = copy(
                    rBigEndian,
                    messageBigEndian,
                    offset);
            offset = copy(
                    ioCapabilityBigEndian,
                    messageBigEndian,
                    offset);
            offset = copy(
                    a1BigEndian,
                    messageBigEndian,
                    offset);
            copy(
                    a2BigEndian,
                    messageBigEndian,
                    offset);
            calculateCmacBigEndian(
                    keyBigEndian,
                    messageBigEndian,
                    macBigEndian);
            return reverseCopy(macBigEndian);
        } finally {
            wipe(keyBigEndian);
            wipe(n1BigEndian);
            wipe(n2BigEndian);
            wipe(rBigEndian);
            wipe(ioCapabilityBigEndian);
            wipe(a1BigEndian);
            wipe(a2BigEndian);
            wipe(messageBigEndian);
            wipe(macBigEndian);
        }
    }

    static LocalOobMaterial generateLocalOob(
            SecureRandom random) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(
                DOMAIN,
                random));
        AsymmetricCipherKeyPair pair =
                generator.generateKeyPair();
        ECPrivateKeyParameters privateKey =
                (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters publicKey =
                (ECPublicKeyParameters) pair.getPublic();
        ECPoint point = publicKey.getQ().normalize();

        byte[] privateScalar =
                toLittleEndian(
                        privateKey.getD(),
                        P256_VALUE_LENGTH);
        byte[] publicX =
                toLittleEndian(
                        point.getAffineXCoord()
                                .toBigInteger(),
                        P256_VALUE_LENGTH);
        byte[] publicY =
                toLittleEndian(
                        point.getAffineYCoord()
                                .toBigInteger(),
                        P256_VALUE_LENGTH);
        byte[] randomizer =
                new byte[AppleLeScOobData.VALUE_LENGTH];
        random.nextBytes(randomizer);
        byte[] confirmation =
                f4(publicX, publicX, randomizer, 0);
        try {
            return new LocalOobMaterial(
                    privateScalar,
                    publicX,
                    publicY,
                    randomizer,
                    confirmation);
        } finally {
            wipe(privateScalar);
            wipe(publicX);
            wipe(publicY);
            wipe(randomizer);
            wipe(confirmation);
        }
    }

    static final class F5Result {
        private final byte[] macKey;
        private final byte[] longTermKey;
        private boolean destroyed;

        private F5Result(
                byte[] macKey,
                byte[] longTermKey) {
            this.macKey = macKey.clone();
            this.longTermKey = longTermKey.clone();
        }

        byte[] macKey() {
            requireLive();
            return macKey.clone();
        }

        byte[] longTermKey() {
            requireLive();
            return longTermKey.clone();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(macKey);
            wipe(longTermKey);
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "LE Secure Connections f5 result "
                                + "has been destroyed");
            }
        }
    }

    static final class LocalOobMaterial {
        private final byte[] privateScalar;
        private final byte[] publicX;
        private final byte[] publicY;
        private final AppleLeScOobData appleOobData;
        private boolean destroyed;

        private LocalOobMaterial(
                byte[] privateScalar,
                byte[] publicX,
                byte[] publicY,
                byte[] randomizer,
                byte[] confirmation) {
            this.privateScalar = privateScalar.clone();
            this.publicX = publicX.clone();
            this.publicY = publicY.clone();
            this.appleOobData =
                    AppleLeScOobData.of(
                            randomizer,
                            confirmation);
        }

        byte[] publicKeyForSmp() {
            requireLive();
            byte[] output = new byte[PUBLIC_KEY_LENGTH];
            System.arraycopy(
                    publicX,
                    0,
                    output,
                    0,
                    P256_VALUE_LENGTH);
            System.arraycopy(
                    publicY,
                    0,
                    output,
                    P256_VALUE_LENGTH,
                    P256_VALUE_LENGTH);
            return output;
        }

        byte[] appleOobData() {
            requireLive();
            return appleOobData.serialize();
        }

        byte[] calculateDhKey(
                byte[] peerPublicKeyLittleEndian) {
            requireLive();
            requireLength(
                    "peer SMP public key",
                    peerPublicKeyLittleEndian,
                    PUBLIC_KEY_LENGTH);
            byte[] peerXBigEndian = reverseCopy(
                    Arrays.copyOfRange(
                            peerPublicKeyLittleEndian,
                            0,
                            P256_VALUE_LENGTH));
            byte[] peerYBigEndian = reverseCopy(
                    Arrays.copyOfRange(
                            peerPublicKeyLittleEndian,
                            P256_VALUE_LENGTH,
                            PUBLIC_KEY_LENGTH));
            byte[] privateBigEndian =
                    reverseCopy(privateScalar);
            try {
                BigInteger peerX =
                        new BigInteger(1, peerXBigEndian);
                BigInteger peerY =
                        new BigInteger(1, peerYBigEndian);
                ECPoint peerPoint =
                        P256.getCurve()
                                .validatePoint(peerX, peerY)
                                .normalize();
                if (peerPoint.isInfinity()) {
                    throw new IllegalArgumentException(
                            "peer SMP public key is the point at infinity");
                }
                ECDHBasicAgreement agreement =
                        new ECDHBasicAgreement();
                agreement.init(new ECPrivateKeyParameters(
                        new BigInteger(1, privateBigEndian),
                        DOMAIN));
                BigInteger dhKey =
                        agreement.calculateAgreement(
                                new ECPublicKeyParameters(
                                        peerPoint,
                                        DOMAIN));
                return toLittleEndian(
                        dhKey,
                        P256_VALUE_LENGTH);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                        "invalid peer SMP P-256 public key",
                        error);
            } finally {
                wipe(peerXBigEndian);
                wipe(peerYBigEndian);
                wipe(privateBigEndian);
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(privateScalar);
            wipe(publicX);
            wipe(publicY);
            appleOobData.destroy();
            destroyed = true;
        }

        boolean isDestroyed() {
            return destroyed;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "local LE SC OOB material has been destroyed");
            }
        }
    }

    private static byte[] toLittleEndian(
            BigInteger value,
            int length) {
        return reverseCopy(
                BigIntegers.asUnsignedByteArray(
                        length,
                        value));
    }

    private static byte[] reverseCopy(byte[] value) {
        byte[] output = value.clone();
        for (int left = 0, right = output.length - 1;
                left < right;
                left++, right--) {
            byte swap = output[left];
            output[left] = output[right];
            output[right] = swap;
        }
        return output;
    }

    private static void buildF5Message(
            byte[] output,
            int counter,
            byte[] n1BigEndian,
            byte[] n2BigEndian,
            byte[] a1BigEndian,
            byte[] a2BigEndian) {
        int offset = 0;
        output[offset++] = (byte) counter;
        offset = copy(
                F5_KEY_ID_BIG_ENDIAN,
                output,
                offset);
        offset = copy(
                n1BigEndian,
                output,
                offset);
        offset = copy(
                n2BigEndian,
                output,
                offset);
        offset = copy(
                a1BigEndian,
                output,
                offset);
        offset = copy(
                a2BigEndian,
                output,
                offset);
        output[offset++] = 0x01;
        output[offset] = 0x00;
    }

    private static int copy(
            byte[] source,
            byte[] destination,
            int offset) {
        System.arraycopy(
                source,
                0,
                destination,
                offset,
                source.length);
        return offset + source.length;
    }

    private static void calculateCmacBigEndian(
            byte[] keyBigEndian,
            byte[] messageBigEndian,
            byte[] outputBigEndian) {
        CMac cmac = new CMac(AESEngine.newInstance());
        cmac.init(new KeyParameter(keyBigEndian));
        cmac.update(
                messageBigEndian,
                0,
                messageBigEndian.length);
        cmac.doFinal(outputBigEndian, 0);
    }

    private static void requireLength(
            String name,
            byte[] value,
            int length) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(
                    name + " must be exactly "
                            + length
                            + " bytes");
        }
    }

    static boolean matchesRpa(byte[] address, byte[] irk) {
        if (address == null || address.length != 6 || irk == null || irk.length != 16) {
            return false;
        }
        if ((address[5] & 0xc0) != 0x40) {
            return false;
        }
        byte[] rBe = new byte[16];
        rBe[13] = address[5];
        rBe[14] = address[4];
        rBe[15] = address[3];

        AESEngine aes = new AESEngine();
        aes.init(true, new KeyParameter(irk));
        byte[] out = new byte[16];
        aes.processBlock(rBe, 0, out, 0);
        if ((out[15] == address[0]) && (out[14] == address[1]) && (out[13] == address[2])) {
            return true;
        }

        byte[] reversedIrk = reverseCopy(irk);
        aes.init(true, new KeyParameter(reversedIrk));
        aes.processBlock(rBe, 0, out, 0);
        if ((out[15] == address[0]) && (out[14] == address[1]) && (out[13] == address[2])) {
            return true;
        }

        byte[] rLe = new byte[16];
        rLe[0] = address[3];
        rLe[1] = address[4];
        rLe[2] = address[5];
        aes.init(true, new KeyParameter(irk));
        aes.processBlock(rLe, 0, out, 0);
        if ((out[0] == address[0]) && (out[1] == address[1]) && (out[2] == address[2])) {
            return true;
        }

        aes.init(true, new KeyParameter(reversedIrk));
        aes.processBlock(rLe, 0, out, 0);
        return (out[0] == address[0]) && (out[1] == address[1]) && (out[2] == address[2]);
    }

    static byte[] generateRpa(byte[] irk) {
        if (irk == null || irk.length != 16) {
            throw new IllegalArgumentException("IRK must be 16 bytes");
        }
        byte[] prand = new byte[3];
        new java.security.SecureRandom().nextBytes(prand);
        prand[2] = (byte) ((prand[2] & 0x3f) | 0x40);

        byte[] rBe = new byte[16];
        rBe[13] = prand[2];
        rBe[14] = prand[1];
        rBe[15] = prand[0];

        AESEngine aes = new AESEngine();
        aes.init(true, new KeyParameter(irk));
        byte[] out = new byte[16];
        aes.processBlock(rBe, 0, out, 0);

        byte[] rpa = new byte[6];
        rpa[0] = out[15];
        rpa[1] = out[14];
        rpa[2] = out[13];
        rpa[3] = prand[0];
        rpa[4] = prand[1];
        rpa[5] = prand[2];
        return rpa;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
