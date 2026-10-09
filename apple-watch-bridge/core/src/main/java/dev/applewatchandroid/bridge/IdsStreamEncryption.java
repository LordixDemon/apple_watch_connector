package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

/**
 * iOS 26.6 IDSStreamEncryption/SRTP-v1 packet transform used by the legacy
 * generic-UTun IDS packet path inside an authenticated Class-C or Class-D
 * ESP link.
 *
 * <p>This private IDS layer has replay protection but deliberately has no
 * authentication tag. Its integrity boundary is the outer ESP transport.
 * Modern {@code shouldUseServiceConnector} data connections carry
 * socket-pair frames directly and must not add this envelope.</p>
 */
final class IdsStreamEncryption implements AutoCloseable {
    static final int CONTRIBUTION_LENGTH = 60;
    static final int MASTER_KEY_LENGTH = 16;
    static final int MASTER_SALT_LENGTH = 14;
    static final int SESSION_KEY_LENGTH = 32;
    static final int SESSION_SALT_LENGTH = 14;
    static final int ENCRYPTED_HEADER_LENGTH = 8;
    static final int MAX_PLAINTEXT_LENGTH = 1600;
    static final int REPLAY_WINDOW_SIZE = 512;

    static final int MARKER_UDP = 0x00;
    static final int MARKER_TCP = 0x20;
    static final int MARKER_COMPRESSED_UDP = 0x90;
    static final int MARKER_COMPRESSED_TCP = 0xb0;

    private static final int AES_BLOCK_LENGTH = 16;

    private final DirectionState send;
    private final DirectionState receive;
    private final ReplayWindow replayWindow =
            new ReplayWindow();
    private boolean destroyed;

    /**
     * Creates both directional transforms from the XOR-combined 60-byte
     * contribution. GUID comparison uses canonical UUID strings, matching
     * the NSString comparison in identityservicesd.
     */
    static IdsStreamEncryption create(
            byte[] combinedMaterial,
            UUID localConnectionGuid,
            UUID remoteConnectionGuid,
            int localSsrc,
            int localInitialSequence,
            int remoteSsrc,
            int remoteInitialSequence) {
        requireLength(
                "combined IDS key material",
                combinedMaterial,
                CONTRIBUTION_LENGTH);
        if (localConnectionGuid == null
                || remoteConnectionGuid == null
                || localConnectionGuid.equals(
                        remoteConnectionGuid)) {
            throw new IllegalArgumentException(
                    "Distinct local/remote IDS connection GUIDs "
                            + "are required");
        }
        requireSequence(
                "local initial",
                localInitialSequence);
        requireSequence(
                "remote initial",
                remoteInitialSequence);

        DirectionalKeys pairA =
                derivePair(
                        combinedMaterial,
                        0);
        DirectionalKeys pairB =
                derivePair(
                        combinedMaterial,
                        MASTER_KEY_LENGTH
                                + MASTER_SALT_LENGTH);
        try {
            boolean localSortsAfterRemote =
                    localConnectionGuid
                            .toString()
                            .compareTo(
                                    remoteConnectionGuid
                                            .toString())
                            > 0;
            DirectionalKeys sendKeys =
                    localSortsAfterRemote
                            ? pairA
                            : pairB;
            DirectionalKeys receiveKeys =
                    localSortsAfterRemote
                            ? pairB
                            : pairA;
            return new IdsStreamEncryption(
                    new DirectionState(
                            sendKeys,
                            localSsrc,
                            localInitialSequence),
                    new DirectionState(
                            receiveKeys,
                            remoteSsrc,
                            remoteInitialSequence));
        } finally {
            pairA.destroy();
            pairB.destroy();
        }
    }

    static byte[] combineContributions(
            byte[] localContribution,
            byte[] remoteContribution) {
        requireLength(
                "local IDS contribution",
                localContribution,
                CONTRIBUTION_LENGTH);
        requireLength(
                "remote IDS contribution",
                remoteContribution,
                CONTRIBUTION_LENGTH);
        byte[] combined =
                new byte[CONTRIBUTION_LENGTH];
        for (int index = 0;
                index < combined.length;
                index++) {
            combined[index] =
                    (byte) (localContribution[index]
                            ^ remoteContribution[index]);
        }
        return combined;
    }

