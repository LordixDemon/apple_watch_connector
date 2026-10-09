package dev.applewatchandroid.bridge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutual IDS credentials commands 11/12, independent of account login or activation. */
final class IdsDeviceInfoExchange implements AutoCloseable {
    static final String TOPIC = "com.apple.private.alloy.idscredentials";
    static final long REQUEST_DEVICE_INFO = 11;
    static final long RESPONSE_DEVICE_INFO = 12;
    private final Map<String, byte[]> localKeys;
    private final String localIdentifier;
    private final String expectedPeerIdentifier;
    private final String requestId = UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
    private final Set<String> peerRequests = new HashSet<>();
    private final Set<Long> localResponseSequences = new HashSet<>();
    private Map<String, byte[]> peerKeys;
    private boolean started;
    private boolean peerInfoReceived;
    private boolean localInfoDelivered;
    private boolean closed;

    IdsDeviceInfoExchange(byte[] publicBundle, String localIdentifier,
                         String expectedPeerIdentifier, boolean previouslyExchanged) {
        this.localKeys = IdsMessageProtectionIdentity.parsePublicBundle(publicBundle);
        this.localIdentifier = canonicalUuid(localIdentifier);
        this.expectedPeerIdentifier = expectedPeerIdentifier;
        this.localInfoDelivered = previouslyExchanged;
    }

    byte[] begin() {
        requireOpen();
        if (started) return null;
        started = true;
        return AppleBinaryPropertyList.encode(Map.of("command", REQUEST_DEVICE_INFO, "unique-id", requestId));
    }

    /** Returns a real local-info response for command 11; consumes correlated command 12. */
    byte[] accept(byte[] payload) {
        requireOpen();
        if (payload == null || payload.length > 32 * 1024) throw new IllegalArgumentException("IDS device info is too large");
        Object decoded = AppleBinaryPropertyList.decode(payload);
        try {
            if (!(decoded instanceof Map<?, ?> message) || !(message.get("command") instanceof Long command)) {
                throw new IllegalArgumentException("Invalid IDS device-info dictionary");
            }
            if (command != REQUEST_DEVICE_INFO && command != RESPONSE_DEVICE_INFO) return null;
            if (!(message.get("unique-id") instanceof String uniqueId)) {
                throw new IllegalArgumentException("IDS device-info correlation is absent");
            }
            String normalized = canonicalUuid(uniqueId);
            if (command == REQUEST_DEVICE_INFO) {
                if (!peerRequests.contains(normalized) && peerRequests.size() >= 16) {
                    throw new IllegalStateException("Too many IDS device-info requests");
                }
                peerRequests.add(normalized);
                localInfoDelivered = false;
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("command", RESPONSE_DEVICE_INFO);
                reply.put("unique-id", uniqueId);
                reply.put("identifier", localIdentifier);
                reply.put("encryption-key", localKeys.get("D"));
                reply.put("encryption-class-a-key", localKeys.get("A"));
                reply.put("encryption-class-c-key", localKeys.get("C"));
                reply.put("success", true);
                reply.put("private-device-data", IosCompanionProfile26_6.idsPrivateDeviceData(localIdentifier));
                reply.put("device-name", IosCompanionProfile26_6.DEVICE_NAME);
                reply.put("hardware-version", IosCompanionProfile26_6.MODEL);
                return AppleBinaryPropertyList.encode(reply);
            }
            if (!started || !requestId.equals(normalized)) return null;
            if (!Boolean.TRUE.equals(message.get("success"))) {
                throw new IllegalArgumentException("Watch declined IDS device-info request");
            }
            if (!(message.get("identifier") instanceof String identifier)) {
                throw new IllegalArgumentException("Watch IDS identifier is absent");
            }
            canonicalUuid(identifier);
            if (expectedPeerIdentifier != null && !expectedPeerIdentifier.equalsIgnoreCase(identifier)) {
                throw new IllegalArgumentException("Watch IDS identifier differs from the authenticated pairing");
            }
            Map<String, byte[]> received = new LinkedHashMap<>();
            try {
                received.put("D", publicKey(message.get("encryption-key")));
                received.put("A", publicKey(message.get("encryption-class-a-key")));
                received.put("C", publicKey(message.get("encryption-class-c-key")));
                for (String name : received.keySet()) {
                    if (Arrays.equals(received.get(name), localKeys.get(name))) {
                        throw new IllegalArgumentException("Watch reflected a local IDS public identity");
                    }
                }
                IdsMessageProtectionIdentity.wipeValues(peerKeys);
                peerKeys = received;
                received = null;
                peerInfoReceived = true;
            } finally { IdsMessageProtectionIdentity.wipeValues(received); }
            return null;
        } finally { IdsMessageProtectionIdentity.wipeValues(decoded); }
    }

    void onResponseQueued(long sequence) {
        requireOpen();
        if (localResponseSequences.size() >= 32) throw new IllegalStateException("Too many pending IDS info replies");
        localResponseSequences.add(sequence);
    }

    boolean onAcknowledgement(String service, long sequence) {
        requireOpen();
        if (!IdsUtunConnectionName.isClassDIdentifier(service) || !localResponseSequences.remove(sequence)) return false;
        localInfoDelivered = true;
        return true;
    }

    boolean ready() { return !closed && peerInfoReceived && localInfoDelivered; }
    boolean hasVerifiedPeerIdentity() { return !closed && peerInfoReceived && expectedPeerIdentifier != null; }
    String verifiedPeerIdentifier() {
        requireOpen();
        if (!hasVerifiedPeerIdentity()) throw new IllegalStateException("IDS peer identity is not verified");
        return canonicalUuid(expectedPeerIdentifier);
    }
    byte[] peerClassAPublicKey() {
        requireOpen();
        if (!hasVerifiedPeerIdentity() || peerKeys == null) throw new IllegalStateException("IDS peer public identity is not verified");
        return peerKeys.get("A").clone();
    }
    String summary() { return "peerInfo=" + peerInfoReceived + " localInfoDelivered=" + localInfoDelivered; }

    private static byte[] publicKey(Object value) {
        byte[] key;
        if (value instanceof byte[] bytes) key = bytes.clone();
        else if (value instanceof String text && text.length() <= 8192) key = java.util.Base64.getDecoder().decode(text);
        else throw new IllegalArgumentException("Watch IDS public identity is absent");
        try { IdsMessageProtectionIdentity.validatePublic(key); return key; }
        catch (RuntimeException invalid) { Arrays.fill(key, (byte) 0); throw invalid; }
    }

    private static String canonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Invalid IDS device-info UUID");
        return uuid.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("IDS device-info exchange is closed"); }
    @Override public void close() {
        if (closed) return;
        closed = true;
        IdsMessageProtectionIdentity.wipeValues(localKeys);
        IdsMessageProtectionIdentity.wipeValues(peerKeys);
        peerRequests.clear(); localResponseSequences.clear();
    }
}
