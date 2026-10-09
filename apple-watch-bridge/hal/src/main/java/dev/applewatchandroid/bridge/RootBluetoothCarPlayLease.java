package dev.applewatchandroid.bridge;

import android.os.Build;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Root wrapper that temporarily transfers the controller from Android's
 * Bluetooth manager to the Watch helper, then restores the manager before the
 * process exits. CatPlay must already have accepted its signature-protected
 * Wi-Fi/media handoff; this class never edits Bluetooth bonds or CatPlay data.
 */
public final class RootBluetoothCarPlayLease {
    private static final String REQUIRED_ACK =
            "CPH2653";
    private static final long COMMAND_TIMEOUT_SECONDS = 30L;

    private RootBluetoothCarPlayLease() {
    }

    public static void main(String[] args) {
        int exitStatus = 1;
        boolean restoreRequired = false;
        try {
            validateInvocation(args);
            String initial = readBluetoothSetting();
            if (!"1".equals(initial)) {
                throw new LeaseException(
                        "Cooperative lease requires stock Bluetooth ON");
            }
            restoreRequired = true;
            requireCommandSuccess(
                    "/system/bin/cmd",
                    "bluetooth_manager",
                    "disable");
            requireCommandSuccess(
                    "/system/bin/cmd",
                    "bluetooth_manager",
                    "wait-for-state:STATE_OFF");
            if (!"0".equals(readBluetoothSetting())) {
                throw new LeaseException(
                        "Bluetooth setting did not reach OFF");
            }
            log("CARPLAY STOCK BLUETOOTH LEASE ACQUIRED: "
                    + "manager=OFF; CatPlay Wi-Fi/media retained=true; "
                    + "bond/config mutation=false.");
            exitStatus =
                    RootBluetoothHalHost
                            .runForCoordinator(args);
        } catch (Throwable error) {
            log("CARPLAY STOCK BLUETOOTH LEASE FAIL: "
                    + error.getClass().getSimpleName()
                    + ": "
                    + safeMessage(error));
            exitStatus = 1;
        } finally {
            if (restoreRequired) {
                try {
                    requireCommandSuccess(
                            "/system/bin/cmd",
                            "bluetooth_manager",
                            "enable");
                    requireCommandSuccess(
                            "/system/bin/cmd",
                            "bluetooth_manager",
                            "wait-for-state:STATE_ON");
                    if (!"1".equals(readBluetoothSetting())) {
                        throw new LeaseException(
                                "Bluetooth setting did not return ON");
                    }
                    log("CARPLAY STOCK BLUETOOTH RESTORE PASS: "
                            + "manager=ON; CatPlay resume may begin; "
                            + "bond/config mutation=false.");
                } catch (Throwable error) {
                    log("CARPLAY STOCK BLUETOOTH RESTORE FAIL: "
                            + error.getClass().getSimpleName()
                            + ": "
                            + safeMessage(error));
                    exitStatus = 3;
                }
            }
        }
        if (exitStatus != 0) {
            System.exit(exitStatus);
        }
    }

    private static void validateInvocation(
            String[] args) throws LeaseException {
        try {
            HalHostArguments.parse(args);
        } catch (IllegalArgumentException invalid) {
            throw new LeaseException(invalid.getMessage());
        }
        if (Process.myUid() != 0) {
            throw new LeaseException(
                    "CarPlay lease requires uid=0");
        }
        if (!"OnePlus".equalsIgnoreCase(
                Build.MANUFACTURER)
                || !"CPH2653".equalsIgnoreCase(
                Build.MODEL)) {
            throw new LeaseException(
                    "CarPlay lease is restricted to OnePlus CPH2653");
        }
    }

    private static String readBluetoothSetting()
            throws IOException, InterruptedException, LeaseException {
        CommandResult result = runCommand(
                "/system/bin/settings",
                "get",
                "global",
                "bluetooth_on");
        if (result.exitStatus != 0) {
            throw new LeaseException(
                    "Cannot read the Bluetooth setting");
        }
        return result.output.trim();
    }

    private static void requireCommandSuccess(
            String... command)
            throws IOException, InterruptedException, LeaseException {
        CommandResult result = runCommand(command);
        if (result.exitStatus != 0) {
            throw new LeaseException(
                    "Bluetooth manager command failed with exit="
                            + result.exitStatus);
        }
    }

    private static CommandResult runCommand(
            String... command)
            throws IOException, InterruptedException, LeaseException {
        java.lang.Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();
        boolean finished = process.waitFor(
                COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new LeaseException(
                    "Bluetooth manager command timed out");
        }
        byte[] output = readBounded(
                process.getInputStream());
        try {
            return new CommandResult(
                    process.exitValue(),
                    new String(
                            output,
                            StandardCharsets.UTF_8));
        } finally {
            Arrays.fill(output, (byte) 0);
        }
    }

    private static byte[] readBounded(
            InputStream input) throws IOException, LeaseException {
        try (input;
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream()) {
            byte[] buffer = new byte[512];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 8_192) {
                    throw new LeaseException(
                            "Bluetooth manager output exceeded its bound");
                }
                output.write(buffer, 0, count);
            }
            Arrays.fill(buffer, (byte) 0);
            return output.toByteArray();
        }
    }

    private static void log(String message) {
        System.out.println("[CarPlayLease] " + message);
        System.out.flush();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "(no message)"
                : message;
    }

    private static final class CommandResult {
        final int exitStatus;
        final String output;

        CommandResult(
                int exitStatus,
                String output) {
            this.exitStatus = exitStatus;
            this.output = output;
        }
    }

    private static final class LeaseException extends Exception {
        LeaseException(String message) {
            super(message);
        }
    }
}
