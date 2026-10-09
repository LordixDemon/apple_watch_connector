package dev.applewatchandroid.bridge;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Real SecMessageLegacy identities, sealed at rest; unrelated to NWSC keys. */
final class IdsMessageProtectionIdentity implements AutoCloseable {
    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    private static final String[] CLASSES = {"A", "C", "D"};
    private static final byte[] FILE_MAGIC = {'I','D','S','M','P',1};
    private static final int MAX_RECORD = 32 * 1024;
    private final KeyPair[] signing = new KeyPair[3];
    private final KeyPair[] encryption = new KeyPair[3];
    private boolean closed;

    static IdsMessageProtectionIdentity generate(SecureRandom random) {
        IdsMessageProtectionIdentity material = new IdsMessageProtectionIdentity();
        try {
            KeyPairGenerator ec = KeyPairGenerator.getInstance("EC", PROVIDER);
            ec.initialize(new ECGenParameterSpec("secp256r1"), random);
            KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA", PROVIDER);
            // Exact legacy SecMP profile in iOS 26.6, including its public-key importer.
            rsa.initialize(new RSAKeyGenParameterSpec(1280, RSAKeyGenParameterSpec.F4), random);
            for (int i = 0; i < 3; i++) {
                material.signing[i] = ec.generateKeyPair();
                material.encryption[i] = rsa.generateKeyPair();
            }
            return material;
        } catch (Exception failure) {
            material.close();
            throw new IllegalStateException("Cannot generate IDS message-protection identities", failure);
        }
    }

    byte[] publicBundle() {
        requireOpen();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", 1L);
        try {
            for (int i = 0; i < 3; i++) {
                values.put(CLASSES[i], encodePublic(signing[i].getPublic(), encryption[i].getPublic()));
            }
            return AppleBinaryPropertyList.encode(values);
        } finally { wipeValues(values); }
    }

    static Map<String, byte[]> parsePublicBundle(byte[] bytes) {
        Map<?, ?> root = record(bytes);
        Map<String, byte[]> keys = new LinkedHashMap<>();
        try {
            if (root.size() != 4 && !(root.size() == 5 && root.containsKey("identifier"))) {
                throw new IllegalArgumentException("Invalid IDS public bundle fields");
            }
            if (root.containsKey("identifier")) {
                if (!(root.get("identifier") instanceof String identifier)) {
                    throw new IllegalArgumentException("Invalid IDS bundle device identifier");
                }
                IdsDeviceIdentifier.canonical(identifier);
            }
            for (String name : CLASSES) {
                Object value = root.get(name);
                if (!(value instanceof byte[] key)) throw new IllegalArgumentException("Missing IDS public key");
                validatePublic(key);
                keys.put(name, key.clone());
            }
            return keys;
        } catch (RuntimeException error) {
            wipeValues(keys);
            throw error;
        } finally { wipeValues(root); }
    }

    static byte[] bindPublicBundle(byte[] bundle, String identifier) {
        Map<String, byte[]> keys = parsePublicBundle(bundle);
        try {
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put("version", 1L);
            bound.put("identifier", IdsDeviceIdentifier.canonical(identifier));
            bound.putAll(keys);
            return AppleBinaryPropertyList.encode(bound);
        } finally { wipeValues(keys); }
    }

    static String publicBundleIdentifier(byte[] bundle) {
        Map<?, ?> root = record(bundle);
        try {
            if (!(root.get("identifier") instanceof String identifier)) {
                throw new IllegalArgumentException("IDS public bundle lacks installation identity");
            }
            return IdsDeviceIdentifier.canonical(identifier);
        } finally { wipeValues(root); }
    }

