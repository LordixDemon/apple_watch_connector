package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Exact native empty outgoing-profile Restore: ID1/priority0, sequence1/Finished2; no obliterated list. */
final class NativeHealthInitialRestoreCodec {
    private NativeHealthInitialRestoreCodec() { }
    static byte[] encode(HealthSyncStateStore.State state) {
        if(state.phase()!=HealthSyncStateStore.Phase.PREPARED) throw new IllegalArgumentException("Restore was not durably prepared");
        var restore=new ByteArrayOutputStream(); bytes(restore,1,uuid(state.session()));
        number(restore,2,state.sequence()); number(restore,3,2);
        bytes(restore,4,state.source().getBytes(StandardCharsets.UTF_8));
        var frame=new ByteArrayOutputStream();frame.write(1);frame.write(0);frame.write(0);
        number(frame,2,state.version()); bytes(frame,3,uuid(state.persistent()));bytes(frame,4,uuid(state.health()));
        byte[] record=restore.toByteArray();
        try { bytes(frame,9,record);return frame.toByteArray(); }
        finally { Arrays.fill(record,(byte)0); }
    }
    private static byte[] uuid(UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
    private static void bytes(ByteArrayOutputStream out,int field,byte[] value) {
        try { varint(out,(field<<3)|2);varint(out,value.length);out.writeBytes(value); }
        finally { Arrays.fill(value,(byte)0); }
    }
    private static void number(ByteArrayOutputStream out,int field,long value) { varint(out,field<<3);varint(out,value); }
    private static void varint(ByteArrayOutputStream out,long value) {
        while((value&~0x7fL)!=0) { out.write((int)value&0x7f|0x80);value>>>=7; }out.write((int)value);
    }
}
