package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.security.*;
import java.security.spec.*;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.junit.Test;

/** Independent platform-JCA receiver checks the production producer, including native integrity tag. */
public final class IdsAOverCOutgoingTest {
    private static KeyPair rsa() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(1280); return generator.generateKeyPair();
    }
    private static KeyPair ec() throws Exception {
        var generator=KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
    private static byte[] receive(byte[] dictionary,PublicKey sender,PrivateKey recipient,
            byte[] senderHash,byte[] recipientHash) throws Exception {
        Map<?,?> decoded=(Map<?,?>)AppleBinaryPropertyList.decode(dictionary);
        byte[] ekd=(byte[])decoded.get("ekd"),sed=(byte[])decoded.get("sed");
        try {
            assertEquals(2,ekd[0]); assertEquals(160,((ekd[1]&255)<<8)|(ekd[2]&255));
            assertEquals(ekd.length-164,ekd[163]&255);
            byte[] body=Arrays.copyOfRange(ekd,3,163),sig=Arrays.copyOfRange(ekd,164,ekd.length);
            var verifier=Signature.getInstance("SHA1withECDSA");verifier.initVerify(sender);verifier.update(body);
            if(!verifier.verify(sig)) throw new GeneralSecurityException("wrong signer");
            var rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");
            rsa.init(Cipher.DECRYPT_MODE,recipient,new OAEPParameterSpec("SHA-1","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
            byte[] embedded=rsa.doFinal(body);assertEquals(32,embedded.length);
            byte[] iv=new byte[16];iv[15]=1;
            var ctr=Cipher.getInstance("AES/CTR/NoPadding");
            ctr.init(Cipher.DECRYPT_MODE,new SecretKeySpec(embedded,0,16,"AES"),new IvParameterSpec(iv));
            byte[] key=ctr.doFinal(embedded,16,16);
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(embedded,0,11,"HmacSHA256"));
            mac.update(key);mac.update((byte)2);mac.update(senderHash);byte[] tag=mac.doFinal(recipientHash);
            assertArrayEquals(Arrays.copyOf(tag,5),Arrays.copyOfRange(embedded,11,16));
            var cbc=Cipher.getInstance("AES/CBC/PKCS5Padding");
            cbc.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(new byte[16]));
            return cbc.doFinal(sed);
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }
    private static PublicKey signingKey(byte[] identity) throws Exception {
        var seq=org.bouncycastle.asn1.ASN1Sequence.getInstance(identity);
        byte[] sized=org.bouncycastle.asn1.ASN1OctetString.getInstance(
                org.bouncycastle.asn1.ASN1TaggedObject.getInstance(seq.getObjectAt(0)),false).getOctets();
        var params=AlgorithmParameters.getInstance("EC");params.init(new ECGenParameterSpec("secp256r1"));
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(
                new java.math.BigInteger(1,Arrays.copyOfRange(sized,3,35)),
                new java.math.BigInteger(1,Arrays.copyOfRange(sized,35,67))),params.getParameterSpec(ECParameterSpec.class)));
    }
    @Test public void productionCapsuleVerifiesAndDecryptsInIndependentReceiver() throws Exception {
        var sender=ec();var recipient=rsa();byte[] sh=new byte[32],rh=new byte[32];Arrays.fill(sh,(byte)1);Arrays.fill(rh,(byte)2);
        byte[] plain={2,0,2,16,12,58,0};
        byte[] encrypted=IdsAOverC.encrypt(plain,sender.getPrivate(),recipient.getPublic(),sh,rh,new SecureRandom());
        assertArrayEquals(plain,receive(encrypted,sender.getPublic(),recipient.getPrivate(),sh,rh));
        assertThrows(GeneralSecurityException.class,() -> receive(encrypted,ec().getPublic(),recipient.getPrivate(),sh,rh));
    }
    @Test public void capsuleUsesFreshKeysAndPreservesInputAcrossBlockBoundaries() throws Exception {
        var sender=ec();var recipient=rsa();byte[] hash=new byte[32];
        for(int length:new int[]{1,15,16,17,4096}) {
            byte[] plain=new byte[length];Arrays.fill(plain,(byte)42);byte[] original=plain.clone();
            byte[] first=IdsAOverC.encrypt(plain,sender.getPrivate(),recipient.getPublic(),hash,hash,new SecureRandom());
            byte[] second=IdsAOverC.encrypt(plain,sender.getPrivate(),recipient.getPublic(),hash,hash,new SecureRandom());
            assertFalse(Arrays.equals(first,second));assertArrayEquals(original,plain);
            assertArrayEquals(original,receive(first,sender.getPublic(),recipient.getPrivate(),hash,hash));
        }
    }
    @Test public void restoredIdentitySignsClassAAndBindsCanonicalPublicHashes() throws Exception {
        var peerEc=ec();var peerRsa=rsa();byte[] peer=IdsMessageProtectionIdentity.encodePublic(peerEc.getPublic(),peerRsa.getPublic());
        var generator=KeyGenerator.getInstance("AES");generator.init(256);var key=generator.generateKey();
        try(var original=IdsMessageProtectionIdentity.generate(new SecureRandom());
            var restored=IdsMessageProtectionIdentity.open(original.seal(key),key)) {
            Map<String,byte[]> local=IdsMessageProtectionIdentity.parsePublicBundle(original.publicBundle());
            byte[] plain={2,0,0},encrypted=restored.encryptClassA(plain,peer,new SecureRandom());
            byte[] a=local.get("A");
            assertArrayEquals(plain,receive(encrypted,signingKey(a),peerRsa.getPrivate(),
                    IdsMessageProtectionIdentity.canonicalPublicHash(a),IdsMessageProtectionIdentity.canonicalPublicHash(peer)));
            assertThrows(GeneralSecurityException.class,() -> receive(encrypted,signingKey(local.get("C")),peerRsa.getPrivate(),
                    new byte[32],new byte[32]));
            restored.close();assertThrows(IllegalStateException.class,() -> restored.encryptClassA(plain,peer,new SecureRandom()));
            IdsMessageProtectionIdentity.wipeValues(local);
        }
    }
    @Test public void canonicalHashUsesRawSizePrefixedKeysRatherThanAsn1IdentityContainer() throws Exception {
        byte[] identity=IdsMessageProtectionIdentity.encodePublic(ec().getPublic(),rsa().getPublic());
        var seq=org.bouncycastle.asn1.ASN1Sequence.getInstance(identity);
        var digest=MessageDigest.getInstance("SHA-256");
        for(int i=0;i<2;i++) digest.update(org.bouncycastle.asn1.ASN1OctetString.getInstance(
                org.bouncycastle.asn1.ASN1TaggedObject.getInstance(seq.getObjectAt(i)),false).getOctets());
        assertArrayEquals(digest.digest(),IdsMessageProtectionIdentity.canonicalPublicHash(identity));
        assertFalse(Arrays.equals(MessageDigest.getInstance("SHA-256").digest(identity),
                IdsMessageProtectionIdentity.canonicalPublicHash(identity)));
    }
    @Test public void invalidProfileAndOversizedEncryptedOutputRefuseEmission() throws Exception {
        var sender=ec();var recipient=rsa();byte[] hash=new byte[32];
        for(byte[] plain:new byte[][]{new byte[0],new byte[NativeHealthSyncCodec.MAX_BYTES],null}) {
            assertThrows(GeneralSecurityException.class,() -> IdsAOverC.encrypt(plain,sender.getPrivate(),recipient.getPublic(),hash,hash,new SecureRandom()));
        }
        assertThrows(GeneralSecurityException.class,() -> IdsAOverC.encrypt(new byte[1],sender.getPrivate(),recipient.getPublic(),new byte[31],hash,new SecureRandom()));
        assertThrows(GeneralSecurityException.class,() -> IdsAOverC.encrypt(new byte[1],sender.getPrivate(),recipient.getPublic(),hash,hash,null));
    }
}
