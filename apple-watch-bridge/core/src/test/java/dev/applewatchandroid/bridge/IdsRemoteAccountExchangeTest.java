package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class IdsRemoteAccountExchangeTest {
    private static final String REQUEST = "00112233-4455-6677-8899-aabbccddeeff";

    @Test public void spsReceiptIsPositiveOnlyAfterTheReceiverPersistsIt() throws Exception {
        byte[] request = AppleBinaryPropertyList.encode(Map.of("command",15L,"unique-id",REQUEST,
                "sync-payload",Map.of("command",5L,"sps-device-udid",IdsSpsCompanionInfoTest.UDID,"sps-phone-numbers",List.of())));
        java.util.List<byte[]> persisted = new java.util.ArrayList<>();
        try (var reply = IdsRemoteAccountExchange.accept(request, info -> persisted.add(info.serialize()))) {
            assertEquals(1,persisted.size());
            try (var parsed = IdsSpsCompanionInfo.parse(persisted.get(0))) {
                assertTrue(parsed.summary().contains("phoneNumberCount=0"));
            }
            assertEquals(Map.of("command",16L,"unique-id",REQUEST,"success",true), AppleBinaryPropertyList.decode(reply.payload));
            assertTrue(reply.summary.contains("receipt acknowledged"));
            assertFalse(reply.summary.contains(IdsSpsCompanionInfoTest.UDID));
        }
        assertThrows(java.io.IOException.class, () -> IdsRemoteAccountExchange.accept(request,
                info -> { throw new java.io.IOException("simulated storage failure"); }));
        // With no receiver, accepting the same RPC still cannot claim success.
        try (var reply = IdsRemoteAccountExchange.accept(request)) {
            assertEquals(false, ((Map<?, ?>)AppleBinaryPropertyList.decode(reply.payload)).get("success"));
        }
    }

    @Test public void fourServiceFetchReceivesCorrelatedEmptyAccountArrays() {
        var services = List.of("com.apple.madrid", "com.apple.ess", "com.apple.private.alloy.fixture", "com.apple.private.alloy.test");
        byte[] request = AppleBinaryPropertyList.encode(Map.of("command", 17L,
                "unique-id", REQUEST, "serviceTypes", services));
        byte[] returned;
        try (var reply = IdsRemoteAccountExchange.accept(request)) {
            assertEquals(18, reply.command);
            returned = reply.payload;
            var data = (Map<?, ?>) AppleBinaryPropertyList.decode(returned);
            assertEquals(18L, data.get("command"));
            assertEquals(REQUEST, data.get("unique-id"));
            assertEquals(3, data.size());
            assertFalse(data.containsKey("success"));
            var map = (Map<?, ?>) data.get("accountMap");
            assertEquals(4, map.size());
            services.forEach(service -> assertEquals(List.of(), map.get(service)));
        }
        assertArrayEquals(new byte[returned.length], returned);
        try (var duplicate = IdsRemoteAccountExchange.accept(request)) {
            assertEquals(18, duplicate.command);
        }
    }

    @Test public void accountSyncDoesNotInventSuccessfulProvisioning() {
        byte[] request = AppleBinaryPropertyList.encode(Map.of("command", 15L, "unique-id", REQUEST,
                "sync-payload", Map.of("command", 1L, "account-info", List.of(), "service", "com.apple.madrid")));
        try (var reply = IdsRemoteAccountExchange.accept(request)) {
            var data = (Map<?, ?>) AppleBinaryPropertyList.decode(reply.payload);
            assertEquals(Map.of("command", 16L, "unique-id", REQUEST, "success", false), data);
            assertTrue(reply.summary.contains("unsupported"));
        }
    }

    @Test public void unrelatedAndMalformedRequestsDoNotGenerateFabricatedReplies() {
        for (long command : new long[]{11, 12, 16, 18, 99}) {
            assertNull(IdsRemoteAccountExchange.accept(AppleBinaryPropertyList.encode(Map.of("command", command))));
        }
        for (Object services : List.of("not-an-array", List.of(17L), List.of("invalid topic"), java.util.Collections.nCopies(65, "com.apple.madrid"))) {
            assertThrows(IllegalArgumentException.class, () -> IdsRemoteAccountExchange.accept(
                    AppleBinaryPropertyList.encode(Map.of("command", 17L, "unique-id", REQUEST, "serviceTypes", services))));
        }
        assertThrows(IllegalArgumentException.class, () -> IdsRemoteAccountExchange.accept(
                AppleBinaryPropertyList.encode(Map.of("command", 17L, "unique-id", "1-2-3-4-5", "serviceTypes", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> IdsRemoteAccountExchange.accept(new byte[32769]));
        assertThrows(IllegalArgumentException.class, () -> IdsRemoteAccountExchange.accept(
                AppleBinaryPropertyList.encode(Map.of("command", 15L, "unique-id", REQUEST))));
    }
}
