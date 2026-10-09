package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * First ordinary Child-SA KEYMAT and Apple AES-GCM-IIV ESP transport.
 */
final class OrdinaryChildSaCrypto {
    static final int ESP_PROTOCOL_NUMBER = 50;
    static final int CHILD_SPI_LENGTH = 4;
    static final int AES_KEY_LENGTH = 32;
    static final int SALT_LENGTH = 4;
    static final int DIRECTION_KEY_MATERIAL_LENGTH =
            AES_KEY_LENGTH + SALT_LENGTH;
    static final int KEYMAT_LENGTH =
            2 * DIRECTION_KEY_MATERIAL_LENGTH;
    static final int GCM_TAG_LENGTH = 16;
    static final int REPLAY_WINDOW_SIZE = 16;
    static final int TRAFFIC_CLASS_COUNT = 4;
    static final long TRAFFIC_CLASS_RANGE_SIZE = 1L << 30;

    private static final int IPV6_HEADER_LENGTH = 40;
    private static final int ESP_HEADER_LENGTH = 8;

    private OrdinaryChildSaCrypto() {
    }

    static ChildSaKeys derive(
            IkeV2SessionCrypto.IkeSaKeys ikeKeys,
            byte[] initiatorChildSpi,
            byte[] responderChildSpi) {
        if (ikeKeys == null) {
            throw new IllegalArgumentException(
                    "IKE key schedule is required");
        }
        requireLength(
                "initiator Child SPI",
                initiatorChildSpi,
                CHILD_SPI_LENGTH);
        requireLength(
                "responder Child SPI",
                responderChildSpi,
                CHILD_SPI_LENGTH);
        if (isAllZero(initiatorChildSpi)
                || isAllZero(responderChildSpi)) {
            throw new IllegalArgumentException(
                    "Child SPIs must be non-zero");
        }
        byte[] seed = null;
        byte[] keymat = null;
        try {
            seed = concatenate(
                    ikeKeys.initiatorNonce,
                    ikeKeys.responderNonce);
            keymat = prfPlus(
                    ikeKeys.skD,
                    seed,
                    KEYMAT_LENGTH);
            return new ChildSaKeys(
                    initiatorChildSpi,
                    responderChildSpi,
                    Arrays.copyOfRange(
                            keymat,
                            0,
                            DIRECTION_KEY_MATERIAL_LENGTH),
                    Arrays.copyOfRange(
                            keymat,
                            DIRECTION_KEY_MATERIAL_LENGTH,
                            KEYMAT_LENGTH));
        } finally {
            wipe(seed);
            wipe(keymat);
        }
    }

    static int trafficClassIndexForDscp(
            int dscp) {
        if (dscp < 0 || dscp > 63) {
            throw new IllegalArgumentException(
                    "DSCP must fit in six bits");
        }
        if (dscp == 8) {
            return 1;
        }
        if (dscp == 26
                || dscp == 32
                || dscp == 34
                || dscp == 40) {
            return 2;
        }
        if (dscp == 46 || dscp == 48) {
            return 3;
        }
        return 0;
    }

    static int trafficClassIndexForIpv6Packet(
            byte[] ipv6Packet) {
        validateIpv6Packet(ipv6Packet);
        int trafficClass =
                ((ipv6Packet[0] & 0x0f) << 4)
                        | ((ipv6Packet[1] & 0xf0) >>> 4);
        return trafficClassIndexForDscp(
                trafficClass >>> 2);
    }

