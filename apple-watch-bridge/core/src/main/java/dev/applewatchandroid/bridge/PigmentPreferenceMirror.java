package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Original NPS semantics: a paired local value, distinct from a live Watch report.
 * Only the owned HAL writes this store. It never replays an outgoing operation. */
final class PigmentPreferenceMirror {
    enum Origin { REMOTE, LOCAL }
    record Entry(List<String> names, double sourceTimestamp, long updatedAt, Origin origin, UUID request) {
        Entry {
            if (!Double.isFinite(sourceTimestamp) || sourceTimestamp < 0 || updatedAt <= 0
                    || origin == null || (origin == Origin.LOCAL) != (request != null)
                    || origin == Origin.LOCAL && names == null) {
                throw new IllegalArgumentException("Invalid paired pigment mirror");
            }
            if (names != null) names = PigmentPreferenceCodec.names(names);
        }
    }
    record Snapshot(UUID pair, UUID epoch, Entry value, Entry automatic) {
        Snapshot { Objects.requireNonNull(pair); Objects.requireNonNull(epoch); Objects.requireNonNull(value); }
        Snapshot(UUID pair, UUID epoch, Entry value) { this(pair, epoch, value, null); }
        boolean writable(long now) {
            return value.names() != null && now > 0 && value.updatedAt() <= now
                    && value.sourceTimestamp() <= now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS + 5;
        }
        boolean manualWritable(long now) {
            return writable(now) && automatic != null && automatic.names() != null && automatic.updatedAt() <= now
                    && automatic.sourceTimestamp() <= now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS + 5;
        }
    }
    private static final int LEGACY_MAGIC = 0x50474d31, MAGIC = 0x50474d32, MAX_STATE = 256 * 1024;
    private final UUID pair;
    private final Path file;
    private Entry value, automatic;
    private boolean unavailable;

