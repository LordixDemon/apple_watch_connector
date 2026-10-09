package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.UUID;

/** APK-private durable public key, always bound to activated pair and local/remote IDS identities. */
final class HealthPeerIdentityStore {
    private final Path directory;
    HealthPeerIdentityStore(Path directory) { this.directory=directory; }
    synchronized void store(HealthPeerIdentityCodec.Identity identity, UUID pair, UUID epoch,
                            UUID local, UUID peer) throws IOException {
        identity.requirePair(pair,local,peer);
        if(!identity.epoch.equals(epoch)) throw new IllegalArgumentException("Stale Health peer epoch");
        Files.createDirectories(directory);
        byte[] bytes=HealthPeerIdentityCodec.encode(identity); Path temporary=null;
        try {
            temporary=Files.createTempFile(directory,"peer-",".tmp");
            try(var out=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                var buffer=ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            Files.move(temporary,directory.resolve(pair+".public"),StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING); temporary=null;
            try(var parent=FileChannel.open(directory,StandardOpenOption.READ)) { parent.force(true); }
        } finally { if(temporary!=null) Files.deleteIfExists(temporary); Arrays.fill(bytes,(byte)0); }
    }
    synchronized byte[] read(UUID pair, UUID local, UUID peer) throws IOException {
        Path path=directory.resolve(pair+".public");
        if(!Files.exists(path)) return null;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)>HealthPeerIdentityCodec.MAX_FRAME) {
            throw new IOException("Invalid saved Health peer identity");
        }
        byte[] bytes=Files.readAllBytes(path);
        try(var identity=HealthPeerIdentityCodec.decode(bytes)) {
            identity.requirePair(pair,local,peer); return identity.key();
        } catch(IllegalArgumentException invalid) { throw new IOException("Saved Health peer identity mismatch",invalid); }
        finally { Arrays.fill(bytes,(byte)0); }
    }
}
