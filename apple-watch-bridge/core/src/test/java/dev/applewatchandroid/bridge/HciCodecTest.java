package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class HciCodecTest {
    @Test
    public void rejectsTruncatedAndImpossibleControllerCreditCounts() {
        HciCodec.CommandComplete truncated = HciCodec.parseCommandComplete(new byte[]{
                0x0E, 0x06, 0x01, 0x05, 0x10, 0, 0x40, 0});
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> HciCodec.parseAclDataPacketCount(truncated));
        HciCodec.CommandComplete empty = HciCodec.parseCommandComplete(new byte[]{
                0x0E, 0x0B, 0x01, 0x05, 0x10, 0, 0x40, 0, 0, 0, 0, 0, 0});
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> HciCodec.parseAclDataPacketCount(empty));
        HciCodec.CommandComplete impossibleLe = HciCodec.parseCommandComplete(new byte[]{
                0x0E, 0x07, 0x01, 0x02, 0x20, 0, 0, 0, 1});
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> HciCodec.parseLeAclDataPacketCount(impossibleLe));
        // A zero LE count selects the classic pool, regardless of this unused length.
        HciCodec.CommandComplete shared = HciCodec.parseCommandComplete(new byte[]{
                0x0E, 0x07, 0x01, 0x02, 0x20, 0, 0x40, 0, 0});
        assertEquals(0, HciCodec.parseLeAclDataPacketCount(shared));
    }

    @Test
    public void parsesSyntheticWatchSetupAdvertisement() {
        byte[] event = {
                0x3E, 0x20, 0x02, 0x01,
                0x00, 0x01,
                0x06, 0x05, 0x04, 0x03, 0x02, (byte) 0xC2,
                0x14,
                0x02, 0x01, 0x1A,
                0x10, 0x16, 0x25, (byte) 0xFE, 0x06,
                0x20, (byte) 0x86, (byte) 0x93, (byte) 0xF4, (byte) 0xCE,
                0x64, 0x38, 0x50, 0x00, (byte) 0xD0, 0x10, 0x00,
                (byte) 0xD8
        };

        List<HciCodec.AdvertisingReport> reports =
                HciCodec.parseLegacyAdvertisingReports(event);

        assertEquals(1, reports.size());
        HciCodec.AdvertisingReport report = reports.get(0);
        assertEquals("C2:02:03:04:05:06", report.addressText());
        assertEquals(-40, report.rssi);
        assertEquals(1, report.setup.headerVersion);
        assertArrayEquals(
                new byte[]{
                        (byte) 0x86,
                        (byte) 0x93,
                        (byte) 0xF4,
                        (byte) 0xCE
                },
                report.setup.candidateIdentifier);
        assertEquals(
                4,
                report.setup.decodedIdentifier
                        .pairingStrategy);
        assertEquals(
                26943,
                report.setup.decodedIdentifier
                        .advertisingIdentifier);
        assertEquals(
                "Watch7,5",
                report.setup.decodedMetadata
                        .productType());
        assertEquals(
                25,
                report.setup.decodedMetadata
                        .pairingVersion);
        assertTrue(report.isExpectedTarget());
    }

    @Test
    public void rejectsDistantOrNonConnectableTarget() {
        byte[] data = {
                0x10, 0x16, 0x25, (byte) 0xFE, 0x06,
                0x20, (byte) 0x86, (byte) 0x93, (byte) 0xF4, (byte) 0xCE,
                0x64, 0x38, 0x50, 0x00, (byte) 0xD0, 0x10, 0x00
        };
        HciCodec.WatchSetupPayload setup = HciCodec.parseWatchSetupPayload(data);
        HciCodec.AdvertisingReport distant = new HciCodec.AdvertisingReport(
                0,
                1,
                new byte[6],
                -95,
                data,
                setup);
        HciCodec.AdvertisingReport nonConnectable = new HciCodec.AdvertisingReport(
                3,
                1,
                new byte[6],
                -20,
                data,
                setup);

        assertFalse(distant.isExpectedTarget());
        assertFalse(nonConnectable.isExpectedTarget());
    }

    @Test
    public void acceptsRotatedUltra2IdentifierFromDecodedMetadata() {
        byte[] identifier =
                WatchSetupMetadataCodec
                        .encodeIdentifier(
                                4,
                                51080,
                                19,
                                14);
        byte[] data = {
                0x10, 0x16, 0x25, (byte) 0xFE, 0x06,
                0x20,
                identifier[0],
                identifier[1],
                identifier[2],
                identifier[3],
                0x64, 0x38, 0x50, 0x00,
                (byte) 0xD0, 0x10, 0x00
        };
        HciCodec.WatchSetupPayload setup =
                HciCodec.parseWatchSetupPayload(
                        data);
        HciCodec.AdvertisingReport report =
                new HciCodec.AdvertisingReport(
                        0,
                        1,
                        new byte[6],
                        -30,
                        data,
                        setup);

        assertEquals(
                "51080",
                setup.decodedIdentifier
                        .advertisingCode());
        assertTrue(
                report.isExpectedTarget());
    }

    @Test
    public void buildsProvenLeCreateConnectionParameters() {
        HciCodec.AdvertisingReport target = new HciCodec.AdvertisingReport(
                0,
                1,
                new byte[]{
                        0x06, 0x05, 0x04, 0x03, 0x02, (byte) 0xC2
                },
                -31,
                new byte[0],
                null);

        assertArrayEquals(
                new byte[]{
                        0x60, 0x00, 0x60, 0x00, 0x00, 0x01,
                        0x06, 0x05, 0x04, 0x03, 0x02, (byte) 0xC2,
                        0x01, 0x18, 0x00, 0x28, 0x00,
                        0x00, 0x00, (byte) 0xF4, 0x01,
                        0x00, 0x00, 0x00, 0x00
                },
                HciCodec.buildLeCreateConnectionParameters(
                        target,
                        1));
        assertArrayEquals(
                new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, (byte) 0xC6},
                HciCodec.buildLeSetRandomAddressParameters(
                        new byte[]{
                                0x11, 0x22, 0x33,
                                0x44, 0x55, (byte) 0xC6
                        }));
    }

    @Test
    public void leFixedChannelsUseNonFlushableStartAndReassembleContinuation() {
        for (int cid : new int[]{0x0004, 0x0005, 0x0006, HciCodec.BT_CL_SIGNALING_CID, 0x0040}) {
            byte[] payload = HciCodec.buildBtClVersionPdu();
            List<byte[]> fragments = HciCodec.buildLeAclL2capFragments(0x0010, cid, payload, 6);
            assertEquals(0, (fragments.get(0)[1] >>> 4) & 3);
            HciCodec.AclReassembler assembler = new HciCodec.AclReassembler();
            HciCodec.L2capPdu reassembled = null;
            for (int index = 0; index < fragments.size(); index++) {
                if (index > 0) assertEquals(1, (fragments.get(index)[1] >>> 4) & 3);
                reassembled = assembler.accept(fragments.get(index));
            }
            assertEquals(cid, reassembled.destinationCid);
            assertArrayEquals(payload, reassembled.payload);
        }
    }

    @Test
    public void buildsExactBtClVersionAclWithoutH4Type() {
        assertArrayEquals(
                new byte[]{
                        0x41, 0x20, 0x0B, 0x00,
                        0x07, 0x00, 0x3A, 0x00,
                        0x09, 0x05, 0x0B, 0x21, 0x15, 0x00, 0x00
                },
                HciCodec.buildAclL2cap(
                        0x0041,
                        HciCodec.BT_CL_SIGNALING_CID,
                        HciCodec.buildBtClVersionPdu()));
    }

    @Test
    public void parsesControllerAclBufferAndFragmentsLargeL2capSdu() {
        HciCodec.CommandComplete bufferSize = HciCodec.parseCommandComplete(
                new byte[]{
                        0x0E, 0x0B, 0x01, 0x05, 0x10,
                        0x00, (byte) 0xFD, 0x00, 0x00,
                        0x0A, 0x00, 0x00, 0x00
                });
        assertEquals(253, HciCodec.parseAclDataPacketLength(bufferSize));
        assertEquals(10, HciCodec.parseAclDataPacketCount(bufferSize));

        HciCodec.CommandComplete leBufferSize =
                HciCodec.parseCommandComplete(new byte[]{
                        0x0E, 0x07, 0x01, 0x02, 0x20,
                        0x00, (byte) 0xFB, 0x00, 0x0A
                });
        assertEquals(
                251,
                HciCodec.parseLeAclDataPacketLength(leBufferSize));
        assertEquals(10, HciCodec.parseLeAclDataPacketCount(leBufferSize));

        byte[] payload = {
                0x00, 0x01, 0x02, 0x03, 0x04,
                0x05, 0x06, 0x07, 0x08, 0x09
        };
        List<byte[]> fragments = HciCodec.buildAclL2capFragments(
                0x0041,
                0x0040,
                payload,
                6);
        assertEquals(3, fragments.size());
        assertArrayEquals(
                new byte[]{
                        0x41, 0x00, 0x06, 0x00,
                        0x0A, 0x00, 0x40, 0x00, 0x00, 0x01
                },
                fragments.get(0));
        assertArrayEquals(
                new byte[]{
                        0x41, 0x10, 0x06, 0x00,
                        0x02, 0x03, 0x04, 0x05, 0x06, 0x07
                },
                fragments.get(1));
        assertArrayEquals(
                new byte[]{
                        0x41, 0x10, 0x02, 0x00, 0x08, 0x09
                },
                fragments.get(2));

        HciCodec.AclReassembler assembler = new HciCodec.AclReassembler();
        assertNull(assembler.accept(fragments.get(0)));
        assertNull(assembler.accept(fragments.get(1)));
        HciCodec.L2capPdu reassembled =
                assembler.accept(fragments.get(2));
        assertEquals(0x0041, reassembled.connectionHandle);
        assertEquals(0x0040, reassembled.destinationCid);
        assertArrayEquals(payload, reassembled.payload);
    }

    @Test
    public void parsesSharedLeBufferAndCompletedPacketCredits() {
        HciCodec.CommandComplete sharedBuffer =
                HciCodec.parseCommandComplete(new byte[]{
                        0x0E, 0x07, 0x01, 0x02, 0x20,
                        0x00, 0x00, 0x00, 0x00
                });
        assertEquals(
                0,
                HciCodec.parseLeAclDataPacketLength(sharedBuffer));
        assertEquals(0, HciCodec.parseLeAclDataPacketCount(sharedBuffer));

        List<HciCodec.CompletedPackets> completed =
                HciCodec.parseNumberOfCompletedPackets(new byte[]{
                        0x13, 0x05, 0x01,
                        0x41, 0x00,
                        0x02, 0x00
                });
        assertEquals(1, completed.size());
        assertEquals(0x0041, completed.get(0).connectionHandle);
        assertEquals(2, completed.get(0).completedPackets);
    }

    @Test
    public void parsesControllerPublicAddress() {
        HciCodec.CommandComplete response =
                HciCodec.parseCommandComplete(
                        new byte[]{
                                0x0e, 0x0a, 0x01, 0x09, 0x10,
                                0x00,
                                0x11, 0x22, 0x33,
                                0x44, 0x55, 0x66
                        });

        assertArrayEquals(
                new byte[]{
                        0x11, 0x22, 0x33,
                        0x44, 0x55, 0x66
                },
                HciCodec.parseBdAddr(response));
    }

    @Test
    public void buildsSecureConnectionsStartEncryptionAndParsesEvent() {
        byte[] key = new byte[16];
        for (int index = 0;
                index < key.length;
                index++) {
            key[index] = (byte) (0xa0 + index);
        }
        assertArrayEquals(
                new byte[]{
                        0x41, 0x00,
                        0, 0, 0, 0, 0, 0, 0, 0,
                        0, 0,
                        (byte) 0xa0, (byte) 0xa1,
                        (byte) 0xa2, (byte) 0xa3,
                        (byte) 0xa4, (byte) 0xa5,
                        (byte) 0xa6, (byte) 0xa7,
                        (byte) 0xa8, (byte) 0xa9,
                        (byte) 0xaa, (byte) 0xab,
                        (byte) 0xac, (byte) 0xad,
                        (byte) 0xae, (byte) 0xaf
                },
                HciCodec.buildLeStartEncryptionParameters(
                        0x0041,
                        key));

        HciCodec.EncryptionChange change =
                HciCodec.parseEncryptionChange(
                        new byte[]{
                                0x08, 0x04, 0x00,
                                0x41, 0x00, 0x01
                        });
        assertEquals(0, change.status);
        assertEquals(0x0041,
                change.connectionHandle);
        assertEquals(1,
                change.encryptionEnabled);
    }

    @Test
    public void encodesTerminusRemoteServicesWithWideLength() {
        byte[] pdu = HciCodec.buildRemoteServicesPdu(0x0B);

        assertArrayEquals(
                new byte[]{
                        0x01, 0x21, 0x00,
                        0x01, 0x1F, 0x01, 0x00, 0x01, 0x1A,
                        0x63, 0x6F, 0x6D, 0x2E,
                        0x61, 0x70, 0x70, 0x6C, 0x65, 0x2E,
                        0x74, 0x65, 0x72, 0x6D, 0x69, 0x6E, 0x75, 0x73,
                        0x50, 0x61, 0x69, 0x72, 0x69, 0x6E, 0x67, 0x00,
                        0x01
                },
                pdu);
        assertEquals(HciCodec.BT_CL_REMOTE_SERVICES, pdu[0] & 0xFF);
        assertEquals(33, (pdu[1] & 0xFF) | ((pdu[2] & 0xFF) << 8));
        assertEquals(1, pdu[3] & 0xFF);
        assertEquals(31, pdu[4] & 0xFF);
        assertEquals(26, pdu[8] & 0xFF);
        assertEquals(0, pdu[pdu.length - 2]);
        assertEquals(1, pdu[pdu.length - 1]);
    }

    @Test
    public void parsesExactLeConnectionAndDisconnectEvents() {
        HciCodec.LeConnectionComplete connection =
                HciCodec.parseLeConnectionComplete(new byte[]{
                        0x3E, 0x13, 0x01, 0x00,
                        0x41, 0x00, 0x00, 0x01,
                        0x06, 0x05, 0x04, 0x03, 0x02, (byte) 0xC2,
                        0x28, 0x00, 0x00, 0x00, (byte) 0xF4, 0x01, 0x05
                });
        assertEquals(0, connection.status);
        assertEquals(0x0041, connection.connectionHandle);
        assertEquals(1, connection.peerAddressType);
        assertEquals("C2:02:03:04:05:06",
                HciCodec.formatLeAddress(connection.peerAddress));

        HciCodec.DisconnectionComplete disconnect =
                HciCodec.parseDisconnectionComplete(new byte[]{
                        0x05, 0x04, 0x00, 0x41, 0x00, 0x13
                });
        assertEquals(0, disconnect.status);
        assertEquals(0x0041, disconnect.connectionHandle);
        assertEquals(0x13, disconnect.reason);
    }

    @Test
    public void reassemblesFragmentedIncomingL2capPdu() {
        HciCodec.AclReassembler assembler = new HciCodec.AclReassembler();
        byte[] first = {
                0x41, 0x20, 0x06, 0x00,
                0x07, 0x00, 0x3A, 0x00, 0x09, 0x05
        };
        byte[] continuation = {
                0x41, 0x10, 0x05, 0x00,
                0x0B, 0x21, 0x15, 0x00, 0x00
        };

        assertNull(assembler.accept(first));
        HciCodec.L2capPdu result = assembler.accept(continuation);

        assertEquals(0x0041, result.connectionHandle);
        assertEquals(0x003A, result.destinationCid);
        assertArrayEquals(
                HciCodec.buildBtClVersionPdu(),
                result.payload);
    }

    @Test
    public void parsesCommonAndAcceptChannel() {
        HciCodec.BtClPdu common = HciCodec.parseBtCl(
                0x0B,
                new byte[]{0x02, 0x03, 0x00, 0x01, 0x01, 0x00});
        assertEquals(List.of(1), HciCodec.parseCommonServices(common));

        HciCodec.BtClPdu accept = HciCodec.parseBtCl(
                0x0B,
                new byte[]{0x04, 0x05, 0x00, 0x00, 0x01, 0x00, 0x41, 0x00});
        HciCodec.AcceptChannel parsed = HciCodec.parseAcceptChannel(accept);
        assertEquals(0, parsed.status);
        assertEquals(1, parsed.serviceId);
        assertEquals(0x0041, parsed.responderLocalCid);
    }

    @Test
    public void encodesAndParsesTimeSyncInformation() {
        byte[] encoded = HciCodec.buildTimeSyncInformation(0x0B);
        HciCodec.BtClPdu pdu = HciCodec.parseBtCl(0x0B, encoded);
        assertEquals(HciCodec.BT_CL_TIME_SYNC_INFO, pdu.opcode);
        assertEquals(18, pdu.payload.length);
    }

    @Test
    public void timeSyncUsesNativeNanosecondsAndLittleEndianLayout() {
        // Native gettimeofday seconds*1e9 + microseconds*1e3, not Unix ms.
        byte[] encoded = HciCodec.buildTimeSyncInformation(
                0x0B, 0L, 1000L, 0x0102030405060708L, -120);
        assertArrayEquals(new byte[]{
                0x71, 0x12, 0x00,
                0x00, (byte) 0xCA, (byte) 0x9A, 0x3B, 0, 0, 0, 0,
                8, 7, 6, 5, 4, 3, 2, 1,
                (byte) 0x88, (byte) 0xFF}, encoded);
    }

    @Test
    public void timeSyncCorrectionPublishesAndroidNanosecondTimebase() {
        byte[] encoded = HciCodec.buildTimeSyncCorrection(
                0x0B, 0L, 0x0102030405060708L);
        assertArrayEquals(new byte[]{
                0x72, 0x10, 0x00, 8, 7, 6, 5, 4, 3, 2, 1,
                1, 0, 0, 0, 1, 0, 0, 0}, encoded);
        // Version <=7 uses the native two-byte header.
        assertEquals(18, HciCodec.buildTimeSyncCorrection(7, 0L, 123L).length);
    }
}
