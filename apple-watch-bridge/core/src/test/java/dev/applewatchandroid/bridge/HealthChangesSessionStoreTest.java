package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class HealthChangesSessionStoreTest {
    private static final UUID PAIR=UUID.randomUUID(),LOCAL=UUID.randomUUID(),PEER=UUID.randomUUID(),REGISTRY=UUID.randomUUID(),HEALTH=UUID.randomUUID();
    private static HealthSyncStateStore.State profile() {
        return new HealthSyncStateStore.State(PAIR,LOCAL,PEER,REGISTRY,17,REGISTRY,HEALTH,HealthSyncStateStore.sourceFor(REGISTRY),3,
                HealthSyncStateStore.Phase.REMOTE_FINISHED,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,60000);
    }
    private static HealthChangesSessionStore.State prepared(HealthChangesSessionStore store,HealthSyncStateStore.State profile) throws Exception {
        return store.prepare(profile,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),800000000.125,60000);
    }
    private static NativeHealthChangesCodec.Node status(int code) {
        return NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.STATUS,new byte[]{8,(byte)code});
    }
    @Test public void mustHaveAcceptedRestoreAndCannotRepeatAcrossEpochsOrProcessRestart() throws Exception {
        Path root=Files.createTempDirectory("changes-once");var store=new HealthChangesSessionStore(root);var profile=profile();
        assertThrows(IllegalStateException.class,()->prepared(store,profile.phase(HealthSyncStateStore.Phase.REMOTE_STARTED)));
        assertNull(store.read(PAIR,LOCAL,PEER,profile.identity()));
        var state=prepared(store,profile);var reloaded=new HealthChangesSessionStore(root);
        assertEquals(state,reloaded.read(PAIR,LOCAL,PEER,profile.identity()));
        assertThrows(IllegalStateException.class,()->prepared(reloaded,profile));
        assertEquals(state,reloaded.read(PAIR,LOCAL,PEER,profile.identity()));
    }
    @Test public void ackNeverAcceptsNativeStatusAndTerminalStatusCannotBeDowngraded() throws Exception {
        var store=new HealthChangesSessionStore(Files.createTempDirectory("changes-ack"));var profile=profile();var state=prepared(store,profile);
        assertEquals(state,store.receipt(profile,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.APP_ACK_RECEIVED)));
        var queued=store.receipt(profile,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.IDS_QUEUED));
        assertEquals(HealthChangesSessionStore.Phase.IDS_QUEUED,queued.phase());
        try(var status=status(1)) {
            var received=store.response(profile,state.epoch(),state.message(),100,profile.identity(),status);
            assertEquals(HealthChangesSessionStore.Phase.REMOTE_STATUS,received.phase());assertEquals(Integer.valueOf(1),received.remoteCode());
            assertEquals(received,store.receipt(profile,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.IDS_QUEUED)));
            assertEquals(received,store.end(profile,state.epoch(),state.message(),100000,false));
            assertEquals(received,store.end(profile,state.epoch(),null,100,true));
            assertThrows(IllegalStateException.class,()->prepared(store,profile));
        }
    }
    @Test public void responseScopeAndDeadlineMustMatchBeforeAnyStateChange() throws Exception {
        var store=new HealthChangesSessionStore(Files.createTempDirectory("changes-scope"));var profile=profile();var state=prepared(store,profile);
        try(var status=status(1)) {
            assertEquals(state,store.response(profile,UUID.randomUUID(),state.message(),100,profile.identity(),status));
            assertEquals(state,store.response(profile,state.epoch(),UUID.randomUUID(),100,profile.identity(),status));
            assertEquals(state,store.response(profile,state.epoch(),null,100,profile.identity(),status));
            for(var wrong:List.of(new NativeHealthSyncCodec.Identity(16,REGISTRY,HEALTH),
                    new NativeHealthSyncCodec.Identity(17,UUID.randomUUID(),HEALTH),new NativeHealthSyncCodec.Identity(17,REGISTRY,UUID.randomUUID()))) {
                assertEquals(state,store.response(profile,state.epoch(),state.message(),100,wrong,status));
            }
            assertThrows(IllegalArgumentException.class,()->store.response(profile,state.epoch(),state.message(),-1,profile.identity(),status));
            assertEquals(HealthChangesSessionStore.Phase.EXPIRED,store.response(profile,state.epoch(),state.message(),60000,profile.identity(),status).phase());
            assertEquals(HealthChangesSessionStore.Phase.EXPIRED,store.response(profile,state.epoch(),state.message(),100,profile.identity(),status).phase());
        }
    }
    @Test public void everyKnownRemoteCodeIsObservationOnlyIncludingObliterateAndResend() throws Exception {
        for(int code=0;code<=6;code++) {
            Path root=Files.createTempDirectory("changes-status");var store=new HealthChangesSessionStore(root);var profile=profile();var state=prepared(store,profile);
            try(var status=status(code)) {
                var observed=store.response(profile,state.epoch(),state.message(),100,profile.identity(),status);
                assertEquals(Integer.valueOf(code),observed.remoteCode());assertEquals(profile.identity(),observed.identity());
                assertEquals(observed,new HealthChangesSessionStore(root).read(PAIR,LOCAL,PEER,profile.identity()));
            }
        }
    }
    @Test public void absentUnknownAndFutureStatusCannotClaimAcceptance() throws Exception {
        var store=new HealthChangesSessionStore(Files.createTempDirectory("changes-future"));var profile=profile();var state=prepared(store,profile);
        assertEquals(state,store.response(profile,state.epoch(),state.message(),100,profile.identity(),null));
        for(byte[] bytes:List.of(new byte[0],new byte[]{8,7},new byte[]{8,1,24,1})) {
            try(var status=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.STATUS,bytes)) {
                assertEquals(state,store.response(profile,state.epoch(),state.message(),100,profile.identity(),status));
            }
        }
    }
    @Test public void anchorsOnlyCountAsReportedMetadataNotAppliedData() throws Exception {
        var store=new HealthChangesSessionStore(Files.createTempDirectory("changes-reported"));var profile=profile();var state=prepared(store,profile);
        // Native anchor with a full-width integer, preserved solely in the original encrypted inbox in production.
        byte[] encoded={8,1,18,7,8,2,16,(byte)0x80,(byte)0x80,(byte)0x80,1};
        try(var status=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.STATUS,encoded)) {
            var observed=store.response(profile,state.epoch(),state.message(),100,profile.identity(),status);
            assertEquals(1,observed.reportedAnchors());assertEquals(profile.identity(),observed.identity());
        }
    }
    @Test public void disconnectedOrFailedRequestsRemainUnknownWithoutAutomaticRetry() throws Exception {
        for(boolean disconnect:List.of(false,true)) {
            var store=new HealthChangesSessionStore(Files.createTempDirectory("changes-unknown"));var profile=profile();var state=prepared(store,profile);
            assertEquals(state,store.end(profile,UUID.randomUUID(),null,100,true));
            var ended=disconnect ? store.end(profile,state.epoch(),null,100,true)
                    : store.receipt(profile,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.FAILED));
            assertEquals(HealthChangesSessionStore.Phase.UNKNOWN,ended.phase());
            try(var status=status(1)) { assertEquals(ended,store.response(profile,state.epoch(),state.message(),100,profile.identity(),status)); }
            assertThrows(IllegalStateException.class,()->prepared(store,profile));
        }
    }
    @Test public void staleExpiryTaskCannotExpireAnotherMessageOrAdvanceFinishedState() throws Exception {
        var store=new HealthChangesSessionStore(Files.createTempDirectory("changes-expiry"));var profile=profile();var state=prepared(store,profile);
        assertEquals(state,store.end(profile,state.epoch(),UUID.randomUUID(),100000,false));
        assertEquals(state,store.end(profile,state.epoch(),state.message(),59999,false));
        assertEquals(HealthChangesSessionStore.Phase.EXPIRED,store.end(profile,state.epoch(),state.message(),60000,false).phase());
    }
    @Test public void corruptionOwnershipSymlinksAndTruncationRefuseRegeneration() throws Exception {
        for(int kind=0;kind<4;kind++) {
            Path root=Files.createTempDirectory("changes-corrupt"),file=root.resolve(PAIR+".changes");var store=new HealthChangesSessionStore(root);var profile=profile();
            prepared(store,profile);
            assertThrows(java.io.IOException.class,()->store.read(PAIR,UUID.randomUUID(),PEER,profile.identity()));
            assertThrows(java.io.IOException.class,()->store.read(PAIR,LOCAL,PEER,new NativeHealthSyncCodec.Identity(17,REGISTRY,UUID.randomUUID())));
            byte[] data=Files.readAllBytes(file);
            if(kind==0) { data[20]^=1;Files.write(file,data); }
            if(kind==1)Files.write(file,new byte[513]);
            if(kind==2) { Path saved=root.resolve("original");Files.move(file,saved);Files.createSymbolicLink(file,saved); }
            if(kind==3)Files.write(file,new byte[31]);
            assertThrows(java.io.IOException.class,()->prepared(store,profile));
        }
    }
    @Test public void nativePullWireMatchesExactFinalSessionAndDoesNotAcknowledgeOrDeleteAnything() throws Exception {
        var store=new HealthChangesSessionStore(Files.createTempDirectory("changes-wire"));var profile=profile();var state=prepared(store,profile);
        byte[] wire=NativeHealthInitialChangesCodec.encode(state);
        assertEquals(2,wire[0]);assertEquals(0,wire[1]);assertEquals(0,wire[2]);
        try(var frame=NativeHealthSyncCodec.decodePlaintext(wire,false);var envelope=frame.envelope();var changes=envelope.changeSet();var status=envelope.status()) {
            assertEquals(state.identity(),envelope.requireIdentity());assertFalse(envelope.has(9));assertFalse(envelope.has(11));
            assertEquals(state.session(),NativeHealthSyncCodec.uuid(changes.bytes(2)));assertEquals(state.startDate(),changes.doubleValue(3),0);
            assertEquals(Integer.valueOf(2),changes.int32(5));assertTrue(changes.children(1).isEmpty());assertNull(changes.bytes(4));
            assertEquals(Integer.valueOf(4),status.int32(1));assertTrue(status.children(2).isEmpty());
        }
        assertThrows(IllegalArgumentException.class,()->NativeHealthInitialChangesCodec.encode(state.phase(HealthChangesSessionStore.Phase.IDS_QUEUED)));
    }
    @Test public void invalidPreparedMetadataCannotCreateAFile() throws Exception {
        Path root=Files.createTempDirectory("changes-invalid");var store=new HealthChangesSessionStore(root);var profile=profile();
        for(double date:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->store.prepare(profile,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),date,60000));
        }
        assertNull(store.read(PAIR,LOCAL,PEER,profile.identity()));
    }
    @Test public void watchSyncIdentityDoesNotPreventReadOnlyStatusButMixedRestoreOrChangesDoes() {
        try(var child=NativeHealthSyncCodec.decodeEnvelope(new byte[]{66,2,8,1,90,0});
                var restore=NativeHealthSyncCodec.decodeEnvelope(new byte[]{66,2,8,1,74,0});
                var changes=NativeHealthSyncCodec.decodeEnvelope(new byte[]{66,2,8,1,58,0});
                var absent=NativeHealthSyncCodec.decodeEnvelope(new byte[0])) {
            assertTrue(child.has(11));assertTrue(NativeHealthInitialChangesCodec.statusOnly(child));
            assertFalse(NativeHealthInitialChangesCodec.statusOnly(restore));
            assertFalse(NativeHealthInitialChangesCodec.statusOnly(changes));
            assertFalse(NativeHealthInitialChangesCodec.statusOnly(absent));
        }
    }
}
