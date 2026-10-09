package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

public final class IdsControlChannelCodecTest {
    private static final UUID INSTANCE_ID =
            UUID.fromString(
                    "00112233-4455-6677-8899-aabbccddeeff");
    private static final UUID DEVICE_ID =
            UUID.fromString(
                    "ffeeddcc-bbaa-9988-7766-554433221100");
    private static final String REMOTE_GUID =
            "00112233-4455-6677-8899-aabbccddeeff";
    private static final String LOCAL_GUID =
            "ffeeddcc-bbaa-9988-7766-554433221100";

    @Test
    public void helloMatchesExactCurrentWireVectorAndTlvOrder() {
        IdsControlChannelCodec.HelloMessage source =
                hello();
        byte[] encoded =
                IdsControlChannelCodec.encodeFramed(
                        source);
        IdsControlChannelCodec.HelloMessage decoded =
                (IdsControlChannelCodec.HelloMessage)
                        IdsControlChannelCodec.decodeFramed(
                                encoded);
        try {
            assertArrayEquals(
                    hex(
                            "00 58 "
                                    + "01 "
                                    + "00 01 35 "
                                    + "00 01 50 "
                                    + "00 01 56 "
                                    + "00 01 42 "
                                    + "00 01 4d "
                                    + "01 02 03 04 "
                                    + "00 00 04 11 12 13 14 "
                                    + "01 00 04 21 22 23 24 "
                                    + "02 00 10 "
                                    + "00 11 22 33 44 55 66 77 "
                                    + "88 99 aa bb cc dd ee ff "
                                    + "05 00 10 "
                                    + "ff ee dd cc bb aa 99 88 "
                                    + "77 66 55 44 33 22 11 00 "
                                    + "03 00 08 "
                                    + "01 02 03 04 05 06 07 08 "
                                    + "04 00 02 00 20"),
                    encoded);
            assertEquals("5", decoded.controlChannelVersion);
            assertEquals("P", decoded.productName);
            assertEquals("V", decoded.productVersion);
            assertEquals("B", decoded.productBuildVersion);
            assertEquals("M", decoded.model);
            assertEquals(
                    0x01020304L,
                    decoded.pairingProtocolVersion);
            assertEquals(
                    0x11121314L,
                    decoded.minCompatibilityVersion);
            assertEquals(
                    0x21222324L,
                    decoded.maxCompatibilityVersion);
            assertEquals(INSTANCE_ID, decoded.instanceId);
            assertEquals(DEVICE_ID, decoded.deviceUniqueId);
            assertEquals(
                    0x0102030405060708L,
                    decoded.capabilityFlags);
            assertEquals(
                    32,
                    decoded.serviceMinCompatibilityVersion);
        } finally {
            source.destroy();
            decoded.destroy();
            wipe(encoded);
        }
    }

    @Test
    public void setupChannelHasExact16ByteHeaderAndFieldOrder() {
        IdsControlChannelCodec.SetupChannelMessage source =
                new IdsControlChannelCodec.SetupChannelMessage(
                        IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                        IdsControlChannelCodec.PROTOCOL_TCP,
                        0x1234,
                        IdsControlChannelCodec.DATA_PORT,
                        REMOTE_GUID,
                        null,
                        "a",
                        "s",
                        "n",
                        null);
        byte[] encoded =
                IdsControlChannelCodec.encodeFramed(
                        source);
        byte[] expected =
                concatenate(
                        hex(
                                "00 37 "
                                        + "02 06 12 34 ef 82 "
                                        + "00 24 00 00 "
                                        + "00 01 00 01 00 01"),
                        ascii(
                                REMOTE_GUID),
                        ascii("asn"));
        IdsControlChannelCodec.SetupChannelMessage decoded =
                (IdsControlChannelCodec.SetupChannelMessage)
                        IdsControlChannelCodec.decodeFramed(
                                encoded);
        try {
            assertArrayEquals(expected, encoded);
            assertEquals(
                    IdsControlChannelCodec.PROTOCOL_TCP,
                    decoded.protocol);
            assertEquals(0x1234, decoded.localPort);
            assertEquals(
                    IdsControlChannelCodec.DATA_PORT,
                    decoded.remotePort);
            assertEquals(
                    REMOTE_GUID,
                    decoded.remoteConnectionGuid);
            assertNull(decoded.forLocalGuid);
            assertEquals("a", decoded.account);
            assertEquals("s", decoded.service);
            assertEquals("n", decoded.name);
            assertNull(decoded.directFlags);
        } finally {
            source.destroy();
            decoded.destroy();
            wipe(encoded);
            wipe(expected);
        }
    }

