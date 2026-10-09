package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

/** Optical pairing IKE AUTH after X448/ML-KEM IKE_INTERMEDIATE.
 * RFC7296 PSK AUTH (method 2) + RFC9242 IntAuth; no PIN/SPAKE2+/PPK.
 * The caller first obtains a secret bound to a fresh Watch advertisement.
 * This class authenticates the peer; receiving/decrypting a packet alone does
 * not complete authentication. The enclosing HAL still owns the transport.
 */
final class OpticalPskSession implements AutoCloseable {
    static final int AUTH_MESSAGE_ID = 2;
    static final int NEXT_INFORMATIONAL_MESSAGE_ID = 3;
    static final String KEY_ID = "com.apple.networkrelay.companionlink.pairing.auth.randomKey";
    private static final byte[] KEY_ID_BODY = concatenate(new byte[]{11, 0, 0, 0},
            KEY_ID.getBytes(StandardCharsets.US_ASCII));
    private static final byte[] PAD = "Key Pad for IKEv2".getBytes(StandardCharsets.US_ASCII);
    private final byte[] initialRequest, initialResponse, initiatorNonce, responderNonce;
    private final IkeV2SessionCrypto.IkeSaKeys keys;
    private final byte[] intAuthI, intAuthR, sharedSecret;
    private byte[][] fragments;
    private int received, firstPayload;
    private boolean requestSent, authenticated, closed;

    OpticalPskSession(IkeV2Codec.InitiatorState initiator,
            IkeV2SessionCrypto.ControlSaInitResponse response,
            IkeV2SessionCrypto.IkeSaKeys keys, byte[] intAuthI, byte[] intAuthR,
            byte[] sharedSecret) {
        if (initiator == null || response == null || keys == null
                || intAuthI == null || intAuthI.length != 64
                || intAuthR == null || intAuthR.length != 64
                || sharedSecret == null || sharedSecret.length != 44
                || !Arrays.equals(initiator.initiatorSpi, keys.initiatorSpi)
                || !Arrays.equals(response.responderSpi, keys.responderSpi)
                || !Arrays.equals(initiator.nonce, keys.initiatorNonce)
                || !Arrays.equals(response.responderNonce, keys.responderNonce)) {
            throw new IllegalArgumentException("Optical PSK session requires bound keys and transcripts");
        }
        this.initialRequest = initiator.ikePacket.clone();
        this.initialResponse = response.packet.clone();
        this.initiatorNonce = initiator.nonce.clone();
        this.responderNonce = response.responderNonce.clone();
        this.keys = keys;
        this.intAuthI = intAuthI.clone(); this.intAuthR = intAuthR.clone();
        this.sharedSecret = sharedSecret.clone();
    }

    List<byte[]> request(SecureRandom random) {
        if (closed || requestSent || random == null) throw new IllegalStateException("Optical PSK request is unavailable");
        byte[] id = AppleWatchPairingCrypto.physicalInitiatorIdPayloadBody();
        byte[] auth = authentication(false, id);
        byte[] body = concatenate(new byte[]{2, 0, 0, 0}, auth);
        byte[] plaintext = concatenate(payload(41, id), payload(36, new byte[]{0, 0, 0x40, 0}),
                payload(39, KEY_ID_BODY), payload(0, body));
        try {
            List<byte[]> packets = IkeV2SessionCrypto.encryptProtectedPayload(random,
                    keys.initiatorSpi, keys.responderSpi, keys.skEi, false,
                    IkeV2SessionCrypto.EXCHANGE_IKE_AUTH, AUTH_MESSAGE_ID,
                    plaintext, 35, IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE);
            requestSent = true;
            return packets;
        } finally { wipe(id); wipe(auth); wipe(body); wipe(plaintext); }
    }

    IkeV2SessionCrypto.IkeAuthResponse acceptResponse(byte[] packet) {
        if (closed || !requestSent || authenticated) throw new IllegalStateException("Optical PSK response is unavailable");
        if (packet == null || packet.length > 4096) throw invalid();
        IkeV2SessionCrypto.DecryptedIntermediatePart part = IkeV2SessionCrypto.decryptProtectedPacket(packet,
                keys.initiatorSpi, keys.responderSpi, keys.skEr, true,
                IkeV2SessionCrypto.EXCHANGE_IKE_AUTH, AUTH_MESSAGE_ID);
        if (!part.fragmented) {
            if (fragments != null) { wipe(part.plaintext); throw invalid(); }
            try { return verify(part.firstInnerPayload, part.plaintext); }
            finally { wipe(part.plaintext); }
        }
        if (part.totalFragments < 1 || part.totalFragments > 16) { wipe(part.plaintext); throw invalid(); }
        if (fragments == null) fragments = new byte[part.totalFragments][];
        if (fragments.length != part.totalFragments) { wipe(part.plaintext); throw invalid(); }
        int index = part.fragmentNumber - 1;
        if (fragments[index] != null) {
            boolean equal = Arrays.equals(fragments[index], part.plaintext)
                    && (index != 0 || firstPayload == part.firstInnerPayload);
            wipe(part.plaintext);
            if (!equal) throw invalid();
            return null;
        }
        fragments[index] = part.plaintext; received++;
        if (part.fragmentNumber == 1) firstPayload = part.firstInnerPayload;
        if (received != fragments.length) return null;
        byte[] plaintext = concatenate(fragments);
        try { return verify(firstPayload, plaintext); }
        finally { wipe(plaintext); clearFragments(); }
    }

