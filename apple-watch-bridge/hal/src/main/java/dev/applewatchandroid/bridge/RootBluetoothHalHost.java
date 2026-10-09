package dev.applewatchandroid.bridge;

import android.annotation.SuppressLint;
import static dev.applewatchandroid.bridge.HalTransportSession.StopRequested;
import android.hardware.bluetooth.IBluetoothHci;
import android.hardware.bluetooth.Status;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit root-side owner of the standard VINTF Bluetooth HCI HAL.
 *
 * <p>This class is launched with {@code app_process}; it is never an Android
 * component and is not started at boot. It refuses to run unless the target,
 * root UID, and disabled stock Bluetooth state all match.</p>
 */
public final class RootBluetoothHalHost extends ProtocolHost {
    private static final String HAL_INSTANCE =
            "android.hardware.bluetooth.IBluetoothHci/default";
    private static final String REQUIRED_ACK = "CPH2653";
    private static final long HAL_LOOKUP_TIMEOUT_MS = 10_000;
    RootBluetoothHalHost() {
    }

    public static void main(String[] args) {
        int exitStatus = runForCoordinator(args);
        if (exitStatus != 0) {
            System.exit(exitStatus);
        }
    }

    static int runForCoordinator(String[] args) {
        AtomicBoolean stopRequested = new AtomicBoolean();
        BlockingQueue<char[]> pinInputs =
                new LinkedBlockingQueue<>(1);
        BlockingQueue<Boolean> bondStoreResults =
                new LinkedBlockingQueue<>(1);
        BlockingQueue<Boolean> pairingSessionStoreResults =
                new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> localIdentityRecords =
                new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> localIdsPublicRecords = new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> restoredPairingSessionRecords =
                new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> opticalCodes = new LinkedBlockingQueue<>(1);
        BlockingQueue<OutboundAppMessage> outboundAppMessages =
                new LinkedBlockingQueue<>(32);
        HalTransportSession session = null;
        Thread cleanupHook = null;
        java.io.RandomAccessFile ownershipFile = null;
        java.nio.channels.FileLock ownershipLock = null;
        boolean operationalMode = false;
        int exitStatus = 0;
        try {
            validateInvocation(args);
            NativeFacePackageAccess.installPlatformPreparer(AndroidNativeFacePackageAccess::prepare);
            HalHostArguments invocation = HalHostArguments.parse(args);
            operationalMode = invocation.operationalPairing != null;
            ownershipFile = new java.io.RandomAccessFile("/data/local/tmp/apple-watch-bridge-hal.lock", "rw");
            ownershipLock = ownershipFile.getChannel().tryLock();
            if (ownershipLock == null) throw new HostException("Another bridge root process owns the HAL lease");
            boolean recoveryOnly =
                    isSmpRecoveryInvocation(args);
            HalCommandReader.start(
                    stopRequested,
                    pinInputs,
                    bondStoreResults,
                    pairingSessionStoreResults,
                    localIdentityRecords,
                    localIdsPublicRecords,
                    restoredPairingSessionRecords,
                    opticalCodes,
                    outboundAppMessages);
            log("TARGET OK: manufacturer=" + Build.MANUFACTURER
                    + " model=" + Build.MODEL
                    + " fingerprint=" + Build.FINGERPRINT);
            log("ROOT UID OK: uid=" + Process.myUid() + ".");
            requireStockBluetoothOff();
            log("STOCK BLUETOOTH OFF: raw HAL ownership may begin.");

            IBinder binder = waitForHalBinder();
            if (binder == null) {
                throw new HostException(
                        "Bluetooth HCI HAL did not appear within "
                                + HAL_LOOKUP_TIMEOUT_MS
                                + " ms");
            }
            log("HAL BINDER FOUND: " + HAL_INSTANCE + ".");

            IBluetoothHci hci = IBluetoothHci.Stub.asInterface(binder);
            if (hci == null) {
                throw new HostException("Cannot create IBluetoothHci proxy");
            }
            boolean commitAuthorized =
                    isCommitAuthorized(args);
            log("ROOT HAL START: commitAuthorized=" + commitAuthorized);
            session = new HalTransportSession(
                    new AndroidBluetoothController(hci),
                    stopRequested,
                    pinInputs,
                    bondStoreResults,
                    pairingSessionStoreResults,
                    localIdentityRecords,
                    localIdsPublicRecords,
                    restoredPairingSessionRecords,
                    outboundAppMessages,
                    commitAuthorized,
                    invocation.operationalPairing);
            if (invocation.opticalPairing) {
                byte[] opticalPayload = opticalCodes.poll(10, TimeUnit.SECONDS);
                if (opticalPayload == null) throw new HostException("Optical input was not supplied");
                try { session.configureOpticalPairing(opticalPayload); }
                finally { Arrays.fill(opticalPayload, (byte) 0); }
            }
            HalTransportSession cleanupTarget = session;
            cleanupHook = new Thread(
                    cleanupTarget::cleanup,
                    "apple-watch-hal-shutdown");
            Runtime.getRuntime().addShutdownHook(cleanupHook);

            session.initialize();
            session.runTransportHandshake(recoveryOnly);
            if (recoveryOnly) {
                log("RESULT: PASS — stale Watch SMP transaction received "
                        + "a standard Pairing Failed/Unspecified Reason "
                        + "recovery PDU; no IKE, OOB, DHKey, LTK, bond, "
                        + "or activation material was created.");
            } else if (invocation.operationalPairing != null) {
                log("RESULT: STOPPED — operational session ended; setup replay attempted=false.");
            } else if (commitAuthorized && (!session.setupCompleted())) {
                exitStatus = 2;
                log("RESULT: INCOMPLETE — transport returned before verified Watch setup and reconnect completion.");
            } else if (commitAuthorized) {
                log("RESULT: PASS — pairing IKE, bidirectional Apple private "
                        + "OOB notifies, Bluetooth LE Secure Connections "
                        + "OOB authentication, link encryption, identity "
                        + "key distribution, BT_CL normal handoff, "
                        + "Class D/Class C, IDS full-property snapshot, "
                        + "IsPaired commit, observed activation result, "
                        + "NanoRegistry Normal, PairedSync, and Carousel Clock "
                        + "barriers completed; Bluetooth and NetworkRelay "
                        + "continuity material persisted only as Android-Keystore "
                        + "AES-GCM ciphertext; secret bytes logged=false.");
            } else {
                log("RESULT: PASS — pairing IKE, bidirectional Apple private "
                        + "OOB notifies, Bluetooth LE Secure Connections "
                        + "OOB authentication, link encryption, identity "
                        + "key distribution, BT_CL normal handoff, and "
                        + "ordinary Class D/Class C, IDS and full-property "
                        + "pre-commit barriers completed; Bluetooth and "
                        + "NetworkRelay continuity material persisted only "
                        + "as Android-Keystore AES-GCM ciphertext; "
                        + "secret bytes logged=false.");
            }
        } catch (StopRequested expected) {
            if (operationalMode) {
                log("RESULT: STOPPED — owner ended operational connection; setup replay attempted=false.");
            } else if (session != null
                    && session.readyForCleanStop()) {
                log("RESULT: PASS — READY_TO_COMMIT_IS_PAIRED normal-link "
                        + "session stopped cleanly after local STOP; "
                        + "IsPaired/setup/activation commit attempted=false.");
            } else {
                exitStatus = 2;
                log("RESULT: STOPPED — local STOP requested cleanup before "
                        + "the IDS control/Class-D/Class-C barrier.");
            }
        } catch (Throwable error) {
            exitStatus = 1;
            log("RESULT: FAIL — "
                    + error.getClass().getSimpleName()
                    + ": "
                    + safeMessage(error));
        } finally {
            if (session != null) {
                session.cleanup();
            }
            if (cleanupHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(
                            cleanupHook);
                } catch (IllegalStateException ignored) {
                    // VM shutdown already owns the idempotent cleanup hook.
                }
            }
            byte[] pendingIdentity;
            while ((pendingIdentity =
                    localIdentityRecords.poll()) != null) {
                Arrays.fill(pendingIdentity, (byte) 0);
            }
            while ((pendingIdentity = localIdsPublicRecords.poll()) != null) {
                Arrays.fill(pendingIdentity, (byte) 0);
            }
            while ((pendingIdentity = opticalCodes.poll()) != null) Arrays.fill(pendingIdentity, (byte) 0);
            if (ownershipLock != null) {
                try { ownershipLock.close(); } catch (IOException ignored) { }
            }
            if (ownershipFile != null) {
                try { ownershipFile.close(); } catch (IOException ignored) { }
            }
        }
        return exitStatus;
    }

    private static void validateInvocation(String[] args) throws HostException {
        try {
            HalHostArguments.parse(args);
        } catch (IllegalArgumentException invalid) {
            throw new HostException(invalid.getMessage());
        }
        if (Process.myUid() != 0) {
            throw new HostException(
                    "Root HAL host requires uid=0; observed uid="
                            + Process.myUid());
        }
        if (!"OnePlus".equalsIgnoreCase(Build.MANUFACTURER)
                || !"CPH2653".equalsIgnoreCase(Build.MODEL)) {
            throw new HostException(
                    "This build is fail-closed to OnePlus CPH2653");
        }
    }

    private static boolean isSmpRecoveryInvocation(
            String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--recover-stale-smp".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommitAuthorized(
            String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--commit-is-paired".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void requireStockBluetoothOff()
            throws IOException, InterruptedException, HostException {
        java.lang.Process settings = new ProcessBuilder(
                "/system/bin/settings",
                "get",
                "global",
                "bluetooth_on")
                .redirectErrorStream(true)
                .start();
        String value;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        settings.getInputStream(),
                        StandardCharsets.UTF_8))) {
            value = reader.readLine();
        }
        int status = settings.waitFor();
        if (status != 0 || !"0".equals(value == null ? "" : value.trim())) {
            throw new HostException(
                    "Refusing HAL ownership: settings global bluetooth_on="
                            + value
                            + " exit="
                            + status);
        }
    }

    @SuppressLint({
            "BlockedPrivateApi",
            "PrivateApi"
    }) // Intentional root app_process bridge; guarded at runtime.
    private static IBinder waitForHalBinder() throws HostException {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "apple-watch-hal-lookup");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<IBinder> future = executor.submit(() -> {
                Class<?> serviceManager = Class.forName("android.os.ServiceManager");
                Method waitMethod = serviceManager.getDeclaredMethod(
                        "waitForDeclaredService",
                        String.class);
                waitMethod.setAccessible(true);
                return (IBinder) waitMethod.invoke(null, HAL_INSTANCE);
            });
            return future.get(HAL_LOOKUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new HostException("Timed out waiting for Bluetooth HCI HAL", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new HostException("Interrupted while waiting for Bluetooth HCI HAL", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof InvocationTargetException invocation
                    && invocation.getTargetException() != null) {
                cause = invocation.getTargetException();
            }
            throw new HostException(
                    "Cannot access ServiceManager.waitForDeclaredService",
                    cause);
        } finally {
            executor.shutdownNow();
        }
    }


}
