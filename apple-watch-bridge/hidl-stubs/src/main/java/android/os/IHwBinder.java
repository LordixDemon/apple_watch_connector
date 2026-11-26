package android.os;

/**
 * Compile-only stub for Android framework {@code android.os.IHwBinder}.
 * Resolved against the device's boot classpath at runtime inside {@code app_process}.
 */
public interface IHwBinder {
    void transact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException;

    IHwInterface queryLocalInterface(String descriptor);
}
