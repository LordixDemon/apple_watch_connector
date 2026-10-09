package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/** APK-private native Health identities/session. No samples, anchor advancement or profile deletion here. */
final class HealthSyncStateStore {
    private static final Object LOCK=new Object();
    private static final int MAGIC=0x41574853, MAX_FILE=1024;
    enum Phase { NONE, PREPARED, IDS_QUEUED, REMOTE_STARTED, REMOTE_FINISHED, REMOTE_ABORT, UNKNOWN, EXPIRED }
    record State(UUID pair, UUID local, UUID peer, UUID registry, int version, UUID persistent, UUID health, String source,
                 long revision, Phase phase, UUID session, UUID message, UUID epoch, long sequence, long deadline) {
        State {
            if(pair==null || local==null || peer==null || registry==null || version!=17 || persistent==null || health==null
                    || !sourceFor(persistent).equals(source) || revision<0 || phase==null) throw new IllegalArgumentException();
            boolean none=phase==Phase.NONE;
            if(none ? session!=null || message!=null || epoch!=null || sequence!=0 || deadline!=0
                    : session==null || message==null || message.version()!=4 || message.variant()!=2
                    || epoch==null || sequence!=1 || deadline<=0) throw new IllegalArgumentException();
        }
        NativeHealthSyncCodec.Identity identity() { return new NativeHealthSyncCodec.Identity(version,persistent,health); }
        boolean pending() { return phase==Phase.PREPARED || phase==Phase.IDS_QUEUED || phase==Phase.REMOTE_STARTED; }
        State phase(Phase next) {
            return new State(pair,local,peer,registry,version,persistent,health,source,Math.addExact(revision,1),next,
                    session,message,epoch,sequence,deadline);
        }
    }
    private final Path directory;
    HealthSyncStateStore(Path directory) { this.directory=directory; }
    static String sourceFor(UUID persistent) {
        return "com.apple.health."+persistent.toString().toUpperCase(Locale.ROOT);
    }
    /** Exact current target only. Unknown firmware never inherits this version or identity contract. */
    State initialize(UUID pair, UUID local, UUID peer, UUID nativePairing, String product, String build, Supplier<UUID> healthUuid) throws IOException {
        synchronized(LOCK) {
            if(pair==null || local==null || peer==null || local.equals(peer) || nativePairing==null || healthUuid==null
                    || !"Watch7,5".equals(product) || !"23S303".equals(build)) throw new IllegalArgumentException("Unsupported Health profile");
            State existing=read(pair,local,peer);
            if(existing!=null) {
                if(!existing.registry.equals(nativePairing))throw new IOException("Native Health registry binding changed");
                return existing;
            }
            // Native first-store persistent fallback is hd_pairingID; database UUID has its own lifetime.
            State created=new State(pair,local,peer,nativePairing,17,nativePairing,healthUuid.get(),sourceFor(nativePairing),0,Phase.NONE,
                    null,null,null,0,0);
            save(created); return created;
        }
    }
    State prepare(UUID pair, UUID local, UUID peer, UUID epoch, long deadline, UUID session, UUID message) throws IOException {
        synchronized(LOCK) {
            State old=require(pair,local,peer);
            if(old.phase==Phase.REMOTE_FINISHED || old.epoch!=null && old.epoch.equals(epoch)) {
                throw new IllegalStateException("Health Restore already prepared in this epoch or remotely finished");
            }
            State next=new State(pair,local,peer,old.registry,old.version,old.persistent,old.health,old.source,
                    Math.addExact(old.revision,1),Phase.PREPARED,session,message,epoch,1,deadline);
            save(next); return next;
        }
    }
    State receipt(UUID pair, UUID local, UUID peer, BridgeCommandCodec.Status receipt) throws IOException {
        synchronized(LOCK) {
            State old=require(pair,local,peer);
            if(!old.pending() || !receipt.id().equals(old.message) || !receipt.epoch().equals(old.epoch)) return old;
            Phase phase=switch(receipt.stage()) {
                case IDS_QUEUED -> old.phase==Phase.PREPARED ? Phase.IDS_QUEUED : old.phase;
                case EXPIRED -> Phase.EXPIRED;
                case REJECTED,FAILED,UNKNOWN -> Phase.UNKNOWN;
                default -> old.phase; // Transport/app ACK never finishes a native Restore.
            };
            return change(old,phase);
        }
    }
    State disconnected(UUID pair, UUID local, UUID peer, UUID epoch) throws IOException {
        synchronized(LOCK) {
            State old=read(pair,local,peer);
            return old!=null && old.pending() && old.epoch.equals(epoch) ? change(old,Phase.UNKNOWN) : old;
        }
    }
    State expire(UUID pair,UUID local,UUID peer,UUID epoch,UUID message,long now) throws IOException {
        synchronized(LOCK) {
            State old=require(pair,local,peer);
            return old.pending() && old.epoch.equals(epoch) && old.message.equals(message) && now>=old.deadline
                    ? change(old,Phase.EXPIRED) : old;
        }
    }
    State response(UUID pair, UUID local, UUID peer, UUID epoch, UUID responseTo, long now,
                   NativeHealthSyncCodec.Identity identity, NativeHealthRestoreCodec.Header header) throws IOException {
        synchronized(LOCK) {
            State old=require(pair,local,peer);
            if(!old.pending() || !old.epoch.equals(epoch) || !old.message.equals(responseTo)
                    || !old.identity().equals(identity) || !old.session.equals(header.identifier())) return old;
            if(now>=old.deadline) return change(old,Phase.EXPIRED);
            if(!header.obliterated().isEmpty()) return old; // Never adopt an unexpected deletion instruction.
            Phase next=switch(header.status()) {
                case START -> Phase.REMOTE_STARTED;
                case FINISHED -> header.sequence()==old.sequence ? Phase.REMOTE_FINISHED : Phase.UNKNOWN;
                case ABORT -> Phase.REMOTE_ABORT;
                case UNKNOWN -> old.phase;
            };
            return change(old,next);
        }
    }
    private State change(State old,Phase phase) throws IOException {
        if(old.phase==phase) return old;
        State next=old.phase(phase); save(next); return next;
    }
    private State require(UUID pair,UUID local,UUID peer) throws IOException {
        State state=read(pair,local,peer);
        if(state==null) throw new IOException("No native Health state"); return state;
    }
    State read(UUID pair,UUID local,UUID peer) throws IOException {
        synchronized(LOCK) {
            Path path=directory.resolve(pair+".state");
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return null;
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>MAX_FILE) throw new IOException("Invalid Health state file");
            byte[] file=Files.readAllBytes(path), body=null, digest=null;
            try {
                if(file.length<32) throw new IOException("Truncated Health state");
                body=Arrays.copyOf(file,file.length-32); digest=sha(body);
                if(!MessageDigest.isEqual(digest,Arrays.copyOfRange(file,body.length,file.length))) throw new IOException("Health state checksum");
                var in=new DataInputStream(new ByteArrayInputStream(body));
                if(in.readInt()!=MAGIC || in.readUnsignedByte()!=2) throw new IOException("Health state version");
                UUID owner=uuid(in), sender=uuid(in), receiver=uuid(in);
                UUID registry=uuid(in);
                int version=in.readInt(); UUID persistent=uuid(in), health=uuid(in); String source=in.readUTF();
                long revision=in.readLong(); int phase=in.readUnsignedByte();
                if(phase>=Phase.values().length) throw new IOException("Health state phase");
                boolean hasSession=in.readBoolean(); UUID session=hasSession ? uuid(in) : null;
                UUID message=hasSession ? uuid(in) : null, epoch=hasSession ? uuid(in) : null;
                long sequence=hasSession ? in.readLong() : 0, deadline=hasSession ? in.readLong() : 0;
                State state=new State(owner,sender,receiver,registry,version,persistent,health,source,revision,Phase.values()[phase],
                        session,message,epoch,sequence,deadline);
                if(in.available()!=0 || !owner.equals(pair) || !sender.equals(local) || !receiver.equals(peer)) throw new IOException("Health state ownership");
                return state;
            } catch(IllegalArgumentException invalid) { throw new IOException("Invalid Health state",invalid); }
            finally { wipe(file);wipe(body);wipe(digest); }
        }
    }
    private void save(State state) throws IOException {
        var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeByte(2); uuid(out,state.pair); uuid(out,state.local); uuid(out,state.peer);uuid(out,state.registry);
        out.writeInt(state.version); uuid(out,state.persistent); uuid(out,state.health); out.writeUTF(state.source);
        out.writeLong(state.revision); out.writeByte(state.phase.ordinal()); out.writeBoolean(state.session!=null);
        if(state.session!=null) { uuid(out,state.session);uuid(out,state.message);uuid(out,state.epoch);out.writeLong(state.sequence);out.writeLong(state.deadline); }
        byte[] body=bytes.toByteArray(), digest=sha(body); Path temporary=null;
        try {
            Files.createDirectories(directory); temporary=Files.createTempFile(directory,"health-",".tmp");
            try(var file=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                for(byte[] part:new byte[][]{body,digest}) { var buffer=ByteBuffer.wrap(part);while(buffer.hasRemaining())file.write(buffer); }
                file.force(true);
            }
            Files.move(temporary,directory.resolve(state.pair+".state"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            temporary=null; try(var parent=FileChannel.open(directory,StandardOpenOption.READ)) { parent.force(true); }
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
