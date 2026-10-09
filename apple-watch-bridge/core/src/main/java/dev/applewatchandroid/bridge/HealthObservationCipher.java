package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local encrypted observations, separate from IDS/pairing keys and native acceptance. */
final class HealthObservationCipher {
    private final SecretKey encryption,index;
    HealthObservationCipher(SecretKey encryption,SecretKey index) {
        if(encryption==null || index==null)throw new IllegalArgumentException("Missing Health storage keys");
        this.encryption=encryption;this.index=index;
    }
    String token(byte[] domain,byte[] content) throws GeneralSecurityException {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(index);
        mac.update(ByteBuffer.allocate(4).putInt(domain.length).array());mac.update(domain);mac.update(content);
        byte[] hash=mac.doFinal();
        try {
            StringBuilder out=new StringBuilder(64);
            for(byte b:hash)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return out.toString();
        } finally { Arrays.fill(hash,(byte)0); }
    }
    static byte[] aad(String scope,String kind,String object,String variant) {
        for(String token:new String[]{scope,object,variant})
            if(token==null || !token.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid Health observation index");
        if(kind==null || !kind.matches("SOURCE|DEVICE|DEFAULTS|PROTECTED_DEFAULTS|CATEGORY|QUANTITY|DELETED"))
            throw new IllegalArgumentException("Invalid Health observation kind");
        return ("AWHealthObserved1\n"+scope+"\n"+kind+"\n"+object+"\n"+variant).getBytes(StandardCharsets.US_ASCII);
    }
    byte[] seal(byte[] plaintext,byte[] aad) throws GeneralSecurityException {
        if(plaintext==null || plaintext.length>NativeHealthSyncCodec.MAX_BYTES+8)throw new IllegalArgumentException("Health observation size");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,encryption);
        cipher.updateAAD(aad);byte[] ciphertext=cipher.doFinal(plaintext),iv=cipher.getIV();
        try {
            if(iv.length!=12)throw new GeneralSecurityException("Health storage nonce size");
            return ByteBuffer.allocate(1+12+ciphertext.length).put((byte)1).put(iv).put(ciphertext).array();
        } finally { Arrays.fill(ciphertext,(byte)0);Arrays.fill(iv,(byte)0); }
    }
    byte[] open(byte[] sealed,byte[] aad) throws GeneralSecurityException {
        if(sealed==null || sealed.length<29 || sealed.length>NativeHealthSyncCodec.MAX_BYTES+37 || sealed[0]!=1)
            throw new GeneralSecurityException("Invalid Health observation ciphertext");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,encryption,new GCMParameterSpec(128,sealed,1,12));cipher.updateAAD(aad);
        return cipher.doFinal(sealed,13,sealed.length-13);
    }
}
