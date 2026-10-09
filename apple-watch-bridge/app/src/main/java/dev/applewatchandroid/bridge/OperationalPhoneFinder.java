package dev.applewatchandroid.bridge;

import android.os.SystemClock;
import android.util.Base64;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;

/** Owns phone effects and durable duplicate claims; no HAL lifetime ownership. */
final class OperationalPhoneFinder implements AutoCloseable {
    private final OperationalSessionAccess host;

    OperationalPhoneFinder(OperationalSessionAccess host, Runnable onAlarmChanged) {
        this.host = host;
        try { phoneClaims = new FindMyPhoneClaims(new FindMyPhoneClaimStore(host.context())); }
        catch (IOException | RuntimeException unavailable) {
            log("PHONE FIND claims unavailable; phone effects refused.");
        }
        BridgeIpcDispatcher.getInstance().observePhoneFind(null);
        phoneAlarm = new FindMyPhoneAlarm(host.context(), host.handler(), this::log, (playing, reason) -> {
            PhoneFindObservation previous = BridgeIpcDispatcher.getInstance().phoneFindObservation();
            if (!playing && previous != null && previous.active()) {
                BridgeIpcDispatcher.getInstance().observePhoneFind(previous.stopped(System.currentTimeMillis(), reason));
            }
            onAlarmChanged.run();
        });
    }
    boolean active() { return phoneAlarm.active(); }
    void stop(String reason) { phoneAlarm.stop(reason); }
    @Override public void close() { phoneAlarm.close(); }
    private void log(String line) { host.log(line); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private FindMyPhoneAlarm phoneAlarm;

    private FindMyPhoneClaims phoneClaims;

    void handlePhonePingRequest(String encoded) {
        byte[] bytes = null;
        try {
            if (encoded.length() > 108) throw new IllegalArgumentException();
            bytes = Base64.decode(encoded, Base64.NO_WRAP);
            var request = FindMyPhoneIpcCodec.decodeRequest(bytes);
            BufferedWriter owner = host.input();
            String pairing = host.pairing();
            host.handler().post(() -> {
                if (!phonePingAllowed(request, owner) || pairing == null) {
                    log("PHONE FIND request expired/disconnected; no effect."); return;
                }
                boolean played = executePhonePing(request, pairing, false);
                sendPhonePingResult(request, owner, played);
            });
        } catch (IllegalArgumentException malformed) {
            log("PHONE FIND request rejected: malformed private child IPC; payload logged=false.");
        } finally { wipe(bytes); }
    }

    boolean phonePingAllowed(FindMyPhoneIpcCodec.Request request, BufferedWriter owner) {
        return !host.stopping() && host.connected() && owner != null && host.input() == owner
                && request.epoch().equals(host.epoch()) && SystemClock.elapsedRealtime() < request.deadline()
                && request.body().freshAt(System.currentTimeMillis());
    }

    boolean executePhonePing(FindMyPhoneIpcCodec.Request request, String pairing, boolean localProbe) {
        if (phoneClaims == null) return false;
        try {
            var claim = phoneClaims.claim(pairing, request, System.currentTimeMillis());
            if (!claim.fresh()) {
                log("PHONE FIND duplicate claim: resultKnown=" + (claim.previousResult() != null)
                        + " localProbe=" + localProbe + "; phone effect not replayed.");
                return Boolean.TRUE.equals(claim.previousResult());
            }
            boolean played = phoneAlarm.start(request.behavior());
            try { phoneClaims.complete(claim, played); }
            catch (IOException failed) { log("PHONE FIND result persistence unavailable; durable pending claim retained."); }
            log("PHONE FIND effect result: type=" + request.type() + " behavior=" + request.behavior()
                    + " didPlay=" + played + " localProbe=" + localProbe + "; not inferred from IDS ACK.");
            // A busy refusal or native None must not replace the description of an existing signal.
            if (!phoneAlarm.active() || (played && request.behavior() != 4)) {
                BridgeIpcDispatcher.getInstance().observePhoneFind(new PhoneFindObservation(
                        phoneAlarm.active(), request.behavior(), localProbe, played, System.currentTimeMillis(), null));
            }
            return played;
        } catch (IOException | RuntimeException failed) {
            log("PHONE FIND claim rejected; no replay permitted: " + failed.getClass().getSimpleName());
            return false;
        }
    }

    private void sendPhonePingResult(FindMyPhoneIpcCodec.Request request, BufferedWriter owner, boolean played) {
        try {
            host.commands().execute(() -> {
                byte[] frame = FindMyPhoneIpcCodec.encode(new FindMyPhoneIpcCodec.Result(
                        request.epoch(), request.messageId(), request.type(), played));
                try {
                    synchronized (owner) {
                        if (!phonePingAllowed(request, owner)) return;
                        owner.write(FindMyPhoneIpcCodec.RESULT_PREFIX);
                        owner.write(Base64.encodeToString(frame, Base64.NO_WRAP));
                        owner.write('\n'); owner.flush();
                    }
                } catch (IOException failure) { log("PHONE FIND result delivery unconfirmed; effect not replayed."); }
                finally { wipe(frame); }
            });
        } catch (RejectedExecutionException full) { log("PHONE FIND result queue full; effect not replayed."); }
    }

}