    PigmentPreferenceMirror(Path root, UUID pair) throws IOException {
        this.pair = Objects.requireNonNull(pair);
        file = root.resolve(pair.toString()).resolve("state.bin");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_STATE)
                throw new IOException("Invalid paired pigment store");
            byte[] bytes = Files.readAllBytes(file);
            try { decode(bytes); }
            finally { Arrays.fill(bytes, (byte) 0); }
        }
    }

    synchronized Snapshot snapshot(UUID epoch) {
        return unavailable || value == null ? null : new Snapshot(pair, epoch, value, automatic);
    }
    synchronized boolean available() { return !unavailable; }

    synchronized boolean observe(List<PigmentPreferenceCodec.Report> reports, long now) throws IOException {
        return observe(reports, List.of(), now);
    }

    synchronized boolean observe(List<PigmentPreferenceCodec.Report> reports,
                                 List<PigmentPreferenceCodec.Report> automaticReports, long now) throws IOException {
        if (unavailable) throw new IOException("Reload paired pigment store before continuing");
        if (now <= 0) throw new IllegalArgumentException("Missing pigment receipt time");
        Entry next = observed(value, reports, now), nextAuto = observed(automatic, automaticReports, now);
        if (Objects.equals(next, value) && Objects.equals(nextAuto, automatic)) return false;
        commit(next, nextAuto);
        return true;
    }

    private static Entry observed(Entry previous, List<PigmentPreferenceCodec.Report> reports, long now) {
        Entry next = previous;
        for (var report : reports) {
            if (report.sourceTimestamp() > now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS + 5)
                continue;
            if (next != null && report.sourceTimestamp() < next.sourceTimestamp()) continue;
            if (next != null && report.sourceTimestamp() == next.sourceTimestamp()) {
                if (!PigmentPreferenceCodec.sameNames(report.names(), next.names()) || next.origin() == Origin.REMOTE) continue;
                // A genuine Watch echo of the same set changes provenance,
                // even if NSSet.allObjects serializes it in a different order.
            }
            next = new Entry(report.names(), report.sourceTimestamp(), now, Origin.REMOTE, null);
        }
        return next;
    }

    /** Persist the exact full value and timestamp BEFORE handing bytes to IDS.
     * A failed/uncertain send remains a local preference, not observed Watch state. */
    synchronized byte[] prepare(PigmentPreferenceCommand.Command command, UUID epoch,
                                UUID request, long now) throws IOException {
        if (unavailable) throw new IOException("Reload paired pigment store before continuing");
        Objects.requireNonNull(request);
        var baseline = snapshot(epoch);
        PigmentPreferenceCommand.requireMirror(command, baseline, pair, epoch, now);
        double source = Math.max(now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS,
                Math.nextUp(Math.max(value.sourceTimestamp(), automatic.sourceTimestamp())));
        if (!Double.isFinite(source) || source > now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS + 5)
            throw new IllegalArgumentException("Pigment clock cannot advance safely");
        var next = new Entry(PigmentPreferenceCodec.merge(value.names(), command.changes()),
                source, now, Origin.LOCAL, request);
        var nextAuto = new Entry(PigmentPreferenceCodec.removeAutomatic(automatic.names(), command.changes()),
                source, now, Origin.LOCAL, request);
        byte[] wire = PigmentPreferenceCodec.encodeManualAt(
                new PigmentPreferenceCodec.Report(value.names(), value.sourceTimestamp()),
                new PigmentPreferenceCodec.Report(automatic.names(), automatic.sourceTimestamp()), command.changes(), source);
        try { commit(next, nextAuto); return wire; }
        catch (IOException | RuntimeException failure) { Arrays.fill(wire, (byte) 0); throw failure; }
    }

    private void commit(Entry next, Entry nextAuto) throws IOException {
        byte[] bytes = encode(next, nextAuto);
        try { ClockFaceSyncJournal.atomicWrite(file, bytes); value = next; automatic = nextAuto; }
        catch (IOException failure) {
            // Rename may have completed before directory fsync failed. Reopen
            // and validate disk state instead of using an older in-memory value.
            unavailable = true;
            throw failure;
        }
        finally { Arrays.fill(bytes, (byte) 0); }
    }

    private byte[] encode(Entry entry, Entry autoEntry) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); uuid(out, pair);
            out.writeBoolean(entry != null); if (entry != null) writeEntry(out, entry);
            out.writeBoolean(autoEntry != null); if (autoEntry != null) writeEntry(out, autoEntry);
        }
        byte[] body = bytes.toByteArray(), digest = digest(body);
        try { bytes.write(digest); return bytes.toByteArray(); }
        finally { Arrays.fill(body, (byte) 0); Arrays.fill(digest, (byte) 0); }
    }

    private void decode(byte[] bytes) throws IOException {
        if (bytes.length < 52 || bytes.length > MAX_STATE) throw new IOException("Invalid pigment store size");
        byte[] body = Arrays.copyOf(bytes, bytes.length - 32), checksum = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        byte[] digest = digest(body);
        try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
            int magic = in.readInt();
            if (!MessageDigest.isEqual(checksum, digest) || (magic != MAGIC && magic != LEGACY_MAGIC) || !uuid(in).equals(pair))
                throw new IOException("Invalid paired pigment store identity");
            Entry selected = magic == LEGACY_MAGIC || present(in) ? readEntry(in) : null;
            Entry autoEntry = magic == MAGIC && present(in) ? readEntry(in) : null;
            if (in.available() != 0) throw new IOException("Trailing pigment store bytes");
            if (selected == null && autoEntry == null) throw new IOException("Empty paired pigment state");
            value = selected; automatic = autoEntry;
        } catch (IllegalArgumentException malformed) { throw new IOException("Invalid paired pigment value", malformed); }
        finally { Arrays.fill(body, (byte) 0); Arrays.fill(checksum, (byte) 0); Arrays.fill(digest, (byte) 0); }
    }

    static boolean present(DataInputStream in) throws IOException {
        int flag = in.readUnsignedByte();
        if (flag > 1) throw new IOException("Invalid pigment presence flag");
        return flag == 1;
    }

    static void writeEntry(DataOutputStream out, Entry entry) throws IOException {
        out.writeDouble(entry.sourceTimestamp()); out.writeLong(entry.updatedAt());
        out.writeByte(entry.origin().ordinal());
        if (entry.request() != null) uuid(out, entry.request());
        out.writeInt(entry.names() == null ? -1 : entry.names().size());
        if (entry.names() != null) for (String name : entry.names()) out.writeUTF(name);
    }
    static Entry readEntry(DataInputStream in) throws IOException {
        double source = in.readDouble(); long time = in.readLong(); int flag = in.readUnsignedByte();
        if (flag > 1) throw new IOException("Invalid pigment provenance");
        Origin origin = Origin.values()[flag]; UUID request = origin == Origin.LOCAL ? uuid(in) : null;
        int count = in.readInt();
        if (count < -1 || count > PigmentPreferenceCodec.MAX_NAMES) throw new IOException("Invalid pigment count");
        List<String> names = count == -1 ? null : new ArrayList<>();
        for (int i = 0; i < count; i++) names.add(in.readUTF());
        return new Entry(names, source, time, origin, request);
    }
    static void uuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }
    static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