    private static byte[] encryptEsp(
            byte[] spi,
            byte[] keyMaterial,
            long sequence,
            byte[] upperLayerPayload,
            int nextHeader) {
        requireLength(
                "outbound ESP SPI",
                spi,
                CHILD_SPI_LENGTH);
        requireLength(
                "outbound ESP key material",
                keyMaterial,
                DIRECTION_KEY_MATERIAL_LENGTH);
        if (sequence <= 0
                || sequence > 0xffff_ffffL
                || upperLayerPayload == null
                || nextHeader < 0
                || nextHeader > 0xff) {
            throw new IllegalArgumentException(
                    "ESP sequence, payload, and next header "
                            + "are required");
        }
        int paddingLength =
                (4 - ((upperLayerPayload.length + 2) & 3)) & 3;
        byte[] plaintext =
                new byte[
                        upperLayerPayload.length
                                + paddingLength
                                + 2];
        System.arraycopy(
                upperLayerPayload,
                0,
                plaintext,
                0,
                upperLayerPayload.length);
        for (int index = 0;
                index < paddingLength;
                index++) {
            plaintext[
                    upperLayerPayload.length + index] =
                    (byte) (index + 1);
        }
        plaintext[plaintext.length - 2] =
                (byte) paddingLength;
        plaintext[plaintext.length - 1] =
                (byte) nextHeader;

        byte[] aad = new byte[ESP_HEADER_LENGTH];
        System.arraycopy(
                spi,
                0,
                aad,
                0,
                spi.length);
        writeBe32(
                aad,
                CHILD_SPI_LENGTH,
                sequence);
        byte[] nonce =
                espNonce(
                        keyMaterial,
                        sequence);
        byte[] ciphertextAndTag = null;
        try {
            ciphertextAndTag =
                    aesGcm(
                            true,
                            keyMaterial,
                            nonce,
                            aad,
                            plaintext);
            byte[] packet =
                    new byte[
                            ESP_HEADER_LENGTH
                                    + ciphertextAndTag.length];
            System.arraycopy(
                    aad,
                    0,
                    packet,
                    0,
                    aad.length);
            System.arraycopy(
                    ciphertextAndTag,
                    0,
                    packet,
                    aad.length,
                    ciphertextAndTag.length);
            return packet;
        } finally {
            wipe(plaintext);
            wipe(aad);
            wipe(nonce);
            wipe(ciphertextAndTag);
        }
    }

    private static DecryptedEsp decryptEsp(
            byte[] expectedSpi,
            byte[] keyMaterial,
            byte[] packet) {
        requireLength(
                "inbound ESP SPI",
                expectedSpi,
                CHILD_SPI_LENGTH);
        requireLength(
                "inbound ESP key material",
                keyMaterial,
                DIRECTION_KEY_MATERIAL_LENGTH);
        if (packet == null
                || packet.length
                < ESP_HEADER_LENGTH + 2 + GCM_TAG_LENGTH
                || !Arrays.equals(
                expectedSpi,
                Arrays.copyOfRange(
                        packet,
                        0,
                        CHILD_SPI_LENGTH))) {
            throw new IllegalArgumentException(
                    "Inbound ESP packet or SPI is invalid");
        }
        long sequence =
                readUnsignedBe32(
                        packet,
                        CHILD_SPI_LENGTH);
        if (sequence == 0) {
            throw new IllegalArgumentException(
                    "ESP sequence number zero is forbidden");
        }
        byte[] aad =
                Arrays.copyOfRange(
                        packet,
                        0,
                        ESP_HEADER_LENGTH);
        byte[] encrypted =
                Arrays.copyOfRange(
                        packet,
                        ESP_HEADER_LENGTH,
                        packet.length);
        byte[] nonce =
                espNonce(
                        keyMaterial,
                        sequence);
        byte[] plaintext = null;
        try {
            plaintext =
                    aesGcm(
                            false,
                            keyMaterial,
                            nonce,
                            aad,
                            encrypted);
            if (plaintext.length < 2) {
                throw new IllegalArgumentException(
                        "ESP plaintext is truncated");
            }
            int paddingLength =
                    plaintext[plaintext.length - 2] & 0xff;
            if (paddingLength + 2
                    > plaintext.length) {
                throw new IllegalArgumentException(
                        "ESP padding length is invalid");
            }
            int payloadLength =
                    plaintext.length - paddingLength - 2;
            // RFC 4303 permits transform-specific or arbitrary padding,
            // including padding used to conceal traffic length. AES-GCM
            // authenticates the full plaintext trailer, so only the
            // authenticated Pad Length boundary is required here.
            return new DecryptedEsp(
                    Arrays.copyOf(
                            plaintext,
                            payloadLength),
                    plaintext[plaintext.length - 1] & 0xff,
                    sequence);
        } finally {
            wipe(aad);
            wipe(encrypted);
            wipe(nonce);
            wipe(plaintext);
        }
    }

