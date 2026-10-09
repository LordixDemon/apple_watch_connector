package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Authenticated NR discovery only; does not claim a QUIC connection or snapshot. */
final class NativeApplicationServiceDiscovery {
    static final String SNAPSHOT_SERVICE = "com.apple.ApplicationService.replicatord.terminus";
    private final KeyPair identity;
    private final UUID token;
    private final String service;
    private boolean completed;

    private NativeApplicationServiceDiscovery(String service, UUID token, KeyPair identity) {
        this.service = service;
        this.token = token;
        this.identity = identity;
    }

    static NativeApplicationServiceDiscovery createSnapshotDiscovery() throws GeneralSecurityException {
        // Actual Network.framework options generate RSA2048 SPKI (294 bytes).
        // Generate away from the HAL packet loop; keep the private key in memory.
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new NativeApplicationServiceDiscovery(SNAPSHOT_SERVICE, UUID.randomUUID(), generator.generateKeyPair());
    }

    byte[] request() { return request(service, token, identity.getPublic().getEncoded()); }

    /** Transient TLS signer input; caller must wipe the returned PKCS8 copy. */
    byte[] privateKeyEncoded() { return identity.getPrivate().getEncoded(); }

    static byte[] request(String service, UUID token, byte[] publicKey) {
        byte[] name = service.getBytes(StandardCharsets.UTF_8);
        if (name.length == 0 || name.length > 255 || service.indexOf('\0') >= 0
                || publicKey == null || publicKey.length == 0 || publicKey.length > 512 || token == null)
            throw new IllegalArgumentException("Invalid application service request");
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        tlv(record, 5, name);
        tlv(record, 7, publicKey);
        tlv(record, 9, uuidBytes(token));
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        tlv(result, 6, record.toByteArray());
        return result.toByteArray();
    }

    /** Called only after IKE authentication, once per accepted request, on its owning session. */
    List<Endpoint> acceptAuthenticated(byte[] message) {
        if (completed) return List.of();
        List<Endpoint> matched = new ArrayList<>();
        for (Endpoint endpoint : responses(message)) {
            if (service.equals(endpoint.service) && token.equals(endpoint.token)) matched.add(endpoint);
        }
        if (!matched.isEmpty()) completed = true;
        return List.copyOf(matched);
    }

    static List<Endpoint> responses(byte[] message) {
        if (message == null || message.length < 19 || message.length > 1280 || message[0] != 2
                || message[1] != 0 || ByteBuffer.wrap(message, 4, 4).getInt() != 0
                || be16(message, 2) != message.length - 16)
            throw new IllegalArgumentException("Invalid application service LinkDirector envelope");
        List<Endpoint> result = new ArrayList<>();
        for (Field field : fields(message, 16, message.length)) {
            if (field.type != 21) continue;
            for (Field container : fields(field.bytes, 0, field.bytes.length)) {
                if (container.type != 6) continue;
                String service = null;
                UUID token = null;
                byte[] address = null;
                List<byte[]> keys = new ArrayList<>();
                for (Field part : fields(container.bytes, 0, container.bytes.length)) {
                    switch (part.type) {
                        case 5 -> {
                            if (service != null || part.bytes.length == 0 || part.bytes.length > 255)
                                throw new IllegalArgumentException("Invalid application service name");
                            try {
                                service = StandardCharsets.UTF_8.newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                                        .decode(ByteBuffer.wrap(part.bytes)).toString();
                            } catch (java.nio.charset.CharacterCodingException invalid) {
                                throw new IllegalArgumentException("Invalid application service name", invalid);
                            }
                            if (service.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid application service name");
                        }
                        case 7 -> {
                            if (part.bytes.length == 0 || part.bytes.length > 512 || keys.size() == 8)
                                throw new IllegalArgumentException("Invalid application service public key");
                            keys.add(part.bytes);
                        }
                        case 9 -> {
                            if (token != null || part.bytes.length != 16)
                                throw new IllegalArgumentException("Invalid application service token");
                            ByteBuffer uuid = ByteBuffer.wrap(part.bytes);
                            token = new UUID(uuid.getLong(), uuid.getLong());
                        }
                        case 2 -> {
                            if (address != null || (part.bytes.length != 7 || part.bytes[0] != 2)
                                    && (part.bytes.length != 19 || part.bytes[0] != 30))
                                throw new IllegalArgumentException("Invalid application service address");
                            address = part.bytes;
                        }
                        default -> { /* Retain forward compatibility with bounded native attributes. */ }
                    }
                }
                if (service == null || token == null || address == null || keys.isEmpty()) continue;
                int port = be16(address, 1);
                if (port == 0) throw new IllegalArgumentException("Invalid application service port");
                result.add(new Endpoint(service, token, port,
                        Arrays.copyOfRange(address, 3, address.length), keys));
            }
        }
        return List.copyOf(result);
    }

    private static List<Field> fields(byte[] bytes, int start, int end) {
        List<Field> fields = new ArrayList<>();
        for (int offset = start; offset < end;) {
            if (fields.size() == 32 || end - offset < 3)
                throw new IllegalArgumentException("Invalid application service TLV chain");
            int length = be16(bytes, offset + 1), next = offset + 3 + length;
            if (next > end) throw new IllegalArgumentException("Truncated application service TLV");
            fields.add(new Field(bytes[offset] & 255, Arrays.copyOfRange(bytes, offset + 3, next)));
            offset = next;
        }
        return fields;
    }

    private static void tlv(ByteArrayOutputStream out, int type, byte[] bytes) {
        out.write(type); out.write(bytes.length >>> 8); out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }
    private static int be16(byte[] b, int at) { return (b[at] & 255) << 8 | b[at + 1] & 255; }
    private static byte[] uuidBytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
    private record Field(int type, byte[] bytes) {}

    static final class Endpoint {
        final String service;
        final UUID token;
        final int port;
        private final byte[] address;
        private final List<byte[]> publicKeys;
        Endpoint(String service, UUID token, int port, byte[] address, List<byte[]> publicKeys) {
            this.service = service; this.token = token; this.port = port;
            this.address = address.clone();
            this.publicKeys = publicKeys.stream().map(byte[]::clone).collect(java.util.stream.Collectors.toList());
        }
        byte[] address() { return address.clone(); }
        List<byte[]> publicKeys() { return publicKeys.stream().map(byte[]::clone).collect(java.util.stream.Collectors.toList()); }
    }
}
