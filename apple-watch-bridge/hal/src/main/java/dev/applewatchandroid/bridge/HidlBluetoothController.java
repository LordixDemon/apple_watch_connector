package dev.applewatchandroid.bridge;

import android.os.HwBinder;
import android.os.HwParcel;
import android.os.IHwBinder;
import android.os.IHwInterface;
import android.os.RemoteException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.NoSuchElementException;

/**
 * HIDL {@code android.hardware.bluetooth@1.0::IBluetoothHci} adapter for devices
 * that do not declare the AIDL HAL (for example Qualcomm vendor images that
 * still ship {@code android.hardware.bluetooth@1.1-service-qti}).
 *
 * <p>Contributed by Muggles001. Transactions are encoded by hand because the
 * generated HIDL Java classes are not on the app_process classpath. Transaction
 * codes follow the method order of the 1.0 .hal files; a 1.1 service still
 * serves the 1.0 methods.</p>
 */
final class HidlBluetoothController implements BluetoothController {
    static final String HCI_DESCRIPTOR = "android.hardware.bluetooth@1.0::IBluetoothHci";
    private static final String CALLBACKS_DESCRIPTOR =
            "android.hardware.bluetooth@1.0::IBluetoothHciCallbacks";
    private static final String BASE_DESCRIPTOR = "android.hidl.base@1.0::IBase";

    private static final int HCI_INITIALIZE = 1;
    private static final int HCI_SEND_COMMAND = 2;
    private static final int HCI_SEND_ACL = 3;
    private static final int HCI_CLOSE = 5;

    private static final int CB_INITIALIZATION_COMPLETE = 1;
    private static final int CB_HCI_EVENT = 2;
    private static final int CB_ACL_DATA = 3;
    private static final int CB_SCO_DATA = 4;

    private static final int IBASE_INTERFACE_CHAIN = 0x0f43484e;
    private static final int IBASE_INTERFACE_DESCRIPTOR = 0x0f445343;
    private static final int IBASE_PING = 0x0f504e47;
    private static final int FLAG_ONEWAY = 1;

    private static final Object THREADPOOL_LOCK = new Object();
    private static boolean threadpoolStarted;

    private final IHwBinder hci;
    private CallbackBinder callbackBinder;

    private HidlBluetoothController(IHwBinder hci) {
        this.hci = hci;
    }

    /** Returns null when the device does not register the HIDL HCI service. */
    static HidlBluetoothController openOrNull() throws IOException {
        IHwBinder binder;
        try {
            binder = HwBinder.getService(HCI_DESCRIPTOR, "default");
        } catch (NoSuchElementException | LinkageError absent) {
            return null;
        } catch (RemoteException failure) {
            throw new IOException("Cannot query hwservicemanager", failure);
        }
        return binder == null ? null : new HidlBluetoothController(binder);
    }

    @Override
    public void initialize(Callbacks callbacks) throws IOException {
        startThreadpool();
        callbackBinder = new CallbackBinder(callbacks);
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(HCI_DESCRIPTOR);
        request.writeStrongBinder(callbackBinder);
        call(HCI_INITIALIZE, request, "HCI initialization failed");
    }

    @Override
    public void sendHciCommand(byte[] bytes) throws IOException {
        call(HCI_SEND_COMMAND, packet(bytes), "HCI command failed");
    }

    @Override
    public void sendAclData(byte[] bytes) throws IOException {
        call(HCI_SEND_ACL, packet(bytes), "ACL send failed");
    }

    @Override
    public void close() throws IOException {
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(HCI_DESCRIPTOR);
        call(HCI_CLOSE, request, "HCI close failed");
    }

    private static HwParcel packet(byte[] bytes) {
        ArrayList<Byte> vector = new ArrayList<>(bytes.length);
        for (byte value : bytes) vector.add(value);
        HwParcel request = new HwParcel();
        request.writeInterfaceToken(HCI_DESCRIPTOR);
        request.writeInt8Vector(vector);
        return request;
    }

    private void call(int code, HwParcel request, String failure) throws IOException {
        HwParcel reply = new HwParcel();
        try {
            hci.transact(code, request, reply, 0);
            reply.verifySuccess();
            request.releaseTemporaryStorage();
        } catch (RemoteException | RuntimeException error) {
            throw new IOException(failure, error);
        } finally {
            reply.release();
        }
    }

    // Callbacks arrive on hwbinder threads, which app_process does not start
    // on its own; one joined daemon thread lets the driver spawn the rest.
    private static void startThreadpool() {
        synchronized (THREADPOOL_LOCK) {
            if (threadpoolStarted) return;
            HwBinder.configureRpcThreadpool(4, true);
            Thread joiner = new Thread(HwBinder::joinRpcThreadpool, "apple-watch-hwbinder");
            joiner.setDaemon(true);
            joiner.start();
            threadpoolStarted = true;
        }
    }

    private static byte[] bytes(ArrayList<Byte> vector) {
        byte[] out = new byte[vector.size()];
        for (int i = 0; i < out.length; i++) out[i] = vector.get(i);
        return out;
    }

    private static final class CallbackBinder extends HwBinder implements IHwInterface {
        private final Callbacks callbacks;

        CallbackBinder(Callbacks callbacks) {
            this.callbacks = callbacks;
        }

        @Override
        public IHwBinder asBinder() {
            return this;
        }

        @Override
        public IHwInterface queryLocalInterface(String descriptor) {
            return CALLBACKS_DESCRIPTOR.equals(descriptor) ? this : null;
        }

        @Override
        public void onTransact(int code, HwParcel request, HwParcel reply, int flags) {
            switch (code) {
                case CB_INITIALIZATION_COMPLETE -> {
                    request.enforceInterface(CALLBACKS_DESCRIPTOR);
                    callbacks.initializationComplete(request.readInt32());
                }
                case CB_HCI_EVENT -> {
                    request.enforceInterface(CALLBACKS_DESCRIPTOR);
                    callbacks.hciEventReceived(bytes(request.readInt8Vector()));
                }
                case CB_ACL_DATA -> {
                    request.enforceInterface(CALLBACKS_DESCRIPTOR);
                    callbacks.aclDataReceived(bytes(request.readInt8Vector()));
                }
                case CB_SCO_DATA -> {
                    request.enforceInterface(CALLBACKS_DESCRIPTOR);
                    callbacks.scoDataReceived(bytes(request.readInt8Vector()));
                }
                case IBASE_INTERFACE_CHAIN -> {
                    request.enforceInterface(BASE_DESCRIPTOR);
                    ArrayList<String> chain = new ArrayList<>();
                    chain.add(CALLBACKS_DESCRIPTOR);
                    chain.add(BASE_DESCRIPTOR);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.writeStringVector(chain);
                    reply.send();
                    return;
                }
                case IBASE_INTERFACE_DESCRIPTOR -> {
                    request.enforceInterface(BASE_DESCRIPTOR);
                    reply.writeStatus(HwParcel.STATUS_SUCCESS);
                    reply.writeString(CALLBACKS_DESCRIPTOR);
                    reply.send();
                    return;
                }
                case IBASE_PING -> request.enforceInterface(BASE_DESCRIPTOR);
                default -> {
                    return;
                }
            }
            if ((flags & FLAG_ONEWAY) == 0) {
                reply.writeStatus(HwParcel.STATUS_SUCCESS);
                reply.send();
            }
        }
    }
}
