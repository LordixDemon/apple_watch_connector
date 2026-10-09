package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.UUID;
import org.junit.Test;

public final class NativeHealthIncomingPolicyTest {
    private static final UUID PAIR=UUID.fromString("00000000-0000-0000-0000-000000000001"),
            LOCAL=UUID.fromString("00000000-0000-0000-0000-000000000002"),
            PEER=UUID.fromString("B59F434C-081D-55BD-AD10-DE8FB3B8D77E"),
            HEALTH=UUID.fromString("00000000-0000-0000-0000-000000000004"),
            RESTORE_EPOCH=UUID.fromString("00000000-0000-0000-0000-000000000005"),
            CURRENT_EPOCH=UUID.fromString("00000000-0000-0000-0000-000000000006"),
            MESSAGE=UUID.fromString("00000000-0000-4000-8000-000000000007");
    private static final NativeHealthSyncCodec.Identity IDENTITY=new NativeHealthSyncCodec.Identity(17,PAIR,HEALTH);
    private static HealthSyncStateStore.State profile(HealthSyncStateStore.Phase phase) {
        boolean none=phase==HealthSyncStateStore.Phase.NONE;
        return new HealthSyncStateStore.State(PAIR,LOCAL,PEER,PAIR,17,PAIR,HEALTH,HealthSyncStateStore.sourceFor(PAIR),
                1,phase,none ? null : MESSAGE,none ? null : MESSAGE,none ? null : RESTORE_EPOCH,none ? 0 : 1,none ? 0 : 1000);
    }
    private static NativeHealthIncomingPolicy.Context context() {
        return new NativeHealthIncomingPolicy.Context(PAIR,LOCAL,PEER,CURRENT_EPOCH,"23S303");
    }
    private static NativeHealthIncomingPolicy.Message message(UUID pair,UUID epoch,int id,boolean response,
                                                               NativeHealthSyncCodec.Identity identity,boolean changes,boolean status) {
        return new NativeHealthIncomingPolicy.Message(pair,epoch,id,response,identity,changes,status);
    }
    private static NativeHealthIncomingPolicy.Result inspect(NativeHealthIncomingPolicy.Message message) {
        return NativeHealthIncomingPolicy.inspect(context(),profile(HealthSyncStateStore.Phase.REMOTE_FINISHED),message);
    }
    private static NativeHealthIncomingPolicy.Message changes() {
        return message(PAIR,CURRENT_EPOCH,2,false,IDENTITY,true,false);
    }
    @Test public void matchedChangeAndSpeculativeRequestsBecomeEligibleAcrossFinishedRestoreEpoch() {
        for(int id:new int[]{2,7}) {
            var result=inspect(message(PAIR,CURRENT_EPOCH,id,false,IDENTITY,true,false));
            assertTrue(result.eligible());assertEquals(NativeHealthIncomingPolicy.Reason.MATCHED,result.reason());
            assertTrue(result.restoreComplete());assertTrue(result.protocolMatches());assertTrue(result.identityMatches());
        }
    }
    @Test public void everyUnfinishedRestorePhaseHoldsRatherThanTreatingTransportAckAsFinish() {
        for(var phase:HealthSyncStateStore.Phase.values())if(phase!=HealthSyncStateStore.Phase.REMOTE_FINISHED) {
            var result=NativeHealthIncomingPolicy.inspect(context(),profile(phase),changes());
            assertEquals(NativeHealthIncomingPolicy.Reason.RESTORE_INCOMPLETE,result.reason());
            assertFalse(result.eligible());assertFalse(result.restoreComplete());
        }
    }
    @Test public void bothPairingIdentitiesMustMatchAndAreNeverAdoptedFromMessage() {
        for(var identity:new NativeHealthSyncCodec.Identity[]{new NativeHealthSyncCodec.Identity(17,PEER,HEALTH),
                new NativeHealthSyncCodec.Identity(17,PAIR,LOCAL)}) {
            var result=inspect(message(PAIR,CURRENT_EPOCH,2,false,identity,true,false));
            assertEquals(NativeHealthIncomingPolicy.Reason.IDENTITY_MISMATCH,result.reason());
            assertFalse(result.eligible());assertFalse(result.identityMatches());
        }
    }
    @Test public void oldReplayAndDisconnectedWorkerNeverBecomeFreshEligibleTransactions() {
        var replay=inspect(message(PAIR,RESTORE_EPOCH,7,false,IDENTITY,true,false));
        assertEquals(NativeHealthIncomingPolicy.Reason.STALE_EPOCH,replay.reason());assertTrue(replay.identityMatches());
        var disconnected=new NativeHealthIncomingPolicy.Context(PAIR,LOCAL,PEER,null,"23S303");
        assertEquals(NativeHealthIncomingPolicy.Reason.STALE_EPOCH,
                NativeHealthIncomingPolicy.inspect(disconnected,profile(HealthSyncStateStore.Phase.REMOTE_FINISHED),changes()).reason());
    }
    @Test public void responsesAndOtherRequestsAreSeparatePipelines() {
        for(int id:new int[]{1,3,4,5,6,8,65535})
            assertEquals(NativeHealthIncomingPolicy.Reason.UNSUPPORTED_DIRECTION,
                    inspect(message(PAIR,CURRENT_EPOCH,id,false,IDENTITY,true,false)).reason());
        assertEquals(NativeHealthIncomingPolicy.Reason.UNSUPPORTED_DIRECTION,
                inspect(message(PAIR,CURRENT_EPOCH,2,true,IDENTITY,true,false)).reason());
    }
    @Test public void foreignPairAndWrongLocalOrPeerBindingsNeverUseCurrentProfile() {
        assertEquals(NativeHealthIncomingPolicy.Reason.FOREIGN_PAIR,
                inspect(message(LOCAL,CURRENT_EPOCH,2,false,IDENTITY,true,false)).reason());
        for(var context:new NativeHealthIncomingPolicy.Context[]{
                new NativeHealthIncomingPolicy.Context(LOCAL,LOCAL,PEER,CURRENT_EPOCH,"23S303"),
                new NativeHealthIncomingPolicy.Context(PAIR,HEALTH,PEER,CURRENT_EPOCH,"23S303"),
                new NativeHealthIncomingPolicy.Context(PAIR,LOCAL,HEALTH,CURRENT_EPOCH,"23S303")})
            assertEquals(NativeHealthIncomingPolicy.Reason.FOREIGN_PAIR,
                    NativeHealthIncomingPolicy.inspect(context,profile(HealthSyncStateStore.Phase.REMOTE_FINISHED),changes()).reason());
    }
    @Test public void unknownBuildAndUnavailableProfileStayHeld() {
        assertEquals(NativeHealthIncomingPolicy.Reason.PROFILE_UNAVAILABLE,
                NativeHealthIncomingPolicy.inspect(context(),null,changes()).reason());
        var unknown=new NativeHealthIncomingPolicy.Context(PAIR,LOCAL,PEER,CURRENT_EPOCH,"23S304");
        assertEquals(NativeHealthIncomingPolicy.Reason.FOREIGN_BUILD,
                NativeHealthIncomingPolicy.inspect(unknown,profile(HealthSyncStateStore.Phase.REMOTE_FINISHED),changes()).reason());
    }
    @Test public void oldFutureNegativeAndUnsignedProtocolVersionsDoNotFabricateNegotiatedStores() {
        for(int version:new int[]{0,16,18,-1,Integer.MIN_VALUE}) {
            var result=inspect(message(PAIR,CURRENT_EPOCH,2,false,new NativeHealthSyncCodec.Identity(version,PAIR,HEALTH),true,false));
            assertEquals(NativeHealthIncomingPolicy.Reason.PROTOCOL_VERSION,result.reason());assertFalse(result.protocolMatches());
        }
    }
    @Test public void nativeEmptyRequestSkipsIdentityValidationButStillCannotAdvanceData() {
        var mismatch=new NativeHealthSyncCodec.Identity(17,LOCAL,PEER);
        var result=inspect(message(PAIR,CURRENT_EPOCH,2,false,mismatch,false,false));
        assertEquals(NativeHealthIncomingPolicy.Outcome.EMPTY,result.outcome());assertFalse(result.eligible());
        assertFalse(result.identityMatches());
        assertEquals(NativeHealthIncomingPolicy.Reason.IDENTITY_MISMATCH,
                inspect(message(PAIR,CURRENT_EPOCH,2,false,mismatch,false,true)).reason());
        assertTrue(inspect(message(PAIR,CURRENT_EPOCH,2,false,IDENTITY,false,true)).eligible());
    }
    @Test public void missingInputIsRejectedAndDiagnosticsContainNoIdentities() {
        assertThrows(IllegalArgumentException.class,()->NativeHealthIncomingPolicy.inspect(null,null,changes()));
        assertThrows(IllegalArgumentException.class,()->NativeHealthIncomingPolicy.inspect(context(),null,null));
        for(String text:new String[]{context().toString(),changes().toString(),inspect(changes()).toString()})
            for(UUID id:new UUID[]{PAIR,LOCAL,PEER,HEALTH,RESTORE_EPOCH,CURRENT_EPOCH})assertFalse(text.contains(id.toString()));
    }
}