    static byte[] encodePublic(PublicKey ecKey, PublicKey rsaKey) {
        if (!(ecKey instanceof ECPublicKey ec) || !(rsaKey instanceof RSAPublicKey rsa)) {
            throw new IllegalArgumentException("IDS requires P-256 and RSA public keys");
        }
        if (!isP256(ec.getParams()) || rsa.getModulus().bitLength() != 1280) {
            throw new IllegalArgumentException("IDS legacy public key sizes are invalid");
        }
        byte[] point = new byte[65];
        point[0] = 4;
        putCoordinate(point, 1, ec.getW().getAffineX());
        putCoordinate(point, 33, ec.getW().getAffineY());
        try {
            byte[] rsaDer = new org.bouncycastle.asn1.pkcs.RSAPublicKey(
                    rsa.getModulus(), rsa.getPublicExponent()).getEncoded("DER");
            byte[] result = new DERSequence(new ASN1Encodable[]{
                    new DERTaggedObject(false, 1, new DEROctetString(withSize(point))),
                    new DERTaggedObject(false, 2, new DEROctetString(withSize(rsaDer)))
            }).getEncoded("DER");
            validatePublic(result);
            return result;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Cannot encode IDS public identity", invalid);
        } finally { Arrays.fill(point, (byte) 0); }
    }