    private IdsStreamEncryption(
            DirectionState send,
            DirectionState receive) {
        this.send = send;
        this.receive = receive;
    }

    synchronized byte[] encryptTcp(
            byte[] tcpSegment) {
        return encrypt(
                MARKER_TCP,
                -1,
                tcpSegment);
    }

    synchronized byte[] encryptUdp(
            byte[] udpPayload) {
        return encrypt(
                MARKER_UDP,
                -1,
                udpPayload);
    }

    synchronized byte[] encryptCompressedTcp(
            int compressionContextId,
            byte[] compressedTcp) {
        return encrypt(
                MARKER_COMPRESSED_TCP,
                compressionContextId,
                compressedTcp);
    }

    synchronized byte[] encryptCompressedUdp(
            int compressionContextId,
            byte[] compressedUdp) {
        return encrypt(
                MARKER_COMPRESSED_UDP,
                compressionContextId,
                compressedUdp);
    }

    synchronized DecryptedPacket decrypt(
            byte[] frame) {
        requireLive();
        if (frame == null
                || frame.length <= ENCRYPTED_HEADER_LENGTH
                || frame.length
                - ENCRYPTED_HEADER_LENGTH
                > MAX_PLAINTEXT_LENGTH
                || (frame[0] & 0xff) != 0xe0
                || frame[1] != 0) {
            throw new IllegalArgumentException(
                    "IDS encrypted data frame is invalid");
        }
        int sequence =
                be16(
                        frame,
                        2);
        int ssrc =
                be32(
                        frame,
                        4);
        if (ssrc != receive.ssrc) {
            throw new IllegalArgumentException(
                    "IDS encrypted frame has an unknown SSRC");
        }
        replayWindow.requireAcceptable(
                sequence);
        long rollover =
                receive.rolloverFor(
                        sequence);
        byte[] ciphertext =
                Arrays.copyOfRange(
                        frame,
                        ENCRYPTED_HEADER_LENGTH,
                        frame.length);
        byte[] plaintext = null;
        try {
            plaintext =
                    transform(
                            receive.sessionKey,
                            receive.sessionSalt,
                            ssrc,
                            rollover,
                            sequence,
                            ciphertext);
            DecryptedPacket result =
                    parsePlaintext(
                            sequence,
                            ssrc,
                            plaintext);
            replayWindow.commit(
                    sequence);
            receive.commitSequence(
                    sequence,
                    rollover);
            return result;
        } finally {
            wipe(ciphertext);
            wipe(plaintext);
        }
    }

    int nextSendSequence() {
        requireLive();
        return send.nextSequence;
    }

    long sendRolloverCounter() {
        requireLive();
        return send.rolloverCounter;
    }

    private byte[] encrypt(
            int marker,
            int compressionContextId,
            byte[] payload) {
        requireLive();
        if (payload == null) {
            throw new IllegalArgumentException(
                    "IDS data payload is null");
        }
        boolean compressed =
                marker == MARKER_COMPRESSED_TCP
                        || marker
                        == MARKER_COMPRESSED_UDP;
        if (compressed
                && (compressionContextId < 0
                || compressionContextId > 0xffff)) {
            throw new IllegalArgumentException(
                    "IDS compression context must fit uint16");
        }
        int prefixLength =
                compressed ? 3 : 1;
        if (payload.length + prefixLength
                > MAX_PLAINTEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS plaintext exceeds the 1600-byte transform limit");
        }

        ByteArrayOutputStream plaintext =
                new ByteArrayOutputStream(
                        prefixLength
                                + payload.length);
        plaintext.write(marker);
        if (compressed) {
            writeBe16(
                    plaintext,
                    compressionContextId);
        }
        plaintext.writeBytes(
                payload);
        byte[] plaintextBytes =
                plaintext.toByteArray();
        byte[] ciphertext = null;
        try {
            int sequence =
                    send.nextSequence;
            long rollover =
                    send.rolloverFor(
                            sequence);
            ciphertext =
                    transform(
                            send.sessionKey,
                            send.sessionSalt,
                            send.ssrc,
                            rollover,
                            sequence,
                            plaintextBytes);
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream(
                            ENCRYPTED_HEADER_LENGTH
                                    + ciphertext.length);
            output.write(0xe0);
            output.write(0);
            writeBe16(
                    output,
                    sequence);
            writeBe32(
                    output,
                    send.ssrc);
            output.writeBytes(
                    ciphertext);
            send.commitSequence(
                    sequence,
                    rollover);
            send.nextSequence =
                    (sequence + 1) & 0xffff;
            return output.toByteArray();
        } finally {
            wipe(plaintextBytes);
            wipe(ciphertext);
        }
    }

