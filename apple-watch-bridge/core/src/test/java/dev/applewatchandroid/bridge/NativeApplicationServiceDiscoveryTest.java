package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;

public final class NativeApplicationServiceDiscoveryTest {
    private static final UUID TOKEN = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f");
    private static final byte[] KEY = {1, 2, 3, 4}; // Opaque structural fixture, not TLS material.

    @Test public void emitsNativeNestedRequestShapeAndCanonicalToken() {
        byte[] request = NativeApplicationServiceDiscovery.request("test", TOKEN, KEY);
        assertArrayEquals(new byte[]{6,0,33,5,0,4,'t','e','s','t',7,0,4,1,2,3,4,9,0,16,
                0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15}, request);
        assertThrows(IllegalArgumentException.class,
                () -> NativeApplicationServiceDiscovery.request("test\0name", TOKEN, KEY));
    }

    @Test public void parsesDarwinIpv6FamilyPortAndDefensivelyOwnsKeyAndAddress() {
        byte[] message = response(new byte[]{30,(byte)0xd4,0x31,0x20,1,0x0d,(byte)0xb8,0,0,0,0,0,0,0,0,0,0,0,1});
        var result = NativeApplicationServiceDiscovery.responses(message);
        assertEquals(1, result.size());
        var endpoint = result.get(0);
        assertEquals("test", endpoint.service);
        assertEquals(TOKEN, endpoint.token);
        assertEquals(54321, endpoint.port);
        assertEquals(16, endpoint.address().length);
        assertArrayEquals(KEY, endpoint.publicKeys().get(0));
        endpoint.publicKeys().get(0)[0] = 99;
        endpoint.address()[0] = 0;
        java.util.Arrays.fill(message, (byte)0);
        assertArrayEquals(KEY, endpoint.publicKeys().get(0));
        assertEquals(0x20, endpoint.address()[0]);
    }

    @Test public void rejectsTruncationLengthFamilyAndPortButIgnoresUnrelatedMessages() {
        byte[] message = response(new byte[]{2,1,2,(byte)192,0,2,1});
        assertEquals(4, NativeApplicationServiceDiscovery.responses(message).get(0).address().length);
        for (int length = 0; length < message.length; length++) {
            byte[] truncated = java.util.Arrays.copyOf(message, length);
            assertThrows(IllegalArgumentException.class, () -> NativeApplicationServiceDiscovery.responses(truncated));
        }
        message[16] = 20;
        assertTrue(NativeApplicationServiceDiscovery.responses(message).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> NativeApplicationServiceDiscovery.responses(response(new byte[]{10,1,2,0,0,0,1})));
        assertThrows(IllegalArgumentException.class, () -> NativeApplicationServiceDiscovery.responses(response(new byte[]{2,0,0,0,0,0,1})));
    }

    @Test public void nativeOptionsIdentityIsIndependentRsa2048AndDiscoveryNeverAcceptsIkeReceiptAsEndpoint() throws Exception {
        var discovery = NativeApplicationServiceDiscovery.createSnapshotDiscovery();
        assertTrue(discovery.request().length < 1200);
        assertTrue(discovery.acceptAuthenticated(LinkDirectorMessageCodec.encode(1,1)).isEmpty());
        assertTrue(discovery.acceptAuthenticated(response(new byte[]{2,1,2,(byte)192,0,2,1})).isEmpty());
    }

    private static byte[] response(byte[] address) {
        byte[] request = NativeApplicationServiceDiscovery.request("test", TOKEN, KEY);
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        record.write(request,3,request.length-3);
        record.write(2); record.write(0); record.write(address.length); record.write(address,0,address.length);
        ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
        wrapped.write(6); wrapped.write(0); wrapped.write(record.size());
        wrapped.write(record.toByteArray(),0,record.size());
        byte[] envelope = ByteBuffer.allocate(19 + wrapped.size()).put((byte)2).put((byte)0)
                .putShort((short)(wrapped.size()+3)).putInt(0).putLong(1).put((byte)21)
                .putShort((short)wrapped.size()).put(wrapped.toByteArray()).array();
        return envelope;
    }
}
