package dev.applewatchandroid.bridge.transport;

import dev.applewatchandroid.bridge.spi.IdsLinkKind;

/**
 * Post-SMP bearer interface carrying IDS control and application frames.
 */
public interface IdsLinkTransport extends AutoCloseable {
    IdsLinkKind kind();

    void sendControlFrame(byte[] payload) throws Exception;

    byte[] receiveControlFrame(long timeoutMs) throws Exception;

    boolean isOpen();
}
