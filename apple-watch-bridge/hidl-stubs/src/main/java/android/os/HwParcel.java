package android.os;

import java.util.ArrayList;

/**
 * Compile-only stub for Android framework {@code android.os.HwParcel}.
 * Resolved against the device's boot classpath at runtime inside {@code app_process}.
 */
public class HwParcel {
    public static final int STATUS_SUCCESS = 0;

    public HwParcel() {
        throw new RuntimeException("Stub!");
    }

    public final void writeInterfaceToken(String interfaceName) {
        throw new RuntimeException("Stub!");
    }

    public final void enforceInterface(String interfaceName) {
        throw new RuntimeException("Stub!");
    }

    public final void writeStrongBinder(IHwBinder binder) {
        throw new RuntimeException("Stub!");
    }

    public final void writeInt8Vector(ArrayList<Byte> val) {
        throw new RuntimeException("Stub!");
    }

    public final ArrayList<Byte> readInt8Vector() {
        throw new RuntimeException("Stub!");
    }

    public final int readInt32() {
        throw new RuntimeException("Stub!");
    }

    public final void writeStatus(int status) {
        throw new RuntimeException("Stub!");
    }

    public final void writeString(String val) {
        throw new RuntimeException("Stub!");
    }

    public final void writeStringVector(ArrayList<String> val) {
        throw new RuntimeException("Stub!");
    }

    public final void verifySuccess() {
        throw new RuntimeException("Stub!");
    }

    public final void releaseTemporaryStorage() {
        throw new RuntimeException("Stub!");
    }

    public final void release() {
        throw new RuntimeException("Stub!");
    }

    public final void send() {
        throw new RuntimeException("Stub!");
    }
}
