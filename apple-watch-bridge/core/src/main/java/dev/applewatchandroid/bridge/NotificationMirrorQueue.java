package dev.applewatchandroid.bridge;

import java.io.*;
import java.util.*;
import java.util.function.Function;

/** Durable mirror state. Acknowledged means an IDS receipt, never visible/applied. */
final class NotificationMirrorQueue {
    static final int MAX_ENTRIES = 256, MAX_PAYLOAD = 131_072, MAX_BYTES = 2_097_152;
    private static final int MAGIC = 0x41574e51;
    record Entry(NotificationIdentityIndex.Identity identity, UUID revision, byte[] fingerprint,
                 byte[] payload, boolean removal, boolean pending, boolean replyClaimed, boolean dismissClaimed) { }
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    List<Entry> entries() { return List.copyOf(entries.values()); }
    List<Entry> pending() {
        List<Entry> pending = new ArrayList<>();
        for (Entry e : entries.values()) if (e.pending) pending.add(e);
        return List.copyOf(pending);
    }
    NotificationIdentityIndex.Identity match(String publisher, String record, String section) {
        Entry e = entries.get(publisher);
        return e != null && !e.removal && e.identity.sectionId().equals(section)
                && (record == null || e.identity.recordId().equals(record)) ? e.identity : null;
    }
    NotificationIdentityIndex.Identity matchLights(String publisher, String section, String token) {
        for (Entry e : entries.values()) {
            var i = e.identity;
            if (!e.removal && (token != null ? i.replyToken().equals(token)
                    : publisher != null && section != null && i.publisherId().equals(publisher) && i.sectionId().equals(section))
                    && (publisher == null || i.publisherId().equals(publisher))
                    && (section == null || i.sectionId().equals(section))) return i;
        }
        return null;
    }
    private Entry active(String key) {
        for (Entry e : entries.values()) if (!e.removal && e.identity.androidKey().equals(key)) return e;
        return null;
    }
    boolean hasActive(String key) { return active(key) != null; }
    static String replyIdentifier(NotificationIdentityIndex.Identity identity) {
        return bounded(identity.publisherId() + ".reply." + identity.replyToken(), 128);
    }
    /** Persist before invoking Android; after an uncertain crash never silently send twice. */
    boolean claimReply(String publisher, String record, String section, String identifier) {
        var identity = match(publisher, record, section);
        if (identity == null || !replyIdentifier(identity).equals(identifier)) return false;
        Entry old = entries.get(publisher);
        if (old.replyClaimed) return false;
        entries.put(publisher, new Entry(identity, old.revision, old.fingerprint, old.payload,
                false, old.pending, true, old.dismissClaimed));
        return true;
    }
    boolean claimDismiss(String publisher, String record, String section) {
        var identity = match(publisher, record, section);
        if (identity == null) return false;
        Entry old = entries.get(publisher);
        if (old.dismissClaimed) return false;
        entries.put(publisher, new Entry(identity, old.revision, old.fingerprint, old.payload,
                false, old.pending, old.replyClaimed, true));
        return true;
    }
    boolean upsert(String key, String section, byte[] fingerprint,
                   Function<NotificationIdentityIndex.Identity, byte[]> encoder) {
        bounded(key, 4096); bounded(section, 512);
        if (fingerprint == null || fingerprint.length != 32) throw new IllegalArgumentException("Invalid fingerprint");
        Entry old = active(key);
        if (old != null && !old.identity.sectionId().equals(section)) throw new IllegalArgumentException("Notification key changed section");
        if (old != null && Arrays.equals(old.fingerprint, fingerprint)) return false;
        String publisher = old == null ? UUID.randomUUID().toString().toUpperCase(Locale.ROOT) : old.identity.publisherId();
        var identity = new NotificationIdentityIndex.Identity(key, section, publisher,
                old == null ? publisher : old.identity.recordId(), UUID.randomUUID().toString());
        byte[] payload = encoder.apply(identity);
        try {
            if (payload == null || payload.length < 1 || payload.length > MAX_PAYLOAD) throw new IllegalArgumentException("Invalid bulletin size");
            Entry next = new Entry(identity, UUID.randomUUID(), fingerprint.clone(), payload.clone(), false, true,
                    false, old != null && old.dismissClaimed);
            replace(next, old);
            return true;
        } finally { wipe(payload); }
    }
    boolean remove(String key) {
        Entry old = active(key);
        if (old == null) return false;
        var i = old.identity;
        byte[] payload = new BulletinDistributorCodec.RemoveBulletinRequest(i.publisherId(), i.recordId(), i.sectionId()).encode();
        replace(new Entry(i, UUID.randomUUID(), old.fingerprint.clone(), payload, true, true, old.replyClaimed, old.dismissClaimed), old);
        return true;
    }
    /** Only exact revision receipt can retire work; receipt carries no UI-effect claim. */
    boolean acknowledge(UUID revision) {
        for (Entry e : entries.values()) {
            if (!e.revision.equals(revision) || !e.pending) continue;
            if (e.removal) { entries.remove(e.identity.publisherId()); wipe(e.payload); wipe(e.fingerprint); }
            else entries.put(e.identity.publisherId(), new Entry(e.identity, e.revision, e.fingerprint, e.payload,
                    false, false, e.replyClaimed, e.dismissClaimed));
            return true;
        }
        return false;
    }
    private void replace(Entry next, Entry old) {
        if (old == null && entries.size() >= MAX_ENTRIES) { wipe(next.payload); wipe(next.fingerprint); throw new IllegalStateException("Notification journal full"); }
        entries.put(next.identity.publisherId(), next);
        try { byte[] encoded = encode(); wipe(encoded); }
        catch (RuntimeException tooLarge) {
            if (old == null) entries.remove(next.identity.publisherId()); else entries.put(old.identity.publisherId(), old);
            wipe(next.payload); wipe(next.fingerprint); throw tooLarge;
        }
        if (old != null) { wipe(old.payload); wipe(old.fingerprint); }
    }
    byte[] encode() {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(MAGIC); out.writeByte(2); out.writeInt(entries.size());
            for (Entry e : entries.values()) {
                var i = e.identity;
                out.writeUTF(i.androidKey()); out.writeUTF(i.sectionId()); out.writeUTF(i.publisherId());
                out.writeUTF(i.recordId()); out.writeUTF(i.replyToken());
                out.writeLong(e.revision.getMostSignificantBits()); out.writeLong(e.revision.getLeastSignificantBits());
                out.write(e.fingerprint); out.writeBoolean(e.removal); out.writeBoolean(e.pending);
                out.writeBoolean(e.replyClaimed); out.writeBoolean(e.dismissClaimed);
                out.writeInt(e.payload.length); out.write(e.payload);
                if (buffer.size() > MAX_BYTES) throw new IllegalArgumentException("Notification journal byte limit");
            }
            return buffer.toByteArray();
        } catch (IOException impossible) { throw new IllegalArgumentException("Notification serialization failed", impossible); }
    }
    static NotificationMirrorQueue decode(byte[] data) {
        NotificationMirrorQueue queue = new NotificationMirrorQueue();
        try {
            if (data == null || data.length > MAX_BYTES) throw new IllegalArgumentException("Invalid journal size");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("Invalid journal format");
            int version = in.readUnsignedByte();
            if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported journal version");
            int count = in.readInt();
            if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid journal count");
            Set<String> activeKeys = new HashSet<>(), tokens = new HashSet<>();
            for (int n = 0; n < count; n++) {
                String key = bounded(in.readUTF(), 4096), section = bounded(in.readUTF(), 512),
                        publisher = bounded(in.readUTF(), 128), record = bounded(in.readUTF(), 128), token = bounded(in.readUTF(), 128);
                UUID revision = new UUID(in.readLong(), in.readLong());
                byte[] fingerprint = in.readNBytes(32);
                if (fingerprint.length != 32) throw new IllegalArgumentException("Truncated fingerprint");
                boolean removal = flag(in), pending = flag(in);
                boolean replyClaimed = version >= 2 && flag(in), dismissClaimed = version >= 2 && flag(in);
                int length = in.readInt();
                if (length < 1 || length > MAX_PAYLOAD || length > in.available()) throw new IllegalArgumentException("Invalid journal payload length");
                if (queue.entries.containsKey(publisher) || !tokens.add(token) || (!removal && !activeKeys.add(key))
                        || (removal && !pending)) throw new IllegalArgumentException("Ambiguous journal identity");
                byte[] payload = in.readNBytes(length);
                if (removal) {
                    var request = BulletinDistributorCodec.RemoveBulletinRequest.decodeExact(payload);
                    if (!publisher.equals(request.publisherBulletinId) || !record.equals(request.recordId)
                            || !section.equals(request.sectionId)) { wipe(payload); throw new IllegalArgumentException("Removal identity mismatch"); }
                }
                queue.entries.put(publisher, new Entry(new NotificationIdentityIndex.Identity(key, section, publisher, record, token),
                        revision, fingerprint, payload, removal, pending, replyClaimed, dismissClaimed));
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing journal data");
            return queue;
        } catch (IOException | RuntimeException invalid) {
            queue.destroy(); throw new IllegalArgumentException("Invalid notification journal", invalid);
        }
    }
    private static boolean flag(DataInputStream in) throws IOException {
        int flag = in.readUnsignedByte(); if (flag > 1) throw new IllegalArgumentException("Invalid boolean"); return flag == 1;
    }
    private static String bounded(String s, int limit) {
        if (s == null || s.isBlank() || s.length() > limit) throw new IllegalArgumentException("Invalid notification identity"); return s;
    }
    void destroy() { for (Entry e : entries.values()) { wipe(e.payload); wipe(e.fingerprint); } entries.clear(); }
    private static void wipe(byte[] b) { if (b != null) Arrays.fill(b, (byte) 0); }
}
