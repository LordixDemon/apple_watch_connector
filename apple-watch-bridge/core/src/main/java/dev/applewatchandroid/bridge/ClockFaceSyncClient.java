package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pair-scoped SY service identity and counters; reserve before any network send. */
final class ClockFaceSyncClient {
    private final Path file;
    private final String pair;
    ClockFaceSyncClient(Path root, String pair) {
        if (!NtkFacePayloadCodec.uuid(pair).equals(pair)) throw new IllegalArgumentException("Invalid canonical pair");
        this.pair = pair; file = root.resolve(pair).resolve("client-state.bplist");
    }
    synchronized byte[] reserveHeader(long unixMs, boolean fullRequest) throws IOException {
        State state = read();
        if (unixMs <= ClockFaceSyncHeaderCodec.APPLE_EPOCH_MS || state.sequence == Long.MAX_VALUE
                || fullRequest && state.lastRequest != 0 && (unixMs < state.lastRequest || unixMs - state.lastRequest < 60_000)) {
            throw new IllegalArgumentException("Clockface request rate/time/counter limit");
        }
        state.sequence++;
        if (fullRequest) state.lastRequest = unixMs;
        byte[] header = ClockFaceSyncHeaderCodec.header(state.peer, state.generation, state.sequence, unixMs, state.clocks);
        save(state);
        return header;
    }
    synchronized void noteCommittedHeader(byte[] header) throws IOException {
        var received = ClockFaceSyncHeaderCodec.decode(header);
        State state = read();
        if (received.peer().equals(state.peer)) throw new IllegalArgumentException("Self clockface sender");
        for (var entry : received.clocks().entrySet()) {
            if (!entry.getKey().equals(state.peer)) state.clocks.merge(entry.getKey(), entry.getValue(), Math::max);
        }
        if (state.clocks.size() > 32) throw new IllegalArgumentException("Clockface peer count bound");
        save(state);
    }
    synchronized UUID remotePeerForDelta() throws IOException {
        State state = read();
        if (state.clocks.size() != 2) throw new IllegalStateException("Delta requires one known native Watch peer");
        return state.clocks.keySet().stream().filter(id -> !id.equals(state.peer)).findFirst().orElseThrow();
    }
    synchronized long earliestFullRequestMs() throws IOException {
        long last = read().lastRequest;
        if (last > Long.MAX_VALUE - 60_000) throw new IOException("Invalid native full request timestamp");
        return last == 0 ? 0 : last + 60_000;
    }
    private State read() throws IOException {
        if (!Files.exists(file)) return new State();
        if (!Files.isRegularFile(file) || Files.size(file) > 16384) throw new IOException("Invalid clockface client state length");
        Object decoded = AppleBinaryPropertyList.decode(Files.readAllBytes(file));
        if (!(decoded instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("version", "pair", "peer", "generation", "sequence", "clocks", "lastRequest"))
                || !Long.valueOf(1).equals(map.get("version")) || !pair.equals(map.get("pair"))) throw new IOException("Invalid clockface client state schema");
        State state = new State();
        try {
            state.peer = UUID.fromString(NtkFacePayloadCodec.uuid((String) map.get("peer")));
            state.generation = UUID.fromString(NtkFacePayloadCodec.uuid((String) map.get("generation")));
            state.sequence = nonnegative(map.get("sequence")); state.lastRequest = nonnegative(map.get("lastRequest"));
            if (!(map.get("clocks") instanceof Map<?, ?> clocks) || clocks.size() > 32) throw new IllegalArgumentException();
            state.clocks.clear();
            for (var entry : clocks.entrySet()) {
                UUID id = UUID.fromString(NtkFacePayloadCodec.uuid((String) entry.getKey()));
                if (state.clocks.put(id, nonnegative(entry.getValue())) != null) throw new IllegalArgumentException();
            }
            if (!Long.valueOf(1).equals(state.clocks.get(state.peer))) throw new IllegalArgumentException();
            return state;
        } catch (RuntimeException invalid) { throw new IOException("Invalid clockface client state values", invalid); }
    }
    private void save(State state) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>(), clocks = new LinkedHashMap<>();
        map.put("version", 1L); map.put("pair", pair); map.put("peer", state.peer.toString());
        map.put("generation", state.generation.toString()); map.put("sequence", state.sequence);
        for (var entry : state.clocks.entrySet()) clocks.put(entry.getKey().toString(), entry.getValue());
        map.put("clocks", clocks); map.put("lastRequest", state.lastRequest);
        byte[] bytes = AppleBinaryPropertyList.encode(map);
        try { ClockFaceSyncJournal.atomicWrite(file, bytes); }
        finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
    private static long nonnegative(Object value) {
        if (!(value instanceof Long number) || number < 0) throw new IllegalArgumentException(); return number;
    }
    private static final class State {
        UUID peer = UUID.randomUUID(), generation = UUID.randomUUID();
        long sequence, lastRequest;
        final Map<UUID, Long> clocks = new LinkedHashMap<>(Map.of(peer, 1L));
    }
}
