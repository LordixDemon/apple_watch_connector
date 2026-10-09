package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.Test;

/** Independent JCA capsule producer is a fixture, never outgoing production Health traffic. */
public final class IdsAOverCTest {
    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(1280); return generator.generateKeyPair();
    }
    private static KeyPair ec() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair();
    }
    private static byte[] fixture(PublicKey recipient, PrivateKey signer, byte[] plaintext, int embeddedSize) throws Exception {
        byte[] transformKey=new byte[16], contentKey=new byte[16], iv=new byte[16]; iv[15]=1;
        new SecureRandom().nextBytes(transformKey); new SecureRandom().nextBytes(contentKey);
        Cipher ctr=Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(transformKey,"AES"),new IvParameterSpec(iv));
        byte[] embedded=new byte[embeddedSize]; System.arraycopy(transformKey,0,embedded,0,16);
        byte[] ciphertext=ctr.doFinal(contentKey); System.arraycopy(ciphertext,0,embedded,16,Math.min(16,embeddedSize-16));
        Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.ENCRYPT_MODE,recipient,new OAEPParameterSpec("SHA-1","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
        byte[] rsaCipher=rsa.doFinal(embedded);
        Signature signature=Signature.getInstance("SHA1withECDSA"); signature.initSign(signer); signature.update(rsaCipher);
        byte[] sig=signature.sign(), capsule=new byte[4+rsaCipher.length+sig.length];
        capsule[0]=2; capsule[1]=(byte)(rsaCipher.length>>>8); capsule[2]=(byte)rsaCipher.length;
        System.arraycopy(rsaCipher,0,capsule,3,rsaCipher.length); capsule[3+rsaCipher.length]=(byte)sig.length;
        System.arraycopy(sig,0,capsule,4+rsaCipher.length,sig.length);
        Cipher cbc=Cipher.getInstance("AES/CBC/PKCS5Padding");
        cbc.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(contentKey,"AES"),new IvParameterSpec(new byte[16]));
        return AppleBinaryPropertyList.encode(Map.of("ekd",capsule,"sed",cbc.doFinal(plaintext)));
    }
    @Test public void verifiesSenderBeforeExposingNativePlaintext() throws Exception {
        KeyPair recipient=rsa(),sender=ec(); byte[] plaintext={2,0,2,16,12,58,0};
        byte[] dictionary=fixture(recipient.getPublic(),sender.getPrivate(),plaintext,32);
        assertArrayEquals(plaintext,IdsAOverC.decrypt(dictionary,sender.getPublic(),recipient.getPrivate()));
        assertThrows(GeneralSecurityException.class,()->IdsAOverC.decrypt(dictionary,ec().getPublic(),recipient.getPrivate()));
        assertThrows(GeneralSecurityException.class,()->IdsAOverC.decrypt(dictionary,sender.getPublic(),rsa().getPrivate()));
    }
    @Test public void tamperedTruncatedVersionAndUnsupportedEmbeddedProfilesFailClosed() throws Exception {
        KeyPair recipient=rsa(),sender=ec(); byte[] dictionary=fixture(recipient.getPublic(),sender.getPrivate(),new byte[]{2,0,0},32);
        Map<?,?> decoded=(Map<?,?>)AppleBinaryPropertyList.decode(dictionary);
        byte[] original=(byte[])decoded.get("ekd"),sed=(byte[])decoded.get("sed");
        try {
            for(int position:new int[]{0,1,2,3,163,original.length-1}) {
                byte[] capsule=original.clone(); capsule[position]^=1;
                byte[] altered=AppleBinaryPropertyList.encode(Map.of("ekd",capsule,"sed",sed));
                assertThrows(GeneralSecurityException.class,()->IdsAOverC.decrypt(altered,sender.getPublic(),recipient.getPrivate()));
            }
            byte[] truncated=AppleBinaryPropertyList.encode(Map.of("ekd",Arrays.copyOf(original,original.length-1),"sed",sed));
            assertThrows(GeneralSecurityException.class,()->IdsAOverC.decrypt(truncated,sender.getPublic(),recipient.getPrivate()));
            byte[] unsupported=fixture(recipient.getPublic(),sender.getPrivate(),new byte[]{2,0,0},33);
            assertThrows(GeneralSecurityException.class,()->IdsAOverC.decrypt(unsupported,sender.getPublic(),recipient.getPrivate()));
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
    @Test public void actualIdentityUsesLocalClassAAndPeerNativeDerSigningKey() throws Exception {
        KeyPair peerEc=ec(),peerRsa=rsa(); KeyGenerator kg=KeyGenerator.getInstance("AES");kg.init(256); SecretKey storage=kg.generateKey();
        try(var original=IdsMessageProtectionIdentity.generate(new SecureRandom());
            var restored=IdsMessageProtectionIdentity.open(original.seal(storage),storage)) {
            Map<String,byte[]> bundle=IdsMessageProtectionIdentity.parsePublicBundle(original.publicBundle());
            byte[] peer=IdsMessageProtectionIdentity.encodePublic(peerEc.getPublic(),peerRsa.getPublic());
            try {
                var seq=org.bouncycastle.asn1.ASN1Sequence.getInstance(bundle.get("A"));
                byte[] encoded=org.bouncycastle.asn1.ASN1OctetString.getInstance(
                        org.bouncycastle.asn1.ASN1TaggedObject.getInstance(seq.getObjectAt(1)),false).getOctets();
                var rsa=org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(Arrays.copyOfRange(encoded,2,encoded.length));
                PublicKey recipient=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent()));
                byte[] plaintext={2,0,0,16,12},encrypted=fixture(recipient,peerEc.getPrivate(),plaintext,32);
                assertArrayEquals(plaintext,restored.decryptClassA(encrypted,peer));
                restored.close(); assertThrows(IllegalStateException.class,()->restored.decryptClassA(encrypted,peer));
            } finally { IdsMessageProtectionIdentity.wipeValues(bundle); }
        }
    }
    @Test public void signedCapsuleOutsideRecipientModulusFailsAsSecurityErrorBeforeRsaProvider() throws Exception {
        KeyPair recipient=rsa(),sender=ec();byte[] body=new byte[160];Arrays.fill(body,(byte)0xff);
        Signature signer=Signature.getInstance("SHA1withECDSA");signer.initSign(sender.getPrivate());signer.update(body);
        byte[] sig=signer.sign(),capsule=new byte[164+sig.length];capsule[0]=2;capsule[2]=(byte)160;
        System.arraycopy(body,0,capsule,3,160);capsule[163]=(byte)sig.length;System.arraycopy(sig,0,capsule,164,sig.length);
        byte[] dictionary=AppleBinaryPropertyList.encode(Map.of("ekd",capsule,"sed",new byte[16]));
        assertThrows(GeneralSecurityException.class,()->IdsAOverC.decrypt(dictionary,sender.getPublic(),recipient.getPrivate()));
    }
}
