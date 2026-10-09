package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class LinkDirectorAnnouncementTest {
    @Test
    public void bothRolesAnnounceHelloThenStateAndAcceptDelayedDuplicateResponses() {
        try (Pair pair = new Pair()) {
            for (int role = 0; role < 2; role++) {
                OrdinaryClassDSession sender = role == 0 ? pair.initiator : pair.responder;
                OrdinaryClassDSession peer = role == 0 ? pair.responder : pair.initiator;
                List<String> log = new ArrayList<>();
                sender.enableLinkDirectorAnnouncements(100 + role * 10, log::add);
                byte[] hello = packet(sender.pollLinkDirectorAnnouncement(1_000));
                assertNull(sender.pollLinkDirectorAnnouncement(1_001));
                byte[] helloAck = packet(peer.acceptInboundIke(hello));
                assertTrue(peer.consumeLinkDirectorObservation().contains("type=1 length=0"));
                assertNull(sender.acceptInboundIke(helloAck));
                byte[] state = packet(sender.pollLinkDirectorAnnouncement(1_002));
                assertNull(sender.acceptInboundIke(helloAck));
                byte[] stateAck = packet(peer.acceptInboundIke(state));
                assertTrue(peer.consumeLinkDirectorObservation().contains("type=6 length=1"));
                assertNull(sender.acceptInboundIke(stateAck));
                assertNull(sender.acceptInboundIke(helloAck));
                assertNull(sender.pollLinkDirectorAnnouncement(100_000));
                assertEquals(2, log.stream().filter(v -> v.startsWith("LINK DIRECTOR TX:")).count());
                assertEquals(2, log.stream().filter(v -> v.startsWith("LINK DIRECTOR ACK:")).count());
                assertTrue(sender.established());
                wipe(hello, helloAck, state, stateAck);
            }
        }
    }

    @Test
    public void droppedAckRetransmitsSameCiphertextWithoutSkippingState() {
        try (Pair pair = new Pair()) {
            pair.initiator.enableLinkDirectorAnnouncements(123, null);
            byte[] first = packet(pair.initiator.pollLinkDirectorAnnouncement(50));
            byte[] droppedAck = packet(pair.responder.acceptInboundIke(first));
            assertNull(pair.initiator.pollLinkDirectorAnnouncement(10_049));
            byte[] retry = packet(pair.initiator.pollLinkDirectorAnnouncement(10_050));
            assertArrayEquals(first, retry);
            byte[] retryAck = packet(pair.responder.acceptInboundIke(retry));
            assertArrayEquals(droppedAck, retryAck);
            assertTrue(pair.responder.lastInboundWasRetransmission());
            assertNull(pair.initiator.acceptInboundIke(retryAck));
            byte[] state = packet(pair.initiator.pollLinkDirectorAnnouncement(10_051));
            byte[] stateAck = packet(pair.responder.acceptInboundIke(state));
            assertNull(pair.initiator.acceptInboundIke(stateAck));
            assertNull(pair.initiator.pollLinkDirectorAnnouncement(20_051));
            wipe(first, droppedAck, retry, retryAck, state, stateAck);
        }
    }

    @Test
    public void serializesClassCUnlockAndLinkDirectorRequests() {
        try (Pair pair = new Pair()) {
            pair.initiator.enableLinkDirectorAnnouncements(123, null);
            byte[] unlock = packet(pair.initiator.announceLocalClassCUnlocked());
            assertNull(pair.initiator.pollLinkDirectorAnnouncement(0));
            byte[] unlockAck = packet(pair.responder.acceptInboundIke(unlock));
            assertNull(pair.initiator.acceptInboundIke(unlockAck));
            byte[] hello = packet(pair.initiator.pollLinkDirectorAnnouncement(1));
            assertThrows(IllegalStateException.class, pair.initiator::announceLocalClassCUnlocked);
            byte[] helloAck = packet(pair.responder.acceptInboundIke(hello));
            assertNull(pair.initiator.acceptInboundIke(helloAck));
            wipe(unlock, unlockAck, hello, helloAck);
        }
    }

    @Test
    public void boundsRetriesKeepsTransportAndAllowsLateAuthenticatedAck() {
        try (Pair pair = new Pair()) {
            List<String> log = new ArrayList<>();
            pair.initiator.enableLinkDirectorAnnouncements(123, log::add);
            byte[] first = packet(pair.initiator.pollLinkDirectorAnnouncement(0));
            byte[] lateAck = packet(pair.responder.acceptInboundIke(first));
            for (int retry = 1; retry <= 10; retry++) {
                byte[] retransmitted = packet(pair.initiator.pollLinkDirectorAnnouncement(retry * 10_000L));
                assertArrayEquals(first, retransmitted);
                wipe(retransmitted);
            }
            assertNull(pair.initiator.pollLinkDirectorAnnouncement(110_000));
            assertNull(pair.initiator.pollLinkDirectorAnnouncement(120_000));
            assertEquals(1, log.stream().filter(v -> v.contains("retry limit")).count());
            assertTrue(pair.initiator.established());
            assertTrue(pair.initiator.childSaEstablished());
            assertNull(pair.initiator.acceptInboundIke(lateAck));
            byte[] state = packet(pair.initiator.pollLinkDirectorAnnouncement(120_001));
            byte[] stateAck = packet(pair.responder.acceptInboundIke(state));
            assertTrue(pair.responder.consumeLinkDirectorObservation().contains("type=6 length=1"));
            assertNull(pair.initiator.acceptInboundIke(stateAck));
            wipe(first, lateAck, state, stateAck);
        }
    }

    private static byte[] packet(OrdinaryClassDSession.Outbound message) {
        assertNotNull(message);
        try { return message.ikePacket.clone(); }
        finally { message.destroy(); }
    }

    private static void wipe(byte[]... arrays) {
        for (byte[] value : arrays) Arrays.fill(value, (byte) 0);
    }

    private static OrdinaryIkeAuth.PeerMaterial peer(AppleNetworkRelayPairingMaterial material) {
        byte[] identity = material.identityPayload();
        byte[] classD = material.classDPublicKeysPayload();
        byte[] classC = material.classCPublicKeysPayload();
        try { return OrdinaryIkeAuth.PeerMaterial.fromPairingPayloads(identity, classD, classC); }
        finally { wipe(identity, classD, classC); }
    }

    private static final class Pair implements AutoCloseable {
        final OrdinaryClassDSession initiator;
        final OrdinaryClassDSession responder;

        Pair() {
            SecureRandom random = new SecureRandom();
            AppleNetworkRelayPairingMaterial im = AppleNetworkRelayPairingMaterial.generate(random);
            AppleNetworkRelayPairingMaterial rm = AppleNetworkRelayPairingMaterial.generate(random);
            OrdinaryIkeAuth.PeerMaterial ip = peer(rm);
            OrdinaryIkeAuth.PeerMaterial rp = peer(im);
            byte[] iu = new byte[16], ru = new byte[16];
            iu[15] = 1; ru[15] = 2;
            byte[] il = NrLinkBluetoothPrelude.encodeFreshModern(iu, false, false);
            byte[] rl = NrLinkBluetoothPrelude.encodeFreshModern(ru, false, false);
            AppleNetworkRelayInnerAddresses addresses = AppleNetworkRelayInnerAddresses.generate(random);
            OrdinaryIkeAuth.ResponderProfile iProfile = new OrdinaryIkeAuth.ResponderProfile(
                    addresses, il, "Phone", "test", null, 1, true, true);
            OrdinaryIkeAuth.ResponderProfile rProfile = new OrdinaryIkeAuth.ResponderProfile(
                    addresses, rl, "Watch", "23S303", "12345678-1234-1234-1234-123456789012", 2, true, true);
            initiator = OrdinaryClassDSession.create(random, NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                    im, ip, il, rl, iProfile);
            responder = OrdinaryClassDSession.create(random, NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    rm, rp, rl, il, rProfile);
            addresses.destroy();
            wipe(iu, ru, il, rl);
            OrdinaryClassDSession.Outbound next = initiator.start();
            boolean toResponder = true;
            int exchanges = 0;
            while (next != null) {
                OrdinaryClassDSession.Outbound current = next;
                try { next = (toResponder ? responder : initiator).acceptInboundIke(current.ikePacket); }
                finally { current.destroy(); }
                toResponder = !toResponder;
                assertTrue(++exchanges <= 8);
            }
            assertTrue(initiator.established());
            assertTrue(responder.established());
        }

        @Override public void close() { initiator.close(); responder.close(); }
    }
}
