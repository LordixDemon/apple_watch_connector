package dev.applewatchandroid.bridge;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static dev.applewatchandroid.bridge.BridgeCommandCodec.Stage.*;

/** Session-scoped IDs: deduplication, expiry, and exact IDS application correlation. */
final class OperationalRequestTracker {
    private static final long TIMEOUT_MS = 60_000;
    private final int capacity;
    private final Consumer<BridgeCommandCodec.Status> output;
    private final LinkedHashMap<UUID, Entry> entries = new LinkedHashMap<>();
    private UUID epoch;
    private static final class Entry {
        final BridgeCommandCodec.Request request;
        final long acceptedAt;
        BridgeCommandCodec.Stage stage = HAL_ACCEPTED;
        String messageUuid, topic;
        int responseType = -1;
        Entry(BridgeCommandCodec.Request request, long now) { this.request = request; acceptedAt = now; }
        boolean pending() { return stage == HAL_ACCEPTED || stage == IDS_QUEUED || stage == APP_ACK_RECEIVED; }
    }
    OperationalRequestTracker(int capacity, Consumer<BridgeCommandCodec.Status> output) {
        if (capacity < 1 || output == null) throw new IllegalArgumentException();
        this.capacity = capacity; this.output = output;
    }
    synchronized UUID newEpoch() {
        disconnect(); entries.clear(); epoch = UUID.randomUUID(); return epoch;
    }
    synchronized void disconnect() {
        for (Entry entry : entries.values()) {
            if (entry.pending()) emit(entry, entry.stage == HAL_ACCEPTED ? EXPIRED : UNKNOWN);
        }
        epoch = null;
    }
    synchronized boolean accept(BridgeCommandCodec.Request request, long now, BooleanSupplier enqueue) {
        expire(now);
        if (!request.epoch().equals(epoch) || now >= request.deadlineMs()) {
            output.accept(new BridgeCommandCodec.Status(request.id(), request.epoch(), EXPIRED)); return false;
        }
        if (request.deadlineMs() - now > TIMEOUT_MS) {
            output.accept(new BridgeCommandCodec.Status(request.id(), request.epoch(), REJECTED)); return false;
        }
        Entry existing = entries.get(request.id());
        if (existing != null) {
            // Never resend an already accepted ID, including terminal operations.
            output.accept(new BridgeCommandCodec.Status(request.id(), request.epoch(), existing.stage)); return false;
        }
        if (entries.size() >= capacity) {
            Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (!entry.pending() && entry.request.deadlineMs() <= now) iterator.remove();
            }
        }
        if (entries.size() >= capacity || !enqueue.getAsBoolean()) {
            output.accept(new BridgeCommandCodec.Status(request.id(), request.epoch(), REJECTED)); return false;
        }
        Entry entry = new Entry(request, now); entries.put(request.id(), entry);
        emit(entry, HAL_ACCEPTED); return true;
    }
    synchronized boolean canSend(BridgeCommandCodec.Request request, long now) {
        expire(now);
        Entry entry = entries.get(request.id());
        return request.epoch().equals(epoch) && entry != null && entry.stage == HAL_ACCEPTED;
    }
    synchronized void idsQueued(BridgeCommandCodec.Request request, String topic, String messageUuid, int responseType) {
        Entry entry = entries.get(request.id());
        if (entry == null || !request.epoch().equals(epoch) || entry.stage != HAL_ACCEPTED) return;
        if (topic == null || messageUuid == null || messageUuid.isEmpty()) { emit(entry, UNKNOWN); return; }
        entry.topic = topic; entry.messageUuid = messageUuid; entry.responseType = responseType;
        emit(entry, IDS_QUEUED);
    }
    synchronized void appAck(String topic, String responseId, long now) {
        expire(now);
        Entry entry = matching(topic, responseId);
        if (entry != null && entry.stage == IDS_QUEUED) emit(entry, APP_ACK_RECEIVED);
    }
    synchronized void appResponse(String topic, String responseId, int protobufType, boolean response, long now) {
        expire(now);
        Entry entry = matching(topic, responseId);
        if (response && entry != null && entry.responseType == protobufType
                && (entry.stage == IDS_QUEUED || entry.stage == APP_ACK_RECEIVED)) emit(entry, APP_RESPONSE_RECEIVED);
    }
    synchronized boolean expectsResponse(String topic, String responseId, int responseType, long now) {
        expire(now);
        Entry entry = matching(topic, responseId);
        return entry != null && entry.responseType == responseType
                && (entry.stage == IDS_QUEUED || entry.stage == APP_ACK_RECEIVED);
    }
    /** Sysdiagnose type 7 is a new IDS request, with our nonce in its validated
     * archive rather than an IDS response UUID. Only this read-only command
     * accepts payload correlation; it cannot complete another operation. */
    synchronized void diagnosticInventoryResponse(UUID requestId, long now) {
        expire(now);
        Entry entry = entries.get(requestId);
        if (entry != null && entry.request.epoch().equals(epoch)
                && SysdiagnoseArchiveInventory.COMMAND.equals(entry.request.command())
                && SysdiagnoseArchiveInventory.TOPIC.equals(entry.topic)
                && entry.responseType == SysdiagnoseArchiveInventory.LIST_RESULT
                && (entry.stage == IDS_QUEUED || entry.stage == APP_ACK_RECEIVED)) {
            emit(entry, APP_RESPONSE_RECEIVED);
        }
    }
    private Entry matching(String topic, String responseId) {
        if (topic == null || responseId == null) return null;
        for (Entry entry : entries.values()) {
            if (topic.equals(entry.topic) && responseId.equals(entry.messageUuid)) return entry;
        }
        return null;
    }
    synchronized void failed(BridgeCommandCodec.Request request) {
        Entry entry = entries.get(request.id());
        if (entry != null && entry.pending()) emit(entry, entry.stage == HAL_ACCEPTED ? FAILED : UNKNOWN);
    }
    synchronized void expire(long now) {
        for (Entry entry : entries.values()) {
            if (entry.pending() && now >= entry.request.deadlineMs()) {
                emit(entry, entry.stage == HAL_ACCEPTED ? EXPIRED : UNKNOWN);
            }
        }
    }
    private void emit(Entry entry, BridgeCommandCodec.Stage stage) {
        entry.stage = stage;
        output.accept(new BridgeCommandCodec.Status(entry.request.id(), entry.request.epoch(), stage));
    }
}
