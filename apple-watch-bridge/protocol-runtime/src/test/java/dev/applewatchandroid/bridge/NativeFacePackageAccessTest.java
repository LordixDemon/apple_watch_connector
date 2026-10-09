package dev.applewatchandroid.bridge;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeFacePackageAccessTest {
    @Test public void validatesPairBeforeCallingPlatform() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        NativeFacePackageAccess.installPlatformPreparer((files, pair) -> called.set(true));
        try {
            assertThrows(IllegalArgumentException.class, () -> NativeFacePackageAccess.prepare(Path.of("."), "../other"));
            assertFalse(called.get());
            NativeFacePackageAccess.prepare(Path.of("."), "4a8c08cd-7bdb-5718-b9f6-316651d517b5");
            assertTrue(called.get());
        } finally { NativeFacePackageAccess.installPlatformPreparer((files, pair) -> { }); }
    }

    @Test public void platformFailureIsReportedToExportTransaction() {
        NativeFacePackageAccess.installPlatformPreparer((files, pair) -> { throw new java.io.IOException("label failure"); });
        try {
            assertThrows(java.io.IOException.class, () -> NativeFacePackageAccess.prepare(Path.of("."),
                    "4a8c08cd-7bdb-5718-b9f6-316651d517b5"));
        } finally { NativeFacePackageAccess.installPlatformPreparer((files, pair) -> { }); }
    }
}
