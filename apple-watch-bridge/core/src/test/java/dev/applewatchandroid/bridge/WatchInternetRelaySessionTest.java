package dev.applewatchandroid.bridge;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class WatchInternetRelaySessionTest {
    private static final byte[] LOCAL_D = new byte[]{
            (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    private static final byte[] REMOTE_D = new byte[]{
            (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2};
    private static final byte[] LOCAL_C = new byte[]{
            (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1};
    private static final byte[] REMOTE_C = new byte[]{
            (byte) 0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 2};

    @Test
    public void relaysTcpFlowBidirectionallyWithFlowOpenAndHalfClose() throws Exception {
        byte[] expectedReply = "HELLO-FROM-APPLE-SERVER".getBytes(StandardCharsets.US_ASCII);
        byte[] expectedRequest = "CLIENT-HELLO-PAYLOAD".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream serverReceived = new ByteArrayOutputStream();

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int serverPort = server.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket client = server.accept()) {
                    InputStream in = client.getInputStream();
                    byte[] buf = new byte[256];
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        serverReceived.write(buf, 0, n);
                    }
                    OutputStream out = client.getOutputStream();
                    out.write(expectedReply);
                    out.flush();
                } catch (IOException ignored) {
                }
            }, "relay-test-server");
            serverThread.setDaemon(true);
            serverThread.start();

            List<String> logs = new ArrayList<>();
            WatchInternetRelaySession.UpstreamDialer localDialer = (host, port, timeoutMs) -> {
                assertEquals("mesu.apple.com", host);
                assertEquals(443, port);
                Socket s = new Socket(InetAddress.getLoopbackAddress(), serverPort);
                s.setTcpNoDelay(true);
                return s;
            };

            try (WatchInternetRelaySession relay = new WatchInternetRelaySession(
                    LOCAL_D, REMOTE_D, LOCAL_C, REMOTE_C,
                    ShoesProxyCodec.PROXY_PORT, new SecureRandom(), localDialer, logs::add);
                 Ipv6TcpStream watchClient = Ipv6TcpStream.active(
                         REMOTE_D, LOCAL_D, 50123, ShoesProxyCodec.PROXY_PORT, 1000L)) {

                ByteArrayOutputStream watchReceived = new ByteArrayOutputStream();
                // 1. Watch SYN -> Relay SYN-ACK -> Watch ACK
                assertTrue(relay.accept(deliveredD(watchClient.startActiveOpen())));
                pumpRelayToWatch(relay, watchClient, watchReceived);
                assertEquals(Ipv6TcpStream.State.ESTABLISHED, watchClient.state());

                // 2. Watch sends fragmented Shoes header + trailing payload + FIN (half-close)
                byte[] header = ShoesProxyCodec.encodeHeader("mesu.apple.com", 443, "com.apple.mobileassetd");
                byte[] firstHalf = Arrays.copyOfRange(header, 0, 7);
                byte[] secondHalf = new byte[(header.length - 7) + expectedRequest.length];
                System.arraycopy(header, 7, secondHalf, 0, header.length - 7);
                System.arraycopy(expectedRequest, 0, secondHalf, header.length - 7, expectedRequest.length);

                sendWatchPackets(relay, watchClient.send(firstHalf));
                pumpRelayToWatch(relay, watchClient, watchReceived);
                sendWatchPackets(relay, watchClient.send(secondHalf));
                sendWatchPackets(relay, List.of(watchClient.closeOutput()));

                // 3. Poll until server replies, relay sends FLOW_OPEN + reply + FIN, and Watch finishes
                long deadline = System.currentTimeMillis() + 5_000L;
                while (System.currentTimeMillis() < deadline
                        && (watchClient.state() != Ipv6TcpStream.State.TIME_WAIT || relay.activeFlowCount() > 0)) {
                    pumpRelayToWatch(relay, watchClient, watchReceived);
                    Thread.sleep(10L);
                }

                serverThread.join(2_000L);
                assertArrayEquals(expectedRequest, serverReceived.toByteArray());
                byte[] received = watchReceived.toByteArray();
                assertTrue("Expected FLOW_OPEN + reply", received.length == 8 + expectedReply.length);
                assertArrayEquals(ShoesProxyCodec.flowOpenBytes(), Arrays.copyOfRange(received, 0, 8));
                assertArrayEquals(expectedReply, Arrays.copyOfRange(received, 8, received.length));
                assertEquals(0, relay.activeFlowCount());
                assertTrue(logs.stream().anyMatch(l -> l.contains("INTERNET RELAY CONNECTED: service=com.apple.mobileassetd")));
            }
        }
    }

    @Test
    public void sendsTcpResetWhenUpstreamConnectFails() throws Exception {
        List<String> logs = new ArrayList<>();
        WatchInternetRelaySession.UpstreamDialer failingDialer = (host, port, timeoutMs) -> {
            throw new IOException("No route to host");
        };
        try (WatchInternetRelaySession relay = new WatchInternetRelaySession(
                LOCAL_D, REMOTE_D, LOCAL_C, REMOTE_C,
                ShoesProxyCodec.PROXY_PORT, new SecureRandom(), failingDialer, logs::add);
             Ipv6TcpStream watchClient = Ipv6TcpStream.active(
                     REMOTE_C, LOCAL_C, 50456, ShoesProxyCodec.PROXY_PORT, 2000L)) {

            ByteArrayOutputStream watchReceived = new ByteArrayOutputStream();
            assertTrue(relay.accept(deliveredC(watchClient.startActiveOpen())));
            pumpRelayToWatch(relay, watchClient, watchReceived, OrdinaryIkeAuth.DataClass.CLASS_C);
            assertEquals(Ipv6TcpStream.State.ESTABLISHED, watchClient.state());

            byte[] header = ShoesProxyCodec.encodeHeader("gsa.apple.com", 443, "com.apple.akd");
            for (byte[] pkt : watchClient.send(header)) {
                assertTrue(relay.accept(deliveredC(pkt)));
            }

            long deadline = System.currentTimeMillis() + 5_000L;
            while (System.currentTimeMillis() < deadline && watchClient.state() != Ipv6TcpStream.State.RESET) {
                pumpRelayToWatch(relay, watchClient, watchReceived, OrdinaryIkeAuth.DataClass.CLASS_C);
                Thread.sleep(10L);
            }
            assertEquals(Ipv6TcpStream.State.RESET, watchClient.state());
            assertEquals(0, relay.activeFlowCount());
            assertTrue(logs.stream().anyMatch(l -> l.contains("INTERNET RELAY FAILED: phase=connect")));
        }
    }

    private static NormalLinkPipeSession.DeliveredIp deliveredD(byte[] packet) {
        return new NormalLinkPipeSession.DeliveredIp(
                OrdinaryIkeAuth.DataClass.CLASS_D, true, packet);
    }

    private static NormalLinkPipeSession.DeliveredIp deliveredC(byte[] packet) {
        return new NormalLinkPipeSession.DeliveredIp(
                OrdinaryIkeAuth.DataClass.CLASS_C, true, packet);
    }

    private static void sendWatchPackets(WatchInternetRelaySession relay, List<byte[]> packets) {
        for (byte[] pkt : packets) {
            assertTrue(relay.accept(deliveredD(pkt)));
        }
    }

    private static void pumpRelayToWatch(
            WatchInternetRelaySession relay,
            Ipv6TcpStream watchClient,
            ByteArrayOutputStream watchReceived) {
        pumpRelayToWatch(relay, watchClient, watchReceived, OrdinaryIkeAuth.DataClass.CLASS_D);
    }

    private static void pumpRelayToWatch(
            WatchInternetRelaySession relay,
            Ipv6TcpStream watchClient,
            ByteArrayOutputStream watchReceived,
            OrdinaryIkeAuth.DataClass expectedClass) {
        List<byte[]> fromRelay = new ArrayList<>();
        relay.pollPackets((dc, clearIpv6) -> {
            assertEquals(expectedClass, dc);
            fromRelay.add(clearIpv6.clone());
            return true;
        });
        for (byte[] pkt : fromRelay) {
            if (watchClient.state() == Ipv6TcpStream.State.CLOSED
                    || watchClient.state() == Ipv6TcpStream.State.RESET
                    || watchClient.state() == Ipv6TcpStream.State.TIME_WAIT) {
                continue;
            }
            try (Ipv6TcpStream.InboundResult res = watchClient.accept(pkt)) {
                for (byte[] chunk : res.deliveredBytes()) {
                    watchReceived.writeBytes(chunk);
                }
                for (byte[] reply : res.outboundPackets()) {
                    NormalLinkPipeSession.DeliveredIp delivered =
                            expectedClass == OrdinaryIkeAuth.DataClass.CLASS_D
                                    ? deliveredD(reply) : deliveredC(reply);
                    assertTrue(relay.accept(delivered));
                }
            }
        }
    }
}
