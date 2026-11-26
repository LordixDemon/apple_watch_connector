package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.BluetoothLocalIdentity;

import java.util.Optional;

/**
 * Clean LE HCI transport interface decoupled from controller/HAL specifics and
 * strategy orchestration.
 */
public interface HciTransport {
    enum PacketKind {
        EVENT,
        ACL
    }

    record Packet(PacketKind kind, byte[] bytes) {}

    int connectionHandle();

    int peerAddressType();

    byte[] peerAddress();

    BluetoothLocalIdentity localIdentity();

    void checkStop() throws Exception;

    void sendL2cap(int cid, byte[] payload, String label) throws Exception;

    /**
     * Waits for the next controller packet up to monotonic {@code deadlineMs}
     * (measured on {@link dev.applewatchandroid.bridge.BridgeClock#elapsedRealtime()}),
     * throwing {@link java.util.concurrent.TimeoutException} if the deadline elapses.
     */
    Packet nextPacket(long deadlineMs) throws Exception;

    void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) throws Exception;

    /**
     * Enables Server-to-Client ATT response pass-through on the fixed-channel router
     * until the returned scope is closed.
     */
    AutoCloseable openAttClientScope();

    byte[] handlePeerAttRequest(byte[] request) throws Exception;

    void log(String message);

    default Optional<ClassicBredrExtension> classicBredr() {
        return Optional.empty();
    }
}
