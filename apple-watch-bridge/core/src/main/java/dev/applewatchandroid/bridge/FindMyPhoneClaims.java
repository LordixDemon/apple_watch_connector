package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Commit before Android effects, including a duplicate with a new IDS UUID. No replay after a crash. */
final class FindMyPhoneClaims {
    static final int MAX_FRAME = 2631;
    interface Backend { byte[] read() throws IOException; void write(byte[] bytes) throws IOException; }
    record Claim(String key, boolean fresh, Boolean previousResult) { }
    private record Entry(long claimedAt, Boolean completed) { }
    private final Backend backend;
    private LinkedHashMap<String, Entry> entries;
    FindMyPhoneClaims(Backend backend) throws IOException { this.backend = backend; entries = decode(backend.read()); }
    Claim claim(String pairingId, FindMyPhoneIpcCodec.Request request, long now) throws IOException {
        UUID pair = UUID.fromString(pairingId);
        String canonical = pair + "/" + request.type() + "/"
                + Long.toHexString(Double.doubleToLongBits(request.body().unixSeconds())) + "/" + request.behavior();
        String key;
        try { key = hex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        LinkedHashMap<String, Entry> next = new LinkedHashMap<>(entries);
        next.values().removeIf(entry -> now - entry.claimedAt >= 60_000);
        Entry existing = next.get(key);
        if (existing != null) return new Claim(key, false, existing.completed);
        if (now <= 0 || next.size() >= 64) throw new IOException("Phone Ping claim window full");
        next.put(key, new Entry(now, null));
        save(next);
        return new Claim(key, true, null);
    }
    void complete(Claim claim, boolean result) throws IOException {
        Entry entry = entries.get(claim.key);
        if (!claim.fresh || entry == null || entry.completed != null) throw new IOException("No pending phone Ping claim");
        LinkedHashMap<String, Entry> next = new LinkedHashMap<>(entries);
        next.put(claim.key, new Entry(entry.claimedAt, result));
        save(next);
    }
    private void save(LinkedHashMap<String, Entry> next) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(0x41574643); out.writeByte(1); out.writeShort(next.size());
        for (var entry : next.entrySet()) {
            String key = entry.getKey();
            for (int i = 0; i < key.length(); i += 2) out.writeByte(Integer.parseInt(key.substring(i, i + 2), 16));
            out.writeLong(entry.getValue().claimedAt);
            Boolean completed = entry.getValue().completed;
            out.writeByte(completed == null ? 0 : completed ? 2 : 1);
        }
        byte[] frame = bytes.toByteArray();
        try { backend.write(frame); entries = next; }
        finally { Arrays.fill(frame, (byte) 0); }
    }
    private static LinkedHashMap<String, Entry> decode(byte[] bytes) throws IOException {
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        if (bytes == null) throw new IOException("Missing phone Ping claims");
        try {
            if (bytes.length == 0) return result;
            if (bytes.length > MAX_FRAME) throw new IOException("Phone Ping claims oversized");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0x41574643 || in.readUnsignedByte() != 1) throw new IOException("Invalid phone Ping claims");
            int count = in.readUnsignedShort();
            if (count > 64 || in.available() != count * 41) throw new IOException("Invalid phone Ping claim count");
            for (int i = 0; i < count; i++) {
                String key = hex(in.readNBytes(32));
                long time = in.readLong(); int state = in.readUnsignedByte();
                if (time <= 0 || state > 2 || result.containsKey(key)) throw new IOException("Invalid phone Ping claim entry");
                result.put(key, new Entry(time, state == 0 ? null : state == 2));
            }
            return result;
        } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int i = 0; i < bytes.length; i++) {
            chars[2 * i] = digits.charAt((bytes[i] & 255) >>> 4);
            chars[2 * i + 1] = digits.charAt(bytes[i] & 15);
        }
        return new String(chars);
    }
}
