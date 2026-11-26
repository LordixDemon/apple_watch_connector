package android.os;

/**
 * Compile-only stub for Android framework {@code android.os.HwBinder}.
 * Resolved against the device's boot classpath at runtime inside {@code app_process}.
 */
public abstract class HwBinder implements IHwBinder {
    public HwBinder() {
        throw new RuntimeException("Stub!");
    }

    public static IHwBinder getService(String iface, String serviceName) throws RemoteException {
        throw new RuntimeException("Stub!");
    }

    public static IHwBinder getService(String iface, String serviceName, boolean retry) throws RemoteException {
        throw new RuntimeException("Stub!");
    }

    public static void configureRpcThreadpool(long maxThreads, boolean callerWillJoin) {
        throw new RuntimeException("Stub!");
    }

    public static void joinRpcThreadpool() {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void transact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public IHwInterface queryLocalInterface(String descriptor) {
        throw new RuntimeException("Stub!");
    }

    public abstract void onTransact(int code, HwParcel request, HwParcel reply, int flags) throws RemoteException;
}
