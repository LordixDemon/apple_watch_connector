package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** Native final empty outgoing session + ChangesRequested4, with no acknowledged anchors. */
final class NativeHealthInitialChangesCodec {
    private NativeHealthInitialChangesCodec() { }
    /** A Watch response may include its child sync identity; read-only status inspection never adopts it. */
    static boolean statusOnly(NativeHealthSyncCodec.Envelope envelope) {
        return envelope.has(8) && !envelope.has(7) && !envelope.has(9);
    }
    static byte[] encode(HealthChangesSessionStore.State state) {
        if(state.phase()!=HealthChangesSessionStore.Phase.PREPARED)throw new IllegalArgumentException("Changes not durably prepared");
        var changes=new ByteArrayOutputStream();bytes(changes,2,uuid(state.session()));
        varint(changes,(3<<3)|1);long bits=Double.doubleToRawLongBits(state.startDate());
        for(int i=0;i<8;i++)changes.write((int)(bits>>>(8*i))&255);
        number(changes,5,2); // Native ChangeSet.Finished, not completion of incoming data.
        var status=new ByteArrayOutputStream();number(status,1,4); // ChangesRequested, no anchors fabricated.
        var frame=new ByteArrayOutputStream();frame.write(2);frame.write(0);frame.write(0);
        number(frame,2,state.identity().version());bytes(frame,3,uuid(state.identity().persistent()));bytes(frame,4,uuid(state.identity().health()));
        bytes(frame,7,changes.toByteArray());bytes(frame,8,status.toByteArray());return frame.toByteArray();
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
