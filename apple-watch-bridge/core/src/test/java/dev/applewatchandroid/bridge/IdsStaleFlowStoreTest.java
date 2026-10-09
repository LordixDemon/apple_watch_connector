package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

public final class IdsStaleFlowStoreTest {
    @Test
    public void staleFlowsMustMatchBothAddressesOfTheirOwnDataClass() {
        byte[] localD = new byte[16], remoteD = new byte[16];
        byte[] localC = new byte[16], remoteC = new byte[16];
        localD[15] = 1; remoteD[15] = 2; localC[15] = 3; remoteC[15] = 4;
        IdsStaleFlowRecord record = new IdsStaleFlowRecord(
                OrdinaryIkeAuth.DataClass.CLASS_C, localC, remoteC,
                61314, 49154, 10, -1, 1);
        assertTrue(record.matchesAddresses(localD, remoteD, localC, remoteC));
        org.junit.Assert.assertFalse(record.matchesAddresses(localC, remoteC, localD, remoteD));
        byte[] otherWatch = remoteC.clone(); otherWatch[14] = 1;
        org.junit.Assert.assertFalse(record.matchesAddresses(localD, remoteD, localC, otherWatch));
        org.junit.Assert.assertFalse(record.matchesAddresses(localD, remoteD, null, null));
    }

    @Test
    public void storeRoundTripsRecordsAndSkipsMalformedLines() throws Exception {
        File temp = Files.createTempFile("stale-flows", ".v1").toFile();
        temp.deleteOnExit();
        File previous = IdsStaleFlowStore.storeFile;
        IdsStaleFlowStore.storeFile = temp;
        try {
            byte[] local = new byte[16];
            byte[] remote = new byte[16];
            for (int i = 0; i < 16; i++) {
                local[i] = (byte) (0xa0 + i);
                remote[i] = (byte) (0x50 + i);
            }
            IdsStaleFlowRecord record =
                    new IdsStaleFlowRecord(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            local,
                            remote,
                            61315,
                            49173,
                            3095449250L,
                            2129125759L,
                            1759656000000L);
            IdsStaleFlowStore.save(List.of(record));
            // A torn tail line must not lose the valid records.
            java.nio.file.Files.write(
                    temp.toPath(),
                    (record.encode() + "\ngarbage line without fields\n")
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            List<IdsStaleFlowRecord> loaded = IdsStaleFlowStore.load();
            assertEquals(1, loaded.size());
            IdsStaleFlowRecord restored = loaded.get(0);
            assertEquals(OrdinaryIkeAuth.DataClass.CLASS_D, restored.dataClass);
            assertEquals(61315, restored.localPort);
            assertEquals(49173, restored.remotePort);
            assertEquals(3095449250L, restored.peerAck);
            assertEquals(2129125759L, restored.peerTimestamp);
            assertEquals(1759656000000L, restored.lastSeenEpochMs);
            org.junit.Assert.assertArrayEquals(local, restored.localAddress);
            org.junit.Assert.assertArrayEquals(remote, restored.remoteAddress);

            // Merge with a fresher observation of the same tuple wins.
            IdsStaleFlowRecord fresher =
                    new IdsStaleFlowRecord(
                            OrdinaryIkeAuth.DataClass.CLASS_D,
                            local,
                            remote,
                            61315,
                            49173,
                            3095449260L,
                            -1L,
                            1759656100000L);
            IdsStaleFlowStore.merge(List.of(fresher));
            List<IdsStaleFlowRecord> merged = IdsStaleFlowStore.load();
            assertEquals(1, merged.size());
            assertEquals(3095449260L, merged.get(0).peerAck);
            assertTrue(IdsStaleFlowStore.load().get(0).lastSeenEpochMs
                    == 1759656100000L);
        } finally {
            IdsStaleFlowStore.storeFile = previous;
        }
    }
}
