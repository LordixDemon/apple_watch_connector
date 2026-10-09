package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

/** Synthetic encrypted dictionaries test inbox boundaries, not native Health decryption. */
public final class HealthInboundJournalTest {
    private static final UUID PAIR = UUID.fromString("11223344-5566-4777-8899-aabbccddeeff");
    private static final UUID EPOCH = UUID.fromString("abcdefab-1234-4567-89ab-cdefabcdefab");
    private static byte[] key() {
        try(var identity = IdsMessageProtectionIdentity.generate(new SecureRandom())) {
            Map<String,byte[]> keys = IdsMessageProtectionIdentity.parsePublicBundle(identity.publicBundle());
            try { return keys.get("A").clone(); } finally { IdsMessageProtectionIdentity.wipeValues(keys); }
        }
    }
    private static byte[] encrypted(int discriminator) {
        byte[] sed = new byte[16]; sed[0]=(byte)discriminator;
        return AppleBinaryPropertyList.encode(Map.of("ekd",new byte[]{2,0,1,0},"sed",sed));
    }
    private static HealthDataEventCodec.Event event(UUID epoch, UUID id, byte[] key, int value) {
        return new HealthDataEventCodec.Event(PAIR,epoch,id,null,2,5,key,encrypted(value));
    }
    @Test public void privateFrameRetainsPairEpochResponseAndOriginalCiphertext() {
        byte[] key=key(), payload=encrypted(1); UUID id=UUID.randomUUID(), reply=UUID.randomUUID();
        try(var event=new HealthDataEventCodec.Event(PAIR,EPOCH,id,reply,3,1,key,payload);
            var decoded=HealthDataEventCodec.decode(HealthDataEventCodec.encode(event))) {
            assertEquals(PAIR,decoded.pair); assertEquals(EPOCH,decoded.epoch); assertEquals(id,decoded.messageId);
            assertEquals(reply,decoded.responseTo); assertEquals(3,decoded.streamId); assertEquals(1,decoded.flags);
            assertArrayEquals(key,decoded.peerKey()); assertArrayEquals(payload,decoded.encrypted());
            byte[] copy=decoded.encrypted(); copy[0]=0; assertArrayEquals(payload,decoded.encrypted());
        }
    }
    @Test public void plaintextWrongFieldsNonBlockCiphertextAndBoundsAreRejected() {
        byte[] key=key();
        for(byte[] bytes:new byte[][]{new byte[]{2,0,0},
                AppleBinaryPropertyList.encode(Map.of("sed",new byte[16])),
                AppleBinaryPropertyList.encode(Map.of("ekd",new byte[4],"sed",new byte[15])),
                AppleBinaryPropertyList.encode(Map.of("ekd",new byte[4097],"sed",new byte[16]))}) {
            assertThrows(IllegalArgumentException.class,()->new HealthDataEventCodec.Event(
                    PAIR,EPOCH,UUID.randomUUID(),null,2,5,key,bytes));
        }
        assertThrows(IllegalArgumentException.class,()->HealthDataEventCodec.decode(new byte[HealthDataEventCodec.MAX_FRAME+1]));
        assertThrows(IllegalArgumentException.class,()->new HealthDataEventCodec.Event(
                PAIR,EPOCH,UUID.randomUUID(),null,0,5,key,encrypted(0)));
    }
    @Test public void durableInboxSurvivesNewInstanceAndDeduplicatesAcrossEpochs() throws Exception {
        Path root=Files.createTempDirectory("health-inbox-test"); byte[] key=key(); UUID id=UUID.randomUUID();
        try(var first=event(EPOCH,id,key,1); var duplicate=event(UUID.randomUUID(),id,key,1);
            var conflict=event(EPOCH,id,key,2)) {
            assertEquals(HealthInboundJournal.Outcome.STAGED_ENCRYPTED,new HealthInboundJournal(root,PAIR).stage(first,EPOCH));
            assertEquals(HealthInboundJournal.Outcome.DUPLICATE,new HealthInboundJournal(root,PAIR).stage(duplicate,duplicate.epoch));
            assertEquals(HealthInboundJournal.Outcome.CONFLICT,new HealthInboundJournal(root,PAIR).stage(conflict,EPOCH));
            try(var stored=HealthDataEventCodec.decode(Files.readAllBytes(root.resolve(PAIR.toString()).resolve(id+".aoverc")))) {
                assertArrayEquals(first.encrypted(),stored.encrypted());
            }
        }
    }
    @Test public void wrongPairEpochAndCorruptPriorRecordNeverOverwriteInbox() throws Exception {
        Path root=Files.createTempDirectory("health-inbox-test"); UUID id=UUID.randomUUID();
        try(var incoming=event(EPOCH,id,key(),1)) {
            assertThrows(IllegalArgumentException.class,()->new HealthInboundJournal(root,UUID.randomUUID()).stage(incoming,EPOCH));
            assertThrows(IllegalArgumentException.class,()->new HealthInboundJournal(root,PAIR).stage(incoming,UUID.randomUUID()));
            Path directory=root.resolve(PAIR.toString()); Files.createDirectories(directory);
            Path record=directory.resolve(id+".aoverc"); Files.write(record,new byte[]{1,2,3});
            assertEquals(HealthInboundJournal.Outcome.CONFLICT,new HealthInboundJournal(root,PAIR).stage(incoming,EPOCH));
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(record));
        }
    }
    @Test public void recordAndByteBudgetsKeepOldDataRatherThanSilentlyEvicting() throws Exception {
        byte[] key=key(); Path countRoot=Files.createTempDirectory("health-inbox-limit");
        Path directory=countRoot.resolve(PAIR.toString()); Files.createDirectories(directory);
        for(int i=0;i<HealthInboundJournal.MAX_RECORDS;i++) Files.write(directory.resolve(i+".tmp"),new byte[0]);
        try(var incoming=event(EPOCH,UUID.randomUUID(),key,1)) {
            assertEquals(HealthInboundJournal.Outcome.FULL,new HealthInboundJournal(countRoot,PAIR).stage(incoming,EPOCH));
            Path sizeRoot=Files.createTempDirectory("health-inbox-size"); directory=sizeRoot.resolve(PAIR.toString()); Files.createDirectories(directory);
            try(var file=new java.io.RandomAccessFile(directory.resolve("abandoned.tmp").toFile(),"rw")) {
                file.setLength(HealthInboundJournal.MAX_TOTAL_BYTES);
            }
            assertEquals(HealthInboundJournal.Outcome.FULL,new HealthInboundJournal(sizeRoot,PAIR).stage(incoming,EPOCH));
        }
    }
    @Test public void ciphertextBeforeKeysSurvivesRestartAndReplaysItsOriginalEpoch() throws Exception {
        Path root=Files.createTempDirectory("health-before-keys"); UUID id=UUID.randomUUID();
        byte[] encrypted=encrypted(7); UUID reply=UUID.randomUUID();
        try(var first=new HealthDataEventCodec.Event(PAIR,EPOCH,id,reply,2,5,null,encrypted)) {
            assertEquals(HealthInboundJournal.Outcome.STAGED_ENCRYPTED,new HealthInboundJournal(root,PAIR).stage(first,EPOCH));
        }
        byte[] before=Files.readAllBytes(root.resolve(PAIR.toString()).resolve(id+".aoverc"));
        int[] reads={0};
        assertEquals(1,new HealthInboundJournal(root,PAIR).replay(event -> {
            assertNull(event.peerKey()); assertEquals(EPOCH,event.epoch); assertEquals(reply,event.responseTo);
            assertEquals(id,event.messageId); assertArrayEquals(encrypted,event.encrypted()); reads[0]++;
        }));
        assertEquals(1,reads[0]);
        try(var redelivered=new HealthDataEventCodec.Event(PAIR,UUID.randomUUID(),id,reply,3,5,key(),encrypted)) {
            assertEquals(HealthInboundJournal.Outcome.DUPLICATE,new HealthInboundJournal(root,PAIR).stage(redelivered,redelivered.epoch));
        }
        assertArrayEquals(before,Files.readAllBytes(root.resolve(PAIR.toString()).resolve(id+".aoverc")));
    }
    @Test public void replayRejectsChangedPairFilenameCorruptionAndOverBudgetWithoutDeleting() throws Exception {
        Path root=Files.createTempDirectory("health-replay-invalid"); UUID id=UUID.randomUUID();
        Path directory=root.resolve(PAIR.toString()); Files.createDirectories(directory);
        byte[] bytes;
        try(var event=event(EPOCH,id,null,1)) { bytes=HealthDataEventCodec.encode(event); }
        Path wrong=directory.resolve(UUID.randomUUID()+".aoverc"); Files.write(wrong,bytes);
        assertThrows(java.io.IOException.class,()->new HealthInboundJournal(root,PAIR).replay(e -> fail("Invalid identity delivered")));
        assertArrayEquals(bytes,Files.readAllBytes(wrong)); Files.delete(wrong);
        Path corrupt=directory.resolve(id+".aoverc"); Files.write(corrupt,new byte[]{1,2,3});
        assertThrows(java.io.IOException.class,()->new HealthInboundJournal(root,PAIR).replay(e -> fail("Corruption delivered")));
        assertTrue(Files.exists(corrupt)); Files.delete(corrupt);
        for(int i=0;i<=HealthInboundJournal.MAX_RECORDS;i++) Files.write(directory.resolve(i+".tmp"),new byte[0]);
        assertThrows(java.io.IOException.class,()->new HealthInboundJournal(root,PAIR).replay(e -> fail("Over-budget delivered")));
    }
    @Test public void versionOneInboxIsStillReadableButMissingKeyIsNotValidVersionOne() {
        byte[] key=key(); UUID id=UUID.randomUUID();
        Map<String,Object> legacy=new java.util.LinkedHashMap<>(Map.of("v",1L,"pair",PAIR.toString(),
                "epoch",EPOCH.toString(),"id",id.toString(),"stream",2L,"flags",5L,
                "peerA",key,"encrypted",encrypted(1)));
        try(var decoded=HealthDataEventCodec.decode(AppleBinaryPropertyList.encode(legacy))) {
            assertArrayEquals(key,decoded.peerKey());
        }
        legacy.remove("peerA");
        assertThrows(IllegalArgumentException.class,()->HealthDataEventCodec.decode(AppleBinaryPropertyList.encode(legacy)));
        legacy.put("v",2L); legacy.put("peerA","unverified-placeholder");
        assertThrows(IllegalArgumentException.class,()->HealthDataEventCodec.decode(AppleBinaryPropertyList.encode(legacy)));
    }
}
