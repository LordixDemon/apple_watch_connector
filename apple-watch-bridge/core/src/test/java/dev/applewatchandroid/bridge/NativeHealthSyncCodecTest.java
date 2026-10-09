package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class NativeHealthSyncCodecTest {
    @Test public void idsDataHeaderUsesLittleEndianIdAndContextSpecificOffset() {
        byte[] pb = {16, 12, 58, 0};
        try (var request = NativeHealthSyncCodec.decodePlaintext(new byte[]{2,0,2,16,12,58,0}, false);
             var response = NativeHealthSyncCodec.decodePlaintext(new byte[]{2,0,16,12,58,0}, true);
             var envelope = request.envelope()) {
            assertEquals(2, request.messageId); assertEquals(Integer.valueOf(2), request.priority);
            assertNull(response.priority); assertTrue(response.response);
            assertArrayEquals(pb, request.protobuf()); assertArrayEquals(pb, response.protobuf());
            assertEquals(Integer.valueOf(12), envelope.version);
            assertTrue(envelope.has(7)); assertEquals(0, envelope.size(7));
            assertFalse(envelope.has(8)); assertNull(envelope.bytes(8));
        }
    }
    @Test public void missingVersionAndIdentityAreAbsentAndDoNotCreateMeasurements() {
        try (var envelope = NativeHealthSyncCodec.decodeEnvelope(new byte[0])) {
            assertNull(envelope.version); assertNull(envelope.bytes(3)); assertEquals(-1, envelope.size(4));
        }
        assertEquals("Tinker Pairing", NativeHealthSyncCodec.messageName(10));
        assertEquals("Tinker Opt In", NativeHealthSyncCodec.messageName(11));
        try (var frame = NativeHealthSyncCodec.decodePlaintext(new byte[]{10,0,0}, false)) {
            assertThrows(IllegalArgumentException.class, frame::envelope);
        }
    }
    @Test public void nativeEnvelopePreservesEveryKnownOpaqueFieldAndSkipsExtensions() {
        byte[] pb = {26,2,1,2,34,1,3,58,1,4,66,1,5,74,1,6,82,1,7,90,1,8,
                (byte)160,6,1,(byte)169,6,1,2,3,4,5,6,7,8,(byte)178,6,2,9,10,
                (byte)189,6,1,2,3,4};
        try (var envelope = NativeHealthSyncCodec.decodeEnvelope(pb)) {
            assertArrayEquals(new byte[]{1,2}, envelope.bytes(3));
            for (int field = 7; field <= 11; field++) assertArrayEquals(new byte[]{(byte)(field-3)}, envelope.bytes(field));
            byte[] copy = envelope.bytes(3); copy[0]=99; assertEquals(1, envelope.bytes(3)[0]);
        }
    }
    @Test public void malformedDuplicateAndOversizedFramesFailWithoutPartialEnvelope() {
        for (byte[] pb : new byte[][]{{16,1,16,2},{26,0,26,0},{17,0},{0},{8,(byte)128},
                {26,100,1},{26,(byte)255,(byte)255,(byte)255,(byte)255,15},
                {26,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,2},
                {13,1,2},{11}}) {
            assertThrows(IllegalArgumentException.class, () -> NativeHealthSyncCodec.decodeEnvelope(pb));
        }
        assertThrows(IllegalArgumentException.class, () -> NativeHealthSyncCodec.decodePlaintext(new byte[]{2,0},false));
        assertThrows(IllegalArgumentException.class, () -> NativeHealthSyncCodec.decodeEnvelope(new byte[NativeHealthSyncCodec.MAX_BYTES+1]));
    }
    @Test public void fieldBudgetAndClosedOwnershipAreEnforced() {
        byte[] fields = new byte[16386]; Arrays.fill(fields,(byte)1);
        for(int i=0;i<fields.length;i+=2) fields[i]=8;
        assertThrows(IllegalArgumentException.class, () -> NativeHealthSyncCodec.decodeEnvelope(fields));
        var frame=NativeHealthSyncCodec.decodePlaintext(new byte[]{2,0},true); frame.close();
        assertThrows(IllegalStateException.class,frame::protobuf);
        var envelope=NativeHealthSyncCodec.decodeEnvelope(new byte[]{26,1,3}); envelope.close();
        assertThrows(IllegalStateException.class, () -> envelope.bytes(3));
    }
}