    private static DecryptedPacket parsePlaintext(
            int sequence,
            int ssrc,
            byte[] plaintext) {
        if (plaintext.length == 0) {
            throw new IllegalArgumentException(
                    "IDS decrypted payload has no protocol marker");
        }
        int marker =
                plaintext[0] & 0xff;
        boolean compressed =
                marker == MARKER_COMPRESSED_UDP
                        || marker
                        == MARKER_COMPRESSED_TCP;
        boolean tcp;
        if (marker == MARKER_UDP
                || marker == MARKER_COMPRESSED_UDP) {
            tcp = false;
        } else if (marker == MARKER_TCP
                || marker == MARKER_COMPRESSED_TCP) {
            tcp = true;
        } else {
            throw new IllegalArgumentException(
                    "IDS decrypted protocol marker is unsupported");
        }
        int payloadOffset =
                compressed ? 3 : 1;
        if (plaintext.length < payloadOffset) {
            throw new IllegalArgumentException(
                    "IDS compressed payload header is truncated");
        }
        int context =
                compressed
                        ? be16(
                                plaintext,
                                1)
                        : -1;
        return new DecryptedPacket(
                sequence,
                ssrc,
                tcp,
                compressed,
                context,
                Arrays.copyOfRange(
                        plaintext,
                        payloadOffset,
                        plaintext.length));
    }

    private static DirectionalKeys derivePair(
            byte[] combined,
            int offset) {
        byte[] masterKey =
                Arrays.copyOfRange(
                        combined,
                        offset,
                        offset + MASTER_KEY_LENGTH);
        byte[] masterSalt =
                Arrays.copyOfRange(
                        combined,
                        offset + MASTER_KEY_LENGTH,
                        offset
                                + MASTER_KEY_LENGTH
                                + MASTER_SALT_LENGTH);
        byte[] block0 =
                new byte[AES_BLOCK_LENGTH];
        byte[] block1 =
                new byte[AES_BLOCK_LENGTH];
        byte[] saltBlock =
                new byte[AES_BLOCK_LENGTH];
        byte[] encrypted0 = null;
        byte[] encrypted1 = null;
        byte[] encryptedSalt = null;
        try {
            System.arraycopy(
                    masterSalt,
                    0,
                    block0,
                    0,
                    masterSalt.length);
            System.arraycopy(
                    masterSalt,
                    0,
                    block1,
                    0,
                    masterSalt.length);
            block1[15] = 1;
            System.arraycopy(
                    masterSalt,
                    0,
                    saltBlock,
                    0,
                    masterSalt.length);
            saltBlock[7] ^= 0x02;

            encrypted0 =
                    aesBlock(
                            masterKey,
                            block0);
            encrypted1 =
                    aesBlock(
                            masterKey,
                            block1);
            encryptedSalt =
                    aesBlock(
                            masterKey,
                            saltBlock);
            byte[] sessionKey =
                    new byte[SESSION_KEY_LENGTH];
            System.arraycopy(
                    encrypted0,
                    0,
                    sessionKey,
                    0,
                    encrypted0.length);
            System.arraycopy(
                    encrypted1,
                    0,
                    sessionKey,
                    encrypted0.length,
                    encrypted1.length);
            byte[] sessionSalt =
                    Arrays.copyOf(
                            encryptedSalt,
                            SESSION_SALT_LENGTH);
            try {
                return new DirectionalKeys(
                        sessionKey,
                        sessionSalt);
            } finally {
                wipe(sessionKey);
                wipe(sessionSalt);
            }
        } finally {
            wipe(masterKey);
            wipe(masterSalt);
            wipe(block0);
            wipe(block1);
            wipe(saltBlock);
            wipe(encrypted0);
            wipe(encrypted1);
            wipe(encryptedSalt);
        }
    }

