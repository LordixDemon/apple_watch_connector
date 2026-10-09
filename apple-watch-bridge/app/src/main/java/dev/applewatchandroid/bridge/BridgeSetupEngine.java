package dev.applewatchandroid.bridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class BridgeSetupEngine implements AutoCloseable {
    private final Context context;
    private final Runnable onIdle;
    private final BridgeActivityJournal journal;
    private static final String TARGET_ACK = "CPH2653";
    private static final String ROOT_MAIN =
            "dev.applewatchandroid.bridge.RootBluetoothHalHost";
    private static final String ROOT_CARPLAY_MAIN =
            "dev.applewatchandroid.bridge.RootBluetoothCarPlayLease";
    private static final String ROOT_IMPORT_MAIN =
            "dev.applewatchandroid.bridge.RootBluetoothBondImporter";
    private static final String EXTRA_IMPORT_STOCK_BOND =
            "dev.applewatchandroid.bridge.extra.IMPORT_STOCK_BOND";
    private static final String EXTRA_PROBE_STOCK_RECONNECT =
            "dev.applewatchandroid.bridge.extra.PROBE_STOCK_RECONNECT";
    private static final String EXTRA_ALIGN_STOCK_IDENTITY =
            "dev.applewatchandroid.bridge.extra.ALIGN_STOCK_IDENTITY";
    private static final String BOND_SECRET_PREFIX =
            "BOND_SECRET_V1:";
    private static final String PAIRING_SESSION_PREFIX =
            "PAIRING_SESSION_V2:";
    private static final String LOCAL_IDENTITY_PREFIX =
            "LOCAL_IDENTITY_V1:";
    private static final String BOND_STORE_ACK =
            "BOND-STORED";
    private static final String BOND_STORE_FAILURE =
            "BOND-STORE-FAILED";
    private static final String PAIRING_SESSION_STORE_ACK =
            "PAIRING-SESSION-STORED";
    private static final String PAIRING_SESSION_STORE_FAILURE =
            "PAIRING-SESSION-STORE-FAILED";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean running, pinRequired, stopping;
    private final android.os.Bundle setupState = new android.os.Bundle();
    private final SetupProgressState progress = new SetupProgressState();
    private volatile String pendingOwnerConfirmation;

    private BridgeIdentityStore identityStore;
    private File encryptedBondFile;
    private File encryptedPairingSessionFile;

    private volatile Process rootProcess;
    private volatile BufferedWriter rootInput;
    private volatile StockBluetoothReconnectProbe
            stockReconnectProbe;
    private int activeChallengeId =
            -1;

    private static volatile BridgeSetupEngine activeInstance;

    public static void sendCommandToRoot(String command) {
        if (OperationalWatchService.isRunning()) {
            if (!OperationalWatchService.sendCommand(command)) {
                BridgeSetupEngine current = activeInstance;
                if (current != null) current.appendLog(current.getString(R.string.command_not_accepted_by_the_service_delivery_is_not));
            }
            return;
        }
        BridgeSetupEngine instance = activeInstance;
        if (instance != null && instance.rootInput != null) {
            synchronized (instance.rootInput) {
                try {
                    instance.rootInput.write(command);
                    if (!command.endsWith("\n")) {
                        instance.rootInput.write('\n');
                    }
                    instance.rootInput.flush();
                } catch (Exception e) {
                    instance.appendLog("ERROR sending to root: " + e.getMessage());
                }
            }
        }
    }

    BridgeSetupEngine(Context context, Runnable onIdle) {
        this.context = context;
        this.onIdle = onIdle;
        journal = new BridgeActivityJournal(context, mainHandler, CompanionSessionState::journal);
        activeInstance = this;
        identityStore = new BridgeIdentityStore(context, this::appendLog);
        encryptedBondFile = new File(context.getFilesDir(), "bluetooth-bond.v1.aesgcm");
        encryptedPairingSessionFile = new File(context.getFilesDir(), "pairing-session.v2.aesgcm");
        setupState.putString("setupPhase", "IDLE");
        publishSetup();
    }
    boolean busy() { return running || rootProcess != null || stockReconnectProbe != null; }
    boolean command(String method, android.os.Bundle args) {
        if (method == null || args == null) return false;
        if ("disconnectWatch".equals(method)) { boolean owned = busy(); requestStop("Companion"); return owned; }
        if ("submitPin".equals(method)) return submitPin(args.getString("pin"));
        if ("selectDiscoveredWatch".equals(method)) {
            String token = args.getString("discoveryToken");
            if (stopping || rootInput == null || !"DISCOVERING".equals(setupState.getString("setupPhase"))) return false;
            java.util.ArrayList<android.os.Bundle> rows = setupState.getParcelableArrayList("discoveredWatches");
            if (rows == null || rows.stream().noneMatch(row -> java.util.Objects.equals(token, row.getString("token")))) return false;
            sendCommandToRoot("SELECT_DISCOVERED_WATCH_V1:" + token);
            return true;
        }
        if ("activationResponse".equals(method)) return activationResponse(args);
        if ("confirmSetup".equals(method) && busy()) {
            String expected = args.getString("pairId");
            if (stopping || rootProcess == null || OperationalWatchService.hasOwner()
                    || !setupState.getBoolean("activationConfirmed") || expected == null) return false;
            pendingOwnerConfirmation = expected;
            requestStop("owner-confirmed-watch-face");
            return true;
        }
        if (busy() || OperationalWatchService.hasOwner()) return false;
        switch (method) {
            case "beginPairing" -> { if (encryptedPairingSessionFile.isFile()) return false; startRootHost(true); }
            case "beginOpticalPairing", "replacePairOptically" -> {
                byte[] payload = args.getByteArray("opticalCode");
                try (OpticalPairingCode ignored = OpticalPairingCode.parse(payload)) {
                    boolean replacement = "replacePairOptically".equals(method);
                    if (replacement != encryptedPairingSessionFile.isFile()) return false;
                    String previous = replacement ? args.getString("pairId") : null;
                    if (replacement && previous == null) return false;
                    startRootHost(true, payload.clone(), previous);
                } catch (IllegalArgumentException invalid) { return false; }
                finally { wipe(payload); args.remove("opticalCode"); }
            }
            case "resumeSetup" -> { if (!encryptedPairingSessionFile.isFile()) return false; startRootHost(false); }
            case "confirmSetup" -> confirmOwnerCompletedSetup();
            case "auditStockBond" -> startStockBondAudit();
            case "importStockBond" -> startStockBondImport();
            case "alignStockIdentity" -> startStockIdentityAlignment();
            case "probeStockReconnect" -> startStockReconnectProbe();
            default -> { return false; }
        }
        return true;
    }
    @Override public void close() {
        requestStop("service-destroy");
        if (activeInstance == this) activeInstance = null;
        ioExecutor.shutdown();
        journal.close();
    }
    private synchronized void publishSetup() {
        setupState.putBoolean("setupRunning", running);
        setupState.putBoolean("pinRequired", pinRequired);
        CompanionSessionState.setup(setupState);
    }
    private synchronized void phase(String value) {
        if (!progress.observe(SetupProgressState.Phase.valueOf(value))) return;
        setupState.putString("setupPhase", progress.phase().name());
        if (progress.phase() == SetupProgressState.Phase.VERIFIED) {
            // A completed native checkpoint must not remain masked by the
            // transient false value published at the start of this run.
            setupState.remove("operationalEligible");
            CompanionSessionState.refreshIdentity(context);
        }
        if (progress.phase() == SetupProgressState.Phase.ACTIVATED
                || progress.phase() == SetupProgressState.Phase.SYNCING
                || progress.phase() == SetupProgressState.Phase.WAITING_FOR_WATCH
                || progress.phase() == SetupProgressState.Phase.VERIFYING_RECONNECT
                || progress.phase() == SetupProgressState.Phase.VERIFIED) {
            setupState.putBoolean("activationConfirmed", true);
        }
        if (progress.phase().ordinal() >= SetupProgressState.Phase.ACTIVATED.ordinal()) {
            pinRequired = false;
            activeChallengeId = -1;
            setupState.remove("activationChallengeId");
            setupState.remove("activationTitle");
            setupState.remove("activationMessage");
            setupState.remove("activationFields");
        }
        publishSetup();
    }
    private synchronized void discover(String line) {
        String[] parts = line.split(":");
        if (parts.length != 5 || !"DISCOVERING".equals(setupState.getString("setupPhase"))) return;
        try {
            if (!java.util.UUID.fromString(parts[1]).toString().equals(parts[1]) || !parts[2].matches("Watch[0-9]+,[0-9]+")) return;
            android.os.Bundle row = new android.os.Bundle();
            row.putString("token", parts[1]); row.putString("productType", parts[2]);
            row.putString("watchOs", parts[3]); row.putInt("rssi", Integer.parseInt(parts[4]));
            java.util.ArrayList<android.os.Bundle> rows = setupState.getParcelableArrayList("discoveredWatches");
            if (rows == null) rows = new java.util.ArrayList<>();
            rows.removeIf(old -> parts[1].equals(old.getString("token")));
            if (rows.size() >= 8) rows.remove(0);
            rows.add(row); setupState.putParcelableArrayList("discoveredWatches", rows); publishSetup();
        } catch (IllegalArgumentException invalid) { /* Not a candidate observation. */ }
    }

    private String getPackageManagerVersionName() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            return "unknown";
        }
    }

    private void startRootHost() {
        startRootHost(false);
    }

    private void handleBridgeApplicationEvent(String encoded) {
        byte[] frame = null;
        try {
            if (encoded.length() > ((BridgeApplicationEventCodec.MAX_FRAME + 2) / 3) * 4) {
                throw new IllegalArgumentException("Bridge event exceeds size limit");
            }
            frame = Base64.decode(encoded, Base64.NO_WRAP);
            try (BridgeApplicationEventCodec.Event event = BridgeApplicationEventCodec.decode(frame)) {
                BridgeIpcDispatcher.getInstance().dispatchApplicationMessage(event);
                appendLog("APPLICATION IPC RX: topic=" + event.topic + " type=" + event.protobufType
                        + " response=" + event.response + " bytes=" + event.payloadLength() + "; payload logged=false.");
            }
        } catch (IllegalArgumentException malformed) {
            appendLog("APPLICATION IPC RX rejected; payload logged=false.");
        } finally {
            wipe(frame);
        }
    }

    private PairingSessionRecord readStoredPairingRecord() throws Exception {
        if (encryptedPairingSessionFile == null || !encryptedPairingSessionFile.isFile()) {
            throw new IOException(getString(R.string.no_saved_pair));
        }
        byte[] container = null;
        byte[] plaintext = null;
        try {
            container = readBoundedFile(encryptedPairingSessionFile, PairingSessionEnvelope.MAX_CONTAINER_LENGTH);
            plaintext = decryptPairingSessionContainer(container);
            return PairingSessionRecord.parse(plaintext);
        } finally {
            wipe(plaintext);
            wipe(container);
        }
    }

    private void confirmOwnerCompletedSetup() {
        if (busy() || OperationalWatchService.hasOwner()) {
            appendLog(getString(R.string.stop_the_current_run_first_confirmation_applies_to_the));
            return;
        }
        setRunning(true);
        ioExecutor.execute(() -> {
            try {
                saveOwnerCompletedSetup(null);
            } catch (Exception error) {
                synchronized (this) { progress.reset(); }
                phase("FAILED");
                appendLog(getString(R.string.setup_confirmation_not_saved) + safeMessage(error));
            } finally {
                setRunning(false);
            }
        });
    }

    private void saveOwnerCompletedSetup(String expectedPair) throws Exception {
        PairingSessionRecord record = readStoredPairingRecord();
        try {
            String pair = OperationalSessionPolicy.pairingId(record);
            OperationalSessionPolicy.requireMatchingActivatedPair(record, expectedPair == null ? pair : expectedPair);
            if (!context.getSharedPreferences("watch_operating_mode", Context.MODE_PRIVATE).edit()
                    .putString("owner_confirmed_pairing", pair)
                    .putLong("owner_confirmed_at", System.currentTimeMillis()).commit()) {
                throw new IOException(getString(R.string.could_not_save_confirmation));
            }
            synchronized (this) {
                progress.ownerConfirmed();
                setupState.putString("setupPhase", "VERIFIED");
                setupState.remove("operationalEligible");
                publishSetup();
            }
            CompanionSessionState.refreshIdentity(context);
            appendLog(getString(R.string.normal_mode_saved_for_this_pair_with_owner_confirmation)
                    + getString(R.string.setup_will_not_repeat_native_issetup_and_operational_health));
        } finally { record.destroy(); }
    }

    private String operationalPairingForStart(boolean freshPairing) throws Exception {
        return freshPairing ? null : identityStore.operationalPairing();
    }

    private void startRootHost(boolean freshPairing) {
        startRootHost(freshPairing, null, null);
    }

    private void startRootHost(boolean freshPairing, byte[] opticalCode, String replacedPair) {
        if (busy() || OperationalWatchService.hasOwner()) {
            wipe(opticalCode);
            appendLog(getString(R.string.root_hal_host_is_already_running));
            return;
        }
        String confirmed = context.getSharedPreferences("watch_operating_mode", Context.MODE_PRIVATE)
                .getString("owner_confirmed_pairing", null);
        if (confirmed != null && (opticalCode == null || !confirmed.equals(replacedPair))) {
            wipe(opticalCode);
            appendLog(getString(R.string.new_pairing_rejected_an_activated_pair_is_saved_normal));
            mainHandler.post(onIdle);
            return;
        }
        final boolean commitAuthorized = true;
        stopping = false;
        synchronized (this) {
            progress.reset();
            setupState.clear();
            activeChallengeId = -1;
            setupState.putString("setupPhase", progress.phase().name());
            setupState.putBoolean("activationConfirmed", false);
            setupState.putBoolean("operationalEligible", false);
        }
        setRunning(true);
        setPinEntryEnabled(false);
        journal.clearVisibleLog();
        appendLog("");
        appendLog(getString(R.string.new_run)
                + DateFormat.getDateTimeInstance().format(new Date()) + " ===");
        appendLog(getString(R.string.commit_and_activation_albert_https)
                + (commitAuthorized ? getString(R.string.enabled) : getString(R.string.disabled)));
        if (freshPairing && opticalCode == null) {
            appendLog(getString(R.string.fresh_pairing_a_new_code_is_required_for_this)
                    + getString(R.string.the_saved_session_is_not_sent_to_the_helper)
                    + getString(R.string.its_encrypted_file_is_retained_until_a_new_pair));
        }
        appendLog(getString(R.string.requires_magisk_root_and_the_installed_bridge_module));
        appendLog(getString(R.string.fail_closed_direct_mode_requires_bluetooth_off)
                + getString(R.string.with_bluetooth_on_only_a_signed_catplay)
                + getString(R.string.handoff_after_bmw_wi_fi_and_media_are_ready));

        ioExecutor.execute(() -> {
            Process process = null;
            byte[] localIdentityRecord = null;
            byte[] idsPublicBundle = null;
            CarPlayBluetoothHandoffClient handoffClient = null;
            boolean handoffMayBePrepared = false;
            boolean handoffPrepared = false;
            boolean handoffResumed = false;
            boolean cleanupSucceeded = false;
            try {
                if (opticalCode != null) {
                    appendLog("OPTICAL SETUP: fresh advertisement binding required; code and key logged=false.");
                }
                String operationalPairing = operationalPairingForStart(freshPairing);
                if (operationalPairing != null) {
                    appendLog(getString(R.string.normal_activated_pair_connection_setup_albert_and_new_pin));
                }
                idsPublicBundle = loadOrCreateIdsPublicBundle();
                byte[] boundPublicBundle = IdsMessageProtectionIdentity.bindPublicBundle(
                        idsPublicBundle, loadOrCreateIdsDeviceIdentifier());
                wipe(idsPublicBundle);
                idsPublicBundle = boundPublicBundle;
                localIdentityRecord =
                        deriveStableLocalIdentityRecord();
                int bluetoothState = stockBluetoothState();
                CarPlayBluetoothHandoffClient.Status handoffStatus =
                        CarPlayBluetoothHandoffClient.Status
                                .unavailable();
                if (bluetoothState
                        == BluetoothAdapter.STATE_ON) {
                    handoffClient =
                            new CarPlayBluetoothHandoffClient(
                                    context.getContentResolver());
                    handoffStatus = handoffClient.status();
                }
                CarPlayHandoffDecision.Mode mode =
                        CarPlayHandoffDecision.decide(
                                bluetoothState
                                        == BluetoothAdapter.STATE_OFF,
                                bluetoothState
                                        == BluetoothAdapter.STATE_ON,
                                handoffStatus.available,
                                handoffStatus.wifiReady,
                                handoffStatus.vehicleMediaActive,
                                handoffStatus.prepared);
                if (mode
                        == CarPlayHandoffDecision.Mode.REFUSE) {
                    throw new IOException(
                            "Bluetooth/CarPlay readiness gate refused "
                                    + "startup: adapterState="
                                    + bluetoothState
                                    + " provider="
                                    + handoffStatus.available
                                    + " wifiReady="
                                    + handoffStatus.wifiReady
                                    + " vehicleMediaActive="
                                    + handoffStatus
                                    .vehicleMediaActive
                                    + " alreadyPrepared="
                                    + handoffStatus.prepared);
                }
                if (mode
                        == CarPlayHandoffDecision.Mode
                        .COOPERATIVE_CARPLAY) {
                    handoffMayBePrepared = true;
                    CarPlayBluetoothHandoffClient.Status prepared =
                            handoffClient.prepare();
                    if (!prepared.available
                            || !prepared.ready
                            || !prepared.prepared) {
                        throw new IOException(
                                "CarPlay handoff preparation failed: "
                                        + "ready="
                                        + prepared.ready
                                        + " prepared="
                                        + prepared.prepared);
                    }
                    handoffPrepared = true;
                    appendLog("Cooperative CarPlay handoff prepared; "
                            + "root manager session active.");
                } else {
                    appendLog("DIRECT HAL MODE: stock Bluetooth OFF; "
                            + "CatPlay handoff not requested.");
                }
                String apk = context.getApplicationInfo().sourceDir;
                String rootMain = handoffPrepared
                        ? ROOT_CARPLAY_MAIN
                        : ROOT_MAIN;
                if (stopping) throw new IOException("Session cancelled before HAL startup");
                if (opticalCode != null) {
                    if (replacedPair != null) retirePairForOpticalReplacement(replacedPair);
                    else if (encryptedPairingSessionFile.exists()) throw new IOException("A saved pair appeared before optical setup");
                }
                String command =
                        "CLASSPATH=" + shellQuote(apk)
                                + " app_process /system/bin "
                                + rootMain
                                + " --acknowledge-target "
                                + TARGET_ACK
                                + (operationalPairing != null
                                    ? " --operational-pairing " + shellQuote(operationalPairing)
                                    : (commitAuthorized ? " --commit-is-paired" : ""))
                                + (opticalCode == null ? "" : " --optical-pairing");
                process = new ProcessBuilder("su", "-c", command)
                        .redirectErrorStream(true)
                        .start();
                rootProcess = process;
                rootInput = new BufferedWriter(new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8));
                if (stopping) { synchronized (rootInput) { rootInput.write("STOP\n"); rootInput.flush(); } }
                if (opticalCode != null) {
                    byte[] encoded = Base64.encode(opticalCode, Base64.NO_WRAP);
                    try {
                        synchronized (rootInput) {
                            rootInput.write("OPTICAL_PAIRING_CODE_V1:");
                            for (byte value : encoded) rootInput.write((char) (value & 0xff));
                            rootInput.write('\n'); rootInput.flush();
                        }
                    } finally { wipe(encoded); wipe(opticalCode); }
                }
                synchronized (rootInput) { rootInput.write("COMPANION_DISCOVERY_V1\n"); rootInput.flush(); }
                sendLocalIdentityToRoot(
                        rootInput,
                        localIdentityRecord);
                synchronized (rootInput) {
                    rootInput.write("LOCAL_IDS_PUBLIC_KEYS_V1:");
                    rootInput.write(Base64.encodeToString(idsPublicBundle, Base64.NO_WRAP));
                    rootInput.write('\n');
                    rootInput.flush();
                }
                appendLog(getString(R.string.ids_identity_protection_keys_stored_in_android_keystore_helper));
                appendLog(getString(R.string.persistent_static_random_ble_identity)
                        + getString(R.string.derived_from_a_non_exportable_android_keystore)
                        + getString(R.string.key_and_sent_to_the_helper_address_irk)
                        + "logged=false.");

                if (!freshPairing && encryptedPairingSessionFile != null && encryptedPairingSessionFile.isFile()) {
                    byte[] sessionContainer = null;
                    byte[] sessionPlaintext = null;
                    PairingSessionRecord storedRecord = null;
                    try {
                        sessionContainer = readBoundedFile(
                                encryptedPairingSessionFile,
                                PairingSessionEnvelope.MAX_CONTAINER_LENGTH);
                        sessionPlaintext = decryptPairingSessionContainer(sessionContainer);
                        storedRecord = PairingSessionRecord.parse(sessionPlaintext);
                        if (storedRecord.bluetoothBond() != null
                                || storedRecord.state().wireValue()
                                >= PairingSessionRecord.DurableState.SMP_BONDED_RAW.wireValue()) {
                            byte[] encodedSession = Base64.encode(sessionPlaintext, Base64.NO_WRAP);
                            synchronized (rootInput) {
                                rootInput.write("RESTORED_PAIRING_SESSION_V2:");
                                for (byte value : encodedSession) {
                                    rootInput.write((char) (value & 0xff));
                                }
                                rootInput.write('\n');
                                rootInput.flush();
                            }
                            appendLog(getString(R.string.saved_pairing_session_state)
                                    + storedRecord.state()
                                    + getString(R.string.sent_to_the_helper_for_direct_reconnection));
                        }
                    } catch (Exception restoreError) {
                        appendLog(getString(R.string.could_not_send_the_saved_session_to_the_helper)
                                + safeMessage(restoreError));
                    } finally {
                        if (storedRecord != null) {
                            storedRecord.destroy();
                        }
                        wipe(sessionPlaintext);
                        wipe(sessionContainer);
                    }
                }

                boolean sawTerminalPass = false;
                boolean sawTerminalFailure = false;
                boolean sawFatalFailure = false;
                boolean sawCarPlayRestorePass =
                        !handoffPrepared;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                process.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith(SetupProgressState.PREFIX)) {
                            try { phase(SetupProgressState.decode(line).name()); }
                            catch (IllegalArgumentException invalid) { appendLog("Invalid setup progress event ignored."); }
                            continue;
                        }
                        if (line.startsWith("WATCH_DISCOVERED_V1:")) { discover(line); continue; }
                        if (line.startsWith(HealthRegistryObservationCodec.PREFIX) || line.startsWith(HealthDataEventCodec.PREFIX) || line.startsWith(HealthPeerIdentityCodec.PREFIX)
                                || line.startsWith(HealthOutboundIpcCodec.STATUS_PREFIX)) {
                            // Health Data is handled by OperationalWatchService in a ready pair/epoch.
                            // Pairing/debug stdout must also keep encrypted payloads out of the UI journal.
                            appendLog("HEALTH DATA ignored outside operational service; payload logged=false.");
                            continue;
                        }
                        if (line.startsWith(BridgeApplicationEventCodec.PREFIX)) {
                            handleBridgeApplicationEvent(line.substring(BridgeApplicationEventCodec.PREFIX.length()));
                            continue; // Payloads bypass journal and UI log.
                        }
                        if (line.startsWith(
                                PAIRING_SESSION_PREFIX)) {
                            boolean stored =
                                    persistEncryptedPairingSession(
                                            line.substring(
                                                    PAIRING_SESSION_PREFIX
                                                            .length()));
                            acknowledgePairingSessionStore(
                                    stored);
                            appendLog(stored
                                    ? getString(R.string.pairingsessionrecord_v2_saved)
                                    + getString(R.string.atomically_using_android_keystore)
                                    + getString(R.string.aes_gcm_and_verified_by_readback)
                                    + "plaintext logged=false."
                                    : getString(R.string.could_not_securely_save)
                                    + "PairingSessionRecord v2; helper "
                                    + getString(R.string.will_stop_pairing_before_the_next)
                                    + getString(R.string.irreversible_boundary));
                            continue;
                        }
                        if (line.startsWith(
                                BOND_SECRET_PREFIX)) {
                            boolean stored =
                                    persistEncryptedBondSecret(
                                            line.substring(
                                                    BOND_SECRET_PREFIX
                                                            .length()));
                            acknowledgeBondStore(stored);
                            appendLog(stored
                                    ? getString(R.string.bluetooth_bond_material_saved)
                                    + getString(R.string.atomically_using_android_keystore)
                                    + "AES-GCM; plaintext logged=false."
                                    : getString(R.string.could_not_securely_save)
                                    + "Bluetooth bond material; "
                                    + getString(R.string.helper_will_stop_smp_before)
                                    + getString(R.string.final_key_distribution));
                            continue;
                        }
                        if (line.startsWith("EVENT:FIND_MY_PHONE")) {
                            Intent eventIntent = new Intent("dev.applewatchandroid.companion.ACTION_FIND_MY_PHONE");
                            context.sendBroadcast(eventIntent);
                            appendLog("EVENT: Apple Watch requested Find My Phone!");
                        }
                        if (line.startsWith("ACTIVATION-CHALLENGE-V1:")) {
                            handleActivationChallengeLine(
                                    line.substring("ACTIVATION-CHALLENGE-V1:".length()));
                            continue;
                        }
                        if (line.startsWith("ACTIVATION-ALERT-V1:")) {
                            handleActivationAlertLine(
                                    line.substring("ACTIVATION-ALERT-V1:".length()));
                            continue;
                        }
                        if (line.startsWith("EVENT:NOTIFICATION_DISMISS")) {
                            Intent eventIntent = new Intent("dev.applewatchandroid.companion.ACTION_NOTIFICATION_DISMISS");
                            context.sendBroadcast(eventIntent);
                        }
                        if (line.contains("PAIRED IDS LIVE HOLD: session ready")) {
                            BridgeIpcDispatcher.getInstance().updateConnectionState(true, "Apple Watch");
                            Intent eventIntent = new Intent("dev.applewatchandroid.companion.ACTION_CONNECTION_STATE");
                            eventIntent.putExtra("connected", true);
                            eventIntent.putExtra("deviceName", "Apple Watch");
                            context.sendBroadcast(eventIntent);
                        }
                        if (line.contains("PIN_REQUIRED:")) {
                            setPinEntryEnabled(true);
                        }
                        if (line.contains(
                                "CARPLAY STOCK BLUETOOTH RESTORE PASS:")) {
                            sawCarPlayRestorePass = true;
                        }
                        if (line.contains("RESULT: PASS")) {
                            sawTerminalPass = true;
                        } else if (line.contains("RESULT: FAIL")
                                || line.contains(
                                "RESULT: STOPPED")) {
                            sawTerminalFailure = true;
                            if (line.contains("RESULT: FAIL")) sawFatalFailure = true;
                        }
                        appendLog(line);
                    }
                }
                int status = process.waitFor();
                if (handoffPrepared) {
                    CarPlayBluetoothHandoffClient.Status resumed =
                            handoffClient.resume();
                    if (!resumed.available || resumed.prepared) {
                        throw new IOException(
                                "CatPlay did not release its handoff state "
                                        + "after stock Bluetooth restore");
                    }
                    handoffResumed = true;
                    appendLog("CARPLAY HANDOFF RESUME PASS: signed "
                            + "coordinator released; BMW Wi-Fi/media "
                            + "state was not cleared.");
                }
                appendLog(getString(R.string.root_hal_host_exited_exit) + status + ".");
                cleanupSucceeded = status == 0 && sawCarPlayRestorePass && !sawFatalFailure;
                BridgeIpcDispatcher.getInstance().updateConnectionState(false, "Apple Watch");
                if (status != 0
                        || !sawTerminalPass
                        || sawTerminalFailure
                        || !sawCarPlayRestorePass) {
                    appendLog("ROOT HAL TERMINAL CHECK: FAIL — "
                            + getString(R.string.success_requires_both_exit_0)
                            + getString(R.string.and_exactly_one_result_pass_carplay)
                            + getString(R.string.mode_also_requires_manager_restore_pass));
                    if (!stopping) phase("FAILED");
                }
            } catch (Exception error) {
                phase("FAILED");
                appendLog(getString(R.string.startup_error)
                        + error.getClass().getSimpleName()
                        + ": "
                        + safeMessage(error));
            } finally {
                if (handoffMayBePrepared
                        && !handoffResumed
                        && handoffClient != null) {
                    CarPlayBluetoothHandoffClient.Status resumed =
                            handoffClient.resume();
                    handoffResumed = resumed.available
                            && !resumed.prepared;
                    appendLog(handoffResumed
                            ? "CARPLAY HANDOFF FALLBACK RESUME PASS: "
                            + "coordinator state released."
                            : "CARPLAY HANDOFF FALLBACK RESUME FAIL: "
                            + "CatPlay provider did not confirm release.");
                }
                wipe(localIdentityRecord);
                wipe(idsPublicBundle);
                wipe(opticalCode);
                if (rootProcess == process) {
                    rootProcess = null;
                    rootInput = null;
                    BridgeIpcDispatcher.getInstance().updateConnectionState(false, "Apple Watch");
                }
                setPinEntryEnabled(false);
                if (stopping) phase("STOPPED");
                String confirmation = pendingOwnerConfirmation;
                pendingOwnerConfirmation = null;
                if (confirmation != null) {
                    try {
                        if (!cleanupSucceeded) throw new IOException("Setup transport did not stop cleanly");
                        saveOwnerCompletedSetup(confirmation);
                    } catch (Exception error) {
                        // A failed cleanup/save must not appear as owner-confirmed completion.
                        synchronized (this) {
                            progress.reset();
                            phase("FAILED");
                        }
                        appendLog(getString(R.string.setup_confirmation_not_saved) + safeMessage(error));
                    }
                }
                setRunning(false);
            }
        });
    }

    private void retirePairForOpticalReplacement(String expected) throws Exception {
        if (stopping || OperationalWatchService.hasOwner()) throw new IOException("Replacement no longer owns setup");
        PairingSessionRecord stored = readStoredPairingRecord();
        try {
            if (!expected.equals(OperationalSessionPolicy.pairingId(stored))) {
                throw new IOException("Saved pair changed before optical replacement");
            }
        } finally { stored.destroy(); }
        var preferences = context.getSharedPreferences("watch_operating_mode", Context.MODE_PRIVATE);
        String confirmation = preferences.getString("owner_confirmed_pairing", null);
        long confirmedAt = preferences.getLong("owner_confirmed_at", 0);
        PairingReplacementBackup backup = PairingReplacementBackup.stage(context.getFilesDir().toPath(),
                expected, confirmation, confirmedAt);
        try {
            if (!preferences.edit().remove("owner_confirmed_pairing").remove("owner_confirmed_at").commit()) {
                throw new IOException("Could not retire saved pair confirmation");
            }
            backup.retire();
        } catch (Exception failure) {
            try { backup.restoreMissing(); } catch (Exception restore) { failure.addSuppressed(restore); }
            var restore = preferences.edit();
            if (confirmation != null) restore.putString("owner_confirmed_pairing", confirmation).putLong("owner_confirmed_at", confirmedAt);
            else restore.remove("owner_confirmed_pairing").remove("owner_confirmed_at");
            if (!restore.commit()) failure.addSuppressed(new IOException("Could not restore pair confirmation"));
            CompanionSessionState.refreshIdentity(context);
            throw failure;
        }
        CompanionSessionState.refreshIdentity(context);
        appendLog("OPTICAL REPLACEMENT: encrypted previous pair archived and verified; Android identities retained.");
    }


    private int stockBluetoothState() throws IOException {
        BluetoothManager manager =
                context.getSystemService(
                        BluetoothManager.class);
        BluetoothAdapter adapter = manager == null
                ? null
                : manager.getAdapter();
        if (adapter == null) {
            throw new IOException(
                    "Bluetooth adapter is unavailable");
        }
        return adapter.getState();
    }

    private void startStockBondAudit() {
        startStockBondTool(
                "--audit-stock-bond",
                "=== Read-only stock Bluetooth bond audit ===",
                getString(R.string.bluetooth_manager_must_be_off_helper_reads)
                        + getString(R.string.the_config_and_prepares_a_redacted_hypothetical_diff)
                        + getString(R.string.without_opening_any_file_for_writing),
                "STOCK AUDIT");
    }

    private void startStockBondImport() {
        startStockBondTool(
                "--import-stock-bond",
                "=== Additive stock Bluetooth bond import ===",
                getString(R.string.fail_closed_bluetooth_manager_must_be_off)
                        + getString(R.string.the_existing_bmw_config_must_remain)
                        + getString(R.string.byte_for_byte_unchanged),
                "STOCK IMPORT");
    }

    private void startStockBondTool(
            String mode,
            String title,
            String description,
            String errorLabel) {
        if (busy() || OperationalWatchService.hasOwner()) {
            appendLog(getString(R.string.root_helper_is_already_running));
            return;
        }
        if (!encryptedBondFile.isFile()) {
            appendLog(getString(R.string.no_encrypted_bond_record)
                    + getString(R.string.stock_import_did_not_start));
            return;
        }
        setRunning(true);
        setPinEntryEnabled(false);
        appendLog("");
        appendLog(title);
        appendLog(description);

        ioExecutor.execute(() -> {
            Process process = null;
            byte[] plaintext = null;
            try {
                plaintext =
                        decryptStoredBondSecret();
                String apk =
                        context.getApplicationInfo().sourceDir;
                String command =
                        "CLASSPATH=" + shellQuote(apk)
                                + " app_process /system/bin "
                                + ROOT_IMPORT_MAIN
                                + " --acknowledge-target "
                                + TARGET_ACK
                                + " "
                                + mode;
                process =
                        new ProcessBuilder(
                                "su",
                                "-c",
                                command)
                                .redirectErrorStream(true)
                                .start();
                rootProcess = process;
                try (OutputStream input =
                             process.getOutputStream()) {
                    input.write(plaintext);
                    input.flush();
                }
                Arrays.fill(plaintext, (byte) 0);
                plaintext = null;

                boolean sawTerminalPass = false;
                boolean sawTerminalFailure = false;
                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             process.getInputStream(),
                                             StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine())
                            != null) {
                        if (line.contains("RESULT: PASS")) {
                            sawTerminalPass = true;
                        } else if (line.contains(
                                "RESULT: FAIL")) {
                            sawTerminalFailure = true;
                        }
                        appendLog(line);
                    }
                }
                int status = process.waitFor();
                appendLog(getString(R.string.root_stock_bond_helper_exited)
                        + "exit="
                        + status
                        + ".");
                if (status != 0
                        || !sawTerminalPass
                        || sawTerminalFailure) {
                    appendLog("STOCK HELPER TERMINAL CHECK: FAIL — "
                            + getString(R.string.success_requires_both_exit_0)
                            + getString(R.string.and_result_pass));
                }
            } catch (Exception error) {
                appendLog(getString(R.string.error)
                        + errorLabel
                        + ": "
                        + error.getClass()
                                .getSimpleName()
                        + ": "
                        + safeMessage(error));
            } finally {
                if (plaintext != null) {
                    Arrays.fill(
                            plaintext,
                            (byte) 0);
                }
                if (rootProcess == process) {
                    rootProcess = null;
                    rootInput = null;
                }
                setRunning(false);
            }
        });
    }

    private void startStockReconnectProbe() {
        if (rootProcess != null
                || stockReconnectProbe != null) {
            appendLog(getString(R.string.bluetooth_helper_probe_is_already_running));
            return;
        }
        if (!encryptedBondFile.isFile()) {
            appendLog(getString(R.string.no_encrypted_bond_record)
                    + getString(R.string.acl_only_probe_did_not_start));
            return;
        }
        byte[] bondPlaintext = null;
        String expectedIdentityAddress;
        try {
            bondPlaintext = decryptStoredBondSecret();
            expectedIdentityAddress =
                    BluetoothBondSecretRecord
                            .peerIdentityAddressText(
                                    bondPlaintext);
        } catch (Exception error) {
            appendLog(getString(R.string.could_not_open_the_exact_bond_identity)
                    + getString(R.string.acl_only_probe_did_not_start2)
                    + safeMessage(error));
            return;
        } finally {
            wipe(bondPlaintext);
        }
        setRunning(true);
        setPinEntryEnabled(false);
        appendLog("");
        appendLog("=== Stock Android exact-identity ACL-only probe ===");
        appendLog(getString(R.string.only_public_bluetooth_apis_and_the_identity_from)
                + getString(R.string.the_encrypted_bond_record_scan_connectgatt)
                + "Pairing, service discovery, characteristic reads, "
                + getString(R.string.cccd_and_application_payloads_are_prohibited));
        StockBluetoothReconnectProbe probe =
                new StockBluetoothReconnectProbe(context,
                        mainHandler,
                        this::appendLog,
                        () -> {
                            stockReconnectProbe = null;
                            setRunning(false);
                        },
                        expectedIdentityAddress,
                        StockBluetoothReconnectProbe
                                .Mode.ACL_ONLY);
        stockReconnectProbe = probe;
        probe.start();
    }

    private void startStockIdentityAlignment() {
        if (rootProcess != null
                || stockReconnectProbe != null) {
            appendLog(getString(R.string.bluetooth_helper_probe_is_already_running));
            return;
        }
        if (!encryptedBondFile.isFile()) {
            appendLog(getString(R.string.no_encrypted_bond_record)
                    + getString(R.string.identity_alignment_did_not_start));
            return;
        }
        setRunning(true);
        setPinEntryEnabled(false);
        appendLog("");
        appendLog("=== Stock adapter identity alignment ===");
        appendLog(getString(R.string.fail_closed_bluetooth_manager_must_be_off2)
                + getString(R.string.only_bmw_br_edr_and_this_watch_are_allowed)
                + getString(R.string.one_adapter_irk_line_changes_with_a_separate)
                + getString(R.string.protected_backup));

        ioExecutor.execute(() -> {
            Process process = null;
            byte[] plaintext = null;
            try {
                plaintext =
                        decryptStoredBondSecret();
                String apk =
                        context.getApplicationInfo().sourceDir;
                String command =
                        "CLASSPATH=" + shellQuote(apk)
                                + " app_process /system/bin "
                                + ROOT_IMPORT_MAIN
                                + " --acknowledge-target "
                                + TARGET_ACK
                                + " --align-stock-identity";
                process =
                        new ProcessBuilder(
                                "su",
                                "-c",
                                command)
                                .redirectErrorStream(true)
                                .start();
                rootProcess = process;
                try (OutputStream input =
                             process.getOutputStream()) {
                    input.write(plaintext);
                    input.flush();
                }
                Arrays.fill(plaintext, (byte) 0);
                plaintext = null;

                try (BufferedReader reader =
                             new BufferedReader(
                                     new InputStreamReader(
                                             process.getInputStream(),
                                             StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine())
                            != null) {
                        appendLog(line);
                    }
                }
                int status = process.waitFor();
                appendLog(getString(R.string.root_identity_aligner_exited_exit)
                        + status
                        + ".");
            } catch (Exception error) {
                appendLog(getString(R.string.identity_alignment_error)
                        + error.getClass()
                                .getSimpleName()
                        + ": "
                        + safeMessage(error));
            } finally {
                if (plaintext != null) {
                    Arrays.fill(
                            plaintext,
                            (byte) 0);
                }
                if (rootProcess == process) {
                    rootProcess = null;
                    rootInput = null;
                }
                setRunning(false);
            }
        });
    }

    private boolean persistEncryptedBondSecret(String encoded) { return identityStore.storeBond(encoded); }
    private boolean persistEncryptedPairingSession(String encoded) {
        boolean stored = identityStore.storePairing(encoded, null);
        if (stored) CompanionSessionState.refreshIdentity(context);
        return stored;
    }
    private byte[] decryptPairingSessionContainer(byte[] bytes) throws Exception { return identityStore.decryptPairing(bytes); }
    private byte[] decryptStoredBondSecret() throws Exception { return identityStore.decryptBond(); }
    private byte[] loadOrCreateIdsPublicBundle() throws Exception { return identityStore.publicBundle(); }
    private String loadOrCreateIdsDeviceIdentifier() throws Exception { return identityStore.idsIdentifier(); }
    private byte[] deriveStableLocalIdentityRecord() throws Exception { return identityStore.localIdentity(); }
    private static byte[] readBoundedFile(File file, int limit) throws IOException { return BridgeIdentityStore.readFile(file, limit); }

    private static void sendLocalIdentityToRoot(
            BufferedWriter input,
            byte[] identityRecord)
            throws IOException {
        BluetoothLocalIdentity parsed = null;
        byte[] encoded = null;
        try {
            parsed = BluetoothLocalIdentity.parse(identityRecord);
            encoded = Base64.encode(
                    identityRecord,
                    Base64.NO_WRAP);
            synchronized (input) {
                input.write(LOCAL_IDENTITY_PREFIX);
                for (byte value : encoded) {
                    input.write((char) (value & 0xff));
                }
                input.write('\n');
                input.flush();
            }
        } finally {
            if (parsed != null) {
                parsed.destroy();
            }
            wipe(encoded);
        }
    }

    private void acknowledgeBondStore(
            boolean stored) {
        BufferedWriter input = rootInput;
        if (input == null) {
            return;
        }
        try {
            synchronized (input) {
                input.write(stored
                        ? BOND_STORE_ACK
                        : BOND_STORE_FAILURE);
                input.write('\n');
                input.flush();
            }
        } catch (IOException error) {
            appendLog(getString(R.string.could_not_confirm_the_helper_result_for)
                    + getString(R.string.protected_bond_material_storage));
        }
    }

    private void acknowledgePairingSessionStore(
            boolean stored) {
        BufferedWriter input = rootInput;
        if (input == null) {
            return;
        }
        try {
            synchronized (input) {
                input.write(stored
                        ? PAIRING_SESSION_STORE_ACK
                        : PAIRING_SESSION_STORE_FAILURE);
                input.write('\n');
                input.flush();
            }
        } catch (IOException error) {
            appendLog(getString(R.string.could_not_confirm_the_helper_result_for)
                    + getString(R.string.protected_pairing_session_storage));
        }
    }

    private boolean submitPin(String pin) {
        BufferedWriter input = rootInput;
        if (input == null || stopping || !pinRequired || pin == null || !pin.matches("[0-9]{6}")) return false;
        try {
            synchronized (input) { input.write("PIN:" + pin + "\n"); input.flush(); }
            setPinEntryEnabled(false); phase("SECURITY");
            appendLog("Six-digit PIN submitted; value logged=false.");
            return true;
        } catch (IOException error) { setPinEntryEnabled(true); return false; }
    }

    private void handleActivationChallengeLine(String rest) {
        // Format: <id>:<page>:<b64 account|->:<field1,field2,...>[:retry]
        try {
            String[] parts = rest.split(":");
            if (parts.length < 4) {
                return;
            }
            final int id = Integer.parseInt(parts[0]);
            final String page = parts[1];
            final String account = "-".equals(parts[2])
                    ? null
                    : new String(Base64.decode(parts[2], Base64.NO_WRAP),
                            StandardCharsets.UTF_8);
            final String[] fields = parts[3].split(",");
            final boolean isRetryPrompt = parts.length >= 5
                    && "retry".equals(parts[4]);
            mainHandler.post(() -> showActivationChallenge(
                    id, page, account, fields, isRetryPrompt));
            appendLog(getString(R.string.activation_lock_albert_requested_owner_confirmation)
                    + page

                    + (isRetryPrompt
                            ? getString(R.string.retry_after_a_rejected_attempt)
                            : "")
                    + getString(R.string.watch_is_waiting_buddyml_page_was_not_sent_to)
                    + getString(R.string.waiting_for_input_in_the_dialog));
        } catch (Exception error) {
            appendLog(getString(R.string.could_not_parse_activation_challenge)
                    + safeMessage(error));
        }
    }

    private void handleActivationAlertLine(String rest) {
        // Format: <id>:<b64 title>:<b64 message>
        try {
            String[] parts = rest.split(":");
            if (parts.length < 3) {
                return;
            }
            final int id = Integer.parseInt(parts[0]);
            final String title = new String(
                    Base64.decode(parts[1], Base64.NO_WRAP),
                    StandardCharsets.UTF_8);
            final String message = new String(
                    Base64.decode(parts[2], Base64.NO_WRAP),
                    StandardCharsets.UTF_8);
            mainHandler.post(() -> showActivationAlert(
                    id, title, message));
            appendLog("Activation service requires owner action; private message logged=false.");
        } catch (Exception error) {
            appendLog(getString(R.string.could_not_parse_activation_alert)
                    + safeMessage(error));
        }
    }

    private synchronized void showActivationAlert(int id, String title, String message) {
        if (!mayShowActivationInput()) return;
        activeChallengeId = id;
        setupState.putInt("activationChallengeId", id);
        setupState.putString("activationTitle", title);
        setupState.putString("activationMessage", message);
        setupState.putStringArray("activationFields", new String[0]);
        phase("ACTIVATION_INPUT");
        publishSetup();
    }
    private synchronized void showActivationChallenge(int id, String page, String account, String[] fields,
            boolean retry) {
        if (!mayShowActivationInput()) return;
        for (String field : fields) if (!field.matches("[a-zA-Z0-9_-]{1,64}")) return;
        activeChallengeId = id;
        setupState.putInt("activationChallengeId", id);
        setupState.putString("activationTitle", "Apple Watch activation");
        setupState.putString("activationMessage", retry ? "Apple rejected the previous attempt. Check your credentials."
                : "Enter the owner credentials requested by Apple.");
        setupState.putStringArray("activationFields", fields);
        phase("ACTIVATION_INPUT");
        publishSetup();
    }
    private boolean mayShowActivationInput() {
        return running && !stopping && progress.phase().ordinal()
                <= SetupProgressState.Phase.ACTIVATION_INPUT.ordinal();
    }
    private synchronized boolean activationResponse(android.os.Bundle args) {
        int id = args.getInt("challengeId", -1);
        BufferedWriter input = rootInput;
        if (id != activeChallengeId || id < 0 || input == null || stopping) return false;
        String action = args.getString("action");
        String line;
        if ("cancel".equals(action)) line = "ACTIVATION-CANCEL-V1:" + id;
        else if ("retry".equals(action)) line = "ACTIVATION-RETRY-V1:" + id;
        else if ("submit".equals(action)) {
            String[] fields = setupState.getStringArray("activationFields");
            if (fields == null || fields.length == 0) return false;
            StringBuilder builder = new StringBuilder("ACTIVATION-CREDENTIALS-V1:").append(id);
            android.os.Bundle values = args.getBundle("credentials");
            if (values == null) return false;
            for (String field : fields) {
                String value = values.getString(field);
                if (value == null || value.isEmpty() || value.length() > 4096) return false;
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                try { builder.append(':').append(field).append('=').append(Base64.encodeToString(utf8, Base64.NO_WRAP)); }
                finally { wipe(utf8); }
            }
            line = builder.toString();
        } else return false;
        try {
            synchronized (input) { input.write(line); input.write('\n'); input.flush(); }
            activeChallengeId = -1;
            setupState.remove("activationChallengeId");
            setupState.remove("activationTitle");
            setupState.remove("activationMessage");
            setupState.remove("activationFields");
            phase("ACTIVATING");
            return true;
        } catch (IOException error) { return false; }
    }

    private void requestStop(String source) {
        stopping = true;
        if (running) phase("STOPPING");
        StockBluetoothReconnectProbe probe =
                stockReconnectProbe;
        if (probe != null) {
            appendLog(getString(R.string.stock_ble_probe_stop_requested));
            stockReconnectProbe = null;
            probe.close();
            setRunning(false);
        }
        BufferedWriter input = rootInput;
        Process process = rootProcess;
        if (process == null) {
            return;
        }
        appendLog(getString(R.string.safe_stop_disconnect_requested_source) + source + ".");
        if (input != null) {
            try {
                synchronized (input) {
                    input.write("STOP\n");
                    input.flush();
                }
                return;
            } catch (IOException error) {
                appendLog(getString(R.string.could_not_send_stop) + safeMessage(error));
            }
        }
        process.destroy();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? getString(R.string.no_message) : message;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private synchronized void setRunning(boolean value) {
        running = value;
        if (!value) {
            stopping = false; pinRequired = false;
            setupState.remove("activationChallengeId");
            setupState.remove("activationConfirmed");
            setupState.remove("operationalEligible");
        }
        publishSetup();
        if (!value) mainHandler.post(() -> { if (!busy()) onIdle.run(); });
    }
    private synchronized void setPinEntryEnabled(boolean enabled) {
        if (enabled && progress.phase() != SetupProgressState.Phase.PIN_REQUIRED
                && !progress.observe(SetupProgressState.Phase.PIN_REQUIRED)) return;
        pinRequired = enabled;
        if (enabled) setupState.putString("setupPhase", "PIN_REQUIRED");
        publishSetup();
    }
    private String getString(int id, Object... arguments) { return context.getString(id, arguments); }

    private void appendLog(String line) {
        if (line.contains("LE SCAN ACTIVE:")) phase("DISCOVERING");
        else if (line.contains("WATCH TARGET LOCKED:")) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("product=(\\S+) watchOS=(\\S+)").matcher(line);
            if (match.find()) synchronized (this) {
                setupState.putString("discoveredProductType", match.group(1));
                setupState.putString("discoveredWatchOs", match.group(2));
            }
            phase("CONNECTING");
        } else if (line.contains("SMP LINK ENCRYPTION PASS:") || line.contains("LE link encryption active!")) phase("SECURITY");
        else if (line.contains("IDS CONTROL CHECKPOINT PASS:")) phase("IDS");
        else if (line.contains("INITIAL NANO SETUP START:")) phase("REGISTRY");
        else if (line.contains("INITIAL PROPERTIES CHECKPOINT PASS:")) phase("CONFIGURING");
        else if (line.contains("RESULT: FAIL") || line.startsWith("Startup error:")) phase("FAILED");
        journal.appendLog(line);
    }
    private void displayLogEntry(String entry) { journal.displayLogEntry(entry); }
    private void copyLog() { journal.copyLog(); }
}
