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
}
