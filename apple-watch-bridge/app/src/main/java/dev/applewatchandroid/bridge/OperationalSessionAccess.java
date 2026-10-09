package dev.applewatchandroid.bridge;

import android.content.Context;
import android.os.Handler;
import java.io.BufferedWriter;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Live session capabilities. Feature controllers cannot start, replace or own HAL. */
interface OperationalSessionAccess {
    Context context();
    Handler handler();
    Executor commands();
    boolean stopping();
    boolean connected();
    UUID epoch();
    String pairing();
    BufferedWriter input();
    BridgeIdentityStore identities();
    void log(String line);
}
