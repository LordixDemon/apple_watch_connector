package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class HealthSyncStateStoreTest {
    private static final UUID PAIR=UUID.fromString("11223344-5566-4777-8899-aabbccddeeff"), LOCAL=UUID.randomUUID(), PEER=UUID.randomUUID();
    private static final UUID NATIVE=UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"), HEALTH=UUID.randomUUID();
    private static HealthSyncStateStore store() throws Exception {
        var store=new HealthSyncStateStore(Files.createTempDirectory("health-state"));
        store.initialize(PAIR,LOCAL,PEER,NATIVE,"Watch7,5","23S303",()->HEALTH);return store;
    }
    private static HealthSyncStateStore.State prepared(HealthSyncStateStore store) throws Exception {
        return store.prepare(PAIR,LOCAL,PEER,UUID.randomUUID(),60000,UUID.randomUUID(),UUID.randomUUID());
    }
    private static NativeHealthRestoreCodec.Header header(HealthSyncStateStore.State state,int code,long seq) {
        var status=switch(code) { case 1 -> NativeHealthRestoreCodec.Status.START;case 2 -> NativeHealthRestoreCodec.Status.FINISHED;
            case 3 -> NativeHealthRestoreCodec.Status.ABORT;default -> NativeHealthRestoreCodec.Status.UNKNOWN; };
        return new NativeHealthRestoreCodec.Header(state.session(),seq,code,status,List.of());
    }
    private static HealthSyncStateStore.State response(HealthSyncStateStore store,HealthSyncStateStore.State state,int code,long sequence) throws Exception {
        return store.response(PAIR,LOCAL,PEER,state.epoch(),state.message(),100,state.identity(),header(state,code,sequence));
    }
    @Test public void durableIdentityReloadNeverRegeneratesOrSubstitutesOwnerPair() throws Exception {
        Path root=Files.createTempDirectory("health-state-reload");var store=new HealthSyncStateStore(root);
        var first=store.initialize(PAIR,LOCAL,PEER,NATIVE,"Watch7,5","23S303",()->HEALTH);
        assertEquals(NATIVE,first.persistent());assertNotEquals(PAIR,first.persistent());assertEquals(HEALTH,first.health());
        assertEquals("com.apple.health.AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE",first.source());
        assertEquals(first,new HealthSyncStateStore(root).initialize(PAIR,LOCAL,PEER,NATIVE,"Watch7,5","23S303",()->{fail("Identity regenerated");return null;}));
        assertThrows(java.io.IOException.class,()->store.initialize(PAIR,LOCAL,PEER,UUID.randomUUID(),"Watch7,5","23S303",()->{fail("Registry mismatch regenerated");return null;}));
        assertEquals(first,store.read(PAIR,LOCAL,PEER));assertEquals(NATIVE,first.registry());
        assertThrows(java.io.IOException.class,()->store.read(PAIR,UUID.randomUUID(),PEER));
        assertThrows(java.io.IOException.class,()->store.read(PAIR,LOCAL,UUID.randomUUID()));
    }
    @Test public void invalidContextAndUnknownTargetDoNotInvokeUuidSupplier() throws Exception {
        var store=new HealthSyncStateStore(Files.createTempDirectory("health-unsupported"));
        java.util.function.Supplier<UUID> unused=()->{fail("Generated before validating context");return null;};
        assertThrows(IllegalArgumentException.class,()->store.initialize(null,LOCAL,PEER,NATIVE,"Watch7,5","23S303",unused));
        assertThrows(IllegalArgumentException.class,()->store.initialize(PAIR,LOCAL,LOCAL,NATIVE,"Watch7,5","23S303",unused));
        assertThrows(IllegalArgumentException.class,()->store.initialize(PAIR,LOCAL,PEER,NATIVE,"Watch7,4","23S303",unused));
        assertThrows(IllegalArgumentException.class,()->store.initialize(PAIR,LOCAL,PEER,NATIVE,"Watch7,5","23S304",unused));
        assertNull(store.read(PAIR,LOCAL,PEER));
    }
    @Test public void corruptOversizedSymlinkAndTruncatedFilesNeverRegenerate() throws Exception {
        for(int kind=0;kind<4;kind++) {
            Path root=Files.createTempDirectory("health-corrupt"),file=root.resolve(PAIR+".state");var store=new HealthSyncStateStore(root);
            store.initialize(PAIR,LOCAL,PEER,NATIVE,"Watch7,5","23S303",()->HEALTH);
            byte[] valid=Files.readAllBytes(file);
            if(kind==0) { valid[15]^=1;Files.write(file,valid); }
            if(kind==1)Files.write(file,new byte[1025]);
            if(kind==2) { Path target=root.resolve("original");Files.move(file,target);Files.createSymbolicLink(file,target); }
            if(kind==3)Files.write(file,new byte[31]);
            assertThrows(java.io.IOException.class,()->store.initialize(PAIR,LOCAL,PEER,NATIVE,"Watch7,5","23S303",()->{fail("Corruption regenerated");return null;}));
        }
    }
    @Test public void preparedSessionSurvivesRestartAndCannotBeReissuedWithinEpoch() throws Exception {
        var store=store();var state=prepared(store);assertEquals(state,store.read(PAIR,LOCAL,PEER));
        assertEquals(1,state.sequence());assertEquals(1,state.revision());
        assertThrows(IllegalStateException.class,()->store.prepare(PAIR,LOCAL,PEER,state.epoch(),60001,UUID.randomUUID(),UUID.randomUUID()));
        assertEquals(state,store.read(PAIR,LOCAL,PEER));
    }
    @Test public void ackNeverFinishesAndDuplicateQueueCannotRollBackNativeStart() throws Exception {
        var store=store();var state=prepared(store);
        assertEquals(state,store.receipt(PAIR,LOCAL,PEER,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.APP_ACK_RECEIVED)));
        var queued=store.receipt(PAIR,LOCAL,PEER,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.IDS_QUEUED));
        assertEquals(HealthSyncStateStore.Phase.IDS_QUEUED,queued.phase());
        assertEquals(queued,store.receipt(PAIR,LOCAL,PEER,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.IDS_QUEUED)));
        var started=response(store,state,1,123456789012L);assertEquals(1,started.sequence());
        assertEquals(started,store.receipt(PAIR,LOCAL,PEER,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.IDS_QUEUED)));
        assertEquals(HealthSyncStateStore.Phase.REMOTE_FINISHED,response(store,state,2,1).phase());
        assertThrows(IllegalStateException.class,()->store.prepare(PAIR,LOCAL,PEER,UUID.randomUUID(),60001,UUID.randomUUID(),UUID.randomUUID()));
    }
    @Test public void everyNativeResponseOwnerMustMatchBeforeAnyTransition() throws Exception {
        var store=store();var state=prepared(store);var good=header(state,2,1);
        assertEquals(state,store.response(PAIR,LOCAL,PEER,UUID.randomUUID(),state.message(),100,state.identity(),good));
        assertEquals(state,store.response(PAIR,LOCAL,PEER,state.epoch(),UUID.randomUUID(),100,state.identity(),good));
        for(var identity:List.of(new NativeHealthSyncCodec.Identity(16,NATIVE,HEALTH),
                new NativeHealthSyncCodec.Identity(17,UUID.randomUUID(),HEALTH),new NativeHealthSyncCodec.Identity(17,NATIVE,UUID.randomUUID()))) {
            assertEquals(state,store.response(PAIR,LOCAL,PEER,state.epoch(),state.message(),100,identity,good));
        }
        var foreign=new NativeHealthRestoreCodec.Header(UUID.randomUUID(),1,2,NativeHealthRestoreCodec.Status.FINISHED,List.of());
        assertEquals(state,store.response(PAIR,LOCAL,PEER,state.epoch(),state.message(),100,state.identity(),foreign));
    }
    @Test public void unknownDeletionAndOutOfSequenceResultsDoNotFinish() throws Exception {
        var store=store();var state=prepared(store);
        assertEquals(state,response(store,state,55,1));
        var deleting=new NativeHealthRestoreCodec.Header(state.session(),1,2,NativeHealthRestoreCodec.Status.FINISHED,List.of(HEALTH));
        assertEquals(state,store.response(PAIR,LOCAL,PEER,state.epoch(),state.message(),100,state.identity(),deleting));
        assertEquals(HealthSyncStateStore.Phase.UNKNOWN,response(store,state,2,2).phase());
        assertEquals(HealthSyncStateStore.Phase.UNKNOWN,response(store,state,2,1).phase());
    }
    @Test public void expiredAndDisconnectedSessionsNeverReviveAndReconnectRetainsHealthIds() throws Exception {
        var store=store();var state=prepared(store);
        assertEquals(HealthSyncStateStore.Phase.EXPIRED,store.response(PAIR,LOCAL,PEER,state.epoch(),state.message(),60000,state.identity(),header(state,2,1)).phase());
        assertEquals(HealthSyncStateStore.Phase.EXPIRED,response(store,state,2,1).phase());
        var renewed=prepared(store);assertNotEquals(state.message(),renewed.message());assertEquals(state.identity(),renewed.identity());
        assertEquals(renewed,store.disconnected(PAIR,LOCAL,PEER,state.epoch()));
        assertEquals(HealthSyncStateStore.Phase.UNKNOWN,store.disconnected(PAIR,LOCAL,PEER,renewed.epoch()).phase());
        assertEquals(HealthSyncStateStore.Phase.UNKNOWN,response(store,renewed,2,1).phase());
    }
    @Test public void abortAndSendFailureAreDurableTerminalStates() throws Exception {
        var store=store();var state=prepared(store);assertEquals(HealthSyncStateStore.Phase.REMOTE_ABORT,response(store,state,3,1).phase());
        assertEquals(HealthSyncStateStore.Phase.REMOTE_ABORT,store.read(PAIR,LOCAL,PEER).phase());
        var retry=prepared(store);
        assertEquals(HealthSyncStateStore.Phase.UNKNOWN,store.receipt(PAIR,LOCAL,PEER,new BridgeCommandCodec.Status(retry.message(),retry.epoch(),BridgeCommandCodec.Stage.FAILED)).phase());
        assertEquals(HealthSyncStateStore.Phase.UNKNOWN,response(store,retry,2,1).phase());
    }
    @Test public void deadlineTaskExpiresOnlyItsPendingSessionWithoutChangingFinishedOrNewerSession() throws Exception {
        var store=store();var state=prepared(store);
        assertEquals(state,store.expire(PAIR,LOCAL,PEER,state.epoch(),state.message(),59999));
        assertEquals(state,store.expire(PAIR,LOCAL,PEER,UUID.randomUUID(),state.message(),60000));
        assertEquals(state,store.expire(PAIR,LOCAL,PEER,state.epoch(),UUID.randomUUID(),60000));
        assertEquals(HealthSyncStateStore.Phase.EXPIRED,store.expire(PAIR,LOCAL,PEER,state.epoch(),state.message(),60000).phase());
        var current=prepared(store);
        assertEquals(current,store.expire(PAIR,LOCAL,PEER,state.epoch(),state.message(),100000));
        var finished=response(store,current,2,1);
        assertEquals(finished,store.expire(PAIR,LOCAL,PEER,current.epoch(),current.message(),100000));
    }
    @Test public void nativeEmptyOutgoingRestoreHasExactHeaderIdentitiesAndNoDeletionOrSyncIdentity() throws Exception {
        var store=store();var state=prepared(store);byte[] wire=NativeHealthInitialRestoreCodec.encode(state);
        assertEquals(1,wire[0]);assertEquals(0,wire[1]);assertEquals(0,wire[2]);
        try(var frame=NativeHealthSyncCodec.decodePlaintext(wire,false);var envelope=frame.envelope();var restore=envelope.restore()) {
            assertEquals(state.identity(),envelope.requireIdentity());assertFalse(envelope.has(7));assertFalse(envelope.has(8));assertFalse(envelope.has(11));
            var header=NativeHealthRestoreCodec.requireHeader(restore);assertEquals(state.session(),header.identifier());
            assertEquals(1,header.sequence());assertEquals(2,header.statusCode());assertTrue(header.obliterated().isEmpty());
            assertEquals(state.source(),new String(restore.bytes(4),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThrows(IllegalArgumentException.class,()->NativeHealthInitialRestoreCodec.encode(state.phase(HealthSyncStateStore.Phase.IDS_QUEUED)));
    }
}
