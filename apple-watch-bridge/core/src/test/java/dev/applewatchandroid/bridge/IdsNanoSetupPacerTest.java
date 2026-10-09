package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IdsNanoSetupPacerTest {
    @Test
    public void handshakeBurstIsNotQuiet() {
        assertFalse(
                IdsNanoSetupPacer.isErtmQuiet(
                        8));
        assertFalse(
                IdsNanoSetupPacer.isErtmQuiet(
                        2));
        assertTrue(
                IdsNanoSetupPacer.isErtmQuiet(
                        1));
        assertTrue(
                IdsNanoSetupPacer.isErtmQuiet(
                        0));
    }

    @Test
    public void tcpUnaBlocksCheckEvenWhenErtmIsQuiet() {
        assertFalse(
                IdsNanoSetupPacer.isTransportQuiet(
                        0,
                        117L));
        assertFalse(
                IdsNanoSetupPacer.isTransportQuiet(
                        1,
                        9L));
        assertFalse(
                IdsNanoSetupPacer.isTransportQuiet(
                        8,
                        0L));
        assertTrue(
                IdsNanoSetupPacer.isTransportQuiet(
                        1,
                        0L));
        assertTrue(
                IdsNanoSetupPacer.isTransportQuiet(
                        0,
                        0L));
        assertFalse(
                IdsNanoSetupPacer.shouldSendCheck(
                        IdsNanoSetupPacer.isTransportQuiet(
                                0,
                                117L),
                        536L));
    }

    @Test
    public void quietWindowWithTcpUnaRetransmitsPayloadNotCheck() {
        assertFalse(
                IdsNanoSetupPacer.shouldRetransmitTcpDuringHold(
                        false,
                        27L));
        assertFalse(
                IdsNanoSetupPacer.shouldRetransmitTcpDuringHold(
                        true,
                        0L));
        assertTrue(
                IdsNanoSetupPacer.shouldRetransmitTcpDuringHold(
                        true,
                        27L));
        assertTrue(
                IdsNanoSetupPacer.shouldRetransmitTcpDuringHold(
                        true,
                        9L));
    }

    @Test
    public void checkSendsOnlyOnAQuietWindowAfterTheMinimumWait() {
        assertFalse(
                IdsNanoSetupPacer.shouldSendCheck(
                        false,
                        10L));
        assertFalse(
                IdsNanoSetupPacer.shouldSendCheck(
                        true,
                        10L));
        assertTrue(
                IdsNanoSetupPacer.shouldSendCheck(
                        true,
                        IdsNanoSetupPacer.MIN_WAIT_MS));
        assertFalse(
                IdsNanoSetupPacer.shouldSendCheck(
                        false,
                        421L));
        assertFalse(
                IdsNanoSetupPacer.shouldSendCheck(
                        false,
                        8_000L));
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsNanoSetupPacer.shouldSendCheck(
                        false,
                        -1L));
    }
}
