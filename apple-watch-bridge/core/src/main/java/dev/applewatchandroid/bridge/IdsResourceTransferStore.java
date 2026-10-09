package dev.applewatchandroid.bridge;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Native IDSFoundation command 0x16: one sequence/UUID for the whole file.
 * First chunk is a plist; its data and subsequent chunks start with a BE64
 * file offset. Never use sequence alone to discard resource chunks. */
final class IdsResourceTransferStore {
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final String PREFIX = "ids-message-resource-transfer-";

    static PairedSyncResourceStore.Result append(IdsSocketPairCodec.DataMessage message, Path parent)
            throws IOException {
        byte[] payload = message.payload;
        boolean legacyHeader = payload != null && payload.length >= 4 && payload[0] == 1
                && payload[1] == 0x1f && (payload[2] & 255) == 0x8b;
        byte[] unwrapped = null;
        if (legacyHeader) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload, 1, payload.length - 1));
                 ByteArrayOutputStream plain = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = gzip.read(buffer)) > 0) {
                    if (plain.size() + count > 1024 * 1024) throw new IllegalArgumentException("Resource header exceeds limit");
                    plain.write(buffer, 0, count);
                }
                unwrapped = plain.toByteArray();
                payload = unwrapped;
            }
        }
        boolean initial = payload != null && payload.length >= 8
                && Arrays.equals(Arrays.copyOf(payload, 8), "bplist00".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Path directory = parent.resolve("native");
        Path metadata = directory.resolve(message.messageUuid + ".meta");
        if (!initial && !Files.isRegularFile(metadata)) return null;
        long total;
        String name;
        boolean legacy;
        byte[] chunk = payload;
        Object decoded = null;
        try {
            if (initial) {
                decoded = AppleBinaryPropertyList.decode(payload);
                if (legacyHeader && decoded instanceof Map<?, ?> old && !old.containsKey(PREFIX + "total-bytes")) return null;
                if (!(decoded instanceof Map<?, ?> values)
                        || !(values.get(PREFIX + "total-bytes") instanceof Number size)
                        || !(values.get(PREFIX + "url") instanceof String url)
                        || !(values.get(PREFIX + "data") instanceof byte[] data)) {
                    throw new IllegalArgumentException("Resource header is incomplete");
                }
                total = size.longValue();
                legacy = legacyHeader;
                name = url.substring(url.lastIndexOf('/') + 1);
                if (total < 0 || total > MAX_FILE_BYTES || size.doubleValue() != (double) total
                        || !name.matches("[A-Za-z0-9._+\\-]{1,180}") || name.contains("..")) {
                    throw new IllegalArgumentException("Resource file size/name is invalid");
                }
                chunk = data;
            } else {
                try (DataInputStream input = new DataInputStream(Files.newInputStream(metadata))) {
                    total = input.readLong();
                    name = input.readUTF();
                    legacy = input.readBoolean();
                }
            }
            // Watch JW header data is raw, but its continuation chunks still
            // carry BE64 offsets. Live 326 had 4000 raw header bytes followed
            // by offsets 4000 and 8000 in 4008-byte chunks. nextMessage_old
            // calls readNextBytes; 23S303 readNextBytes:byteOffset: prepends
            // the offset when resumeResourceTransfers is false (1828086a4).
            int prefixBytes = legacy && initial ? 0 : 8;
            if (chunk == null || chunk.length < prefixBytes) throw new IllegalArgumentException("Resource offset is missing");
            Path partial = directory.resolve(message.messageUuid + ".partial");
            Path complete = directory.resolve(message.messageUuid + "-" + name);
            // A raw JW initial header starts from zero. Continuations in
            // either format use the explicit offset and permit exact retries.
            long offset = legacy && initial ? 0
                    : ByteBuffer.wrap(chunk, 0, 8).getLong();
            int length = chunk.length - prefixBytes;
            if (offset < 0 || offset > total || length > total - offset) {
                throw new IllegalArgumentException("Resource chunk exceeds declared file");
            }
            Files.createDirectories(parent);
            if (!Files.exists(directory)) Files.createDirectory(directory,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            if (initial) {
                if (Files.exists(metadata)) {
                    try (DataInputStream input = new DataInputStream(Files.newInputStream(metadata))) {
                        if (input.readLong() != total || !input.readUTF().equals(name) || input.readBoolean() != legacy) {
                            throw new IllegalArgumentException("Resource header changed during transfer");
                        }
                    }
                } else {
                    if (offset != 0) throw new IllegalArgumentException("Initial resource offset is not zero");
                    Files.createFile(metadata, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rw-------")));
                    try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(metadata))) {
                        output.writeLong(total);
                        output.writeUTF(name);
                        output.writeBoolean(legacy);
                    }
                }
            }
            Path target = Files.exists(complete) ? complete : partial;
            if (!Files.exists(target)) Files.createFile(target, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rw-------")));
            int written = 0;
            long received;
            try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
                if (legacy && initial && target.equals(partial)) file.setLength(0);
                received = file.length();
                if (offset > received) throw new IllegalArgumentException(
                        "Resource chunk leaves a gap: offset=" + offset + " received=" + received
                                + " chunkBytes=" + length);
                if (offset < received) {
                    if (length > received - offset) throw new IllegalArgumentException("Resource chunks overlap partially");
                    file.seek(offset);
                    for (int i = prefixBytes; i < chunk.length; i++) {
                        if (file.readUnsignedByte() != (chunk[i] & 255)) {
                            throw new IllegalArgumentException("Repeated resource chunk changed");
                        }
                    }
                } else {
                    file.seek(offset);
                    file.write(chunk, prefixBytes, length);
                    file.getFD().sync();
                    written = length;
                    received += length;
                }
            }
            if (received == total && !target.equals(complete)) {
                Files.move(partial, complete, StandardCopyOption.ATOMIC_MOVE);
            }
            return new PairedSyncResourceStore.Result(written, received,
                    received == total ? "native/" + complete.getFileName() : null);
        } finally {
            IdsMessageProtectionIdentity.wipeValues(decoded);
            if (unwrapped != null) Arrays.fill(unwrapped, (byte) 0);
        }
    }
}
