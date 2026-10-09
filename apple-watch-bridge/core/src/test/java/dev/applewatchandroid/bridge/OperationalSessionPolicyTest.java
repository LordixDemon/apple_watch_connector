package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class OperationalSessionPolicyTest {
    private static final String PAIR = "79105820-c9ef-4b5c-b2aa-16938715ed30";
    private static final String OTHER = "ba670763-622e-4fab-a98a-a507f65e0b75";

    @Test public void physicalConfirmationOnlyAppliesToTheSameActivatedPair() {
        var activated = PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED;
        assertTrue(OperationalSessionPolicy.mayUseOperationalMode(activated, PAIR, PAIR, false));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(activated, PAIR, OTHER, false));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(activated, PAIR, null, false));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(activated, PAIR, PAIR, true));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(
                PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED, PAIR, PAIR, false));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(null, PAIR, PAIR, false));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(activated, "bad", "bad", false));
        assertThrows(IllegalStateException.class,
                () -> OperationalSessionPolicy.requireMatchingActivatedPair(null, PAIR));
    }

    @Test public void operationalInvocationCannotAlsoPairOrRecoverSmp() {
        var parsed = HalHostArguments.parse(new String[]{"--acknowledge-target",
                HalHostArguments.TARGET, "--operational-pairing", PAIR});
        assertEquals(PAIR, parsed.operationalPairing);
        assertFalse(parsed.commit);
        assertFalse(parsed.recovery);
        for (String forbidden : new String[]{"--commit-is-paired", "--recover-stale-smp"}) {
            assertThrows(IllegalArgumentException.class, () -> HalHostArguments.parse(
                    new String[]{"--acknowledge-target", HalHostArguments.TARGET,
                            forbidden, "--operational-pairing", PAIR}));
        }
    }

    @Test public void nativeCompletionDoesNotRequireAnAdditionalOwnerCheckbox() {
        var complete = PairingSessionRecord.DurableState.OPERATIONAL_HEALTH_CONFIRMED;
        assertTrue(OperationalSessionPolicy.mayUseOperationalMode(complete, PAIR, null, false, true));
        assertTrue(OperationalSessionPolicy.mayUseOperationalMode(complete, PAIR, OTHER, false, true));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(complete, PAIR, null, true, true));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(complete, "bad", null, false, true));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(complete, PAIR, null, false, false));
        assertFalse(OperationalSessionPolicy.mayUseOperationalMode(complete, PAIR, null, false));
        for (var pending : new PairingSessionRecord.DurableState[]{
                PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                PairingSessionRecord.DurableState.IS_SETUP_CONFIRMED,
                PairingSessionRecord.DurableState.PAIRED_SYNC_COMPLETE,
                PairingSessionRecord.DurableState.SETUP_COMPLETE,
                PairingSessionRecord.DurableState.CLOCK_VISIBLE_CONFIRMED}) {
            assertFalse(OperationalSessionPolicy.mayUseOperationalMode(pending, PAIR, null, false, true));
        }
    }

    @Test public void invalidAndDuplicateInvocationOptionsAreRejected() {
        for (String[] args : new String[][]{
                null, {}, {"--acknowledge-target", "other"},
                {"--acknowledge-target", HalHostArguments.TARGET, "--operational-pairing"},
                {"--acknowledge-target", HalHostArguments.TARGET, "--operational-pairing", "1-1-1-1-1"},
                {"--acknowledge-target", HalHostArguments.TARGET, "--commit-is-paired", "--commit-is-paired"},
                {"--acknowledge-target", HalHostArguments.TARGET, "--unknown"},
                {"--acknowledge-target", HalHostArguments.TARGET,
                        "--operational-pairing", PAIR, "--operational-pairing", OTHER}}) {
            assertThrows(IllegalArgumentException.class, () -> HalHostArguments.parse(args));
        }
    }

    @Test public void setupAndRecoveryInvocationsRemainAvailableExplicitly() {
        assertTrue(HalHostArguments.parse(new String[]{"--acknowledge-target",
                HalHostArguments.TARGET, "--commit-is-paired"}).commit);
        assertTrue(HalHostArguments.parse(new String[]{"--acknowledge-target",
                HalHostArguments.TARGET, "--recover-stale-smp"}).recovery);
    }
    @Test public void opticalInvocationCannotReuseOperationalPairOrRecoverSmp() {
        var parsed = HalHostArguments.parse(new String[]{"--acknowledge-target", HalHostArguments.TARGET,
                "--commit-is-paired", "--optical-pairing"});
        assertTrue(parsed.opticalPairing);
        assertTrue(parsed.commit);
        assertNull(parsed.operationalPairing);
        for (String[] tail : new String[][]{
                {"--optical-pairing", "--optical-pairing"},
                {"--recover-stale-smp", "--optical-pairing"},
                {"--optical-pairing", "--operational-pairing", PAIR}}) {
            String[] args = new String[tail.length + 2];
            args[0] = "--acknowledge-target"; args[1] = HalHostArguments.TARGET;
            System.arraycopy(tail, 0, args, 2, tail.length);
            assertThrows(IllegalArgumentException.class, () -> HalHostArguments.parse(args));
        }
    }
}