    private static byte[] espNonce(
            byte[] keyMaterial,
            long sequence) {
        byte[] nonce = new byte[12];
        System.arraycopy(
                keyMaterial,
                AES_KEY_LENGTH,
                nonce,
                0,
                SALT_LENGTH);
        writeBe32(
                nonce,
                8,
                sequence);
        return nonce;
    }

    private static byte[] aesGcm(
            boolean encrypt,
            byte[] keyMaterial,
            byte[] nonce,
            byte[] aad,
            byte[] input) {
        byte[] key =
                Arrays.copyOf(
                        keyMaterial,
                        AES_KEY_LENGTH);
        GCMBlockCipher cipher =
                new GCMBlockCipher(
                        AESEngine.newInstance());
        cipher.init(
                encrypt,
                new AEADParameters(
                        new KeyParameter(key),
                        GCM_TAG_LENGTH * 8,
                        nonce,
                        aad));
        byte[] output =
                new byte[
                        cipher.getOutputSize(
                                input.length)];
        try {
            int length =
                    cipher.processBytes(
                            input,
                            0,
                            input.length,
                            output,
                            0);
            length += cipher.doFinal(
                    output,
                    length);
            if (length == output.length) {
                return output;
            }
            byte[] exact =
                    Arrays.copyOf(
                            output,
                            length);
            wipe(output);
            return exact;
        } catch (InvalidCipherTextException error) {
            wipe(output);
            throw new IllegalArgumentException(
                    "ESP AES-GCM authentication failed",
                    error);
        } finally {
            wipe(key);
        }
    }

    private static byte[] prfPlus(
            byte[] key,
            byte[] seed,
            int outputLength) {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(
                        outputLength);
        byte[] previous = new byte[0];
        int counter = 1;
        try {
            while (output.size() < outputLength) {
                if (counter > 0xff) {
                    throw new IllegalArgumentException(
                            "Child KEYMAT request is too long");
                }
                byte[] input =
                        concatenate(
                                previous,
                                seed,
                                new byte[]{(byte) counter});
                byte[] next;
                try {
                    next = prf(key, input);
                } finally {
                    wipe(input);
                }
                wipe(previous);
                previous = next;
                output.writeBytes(previous);
                counter++;
            }
            return Arrays.copyOf(
                    output.toByteArray(),
                    outputLength);
        } finally {
            wipe(previous);
        }
    }

    private static byte[] prf(
            byte[] key,
            byte[] data) {
        HMac hmac =
                new HMac(
                        new SHA512Digest());
        hmac.init(
                new KeyParameter(key));
        hmac.update(
                data,
                0,
                data.length);
        byte[] output =
                new byte[hmac.getMacSize()];
        hmac.doFinal(output, 0);
        return output;
    }