    private static byte[] transform(
            byte[] sessionKey,
            byte[] sessionSalt,
            int ssrc,
            long rollover,
            int sequence,
            byte[] input) {
        if (input.length > MAX_PLAINTEXT_LENGTH) {
            throw new IllegalArgumentException(
                    "IDS transform input exceeds 1600 bytes");
        }
        byte[] counter =
                new byte[AES_BLOCK_LENGTH];
        byte[] keystream =
                new byte[AES_BLOCK_LENGTH];
        byte[] output =
                new byte[input.length];
        System.arraycopy(
                sessionSalt,
                0,
                counter,
                0,
                sessionSalt.length);
        xorBe32(
                counter,
                4,
                ssrc);
        xorBe32(
                counter,
                8,
                (int) rollover);
        counter[12] ^=
                (byte) (sequence >>> 8);
        counter[13] ^=
                (byte) sequence;
        counter[14] = 0;
        counter[15] = 0;

        BlockCipher aes =
                AESEngine.newInstance();
        aes.init(
                true,
                new KeyParameter(
                        sessionKey));
        try {
            int offset = 0;
            while (offset < input.length) {
                aes.processBlock(
                        counter,
                        0,
                        keystream,
                        0);
                int blockLength =
                        Math.min(
                                AES_BLOCK_LENGTH,
                                input.length - offset);
                for (int index = 0;
                        index < blockLength;
                        index++) {
                    output[offset + index] =
                            (byte) (input[offset + index]
                                    ^ keystream[index]);
                }
                offset += blockLength;
                if (offset < input.length) {
                    incrementBlockCounter(
                            counter);
                }
            }
            return output;
        } finally {
            aes.reset();
            wipe(counter);
            wipe(keystream);
        }
    }

    private static byte[] aesBlock(
            byte[] key,
            byte[] input) {
        BlockCipher aes =
                AESEngine.newInstance();
        aes.init(
                true,
                new KeyParameter(
                        key));
        byte[] output =
                new byte[AES_BLOCK_LENGTH];
        try {
            aes.processBlock(
                    input,
                    0,
                    output,
                    0);
            return output;
        } finally {
            aes.reset();
        }
    }

    private static void incrementBlockCounter(
            byte[] counter) {
        for (int index = 15;
                index >= 14;
                index--) {
            counter[index]++;
            if (counter[index] != 0) {
                return;
            }
        }
        throw new IllegalStateException(
                "IDS per-packet AES block counter exhausted");
    }

    private static void xorBe32(
            byte[] target,
            int offset,
            int value) {
        target[offset] ^=
                (byte) (value >>> 24);
        target[offset + 1] ^=
                (byte) (value >>> 16);
        target[offset + 2] ^=
                (byte) (value >>> 8);
        target[offset + 3] ^=
                (byte) value;
    }

