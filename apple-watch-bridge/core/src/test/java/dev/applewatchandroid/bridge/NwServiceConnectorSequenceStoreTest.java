package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

public class NwServiceConnectorSequenceStoreTest {

    private File tempFile;

    @Before
    public void setUp() throws Exception {
        tempFile = File.createTempFile("nwsc_seq_test", ".dat");
        NwServiceConnectorSequenceStore.setStoreFileForTesting(tempFile);
    }

    @After
    public void tearDown() {
        NwServiceConnectorSequenceStore.setStoreFileForTesting(null);
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Test
    public void malformedExistingReservationCannotSilentlyRestartAtZero() throws Exception {
        java.nio.file.Files.write(tempFile.toPath(), "corrupt".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(IllegalStateException.class, NwServiceConnectorSequenceStore::load);
    }

    @Test
    public void writeFailurePreventsClaimingASequenceReservation() {
        NwServiceConnectorSequenceStore.setStoreFileForTesting(new File(tempFile, "not-a-directory"));
        assertThrows(IllegalStateException.class, () -> NwServiceConnectorSequenceStore.save(50));
    }

    @Test
    public void unsignedReservationCannotRollBackAfterOverflow() {
        assertThrows(IllegalStateException.class, () -> NwServiceConnectorSequenceStore.reserveAfter(-1L));
        assertThrows(IllegalStateException.class, () -> NwServiceConnectorSequenceStore.chooseSeed(1, -1L));
    }

    @Test
    public void testSaveAndLoad() {
        long saved = 50_000_000L;
        NwServiceConnectorSequenceStore.save(saved);

        long loaded = NwServiceConnectorSequenceStore.load();
        assertEquals(saved, loaded);
    }

    @Test
    public void testChooseSeedWithPersisted() {
        long clockSeed = 1_000_000L;
        long persisted = 5_000_000L;
        long seed = NwServiceConnectorSequenceStore.chooseSeed(clockSeed, persisted);

        assertEquals(persisted + 10_000_000L, seed);
    }

    @Test
    public void testChooseSeedWithClockGreater() {
        long clockSeed = 20_000_000L;
        long persisted = 5_000_000L;
        long seed = NwServiceConnectorSequenceStore.chooseSeed(clockSeed, persisted);

        assertEquals(clockSeed + 10_000_000L, seed);
    }

    @Test
    public void testChooseSeedWithNoPersisted() {
        long clockSeed = 1_000_000L;
        long persisted = 0L;
        long seed = NwServiceConnectorSequenceStore.chooseSeed(clockSeed, persisted);

        assertEquals(clockSeed, seed);
    }

    @Test
    public void testFallbackWhenNoFile() {
        if (tempFile.exists()) {
            tempFile.delete();
        }
        long loaded = NwServiceConnectorSequenceStore.load();
        assertEquals(0L, loaded);
    }
}