    @Test
    public void directSetupCarriesExactlyEightOpaqueFlagBytes() {
        byte[] flags =
                hex("01 02 03 04 05 06 07 08");
        IdsControlChannelCodec.SetupChannelMessage source =
                new IdsControlChannelCodec.SetupChannelMessage(
                        IdsControlChannelCodec
                                .TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE,
                        IdsControlChannelCodec.PROTOCOL_UDP,
                        0x1234,
                        IdsControlChannelCodec.DATA_PORT,
                        REMOTE_GUID,
                        null,
                        "a",
                        "s",
                        "n",
                        flags);
        byte[] encoded =
                IdsControlChannelCodec.encodeFramed(
                        source);
        IdsControlChannelCodec.SetupChannelMessage decoded =
                (IdsControlChannelCodec.SetupChannelMessage)
                        IdsControlChannelCodec.decodeFramed(
                                encoded);
        try {
            assertEquals(0x003f, be16(encoded, 0));
            assertEquals(
                    IdsControlChannelCodec
                            .TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE,
                    encoded[2] & 0xff);
            assertArrayEquals(
                    flags,
                    Arrays.copyOfRange(
                            encoded,
                            encoded.length - flags.length,
                            encoded.length));
            assertArrayEquals(flags, decoded.directFlags);
        } finally {
            source.destroy();
            decoded.destroy();
            wipe(flags);
            wipe(encoded);
        }
    }

    @Test
    public void encryptedSetupMatches24ByteHeaderAndMixesContributions() {
        byte[] remoteContribution =
                range(
                        0,
                        IdsControlChannelCodec
                                .CURRENT_KEY_MATERIAL_LENGTH);
        byte[] localContribution =
                new byte[
                        IdsControlChannelCodec
                                .CURRENT_KEY_MATERIAL_LENGTH];
        Arrays.fill(
                localContribution,
                (byte) 0xaa);
        IdsControlChannelCodec.SetupEncryptedChannelMessage source =
                new IdsControlChannelCodec
                        .SetupEncryptedChannelMessage(
                                IdsControlChannelCodec.PROTOCOL_UDP,
                                0x2345,
                                IdsControlChannelCodec.DATA_PORT,
                                REMOTE_GUID,
                                LOCAL_GUID,
                                "a",
                                "s",
                                "n",
                                0x11223344L,
                                0x5566,
                                remoteContribution);
        byte[] encoded =
                IdsControlChannelCodec.encodeFramed(
                        source);
        IdsControlChannelCodec.SetupEncryptedChannelMessage decoded =
                (IdsControlChannelCodec
                        .SetupEncryptedChannelMessage)
                        IdsControlChannelCodec.decodeFramed(
                                encoded);
        byte[] combined =
                IdsControlChannelCodec.combineContributions(
                        localContribution,
                        decoded);
        try {
            assertEquals(0x009f, be16(encoded, 0));
            assertArrayEquals(
                    hex(
                            "00 9f "
                                    + "06 11 23 45 ef 82 "
                                    + "00 24 00 24 "
                                    + "00 01 00 01 00 01 "
                                    + "11 22 33 44 "
                                    + "55 66 00 3c"),
                    Arrays.copyOfRange(
                            encoded,
                            0,
                            26));
            assertEquals(
                    REMOTE_GUID,
                    decoded.remoteConnectionGuid);
            assertEquals(LOCAL_GUID, decoded.forLocalGuid);
            assertEquals(0x11223344L, decoded.ssrc);
            assertEquals(0x5566, decoded.startSequence);
            assertArrayEquals(
                    remoteContribution,
                    decoded.keyMaterial);
            for (int index = 0;
                    index < combined.length;
                    index++) {
                assertEquals(
                        (byte) (0xaa ^ index),
                        combined[index]);
            }

            byte[] retained =
                    decoded.keyMaterial;
            decoded.destroy();
            assertArrayEquals(
                    new byte[
                            IdsControlChannelCodec
                                    .CURRENT_KEY_MATERIAL_LENGTH],
                    retained);
        } finally {
            source.destroy();
            decoded.destroy();
            wipe(remoteContribution);
            wipe(localContribution);
            wipe(encoded);
            wipe(combined);
        }
    }

