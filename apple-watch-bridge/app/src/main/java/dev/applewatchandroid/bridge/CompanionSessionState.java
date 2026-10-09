package dev.applewatchandroid.bridge;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** Public projection only: pairing keys, accounts, PIN and credentials never enter IPC. */
final class CompanionSessionState {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static Bundle identity = new Bundle();
    private static Bundle setup = new Bundle();
    private static String connection = "DISCONNECTED";
    private static String journal = "";
    private static boolean scheduled;

    static void add(Runnable listener) { LISTENERS.add(listener); }
    static void remove(Runnable listener) { LISTENERS.remove(listener); }
    static synchronized Bundle snapshot() {
        Bundle result = new Bundle(identity);
        result.putAll(setup);
        result.putString("connectionStatus", connection);
        result.putString("bridgeJournal", journal);
        return result;
    }
    static synchronized void connection(String status) { connection = status; changed(); }
    static synchronized void setup(Bundle state) { setup = new Bundle(state); changed(); }
    static synchronized void journal(CharSequence value) {
        String text = value.toString();
        journal = text.substring(Math.max(0, text.length() - 24_000));
        changed();
    }
    static synchronized void log(String entry) {
        // The operational transport is very noisy. Keep a bounded, redacted diagnostic tail.
        if (entry.contains("HCI COMPLETED PACKETS") || entry.contains("[ERTM")
                || entry.contains(" ERTM ") || entry.contains("payloadBytes=2; bytes logged=false")
                || entry.contains("HCI ACL DATA") || entry.contains("[NormalLink]")) return;
        journal(journal + entry + "\n");
    }
    private static synchronized void changed() {
        if (scheduled) return;
        scheduled = true;
        MAIN.postDelayed(() -> {
            synchronized (CompanionSessionState.class) { scheduled = false; }
            for (Runnable listener : LISTENERS) listener.run();
        }, 100);
    }
    static void refreshIdentity(Context source) {
        Context context = source.getApplicationContext();
        IO.execute(() -> {
            Bundle result = new Bundle();
            result.putBoolean("identityKnown", true);
            PairingSessionRecord record = null;
            try {
                java.io.File file = new java.io.File(context.getFilesDir(), "pairing-session.v2.aesgcm");
                if (!file.exists()) {
                    result.putBoolean("hasPair", false);
                    synchronized (CompanionSessionState.class) { identity = result; changed(); }
                    return;
                }
                record = new BridgeIdentityStore(context, ignored -> {}).readRecord();
                String pair = OperationalSessionPolicy.pairingId(record);
                result.putBoolean("hasPair", true);
                result.putString("pairId", pair);
                result.putString("pairState", record.state().name());
                if (record.peerProductType() != null) result.putString("productType", record.peerProductType());
                if (record.peerBuildVersion() != null) result.putString("buildVersion", record.peerBuildVersion());
                String confirmed = context.getSharedPreferences("watch_operating_mode", Context.MODE_PRIVATE)
                        .getString("owner_confirmed_pairing", null);
                result.putBoolean("operationalEligible", OperationalSessionPolicy.mayUseOperationalMode(
                        record.state(), pair, confirmed, false, record.hasObservedSetupEvidence()));
                result.putBoolean("activationConfirmed", record.state().wireValue()
                        >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue());
            } catch (java.io.FileNotFoundException absent) {
                result.putBoolean("hasPair", false);
            } catch (Exception unavailable) {
                // A corrupt/locked record must not look like an empty store and allow a new pair.
                result.putBoolean("identityKnown", false);
                result.putString("identityError", "Saved pairing could not be read");
            } finally { if (record != null) record.destroy(); }
            synchronized (CompanionSessionState.class) { identity = result; changed(); }
        });
    }
}
