package dev.applewatchandroid.bridge;

/** Persistence contract shared by the portable protocol engine's desktop hosts. */
interface DesktopSecretStore {
    boolean contains(String name);
    byte[] load(String name) throws Exception;
    void store(String name, byte[] bytes) throws Exception;
    String ownerConfirmedPair() throws Exception;
    void publishPair(PairingSessionRecord pair) throws Exception;
}
