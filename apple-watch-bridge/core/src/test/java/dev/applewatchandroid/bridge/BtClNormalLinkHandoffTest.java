package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class BtClNormalLinkHandoffTest {
    @Test
    public void emitsExactUltra2ServiceAddedAndCreateSequence() {
        BtClNormalLinkHandoff handoff =
                BtClNormalLinkHandoff.begin(0x0b, true);

        byte[] serviceAdded =
                handoff.advertise();
        assertArrayEquals(
                hex("05 1c 00 "
                        + "02 00 01 17 "
                        + "63 6f 6d 2e 61 70 70 6c 65 2e "
                        + "74 65 72 6d 69 6e 75 73 4c 69 6e 6b 00 "
                        + "01"),
                serviceAdded);
        assertEquals(
                BtClNormalLinkHandoff.Phase
                        .WAITING_FOR_COMMON_SERVICES,
                handoff.phase());
        assertTrue(handoff.advertised());
        assertThrows(
                IllegalStateException.class,
                handoff::advertise);

        HciCodec.BtClPdu common =
                HciCodec.parseBtCl(
                        0x0b,
                        hex("02 03 00 01 02 00"));
        byte[] create =
                handoff.acceptCommonServices(common);
        assertArrayEquals(
                hex("03 04 00 41 00 02 00"),
                create);

        HciCodec.BtClPdu accept =
                HciCodec.parseBtCl(
                        0x0b,
                        hex("04 05 00 00 02 00 42 00"));
        handoff.acceptChannel(accept);

        assertEquals(
                BtClNormalLinkHandoff.Phase.PIPE_OPEN,
                handoff.phase());
        assertEquals(0x0041, handoff.requesterLocalCid());
        assertEquals(0x0042, handoff.responderLocalCid());
        assertTrue(handoff.ertmEnabled());
        assertFalse(handoff.fcsEnabled());
    }

    @Test
    public void parsesCurrentAndLegacyServiceAddedBodies() {
        HciCodec.BtClPdu current =
                HciCodec.parseBtCl(
                        0x0b,
                        hex("05 1c 00 "
                                + "02 00 01 17 "
                                + "63 6f 6d 2e 61 70 70 6c 65 2e "
                                + "74 65 72 6d 69 6e 75 73 4c 69 6e 6b 00 "
                                + "01"));
        HciCodec.BtClServiceRecord parsed =
                HciCodec.parseServiceAdded(current);

        assertEquals(2, parsed.serviceId);
        assertEquals(1, parsed.serviceType);
        assertArrayEquals(
                "com.apple.terminusLink\0"
                        .getBytes(StandardCharsets.UTF_8),
                parsed.name());
        assertTrue(parsed.ertm());
        assertFalse(parsed.fcs());

        HciCodec.BtClPdu legacy =
                HciCodec.parseBtCl(
                        0x0b,
                        hex("05 05 00 09 00 01 01 78"));
        HciCodec.BtClServiceRecord legacyParsed =
                HciCodec.parseServiceAdded(legacy);
        assertEquals(0, legacyParsed.flags);
        assertArrayEquals(
                new byte[]{0x78},
                legacyParsed.name());
    }

    @Test
    public void roundTripsGeneralServiceAndChannelCodecs() {
        HciCodec.BtClServiceRecord normal =
                HciCodec.terminusLinkService();
        byte[] listBytes =
                HciCodec.buildRemoteServicesPdu(
                        0x0b,
                        java.util.List.of(normal));
        HciCodec.BtClPdu list =
                HciCodec.parseBtCl(
                        0x0b,
                        listBytes);
        HciCodec.BtClServiceRecord decoded =
                HciCodec.parseRemoteServices(list).get(0);
        assertEquals(normal.serviceId, decoded.serviceId);
        assertEquals(normal.serviceType, decoded.serviceType);
        assertArrayEquals(normal.name(), decoded.name());
        assertEquals(normal.flags, decoded.flags);

        byte[] commonBytes =
                HciCodec.buildCommonServicesPdu(
                        0x0b,
                        java.util.List.of(2));
        assertEquals(
                java.util.List.of(2),
                HciCodec.parseCommonServices(
                        HciCodec.parseBtCl(
                                0x0b,
                                commonBytes)));

        byte[] createBytes =
                HciCodec.buildCreateChannelPdu(
                        0x0b,
                        0x0041,
                        2);
        HciCodec.CreateChannel create =
                HciCodec.parseCreateChannel(
                        HciCodec.parseBtCl(
                                0x0b,
                                createBytes));
        assertEquals(0x0041, create.requesterLocalCid);
        assertEquals(2, create.serviceId);

        byte[] acceptBytes =
                HciCodec.buildAcceptChannelPdu(
                        0x0b,
                        0,
                        2,
                        0x0042);
        HciCodec.AcceptChannel accept =
                HciCodec.parseAcceptChannel(
                        HciCodec.parseBtCl(
                                0x0b,
                                acceptBytes));
        assertEquals(0, accept.status);
        assertEquals(2, accept.serviceId);
        assertEquals(0x0042, accept.responderLocalCid);

        byte[] removedBytes =
                HciCodec.buildServiceRemovedPdu(
                        0x0b,
                        2);
        assertEquals(
                2,
                HciCodec.parseServiceRemoved(
                        HciCodec.parseBtCl(
                                0x0b,
                                removedBytes)));

        byte[] removalConfirmation =
                HciCodec.buildRemoveConfirmationPdu(
                        0x0b,
                        1,
                        0);
        assertArrayEquals(
                hex("07 03 00 01 00 00"),
                removalConfirmation);
        HciCodec.RemoveConfirmation decodedConfirmation =
                HciCodec.parseRemoveConfirmation(
                        HciCodec.parseBtCl(
                                0x0b,
                                removalConfirmation));
        assertEquals(1, decodedConfirmation.serviceId);
        assertEquals(0, decodedConfirmation.status);
    }

    @Test
    public void failsClosedOnSecurityOrOutOfOrderReplies() {
        assertThrows(
                IllegalStateException.class,
                () -> BtClNormalLinkHandoff.begin(
                        0x0b,
                        false));

        BtClNormalLinkHandoff handoff =
                BtClNormalLinkHandoff.begin(0x0b, true);
        assertThrows(
                IllegalStateException.class,
                () -> handoff.acceptCommonServices(
                        HciCodec.parseBtCl(
                                0x0b,
                                hex("02 03 00 01 02 00"))));

        handoff.advertise();
        assertThrows(
                IllegalArgumentException.class,
                () -> handoff.acceptCommonServices(
                        HciCodec.parseBtCl(
                                0x0b,
                                hex("02 03 00 01 01 00"))));

        byte[] create =
                handoff.acceptCommonServices(
                        HciCodec.parseBtCl(
                                0x0b,
                                hex("02 03 00 01 02 00")));
        assertArrayEquals(
                hex("03 04 00 41 00 02 00"),
                create);
        assertThrows(
                IllegalStateException.class,
                handoff::advertise);
        assertThrows(
                IllegalArgumentException.class,
                () -> handoff.acceptChannel(
                        HciCodec.parseBtCl(
                                0x0b,
                                hex("04 05 00 03 02 00 00 00"))));
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
