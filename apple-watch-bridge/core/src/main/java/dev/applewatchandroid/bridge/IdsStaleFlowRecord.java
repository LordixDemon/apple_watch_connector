package dev.applewatchandroid.bridge;

/**
 * One observed Watch TCP flow (either direction) whose death forces the
 * Watch identityservicesd to reopen its ids-control-channel. watchOS 26.2
 * Ghidra evidence (docs/live-0.2.297/firmware-evidence): the Watch's
 * {@code startControlChannelWithDevice:} early-exits while its previous
 * control connection is still connecting/connected, and only a fatal utun
 * socket error ({@code FUN_10035caa4}) clears that flag and retries the
 * connector. A process restart on our side leaves the Watch TCB alive for
 * its full ~8.6-min RTO unless we reset it proactively.
 *
 * <p>The record carries everything a proactive RST needs after a restart:
 * the tuple, the peer's last acknowledgement number (= the Watch's
 * RCV.NXT, the only sequence value RFC 5961 accepts for a reset), and the
 * peer's last timestamp value so the reset can carry a PAWS-valid echo.
 * The timestamp clock ticks at ~1000 Hz (live 13:19-13:21: 64002 ticks in
 * 64 s), so elapsed milliseconds extrapolate the echo.</p>
 */
final class IdsStaleFlowRecord {
    static final int ADDRESS_LENGTH = 16;

    final OrdinaryIkeAuth.DataClass dataClass;
    /** Phone side of the flow: source of the proactive reset. */
    final byte[] localAddress;
    /** Watch side of the flow: destination of the proactive reset. */
    final byte[] remoteAddress;
    final int localPort;
    final int remotePort;
    /** Last acknowledgement seen from the Watch == its RCV.NXT. */
    final long peerAck;
    /** Last TSval seen from the Watch, or -1 when the flow has no TS. */
    final long peerTimestamp;
    final long lastSeenEpochMs;

    IdsStaleFlowRecord(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] localAddress,
            byte[] remoteAddress,
            int localPort,
            int remotePort,
            long peerAck,
            long peerTimestamp,
            long lastSeenEpochMs) {
        if (dataClass == null
                || localAddress == null
                || localAddress.length != ADDRESS_LENGTH
                || remoteAddress == null
                || remoteAddress.length != ADDRESS_LENGTH
                || localPort <= 0
                || localPort > 65535
                || remotePort <= 0
                || remotePort > 65535
                || peerAck < 0
                || peerAck > 0xffff_ffffL
                || peerTimestamp < -1
                || peerTimestamp > 0xffff_ffffL
                || lastSeenEpochMs <= 0) {
            throw new IllegalArgumentException(
                    "Stale flow record is incomplete");
        }
        this.dataClass = dataClass;
        this.localAddress = localAddress.clone();
        this.remoteAddress = remoteAddress.clone();
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.peerAck = peerAck;
        this.peerTimestamp = peerTimestamp;
        this.lastSeenEpochMs = lastSeenEpochMs;
    }

    static IdsStaleFlowRecord fromInbound(
            OrdinaryIkeAuth.DataClass dataClass,
            Ipv6TcpPacketCodec.Packet packet,
            long nowEpochMs) {
        Ipv6TcpPacketCodec.PeerOptions peer =
                Ipv6TcpPacketCodec.parsePeerOptions(packet.options);
        return new IdsStaleFlowRecord(
                dataClass,
                packet.destinationAddress,
                packet.sourceAddress,
                packet.destinationPort,
                packet.sourcePort,
                packet.acknowledgement,
                peer.timestampValue,
                nowEpochMs);
    }

    /** Allows only the current or separately authenticated stored quartet. */
    boolean matchesAddresses(byte[] localD, byte[] remoteD, byte[] localC, byte[] remoteC) {
        byte[] local = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D ? localD : localC;
        byte[] remote = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D ? remoteD : remoteC;
        return java.util.Arrays.equals(localAddress, local)
                && java.util.Arrays.equals(remoteAddress, remote);
    }

    /** Identity of the flow across sessions: class + ports. */
    String key() {
        return dataClass.name()
                + ":"
                + localPort
                + ":"
                + remotePort;
    }

    String encode() {
        StringBuilder line = new StringBuilder(96);
        line.append("1 ");
        line.append(dataClass.name());
        line.append(' ');
        line.append(localPort);
        line.append(' ');
        line.append(remotePort);
        line.append(' ');
        line.append(Long.toUnsignedString(peerAck));
        line.append(' ');
        line.append(Long.toString(peerTimestamp));
        line.append(' ');
        line.append(Long.toString(lastSeenEpochMs));
        line.append(' ');
        appendHex(line, localAddress);
        line.append(' ');
        appendHex(line, remoteAddress);
        return line.toString();
    }

    static IdsStaleFlowRecord decode(String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 9
                || !"1".equals(fields[0])) {
            throw new IllegalArgumentException(
                    "Stale flow line is malformed");
        }
        OrdinaryIkeAuth.DataClass dataClass =
                OrdinaryIkeAuth.DataClass.valueOf(fields[1]);
        int localPort = Integer.parseInt(fields[2]);
        int remotePort = Integer.parseInt(fields[3]);
        long peerAck = Long.parseUnsignedLong(fields[4]);
        long peerTimestamp = Long.parseLong(fields[5]);
        long lastSeenEpochMs = Long.parseLong(fields[6]);
        byte[] localAddress = parseHex(fields[7]);
        byte[] remoteAddress = parseHex(fields[8]);
        return new IdsStaleFlowRecord(
                dataClass,
                localAddress,
                remoteAddress,
                localPort,
                remotePort,
                peerAck,
                peerTimestamp,
                lastSeenEpochMs);
    }

    private static void appendHex(StringBuilder out, byte[] bytes) {
        for (byte b : bytes) {
            out.append(Character.forDigit((b >>> 4) & 0xf, 16));
            out.append(Character.forDigit(b & 0xf, 16));
        }
    }

    private static byte[] parseHex(String hex) {
        if (hex == null
                || hex.length() != ADDRESS_LENGTH * 2) {
            throw new IllegalArgumentException(
                    "Stale flow address hex is malformed");
        }
        byte[] out = new byte[ADDRESS_LENGTH];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException(
                        "Stale flow address hex is malformed");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
