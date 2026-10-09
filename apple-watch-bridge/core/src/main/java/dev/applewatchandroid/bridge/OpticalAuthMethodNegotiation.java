package dev.applewatchandroid.bridge;

import java.util.Arrays;

/** Confirms C546 PSK selection on the authenticated control SA. An empty C545
 * acknowledgement is transport progress only, never an auth-method result. */
final class OpticalAuthMethodNegotiation implements AutoCloseable {
    private final IkeV2SessionCrypto.IkeSaKeys keys;
    private byte[][] fragments;
    private int firstPayload, received, fragmentDirection;
    private boolean acknowledged, selected, closed;

    OpticalAuthMethodNegotiation(IkeV2SessionCrypto.IkeSaKeys keys) {
        if (keys == null) throw new IllegalArgumentException("Control keys are required");
        this.keys = keys;
    }

    /** Returns null until selection; result -1 is a direct response, 0 is a
     * Watch request which HAL must acknowledge before opening the pairing SA. */
    Integer accept(byte[] packet) {
        if (closed || selected) throw new IllegalStateException("Optical method negotiation is unavailable");
        if (packet == null || packet.length < 28 || packet.length > 4096) throw invalid();
        boolean direct = (packet[19] & 0x28) == 0x20;
        if (!direct && !acknowledged) throw invalid();
        int direction = direct ? 0x20 : 0;
        IkeV2SessionCrypto.DecryptedIntermediatePart part =
                IkeV2SessionCrypto.decryptProtectedPacketWithDirectionFlags(packet,
                        keys.initiatorSpi, keys.responderSpi, keys.skEr, direction, 37,
                        direct ? 3 : 0);
        if (!part.fragmented && part.firstInnerPayload == 0 && part.plaintext.length == 0) {
            if (!direct || fragments != null) throw invalid();
            acknowledged = true;
            return null;
        }
        if (!part.fragmented) {
            try {
                if (fragments != null || (direct && acknowledged)) throw invalid();
                verify(part.firstInnerPayload, part.plaintext);
                return direct ? -1 : 0;
            } finally { wipe(part.plaintext); }
        }
        if ((direct && acknowledged) || part.totalFragments > 16) {
            wipe(part.plaintext); throw invalid();
        }
        if (fragments == null) {
            fragments = new byte[part.totalFragments][]; fragmentDirection = direction;
        }
        if (fragments.length != part.totalFragments || fragmentDirection != direction) {
            wipe(part.plaintext); throw invalid();
        }
        int index = part.fragmentNumber - 1;
        if (fragments[index] != null) {
            boolean same = Arrays.equals(fragments[index], part.plaintext)
                    && (index != 0 || firstPayload == part.firstInnerPayload);
            wipe(part.plaintext);
            if (!same) throw invalid();
            return null;
        }
        fragments[index] = part.plaintext; received++;
        if (index == 0) firstPayload = part.firstInnerPayload;
        if (received != fragments.length) return null;
        int length = 0; for (byte[] fragment : fragments) length += fragment.length;
        byte[] plaintext = new byte[length]; int offset = 0;
        for (byte[] fragment : fragments) {
            System.arraycopy(fragment, 0, plaintext, offset, fragment.length); offset += fragment.length;
        }
        try { verify(firstPayload, plaintext); return direct ? -1 : 0; }
        finally { wipe(plaintext); clearFragments(); }
    }

    boolean requestAcknowledged() { return acknowledged && !closed; }
    boolean pskSelected() { return selected && !closed; }

    private void verify(int first, byte[] plaintext) {
        int type = first, offset = 0, method = -1; boolean response = false;
        while (type != 0) {
            if (offset + 8 > plaintext.length || type != 41 || plaintext[offset + 1] != 0) throw invalid();
            int next = plaintext[offset] & 255;
            int length = u16(plaintext, offset + 2);
            if (length < 8 || offset + length > plaintext.length
                    || plaintext[offset + 4] != 0 || plaintext[offset + 5] != 0) throw invalid();
            int notify = u16(plaintext, offset + 6);
            if (notify < 0x4000) throw invalid();
            if (notify == 0xc546) {
                if (response) throw invalid();
                response = true; int at = offset + 8, end = offset + length;
                while (at < end) {
                    if (at + 3 > end) throw invalid();
                    int tlv = plaintext[at] & 255, size = u16(plaintext, at + 1); at += 3;
                    if (at + size > end || tlv == 2) throw invalid(); // PIN salt is not part of PSK.
                    if (tlv == 1) {
                        if (method != -1 || size != 1) throw invalid();
                        method = plaintext[at] & 255;
                    }
                    at += size;
                }
            }
            offset += length; type = next;
        }
        if (offset != plaintext.length || !response || method != 1) throw invalid();
        selected = true;
    }
    private static int u16(byte[] bytes, int at) { return (bytes[at] & 255) * 256 + (bytes[at + 1] & 255); }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Watch did not confirm optical PSK selection");
    }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private void clearFragments() {
        if (fragments != null) for (byte[] fragment : fragments) wipe(fragment);
        fragments = null; received = 0; firstPayload = 0;
    }
    @Override public void close() { closed = true; clearFragments(); }
}
