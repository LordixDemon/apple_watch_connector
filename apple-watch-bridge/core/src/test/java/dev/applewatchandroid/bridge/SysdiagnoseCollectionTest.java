package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SysdiagnoseCollectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final byte[] nonce = "6B48CE92-7491-49EC-B1D7-231F0B550127".getBytes(StandardCharsets.US_ASCII);

    @Test public void explicitCollectionOnlyAndPrivateBoundedStatus() throws Exception {
        var collection = new SysdiagnoseCollection();
        var parent = temporary.getRoot().toPath();
        byte[] status = "synthetic-status".getBytes(StandardCharsets.UTF_8);
        assertNull(collection.receive(5, status, parent));
        collection.queued(6, nonce);
        assertNull(collection.receive(5, status, parent));
        collection.queued(1, nonce);
        var file = collection.receive(5, status, parent);
        assertArrayEquals(status, Files.readAllBytes(file));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertThrows(IllegalArgumentException.class, () -> collection.receive(4,
                new byte[SysdiagnoseArchiveInventory.MAX_BYTES + 1], parent));
        collection.reset();
        assertNull(collection.receive(5, status, parent));
    }

    @Test public void cancellationAndUnboundedTransferSelectorsRemainUnsupported() {
        SysdiagnoseCollection.requireDiagnosticRequest(8, nonce);
        SysdiagnoseCollection.requireDiagnosticRequest(8,
                "sysdiagnose_1970.01.01_12-42-01+0200_Watch-OS_Watch_23S303.tar.gz".getBytes(StandardCharsets.US_ASCII));
        SysdiagnoseCollection.requireDiagnosticRequest(3, "CompanionTimedout".getBytes(StandardCharsets.US_ASCII));
        for (int type : new int[]{2, 3, 4, 5, 7, 9}) {
            assertThrows(IllegalArgumentException.class, () -> SysdiagnoseCollection.requireDiagnosticRequest(type, nonce));
        }
        assertThrows(IllegalArgumentException.class, () -> SysdiagnoseCollection.requireDiagnosticRequest(1, new byte[36]));
        assertThrows(IllegalArgumentException.class, () -> SysdiagnoseCollection.requireDiagnosticRequest(8,
                "../archive".getBytes(StandardCharsets.UTF_8)));
    }
}
