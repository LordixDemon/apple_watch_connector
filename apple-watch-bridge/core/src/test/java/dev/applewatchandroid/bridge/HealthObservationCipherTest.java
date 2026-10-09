package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;

public final class HealthObservationCipherTest {
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static HealthObservationCipher cipher(int salt) {
        byte[] enc=new byte[32],index=new byte[32];Arrays.fill(enc,(byte)salt);Arrays.fill(index,(byte)(salt+1));
        return new HealthObservationCipher(new SecretKeySpec(enc,"AES"),new SecretKeySpec(index,"HmacSHA256"));
    }
    private static byte[] aad() { return HealthObservationCipher.aad("1".repeat(64),"QUANTITY","2".repeat(64),"3".repeat(64)); }
    @Test public void encryptedRowRoundTripsWithoutPlaintextAndRandomizesNonce() throws Exception {
        var cipher=cipher(1);byte[] body=bytes("original sample UUID/value/source/metadata");
        byte[] first=cipher.seal(body,aad()),second=cipher.seal(body,aad());
        assertFalse(Arrays.equals(first,second));assertArrayEquals(body,cipher.open(first,aad()));
        assertArrayEquals(body,cipher.open(second,aad()));
        assertFalse(new String(first,StandardCharsets.ISO_8859_1).contains("original sample"));
        assertEquals(body.length+29,first.length);assertEquals(1,first[0]);
    }
    @Test public void everyAuthenticatedIndexComponentRejectsRowRelocation() throws Exception {
        var cipher=cipher(1);byte[] sealed=cipher.seal(bytes("record"),aad());
        for(byte[] target:new byte[][]{
                HealthObservationCipher.aad("0".repeat(64),"QUANTITY","2".repeat(64),"3".repeat(64)),
                HealthObservationCipher.aad("1".repeat(64),"CATEGORY","2".repeat(64),"3".repeat(64)),
                HealthObservationCipher.aad("1".repeat(64),"QUANTITY","0".repeat(64),"3".repeat(64)),
                HealthObservationCipher.aad("1".repeat(64),"QUANTITY","2".repeat(64),"0".repeat(64))})
            assertThrows(GeneralSecurityException.class,()->cipher.open(sealed,target));
    }
    @Test public void everyCiphertextByteIsAuthenticatedIncludingNonce() throws Exception {
        var cipher=cipher(1);byte[] sealed=cipher.seal(bytes("record"),aad());
        for(int i=0;i<sealed.length;i++) {
            byte[] changed=sealed.clone();changed[i]^=1;
            assertThrows(GeneralSecurityException.class,()->cipher.open(changed,aad()));
        }
    }
    @Test public void wrongStorageKeyCannotReadExistingRows() throws Exception {
        byte[] sealed=cipher(1).seal(bytes("record"),aad());
        assertThrows(GeneralSecurityException.class,()->cipher(2).open(sealed,aad()));
    }
    @Test public void stableSecretTokensSeparateScopeAndObjectWithoutRawUuid() throws Exception {
        var cipher=cipher(1);
        String token=cipher.token(bytes("scope/QUANTITY"),bytes("00010203-0405-0607-0809-0a0b0c0d0e0f"));
        assertTrue(token.matches("[0-9a-f]{64}"));assertEquals(token,cipher.token(bytes("scope/QUANTITY"),bytes("00010203-0405-0607-0809-0a0b0c0d0e0f")));
        assertNotEquals(token,cipher.token(bytes("other/QUANTITY"),bytes("00010203-0405-0607-0809-0a0b0c0d0e0f")));
        assertNotEquals(token,cipher.token(bytes("scope/CATEGORY"),bytes("00010203-0405-0607-0809-0a0b0c0d0e0f")));
        assertNotEquals(token,cipher(2).token(bytes("scope/QUANTITY"),bytes("00010203-0405-0607-0809-0a0b0c0d0e0f")));
    }
    @Test public void lengthPrefixPreventsDomainContentConcatenationCollision() throws Exception {
        var cipher=cipher(1);
        assertNotEquals(cipher.token(bytes("a"),bytes("bc")),cipher.token(bytes("ab"),bytes("c")));
    }
    @Test public void protectedDefaultsHaveDifferentAuthenticatedNamespace() {
        assertFalse(Arrays.equals(HealthObservationCipher.aad("1".repeat(64),"DEFAULTS","2".repeat(64),"3".repeat(64)),
                HealthObservationCipher.aad("1".repeat(64),"PROTECTED_DEFAULTS","2".repeat(64),"3".repeat(64))));
    }
    @Test public void truncationExtensionVersionAndOversizeAreRefused() throws Exception {
        var cipher=cipher(1);byte[] sealed=cipher.seal(bytes("record"),aad());
        for(int length:new int[]{0,1,12,28,sealed.length-1,sealed.length+1}) {
            byte[] wrong=Arrays.copyOf(sealed,length);
            assertThrows(GeneralSecurityException.class,()->cipher.open(wrong,aad()));
        }
        assertThrows(IllegalArgumentException.class,()->cipher.seal(new byte[NativeHealthSyncCodec.MAX_BYTES+9],aad()));
        assertThrows(GeneralSecurityException.class,()->cipher.open(new byte[NativeHealthSyncCodec.MAX_BYTES+38],aad()));
    }
    @Test public void invalidIndexOrKindCannotBecomeAuthenticatedAssociatedData() {
        for(String scope:new String[]{"","1".repeat(63),"A".repeat(64),"1\n".repeat(32)})
            assertThrows(IllegalArgumentException.class,()->HealthObservationCipher.aad(scope,"QUANTITY","2".repeat(64),"3".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->HealthObservationCipher.aad("1".repeat(64),"QUANTITY\n","2".repeat(64),"3".repeat(64)));
    }
    @Test public void maximalNativePayloadIsSupportedAndOwnedByCaller() throws Exception {
        var cipher=cipher(1);byte[] body=new byte[NativeHealthSyncCodec.MAX_BYTES+8];Arrays.fill(body,(byte)0x92);
        byte[] sealed=cipher.seal(body,aad());assertArrayEquals(body,cipher.open(sealed,aad()));
        body[0]=0;assertEquals((byte)0x92,cipher.open(sealed,aad())[0]);
    }
}
