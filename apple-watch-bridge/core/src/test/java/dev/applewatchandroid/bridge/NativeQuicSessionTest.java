package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NativeQuicSessionTest {
    @Test public void consumesOnlyEspAuthenticatedDiscoveryTupleAndReleasesOnce() {
        byte[] local = new byte[16], remote = new byte[16]; local[15] = 1; remote[15] = 2;
        List<byte[]> received = new ArrayList<>(); int[] closes = {0};
        var backend = new NativeQuicSession.Backend() {
            public void receive(byte[] value) { received.add(value.clone()); }
            public byte[][] poll() { return new byte[0][]; }
            public String state() { return "connecting"; }
            public void close() { closes[0]++; }
        };
        var session = new NativeQuicSession(backend, OrdinaryIkeAuth.DataClass.CLASS_D,
                local, remote, 50000, 65275, ignored -> {});
        byte[] wire = Ipv6UdpPacketCodec.encode(remote, local, 65275, 50000, new byte[]{7});
        assertFalse(session.accept(new NormalLinkPipeSession.DeliveredIp(OrdinaryIkeAuth.DataClass.CLASS_D, false, wire)));
        assertFalse(session.accept(new NormalLinkPipeSession.DeliveredIp(OrdinaryIkeAuth.DataClass.CLASS_C, true, wire)));
        assertFalse(session.accept(new NormalLinkPipeSession.DeliveredIp(OrdinaryIkeAuth.DataClass.CLASS_D, true,
            Ipv6UdpPacketCodec.encode(remote, local, 65275, 50001, new byte[]{7}))));
        assertTrue(session.accept(new NormalLinkPipeSession.DeliveredIp(OrdinaryIkeAuth.DataClass.CLASS_D, true, wire)));
        assertEquals(1, received.size()); assertArrayEquals(new byte[]{7}, received.get(0));
        session.close(); session.close(); assertEquals(1, closes[0]);
        assertFalse(session.accept(new NormalLinkPipeSession.DeliveredIp(OrdinaryIkeAuth.DataClass.CLASS_D, true, wire)));
    }
}
