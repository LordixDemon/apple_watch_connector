package dev.applewatchandroid.bridge;

import android.annotation.SuppressLint;
import android.os.Binder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Keeps the generated callback Binder compatible with the VINTF-stable
 * Bluetooth HAL while allowing this lab APK to compile against the public SDK.
 */
public final class VintfStability {
    private VintfStability() {
    }

    @SuppressLint({
            "BlockedPrivateApi",
            "PrivateApi"
    }) // Required by generated VINTF-stable callback Binder.
    public static void mark(Binder binder) {
        try {
            Method method = Binder.class.getDeclaredMethod("markVintfStability");
            method.setAccessible(true);
            method.invoke(binder);
        } catch (NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException error) {
            throw new IllegalStateException(
                    "Cannot mark Bluetooth callback Binder as VINTF-stable",
                    error);
        }
    }
}
