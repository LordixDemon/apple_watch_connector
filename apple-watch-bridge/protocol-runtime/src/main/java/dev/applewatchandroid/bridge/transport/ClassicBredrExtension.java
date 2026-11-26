package dev.applewatchandroid.bridge.transport;

/**
 * Optional BR/EDR extension hook for transports that expose Classic Bluetooth capabilities
 * alongside the LE HCI data plane.
 */
public interface ClassicBredrExtension {
    boolean isSupported();
}