    @Test
    public void streamDecoderHandlesSplitAndCoalescedControlFrames() {
        IdsControlChannelCodec.HelloMessage hello =
                hello();
        IdsControlChannelCodec.SetupChannelMessage setup =
                setup();
        byte[] helloFrame =
                IdsControlChannelCodec.encodeFramed(
                        hello);
        byte[] setupFrame =
                IdsControlChannelCodec.encodeFramed(
                        setup);
        byte[] combined =
                concatenate(
                        helloFrame,
                        setupFrame);
        try (IdsControlChannelCodec.StreamDecoder decoder =
                new IdsControlChannelCodec.StreamDecoder()) {
            assertTrue(
                    decoder.push(
                            Arrays.copyOfRange(
                                    combined,
                                    0,
                                    1))
                            .isEmpty());
            assertEquals(1, decoder.bufferedLength());
            List<IdsControlChannelCodec.Message> decoded =
                    decoder.push(
                            Arrays.copyOfRange(
                                    combined,
                                    1,
                                    combined.length));
            try {
                assertEquals(2, decoded.size());
                assertEquals(
                        IdsControlChannelCodec.TYPE_HELLO,
                        decoded.get(0).type);
                assertEquals(
                        IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                        decoded.get(1).type);
                assertEquals(0, decoder.bufferedLength());
            } finally {
                destroyAll(decoded);
            }

            assertThrows(
                    IllegalArgumentException.class,
                    () -> decoder.push(
                            hex("00 00")));
            assertThrows(
                    IllegalStateException.class,
                    () -> decoder.push(
                            new byte[0]));
        } finally {
            hello.destroy();
            setup.destroy();
            wipe(helloFrame);
            wipe(setupFrame);
            wipe(combined);
        }
    }

    @Test
    public void rejectsMalformedLengthsUnsupportedModesAndShortKeys() {
        byte[] helloFrame =
                IdsControlChannelCodec.encodeFramed(
                        hello());
        helloFrame[1]--;
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsControlChannelCodec.decodeFramed(
                        helloFrame));
        wipe(helloFrame);

        assertThrows(
                IllegalArgumentException.class,
                () -> new IdsControlChannelCodec.SetupChannelMessage(
                        IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                        99,
                        1,
                        2,
                        REMOTE_GUID,
                        null,
                        "a",
                        "s",
                        "n",
                        null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IdsControlChannelCodec.SetupChannelMessage(
                        IdsControlChannelCodec
                                .TYPE_SETUP_CHANNEL_FOR_DIRECT_MESSAGE,
                        IdsControlChannelCodec.PROTOCOL_TCP,
                        1,
                        2,
                        REMOTE_GUID,
                        null,
                        "a",
                        "s",
                        "n",
                        new byte[7]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IdsControlChannelCodec
                        .SetupEncryptedChannelMessage(
                                IdsControlChannelCodec.PROTOCOL_TCP,
                                1,
                                2,
                                REMOTE_GUID,
                                null,
                                "a",
                                "s",
                                "n",
                                1,
                                2,
                                new byte[59]));
        IdsControlChannelCodec.Message close =
                IdsControlChannelCodec.decodePayload(
                        hex("03"));
        try {
            assertTrue(
                    close instanceof IdsControlChannelCodec
                            .GenericControlMessage);
            assertEquals(
                    IdsControlChannelCodec.TYPE_CLOSE_CHANNEL,
                    close.type);
        } finally {
            close.destroy();
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsControlChannelCodec.decodePayload(
                        hex("06")));
    }

    private static IdsControlChannelCodec.HelloMessage hello() {
        return new IdsControlChannelCodec.HelloMessage(
                "5",
                "P",
                "V",
                "B",
                "M",
                0x01020304L,
                0x11121314L,
                0x21222324L,
                INSTANCE_ID,
                DEVICE_ID,
                0x0102030405060708L,
                32);
    }

    private static IdsControlChannelCodec.SetupChannelMessage setup() {
        return new IdsControlChannelCodec.SetupChannelMessage(
                IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                IdsControlChannelCodec.PROTOCOL_TCP,
                0x1234,
                IdsControlChannelCodec.DATA_PORT,
                REMOTE_GUID,
                null,
                "a",
                "s",
                "n",
                null);
    }

    private static void destroyAll(
            List<IdsControlChannelCodec.Message> messages) {
        for (IdsControlChannelCodec.Message message : messages) {
            message.destroy();
        }
    }

    private static int be16(
            byte[] bytes,
            int offset) {
        return ((bytes[offset] & 0xff) << 8)
                | (bytes[offset + 1] & 0xff);
    }

    private static byte[] ascii(
            String value) {
        return value.getBytes(
                StandardCharsets.US_ASCII);
    }

    private static byte[] concatenate(
            byte[]... values) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        for (byte[] value : values) {
            output.writeBytes(
                    value);
        }
        return output.toByteArray();
    }

