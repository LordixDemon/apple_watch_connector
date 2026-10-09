package dev.applewatchandroid.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class IdsResourceTransferStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeSameSequenceChunksReassembleExactlyAndRetriesAreIdempotent() throws Exception {
        Path parent = temporary.getRoot().toPath();
        long sequence = -1;
        String name = null;
        for (int i = 0; i < 5; i++) {
            var message = (IdsSocketPairCodec.DataMessage) IdsSocketPairCodec.decode(fixture("chunk-" + i + ".bin"));
            try {
                if (i == 0) sequence = message.sequence;
                assertEquals(sequence, message.sequence);
                var result = IdsResourceTransferStore.append(message, parent);
                assertEquals(1000, result.chunkBytes);
                assertEquals((i + 1) * 1000, result.totalBytes);
                if (i < 4) assertNull(result.savedName); else name = result.savedName;
                assertEquals(0, IdsResourceTransferStore.append(message, parent).chunkBytes);
            } finally { message.destroy(); }
        }
        assertNotNull(name);
        Path file = parent.resolve(name);
        assertArrayEquals(fixture("input.bin"), Files.readAllBytes(file));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
        assertFalse(Files.exists(parent.resolve("native/7C48B838-FE90-4B21-839D-1A93B1A5C111.partial")));
    }

    @Test public void missingAndConflictingChunksCannotCompleteAFile() throws Exception {
        Path parent = temporary.getRoot().toPath();
        var first = (IdsSocketPairCodec.DataMessage) IdsSocketPairCodec.decode(fixture("chunk-0.bin"));
        var third = (IdsSocketPairCodec.DataMessage) IdsSocketPairCodec.decode(fixture("chunk-2.bin"));
        try {
            IdsResourceTransferStore.append(first, parent);
            assertThrows(IllegalArgumentException.class, () -> IdsResourceTransferStore.append(third, parent));
            byte[] changed = third.payload.clone();
            Arrays.fill(changed, 0, 8, (byte) 0);
            var duplicate = new IdsSocketPairCodec.DataMessage(third.command, third.sequence,
                    third.streamId, third.flags, null, third.messageUuid, null, changed, null);
            try {
                assertThrows(IllegalArgumentException.class, () -> IdsResourceTransferStore.append(duplicate, parent));
            } finally { duplicate.destroy(); }
            assertEquals(0, IdsResourceTransferStore.append(first, parent).chunkBytes);
        } finally { first.destroy(); third.destroy(); }
    }

    @Test public void watchRawJwHeaderAndOffsetContinuationsReassembleExactly() throws Exception {
        // Live 326 and 23S303 readNextBytes: raw JW initial data, followed
        // by BE64 offset + bytes. The initial header format alone does not
        // determine whether the continuation has an offset.
        byte[] input = fixture("input.bin");
        byte[] header = AppleBinaryPropertyList.encode(java.util.Map.of(
                "ids-message-resource-transfer-total-bytes", input.length,
                "ids-message-resource-transfer-url", "/private/sysdiagnose_1970.01.01_12-42-01+0200_Watch-OS_Watch_23S303.tar.gz",
                "ids-message-resource-transfer-data", Arrays.copyOf(input, 1000)));
        var packed = new java.io.ByteArrayOutputStream();
        packed.write(1);
        try (var gzip = new java.util.zip.GZIPOutputStream(packed)) { gzip.write(header); }
        String uuid = "7C48B838-FE90-4B21-839D-1A93B1A5C222";
        String saved = null;
        for (int i = 0; i < 5; i++) {
            byte[] continuation = java.nio.ByteBuffer.allocate(1008)
                    .putLong(i * 1000L).put(input, i * 1000, 1000).array();
            var message = new IdsSocketPairCodec.DataMessage(IdsSocketPairCodec.COMMAND_RESOURCE_TRANSFER,
                    12, 1, 0, null, uuid, null,
                    i == 0 ? packed.toByteArray() : continuation, null);
            try {
                var result = IdsResourceTransferStore.append(message, temporary.getRoot().toPath());
                assertEquals(1000, result.chunkBytes);
                assertEquals((i + 1) * 1000, result.totalBytes);
                saved = result.savedName;
                if (i > 0) assertEquals(0, IdsResourceTransferStore.append(message,
                        temporary.getRoot().toPath()).chunkBytes);
            } finally { message.destroy(); }
        }
        assertTrue(saved.endsWith("12-42-01+0200_Watch-OS_Watch_23S303.tar.gz"));
        assertArrayEquals(input, Files.readAllBytes(temporary.getRoot().toPath().resolve(saved)));
    }

    private byte[] fixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/ids/resource-chunks/" + name)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
}
