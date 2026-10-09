package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.HexFormat;
import org.junit.Test;

public final class NativeHealthReceivePolicyTest {
    private static final String QUANTITY="0802 4a021004 ";
    private static byte[] hex(String wire) { return HexFormat.of().parseHex(wire.replace(" ","")); }
    private static NativeHealthReceivePolicy.Plan plan(String wire,Long sequence,NativeHealthReceivePolicy.ReceivedAnchors anchors) {
        try(var change=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE,hex(wire))) {
            return NativeHealthReceivePolicy.prepare(change,"23S303",sequence,anchors);
        }
    }
    private static NativeHealthReceivePolicy.Plan range(String wire,long last) { return plan(QUANTITY+wire,0L,entity->last); }
    @Test public void finalChunkUsesReceived3AndValidated5OnlyAfterDataApplied() {
        var result=range("1000 180a 3001 3800 520408001000",0);
        assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,result.outcome());
        assertEquals(Long.valueOf(0),result.afterDataApplied().expectedSequence());
        assertEquals(Long.valueOf(10),result.afterDataApplied().receivedAnchor());
        assertEquals(Long.valueOf(10),result.afterDataApplied().validatedAnchor());
        assertEquals(Long.valueOf(0),result.version().minimum());assertEquals(Long.valueOf(0),result.version().current());
    }
    @Test public void intermediateSequenceNeverAdvancesEitherAnchorEvenWithDoneAbsent() {
        var absent=range("1000 180a 3800",0);var falseDone=range("1000 180a 3000 3800",0);
        for(var result:new NativeHealthReceivePolicy.Plan[]{absent,falseDone}) {
            assertEquals(Long.valueOf(1),result.afterDataApplied().expectedSequence());
            assertNull(result.afterDataApplied().receivedAnchor());assertNull(result.afterDataApplied().validatedAnchor());
        }
    }
    @Test public void sequenceAbsentUpdatesAnchorsDespiteAbsentDoneButDoesNotInventSequence() {
        var result=range("1000 180a",0);
        assertNull(result.afterDataApplied().expectedSequence());
        assertEquals(Long.valueOf(10),result.afterDataApplied().receivedAnchor());
        assertFalse(result.version().present());assertNull(result.version().current());
        assertEquals(0,result.version().projectedCurrent());
    }
    @Test public void zeroSequenceRestartsAnyExpectedSequenceAndNonzeroMustMatch() {
        assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,plan(QUANTITY+"4001 3800",99L,e->null).outcome());
        var mismatch=plan(QUANTITY+"4001 3802",1L,e->null);
        assertEquals(NativeHealthReceivePolicy.Reason.OUT_OF_ORDER,mismatch.reason());assertEquals(Integer.valueOf(0x57a),mismatch.nativeError());
        assertNull(mismatch.afterDataApplied());
        assertEquals(NativeHealthReceivePolicy.Reason.SEQUENCE_STATE_UNAVAILABLE,plan(QUANTITY+"4001 3802",null,e->null).reason());
        assertEquals(Long.valueOf(3),plan(QUANTITY+"4001 3802",2L,e->null).afterDataApplied().expectedSequence());
    }
    @Test public void fullWidthSequenceUsesNativeSignedBitsAndAddWrap() {
        var max=plan(QUANTITY+"4001 38ffffffffffffffff7f",Long.MAX_VALUE,e->null);
        assertEquals(Long.valueOf(Long.MIN_VALUE),max.afterDataApplied().expectedSequence());
        var negative=plan(QUANTITY+"4001 38ffffffffffffffffff01",-1L,e->null);
        assertEquals(Long.valueOf(0),negative.afterDataApplied().expectedSequence());
    }
    @Test public void speculativeSupportedBranchesSkipAllAnchorLookupsAndNeverAdvanceAnchors() {
        for(String wire:new String[]{QUANTITY,"0801 4a021002 ","0810 4a021010 ","0811 4a021011 "}) {
            var result=plan(wire+"4001 3001 3800 2a02080a",null,e->{throw new AssertionError("Native speculative branch queried anchors");});
            assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,result.outcome());
            assertEquals(Long.valueOf(0),result.afterDataApplied().expectedSequence());
            assertNull(result.afterDataApplied().receivedAnchor());assertNull(result.afterDataApplied().validatedAnchor());
        }
    }
    @Test public void sourceAndDeviceHaveNoNativeSpeculativeSelectorAndRejectWithCode100() {
        for(String wire:new String[]{"080a 4a02100b ","080c 4a02100d "}) {
            var result=plan(wire+"4001",null,e->{throw new AssertionError("Unsupported speculative branch queried anchors");});
            assertEquals(NativeHealthReceivePolicy.Reason.UNSUPPORTED_SPECULATIVE,result.reason());
            assertEquals(Integer.valueOf(100),result.nativeError());assertNull(result.afterDataApplied());
        }
    }
    @Test public void unavailableReceivedStateIsHeldAndNeverFilledWithRemoteReportedAnchors() {
        var result=plan(QUANTITY+"1000 180a",null,e->null);
        assertEquals(NativeHealthReceivePolicy.Outcome.HELD,result.outcome());
        assertEquals(NativeHealthReceivePolicy.Reason.RECEIVED_STATE_UNAVAILABLE,result.reason());assertNull(result.nativeError());
    }
    @Test public void coveredRangeIsStrictOnStartAndReturnsNoSequenceOrAnchorEffect() {
        var covered=range("1000 180a 3001 3800",10);
        assertEquals(NativeHealthReceivePolicy.Outcome.ALREADY_APPLIED,covered.outcome());assertNull(covered.afterDataApplied());
        assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,range("100a 180a",10).outcome());
        assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,range("1000 180b",10).outcome());
        assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,range("1000 1800",0).outcome());
    }
    @Test public void missingNegativeReversedAndGappedRangesRejectWithNativeAnchorError1400() {
        for(String wire:new String[]{"1000","1800","10ffffffffffffffffff01 1800","100a 1809","100b 180c"}) {
            var result=range(wire,10);assertEquals(NativeHealthReceivePolicy.Outcome.REJECTED,result.outcome());
            assertEquals(Integer.valueOf(0x578),result.nativeError());assertNull(result.afterDataApplied());
        }
        assertEquals(NativeHealthReceivePolicy.Reason.INVALID_LAST_ANCHOR,range("1000 180a",-1).reason());
    }
    @Test public void dependenciesRequireReceivedRatherThanValidatedAnchorsAndEqualIsEnough() {
        String wire=QUANTITY+"1000 180a 2a08080a10071a02100b"; // Source dependency7.
        var result=plan(wire,null,e->e==NativeHealthReceivePolicy.Entity.SOURCE ? 6L : 0L);
        assertEquals(NativeHealthReceivePolicy.Reason.DEPENDENCY_GAP,result.reason());
        result=plan(wire,null,e->e==NativeHealthReceivePolicy.Entity.SOURCE ? 7L : 0L);
        assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,result.outcome());
    }
    @Test public void duplicateRequiredEntityRetainsLastNativeSetAnchorValue() {
        String wire=QUANTITY+"1000 180a 2a04080a1007 2a04080a1005";
        assertEquals(NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION,plan(wire,null,e->e==NativeHealthReceivePolicy.Entity.SOURCE ? 5L : 0L).outcome());
        wire=QUANTITY+"1000 180a 2a04080a1005 2a04080a1007";
        assertEquals(NativeHealthReceivePolicy.Reason.DEPENDENCY_GAP,plan(wire,null,e->e==NativeHealthReceivePolicy.Entity.SOURCE ? 5L : 0L).reason());
    }
    @Test public void malformedRequiredMapIsCheckedBeforeCoveredRange() {
        for(String dependency:new String[]{"2a02080a","2a0d080a10ffffffffffffffffff01"}) {
            var result=range("1000 180a "+dependency,10);
            assertEquals(NativeHealthReceivePolicy.Reason.INVALID_REQUIRED_MAP,result.reason());
        }
    }
    @Test public void higherRemoteVersionUpdatesReceivedButDoesNotValidateThatVersion() {
        var result=range("1000 180a 520408001001",0);
        assertEquals(Long.valueOf(10),result.afterDataApplied().receivedAnchor());assertNull(result.afterDataApplied().validatedAnchor());
        // Preserve uint32 bits; native validated comparison itself uses signed w-registers.
        result=range("1000 180a 520610ffffffff0f",0);
        assertEquals(Long.valueOf(4294967295L),result.version().current());
        assertEquals(Long.valueOf(10),result.afterDataApplied().validatedAnchor());
    }
    @Test public void unknownNamespaceEntityMalformedVersionOrForeignBuildHoldWithoutFallback() {
        for(String wire:new String[]{"0802 4a06108480808010 4001","0802 4a040a001004 4001",QUANTITY+"4001 52021080",QUANTITY+"4001 52021800"}) {
            assertEquals(NativeHealthReceivePolicy.Outcome.HELD,plan(wire,null,e->null).outcome());
        }
        try(var change=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE,hex(QUANTITY+"4001"))) {
            assertEquals(NativeHealthReceivePolicy.Reason.FOREIGN_BUILD,NativeHealthReceivePolicy.prepare(change,"23S304",null,e->null).reason());
        }
    }
    @Test public void unsignedVersionRangeRejectsOverflowDuplicateWrongWireAndPreservesAbsence() {
        try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.VERSION_RANGE,hex("10ffffffff0f"))) {
            assertNull(node.uint32(1));assertEquals(Long.valueOf(4294967295L),node.uint32(2));
            assertThrows(IllegalArgumentException.class,()->node.int32(2));
        }
        for(String wire:new String[]{"108080808010","10ffffffffffffffffff01","10001000","1200"})
            assertThrows(IllegalArgumentException.class,()->NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.VERSION_RANGE,hex(wire)));
    }
    @Test public void plansAndEffectsNeverExposeSequencesAnchorsVersionsInStringification() {
        var result=range("1000 180a 3001 3800",0);
        assertEquals("Health receive plan (values withheld)",result.toString());
        assertEquals("Post-apply Health effect (values withheld)",result.afterDataApplied().toString());
        assertEquals("Native Health version (values withheld)",result.version().toString());
    }
}
