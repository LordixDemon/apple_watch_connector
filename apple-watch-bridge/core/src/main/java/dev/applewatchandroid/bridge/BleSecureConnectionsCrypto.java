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
public final class BleSecureConnectionsCrypto {
    public static final int AES_VALUE_LENGTH = 16;
    public static final int P256_VALUE_LENGTH = 32;
    public static final int PUBLIC_KEY_LENGTH = 64;
    public static final int DEVICE_ADDRESS_LENGTH = 7;
    public static final int IO_CAPABILITY_LENGTH = 3;

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
    public static byte[] f4(
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
    public static F5Result f5(
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
    public static byte[] f6(
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

    /**
     * Bluetooth Core Vol 3 Part H Section 2.2.9 {@code g2(U, V, X, Y) = AES-CMAC_X(U || V || Y) mod 2^32},
     * accepting SMP little-endian wire inputs and returning the unsigned 32-bit integer value.
     */
    public static long g2(
            byte[] uLittleEndian,
            byte[] vLittleEndian,
            byte[] xLittleEndian,
            byte[] yLittleEndian) {
        requireLength("g2 U", uLittleEndian, P256_VALUE_LENGTH);
        requireLength("g2 V", vLittleEndian, P256_VALUE_LENGTH);
        requireLength("g2 X", xLittleEndian, AES_VALUE_LENGTH);
        requireLength("g2 Y", yLittleEndian, AES_VALUE_LENGTH);

        byte[] keyBigEndian = reverseCopy(xLittleEndian);
        byte[] uBigEndian = reverseCopy(uLittleEndian);
        byte[] vBigEndian = reverseCopy(vLittleEndian);
        byte[] yBigEndian = reverseCopy(yLittleEndian);
        byte[] messageBigEndian = new byte[P256_VALUE_LENGTH * 2 + AES_VALUE_LENGTH];
        byte[] macBigEndian = new byte[AES_VALUE_LENGTH];
        try {
            int offset = copy(uBigEndian, messageBigEndian, 0);
            offset = copy(vBigEndian, messageBigEndian, offset);
            copy(yBigEndian, messageBigEndian, offset);
            calculateCmacBigEndian(keyBigEndian, messageBigEndian, macBigEndian);
            return ((long) (macBigEndian[12] & 0xff) << 24)
                    | ((long) (macBigEndian[13] & 0xff) << 16)
                    | ((long) (macBigEndian[14] & 0xff) << 8)
                    | (long) (macBigEndian[15] & 0xff);
        } finally {
            wipe(keyBigEndian);
            wipe(uBigEndian);
            wipe(vBigEndian);
            wipe(yBigEndian);
            wipe(messageBigEndian);
            wipe(macBigEndian);
        }
    }

    /**
     * Derives the 6-digit LE Secure Connections Numeric Comparison value
     * ({@code g2(U, V, X, Y) mod 1_000_000}).
     */
    public static int g2NumericComparison(
            byte[] uLittleEndian,
            byte[] vLittleEndian,
            byte[] xLittleEndian,
            byte[] yLittleEndian) {
        return (int) (g2(uLittleEndian, vLittleEndian, xLittleEndian, yLittleEndian) % 1_000_000L);
    }

    /**
     * Derives the 128-bit {@code ra}/{@code rb} value for Bluetooth Core LE SC Passkey Entry
     * (Vol 3 Part H Section 2.3.5.6.3) in SMP little-endian wire order.
     */
    public static byte[] passkeyToR128LittleEndian(int passkey) {
        if (passkey < 0 || passkey > 999_999) {
            throw new IllegalArgumentException("BLE SMP passkey must be in 0..999999");
        }
        byte[] r = new byte[AES_VALUE_LENGTH];
        r[0] = (byte) (passkey & 0xff);
        r[1] = (byte) ((passkey >>> 8) & 0xff);
        r[2] = (byte) ((passkey >>> 16) & 0xff);
        return r;
    }

    /**
     * Returns the single-byte round parameter {@code z = 0x80 | bit_i(passkey)} for round
     * {@code 0 <= round < 20} of Bluetooth Core LE SC Passkey Entry.
     */
    public static int passkeyRoundZ(int passkey, int round) {
        if (passkey < 0 || passkey > 999_999) {
            throw new IllegalArgumentException("BLE SMP passkey must be in 0..999999");
        }
        if (round < 0 || round >= 20) {
            throw new IllegalArgumentException("BLE SMP passkey round must be in 0..19");
        }
        return 0x80 | ((passkey >>> round) & 1);
    }

    /**
     * Bluetooth Core Vol 3 Part H Section 2.2.3 confirm value generation function {@code c1(k, r, preq, pres, iat, ia, rat, ra)}
     * for LE Legacy Pairing (SC = 0), with all multi-octet inputs and output in SMP little-endian order.
     */
    public static byte[] legacyC1(
            byte[] tkLittleEndian,
            byte[] rLittleEndian,
            byte[] preqLittleEndian,
            byte[] presLittleEndian,
            int iat,
            byte[] iaLittleEndian,
            int rat,
            byte[] raLittleEndian) {
        requireLength("c1 TK", tkLittleEndian, AES_VALUE_LENGTH);
        requireLength("c1 r", rLittleEndian, AES_VALUE_LENGTH);
        requireLength("c1 preq", preqLittleEndian, 7);
        requireLength("c1 pres", presLittleEndian, 7);
        requireLength("c1 ia", iaLittleEndian, 6);
        requireLength("c1 ra", raLittleEndian, 6);
        if ((iat != 0 && iat != 1) || (rat != 0 && rat != 1)) {
            throw new IllegalArgumentException("c1 address types must be 0 or 1");
        }

        byte[] kBe = reverseCopy(tkLittleEndian);
        byte[] rBe = reverseCopy(rLittleEndian);
        byte[] preqBe = reverseCopy(preqLittleEndian);
        byte[] presBe = reverseCopy(presLittleEndian);
        byte[] iaBe = reverseCopy(iaLittleEndian);
        byte[] raBe = reverseCopy(raLittleEndian);
        byte[] p1Be = new byte[AES_VALUE_LENGTH];
        byte[] p2Be = new byte[AES_VALUE_LENGTH];
        byte[] x1Be = new byte[AES_VALUE_LENGTH];
        byte[] c1Be = new byte[AES_VALUE_LENGTH];
        try {
            // Core Spec Vol 3 Part H §2.2.3: p1 = pres || preq || rat' || iat' (MSB first)
            System.arraycopy(presBe, 0, p1Be, 0, 7);
            System.arraycopy(preqBe, 0, p1Be, 7, 7);
            p1Be[14] = (byte) rat;
            p1Be[15] = (byte) iat;

            // Core Spec Vol 3 Part H §2.2.3: p2 = padding(4B 0) || ia || ra (MSB first)
            System.arraycopy(iaBe, 0, p2Be, 4, 6);
            System.arraycopy(raBe, 0, p2Be, 10, 6);

            for (int i = 0; i < AES_VALUE_LENGTH; i++) {
                x1Be[i] = (byte) (rBe[i] ^ p1Be[i]);
            }
            AESEngine aes = new AESEngine();
            aes.init(true, new KeyParameter(kBe));
            aes.processBlock(x1Be, 0, x1Be, 0);
            for (int i = 0; i < AES_VALUE_LENGTH; i++) {
                x1Be[i] ^= p2Be[i];
            }
            aes.processBlock(x1Be, 0, c1Be, 0);
            return reverseCopy(c1Be);
        } finally {
            wipe(kBe);
            wipe(rBe);
            wipe(preqBe);
            wipe(presBe);
            wipe(iaBe);
            wipe(raBe);
            wipe(p1Be);
            wipe(p2Be);
            wipe(x1Be);
            wipe(c1Be);
        }
    }

    /**
     * Bluetooth Core Vol 3 Part H Section 2.2.4 key generation function {@code s1(k, r1, r2)}
     * for LE Legacy Pairing STK derivation, with all multi-octet inputs and output in SMP little-endian order.
     */
    public static byte[] legacyS1(
            byte[] tkLittleEndian,
            byte[] r1LittleEndian,
            byte[] r2LittleEndian) {
        requireLength("s1 TK", tkLittleEndian, AES_VALUE_LENGTH);
        requireLength("s1 r1", r1LittleEndian, AES_VALUE_LENGTH);
        requireLength("s1 r2", r2LittleEndian, AES_VALUE_LENGTH);

        byte[] kBe = reverseCopy(tkLittleEndian);
        byte[] r1LowBe = reverseCopy(Arrays.copyOfRange(r1LittleEndian, 0, 8));
        byte[] r2LowBe = reverseCopy(Arrays.copyOfRange(r2LittleEndian, 0, 8));
        byte[] rPrimeBe = new byte[AES_VALUE_LENGTH];
        byte[] stkBe = new byte[AES_VALUE_LENGTH];
        try {
            // Core Spec Vol 3 Part H §2.2.4: r' = r1' || r2' where r1' = lower 64 bits of r1 MSB-first
            System.arraycopy(r1LowBe, 0, rPrimeBe, 0, 8);
            System.arraycopy(r2LowBe, 0, rPrimeBe, 8, 8);
            AESEngine aes = new AESEngine();
            aes.init(true, new KeyParameter(kBe));
            aes.processBlock(rPrimeBe, 0, stkBe, 0);
            return reverseCopy(stkBe);
        } finally {
            wipe(kBe);
            wipe(r1LowBe);
            wipe(r2LowBe);
            wipe(rPrimeBe);
            wipe(stkBe);
        }
    }

    public static LocalOobMaterial generateLocalOob(
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

    public static final class F5Result {
        private final byte[] macKey;
        private final byte[] longTermKey;
        private boolean destroyed;

        private F5Result(
                byte[] macKey,
                byte[] longTermKey) {
            this.macKey = macKey.clone();
            this.longTermKey = longTermKey.clone();
        }

        public byte[] macKey() {
            requireLive();
            return macKey.clone();
        }

        public byte[] longTermKey() {
            requireLive();
            return longTermKey.clone();
        }

        public void destroy() {
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

    public static final class LocalOobMaterial {
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

        public byte[] publicKeyForSmp() {
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

        public byte[] publicXLittleEndian() {
            requireLive();
            return publicX.clone();
        }

        public byte[] appleOobData() {
            requireLive();
            return appleOobData.serialize();
        }

        public byte[] calculateDhKey(
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

        public void destroy() {
            if (destroyed) {
                return;
            }
            wipe(privateScalar);
            wipe(publicX);
            wipe(publicY);
            appleOobData.destroy();
            destroyed = true;
        }

        public boolean isDestroyed() {
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

    public static boolean matchesRpa(byte[] address, byte[] irk) {
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
