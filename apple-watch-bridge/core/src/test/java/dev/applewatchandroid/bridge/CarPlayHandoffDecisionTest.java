package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CarPlayHandoffDecisionTest {
    @Test
    public void directHalRequiresCleanStockOffState() {
        assertEquals(
                CarPlayHandoffDecision.Mode.DIRECT_HAL,
                CarPlayHandoffDecision.decide(
                        true,
                        false,
                        false,
                        false,
                        false,
                        false));
        assertEquals(
                CarPlayHandoffDecision.Mode.REFUSE,
                CarPlayHandoffDecision.decide(
                        true,
                        false,
                        true,
                        true,
                        true,
                        true));
    }

    @Test
    public void cooperativeModeRequiresEveryCarPlayReadinessGate() {
        assertEquals(
                CarPlayHandoffDecision.Mode.COOPERATIVE_CARPLAY,
                CarPlayHandoffDecision.decide(
                        false,
                        true,
                        true,
                        true,
                        true,
                        false));
        assertEquals(
                CarPlayHandoffDecision.Mode.REFUSE,
                CarPlayHandoffDecision.decide(
                        false,
                        true,
                        true,
                        false,
                        true,
                        false));
        assertEquals(
                CarPlayHandoffDecision.Mode.REFUSE,
                CarPlayHandoffDecision.decide(
                        false,
                        true,
                        false,
                        true,
                        true,
                        false));
    }

    @Test
    public void transitionalBluetoothStateIsAlwaysRefused() {
        assertEquals(
                CarPlayHandoffDecision.Mode.REFUSE,
                CarPlayHandoffDecision.decide(
                        false,
                        false,
                        true,
                        true,
                        true,
                        false));
    }
}
