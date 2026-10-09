package dev.applewatchandroid.bridge;

import android.hardware.bluetooth.IBluetoothHci;
import android.hardware.bluetooth.IBluetoothHciCallbacks;
import android.os.RemoteException;
import java.io.IOException;

/** Android Binder adaptation is confined to this module. */
final class AndroidBluetoothController implements BluetoothController {
    private final IBluetoothHci hci;
    AndroidBluetoothController(IBluetoothHci hci) { this.hci = hci; }
    public void initialize(Callbacks callbacks) throws IOException {
        try {
            hci.initialize(new IBluetoothHciCallbacks.Stub() {
                public void aclDataReceived(byte[] data) { callbacks.aclDataReceived(data); }
                public void hciEventReceived(byte[] data) { callbacks.hciEventReceived(data); }
                public void initializationComplete(int status) { callbacks.initializationComplete(status); }
                public void isoDataReceived(byte[] data) { callbacks.isoDataReceived(data); }
                public void scoDataReceived(byte[] data) { callbacks.scoDataReceived(data); }
            });
        } catch (RemoteException failure) { throw new IOException("HCI initialization failed", failure); }
    }
    public void sendHciCommand(byte[] bytes) throws IOException {
        try { hci.sendHciCommand(bytes); }
        catch (RemoteException failure) { throw new IOException("HCI command failed", failure); }
    }
    public void sendAclData(byte[] bytes) throws IOException {
        try { hci.sendAclData(bytes); }
        catch (RemoteException failure) { throw new IOException("ACL send failed", failure); }
    }
    public void close() throws IOException {
        try { hci.close(); }
        catch (RemoteException failure) { throw new IOException("HCI close failed", failure); }
    }
}
