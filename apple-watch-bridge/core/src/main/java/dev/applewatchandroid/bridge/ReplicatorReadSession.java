package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Snapshot-zone protocol handshake; files are not acknowledged before persistence. */
final class ReplicatorReadSession implements AutoCloseable {
    // Actual shouldAcceptIncomingMessage at1aef4b600–654 and Message getter06557c.
    static final String MESSAGE_TYPE = "StateReplicator";
    private static final String CLIENT = "com.apple.nanotimekit.replicator.library";
    private static final Map<String, Object> EMPTY_ZONE_VERSIONS = Map.of(
        "library_snapshots::" + CLIENT, Map.of("empty", Map.of()),
        "gallery_snapshots::" + CLIENT, Map.of("empty", Map.of()));
    private final UUID sender, session = UUID.randomUUID(), request = UUID.randomUUID();
    private final String name;
    private final Consumer<String> log;
    private final ArrayDeque<byte[]> outbound = new ArrayDeque<>();
    private boolean completed;
    private UUID peerSession;
    ReplicatorReadSession(String localId, String name, Consumer<String> log) {
        sender = UUID.fromString(localId); this.name = name; this.log = log;
    }
    static Map<String, Object> requestBody(UUID session, String localId, String name) {
        Map<String, Object> version = Map.of("current", 6L, "minimum", 6L);
        Map<String, Object> library = Map.of("id", "library_snapshots", "clientID", CLIENT);
        Map<String, Object> gallery = Map.of("id", "gallery_snapshots", "clientID", CLIENT);
        Map<String, Object> device = Map.of("id", localId, "name", name,
            "protocolVersion", Map.of("current", 8L, "minimum", 8L),
            "deviceType", 2L, "isSource", true,
            "zones", List.of(library, Map.of("id", library, "protocolVersion", version),
                gallery, Map.of("id", gallery, "protocolVersion", version)), "messageTypes", List.of());
        return Map.of("handshake", Map.of("_0", Map.of("request", Map.of("_0", Map.of(
            "sessionID", session.toString().toUpperCase(Locale.ROOT), "relationshipState", Map.of("paired", Map.of()),
            "device", device, "zoneVersions", EMPTY_ZONE_VERSIONS)))));
    }
    byte[] initialFrame() {
        // Match the identity already advertised in NR and IDS device-info.
        String wireDeviceId = sender.toString().toUpperCase(Locale.ROOT);
        return frame(request, null, requestBody(session, wireDeviceId, name));
    }
    private byte[] frame(UUID id, UUID responseTo, Map<String, Object> value) {
        String wireDeviceId = sender.toString().toUpperCase(Locale.ROOT);
        byte[] body = OpackEncoder.encode(value);
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", id.toString().toUpperCase(Locale.ROOT));
            envelope.put("responseToID", responseTo == null ? null : responseTo.toString().toUpperCase(Locale.ROOT));
            envelope.put("messageType", MESSAGE_TYPE); envelope.put("senderDeviceID", wireDeviceId);
            envelope.put("protocolVersion", 8L); envelope.put("encodedBody", body);
            byte[] payload = OpackEncoder.encode(envelope);
            byte[] header = new ReplicatorNetworkHeader(id, sender, payload.length,
                ReplicatorNetworkHeader.MessageType.DATA, 1, 0, ReplicatorNetworkHeader.Priority.HIGH).encode();
            byte[] result = Arrays.copyOf(header, header.length + payload.length);
            System.arraycopy(payload, 0, result, header.length, payload.length);
            Arrays.fill(payload, (byte)0); return result;
        } finally { Arrays.fill(body, (byte)0); }
    }
    void accept(ReplicatorNetworkHeader header, byte[] payload) {
        if (header.messageType != ReplicatorNetworkHeader.MessageType.DATA) {
            log.accept("REPLICATOR APPLICATION: native " + header.messageType + "; bytes=" + payload.length
                + "; file persistence/ACK not implemented; snapshot delivery unconfirmed."); return;
        }
        Object decoded = OpackDecoder.decode(payload);
        if (!(decoded instanceof Map<?, ?> message) || !MESSAGE_TYPE.equals(message.get("messageType"))
                || !(message.get("encodedBody") instanceof byte[] body))
            throw new IllegalArgumentException("Unexpected native Replicator message envelope");
        try {
            Object value = OpackDecoder.decode(body);
            boolean correlated = request.toString().equalsIgnoreCase(String.valueOf(message.get("responseToID")));
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid Replicator body");
            boolean handshake = map.containsKey("handshake");
            log.accept("REPLICATOR APPLICATION: authenticated native message received; correlated=" + correlated
                + "; handshake=" + handshake + "; bytes=" + payload.length
                + "; payload/IDs logged=false; snapshot delivery unconfirmed.");
            UUID id = UUID.fromString(String.valueOf(message.get("id")));
            if (!id.equals(header.messageId) || !header.senderId.equals(UUID.fromString(String.valueOf(message.get("senderDeviceID")))))
                throw new IllegalArgumentException("Replicator envelope identity mismatch");
            if (map.containsKey("ack")) return;
            if (!handshake) {
                log.accept("REPLICATOR APPLICATION: non-handshake body; sync=" + map.containsKey("sync")
                    + "; unpersisted records not acknowledged.");
                return;
            }
            Map<?, ?> phase = child(child(map, "handshake"), "_0");
            boolean response = phase.containsKey("response"), complete = phase.containsKey("complete"), peerRequest = phase.containsKey("request");
            log.accept("REPLICATOR APPLICATION: handshake phase; request=" + peerRequest + "; response=" + response + "; complete=" + complete);
            if (peerRequest) {
                Map<?, ?> incoming = child(child(phase, "request"), "_0");
                UUID incomingSession = UUID.fromString(String.valueOf(incoming.get("sessionID")));
                logPeerEvidence(incoming);
                validatePeer(incoming);
                if (peerSession != null && !peerSession.equals(incomingSession))
                    throw new IllegalArgumentException("Replicator peer changed active session");
                ensureReplyCapacity();
                peerSession = incomingSession;
                outbound.add(frame(UUID.randomUUID(), id, Map.of("ack", Map.of("_0", Map.of()))));
                outbound.add(frame(UUID.randomUUID(), null, responseBody(peerSession, sender.toString().toUpperCase(Locale.ROOT), name)));
                log.accept("REPLICATOR APPLICATION: Watch-initiated handshake accepted; ACK and response queued; snapshots unconfirmed.");
                return;
            }
            if (complete) {
                Map<?, ?> incoming = child(child(phase, "complete"), "_0");
                Map<?, ?> relationship = child(incoming, "relationshipState");
                Object mismatches = incoming.get("mismatchedZones");
                Object versions = child(incoming, "recordManifest").get("recordVersions");
                log.accept("REPLICATOR APPLICATION: completion capabilities; paired=" + relationship.containsKey("paired")
                    + "; pairing=" + relationship.containsKey("pairing") + "; introduced=" + relationship.containsKey("introduced")
                    + "; mismatchCount=" + (mismatches instanceof List<?> values ? values.size() : -1)
                    + "; manifestEntries=" + (versions instanceof List<?> values ? values.size() : -1)
                    + "; record IDs/payload logged=false.");
                if (peerSession == null || !peerSession.equals(UUID.fromString(String.valueOf(incoming.get("sessionID")))))
                    throw new IllegalArgumentException("Uncorrelated Replicator completion");
                if (!initialRelationship(relationship) || !(mismatches instanceof List<?> values) || values.size() > 64)
                    throw new IllegalArgumentException("Replicator completion is incompatible");
                for (Object mismatch : values) {
                    if (!(mismatch instanceof String zone) || zone.length() > 1024
                            || zone.contains("library_snapshots") || zone.contains("gallery_snapshots"))
                        throw new IllegalArgumentException("Replicator completion is incompatible");
                }
                ensureReplyCapacity();
                outbound.add(frame(UUID.randomUUID(), id, Map.of("ack", Map.of("_0", Map.of()))));
                completed = true;
                log.accept("REPLICATOR APPLICATION: Watch handshake complete received and ACK queued; record transfer still unconfirmed.");
                return;
            }
            if (!response) throw new IllegalArgumentException("Unknown Replicator handshake phase");
            Map<?, ?> responseBody = child(child(phase, "response"), "_0");
            if (!session.equals(UUID.fromString(String.valueOf(responseBody.get("sessionID")))))
                throw new IllegalArgumentException("Uncorrelated Replicator handshake session");
            validatePeer(responseBody);
            ensureReplyCapacity();
            outbound.add(frame(UUID.randomUUID(), id, Map.of("ack", Map.of("_0", Map.of()))));
            if (!completed && peerSession == null) {
                outbound.add(frame(UUID.randomUUID(), null, completeBody(session)));
                completed = true;
                log.accept("REPLICATOR APPLICATION: matched handshake response; ACK and complete queued; snapshots unconfirmed.");
            }
        } finally { Arrays.fill(body, (byte)0); }
    }
    private static void validatePeer(Map<?, ?> responseBody) {
            // This callback is reachable only on the existing IKE-authenticated
            // pair and discovery-pinned TLS connection. Replicator has its own
            // initial relationship, observed as introduced on the physical Watch.
            Map<?, ?> relationship = child(responseBody, "relationshipState");
            if (!initialRelationship(relationship))
                throw new IllegalArgumentException("Replicator peer is not paired");
            Map<?, ?> version = child(child(responseBody, "device"), "protocolVersion");
            if (!(version.get("minimum") instanceof Number minimum) || minimum.longValue() > 8
                    || !(version.get("current") instanceof Number current) || current.longValue() < 8)
                throw new IllegalArgumentException("Incompatible Replicator peer protocol");
            validateZones(child(responseBody, "device").get("zones"));
    }
    private static boolean initialRelationship(Map<?, ?> relationship) {
        return relationship.size() == 1 && (relationship.containsKey("paired")
            || relationship.containsKey("introduced") || relationship.containsKey("pairing"));
    }
    private void logPeerEvidence(Map<?, ?> incoming) {
        Map<?, ?> relationship = child(incoming, "relationshipState"), device = child(incoming, "device");
        Map<?, ?> version = child(device, "protocolVersion");
        Object value = device.get("zones");
        int knownZones = 0, clientMatches = 0;
        int emptyMask = 0, hashMask = 0;
        Map<?, ?> zoneVersions = child(incoming, "zoneVersions");
        // Only public protocol zone names, never opaque UUIDs or record/device IDs.
        for (Map.Entry<?, ?> entry : zoneVersions.entrySet()) {
            if (entry.getKey() instanceof String key && key.matches("[A-Za-z.:_/-]{1,160}")
                    && (key.contains("library_snapshots") || key.contains("gallery_snapshots"))) {
                boolean empty = entry.getValue() instanceof Map<?, ?> state && state.containsKey("empty");
                boolean hash = entry.getValue() instanceof Map<?, ?> state && state.containsKey("hash");
                log.accept("REPLICATOR APPLICATION: public snapshot version key=" + key + "; empty=" + empty + "; hash=" + hash
                    + "; version UUID/value logged=false.");
            }
        }
        for (String zone : List.of("library_snapshots", "gallery_snapshots")) {
            Object state = zoneVersions.get(zone + "::" + CLIENT);
            int bit = zone.equals("library_snapshots") ? 1 : 2;
            if (state instanceof Map<?, ?> versionState) {
                if (versionState.containsKey("empty")) emptyMask |= bit;
                if (versionState.containsKey("hash")) hashMask |= bit;
            }
        }
        if (value instanceof List<?> zones) {
            for (int i = 0; i + 1 < zones.size(); i += 2) {
                if (!(zones.get(i) instanceof Map<?, ?> id)) continue;
                if (CLIENT.equals(id.get("clientID"))) clientMatches++;
                if ("library_snapshots".equals(id.get("id"))) knownZones |= 1;
                if ("gallery_snapshots".equals(id.get("id"))) knownZones |= 2;
            }
        }
        log.accept("REPLICATOR APPLICATION: peer capabilities; paired=" + relationship.containsKey("paired")
            + "; pairing=" + relationship.containsKey("pairing") + "; introduced=" + relationship.containsKey("introduced")
            + "; current=" + (version.get("current") instanceof Long n ? n : -1L)
            + "; minimum=" + (version.get("minimum") instanceof Long n ? n : -1L)
            + "; zoneEntries=" + (value instanceof List<?> zones ? zones.size() : -1)
            + "; snapshotZoneMask=" + knownZones + "; snapshotClientMatches=" + clientMatches
            + "; isSource=" + Boolean.TRUE.equals(device.get("isSource"))
            + "; zoneVersionCount=" + zoneVersions.size() + "; snapshotEmptyMask=" + emptyMask + "; snapshotHashMask=" + hashMask
            + "; device IDs/payload logged=false.");
    }
    private void ensureReplyCapacity() {
        if (outbound.size() > 6) throw new IllegalStateException("Replicator reply queue exhausted");
    }
    static Map<String, Object> responseBody(UUID session, String localId, String name) {
        Map<?, ?> request = child(child(child(child(requestBody(session, localId, name), "handshake"), "_0"), "request"), "_0");
        return Map.of("handshake", Map.of("_0", Map.of("response", Map.of("_0", Map.of(
            "sessionID", request.get("sessionID"), "relationshipState", request.get("relationshipState"),
            "device", request.get("device"), "zoneVersions", EMPTY_ZONE_VERSIONS, "recordManifest", Map.of("recordVersions", List.of()))))));
    }
    static Map<String, Object> completeBody(UUID session) {
        return Map.of("handshake", Map.of("_0", Map.of("complete", Map.of("_0", Map.of(
            "sessionID", session.toString().toUpperCase(Locale.ROOT), "relationshipState", Map.of("paired", Map.of()),
            "mismatchedZones", List.of(), "recordManifest", Map.of("recordVersions", List.of()))))));
    }
    private static void validateZones(Object value) {
        if (!(value instanceof List<?> zones) || zones.size() > 128 || (zones.size() & 1) != 0)
            throw new IllegalArgumentException("Invalid Replicator zone descriptors");
        int matched = 0;
        for (int i = 0; i < zones.size(); i += 2) {
            if (!(zones.get(i) instanceof Map<?, ?> id) || !(zones.get(i + 1) instanceof Map<?, ?> zone))
                throw new IllegalArgumentException("Invalid Replicator zone pair");
            if (!CLIENT.equals(id.get("clientID"))) continue;
            int bit = "library_snapshots".equals(id.get("id")) ? 1 : "gallery_snapshots".equals(id.get("id")) ? 2 : 0;
            if (bit == 0) continue;
            Map<?, ?> version = child(zone, "protocolVersion");
            if (!id.equals(zone.get("id")) || !(version.get("minimum") instanceof Number minimum) || minimum.longValue() > 6
                    || !(version.get("current") instanceof Number current) || current.longValue() < 6 || (matched & bit) != 0)
                throw new IllegalArgumentException("Incompatible Replicator snapshot zone");
            matched |= bit;
        }
        if (matched != 3) throw new IllegalArgumentException("Replicator snapshot zones unavailable");
    }
    private static Map<?, ?> child(Map<?, ?> parent, String key) {
        if (!(parent.get(key) instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid Replicator handshake structure");
        return map;
    }
    byte[] pollOutbound() { return outbound.poll(); }
    @Override public void close() { outbound.forEach(bytes -> Arrays.fill(bytes, (byte)0)); outbound.clear(); }
}
