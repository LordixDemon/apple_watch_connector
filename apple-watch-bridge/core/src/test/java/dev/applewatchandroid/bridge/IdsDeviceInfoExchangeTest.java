package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.security.SecureRandom;
import java.util.Map;
import java.util.LinkedHashMap;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public final class IdsDeviceInfoExchangeTest {
    private static final String PHONE = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
    private static final String WATCH = "11111111-2222-4333-8444-555555555555";
    private static final String D = "idstest/localdelivery/UTunDelivery-Default-Urgent-D";
    private static final String C = "idstest/localdelivery/UTunDelivery-Default-Urgent-C";
    private static byte[] phoneKeys;
    private static byte[] watchKeys;

    @BeforeClass public static void keys() {
        try (IdsMessageProtectionIdentity phone = IdsMessageProtectionIdentity.generate(new SecureRandom());
             IdsMessageProtectionIdentity watch = IdsMessageProtectionIdentity.generate(new SecureRandom())) {
            phoneKeys = phone.publicBundle(); watchKeys = watch.publicBundle();
        }
    }
    @AfterClass public static void clean() { java.util.Arrays.fill(phoneKeys, (byte) 0); java.util.Arrays.fill(watchKeys, (byte) 0); }

    @Test
    public void mutualDeviceInfoNeedsPeerKeysAndOurOwnReplyDelivery() {
        try (IdsDeviceInfoExchange phone = new IdsDeviceInfoExchange(phoneKeys, PHONE, WATCH, false);
             IdsDeviceInfoExchange watch = new IdsDeviceInfoExchange(watchKeys, WATCH, PHONE, false)) {
            byte[] phoneRequest = phone.begin();
            byte[] watchRequest = watch.begin();
            assertNull(phone.begin());
            byte[] phoneReply = phone.accept(watchRequest);
            byte[] watchReply = watch.accept(phoneRequest);
            Map<?, ?> message = (Map<?, ?>) AppleBinaryPropertyList.decode(phoneReply);
            assertEquals(12L, message.get("command"));
            assertEquals(((Map<?, ?>) AppleBinaryPropertyList.decode(watchRequest)).get("unique-id"), message.get("unique-id"));
            assertEquals(Boolean.TRUE, message.get("success"));
            Map<String, String> expectedPrivateData = new LinkedHashMap<>();
            expectedPrivateData.put("v", "1");
            expectedPrivateData.put("pn", "iPhone OS");
            expectedPrivateData.put("pv", "26.6");
            expectedPrivateData.put("pb", "23G71");
            expectedPrivateData.put("ap", "1");
            expectedPrivateData.put("h", "1");
            expectedPrivateData.put("t", "1");
            expectedPrivateData.put("s", "1");
            expectedPrivateData.put("m", "1");
            expectedPrivateData.put("p", "1");
            expectedPrivateData.put("c", "1");
            expectedPrivateData.put("ec", "8");
            expectedPrivateData.put("u", PHONE.toUpperCase(java.util.Locale.ROOT));
            assertEquals(expectedPrivateData,
                    message.get("private-device-data"));
            assertEquals(PHONE.toUpperCase(java.util.Locale.ROOT), message.get("identifier"));
            phone.onResponseQueued(7); watch.onResponseQueued(9);
            phone.accept(watchReply); watch.accept(phoneReply);
            assertFalse(phone.ready());
            // Sender verification is available before delivery of our own response.
            assertTrue(phone.hasVerifiedPeerIdentity());
            assertEquals(WATCH.toUpperCase(java.util.Locale.ROOT),phone.verifiedPeerIdentifier());
            Map<String,byte[]> received=IdsMessageProtectionIdentity.parsePublicBundle(watchKeys);
            try { assertArrayEquals(received.get("A"),phone.peerClassAPublicKey()); }
            finally { IdsMessageProtectionIdentity.wipeValues(received); }
            assertFalse(phone.onAcknowledgement(C, 7));
            assertFalse(phone.onAcknowledgement(D, 8));
            assertTrue(phone.onAcknowledgement(D, 7));
            assertTrue(phone.ready());
            assertTrue(watch.onAcknowledgement(D, 9));
            assertTrue(watch.ready());
        }
    }

    @Test
    public void reconnectStillRequiresFreshPeerInfoAndRejectsWrongIdentity() {
        try (IdsDeviceInfoExchange phone = new IdsDeviceInfoExchange(phoneKeys, PHONE, WATCH, true);
             IdsDeviceInfoExchange watch = new IdsDeviceInfoExchange(watchKeys, WATCH, PHONE, false)) {
            assertFalse(phone.ready());
            assertFalse(phone.hasVerifiedPeerIdentity());
            assertThrows(IllegalStateException.class,phone::peerClassAPublicKey);
            byte[] request = phone.begin();
            Map<?, ?> decoded = (Map<?, ?>) AppleBinaryPropertyList.decode(watch.accept(request));
            Map<String, Object> changed = new LinkedHashMap<>();
            decoded.forEach((k, v) -> changed.put((String) k, v));
            changed.put("unique-id", "66666666-7777-4888-8999-aaaaaaaaaaaa");
            phone.accept(AppleBinaryPropertyList.encode(changed));
            assertFalse(phone.ready());
            changed.put("unique-id", decoded.get("unique-id"));
            changed.put("identifier", "66666666-7777-4888-8999-aaaaaaaaaaaa");
            assertThrows(IllegalArgumentException.class, () -> phone.accept(AppleBinaryPropertyList.encode(changed)));
            assertFalse(phone.ready());
            phone.accept(watch.accept(request));
            assertTrue(phone.ready());
        }
    }
}
