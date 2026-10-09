package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** HealthDaemon23S303 ActivationRestore schema. Reading this record never restores or deletes a store. */
final class NativeHealthRestoreCodec {
    enum Status { START, FINISHED, ABORT, UNKNOWN }
    record Header(UUID identifier, long sequence, int statusCode, Status status, List<UUID> obliterated) {
        Header { obliterated=List.copyOf(obliterated); }
    }
    private NativeHealthRestoreCodec() { }

    /** hasRequiredFields requires actual fields1/2/3; an absent native default is not a received Start. */
    static Header requireHeader(NativeHealthChangesCodec.Node node) {
        if(node==null || node.schema!=NativeHealthChangesCodec.Schema.ACTIVATION_RESTORE) {
            throw new IllegalArgumentException("Wrong Health Restore schema");
        }
        Long sequence=node.int64(2); Integer status=node.int32(3);
        if(sequence==null || status==null) throw new IllegalArgumentException("Missing Health Restore fields");
        UUID identifier=readUuid(node.bytes(1));
        var obliterated=new ArrayList<UUID>();
        for(int i=0;i<node.byteCount(6);i++) obliterated.add(readUuid(node.bytesAt(6,i)));
        Status meaning=switch(status) {
            case 1 -> Status.START;
            case 2 -> Status.FINISHED;
            case 3 -> Status.ABORT;
            default -> Status.UNKNOWN;
        };
        return new Header(identifier,sequence,status,meaning,obliterated);
    }

    private static UUID readUuid(byte[] value) {
        try { return NativeHealthSyncCodec.uuid(value); }
        finally { if(value!=null) Arrays.fill(value,(byte)0); }
    }
}
