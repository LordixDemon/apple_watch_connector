package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public final class HealthPeerIdentityStoreTest {
    private static final UUID PAIR=UUID.fromString("11223344-5566-4777-8899-aabbccddeeff");
    private static final UUID LOCAL=UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private static final UUID PEER=UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static byte[] key() {
        try(var identity=IdsMessageProtectionIdentity.generate(new SecureRandom())) {
            Map<String,byte[]> keys=IdsMessageProtectionIdentity.parsePublicBundle(identity.publicBundle());
            try { return keys.get("A").clone(); } finally { IdsMessageProtectionIdentity.wipeValues(keys); }
        }
    }
    @Test public void restartRetainsKeyOnlyForTheSamePairAndBothIdsIdentities() throws Exception {
        Path directory=Files.createTempDirectory("health-peer-key"); byte[] key=key(); UUID epoch=UUID.randomUUID();
        var store=new HealthPeerIdentityStore(directory);
        assertNull(store.read(PAIR,LOCAL,PEER));
        try(var identity=new HealthPeerIdentityCodec.Identity(PAIR,epoch,LOCAL,PEER,key)) {
            store.store(identity,PAIR,epoch,LOCAL,PEER);
        }
        assertArrayEquals(key,new HealthPeerIdentityStore(directory).read(PAIR,LOCAL,PEER));
        assertNull(store.read(UUID.randomUUID(),LOCAL,PEER));
        assertThrows(java.io.IOException.class,()->store.read(PAIR,UUID.randomUUID(),PEER));
        assertThrows(java.io.IOException.class,()->store.read(PAIR,LOCAL,UUID.randomUUID()));
    }
    @Test public void staleEpochWrongInstallationAndWrongPairNeverReplaceSavedKey() throws Exception {
        Path directory=Files.createTempDirectory("health-peer-key"); UUID epoch=UUID.randomUUID();
        var store=new HealthPeerIdentityStore(directory); byte[] key=key();
        try(var identity=new HealthPeerIdentityCodec.Identity(PAIR,epoch,LOCAL,PEER,key)) {
            store.store(identity,PAIR,epoch,LOCAL,PEER);
            byte[] before=Files.readAllBytes(directory.resolve(PAIR+".public"));
            assertThrows(IllegalArgumentException.class,()->store.store(identity,PAIR,UUID.randomUUID(),LOCAL,PEER));
            assertThrows(IllegalArgumentException.class,()->store.store(identity,UUID.randomUUID(),epoch,LOCAL,PEER));
            assertThrows(IllegalArgumentException.class,()->store.store(identity,PAIR,epoch,UUID.randomUUID(),PEER));
            assertArrayEquals(before,Files.readAllBytes(directory.resolve(PAIR+".public")));
        }
    }
    @Test public void malformedOversizedAndCorruptKeysAreRejected() throws Exception {
        byte[] key=key(); UUID epoch=UUID.randomUUID();
        try(var identity=new HealthPeerIdentityCodec.Identity(PAIR,epoch,LOCAL,PEER,key);
            var decoded=HealthPeerIdentityCodec.decode(HealthPeerIdentityCodec.encode(identity))) {
            byte[] copy=decoded.key(); copy[0]=0; assertArrayEquals(key,decoded.key());
        }
        assertThrows(IllegalArgumentException.class,()->new HealthPeerIdentityCodec.Identity(PAIR,epoch,LOCAL,PEER,new byte[10]));
        assertThrows(IllegalArgumentException.class,()->HealthPeerIdentityCodec.decode(new byte[8193]));
        assertThrows(IllegalArgumentException.class,()->HealthPeerIdentityCodec.decode(AppleBinaryPropertyList.encode(
                Map.of("v",1L,"pair",PAIR.toString(),"epoch",epoch.toString(),"local",LOCAL.toString(),"peer",PEER.toString(),"A",key,"extra",true))));
        Path directory=Files.createTempDirectory("health-peer-corrupt");
        Files.write(directory.resolve(PAIR+".public"),new byte[]{1,2,3});
        assertThrows(java.io.IOException.class,()->new HealthPeerIdentityStore(directory).read(PAIR,LOCAL,PEER));
    }
}
