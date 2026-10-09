package dev.applewatchandroid.bridge;

import java.util.Arrays;

/** Bounded baseline IPv6/UDP, including the mandatory pseudo-header checksum. */
final class Ipv6UdpPacketCodec {
    static byte[] encode(byte[] source, byte[] destination, int sourcePort, int destinationPort, byte[] data) {
        if (source == null || source.length != 16 || destination == null || destination.length != 16
                || sourcePort < 1 || sourcePort > 65535 || destinationPort < 1 || destinationPort > 65535
                || data == null || data.length == 0 || data.length > 1200)
            throw new IllegalArgumentException("Invalid bounded IPv6 UDP packet");
        byte[] out = new byte[48 + data.length];
        out[0] = 0x60; put16(out, 4, 8 + data.length); out[6] = 17; out[7] = 64;
        System.arraycopy(source, 0, out, 8, 16); System.arraycopy(destination, 0, out, 24, 16);
        put16(out, 40, sourcePort); put16(out, 42, destinationPort); put16(out, 44, 8 + data.length);
        System.arraycopy(data, 0, out, 48, data.length);
        int check = checksum(out);
        put16(out, 46, check == 0 ? 65535 : check);
        return out;
    }

    static Packet decode(byte[] in) {
        if (in == null || in.length < 49 || in.length > 1248 || (in[0] & 0xf0) != 0x60
                || in[6] != 17 || get16(in, 4) != in.length - 40 || get16(in, 44) != in.length - 40
                || get16(in, 40) == 0 || get16(in, 42) == 0 || get16(in, 46) == 0 || checksum(in) != 0)
            throw new IllegalArgumentException("Invalid bounded IPv6 UDP packet or checksum");
        return new Packet(Arrays.copyOfRange(in, 8, 24), Arrays.copyOfRange(in, 24, 40),
                get16(in, 40), get16(in, 42), Arrays.copyOfRange(in, 48, in.length));
    }

    private static int checksum(byte[] b) {
        long sum = b.length - 40 + 17;
        for (int i = 8; i < 40; i += 2) sum += get16(b, i);
        for (int i = 40; i < b.length; i += 2)
            sum += ((b[i] & 255) << 8) | (i + 1 < b.length ? b[i + 1] & 255 : 0);
        while (sum > 65535) sum = (sum & 65535) + (sum >>> 16);
        return (int) (~sum & 65535);
    }
    private static int get16(byte[] b, int at) { return (b[at] & 255) << 8 | b[at + 1] & 255; }
    private static void put16(byte[] b, int at, int n) { b[at] = (byte) (n >>> 8); b[at + 1] = (byte) n; }
    record Packet(byte[] source, byte[] destination, int sourcePort, int destinationPort, byte[] data) {
        void destroy() { Arrays.fill(source, (byte) 0); Arrays.fill(destination, (byte) 0); Arrays.fill(data, (byte) 0); }
    }
}
