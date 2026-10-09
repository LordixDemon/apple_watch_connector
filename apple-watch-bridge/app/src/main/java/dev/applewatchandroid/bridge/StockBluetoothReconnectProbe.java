package dev.applewatchandroid.bridge;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;

import java.util.ArrayDeque;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Public-API probe for the stock Android BLE path.
 *
 * <p>The probe never initiates pairing and never logs addresses, key bytes,
 * or characteristic values. Modes are explicit: the default ACL-only mode
 * performs no service discovery, characteristic read, descriptor write, or
 * application write.</p>
 */
@SuppressLint("MissingPermission")
final class StockBluetoothReconnectProbe
        implements AutoCloseable {
    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 30_000L;
    private static final long CONNECTED_HOLD_MS = 45_000L;
    private static final long ACL_ONLY_HOLD_MS = 5_000L;
    private static final int MAX_READ_ATTEMPTS = 16;
    private static final UUID CCCD_UUID =
            UUID.fromString(
                    "00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID CONTINUITY_SERVICE_UUID =
            UUID.fromString(
                    "d0611e78-bbb4-4591-a5f8-487910ae4366");
    private static final UUID CONTINUITY_CHARACTERISTIC_UUID =
            UUID.fromString(
                    "8667556c-9a37-4c91-84ed-54ee27d90049");
    private static final UUID NEARBY_SERVICE_UUID =
            UUID.fromString(
                    "9fa480e0-4967-4542-9390-d343dc5d04ae");
    private static final UUID NEARBY_CHARACTERISTIC_UUID =
            UUID.fromString(
                    "af0badb1-5b99-43cd-917a-a77bc549e3cc");

    private final Context context;
    private final Handler handler;
    private final Consumer<String> logger;
    private final Runnable completion;
    private final String expectedIdentityAddress;
    private Mode mode;
    private final ArrayDeque<BluetoothGattCharacteristic>
            readableCharacteristics =
            new ArrayDeque<>();
    private final ArrayDeque<NotificationSubscription>
            notificationSubscriptions =
            new ArrayDeque<>();

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice target;
    private NotificationSubscription activeSubscription;
    private boolean scanRunning;
    private boolean connectStarted;
    private boolean connected;
    private boolean completed;
    private boolean readsStarted;
    private int readsAttempted;
    private int subscriptionsAttempted;
    private int subscriptionsEnabled;
    private int notificationEvents;
    private int continuityNotificationEvents;
    private int nearbyNotificationEvents;

    enum Mode {
        ACL_ONLY,
        GATT_CATALOG_AND_READS,
        NOTIFICATION_SUBSCRIPTIONS
    }

    private final Runnable scanTimeout = () -> {
        if (completed || connectStarted) {
            return;
        }
        log("STOCK SCAN: bonded Watch advertisement was not "
                + "observed in 12 s; trying direct identity-address "
                + "GATT connect through Android.");
        stopScan();
        connect(target, "direct bonded-device path");
    };

    private final Runnable connectTimeout = () -> {
        if (completed || connected) {
            return;
        }
        log("STOCK GATT RESULT: FAIL — no connection callback "
                + "within 30 s.");
        finish();
    };

    private final Runnable connectedHoldTimeout = () -> {
        if (completed) {
            return;
        }
        if (mode == Mode.ACL_ONLY) {
            log("STOCK ACL-ONLY PROBE COMPLETE: encrypted-link "
                    + "observation window ended; service discovery, "
                    + "characteristic reads, descriptor writes, and "
                    + "application payloads were all disabled.");
            finish();
            return;
        }
        log("STOCK GATT NOTIFY SUMMARY: enabled="
                + subscriptionsEnabled
                + "/"
                + subscriptionsAttempted
                + ", events="
                + notificationEvents
                + " (Continuity="
                + continuityNotificationEvents
                + ", Nearby="
                + nearbyNotificationEvents
                + "); payloads logged=false.");
        log("STOCK GATT PROBE COMPLETE: connected link was held "
                + "for external stock-stack security inspection.");
        finish();
    };

    private final ScanCallback scanCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(
                        int callbackType,
                        ScanResult result) {
                    if (completed
                            || connectStarted
                            || !matchesTarget(
                                    result.getDevice())) {
                        return;
                    }
                    boolean identityAddress =
                            result.getDevice()
                                    .getAddress()
                                    .equalsIgnoreCase(
                                            target.getAddress());
                    log("STOCK SCAN PASS: Android resolved a "
                            + "bonded Watch advertisement; "
                            + "identity-address="
                            + identityAddress
                            + ", connectable="
                            + result.isConnectable()
                            + ", RSSI="
                            + result.getRssi()
                            + ".");
                    stopScan();
                    connect(
                            result.getDevice(),
                            "resolved scan result");
                }

                @Override
                public void onScanFailed(
                        int errorCode) {
                    if (completed || connectStarted) {
                        return;
                    }
                    log("STOCK SCAN: Android scan failed with "
                            + "code="
                            + errorCode
                            + "; trying direct bonded-device "
                            + "GATT connect.");
                    stopScan();
                    connect(
                            target,
                            "direct fallback after scan error");
                }
            };

    private final BluetoothGattCallback gattCallback =
            new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(
                        BluetoothGatt callbackGatt,
                        int status,
                        int newState) {
                    if (completed) {
                        return;
                    }
                    log("STOCK GATT STATE: status="
                            + status
                            + ", state="
                            + stateName(newState)
                            + ", bond="
                            + bondName(
                                    callbackGatt
                                            .getDevice()
                                            .getBondState())
                            + ".");
                    if (status == BluetoothGatt.GATT_SUCCESS
                            && newState
                            == BluetoothProfile
                                    .STATE_CONNECTED) {
                        connected = true;
                        handler.removeCallbacks(
                                connectTimeout);
                        log("STOCK GATT ACL PASS: standard Android "
                                + "stack established an LE link to "
                                + "the imported bonded Watch.");
                        if (mode == Mode.ACL_ONLY) {
                            log("STOCK ACL-ONLY BOUNDARY: no GATT "
                                    + "discovery/read/CCCD operation "
                                    + "will be issued.");
                            handler.postDelayed(
                                    connectedHoldTimeout,
                                    ACL_ONLY_HOLD_MS);
                            return;
                        }
                        boolean requested =
                                callbackGatt
                                        .discoverServices();
                        log("STOCK GATT: service discovery "
                                + "requested="
                                + requested
                                + ".");
                        handler.postDelayed(
                                connectedHoldTimeout,
                                CONNECTED_HOLD_MS);
                        return;
                    }
                    if (newState
                            == BluetoothProfile
                                    .STATE_DISCONNECTED) {
                        if (connected) {
                            log("STOCK GATT: peer/link disconnected "
                                    + "after a successful ACL.");
                        } else {
                            log("STOCK GATT RESULT: FAIL — Android "
                                    + "did not establish the LE ACL; "
                                    + "status="
                                    + status
                                    + ".");
                        }
                        finish();
                    }
                }

                @Override
                public void onServicesDiscovered(
                        BluetoothGatt callbackGatt,
                        int status) {
                    if (completed) {
                        return;
                    }
                    int serviceCount =
                            callbackGatt
                                    .getServices()
                                    .size();
                    log("STOCK GATT SERVICES: status="
                            + status
                            + ", count="
                            + serviceCount
                            + "; UUIDs and values logged=false.");
                    if (status
                            != BluetoothGatt.GATT_SUCCESS) {
                        return;
                    }
                    logGattCatalog(
                            callbackGatt
                                    .getServices());
                    queueReadableCharacteristics(
                            callbackGatt
                                    .getServices());
                    if (mode
                            == Mode.GATT_CATALOG_AND_READS) {
                        startReadProbe(callbackGatt);
                        return;
                    }
                    queueAppleNotificationSubscriptions(
                            callbackGatt
                                    .getServices());
                    subscribeNext(
                            callbackGatt);
                }

                @Override
                public void onDescriptorWrite(
                        BluetoothGatt callbackGatt,
                        BluetoothGattDescriptor descriptor,
                        int status) {
                    handleDescriptorWrite(
                            callbackGatt,
                            descriptor,
                            status);
                }

                @Override
                public void onCharacteristicChanged(
                        BluetoothGatt callbackGatt,
                        BluetoothGattCharacteristic characteristic,
                        byte[] value) {
                    handleCharacteristicChanged(
                            characteristic,
                            value == null
                                    ? -1
                                    : value.length);
                }

                @Override
                public void onCharacteristicRead(
                        BluetoothGatt callbackGatt,
                        BluetoothGattCharacteristic characteristic,
                        byte[] value,
                        int status) {
                    handleCharacteristicRead(
                            callbackGatt,
                            status);
                }

                @Override
                @SuppressWarnings("deprecation")
                public void onCharacteristicRead(
                        BluetoothGatt callbackGatt,
                        BluetoothGattCharacteristic characteristic,
                        int status) {
                    // Android 13+ invokes the value-bearing overload. Keep
                    // this callback for vendor stacks that still use the
                    // legacy signature.
                    handleCharacteristicRead(
                            callbackGatt,
                            status);
                }
            };

    StockBluetoothReconnectProbe(
            Context context,
            Handler handler,
            Consumer<String> logger,
            Runnable completion,
            String expectedIdentityAddress,
            Mode mode) {
        this.context =
                context.getApplicationContext();
        this.handler = handler;
        this.logger = logger;
        this.completion = completion;
        if (expectedIdentityAddress == null
                || !expectedIdentityAddress.matches(
                "(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
                || mode == null) {
            throw new IllegalArgumentException(
                    "Exact bonded identity and probe mode are required");
        }
        this.expectedIdentityAddress =
                expectedIdentityAddress;
        this.mode = mode;
    }

    void start() {
        if (context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(
                        Manifest.permission
                                .BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            log("STOCK GATT RESULT: FAIL — BLUETOOTH_SCAN/"
                    + "BLUETOOTH_CONNECT permission is missing.");
            finish();
            return;
        }
        BluetoothManager manager =
                context.getSystemService(
                        BluetoothManager.class);
        BluetoothAdapter adapter =
                manager == null
                        ? null
                        : manager.getAdapter();
        if (adapter == null
                || adapter.getState()
                != BluetoothAdapter.STATE_ON) {
            log("STOCK GATT RESULT: FAIL — stock Bluetooth "
                    + "adapter is not ON.");
            finish();
            return;
        }
        target =
                findBondedWatch(
                        adapter.getBondedDevices());
        if (target == null) {
            log("STOCK GATT RESULT: FAIL — bonded LE Apple Watch "
                    + "is absent from BluetoothAdapter.");
            finish();
            return;
        }
        log("STOCK PROBE START: exact identity from the encrypted "
                + "bond record matched one bonded LE device; mode="
                + mode
                + "; pairing will not be started.");
        scanner =
                adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("STOCK SCAN: scanner unavailable; trying direct "
                    + "bonded-device GATT connect.");
            connect(
                    target,
                    "direct path without scanner");
            return;
        }
        try {
            ScanSettings settings =
                    new ScanSettings.Builder()
                            .setScanMode(
                                    ScanSettings
                                            .SCAN_MODE_LOW_LATENCY)
                            .build();
            scanner.startScan(
                    null,
                    settings,
                    scanCallback);
            scanRunning = true;
            handler.postDelayed(
                    scanTimeout,
                    SCAN_TIMEOUT_MS);
            log("STOCK SCAN: low-latency scan started; only the "
                    + "already-bonded Watch can trigger connect.");
        } catch (RuntimeException error) {
            log("STOCK SCAN: start failed ("
                    + error.getClass().getSimpleName()
                    + "); trying direct GATT connect.");
            connect(
                    target,
                    "direct fallback after start failure");
        }
    }

    private BluetoothDevice findBondedWatch(
            Set<BluetoothDevice> bondedDevices) {
        for (BluetoothDevice device : bondedDevices) {
            int type = device.getType();
            if (expectedIdentityAddress.equalsIgnoreCase(
                    device.getAddress())
                    && (type
                            == BluetoothDevice
                                    .DEVICE_TYPE_LE
                            || type
                            == BluetoothDevice
                                    .DEVICE_TYPE_DUAL)) {
                return device;
            }
        }
        return null;
    }

    private boolean matchesTarget(
            BluetoothDevice candidate) {
        if (target == null || candidate == null) {
            return false;
        }
        if (candidate.getAddress()
                .equalsIgnoreCase(
                        expectedIdentityAddress)) {
            return candidate.getBondState()
                    == BluetoothDevice.BOND_BONDED;
        }
        return false;
    }

    private void connect(
            BluetoothDevice device,
            String path) {
        if (completed || connectStarted) {
            return;
        }
        connectStarted = true;
        stopScan();
        log("STOCK GATT CONNECT: "
                + path
                + "; autoConnect=false, transport=LE, PHY=1M.");
        try {
            gatt =
                    device.connectGatt(
                            context,
                            false,
                            gattCallback,
                            BluetoothDevice
                                    .TRANSPORT_LE,
                            BluetoothDevice
                                    .PHY_LE_1M_MASK,
                            handler);
            if (gatt == null) {
                log("STOCK GATT RESULT: FAIL — connectGatt "
                        + "returned null.");
                finish();
                return;
            }
            handler.postDelayed(
                    connectTimeout,
                    CONNECT_TIMEOUT_MS);
        } catch (RuntimeException error) {
            log("STOCK GATT RESULT: FAIL — connectGatt threw "
                    + error.getClass().getSimpleName()
                    + ".");
            finish();
        }
    }

    private void queueReadableCharacteristics(
            java.util.List<BluetoothGattService> services) {
        readableCharacteristics.clear();
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic characteristic
                    : service.getCharacteristics()) {
                if ((characteristic.getProperties()
                        & BluetoothGattCharacteristic
                                .PROPERTY_READ) != 0) {
                    readableCharacteristics.add(
                            characteristic);
                    if (readableCharacteristics.size()
                            >= MAX_READ_ATTEMPTS) {
                        return;
                    }
                }
            }
        }
    }

    private void queueAppleNotificationSubscriptions(
            java.util.List<BluetoothGattService> services) {
        notificationSubscriptions.clear();
        queueAppleNotificationSubscription(
                services,
                CONTINUITY_SERVICE_UUID,
                CONTINUITY_CHARACTERISTIC_UUID,
                "Continuity");
        queueAppleNotificationSubscription(
                services,
                NEARBY_SERVICE_UUID,
                NEARBY_CHARACTERISTIC_UUID,
                "Nearby");
        log("STOCK GATT NOTIFY CANDIDATES: "
                + notificationSubscriptions.size()
                + "/2 Apple characteristics found; application "
                + "payload writes=false.");
    }

    private void queueAppleNotificationSubscription(
            java.util.List<BluetoothGattService> services,
            UUID serviceUuid,
            UUID characteristicUuid,
            String label) {
        for (BluetoothGattService service : services) {
            if (!serviceUuid.equals(service.getUuid())) {
                continue;
            }
            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(
                            characteristicUuid);
            if (characteristic == null) {
                return;
            }
            notificationSubscriptions.add(
                    new NotificationSubscription(
                            label,
                            characteristic));
            return;
        }
    }

    private void subscribeNext(
            BluetoothGatt callbackGatt) {
        if (completed || activeSubscription != null) {
            return;
        }
        NotificationSubscription next =
                notificationSubscriptions.poll();
        if (next == null) {
            log("STOCK GATT NOTIFY SETUP COMPLETE: enabled="
                    + subscriptionsEnabled
                    + "/"
                    + subscriptionsAttempted
                    + "; payloads logged=false.");
            startReadProbe(callbackGatt);
            return;
        }
        subscriptionsAttempted++;
        BluetoothGattCharacteristic characteristic =
                next.characteristic;
        boolean supportsNotify =
                (characteristic.getProperties()
                        & BluetoothGattCharacteristic
                                .PROPERTY_NOTIFY) != 0;
        BluetoothGattDescriptor cccd =
                characteristic.getDescriptor(
                        CCCD_UUID);
        boolean localRegistration = false;
        if (supportsNotify) {
            localRegistration =
                    callbackGatt
                            .setCharacteristicNotification(
                                    characteristic,
                                    true);
        }
        log("STOCK GATT NOTIFY "
                + next.label
                + " #"
                + subscriptionsAttempted
                + ": notify-property="
                + supportsNotify
                + ", local-registration="
                + localRegistration
                + ", cccd-present="
                + (cccd != null)
                + "; application payload write=false.");
        if (!supportsNotify
                || !localRegistration
                || cccd == null) {
            handler.post(
                    () -> subscribeNext(
                            callbackGatt));
            return;
        }
        activeSubscription = next;
        int startStatus;
        try {
            startStatus =
                    callbackGatt.writeDescriptor(
                            cccd,
                            BluetoothGattDescriptor
                                    .ENABLE_NOTIFICATION_VALUE);
        } catch (RuntimeException error) {
            activeSubscription = null;
            log("STOCK GATT NOTIFY "
                    + next.label
                    + ": CCCD request raised "
                    + error.getClass().getSimpleName()
                    + "; payloads logged=false.");
            handler.post(
                    () -> subscribeNext(
                            callbackGatt));
            return;
        }
        if (startStatus
                != BluetoothStatusCodes.SUCCESS) {
            activeSubscription = null;
            log("STOCK GATT NOTIFY "
                    + next.label
                    + ": CCCD request start-status="
                    + startStatus
                    + "; payloads logged=false.");
            handler.post(
                    () -> subscribeNext(
                            callbackGatt));
        }
    }

    private void handleDescriptorWrite(
            BluetoothGatt callbackGatt,
            BluetoothGattDescriptor descriptor,
            int status) {
        if (completed || activeSubscription == null) {
            return;
        }
        NotificationSubscription finished =
                activeSubscription;
        activeSubscription = null;
        boolean expectedDescriptor =
                CCCD_UUID.equals(
                        descriptor.getUuid());
        if (expectedDescriptor
                && status
                == BluetoothGatt.GATT_SUCCESS) {
            subscriptionsEnabled++;
        }
        log("STOCK GATT NOTIFY "
                + finished.label
                + " RESULT: status="
                + status
                + ", expected-cccd="
                + expectedDescriptor
                + "; payloads logged=false.");
        handler.post(
                () -> subscribeNext(
                        callbackGatt));
    }

    private void startReadProbe(
            BluetoothGatt callbackGatt) {
        if (readsStarted) {
            return;
        }
        readsStarted = true;
        readNextCharacteristic(
                callbackGatt);
    }

    private void handleCharacteristicChanged(
            BluetoothGattCharacteristic characteristic,
            int valueLength) {
        if (completed) {
            return;
        }
        String label =
                notificationLabel(
                        characteristic);
        if (label == null) {
            return;
        }
        notificationEvents++;
        if ("Continuity".equals(label)) {
            continuityNotificationEvents++;
        } else {
            nearbyNotificationEvents++;
        }
        log("STOCK GATT NOTIFICATION #"
                + notificationEvents
                + ": source="
                + label
                + ", length="
                + valueLength
                + "; payload logged=false.");
    }

    private String notificationLabel(
            BluetoothGattCharacteristic characteristic) {
        UUID characteristicUuid =
                characteristic.getUuid();
        BluetoothGattService service =
                characteristic.getService();
        UUID serviceUuid =
                service == null
                        ? null
                        : service.getUuid();
        if (CONTINUITY_SERVICE_UUID.equals(serviceUuid)
                && CONTINUITY_CHARACTERISTIC_UUID.equals(
                        characteristicUuid)) {
            return "Continuity";
        }
        if (NEARBY_SERVICE_UUID.equals(serviceUuid)
                && NEARBY_CHARACTERISTIC_UUID.equals(
                        characteristicUuid)) {
            return "Nearby";
        }
        return null;
    }

    private void logGattCatalog(
            java.util.List<BluetoothGattService> services) {
        int serviceIndex = 0;
        int characteristicCount = 0;
        for (BluetoothGattService service : services) {
            serviceIndex++;
            log("STOCK GATT CATALOG S"
                    + serviceIndex
                    + ": uuid="
                    + service.getUuid()
                    + ", type="
                    + service.getType()
                    + ", characteristics="
                    + service.getCharacteristics()
                            .size()
                    + ".");
            int characteristicIndex = 0;
            for (BluetoothGattCharacteristic characteristic
                    : service.getCharacteristics()) {
                characteristicIndex++;
                characteristicCount++;
                log("STOCK GATT CATALOG S"
                        + serviceIndex
                        + "C"
                        + characteristicIndex
                        + ": uuid="
                        + characteristic.getUuid()
                        + ", properties=0x"
                        + String.format(
                                java.util.Locale.US,
                                "%02X",
                                characteristic
                                        .getProperties())
                        + ", permissions=0x"
                        + String.format(
                                java.util.Locale.US,
                                "%02X",
                                characteristic
                                        .getPermissions())
                        + ", descriptors="
                        + characteristic
                                .getDescriptors()
                                .size()
                        + ".");
            }
        }
        log("STOCK GATT CATALOG COMPLETE: services="
                + serviceIndex
                + ", characteristics="
                + characteristicCount
                + "; values logged=false.");
    }

    @SuppressWarnings("deprecation")
    private void readNextCharacteristic(
            BluetoothGatt callbackGatt) {
        if (completed
                || readsAttempted
                >= MAX_READ_ATTEMPTS) {
            return;
        }
        BluetoothGattCharacteristic next =
                readableCharacteristics.poll();
        if (next == null) {
            log("STOCK GATT READ PROBE: completed "
                    + readsAttempted
                    + " read-only attempts; UUIDs/values "
                    + "logged=false.");
            return;
        }
        readsAttempted++;
        boolean started =
                callbackGatt.readCharacteristic(next);
        log("STOCK GATT READ #"
                + readsAttempted
                + ": requested="
                + started
                + "; UUID/value logged=false.");
        if (!started) {
            handler.post(
                    () -> readNextCharacteristic(
                            callbackGatt));
        }
    }

    private void handleCharacteristicRead(
            BluetoothGatt callbackGatt,
            int status) {
        if (completed) {
            return;
        }
        log("STOCK GATT READ RESULT #"
                + readsAttempted
                + ": status="
                + status
                + "; value logged=false.");
        handler.post(
                () -> readNextCharacteristic(
                        callbackGatt));
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (!scanRunning || scanner == null) {
            return;
        }
        try {
            scanner.stopScan(scanCallback);
        } catch (RuntimeException error) {
            log("STOCK SCAN: stop raised "
                    + error.getClass().getSimpleName()
                    + ".");
        } finally {
            scanRunning = false;
        }
    }

    private void finish() {
        if (completed) {
            return;
        }
        completed = true;
        handler.removeCallbacks(scanTimeout);
        handler.removeCallbacks(connectTimeout);
        handler.removeCallbacks(
                connectedHoldTimeout);
        stopScan();
        BluetoothGatt currentGatt = gatt;
        gatt = null;
        if (currentGatt != null) {
            try {
                if (connected) {
                    currentGatt.disconnect();
                }
            } finally {
                currentGatt.close();
            }
        }
        completion.run();
    }

    @Override
    public void close() {
        finish();
    }

    private void log(String message) {
        logger.accept(message);
    }

    private static String stateName(
            int state) {
        return switch (state) {
            case BluetoothProfile.STATE_CONNECTED ->
                    "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING ->
                    "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTING ->
                    "DISCONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED ->
                    "DISCONNECTED";
            default -> "UNKNOWN(" + state + ")";
        };
    }

    private static String bondName(
            int state) {
        return switch (state) {
            case BluetoothDevice.BOND_BONDED ->
                    "BONDED";
            case BluetoothDevice.BOND_BONDING ->
                    "BONDING";
            case BluetoothDevice.BOND_NONE ->
                    "NONE";
            default -> "UNKNOWN(" + state + ")";
        };
    }

    private static final class NotificationSubscription {
        private final String label;
        private final BluetoothGattCharacteristic characteristic;

        private NotificationSubscription(
                String label,
                BluetoothGattCharacteristic characteristic) {
            this.label = label;
            this.characteristic = characteristic;
        }
    }
}
