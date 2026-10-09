package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Owned per-pair local preference. LOCAL is pending/uncertain, never Watch state.
 * Opening this store does not replay an operation or manufacture an observation. */
final class MonogramPreferenceMirror {
    enum Origin { REMOTE, LOCAL }
    record Entry(String text, double sourceTimestamp, long updatedAt, Origin origin, UUID request) {
        Entry {
            if (text != null && !NativeMonogramTextRules.valid(text)
                    || !Double.isFinite(sourceTimestamp) || sourceTimestamp < 0 || updatedAt <= 0
                    || origin == null || (origin == Origin.LOCAL) != (request != null)
                    || origin == Origin.LOCAL && text == null) throw new IllegalArgumentException("Invalid monogram entry");
        }
    }
    record Snapshot(UUID pair, UUID epoch, Entry value) {
        Snapshot { Objects.requireNonNull(pair); Objects.requireNonNull(epoch); }
        boolean writable(long now) {
            return now > WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS * 1000L && (value == null || value.updatedAt() <= now
                && value.sourceTimestamp() <= now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS + 5);
        }
        String revision() {
            byte[] bytes = entryBytes(value), hash = digest(bytes);
            try {
                char[] hex = new char[hash.length * 2];
                for (int i = 0; i < hash.length; i++) {
                    hex[i * 2] = Character.forDigit((hash[i] & 255) >>> 4, 16);
                    hex[i * 2 + 1] = Character.forDigit(hash[i] & 15, 16);
                }
                return new String(hex);
            } finally { Arrays.fill(bytes, (byte) 0); Arrays.fill(hash, (byte) 0); }
        }
    }
    private static final int MAGIC = 0x4d474d31, MAX_STATE = 512;
    private final UUID pair;
    private final Path file;
    private Entry value;
    private boolean unavailable;
    MonogramPreferenceMirror(Path root, UUID pair) throws IOException {
        this.pair = Objects.requireNonNull(pair);
        file = root.resolve(pair.toString()).resolve("state.bin");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_STATE) throw new IOException("Invalid monogram store");
        byte[] bytes = Files.readAllBytes(file);
        try {
            if (bytes.length < 53 || bytes.length > MAX_STATE) throw new IOException("Invalid monogram size");
            byte[] body = Arrays.copyOf(bytes, bytes.length - 32);
            byte[] checksum = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
            try (var in = new DataInputStream(new ByteArrayInputStream(body))) {
                if (!MessageDigest.isEqual(digest(body), checksum) || in.readInt() != MAGIC || !uuid(in).equals(pair)) throw new IOException("Invalid monogram identity/checksum");
                value = readEntry(in);
                if (value == null || in.available() != 0) throw new IOException("Empty/trailing monogram store");
            } finally { Arrays.fill(body, (byte) 0); Arrays.fill(checksum, (byte) 0); }
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid monogram state", invalid); }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    synchronized boolean available() { return !unavailable; }
    synchronized Snapshot snapshot(UUID epoch) {
        if (unavailable) throw new IllegalStateException("Reload monogram store");
        return new Snapshot(pair, epoch, value);
    }
    synchronized boolean observe(List<MonogramPreferenceCodec.Report> reports, long now) throws IOException {
        if (unavailable || now <= 0) throw new IOException("Invalid monogram observation context");
        Entry next = value;
        for (var report : reports) {
            if (report.sourceTimestamp() > now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS + 5) continue;
            if (next != null && report.sourceTimestamp() < next.sourceTimestamp()) continue;
            if (next != null && report.sourceTimestamp() == next.sourceTimestamp()
                    && (!Objects.equals(report.text(), next.text()) || next.origin() == Origin.REMOTE)) continue;
            next = new Entry(report.text(), report.sourceTimestamp(), now, Origin.REMOTE, null);
        }
        if (Objects.equals(next, value)) return false;
        commit(next); return true;
    }
    synchronized byte[] prepare(MonogramPreferenceCommand.Command command, UUID epoch, UUID request, long now) throws IOException {
        if (unavailable) throw new IOException("Reload monogram store");
        MonogramPreferenceCommand.requireCurrent(command, snapshot(epoch), now);
        Objects.requireNonNull(request);
        double clock = now / 1000.0 - WatchSettingsCodec.APPLE_EPOCH_UNIX_SECONDS;
        double stamp = value == null ? clock : Math.max(clock, Math.nextUp(value.sourceTimestamp()));
        if (!Double.isFinite(stamp) || stamp < 0 || stamp > clock + 5) throw new IllegalArgumentException("Monogram clock cannot advance");
        byte[] wire = MonogramPreferenceCodec.encodeChangeAt(command.text(), stamp);
        try { commit(new Entry(command.text(), stamp, now, Origin.LOCAL, request)); return wire; }
        catch (IOException | RuntimeException failure) { Arrays.fill(wire, (byte) 0); throw failure; }
    }
    private void commit(Entry next) throws IOException {
        byte[] bytes;
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) { out.writeInt(MAGIC); uuid(out, pair); writeEntry(out, next); }
        byte[] body = buffer.toByteArray();
        try { buffer.write(digest(body)); bytes = buffer.toByteArray(); }
        finally { Arrays.fill(body, (byte) 0); }
        try { ClockFaceSyncJournal.atomicWrite(file, bytes); value = next; }
        catch (IOException failure) { unavailable = true; throw failure; }
        finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static byte[] entryBytes(Entry entry) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) { writeEntry(out, entry); }
        catch (IOException impossible) { throw new AssertionError(impossible); }
        return bytes.toByteArray();
    }
    static void writeEntry(DataOutputStream out, Entry entry) throws IOException {
        out.writeByte(entry == null ? 0 : 1);
        if (entry == null) return;
        out.writeDouble(entry.sourceTimestamp()); out.writeLong(entry.updatedAt()); out.writeByte(entry.origin().ordinal());
        if (entry.request() != null) uuid(out, entry.request());
        out.writeByte(entry.text() == null ? 0 : 1);
        if (entry.text() != null) out.writeUTF(entry.text());
    }
    static Entry readEntry(DataInputStream in) throws IOException {
        int present = in.readUnsignedByte();
        if (present == 0) return null;
        if (present != 1) throw new IOException("Invalid monogram presence");
        double stamp = in.readDouble(); long time = in.readLong(); int origin = in.readUnsignedByte();
        if (origin > 1) throw new IOException("Invalid monogram origin");
        UUID request = origin == 1 ? uuid(in) : null;
        int textFlag = in.readUnsignedByte();
        if (textFlag > 1) throw new IOException("Invalid monogram text presence");
        return new Entry(textFlag == 1 ? in.readUTF() : null, stamp, time, Origin.values()[origin], request);
    }
    static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
