package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;
import org.junit.Test;

public final class NativeHealthAuthorizationCodecTest {
    private static byte[] hex(String text) { return HexFormat.of().parseHex(text.replace(" ","")); }
    private static NativeHealthChangesCodec.Node request(String text) {
        return NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.AUTHORIZATION_REQUEST,hex(text));
    }
    @Test public void requestHasApplicationCorrelationAndFullWidthTypeCodes() {
        // Exact native tags:1 app,2 request UUID bytes,10 read int64s,11 write int64s.
        try(var node=request("0a056e65742e78 12020001 508080808010 50ffffffffffffffffff01 5800")) {
            assertEquals("net.x",node.string(1)); assertArrayEquals(hex("0001"),node.bytes(2));
            assertEquals(List.of(4294967296L,-1L),node.int64s(10)); assertEquals(List.of(0L),node.int64s(11));
            assertThrows(UnsupportedOperationException.class,() -> node.int64s(10).clear());
            assertThrows(IllegalArgumentException.class,() -> node.int32(10));
        }
    }
    @Test public void nativeReaderAcceptsPackedUnpackedAndMixedOrder() {
        try(var node=request("5001 5206808080801002 5003 5a020004 5805")) {
            assertEquals(List.of(1L,4294967296L,2L,3L),node.int64s(10));
            assertEquals(List.of(0L,4L,5L),node.int64s(11));
        }
        try(var node=request("5200")) { assertTrue(node.int64s(10).isEmpty()); }
    }
    @Test public void responsePromptIsNotAHealthPermissionGrant() {
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.AUTHORIZATION_RESPONSE,
                hex("0a056e65742e78 120100 5000 5a04486f7374 6200"))) {
            assertEquals(Boolean.FALSE,node.bool(10)); assertEquals("Host",node.string(11));
            assertEquals("",node.string(12));
        }
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.AUTHORIZATION_RESPONSE,new byte[0])) {
            assertNull(node.bool(10)); assertNull(node.string(11)); assertNull(node.string(12));
        }
    }
    @Test public void idsHeaderAndResponseContextSelectDistinctAuthorizationSchemas() {
        try(var incoming=NativeHealthSyncCodec.decodePlaintext(hex("0300020a056e65742e785001"),false);
            var request=incoming.authorization();
            var reply=NativeHealthSyncCodec.decodePlaintext(hex("03005001"),true);
            var response=reply.authorization();
            var done=NativeHealthSyncCodec.decodePlaintext(hex("0400005200"),false);
            var complete=done.authorization()) {
            assertEquals(NativeHealthChangesCodec.Schema.AUTHORIZATION_REQUEST,request.schema);
            assertEquals(List.of(1L),request.int64s(10)); assertEquals(Boolean.TRUE,response.bool(10));
            assertEquals(NativeHealthChangesCodec.Schema.AUTHORIZATION_COMPLETE,complete.schema);
            assertEquals("",complete.string(10));
        }
        try(var unsupported=NativeHealthSyncCodec.decodePlaintext(hex("0400"),true)) {
            assertThrows(IllegalArgumentException.class,unsupported::authorization);
        }
    }
    @Test public void invalidPackedIntegersAndKnownFieldsFailWithoutPartialRecords() {
        for(String text:new String[]{"520180","520affffffffffffffffff02","510000000000000000",
                "52050102","0a01ff","0a000a00","12001200","00"}) {
            assertThrows(text,IllegalArgumentException.class,() -> request(text));
        }
        for(String text:new String[]{"5002","50015000","5a01ff"}) {
            assertThrows(IllegalArgumentException.class,() -> NativeHealthChangesCodec.decode(
                    NativeHealthChangesCodec.Schema.AUTHORIZATION_RESPONSE,hex(text)));
        }
    }
    @Test public void repeatedPackedCountsAndGlobalBudgetCannotBeEvaded() {
        ByteArrayOutputStream wire=new ByteArrayOutputStream();
        wire.writeBytes(hex("52ff1f")); for(int i=0;i<4095;i++) wire.write(1);
        wire.writeBytes(hex("5aff1f")); for(int i=0;i<4095;i++) wire.write(1);
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.AUTHORIZATION_REQUEST,wire.toByteArray())) {
            assertEquals(4095,node.int64s(10).size()); assertEquals(4095,node.int64s(11).size());
        }
        wire.writeBytes(hex("a00101"));
        assertThrows(IllegalArgumentException.class,() -> NativeHealthChangesCodec.decode(
                NativeHealthChangesCodec.Schema.AUTHORIZATION_REQUEST,wire.toByteArray()));
        wire.reset(); wire.writeBytes(hex("528120")); for(int i=0;i<4097;i++) wire.write(1);
        assertThrows(IllegalArgumentException.class,() -> NativeHealthChangesCodec.decode(
                NativeHealthChangesCodec.Schema.AUTHORIZATION_REQUEST,wire.toByteArray()));
    }
    @Test public void closeRevokesApplicationAndTypeAccessWithoutChangingCallerCopies() {
        var node=request("0a056e65742e78 120101 5001"); byte[] id=node.bytes(2); var types=node.int64s(10);
        node.close(); node.close(); assertArrayEquals(hex("01"),id); assertEquals(List.of(1L),types);
        assertThrows(IllegalStateException.class,() -> node.string(1));
        assertThrows(IllegalStateException.class,() -> node.int64s(10));
    }
}
