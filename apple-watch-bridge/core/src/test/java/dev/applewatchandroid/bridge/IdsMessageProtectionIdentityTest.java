package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;

public final class IdsMessageProtectionIdentityTest {
    @Test
    public void threeDistinctNativePublicIdentitiesSurviveAuthenticatedStorage() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256);
        SecretKey key = generator.generateKey();
        try (IdsMessageProtectionIdentity original = IdsMessageProtectionIdentity.generate(new SecureRandom())) {
            byte[] publicBundle = original.publicBundle();
            byte[] sealed = original.seal(key);
            Map<String, byte[]> publicKeys = IdsMessageProtectionIdentity.parsePublicBundle(publicBundle);
            try {
                assertFalse(Arrays.equals(publicKeys.get("A"), publicKeys.get("C")));
                assertFalse(Arrays.equals(publicKeys.get("C"), publicKeys.get("D")));
                for (byte[] publicKey : publicKeys.values()) {
                    assertEquals(0x30, publicKey[0] & 255);
                    IdsMessageProtectionIdentity.validatePublic(publicKey);
                }
                try (IdsMessageProtectionIdentity restored = IdsMessageProtectionIdentity.open(sealed, key)) {
                    assertArrayEquals(publicBundle, restored.publicBundle());
                }
                sealed[sealed.length - 1] ^= 1;
                assertThrows(javax.crypto.AEADBadTagException.class,
                        () -> IdsMessageProtectionIdentity.open(sealed, key));
            } finally {
                IdsMessageProtectionIdentity.wipeValues(publicKeys);
                Arrays.fill(publicBundle, (byte) 0); Arrays.fill(sealed, (byte) 0);
            }
        }
    }

    @Test
    public void malformedPublicBundleNeverBecomesDeviceInfo() {
        assertThrows(IllegalArgumentException.class, () -> IdsMessageProtectionIdentity.parsePublicBundle(
                AppleBinaryPropertyList.encode(Map.of("version", 1L, "A", new byte[65], "C", new byte[65], "D", new byte[65]))));
        assertThrows(IllegalArgumentException.class, () -> IdsMessageProtectionIdentity.validatePublic(new byte[5000]));
    }

    @Test
    public void installationIdentifierTravelsWithTheSameThreePublicKeys() {
        try (var identity = IdsMessageProtectionIdentity.generate(new SecureRandom())) {
            byte[] original = identity.publicBundle();
            String uuid = "aabbccdd-1122-4333-8444-556677889900";
            byte[] bound = IdsMessageProtectionIdentity.bindPublicBundle(original, uuid);
            Map<String, byte[]> before = IdsMessageProtectionIdentity.parsePublicBundle(original);
            Map<String, byte[]> after = IdsMessageProtectionIdentity.parsePublicBundle(bound);
            try {
                assertEquals(uuid, IdsMessageProtectionIdentity.publicBundleIdentifier(bound));
                for (String name : new String[]{"A", "C", "D"}) assertArrayEquals(before.get(name), after.get(name));
                assertThrows(IllegalArgumentException.class,
                        () -> IdsMessageProtectionIdentity.publicBundleIdentifier(original));
            } finally {
                IdsMessageProtectionIdentity.wipeValues(before); IdsMessageProtectionIdentity.wipeValues(after);
                Arrays.fill(original, (byte)0); Arrays.fill(bound, (byte)0);
            }
        }
    }
}