    private static byte[] range(
            int start,
            int length) {
        byte[] output =
                new byte[length];
        for (int index = 0;
                index < length;
                index++) {
            output[index] =
                    (byte) (start + index);
        }
        return output;
    }

    private static byte[] hex(
            String value) {
        String normalized =
                value.replaceAll(
                        "\\s+",
                        "");
        byte[] output =
                new byte[normalized.length() / 2];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            normalized.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
    }

    @Test
    public void directMsgInfoIsOpaqueAndIsNotParsedAsSetupAck() {
        byte[] encoded =
                hex("00 08 0e 01 02 03 04 05 06 07");
        IdsControlChannelCodec.Message decoded =
                IdsControlChannelCodec.decodeFramed(
                        encoded);
        try {
            assertTrue(
                    decoded instanceof IdsControlChannelCodec
                            .DirectMsgInfoMessage);
            IdsControlChannelCodec.DirectMsgInfoMessage info =
                    (IdsControlChannelCodec.DirectMsgInfoMessage)
                            decoded;
            assertEquals(
                    IdsControlChannelCodec.TYPE_DIRECT_MSG_INFO,
                    info.type);
            assertArrayEquals(
                    hex("01 02 03 04 05 06 07"),
                    info.info);
            assertEquals(
                    "DirectMsgInfo",
                    IdsControlChannelCodec.typeName(
                            info.type));
            assertTrue(
                    IdsControlChannelCodec.isKnownTypeName(
                            "DirectMsgInfo"));
            assertTrue(
                    IdsControlChannelCodec.isKnownTypeName(
                            "Hello"));
            assertFalse(
                    IdsControlChannelCodec.isKnownTypeName(
                            "idstest/localdelivery"));
        } finally {
            decoded.destroy();
            wipe(encoded);
        }
    }

    @Test
    public void basicDirectMessagingAnnouncementHasNativeLayoutWithoutUnsupportedFeatures() {
        var info = IdsControlChannelCodec.DirectMsgInfoMessage.basicVersionOne();
        byte[] framed = IdsControlChannelCodec.encodeFramed(info);
        try {
            assertArrayEquals(hex("00 11 0e 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), framed);
            assertEquals("version=1,features=0x0", info.capabilitySummary());
            var parsed = (IdsControlChannelCodec.DirectMsgInfoMessage) IdsControlChannelCodec.decodeFramed(framed);
            try { assertEquals(info.capabilitySummary(), parsed.capabilitySummary()); }
            finally { parsed.destroy(); }
        } finally { info.destroy(); wipe(framed); }
    }

    @Test
    public void closeChannelTypeIsNotASetupAck() {
        byte[] encoded =
                hex("00 01 03");
        IdsControlChannelCodec.Message decoded =
                IdsControlChannelCodec.decodeFramed(
                        encoded);
        try {
            assertTrue(
                    decoded instanceof IdsControlChannelCodec
                            .GenericControlMessage);
            assertEquals(
                    IdsControlChannelCodec.TYPE_CLOSE_CHANNEL,
                    decoded.type);
        } finally {
            decoded.destroy();
            wipe(encoded);
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