    private static byte[] concatenate(
            byte[]... values) {
        int length = 0;
        for (byte[] value : values) {
            length += value.length;
        }
        byte[] output = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(
                    value,
                    0,
                    output,
                    offset,
                    value.length);
            offset += value.length;
        }
        return output;
    }

    private static void validateIpv6Packet(
            byte[] packet) {
        if (packet == null
                || packet.length < IPV6_HEADER_LENGTH
                || (packet[0] & 0xf0) != 0x60
                || (((packet[4] & 0xff) << 8)
                | (packet[5] & 0xff))
                != packet.length - IPV6_HEADER_LENGTH) {
            throw new IllegalArgumentException(
                    "Baseline IPv6 packet is invalid");
        }
    }

    private static long readUnsignedBe32(
            byte[] value,
            int offset) {
        return ((long) (value[offset] & 0xff) << 24)
                | ((long) (value[offset + 1] & 0xff) << 16)
                | ((long) (value[offset + 2] & 0xff) << 8)
                | (long) (value[offset + 3] & 0xff);
    }

    private static void writeBe32(
            byte[] output,
            int offset,
            long value) {
        output[offset] =
                (byte) (value >>> 24);
        output[offset + 1] =
                (byte) (value >>> 16);
        output[offset + 2] =
                (byte) (value >>> 8);
        output[offset + 3] =
                (byte) value;
    }

    private static void requireLength(
            String label,
            byte[] value,
            int length) {
        if (value == null || value.length != length) {
            throw new IllegalArgumentException(
                    label + " must contain " + length + " bytes");
        }
    }

    private static boolean isAllZero(
            byte[] value) {
        int combined = 0;
        for (byte current : value) {
            combined |= current & 0xff;
        }
        return combined == 0;
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class ChildSaKeys {
        final byte[] initiatorChildSpi;
        final byte[] responderChildSpi;
        final byte[] initiatorToResponderKeyMaterial;
        final byte[] responderToInitiatorKeyMaterial;
        private boolean destroyed;

        ChildSaKeys(
                byte[] initiatorChildSpi,
                byte[] responderChildSpi,
                byte[] initiatorToResponderKeyMaterial,
                byte[] responderToInitiatorKeyMaterial) {
            this.initiatorChildSpi =
                    initiatorChildSpi.clone();
            this.responderChildSpi =
                    responderChildSpi.clone();
            this.initiatorToResponderKeyMaterial =
                    initiatorToResponderKeyMaterial.clone();
            this.responderToInitiatorKeyMaterial =
                    responderToInitiatorKeyMaterial.clone();
        }

        EspSession newSession(
                NrLinkBluetoothPrelude.LocalRole localRole) {
            requireLive();
            if (localRole
                    == NrLinkBluetoothPrelude.LocalRole.INITIATOR) {
                return new EspSession(
                        responderChildSpi,
                        initiatorToResponderKeyMaterial,
                        initiatorChildSpi,
                        responderToInitiatorKeyMaterial);
            }
            if (localRole
                    == NrLinkBluetoothPrelude.LocalRole.RESPONDER) {
                return new EspSession(
                        initiatorChildSpi,
                        responderToInitiatorKeyMaterial,
                        responderChildSpi,
                        initiatorToResponderKeyMaterial);
            }
            throw new IllegalArgumentException(
                    "Local IKE role is required");
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(initiatorChildSpi);
            wipe(responderChildSpi);
            wipe(initiatorToResponderKeyMaterial);
            wipe(responderToInitiatorKeyMaterial);
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Child-SA keys have been destroyed");
            }
        }
    }

    static final class EspSession {
        private final byte[] outboundSpi;
        private final byte[] outboundKeyMaterial;
        private final byte[] inboundSpi;
        private final byte[] inboundKeyMaterial;
        private final long[] lastSent =
                new long[TRAFFIC_CLASS_COUNT];
        private final ReplayWindow[] replayWindows =
                new ReplayWindow[]{
                        new ReplayWindow(),
                        new ReplayWindow(),
                        new ReplayWindow(),
                        new ReplayWindow()
                };
        private boolean destroyed;

        EspSession(
                byte[] outboundSpi,
                byte[] outboundKeyMaterial,
                byte[] inboundSpi,
                byte[] inboundKeyMaterial) {
            this.outboundSpi = outboundSpi.clone();
            this.outboundKeyMaterial =
                    outboundKeyMaterial.clone();
            this.inboundSpi = inboundSpi.clone();
            this.inboundKeyMaterial =
                    inboundKeyMaterial.clone();
            for (int index = 0;
                    index < TRAFFIC_CLASS_COUNT;
                    index++) {
                lastSent[index] =
                        (long) index
                                * TRAFFIC_CLASS_RANGE_SIZE;
            }
        }

        byte[] encrypt(
                byte[] upperLayerPayload,
                int nextHeader,
                int trafficClassIndex) {
            requireLive();
            long sequence =
                    nextSequence(
                            trafficClassIndex);
            return encryptEsp(
                    outboundSpi,
                    outboundKeyMaterial,
                    sequence,
                    upperLayerPayload,
                    nextHeader);
        }

        DecryptedEsp decrypt(
                byte[] packet) {
            requireLive();
            if (packet == null
                    || packet.length < ESP_HEADER_LENGTH) {
                throw new IllegalArgumentException(
                        "Inbound ESP packet is truncated");
            }
            long sequence =
                    readUnsignedBe32(
                            packet,
                            CHILD_SPI_LENGTH);
            int trafficClassIndex =
                    (int) (sequence >>> 30);
            ReplayWindow window =
                    replayWindows[trafficClassIndex];
            window.requireAcceptable(sequence);
            DecryptedEsp decrypted =
                    decryptEsp(
                            inboundSpi,
                            inboundKeyMaterial,
                            packet);
            window.commit(sequence);
            return decrypted;
        }

        /**
         * Matches an inbound ESP header without exposing or copying its SPI.
         * The caller still has to authenticate the complete packet before
         * treating the match as anything more than a demultiplexing hint.
         */
        boolean matchesInboundSpi(
                byte[] packet,
                int offset) {
            requireLive();
            if (packet == null
                    || offset < 0
                    || offset > packet.length - CHILD_SPI_LENGTH) {
                return false;
            }
            int difference = 0;
            for (int index = 0;
                    index < CHILD_SPI_LENGTH;
                    index++) {
                difference |=
                        packet[offset + index]
                                ^ inboundSpi[index];
            }
            return difference == 0;
        }

        byte[] encryptIpv6Transport(
                byte[] ipv6Packet) {
            requireLive();
            validateIpv6Packet(ipv6Packet);
            int originalNextHeader =
                    ipv6Packet[6] & 0xff;
            if (originalNextHeader
                    == ESP_PROTOCOL_NUMBER) {
                throw new IllegalArgumentException(
                        "Nested baseline ESP transport "
                                + "is unsupported");
            }
            int trafficClassIndex =
                    trafficClassIndexForIpv6Packet(
                            ipv6Packet);
            byte[] upperLayer =
                    Arrays.copyOfRange(
                            ipv6Packet,
                            IPV6_HEADER_LENGTH,
                            ipv6Packet.length);
            byte[] esp = null;
            try {
                esp = encrypt(
                        upperLayer,
                        originalNextHeader,
                        trafficClassIndex);
                if (esp.length > 0xffff) {
                    throw new IllegalArgumentException(
                            "ESP payload exceeds baseline IPv6");
                }
                byte[] output =
                        new byte[
                                IPV6_HEADER_LENGTH
                                        + esp.length];
                System.arraycopy(
                        ipv6Packet,
                        0,
                        output,
                        0,
                        IPV6_HEADER_LENGTH);
                output[4] =
                        (byte) (esp.length >>> 8);
                output[5] =
                        (byte) esp.length;
                output[6] =
                        (byte) ESP_PROTOCOL_NUMBER;
                System.arraycopy(
                        esp,
                        0,
                        output,
                        IPV6_HEADER_LENGTH,
                        esp.length);
                return output;
            } finally {
                wipe(upperLayer);
                wipe(esp);
            }
        }

        byte[] decryptIpv6Transport(
                byte[] ipv6Packet) {
            requireLive();
            validateIpv6Packet(ipv6Packet);
            if ((ipv6Packet[6] & 0xff)
                    != ESP_PROTOCOL_NUMBER) {
                throw new IllegalArgumentException(
                        "IPv6 packet does not carry ESP");
            }
            byte[] esp =
                    Arrays.copyOfRange(
                            ipv6Packet,
                            IPV6_HEADER_LENGTH,
                            ipv6Packet.length);
            DecryptedEsp decrypted = null;
            try {
                decrypted = decrypt(esp);
                if (decrypted.payload.length > 0xffff) {
                    throw new IllegalArgumentException(
                            "Decrypted IPv6 payload is too large");
                }
                byte[] output =
                        new byte[
                                IPV6_HEADER_LENGTH
                                        + decrypted.payload.length];
                System.arraycopy(
                        ipv6Packet,
                        0,
                        output,
                        0,
                        IPV6_HEADER_LENGTH);
                output[4] =
                        (byte) (decrypted.payload.length >>> 8);
                output[5] =
                        (byte) decrypted.payload.length;
                output[6] =
                        (byte) decrypted.nextHeader;
                System.arraycopy(
                        decrypted.payload,
                        0,
                        output,
                        IPV6_HEADER_LENGTH,
                        decrypted.payload.length);
                return output;
            } finally {
                wipe(esp);
                if (decrypted != null) {
                    decrypted.destroy();
                }
            }
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(outboundSpi);
            wipe(outboundKeyMaterial);
            wipe(inboundSpi);
            wipe(inboundKeyMaterial);
            Arrays.fill(lastSent, 0);
            for (ReplayWindow window : replayWindows) {
                window.destroy();
            }
            destroyed = true;
        }

        private long nextSequence(
                int trafficClassIndex) {
            if (trafficClassIndex < 0
                    || trafficClassIndex
                    >= TRAFFIC_CLASS_COUNT) {
                throw new IllegalArgumentException(
                        "Traffic-class index must be 0..3");
            }
            long rangeEnd =
                    ((long) trafficClassIndex + 1)
                            * TRAFFIC_CLASS_RANGE_SIZE
                            - 1;
            if (lastSent[trafficClassIndex]
                    >= rangeEnd) {
                throw new IllegalStateException(
                        "ESP traffic-class sequence "
                                + "range is exhausted");
            }
            lastSent[trafficClassIndex]++;
            return lastSent[trafficClassIndex];
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "ESP session has been destroyed");
            }
        }
    }

    static final class DecryptedEsp {
        final byte[] payload;
        final int nextHeader;
        final long sequence;
        final int trafficClassIndex;
        private boolean destroyed;

        DecryptedEsp(
                byte[] payload,
                int nextHeader,
                long sequence) {
            this.payload = payload.clone();
            this.nextHeader = nextHeader;
            this.sequence = sequence;
            this.trafficClassIndex =
                    (int) (sequence >>> 30);
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(payload);
            destroyed = true;
        }
    }

    private static final class ReplayWindow {
        private long highestLowSequence;
        private int bitmap;

        void requireAcceptable(
                long sequence) {
            long low =
                    sequence
                            & (TRAFFIC_CLASS_RANGE_SIZE - 1);
            if (low == 0) {
                throw new IllegalArgumentException(
                        "ESP traffic-class base sequence "
                                + "is forbidden");
            }
            if (highestLowSequence == 0
                    || low > highestLowSequence) {
                return;
            }
            long delta =
                    highestLowSequence - low;
            if (delta >= REPLAY_WINDOW_SIZE) {
                throw new IllegalArgumentException(
                        "ESP packet is outside the replay window");
            }
            if ((bitmap & (1 << (int) delta)) != 0) {
                throw new IllegalArgumentException(
                        "ESP replay detected");
            }
        }

        void commit(
                long sequence) {
            long low =
                    sequence
                            & (TRAFFIC_CLASS_RANGE_SIZE - 1);
            if (highestLowSequence == 0) {
                highestLowSequence = low;
                bitmap = 1;
                return;
            }
            if (low > highestLowSequence) {
                long shift =
                        low - highestLowSequence;
                bitmap = shift >= REPLAY_WINDOW_SIZE
                        ? 1
                        : ((bitmap << (int) shift) | 1)
                        & 0xffff;
                highestLowSequence = low;
            } else {
                int delta =
                        (int) (highestLowSequence - low);
                bitmap |= 1 << delta;
            }
        }

        void destroy() {
            highestLowSequence = 0;
            bitmap = 0;
        }
    }
}
