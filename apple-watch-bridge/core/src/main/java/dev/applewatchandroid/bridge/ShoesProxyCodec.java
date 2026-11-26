package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Codec and safety validator for Apple Watch Companion Network Relay ("Shoes")
 * TCP flows arriving on port {@value #PROXY_PORT}.
 *
 * <p>Wire header format sent by watchOS over the authenticated normal-link IPv6
 * tunnel:</p>
 * <pre>
 * u16 BE headerLength    (bytes following this field, 3..2048)
 * u8     version         (must be 1)
 * u16 BE dstPort         (1..65535)
 * u8     hostLength
 * byte[] host            (ASCII hostname)
 * u8     type            (must be 3)
 * u16 BE serviceLength   (1..512)
 * byte[] service         (ASCII originating daemon, e.g. com.apple.mobileassetd)
 * byte[] trailingBytes   (first relayed payload bytes, e.g. TLS ClientHello)
 * </pre>
 */
final class ShoesProxyCodec {
    static final int PROXY_PORT = 62742;
    static final int MAX_HEADER_LENGTH = 2048;
    static final int MAX_SERVICE_LENGTH = 512;
    static final int PROTOCOL_VERSION = 1;
    static final int SERVICE_TLV_TYPE = 3;

    /** Flow-open acknowledgement returned to the Watch once upstream TCP connects. */
    static final byte[] FLOW_OPEN = new byte[]{
            0x00, 0x06, 0x00, 0x00, 0x04, 0x00, 0x01, 0x20
    };

    private static final Pattern HOST_PATTERN =
            Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private ShoesProxyCodec() {
    }

    enum ParseStatus {
        NEED_MORE,
        COMPLETE,
        INVALID
    }

    static final class Header implements AutoCloseable {
        final String host;
        final int port;
        final String service;
        private byte[] trailingBytes;

        Header(String host, int port, String service, byte[] trailingBytes) {
            this.host = host;
            this.port = port;
            this.service = service;
            this.trailingBytes = trailingBytes == null ? new byte[0] : trailingBytes.clone();
        }

        byte[] trailingBytes() {
            return trailingBytes.clone();
        }

        @Override
        public void close() {
            Arrays.fill(trailingBytes, (byte) 0);
            trailingBytes = new byte[0];
        }
    }

    static final class ParseResult {
        final ParseStatus status;
        final Header header;

        private ParseResult(ParseStatus status, Header header) {
            this.status = status;
            this.header = header;
        }

        static ParseResult needMore() {
            return new ParseResult(ParseStatus.NEED_MORE, null);
        }

        static ParseResult invalid() {
            return new ParseResult(ParseStatus.INVALID, null);
        }

        static ParseResult complete(Header header) {
            return new ParseResult(ParseStatus.COMPLETE, header);
        }
    }

    static ParseResult parse(byte[] buffer) {
        if (buffer == null || buffer.length < 2) {
            return ParseResult.needMore();
        }
        int headerLength = be16(buffer, 0);
        if (headerLength < 3 || headerLength > MAX_HEADER_LENGTH) {
            return ParseResult.invalid();
        }
        int totalHeaderBytes = 2 + headerLength;
        if (buffer.length < totalHeaderBytes) {
            return ParseResult.needMore();
        }
        if (totalHeaderBytes < 9) {
            return ParseResult.invalid();
        }
        int version = buffer[2] & 0xff;
        int port = be16(buffer, 3);
        int hostLength = buffer[5] & 0xff;
        int hostEnd = 6 + hostLength;
        if (version != PROTOCOL_VERSION || port < 1 || port > 65535
                || hostLength == 0 || hostEnd + 3 > totalHeaderBytes) {
            return ParseResult.invalid();
        }
        int type = buffer[hostEnd] & 0xff;
        int serviceLength = be16(buffer, hostEnd + 1);
        int serviceEnd = hostEnd + 3 + serviceLength;
        if (type != SERVICE_TLV_TYPE || serviceLength < 1 || serviceLength > MAX_SERVICE_LENGTH
                || serviceEnd != totalHeaderBytes) {
            return ParseResult.invalid();
        }
        String host = decodeAscii(buffer, 6, hostLength);
        String service = decodeAscii(buffer, hostEnd + 3, serviceLength);
        if (host == null || service == null
                || !HOST_PATTERN.matcher(host).matches()
                || !SERVICE_PATTERN.matcher(service).matches()) {
            return ParseResult.invalid();
        }
        byte[] trailing = buffer.length > totalHeaderBytes
                ? Arrays.copyOfRange(buffer, totalHeaderBytes, buffer.length)
                : new byte[0];
        return ParseResult.complete(new Header(host, port, service, trailing));
    }

    static byte[] encodeHeader(String host, int port, String service) {
        if (host == null || !HOST_PATTERN.matcher(host).matches()
                || service == null || !SERVICE_PATTERN.matcher(service).matches()
                || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid Shoes proxy header fields");
        }
        byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        byte[] serviceBytes = service.getBytes(StandardCharsets.US_ASCII);
        if (hostBytes.length > 255 || serviceBytes.length > MAX_SERVICE_LENGTH) {
            throw new IllegalArgumentException("Shoes proxy header fields too long");
        }
        int headerLength = 1 + 2 + 1 + hostBytes.length + 1 + 2 + serviceBytes.length;
        if (headerLength > MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException("Shoes proxy header exceeds maximum length");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(2 + headerLength);
        out.write((headerLength >>> 8) & 0xff);
        out.write(headerLength & 0xff);
        out.write(PROTOCOL_VERSION);
        out.write((port >>> 8) & 0xff);
        out.write(port & 0xff);
        out.write(hostBytes.length & 0xff);
        out.writeBytes(hostBytes);
        out.write(SERVICE_TLV_TYPE);
        out.write((serviceBytes.length >>> 8) & 0xff);
        out.write(serviceBytes.length & 0xff);
        out.writeBytes(serviceBytes);
        return out.toByteArray();
    }

    static byte[] flowOpenBytes() {
        return FLOW_OPEN.clone();
    }

    /**
     * Rejects non-public destinations (loopback, link-local, site-local/RFC1918,
     * CGNAT, multicast, ULA, unspecified) so Watch relay traffic cannot reach
     * host-local or private LAN services.
     */
    static boolean isSafePublicTarget(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            int b0 = raw[0] & 0xff;
            int b1 = raw[1] & 0xff;
            // 0.0.0.0/8, 100.64.0.0/10 (CGNAT), 192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4
            if (b0 == 0 || (b0 == 100 && (b1 & 0xc0) == 64)
                    || (b0 == 192 && b1 == 0 && (raw[2] & 0xff) == 0)
                    || (b0 == 198 && (b1 == 18 || b1 == 19))
                    || b0 >= 240) {
                return false;
            }
            return true;
        }
        if (address instanceof Inet6Address && raw.length == 16) {
            int b0 = raw[0] & 0xff;
            // fc00::/7 (Unique Local Addresses)
            if ((b0 & 0xfe) == 0xfc) {
                return false;
            }
            return true;
        }
        return false;
    }

    private static String decodeAscii(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            int c = bytes[i] & 0xff;
            if (c < 0x20 || c > 0x7e) {
                return null;
            }
        }
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static int be16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }
}
