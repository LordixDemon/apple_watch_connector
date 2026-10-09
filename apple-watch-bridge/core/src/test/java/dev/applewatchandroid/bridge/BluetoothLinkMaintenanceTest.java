package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public final class BluetoothLinkMaintenanceTest {
    @Test public void indicationIsConfirmedWithoutExposingOrProcessingItsValue() throws Exception {
        var server = new BluetoothLinkMaintenance.AttServer(request -> { throw new AssertionError("not a database read"); });
        assertArrayEquals(new byte[]{0x1e}, server.accept(1, new byte[]{0x1d, 8, 0, 1, 0, (byte)255, (byte)255}));
        assertNull(server.accept(1, new byte[]{0x1b, 8, 0, 99}));
        assertNull(server.accept(1, new byte[]{0x1e}));
    }

    @Test public void lateResponsesNeverLaunchDiscoveryOrTriggerAnErrorResponseLoop() throws Exception {
        var server = new BluetoothLinkMaintenance.AttServer(request -> { throw new AssertionError("late response reached legacy code"); });
        for (int opcode : new int[]{1,3,5,7,9,11,13,15,17,19,23,25,27,30,0xd2}) {
            assertNull(server.accept(1, new byte[]{(byte)opcode, 1, 0, 0}));
        }
    }

    @Test public void mtuExchangeAndDiscoveryResponsesRespectTheNegotiatedLimit() throws Exception {
        byte[] groups = new byte[26]; groups[0] = 0x11; groups[1] = 6;
        var server = new BluetoothLinkMaintenance.AttServer(request -> groups.clone());
        byte[] request = {0x10,1,0,(byte)255,(byte)255,0,0x28};
        assertEquals(20, server.accept(1, request).length); // Three complete 6-byte entries fit in MTU23.
        assertArrayEquals(new byte[]{3,(byte)251,0}, server.accept(1, new byte[]{2,(byte)251,0}));
        assertEquals(26, server.accept(1, request).length);
        server.reset();
        assertEquals(20, server.accept(1, request).length);
        assertArrayEquals(new byte[]{1,2,0,0,4}, server.accept(1, new byte[]{2,1,0}));
    }

    @Test public void cccdWriteIsAcknowledgedOnlyAfterValidationAndIsReadable() throws Exception {
        var server = new BluetoothLinkMaintenance.AttServer(request -> { throw new AssertionError("CCCD handled locally"); });
        assertArrayEquals(new byte[]{0x13}, server.accept(1,new byte[]{0x12,9,0,2,0}));
        assertArrayEquals(new byte[]{0x0b,2,0}, server.accept(1,new byte[]{0x0a,9,0}));
        assertArrayEquals(new byte[]{1,0x12,9,0,0x13}, server.accept(1,new byte[]{0x12,9,0,3,0}));
        assertArrayEquals(new byte[]{1,0x12,8,0,3}, server.accept(1,new byte[]{0x12,8,0,1,0}));
        assertNull(server.accept(1,new byte[]{0x52,20,0,1,0}));
        assertArrayEquals(new byte[]{0x0b,1,0}, server.accept(1,new byte[]{0x0a,20,0}));
        assertArrayEquals(new byte[]{0x0b,0,0}, server.accept(2,new byte[]{0x0a,9,0}));
    }

    @Test public void malformedAndUnsupportedRequestsReceiveBoundedErrors() throws Exception {
        var server = new BluetoothLinkMaintenance.AttServer(request -> { throw new AssertionError("invalid request delegated"); });
        assertArrayEquals(new byte[]{1,4,1,0,4}, server.accept(1,new byte[]{4,1,0}));
        assertArrayEquals(new byte[]{1,8,0,0,1}, server.accept(1,new byte[]{8,0,0,3,0,3,0x28}));
        assertArrayEquals(new byte[]{1,0x16,7,0,6}, server.accept(1,new byte[]{0x16,7,0,0,0}));
        assertNull(server.accept(1,new byte[]{0x1d}));
        assertNull(server.accept(1,new byte[0]));
    }

    @Test public void leParameterRequestReceivesExplicitRejectionWithItsIdentifier() {
        byte[] request = {0x12,0x37,8,0,6,0,12,0,0,0,100,0};
        var replies = BluetoothLinkMaintenance.leSignalingReplies(request);
        assertEquals(1,replies.size());
        assertArrayEquals(new byte[]{0x13,0x37,2,0,1,0},replies.get(0));
        assertTrue(BluetoothLinkMaintenance.leSignalingReplies(replies.get(0)).isEmpty());
    }

    @Test public void signalingRepliesAndCreditsDoNotCreateRejectLoops() {
        for (int opcode : new int[]{1,7,0x13,0x15,0x16,0x18,0x1a}) {
            assertTrue(BluetoothLinkMaintenance.leSignalingReplies(new byte[]{(byte)opcode,3,0,0}).isEmpty());
        }
        var replies = BluetoothLinkMaintenance.leSignalingReplies(new byte[]{8,9,2,0,1,2,0x14,10,0,0});
        assertEquals(2,replies.size());
        assertArrayEquals(new byte[]{1,9,2,0,0,0},replies.get(0));
        assertArrayEquals(new byte[]{1,10,2,0,0,0},replies.get(1));
        assertTrue(BluetoothLinkMaintenance.leSignalingReplies(new byte[]{0x12,1,8,0}).isEmpty());
        assertTrue(BluetoothLinkMaintenance.leSignalingReplies(new byte[1025]).isEmpty());
    }

    @Test public void controllerParameterRequestIsAnsweredOnlyForTheActiveHandle() {
        byte[] event = {0x3e,11,6,0x37,0,6,0,12,0,0,0,100,0};
        assertArrayEquals(new byte[]{0x21,0x20,3,0x37,0,0x3b},
                BluetoothLinkMaintenance.remoteParameterRequestRejection(event,0x37));
        assertNull(BluetoothLinkMaintenance.remoteParameterRequestRejection(event,0x38));
        assertNull(BluetoothLinkMaintenance.remoteParameterRequestRejection(Arrays.copyOf(event,12),0x37));
        event[2] = 5;
        assertNull(BluetoothLinkMaintenance.remoteParameterRequestRejection(event,0x37));
    }
}
