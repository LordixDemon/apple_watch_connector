package dev.applewatchandroid.bridge;

import java.io.IOException;

/** Portable packet-level controller lease; platforms own initialization and cleanup. */
interface BluetoothController {
    int SUCCESS = 0;
    int ALREADY_INITIALIZED = 1;
    interface Callbacks {
        void aclDataReceived(byte[] bytes);
        void hciEventReceived(byte[] bytes);
        void initializationComplete(int status);
        void isoDataReceived(byte[] bytes);
        void scoDataReceived(byte[] bytes);
    }
    void initialize(Callbacks callbacks) throws IOException;
    void sendHciCommand(byte[] bytes) throws IOException;
    void sendAclData(byte[] bytes) throws IOException;
    void close() throws IOException;
}
