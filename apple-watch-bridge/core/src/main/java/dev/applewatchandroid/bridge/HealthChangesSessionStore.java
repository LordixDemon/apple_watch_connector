package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;

/** One durable initial Changes pull. Reported anchors are observations, never database commits. */
final class HealthChangesSessionStore {
    private static final Object LOCK=new Object();
    private static final int MAGIC=0x41574843, MAX_FILE=512;
    enum Phase { PREPARED, IDS_QUEUED, REMOTE_STATUS, UNKNOWN, EXPIRED }
    record State(UUID pair, UUID local, UUID peer, NativeHealthSyncCodec.Identity identity,
                 UUID session, UUID message, UUID epoch, double startDate, long deadline,
                 Phase phase, Integer remoteCode, int reportedAnchors) {
        State {
            if(pair==null || local==null || peer==null || local.equals(peer) || identity==null
                    || identity.version()!=17 || identity.persistent()==null || identity.health()==null
                    || session==null || message==null || message.version()!=4 || message.variant()!=2 || epoch==null
                    || !Double.isFinite(startDate) || startDate<=0 || deadline<=0 || phase==null
                    || reportedAnchors<0 || reportedAnchors>4096
                    || (phase==Phase.REMOTE_STATUS ? remoteCode==null || remoteCode<0 || remoteCode>6
                        : remoteCode!=null || reportedAnchors!=0)) throw new IllegalArgumentException("Invalid Health Changes state");
        }
        boolean pending() { return phase==Phase.PREPARED || phase==Phase.IDS_QUEUED; }
        State phase(Phase next) { return new State(pair,local,peer,identity,session,message,epoch,startDate,deadline,next,null,0); }
    }
    private final Path directory;
    HealthChangesSessionStore(Path directory) { this.directory=directory; }
    /** The first accepted Restore profile remains the sole source of store identities. No automatic repeat. */
    State prepare(HealthSyncStateStore.State restored, UUID epoch, UUID session, UUID message,
                  double startDate, long deadline) throws IOException {
        synchronized(LOCK) {
            if(restored.phase()!=HealthSyncStateStore.Phase.REMOTE_FINISHED) throw new IllegalStateException("Restore not accepted");
            if(read(restored.pair(),restored.local(),restored.peer(),restored.identity())!=null) {
                throw new IllegalStateException("Initial Changes pull already recorded");
            }
            State state=new State(restored.pair(),restored.local(),restored.peer(),restored.identity(),session,message,
                    epoch,startDate,deadline,Phase.PREPARED,null,0);
            save(state);return state;
        }
    }
    State receipt(HealthSyncStateStore.State profile, BridgeCommandCodec.Status receipt) throws IOException {
        synchronized(LOCK) {
            State state=read(profile.pair(),profile.local(),profile.peer(),profile.identity());
            if(state==null || !state.pending() || !state.epoch.equals(receipt.epoch()) || !state.message.equals(receipt.id()))return state;
            Phase next=switch(receipt.stage()) {
                case IDS_QUEUED -> Phase.IDS_QUEUED;
                case EXPIRED -> Phase.EXPIRED;
                case FAILED,REJECTED,UNKNOWN -> Phase.UNKNOWN;
                default -> state.phase; // ACK proves no native status or accepted Health data.
            };
            return transition(state,next);
        }
    }
    State response(HealthSyncStateStore.State profile, UUID epoch, UUID responseTo, long now,
                   NativeHealthSyncCodec.Identity identity, NativeHealthChangesCodec.Node status) throws IOException {
        synchronized(LOCK) {
            State state=read(profile.pair(),profile.local(),profile.peer(),profile.identity());
            if(state==null || !state.pending() || !state.epoch.equals(epoch) || !state.message.equals(responseTo)
                    || !state.identity.equals(identity))return state;
            if(now<0)throw new IllegalArgumentException("Invalid elapsed time");
            if(now>=state.deadline)return transition(state,Phase.EXPIRED);
            if(status==null || status.schema!=NativeHealthChangesCodec.Schema.STATUS || status.unknownFields()!=0)return state;
            Integer code=status.int32(1);if(code==null || code<0 || code>6)return state;
            State received=new State(state.pair,state.local,state.peer,state.identity,state.session,state.message,state.epoch,
                    state.startDate,state.deadline,Phase.REMOTE_STATUS,code,status.children(2).size());
            save(received);return received; // Includes Resend/Reactivate/Obliterate as observations only, no effect.
        }
    }
    State end(HealthSyncStateStore.State profile, UUID epoch, UUID message, long now, boolean disconnected) throws IOException {
        synchronized(LOCK) {
            State state=read(profile.pair(),profile.local(),profile.peer(),profile.identity());
            if(state==null || !state.pending() || !state.epoch.equals(epoch)
                    || message!=null && !state.message.equals(message) || !disconnected && now<state.deadline)return state;
            return transition(state,disconnected ? Phase.UNKNOWN : Phase.EXPIRED);
        }
    }
    private State transition(State state,Phase next) throws IOException {
        if(state.phase==next)return state;State changed=state.phase(next);save(changed);return changed;
    }
    State read(UUID pair,UUID local,UUID peer,NativeHealthSyncCodec.Identity identity) throws IOException {
        synchronized(LOCK) {
            Path file=directory.resolve(pair+".changes");
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>MAX_FILE)throw new IOException("Invalid Changes file");
            byte[] data=Files.readAllBytes(file),body=null,digest=null;
            try {
                if(data.length<32)throw new IOException("Truncated Changes state");
                body=Arrays.copyOf(data,data.length-32);digest=sha(body);
                if(!MessageDigest.isEqual(digest,Arrays.copyOfRange(data,body.length,data.length)))throw new IOException("Changes checksum");
                var in=new DataInputStream(new ByteArrayInputStream(body));
                if(in.readInt()!=MAGIC || in.readUnsignedByte()!=1)throw new IOException("Changes format");
                UUID owner=uuid(in),sender=uuid(in),receiver=uuid(in);
                var nativeIdentity=new NativeHealthSyncCodec.Identity(in.readInt(),uuid(in),uuid(in));
                UUID session=uuid(in),message=uuid(in),epoch=uuid(in);
                double startDate=in.readDouble();long deadline=in.readLong();int phase=in.readUnsignedByte();
                if(phase>=Phase.values().length)throw new IOException("Changes phase");
                Integer code=in.readBoolean() ? in.readInt() : null;int count=in.readInt();
                State state=new State(owner,sender,receiver,nativeIdentity,session,message,epoch,startDate,deadline,Phase.values()[phase],code,count);
                if(in.available()!=0 || !owner.equals(pair) || !sender.equals(local) || !receiver.equals(peer)
                        || !nativeIdentity.equals(identity))throw new IOException("Changes ownership");
                return state;
            } catch(IllegalArgumentException invalid) { throw new IOException("Invalid Changes state",invalid); }
            finally { wipe(data);wipe(body);wipe(digest); }
        }
    }
    private void save(State state) throws IOException {
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeByte(1);uuid(out,state.pair);uuid(out,state.local);uuid(out,state.peer);
        out.writeInt(state.identity.version());uuid(out,state.identity.persistent());uuid(out,state.identity.health());
        uuid(out,state.session);uuid(out,state.message);uuid(out,state.epoch);out.writeDouble(state.startDate);out.writeLong(state.deadline);
        out.writeByte(state.phase.ordinal());out.writeBoolean(state.remoteCode!=null);
        if(state.remoteCode!=null)out.writeInt(state.remoteCode);out.writeInt(state.reportedAnchors);
        byte[] body=bytes.toByteArray(),digest=sha(body);Path temporary=null;
        try {
            Files.createDirectories(directory);temporary=Files.createTempFile(directory,"health-changes-",".tmp");
            try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                for(byte[] part:new byte[][]{body,digest}) { var buffer=ByteBuffer.wrap(part);while(buffer.hasRemaining())channel.write(buffer); }
                channel.force(true);
            }
            Files.move(temporary,directory.resolve(state.pair+".changes"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            temporary=null;try(var parent=FileChannel.open(directory,StandardOpenOption.READ)) { parent.force(true); }
        } finally { if(temporary!=null)Files.deleteIfExists(temporary);wipe(body);wipe(digest); }
    }
    private static byte[] sha(byte[] bytes) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch(java.security.GeneralSecurityException failure) { throw new IOException(failure); }
    }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(),in.readLong()); }
    private static void uuid(DataOutputStream out,UUID uuid) throws IOException { out.writeLong(uuid.getMostSignificantBits());out.writeLong(uuid.getLeastSignificantBits()); }
    private static void wipe(byte[] bytes) { if(bytes!=null)Arrays.fill(bytes,(byte)0); }
}