    boolean authenticated() { return authenticated && !closed; }

    private IkeV2SessionCrypto.IkeAuthResponse verify(int first, byte[] plaintext) {
        byte[] id = null, auth = null, expected = null;
        List<Integer> payloads = new ArrayList<>(), notifies = new ArrayList<>();
        try {
            int at = 0, type = first;
            while (type != 0) {
                if (at + 4 > plaintext.length) throw invalid();
                int next = plaintext[at] & 255, flags = plaintext[at + 1] & 255;
                int length = (plaintext[at + 2] & 255) * 256 + (plaintext[at + 3] & 255);
                if (length < 4 || at + length > plaintext.length || (flags & 127) != 0) throw invalid();
                payloads.add(type);
                int start = at + 4, end = at + length, bodyLength = length - 4;
                if (type == 36) {
                    if (id != null || bodyLength != KEY_ID_BODY.length) throw invalid();
                    id = Arrays.copyOfRange(plaintext, start, end);
                    if (!Arrays.equals(id, KEY_ID_BODY)) throw invalid();
                } else if (type == 39) {
                    if (auth != null || bodyLength != 68 || plaintext[start] != 2
                            || plaintext[start + 1] != 0 || plaintext[start + 2] != 0 || plaintext[start + 3] != 0) throw invalid();
                    auth = Arrays.copyOfRange(plaintext, start + 4, end);
                } else if (type == 41) {
                    if (bodyLength < 4 || plaintext[start] != 0 || plaintext[start + 1] != 0) throw invalid();
                    int notify = (plaintext[start + 2] & 255) * 256 + (plaintext[start + 3] & 255);
                    if (notify < 0x4000 || notify == 0x4033 || notify == 0x4034 || notify == 0x4035) throw invalid();
                    notifies.add(notify);
                } else {
                    // This profile is childless; no Child-SA, GSPM, certificate
                    // or other authentication method is accepted as success.
                    throw invalid();
                }
                at = end; type = next;
            }
            if (at != plaintext.length || id == null || auth == null) throw invalid();
            expected = authentication(true, id);
            if (!MessageDigest.isEqual(expected, auth)) throw new IllegalArgumentException("Optical PSK peer authentication failed");
            authenticated = true;
            return new IkeV2SessionCrypto.IkeAuthResponse(payloads, notifies, auth.length);
        } finally { wipe(id); wipe(auth); wipe(expected); }
    }

    private byte[] authentication(boolean responder, byte[] id) {
        byte[] macId = prf(responder ? keys.skPr : keys.skPi, id);
        byte[] signed = concatenate(responder ? initialResponse : initialRequest,
                responder ? initiatorNonce : responderNonce, macId, intAuthI, intAuthR,
                new byte[]{0, 0, 0, AUTH_MESSAGE_ID});
        byte[] paddedKey = prf(sharedSecret, PAD);
        try { return prf(paddedKey, signed); }
        finally { wipe(macId); wipe(signed); wipe(paddedKey); }
    }

    static List<byte[]> controlMethodRequest(SecureRandom random, IkeV2SessionCrypto.IkeSaKeys controlKeys) {
        if (random == null || controlKeys == null) throw new IllegalArgumentException("Optical method negotiation requires control keys");
        // NRTLV(type=authMethod/1,length=1,value=PSK/1), inside C545.
        byte[] plaintext = payload(0, new byte[]{0, 0, (byte) 0xc5, 0x45, 1, 0, 1, 1});
        try { return IkeV2SessionCrypto.encryptProtectedPayload(random, controlKeys.initiatorSpi,
                controlKeys.responderSpi, controlKeys.skEi, false, IkeV2SessionCrypto.EXCHANGE_INFORMATIONAL,
                3, plaintext, 41, IkeV2SessionCrypto.CONTROL_MAX_IKE_PACKET_SIZE); }
        finally { wipe(plaintext); }
    }

    private static byte[] prf(byte[] key, byte[] bytes) {
        HMac mac = new HMac(new SHA512Digest()); mac.init(new KeyParameter(key));
        mac.update(bytes, 0, bytes.length); byte[] output = new byte[64]; mac.doFinal(output, 0); return output;
    }
    private static byte[] payload(int next, byte[] body) {
        return concatenate(new byte[]{(byte) next, 0, (byte) ((body.length + 4) >>> 8), (byte) (body.length + 4)}, body);
    }
    private static byte[] concatenate(byte[]... values) {
        int length = 0;
        for (byte[] value : values) length = Math.addExact(length, value.length);
        byte[] output = new byte[length];
        int at = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, output, at, value.length);
            at += value.length;
        }
        return output;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid optical PSK response"); }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    private void clearFragments() {
        if (fragments != null) for (byte[] fragment : fragments) wipe(fragment);
        fragments = null; received = 0; firstPayload = 0;
    }
    @Override public void close() {
        closed = true; wipe(sharedSecret); wipe(intAuthI); wipe(intAuthR); clearFragments();
        wipe(initialRequest); wipe(initialResponse); wipe(initiatorNonce); wipe(responderNonce);
    }
}
