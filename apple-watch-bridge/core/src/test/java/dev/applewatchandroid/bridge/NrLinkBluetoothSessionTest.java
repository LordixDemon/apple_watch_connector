package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.Test;

public final class NrLinkBluetoothSessionTest {
    @Test
    public void keepsFreshRoleElectionInsideOnePipe() {
        byte[] local = hex(
                "80 00 00 00 00 00 00 00 "
                        + "00 00 00 00 00 00 00 01");
        byte[] remote = hex(
                "7f ff ff ff ff ff ff ff "
                        + "ff ff ff ff ff ff ff ff");
        NrLinkBluetoothSession session =
                NrLinkBluetoothSession
                        .withLocalUuidForTest(local);
        try {
            byte[] outbound =
                    session.outboundPrelude();
            NrLinkBluetoothPrelude.Parsed localParsed =
                    NrLinkBluetoothPrelude.parse(outbound);
            assertEquals(
                    NrLinkBluetoothPrelude.PairingState
                            .MODERN_PAIRING_KEY_CONFIRMATION,
                    localParsed.state);
            assertEquals(0, localParsed.flags);
            assertArrayEquals(local, localParsed.uuid());

            NrLinkBluetoothSession.Negotiated negotiated =
                    session.acceptRemotePrelude(
                            NrLinkBluetoothPrelude.encodeFreshModern(
                                    remote,
                                    true,
                                    false));
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    negotiated.localRole);
            assertEquals(
                    NrLinkBluetoothPrelude.PairingState
                            .MODERN_PAIRING_KEY_CONFIRMATION,
                    negotiated.usedState);
            assertEquals(
                    NrLinkBluetoothPrelude.FLAG_COMPANION_APL,
                    negotiated.remoteFlags);
            assertEquals(6, negotiated.jointUuidHash.length());
            assertEquals(
                    NrLinkBluetoothSession.Phase
                            .PRELUDE_NEGOTIATED,
                    session.phase());
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    session.localRole());
        } finally {
            session.close();
        }
        assertEquals(
                NrLinkBluetoothSession.Phase.DESTROYED,
                session.phase());
        assertThrows(
                IllegalStateException.class,
                session::outboundPrelude);
    }

    @Test
    public void preferredRoleWinsAgainstEitherPeerUuid() {
        byte[] middle = new byte[16];
        Arrays.fill(middle, (byte) 0x80);
        NrLinkBluetoothSession initiator =
                NrLinkBluetoothSession.pairedWithPreferredRole(
                        new java.security.SecureRandom(),
                        NrLinkBluetoothPrelude.LocalRole.INITIATOR);
        NrLinkBluetoothSession responder =
                NrLinkBluetoothSession.pairedWithPreferredRole(
                        new java.security.SecureRandom(),
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER);
        try {
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                    initiator.acceptRemotePrelude(
                            NrLinkBluetoothPrelude.encodeFreshModern(middle, false, false))
                            .localRole);
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    responder.acceptRemotePrelude(
                            NrLinkBluetoothPrelude.encodeFreshModern(middle, false, false))
                            .localRole);
        } finally {
            initiator.close();
            responder.close();
        }
    }

    @Test
    public void reelectsOppositeRoleForAnotherPipe() {
        byte[] smaller = new byte[16];
        byte[] larger = new byte[16];
        larger[15] = 1;

        NrLinkBluetoothSession first =
                NrLinkBluetoothSession
                        .withLocalUuidForTest(smaller);
        NrLinkBluetoothSession second =
                NrLinkBluetoothSession
                        .withLocalUuidForTest(larger);
        try {
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                    first.acceptRemotePrelude(
                            NrLinkBluetoothPrelude.encodeFreshModern(
                                    larger,
                                    false,
                                    false))
                            .localRole);
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    second.acceptRemotePrelude(
                            NrLinkBluetoothPrelude.encodeFreshModern(
                                    smaller,
                                    false,
                                    false))
                            .localRole);
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    public void rejectsNonModernOrTlsPrelude() {
        byte[] local = new byte[16];
        byte[] remote = new byte[16];
        remote[15] = 1;

        NrLinkBluetoothSession tls =
                NrLinkBluetoothSession
                        .withLocalUuidForTest(local);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> tls.acceptRemotePrelude(
                            NrLinkBluetoothPrelude.encodeFreshModern(
                                    remote,
                                    true,
                                    true)));
        } finally {
            tls.close();
        }

        NrLinkBluetoothSession legacy =
                NrLinkBluetoothSession
                        .withLocalUuidForTest(local);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> legacy.acceptRemotePrelude(
                            NrLinkBluetoothPrelude.encode(
                                    NrLinkBluetoothPrelude.PairingState
                                            .HAS_COMPLETED_PAIRING,
                                    remote,
                                    0)));
        } finally {
            legacy.close();
        }
    }

    @Test
    public void mirrorsAppleRoleControlledUuidPrefixes() {
        NrLinkBluetoothSession initiator =
                NrLinkBluetoothSession
                        .freshInitiatorPreferred(
                                new SecureRandom());
        NrLinkBluetoothSession responder =
                NrLinkBluetoothSession
                        .freshResponderPreferred(
                                new SecureRandom());
        try {
            byte[] initiatorUuid =
                    NrLinkBluetoothPrelude
                            .parse(
                                    initiator.outboundPrelude())
                            .uuid();
            byte[] responderUuid =
                    NrLinkBluetoothPrelude
                            .parse(
                                    responder.outboundPrelude())
                            .uuid();
            assertArrayEquals(
                    new byte[8],
                    Arrays.copyOfRange(
                            initiatorUuid,
                            0,
                            8));
            byte[] expectedResponderPrefix =
                    new byte[8];
            Arrays.fill(
                    expectedResponderPrefix,
                    (byte) 0xff);
            assertArrayEquals(
                    expectedResponderPrefix,
                    Arrays.copyOfRange(
                            responderUuid,
                            0,
                            8));
            Arrays.fill(
                    initiatorUuid,
                    (byte) 0);
            Arrays.fill(
                    responderUuid,
                    (byte) 0);
            Arrays.fill(
                    expectedResponderPrefix,
                    (byte) 0);
        } finally {
            initiator.close();
            responder.close();
        }
    }

    private static byte[] hex(String text) {
        String compact = text.replaceAll("\\s+", "");
        byte[] output = new byte[compact.length() / 2];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) Integer.parseInt(
                    compact.substring(
                            index * 2,
                            index * 2 + 2),
                    16);
        }
        return output;
    }
}
