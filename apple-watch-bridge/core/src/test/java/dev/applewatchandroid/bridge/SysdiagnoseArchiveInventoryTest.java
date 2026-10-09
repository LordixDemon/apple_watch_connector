package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class SysdiagnoseArchiveInventoryTest {
    private static final byte[] NONCE = "6B48CE92-7491-49EC-B1D7-231F0B550127".getBytes(StandardCharsets.US_ASCII);
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeInventoryRequiresMatchingRequestAndPersistsPrivately() throws Exception {
        SysdiagnoseArchiveInventory inventory = new SysdiagnoseArchiveInventory();
        Path parent = temporary.getRoot().toPath();
        byte[] nativeReply = fixture("list-native.bplist");
        assertNull(inventory.receive(7, nativeReply, parent));
        inventory.queued(6, NONCE);
        assertNull(inventory.receive(7, fixture("mismatch-native.bplist"), parent));
        assertNull(inventory.receive(9, nativeReply, parent));
        SysdiagnoseArchiveInventory.Observation result = inventory.receive(7, nativeReply, parent);
        assertTrue(result.correlated);
        assertEquals(new String(NONCE, StandardCharsets.US_ASCII), result.correlation);
        assertEquals(1, result.entries);
        assertArrayEquals(nativeReply, Files.readAllBytes(result.file));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(result.file.getParent()));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(result.file));
        assertNull(inventory.receive(7, nativeReply, parent));
    }

    @Test public void nativeEmptyInventoryIsExplicitlyUncorrelated() throws Exception {
        SysdiagnoseArchiveInventory inventory = new SysdiagnoseArchiveInventory();
        inventory.queued(6, NONCE);
        SysdiagnoseArchiveInventory.Observation result = inventory.receive(7, fixture("empty-native.bplist"), temporary.getRoot().toPath());
        assertFalse(result.correlated);
        assertNull(result.correlation);
        assertEquals(0, result.entries);
        assertNull(inventory.receive(7, fixture("empty-native.bplist"), temporary.getRoot().toPath()));
    }

    @Test public void rejectCollectionCancellationTransferAndNonUuidRequests() {
        SysdiagnoseArchiveInventory.requireReadOnlyRequest(6, NONCE);
        for (int type : new int[]{1, 2, 3, 4, 5, 7, 8, 9}) {
            assertThrows(IllegalArgumentException.class, () -> SysdiagnoseArchiveInventory.requireReadOnlyRequest(type, NONCE));
        }
        for (byte[] payload : new byte[][]{null, new byte[0], new byte[36], "../archive".getBytes(StandardCharsets.UTF_8)}) {
            assertThrows(IllegalArgumentException.class, () -> SysdiagnoseArchiveInventory.requireReadOnlyRequest(6, payload));
        }
        assertEquals(IdsUtunConnectionName.PROTECTION_CLASS_C,
                IdsApplicationRoute.forTopic(SysdiagnoseArchiveInventory.TOPIC).idsProtectionClass);
    }

    @Test public void malformedOversizedAndStorageFailureDoNotConsumeRequest() throws Exception {
        SysdiagnoseArchiveInventory inventory = new SysdiagnoseArchiveInventory();
        inventory.queued(6, NONCE);
        Path parent = temporary.getRoot().toPath();
        assertThrows(IllegalArgumentException.class, () -> inventory.receive(7, new byte[SysdiagnoseArchiveInventory.MAX_BYTES + 1], parent));
        assertThrows(IllegalArgumentException.class, () -> inventory.receive(7, "not an archive".getBytes(StandardCharsets.UTF_8), parent));
        assertThrows(IOException.class, () -> inventory.receive(7, fixture("list-native.bplist"), parent.resolve("missing")));
        assertNotNull(inventory.receive(7, fixture("list-native.bplist"), parent));
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/sysdiagnose/" + name)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
}