    static void validatePublic(byte[] bytes) {
        if (bytes == null || bytes.length < 80 || bytes.length > 4096) {
            throw new IllegalArgumentException("IDS public identity length is invalid");
        }
        try {
            ASN1Sequence sequence = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(bytes));
            if (sequence.size() != 2 || !Arrays.equals(bytes, sequence.getEncoded("DER"))) {
                throw new IllegalArgumentException("IDS public identity is not canonical DER");
            }
            byte[] point = unwrap(sequence, 0, 1);
            byte[] rsaBytes = unwrap(sequence, 1, 2);
            if (point.length != 65 || point[0] != 4) throw new IllegalArgumentException("Invalid IDS signing point");
            org.bouncycastle.math.ec.ECPoint q = ECNamedCurveTable.getByName("secp256r1")
                    .getCurve().decodePoint(point);
            if (q.isInfinity() || !q.isValid()) throw new IllegalArgumentException("Invalid IDS signing point");
            org.bouncycastle.asn1.pkcs.RSAPublicKey rsa = org.bouncycastle.asn1.pkcs.RSAPublicKey
                    .getInstance(ASN1Primitive.fromByteArray(rsaBytes));
            if (!Arrays.equals(rsaBytes, rsa.getEncoded("DER")) || rsa.getModulus().signum() <= 0
                    || !rsa.getModulus().testBit(0) || rsa.getModulus().bitLength() != 1280
                    || !rsa.getPublicExponent().equals(BigInteger.valueOf(65537))) {
                throw new IllegalArgumentException("Invalid IDS encryption key");
            }
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("Malformed IDS public identity", invalid);
        }
    }

    private static byte[] unwrap(ASN1Sequence sequence, int index, int tag) {
        ASN1TaggedObject item = ASN1TaggedObject.getInstance(sequence.getObjectAt(index));
        if (item.getTagNo() != tag || item.getTagClass() != 0x80) {
            throw new IllegalArgumentException("Unexpected IDS public key tag");
        }
        byte[] value = ASN1OctetString.getInstance(item, false).getOctets();
        if (value.length < 2 || (((value[0] & 255) << 8) | (value[1] & 255)) != value.length - 2) {
            throw new IllegalArgumentException("Invalid IDS public key size prefix");
        }
        return Arrays.copyOfRange(value, 2, value.length);
    }

    byte[] decryptClassA(byte[] encrypted, byte[] peerPublicIdentity) throws Exception {
        requireOpen();
        validatePublic(peerPublicIdentity);
        ASN1Sequence sequence = ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(peerPublicIdentity));
        byte[] point = unwrap(sequence,0,1);
        try {
            var curve = ECNamedCurveTable.getByName("secp256r1");
            var q = curve.getCurve().decodePoint(point).normalize();
            PublicKey publicKey = KeyFactory.getInstance("EC",PROVIDER).generatePublic(
                    new org.bouncycastle.jce.spec.ECPublicKeySpec(q,new org.bouncycastle.jce.spec.ECParameterSpec(
                            curve.getCurve(),curve.getG(),curve.getN(),curve.getH(),curve.getSeed())));
            return IdsAOverC.decrypt(encrypted,publicKey,encryption[0].getPrivate());
        } finally { Arrays.fill(point,(byte)0); }
    }

    /** SHA256 of native size-prefixed signing/encryption public representations, not DER container. */
    static byte[] canonicalPublicHash(byte[] identity) throws Exception {
        validatePublic(identity);
        ASN1Sequence sequence=ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(identity));
        byte[] point=unwrap(sequence,0,1), rsa=unwrap(sequence,1,2), sizedPoint=null, sizedRsa=null;
        try {
            sizedPoint=withSize(point); sizedRsa=withSize(rsa);
            var digest=java.security.MessageDigest.getInstance("SHA-256"); digest.update(sizedPoint);
            return digest.digest(sizedRsa);
        } finally { Arrays.fill(point,(byte)0); Arrays.fill(rsa,(byte)0);
            if(sizedPoint!=null) Arrays.fill(sizedPoint,(byte)0); if(sizedRsa!=null) Arrays.fill(sizedRsa,(byte)0); }
    }

    byte[] encryptClassA(byte[] plaintext,byte[] peerPublicIdentity,SecureRandom random) throws Exception {
        requireOpen(); validatePublic(peerPublicIdentity);
        ASN1Sequence sequence=ASN1Sequence.getInstance(ASN1Primitive.fromByteArray(peerPublicIdentity));
        byte[] der=unwrap(sequence,1,2), local=null, localHash=null, peerHash=null;
        try {
            var rsa=org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(der);
            PublicKey recipient=KeyFactory.getInstance("RSA",PROVIDER).generatePublic(
                    new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent()));
            local=encodePublic(signing[0].getPublic(),encryption[0].getPublic());
            localHash=canonicalPublicHash(local); peerHash=canonicalPublicHash(peerPublicIdentity);
            return IdsAOverC.encrypt(plaintext,signing[0].getPrivate(),recipient,localHash,peerHash,random);
        } finally { Arrays.fill(der,(byte)0); if(local!=null) Arrays.fill(local,(byte)0);
            if(localHash!=null) Arrays.fill(localHash,(byte)0); if(peerHash!=null) Arrays.fill(peerHash,(byte)0); }
    }

    byte[] seal(SecretKey key) throws Exception {
        requireOpen();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("version", 1L);
        byte[] plaintext = null;
        try {
            for (int i = 0; i < 3; i++) values.put(CLASSES[i], Map.of(
                    "signing", signing[i].getPrivate().getEncoded(),
                    "encryption", encryption[i].getPrivate().getEncoded()));
            plaintext = AppleBinaryPropertyList.encode(values);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            cipher.updateAAD(FILE_MAGIC);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] iv = cipher.getIV();
            if (iv.length != 12) throw new IllegalStateException("Unexpected IDS identity nonce size");
            byte[] result = new byte[FILE_MAGIC.length + iv.length + encrypted.length];
            System.arraycopy(FILE_MAGIC, 0, result, 0, FILE_MAGIC.length);
            System.arraycopy(iv, 0, result, FILE_MAGIC.length, iv.length);
            System.arraycopy(encrypted, 0, result, FILE_MAGIC.length + iv.length, encrypted.length);
            return result;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            wipeValues(values);
        }
    }

    static IdsMessageProtectionIdentity open(byte[] sealed, SecretKey key) throws Exception {
        if (sealed == null || sealed.length < FILE_MAGIC.length + 28 || sealed.length > MAX_RECORD
                || !Arrays.equals(FILE_MAGIC, Arrays.copyOf(sealed, FILE_MAGIC.length))) {
            throw new IllegalArgumentException("Invalid sealed IDS identity record");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, FILE_MAGIC.length, 12));
        cipher.updateAAD(FILE_MAGIC);
        byte[] plaintext = cipher.doFinal(sealed, FILE_MAGIC.length + 12,
                sealed.length - FILE_MAGIC.length - 12);
        IdsMessageProtectionIdentity result = new IdsMessageProtectionIdentity();
        Map<?, ?> values = null;
        try {
            values = record(plaintext);
            if (values.size() != 4) throw new IllegalArgumentException("Invalid IDS private record fields");
            for (int i = 0; i < 3; i++) {
                if (!(values.get(CLASSES[i]) instanceof Map<?, ?> pair) || pair.size() != 2
                        || !(pair.get("signing") instanceof byte[] ecBytes)
                        || !(pair.get("encryption") instanceof byte[] rsaBytes)) {
                    throw new IllegalArgumentException("Invalid IDS private identity");
                }
                KeyFactory ecFactory = KeyFactory.getInstance("EC", PROVIDER);
                ECPrivateKey ec = (ECPrivateKey) ecFactory.generatePrivate(new PKCS8EncodedKeySpec(ecBytes));
                // Record ownership begins immediately so a later validation failure closes every key.
                result.signing[i] = new KeyPair(null, ec);
                if (!isP256(ec.getParams()) || ec.getS().signum() <= 0
                        || ec.getS().compareTo(ec.getParams().getOrder()) >= 0) {
                    throw new IllegalArgumentException("Invalid IDS P-256 private identity");
                }
                org.bouncycastle.math.ec.ECPoint point = ECNamedCurveTable.getByName("secp256r1")
                        .getG().multiply(ec.getS()).normalize();
                PublicKey publicEc = ecFactory.generatePublic(new ECPublicKeySpec(new ECPoint(
                        point.getAffineXCoord().toBigInteger(), point.getAffineYCoord().toBigInteger()), ec.getParams()));
                KeyFactory rsaFactory = KeyFactory.getInstance("RSA", PROVIDER);
                RSAPrivateCrtKey rsa = (RSAPrivateCrtKey) rsaFactory.generatePrivate(new PKCS8EncodedKeySpec(rsaBytes));
                result.encryption[i] = new KeyPair(null, rsa);
                PublicKey publicRsa = rsaFactory.generatePublic(new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
                validatePublic(encodePublic(publicEc, publicRsa));
                result.signing[i] = new KeyPair(publicEc, ec);
                result.encryption[i] = new KeyPair(publicRsa, rsa);
            }
            return result;
        } catch (Exception invalid) {
            result.close();
            throw invalid;
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            wipeValues(values);
        }
    }

    private static Map<?, ?> record(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_RECORD) throw new IllegalArgumentException("IDS identity record is too large");
        Object decoded = AppleBinaryPropertyList.decode(bytes);
        if (!(decoded instanceof Map<?, ?> map) || !Long.valueOf(1).equals(map.get("version"))) {
            wipeValues(decoded);
            throw new IllegalArgumentException("Unsupported IDS identity record");
        }
        return map;
    }

    private static boolean isP256(ECParameterSpec params) {
        var expected = ECNamedCurveTable.getByName("secp256r1");
        return params != null && params.getCurve().getField() instanceof ECFieldFp field
                && field.getP().equals(expected.getCurve().getField().getCharacteristic())
                && params.getCurve().getA().equals(expected.getCurve().getA().toBigInteger())
                && params.getCurve().getB().equals(expected.getCurve().getB().toBigInteger())
                && params.getOrder().equals(expected.getN()) && params.getCofactor() == 1
                && params.getGenerator().getAffineX().equals(expected.getG().normalize().getAffineXCoord().toBigInteger())
                && params.getGenerator().getAffineY().equals(expected.getG().normalize().getAffineYCoord().toBigInteger());
    }

    static void wipeValues(Object value) {
        if (value instanceof byte[] bytes) Arrays.fill(bytes, (byte) 0);
        else if (value instanceof Map<?, ?> map) map.values().forEach(IdsMessageProtectionIdentity::wipeValues);
        else if (value instanceof java.util.List<?> list) list.forEach(IdsMessageProtectionIdentity::wipeValues);
    }

    private static byte[] withSize(byte[] bytes) {
        byte[] result = new byte[bytes.length + 2];
        result[0] = (byte) (bytes.length >>> 8); result[1] = (byte) bytes.length;
        System.arraycopy(bytes, 0, result, 2, bytes.length);
        return result;
    }

    private static void putCoordinate(byte[] out, int offset, BigInteger number) {
        byte[] bytes = number.toByteArray();
        int start = bytes.length > 32 ? bytes.length - 32 : 0;
        int count = bytes.length - start;
        System.arraycopy(bytes, start, out, offset + 32 - count, count);
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("IDS identity is closed"); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (KeyPair[] group : new KeyPair[][]{signing, encryption}) {
            for (KeyPair pair : group) {
                if (pair == null) continue;
                PrivateKey key = pair.getPrivate();
                try { key.destroy(); } catch (Exception ignored) { }
            }
            Arrays.fill(group, null);
        }
    }
}
