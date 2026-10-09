package dev.applewatchandroid.bridge;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** SecMP v2 inline A-over-C profile; exact23S303 IDSEncryptionHelpers uses a16-byte AES key. */
final class IdsAOverC {
    private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
    private IdsAOverC() { }

    /** Builds native inline capsules only. Transport/session authorization is the caller's responsibility. */
    static byte[] encrypt(byte[] plaintext, PrivateKey sender, PublicKey recipient,
            byte[] senderHash, byte[] recipientHash, SecureRandom random) throws GeneralSecurityException {
        if(plaintext==null || plaintext.length==0 || plaintext.length>NativeHealthSyncCodec.MAX_BYTES
                || sender==null || !(recipient instanceof RSAPublicKey rsa) || rsa.getModulus().bitLength()!=1280
                || senderHash==null || senderHash.length!=32 || recipientHash==null || recipientHash.length!=32
                || random==null) throw new GeneralSecurityException("Invalid A-over-C sender/recipient/plaintext");
        byte[] contentKey=new byte[16], transformKey=new byte[16], context=new byte[65];
        byte[] tag=null, wrapped=null, embedded=null, body=null, signature=null, capsule=null, sed=null, dictionary=null;
        try {
            random.nextBytes(contentKey); random.nextBytes(transformKey);
            // Native SecMPComputeIntegrityTag: HMAC-SHA256 with first11 random bytes;
            // input content-key || version2 || canonical sender/recipient hashes, first5 bytes at offset11.
            context[0]=2; System.arraycopy(senderHash,0,context,1,32); System.arraycopy(recipientHash,0,context,33,32);
            Mac mac=Mac.getInstance("HmacSHA256",PROVIDER);
            mac.init(new SecretKeySpec(transformKey,0,11,"HmacSHA256")); mac.update(contentKey);
            tag=mac.doFinal(context); System.arraycopy(tag,0,transformKey,11,5);
            byte[] iv=new byte[16]; iv[15]=1;
            Cipher ctr=Cipher.getInstance("AES/CTR/NoPadding",PROVIDER);
            ctr.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(transformKey,"AES"),new IvParameterSpec(iv));
            wrapped=ctr.doFinal(contentKey); embedded=new byte[32];
            System.arraycopy(transformKey,0,embedded,0,16); System.arraycopy(wrapped,0,embedded,16,16);
            Cipher rsaCipher=Cipher.getInstance("RSA/ECB/OAEPPadding",PROVIDER);
            rsaCipher.init(Cipher.ENCRYPT_MODE,recipient,new OAEPParameterSpec("SHA-1","MGF1",
                    MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT),random);
            body=rsaCipher.doFinal(embedded);
            Signature signer=Signature.getInstance("SHA1withECDSA",PROVIDER);
            signer.initSign(sender,random); signer.update(body); signature=signer.sign();
            if(body.length!=160 || signature.length<8 || signature.length>80) {
                throw new GeneralSecurityException("Invalid A-over-C generated capsule size");
            }
            capsule=new byte[4+body.length+signature.length]; capsule[0]=2;
            capsule[1]=(byte)(body.length>>>8); capsule[2]=(byte)body.length;
            System.arraycopy(body,0,capsule,3,body.length); capsule[3+body.length]=(byte)signature.length;
            System.arraycopy(signature,0,capsule,4+body.length,signature.length);
            Cipher cbc=Cipher.getInstance("AES/CBC/PKCS5Padding",PROVIDER);
            cbc.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(contentKey,"AES"),new IvParameterSpec(new byte[16]));
            sed=cbc.doFinal(plaintext); dictionary=AppleBinaryPropertyList.encode(Map.of("ekd",capsule,"sed",sed));
            try { HealthDataEventCodec.requireEncryptedDictionary(dictionary); }
            catch(IllegalArgumentException tooLarge) { throw new GeneralSecurityException("A-over-C encrypted frame exceeds limit",tooLarge); }
            byte[] result=dictionary; dictionary=null; return result;
        } finally {
            wipe(contentKey); wipe(transformKey); wipe(context); wipe(tag); wipe(wrapped); wipe(embedded);
            wipe(body); wipe(signature); wipe(capsule); wipe(sed); wipe(dictionary);
        }
    }

    static byte[] decrypt(byte[] dictionary, PublicKey sender, PrivateKey recipient) throws GeneralSecurityException {
        HealthDataEventCodec.requireEncryptedDictionary(dictionary);
        Object decoded = AppleBinaryPropertyList.decode(dictionary);
        byte[] payload = null, signature = null, rsaPlain = null, key = null;
        try {
            Map<?,?> map = (Map<?,?>)decoded;
            byte[] ekd = (byte[])map.get("ekd"), sed = (byte[])map.get("sed");
            if (!(recipient instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() != 1280
                    || sender == null || (ekd[0] & 255) != 2) {
                throw new GeneralSecurityException("Unsupported A-over-C identity/capsule version");
            }
            int length = ((ekd[1]&255)<<8) | (ekd[2]&255);
            // Native A-over-C uses a16-byte AES key, fitting the RSA-contained32-byte profile.
            // SecMP additionally supports integrity-tag/extended CTR bodies; do not silently truncate those.
            if (length != 160 || length + 4 >= ekd.length) {
                throw new GeneralSecurityException("Unsupported A-over-C key capsule profile");
            }
            int signatureSize = ekd[3+length] & 255;
            if (signatureSize < 8 || signatureSize > 80 || ekd.length != 4 + length + signatureSize) {
                throw new GeneralSecurityException("Invalid A-over-C signature size");
            }
            payload = Arrays.copyOfRange(ekd,3,3+length);
            signature = Arrays.copyOfRange(ekd,4+length,ekd.length);
            Signature verify = Signature.getInstance("SHA1withECDSA",PROVIDER);
            verify.initVerify(sender); verify.update(payload);
            if (!verify.verify(signature)) throw new GeneralSecurityException("Invalid A-over-C sender signature");
            if(new java.math.BigInteger(1,payload).compareTo(rsa.getModulus())>=0) {
                throw new GeneralSecurityException("A-over-C capsule outside recipient RSA modulus");
            }
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding",PROVIDER);
            rsaCipher.init(Cipher.DECRYPT_MODE,recipient,new OAEPParameterSpec("SHA-1","MGF1",
                    MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
            rsaPlain = rsaCipher.doFinal(payload);
            if (rsaPlain.length != 32) throw new GeneralSecurityException("Unsupported A-over-C embedded key length");
            byte[] iv = new byte[16]; iv[15]=1;
            Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding",PROVIDER);
            ctr.init(Cipher.DECRYPT_MODE,new SecretKeySpec(rsaPlain,0,16,"AES"),new IvParameterSpec(iv));
            key = ctr.doFinal(rsaPlain,16,16);
            Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding",PROVIDER);
            cbc.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(new byte[16]));
            byte[] plaintext = cbc.doFinal(sed);
            if (plaintext.length > NativeHealthSyncCodec.MAX_BYTES) {
                wipe(plaintext); throw new GeneralSecurityException("A-over-C plaintext exceeds limit");
            }
            return plaintext;
        } finally {
            IdsMessageProtectionIdentity.wipeValues(decoded);
            wipe(payload); wipe(signature); wipe(rsaPlain); wipe(key);
        }
    }
    private static void wipe(byte[] value) { if(value != null) Arrays.fill(value,(byte)0); }
}
