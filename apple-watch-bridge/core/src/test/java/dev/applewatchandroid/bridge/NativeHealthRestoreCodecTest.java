package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class NativeHealthRestoreCodecTest {
    private static final String ID="00112233445566778899aabbccddeeff";
    private static final UUID UUID_VALUE=UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value.replace(" ","")); }
    private static NativeHealthChangesCodec.Node restore(String value) {
        return NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.ACTIVATION_RESTORE,hex(value));
    }
    @Test public void nativeTagsRetainFullWidthSequenceSourceAndObliteratedOrder() {
        // Exact native writer tags, not a Watch capture. Sequence 2^32 must not become zero.
        try(var node=restore("0a10"+ID+"108080808010180122056e65742e783210"+ID+"321000000000000000000000000000000000")) {
            var header=NativeHealthRestoreCodec.requireHeader(node);
            assertEquals(UUID_VALUE,header.identifier()); assertEquals(4294967296L,header.sequence());
            assertEquals(1,header.statusCode()); assertEquals(NativeHealthRestoreCodec.Status.START,header.status());
            assertEquals("net.x",node.string(4));
            assertEquals(List.of(UUID_VALUE,new UUID(0,0)),header.obliterated());
            assertThrows(UnsupportedOperationException.class,() -> header.obliterated().clear());
        }
    }
    @Test public void requiredPresenceDiffersFromNativeDefaultStartAndZeroSequence() {
        for(String value:new String[]{"", "0a10"+ID+"1000", "0a10"+ID+"1801", "10001801"}) {
            try(var node=restore(value)) {
                assertThrows(IllegalArgumentException.class,() -> NativeHealthRestoreCodec.requireHeader(node));
            }
        }
        try(var node=restore("0a10"+ID+"10001800")) {
            var header=NativeHealthRestoreCodec.requireHeader(node);
            assertEquals(0L,header.sequence()); assertEquals(NativeHealthRestoreCodec.Status.UNKNOWN,header.status());
        }
    }
    @Test public void statusesRemainDistinctAndUnknownIsNotFinished() {
        var meanings=List.of(NativeHealthRestoreCodec.Status.START,NativeHealthRestoreCodec.Status.FINISHED,
                NativeHealthRestoreCodec.Status.ABORT,NativeHealthRestoreCodec.Status.UNKNOWN);
        int[] codes={1,2,3,99};
        for(int i=0;i<codes.length;i++) try(var node=restore("0a10"+ID+"100018"+String.format("%02x",codes[i]))) {
            var header=NativeHealthRestoreCodec.requireHeader(node);
            assertEquals(codes[i],header.statusCode()); assertEquals(meanings.get(i),header.status());
        }
        try(var node=restore("0a10"+ID+"10ffffffffffffffffff0118ffffffffffffffffff01")) {
            var header=NativeHealthRestoreCodec.requireHeader(node);
            assertEquals(-1L,header.sequence()); assertEquals(-1,header.statusCode());
            assertEquals(NativeHealthRestoreCodec.Status.UNKNOWN,header.status());
        }
    }
    @Test public void malformedRequiredAndObliteratedUuidsAreRejected() {
        for(String value:new String[]{"0a0010001801", "0a010010001801", "0a10"+ID+"100018013200",
                "0a10"+ID+"10001801320100"}) {
            try(var node=restore(value)) {
                assertThrows(IllegalArgumentException.class,() -> NativeHealthRestoreCodec.requireHeader(node));
            }
        }
        assertThrows(IllegalArgumentException.class,() -> NativeHealthSyncCodec.uuid(new byte[17]));
        assertThrows(IllegalArgumentException.class,() -> NativeHealthSyncCodec.uuid(null));
    }
    @Test public void knownDuplicateWireUtf8AndOverflowFailuresAreAtomic() {
        for(String value:new String[]{"0a000a00", "10001001", "18011802", "22002200", "0801", "1200",
                "1a00", "2001", "3001", "2201ff", "1080", "188080808010"}) {
            assertThrows(value,IllegalArgumentException.class,() -> restore(value));
        }
    }
    @Test public void unknownFieldIsRetainedWithoutInventingRestoreMeaning() {
        byte[] input=hex("0a10"+ID+"100018022a020001");
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.ACTIVATION_RESTORE,input)) {
            assertEquals(1,node.unknownFields()); assertArrayEquals(input,node.original());
            assertEquals(NativeHealthRestoreCodec.Status.FINISHED,NativeHealthRestoreCodec.requireHeader(node).status());
        }
    }
    @Test public void identityRequiresTwoNativeHealthUuidsAndExplicitVersion() {
        try(var envelope=NativeHealthSyncCodec.decodeEnvelope(hex("10121a10"+ID+"221000000000000000000000000000000000"))) {
            var identity=envelope.requireIdentity(); assertEquals(18,identity.version());
            assertEquals(UUID_VALUE,identity.persistent()); assertEquals(new UUID(0,0),identity.health());
        }
        for(String value:new String[]{"1a10"+ID+"2210"+ID,"10121a10"+ID,"10121a002210"+ID}) {
            try(var envelope=NativeHealthSyncCodec.decodeEnvelope(hex(value))) {
                assertThrows(IllegalArgumentException.class,envelope::requireIdentity);
            }
        }
    }
    @Test public void envelopeOwnsRecordAndClosingRevokesDecodeWithoutMutatingCopies() {
        String record="0a10"+ID+"10001801";
        var envelope=NativeHealthSyncCodec.decodeEnvelope(hex("10121a10"+ID+"2210"+ID+"4a16"+record));
        byte[] copy=envelope.bytes(9); var node=envelope.restore();
        var header=NativeHealthRestoreCodec.requireHeader(node);
        envelope.close(); envelope.close(); assertArrayEquals(hex(record),copy);
        assertThrows(IllegalStateException.class,envelope::restore);
        assertThrows(IllegalStateException.class,envelope::requireIdentity);
        assertEquals(UUID_VALUE,header.identifier()); node.close();
        assertThrows(IllegalStateException.class,() -> NativeHealthRestoreCodec.requireHeader(node));
        try(var wrong=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.STATUS,new byte[0])) {
            assertThrows(IllegalArgumentException.class,() -> NativeHealthRestoreCodec.requireHeader(wrong));
        }
    }
    @Test public void repeatedObliteratedIdentitiesHaveBoundedWork() {
        var wire=new ByteArrayOutputStream(); wire.writeBytes(hex("0a10"+ID+"10001801"));
        for(int i=0;i<4096;i++) wire.writeBytes(hex("3210"+ID));
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.ACTIVATION_RESTORE,wire.toByteArray())) {
            assertEquals(4096,NativeHealthRestoreCodec.requireHeader(node).obliterated().size());
        }
        wire.writeBytes(hex("3210"+ID));
        assertThrows(IllegalArgumentException.class,() -> NativeHealthChangesCodec.decode(
                NativeHealthChangesCodec.Schema.ACTIVATION_RESTORE,wire.toByteArray()));
    }
}
