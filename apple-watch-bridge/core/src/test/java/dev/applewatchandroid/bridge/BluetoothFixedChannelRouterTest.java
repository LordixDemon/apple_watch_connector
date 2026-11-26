package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class BluetoothFixedChannelRouterTest {
    @Test public void attDiscoveryIsAnsweredWhileTheCallerWaitsForIke() throws Exception {
        var router = new BluetoothFixedChannelRouter();
        var server = new BluetoothLinkMaintenance.AttServer(request -> new byte[]{1,request[0],1,0,10});
        List<byte[]> replies = new ArrayList<>();
        byte[] request = {0x10,1,0,(byte)255,(byte)255,0,0x28};
        for (byte[] acl : HciCodec.buildAclL2capFragments(1,4,request,5)) {
            assertTrue(router.accept(acl,1,p -> replies.add(server.accept(p.connectionHandle,p.payload))));
        }
        assertEquals(1,replies.size());
        assertArrayEquals(new byte[]{1,0x10,1,0,10},replies.get(0));
    }

    @Test public void ikeAndSmpKeepTheirOriginalAclFramingAndBytes() throws Exception {
        var router = new BluetoothFixedChannelRouter();
        for (int cid : new int[]{0x40,0x41,6,0x3a}) {
            byte[] payload = new byte[300]; Arrays.fill(payload,(byte)cid);
            var owner = new HciCodec.AclReassembler();
            HciCodec.L2capPdu received = null;
            for (byte[] acl : HciCodec.buildAclL2capFragments(1,cid,payload,251)) {
                byte[] original = acl.clone();
                assertFalse(router.accept(acl,1,p -> fail("Owner traffic was consumed")));
                assertArrayEquals(original,acl);
                received = owner.accept(acl);
            }
            assertNotNull(received);
            assertEquals(cid,received.destinationCid);
            assertArrayEquals(payload,received.payload);
        }
    }

    @Test public void fixedPayloadIsWipedAndResetDoesNotStealLaterContinuations() throws Exception {
        var router = new BluetoothFixedChannelRouter();
        List<byte[]> delivered = new ArrayList<>();
        assertTrue(router.accept(HciCodec.buildAclL2cap(1,4,new byte[]{0x1d,8,0}),1,p -> delivered.add(p.payload)));
        assertArrayEquals(new byte[3],delivered.get(0));
        var fragments = HciCodec.buildAclL2capFragments(1,4,new byte[20],8);
        assertTrue(router.accept(fragments.get(0),1,p -> fail("Premature delivery")));
        router.reset();
        assertFalse(router.accept(fragments.get(1),1,p -> fail("Orphan continuation consumed")));
        assertFalse(router.accept(HciCodec.buildAclL2cap(2,4,new byte[]{2,23,0}),1,p -> fail("Other handle consumed")));
    }

    @Test public void leSignalingAlsoWorksBeforeNormalRegistration() throws Exception {
        var router = new BluetoothFixedChannelRouter();
        List<byte[]> replies = new ArrayList<>();
        byte[] request = {0x12,7,8,0,6,0,12,0,0,0,100,0};
        assertTrue(router.accept(HciCodec.buildAclL2cap(1,5,request),1,p -> replies.addAll(BluetoothLinkMaintenance.leSignalingReplies(p.payload))));
        assertArrayEquals(new byte[]{0x13,7,2,0,1,0},replies.get(0));
    }

    @Test public void attClientResponsesPassThroughWhenClientActiveWhileServerRequestsAreAnswered() throws Exception {
        var router = new BluetoothFixedChannelRouter();
        var server = new BluetoothLinkMaintenance.AttServer(request -> new byte[]{1, request[0], 1, 0, 10});
        router.setAttClientActive(true);

        // Multi-fragment ATT Read By Group Type Response (0x11) must NOT be swallowed by fixed router.
        byte[] attClientResponse = new byte[22];
        attClientResponse[0] = 0x11;
        attClientResponse[1] = 20;
        attClientResponse[2] = 0x20;
        attClientResponse[4] = 0x24;
        var clientOwner = new HciCodec.AclReassembler();
        HciCodec.L2capPdu received = null;
        for (byte[] acl : HciCodec.buildAclL2capFragments(1, BluetoothLinkMaintenance.ATT_CID, attClientResponse, 10)) {
            assertFalse("ATT client response fragment must pass through to GattAttClient",
                    router.accept(acl, 1, p -> fail("Client response was swallowed by AttServer")));
            received = clientOwner.accept(acl);
        }
        assertNotNull(received);
        assertEquals(BluetoothLinkMaintenance.ATT_CID, received.destinationCid);
        assertArrayEquals(attClientResponse, received.payload);

        // Single-fragment ATT Exchange MTU Response (0x03) and Write Response (0x13) also pass through.
        assertFalse(router.accept(HciCodec.buildAclL2cap(1, 4, new byte[]{0x03, (byte) 185, 0x00}), 1,
                p -> fail("MTU response swallowed")));
        assertFalse(router.accept(HciCodec.buildAclL2cap(1, 4, new byte[]{0x13}), 1,
                p -> fail("Write response swallowed")));

        // Watch-initiated ATT Server request (0x02 Exchange MTU Request) is STILL answered concurrently!
        List<byte[]> serverReplies = new ArrayList<>();
        assertTrue(router.accept(HciCodec.buildAclL2cap(1, 4, new byte[]{0x02, (byte) 185, 0x00}), 1,
                p -> serverReplies.add(server.accept(p.connectionHandle, p.payload))));
        assertEquals(1, serverReplies.size());
        assertArrayEquals(new byte[]{0x03, (byte) 251, 0x00}, serverReplies.get(0));

        // When client scope closes, stray late ATT responses are once again swallowed by fixed router.
        router.setAttClientActive(false);
        assertTrue(router.accept(HciCodec.buildAclL2cap(1, 4, new byte[]{0x13}), 1,
                p -> assertNull(server.accept(p.connectionHandle, p.payload))));
    }
}
