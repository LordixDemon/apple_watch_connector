package dev.applewatchandroid.bridge;

import static dev.applewatchandroid.bridge.HalTransportSession.StopRequested;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unprivileged replacement for {@link RootBluetoothHalHost}: the same protocol
 * session and stdin/stdout contract, driven over an external USB Bluetooth
 * adapter inside the Bridge's isolated {@code :hci} process.
 */
final class UsbHciProtocolHost extends ProtocolHost {
    private UsbHciProtocolHost() { }

    static int run(UsbManager usb, String deviceName, File lockDirectory, String[] args) {
        AtomicBoolean stopRequested = new AtomicBoolean();
        BlockingQueue<char[]> pinInputs = new LinkedBlockingQueue<>(1);
        BlockingQueue<Boolean> bondStoreResults = new LinkedBlockingQueue<>(1);
        BlockingQueue<Boolean> pairingSessionStoreResults = new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> localIdentityRecords = new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> localIdsPublicRecords = new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> restoredPairingSessionRecords = new LinkedBlockingQueue<>(1);
        BlockingQueue<byte[]> opticalCodes = new LinkedBlockingQueue<>(1);
        BlockingQueue<OutboundAppMessage> outboundAppMessages = new LinkedBlockingQueue<>(32);
        HalTransportSession session = null;
        UsbBluetoothController controller = null;
        RandomAccessFile ownershipFile = null;
        FileLock ownershipLock = null;
        boolean operationalMode = false;
        int exitStatus = 0;
        try {
            HalHostArguments invocation;
            try {
                invocation = HalHostArguments.parse(args);
            } catch (IllegalArgumentException invalid) {
                throw new HostException(invalid.getMessage());
            }
            operationalMode = invocation.operationalPairing != null;
            ownershipFile = new RandomAccessFile(new File(lockDirectory, "usb-hci.lock"), "rw");
            ownershipLock = ownershipFile.getChannel().tryLock();
            if (ownershipLock == null) throw new HostException("Another bridge session owns the USB adapter");
            RootWifiNetworkReader.installPlatformReader(() -> {
                throw new IOException("Wi-Fi credential sync needs root; join Wi-Fi on the Watch instead");
            });
            HalCommandReader.start(stopRequested, pinInputs, bondStoreResults, pairingSessionStoreResults,
                    localIdentityRecords, localIdsPublicRecords, restoredPairingSessionRecords,
                    opticalCodes, outboundAppMessages);
            log("TARGET: manufacturer=" + Build.MANUFACTURER + " model=" + Build.MODEL
                    + " sdk=" + Build.VERSION.SDK_INT + "; no-root USB HCI mode.");
            log("APP UID: uid=" + Process.myUid() + "; root not used.");

            UsbDevice device = select(usb, deviceName);
            log("USB BLUETOOTH ADAPTER: " + UsbBluetoothController.describe(device) + ".");
            controller = new UsbBluetoothController(usb, device);
            session = new HalTransportSession(controller, stopRequested, pinInputs, bondStoreResults,
                    pairingSessionStoreResults, localIdentityRecords, localIdsPublicRecords,
                    restoredPairingSessionRecords, outboundAppMessages, invocation.commit,
                    invocation.operationalPairing);
            if (invocation.opticalPairing) {
                byte[] opticalPayload = opticalCodes.poll(10, TimeUnit.SECONDS);
                if (opticalPayload == null) throw new HostException("Optical input was not supplied");
                try { session.configureOpticalPairing(opticalPayload); }
                finally { Arrays.fill(opticalPayload, (byte) 0); }
            }
            session.initialize();
            session.runTransportHandshake(invocation.recovery);
            if (invocation.recovery) {
                log("RESULT: PASS — stale Watch SMP transaction received a standard Pairing Failed/"
                        + "Unspecified Reason recovery PDU; no pairing material was created.");
            } else if (operationalMode) {
                log("RESULT: STOPPED — operational session ended; setup replay attempted=false.");
            } else if (invocation.commit && !session.setupCompleted()) {
                exitStatus = 2;
                log("RESULT: INCOMPLETE — transport returned before verified Watch setup and reconnect completion.");
            } else {
                log("RESULT: PASS — pairing, LE Secure Connections OOB authentication, link encryption, "
                        + "BT_CL handoff, IDS and setup barriers completed over USB HCI; "
                        + "continuity material persisted only as Android-Keystore AES-GCM ciphertext; "
                        + "secret bytes logged=false.");
            }
        } catch (StopRequested expected) {
            if (operationalMode) {
                log("RESULT: STOPPED — owner ended operational connection; setup replay attempted=false.");
            } else if (session != null && session.readyForCleanStop()) {
                log("RESULT: PASS — READY_TO_COMMIT_IS_PAIRED normal-link session stopped cleanly after "
                        + "local STOP; IsPaired/setup/activation commit attempted=false.");
            } else {
                exitStatus = 2;
                log("RESULT: STOPPED — local STOP requested cleanup before the IDS control/Class-D/Class-C barrier.");
            }
        } catch (Throwable error) {
            exitStatus = 1;
            log("RESULT: FAIL — " + error.getClass().getSimpleName() + ": " + safeMessage(error));
        } finally {
            if (session != null) session.cleanup();
            if (controller != null) controller.close();
            byte[] pending;
            while ((pending = localIdentityRecords.poll()) != null) Arrays.fill(pending, (byte) 0);
            while ((pending = localIdsPublicRecords.poll()) != null) Arrays.fill(pending, (byte) 0);
            while ((pending = restoredPairingSessionRecords.poll()) != null) Arrays.fill(pending, (byte) 0);
            while ((pending = opticalCodes.poll()) != null) Arrays.fill(pending, (byte) 0);
            char[] pin;
            while ((pin = pinInputs.poll()) != null) Arrays.fill(pin, '\0');
            if (ownershipLock != null) {
                try { ownershipLock.close(); } catch (IOException ignored) { }
            }
            if (ownershipFile != null) {
                try { ownershipFile.close(); } catch (IOException ignored) { }
            }
        }
        return exitStatus;
    }

    private static UsbDevice select(UsbManager usb, String deviceName) throws HostException {
        java.util.List<UsbDevice> controllers = UsbBluetoothController.controllers(usb);
        if (controllers.isEmpty()) {
            throw new HostException("No USB Bluetooth adapter is attached. Connect a Bluetooth 5.x USB "
                    + "dongle through a USB-OTG adapter.");
        }
        for (UsbDevice device : controllers) {
            if (deviceName != null && deviceName.equals(device.getDeviceName())) {
                if (!usb.hasPermission(device)) throw new HostException("USB permission was not granted");
                return device;
            }
        }
        for (UsbDevice device : controllers) {
            if (usb.hasPermission(device)) return device;
        }
        throw new HostException("USB permission for the Bluetooth adapter was not granted");
    }
}