    @Override
    public synchronized void close() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        send.destroy();
        receive.destroy();
        replayWindow.destroy();
    }

    private void requireLive() {
        if (destroyed) {
            throw new IllegalStateException(
                    "IDS stream encryption was destroyed");
        }
    }

    private static void requireLength(
            String label,
            byte[] value,
            int length) {
        if (value == null
                || value.length != length) {
            throw new IllegalArgumentException(
                    label
                            + " must contain "
                            + length
                            + " bytes");
        }
    }

    private static void requireSequence(
            String label,
            int sequence) {
        if (sequence < 0
                || sequence > 0xffff) {
            throw new IllegalArgumentException(
                    label
                            + " IDS sequence must fit uint16");
        }
    }

    private static int be16(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 8)
                | (value[offset + 1] & 0xff);
    }

    private static int be32(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 24)
                | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8)
                | (value[offset + 3] & 0xff);
    }

    private static void writeBe16(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeBe32(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    static final class DecryptedPacket {
        final int sequence;
        final int ssrc;
        final boolean tcp;
        final boolean compressed;
        final int compressionContextId;
        final byte[] payload;
        private boolean destroyed;

        DecryptedPacket(
                int sequence,
                int ssrc,
                boolean tcp,
                boolean compressed,
                int compressionContextId,
                byte[] payload) {
            this.sequence = sequence;
            this.ssrc = ssrc;
            this.tcp = tcp;
            this.compressed = compressed;
            this.compressionContextId =
                    compressionContextId;
            this.payload = payload.clone();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            wipe(payload);
        }
    }

    private static final class DirectionalKeys {
        final byte[] sessionKey;
        final byte[] sessionSalt;

        DirectionalKeys(
                byte[] sessionKey,
                byte[] sessionSalt) {
            this.sessionKey =
                    sessionKey.clone();
            this.sessionSalt =
                    sessionSalt.clone();
        }

        void destroy() {
            wipe(sessionKey);
            wipe(sessionSalt);
        }
    }

    private static final class DirectionState {
        final byte[] sessionKey;
        final byte[] sessionSalt;
        final int ssrc;
        int nextSequence;
        int previousSequence;
        long rolloverCounter;
        boolean rolloverExhausted;

        DirectionState(
                DirectionalKeys keys,
                int ssrc,
                int initialSequence) {
            sessionKey =
                    keys.sessionKey.clone();
            sessionSalt =
                    keys.sessionSalt.clone();
            this.ssrc = ssrc;
            nextSequence = initialSequence;
            previousSequence = initialSequence;
        }

        long rolloverFor(
                int sequence) {
            if (rolloverExhausted) {
                throw new IllegalStateException(
                        "IDS rollover counter is exhausted");
            }
            int delta =
                    (sequence
                            - previousSequence)
                            & 0xffff;
            if ((delta & 0x8000) == 0) {
                if (previousSequence > sequence) {
                    if (rolloverCounter == 0xffffffffL) {
                        rolloverExhausted = true;
                        throw new IllegalStateException(
                                "IDS rollover counter would reuse a key");
                    }
                    return rolloverCounter + 1;
                }
                return rolloverCounter;
            }
            if (previousSequence <= sequence
                    && sequence != previousSequence) {
                if (rolloverCounter == 0) {
                    throw new IllegalArgumentException(
                            "IDS packet refers to a negative rollover");
                }
                return rolloverCounter - 1;
            }
            return rolloverCounter;
        }

        void commitSequence(
                int sequence,
                long candidateRollover) {
            int delta =
                    (sequence
                            - previousSequence)
                            & 0xffff;
            if ((delta & 0x8000) == 0) {
                previousSequence = sequence;
                rolloverCounter =
                        candidateRollover;
            }
        }

        void destroy() {
            wipe(sessionKey);
            wipe(sessionSalt);
            nextSequence = 0;
            previousSequence = 0;
            rolloverCounter = 0;
            rolloverExhausted = true;
        }
    }

    private static final class ReplayWindow {
        private final long[] bits =
                new long[REPLAY_WINDOW_SIZE / Long.SIZE];
        private int highestSequence;
        private boolean initialized;

        void requireAcceptable(
                int sequence) {
            if (!initialized) {
                return;
            }
            int signedDelta =
                    (short) (sequence
                            - highestSequence);
            if (signedDelta > 0) {
                return;
            }
            int age =
                    -signedDelta;
            if (age >= REPLAY_WINDOW_SIZE
                    || (bits[age / Long.SIZE]
                    & (1L << (age % Long.SIZE))) != 0) {
                throw new IllegalArgumentException(
                        "IDS encrypted packet is replayed or too old");
            }
        }

        void commit(
                int sequence) {
            if (!initialized) {
                initialized = true;
                highestSequence = sequence;
                Arrays.fill(
                        bits,
                        0);
                bits[0] = 1;
                return;
            }
            int signedDelta =
                    (short) (sequence
                            - highestSequence);
            if (signedDelta > 0) {
                shiftLeft(
                        signedDelta);
                highestSequence = sequence;
                bits[0] |= 1;
            } else {
                int age =
                        -signedDelta;
                bits[age / Long.SIZE] |=
                        1L << (age % Long.SIZE);
            }
        }

        private void shiftLeft(
                int count) {
            if (count >= REPLAY_WINDOW_SIZE) {
                Arrays.fill(
                        bits,
                        0);
                return;
            }
            int wholeWords =
                    count / Long.SIZE;
            int remainder =
                    count % Long.SIZE;
            for (int destination = bits.length - 1;
                    destination >= 0;
                    destination--) {
                long value = 0;
                int source =
                        destination - wholeWords;
                if (source >= 0) {
                    value =
                            bits[source] << remainder;
                    if (remainder != 0
                            && source > 0) {
                        value |=
                                bits[source - 1]
                                        >>> (Long.SIZE
                                        - remainder);
                    }
                }
                bits[destination] = value;
            }
        }

        void destroy() {
            Arrays.fill(
                    bits,
                    0);
            highestSequence = 0;
            initialized = false;
        }
    }
}
