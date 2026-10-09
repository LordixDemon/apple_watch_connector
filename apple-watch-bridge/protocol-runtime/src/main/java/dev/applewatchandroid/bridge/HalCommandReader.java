package dev.applewatchandroid.bridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/** Root stdin framing and command dispatch; never owns the HCI HAL. */
final class HalCommandReader {
    static void start(
            AtomicBoolean stopRequested,
            BlockingQueue<char[]> pinInputs,
            BlockingQueue<Boolean> bondStoreResults,
            BlockingQueue<Boolean> pairingSessionStoreResults,
            BlockingQueue<byte[]> localIdentityRecords,
            BlockingQueue<byte[]> localIdsPublicRecords,
            BlockingQueue<byte[]> restoredPairingSessionRecords,
            BlockingQueue<byte[]> opticalCodes,
            BlockingQueue<OutboundAppMessage> outboundAppMessages) {
        Thread thread = new Thread(() -> {
            BridgeIpcDispatcher.getInstance().setOutboundSink((topic, protobufType, payload) -> {
                offerOutbound(outboundAppMessages, new OutboundAppMessage(topic, protobufType, payload));
            });
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("OPTICAL_PAIRING_CODE_V1:")) {
                        byte[] code = null;
                        try {
                            String encoded = line.substring("OPTICAL_PAIRING_CODE_V1:".length());
                            if (encoded.length() != 148) throw new IllegalArgumentException();
                            code = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
                            try (OpticalPairingCode ignored = OpticalPairingCode.parse(code)) {
                                if (opticalCodes.offer(code)) code = null;
                            }
                        } catch (IllegalArgumentException invalid) { log("Optical input rejected; bytes logged=false."); }
                        finally { if (code != null) Arrays.fill(code, (byte) 0); }
                        continue;
                    }
                    if ("COMPANION_DISCOVERY_V1".equals(line)) {
                        COMPANION_DISCOVERY.set(true);
                        continue;
                    }
                    if (line.startsWith("SELECT_DISCOVERED_WATCH_V1:")) {
                        try {
                            String token = line.substring("SELECT_DISCOVERED_WATCH_V1:".length());
                            if (!java.util.UUID.fromString(token).toString().equals(token)) throw new IllegalArgumentException();
                            COMPANION_WATCH_SELECTIONS.offer(token);
                        } catch (IllegalArgumentException invalid) { log("Discovery selection rejected; invalid token."); }
                        continue;
                    }
                    if(line.startsWith(HealthOutboundIpcCodec.PREFIX)) {
                        byte[] frame=null,payload=null;
                        try {
                            String encoded=line.substring(HealthOutboundIpcCodec.PREFIX.length());
                            if(encoded.length()>((HealthOutboundIpcCodec.MAX_FRAME+2)/3)*4)throw new IllegalArgumentException();
                            frame=BridgeBase64.decode(encoded,BridgeBase64.NO_WRAP);
                            try(var request=HealthOutboundIpcCodec.decode(frame)) {
                                payload=request.encrypted();
                                var outgoing=new OutboundAppMessage(IdsApplicationRoute.HEALTH_SYNC_SERVICE,1,payload,true,null,request.header);
                                if(!offerOutbound(outboundAppMessages,outgoing)) {
                                    Arrays.fill(outgoing.payload,(byte)0);
                                    publishHealthSendStatus(request.header,BridgeCommandCodec.Stage.REJECTED);
                                }
                            }
                        } catch(IllegalArgumentException invalid) { log("HEALTH SEND rejected: invalid private ciphertext/context; values logged=false."); }
                        finally { if(frame!=null)Arrays.fill(frame,(byte)0);if(payload!=null)Arrays.fill(payload,(byte)0); }
                        continue;
                    }
                    if (line.startsWith(FindMyPhoneIpcCodec.RESULT_PREFIX)) {
                        byte[] frame = null;
                        try {
                            String encoded = line.substring(FindMyPhoneIpcCodec.RESULT_PREFIX.length());
                            if (encoded.length() > 108) throw new IllegalArgumentException();
                            frame = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
                            if (!FIND_MY_PHONE_RESULTS.offer(FindMyPhoneIpcCodec.decodeResult(frame))) {
                                log("FIND MY PHONE result queue full; reply delivery unconfirmed.");
                            }
                        } catch (IllegalArgumentException invalid) {
                            log("FIND MY PHONE result rejected: malformed private IPC.");
                        } finally { if (frame != null) Arrays.fill(frame, (byte) 0); }
                        continue;
                    }
                    if (line.startsWith(BridgeCommandCodec.REQUEST_PREFIX)) {
                        byte[] frame = null;
                        try {
                            String encoded = line.substring(BridgeCommandCodec.REQUEST_PREFIX.length());
                            if (encoded.length() > ((BridgeCommandCodec.MAX_FRAME + 2) / 3) * 4) throw new IllegalArgumentException();
                            frame = BridgeBase64.decode(encoded, BridgeBase64.NO_WRAP);
                            queueStructuredRequest(BridgeCommandCodec.decodeRequest(frame), outboundAppMessages);
                        } catch (IllegalArgumentException invalid) {
                            log("OPERATIONAL REQUEST rejected: malformed envelope; values logged=false.");
                        } finally { if (frame != null) Arrays.fill(frame, (byte) 0); }
                        continue;
                    }
                    if ("STOP".equalsIgnoreCase(line.trim())) {
                        stopRequested.set(true);
                        log("STOP RECEIVED: cleanup will run at the next state boundary.");
                        return;
                    }
                    if ("BOND-STORED".equals(line)) {
                        bondStoreResults.offer(true);
                        continue;
                    }
                    if ("BOND-STORE-FAILED".equals(line)) {
                        bondStoreResults.offer(false);
                        continue;
                    }
                    if ("PAIRING-SESSION-STORED".equals(line)) {
                        pairingSessionStoreResults.offer(true);
                        continue;
                    }
                    if ("PAIRING-SESSION-STORE-FAILED".equals(line)) {
                        pairingSessionStoreResults.offer(false);
                        continue;
                    }
                    if (line.startsWith(RESTORED_PAIRING_SESSION_PREFIX)) {
                        byte[] serialized = null;
                        PairingSessionRecord parsed = null;
                        try {
                            serialized = BridgeBase64.decode(
                                    line.substring(
                                            RESTORED_PAIRING_SESSION_PREFIX.length()),
                                    BridgeBase64.NO_WRAP);
                            parsed = PairingSessionRecord.parse(serialized);
                            if (!restoredPairingSessionRecords.offer(serialized)) {
                                log("RESTORED PAIRING SESSION INPUT REJECTED: a "
                                        + "record is already queued; "
                                        + "bytes logged=false.");
                            } else {
                                serialized = null;
                                log("RESTORED PAIRING SESSION INPUT RECEIVED: "
                                        + "state=" + parsed.state()
                                        + " transitions=" + parsed.transitionCounter());
                            }
                        } catch (Exception error) {
                            log("RESTORED PAIRING SESSION INPUT REJECTED: malformed "
                                    + "record: " + safeMessage(error));
                        } finally {
                            if (parsed != null) {
                                parsed.destroy();
                            }
                            if (serialized != null) {
                                Arrays.fill(serialized, (byte) 0);
                            }
                        }
                        continue;
                    }
                    if (line.startsWith(LOCAL_IDS_PUBLIC_PREFIX)) {
                        byte[] serialized = null;
                        try {
                            if (line.length() > LOCAL_IDS_PUBLIC_PREFIX.length() + 8192) {
                                throw new IllegalArgumentException("IDS public bundle is too large");
                            }
                            serialized = BridgeBase64.decode(line.substring(LOCAL_IDS_PUBLIC_PREFIX.length()), BridgeBase64.NO_WRAP);
                            java.util.Map<String, byte[]> keys = IdsMessageProtectionIdentity.parsePublicBundle(serialized);
                            IdsMessageProtectionIdentity.wipeValues(keys);
                            if (!localIdsPublicRecords.offer(serialized)) {
                                throw new IllegalStateException("IDS public bundle already queued");
                            }
                            serialized = null;
                            log("LOCAL IDS PUBLIC IDENTITY INPUT RECEIVED: valid SecMP A/C/D public identities; key bytes logged=false.");
                        } catch (RuntimeException invalid) {
                            log("LOCAL IDS PUBLIC IDENTITY INPUT REJECTED; key bytes logged=false.");
                        } finally { if (serialized != null) Arrays.fill(serialized, (byte) 0); }
                        continue;
                    }
                    if (line.startsWith(LOCAL_IDENTITY_PREFIX)) {
                        byte[] serialized = null;
                        BluetoothLocalIdentity parsed = null;
                        try {
                            serialized = BridgeBase64.decode(
                                    line.substring(
                                            LOCAL_IDENTITY_PREFIX.length()),
                                    BridgeBase64.NO_WRAP);
                            parsed = BluetoothLocalIdentity.parse(serialized);
                            if (!localIdentityRecords.offer(serialized)) {
                                log("LOCAL IDENTITY INPUT REJECTED: a "
                                        + "record is already queued; "
                                        + "bytes logged=false.");
                            } else {
                                serialized = null;
                                log("LOCAL IDENTITY INPUT RECEIVED: "
                                        + "validated static-random "
                                        + "address+IRK; bytes logged=false.");
                            }
                        } catch (IllegalArgumentException error) {
                            log("LOCAL IDENTITY INPUT REJECTED: malformed "
                                    + "record; bytes logged=false.");
                        } finally {
                            if (parsed != null) {
                                parsed.destroy();
                            }
                            if (serialized != null) {
                                Arrays.fill(serialized, (byte) 0);
                            }
                        }
                        continue;
                    }
                    if ("PING_WATCH".equals(line.trim())) {
                        boolean queued = offerOutbound(outboundAppMessages, new OutboundAppMessage(
                                IdsApplicationRoute.FIND_MY_LOCAL_SERVICE,
                                FindMyLocalDeviceCodec.TYPE_PLAY_SOUND,
                                new byte[0]));
                        log("COMMAND INPUT: PING_WATCH queued=" + queued);
                        continue;
                    }
                    if ("CLOSE_IKE_SESSION".equals(line.trim())) {
                        PENDING_CLOSE_IKE_SESSION.set(true);
                        log("COMMAND INPUT: CLOSE_IKE_SESSION queued; transient Class-C/D SAs only.");
                        continue;
                    }
                    if ("DISCOVER_NATIVE_SNAPSHOTS".equals(line.trim())) {
                        if (PENDING_NATIVE_SNAPSHOT_DISCOVERY.get() == null) {
                            // This command-reader thread never processes Bluetooth packets.
                            try {
                                boolean queued = PENDING_NATIVE_SNAPSHOT_DISCOVERY.compareAndSet(null,
                                        NativeApplicationServiceDiscovery.createSnapshotDiscovery());
                                log("COMMAND INPUT: DISCOVER_NATIVE_SNAPSHOTS queued=" + queued
                                        + "; discovery only; keys logged=false.");
                            } catch (java.security.GeneralSecurityException unavailable) {
                                log("COMMAND INPUT: native discovery identity unavailable.");
                            }
                        }
                        continue;
                    }
                    if ("PROBE_IDS_KEYS".equals(line.trim())) {
                        PENDING_IDS_KEY_PROBE.set(true);
                        log("COMMAND INPUT: PROBE_IDS_KEYS queued; normal/cloud Class-C/D NoOp exchange.");
                        continue;
                    }
                    if ("OPEN_IDS_CONTROL".equals(line.trim())) {
                        PENDING_IDS_CONTROL_OPEN.set(true);
                        log("COMMAND INPUT: OPEN_IDS_CONTROL queued; signed Class-D cloud request.");
                        continue;
                    }
                    if (line.startsWith("SEED_STALE_FLOW:")) {
                        // SEED_STALE_FLOW:<CLASS_D|CLASS_C>:<localPort>:<remotePort>:<peerAck>:<peerTsval>:<ageMs>
                        // Resets a Watch flow observed in an earlier log:
                        // seq = the flow's last ackNum (= Watch RCV.NXT),
                        // tsval = its last TS option value, age = ms since
                        // that observation. The reset is extrapolated at
                        // fire time, so the age only needs to be roughly right.
                        try {
                            String[] fields =
                                    line.substring("SEED_STALE_FLOW:".length())
                                            .split(":");
                            OrdinaryIkeAuth.DataClass seedClass =
                                    OrdinaryIkeAuth.DataClass.valueOf(
                                            fields[0].trim());
                            int seedLocalPort =
                                    Integer.parseInt(fields[1].trim());
                            int seedRemotePort =
                                    Integer.parseInt(fields[2].trim());
                            long seedPeerAck =
                                    Long.parseUnsignedLong(fields[3].trim());
                            long seedPeerTimestamp =
                                    Long.parseLong(fields[4].trim());
                            long seedAgeMs =
                                    Long.parseLong(fields[5].trim());
                            PENDING_STALE_FLOW_SEEDS.offer(
                                    new PendingStaleFlowSeed(
                                            seedClass,
                                            seedLocalPort,
                                            seedRemotePort,
                                            seedPeerAck,
                                            seedPeerTimestamp,
                                            System.currentTimeMillis()
                                                    - seedAgeMs));
                            log("COMMAND INPUT: queued SEED_STALE_FLOW dc="
                                    + seedClass + " "
                                    + seedRemotePort + "->" + seedLocalPort
                                    + " seq=" + seedPeerAck);
                        } catch (RuntimeException malformed) {
                            log("COMMAND INPUT: invalid SEED_STALE_FLOW: "
                                    + malformed.getMessage());
                        }
                        continue;
                    }
                    if (line.startsWith("SET_ACTIVE_FACE:")) {
                        log("COMMAND INPUT: SET_ACTIVE_FACE refused: native collection protocol required.");
                        continue;
                    }
                    if (line.startsWith(WatchSettingsCodec.COMMAND_PREFIX)) {
                        byte[] settingPayload = null;
                        try {
                            settingPayload = WatchSettingsCodec.encode(WatchSettingsCodec.parseCommand(line), System.currentTimeMillis());
                            boolean queued = offerOutbound(outboundAppMessages, new OutboundAppMessage(
                                    IdsApplicationRoute.PREFERENCE_SYNC_SERVICE, 0, settingPayload));
                            log("COMMAND INPUT: native Watch setting queued=" + queued + "; values logged=false.");
                        } catch (IllegalArgumentException invalid) {
                            log("COMMAND INPUT: invalid native Watch setting refused.");
                        } finally { if (settingPayload != null) Arrays.fill(settingPayload, (byte) 0); }
                        continue;
                    }
                    if (line.startsWith("SEND_BULLETIN:")) {
                        byte[] payload = null;
                        try {
                            if (line.length() > 180_000) throw new IllegalArgumentException("Bulletin exceeds limit");
                            payload = BridgeBase64.decode(
                                    line.substring("SEND_BULLETIN:".length()).trim(),
                                    BridgeBase64.NO_WRAP);
                            boolean queued = offerOutbound(outboundAppMessages, new OutboundAppMessage(
                                    IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE,
                                    BulletinDistributorCodec.TYPE_ADD_BULLETIN,
                                    payload));
                            log("COMMAND INPUT: SEND_BULLETIN queued=" + queued + " payloadLen=" + payload.length);
                        } catch (Exception e) {
                            log("COMMAND INPUT: invalid SEND_BULLETIN: " + e.getMessage());
                        } finally { if (payload != null) Arrays.fill(payload, (byte) 0); }
                        continue;
                    }
                    if (line.startsWith("SEND_APP_DATA:")) {
                        byte[] payload = null;
                        try {
                            if (line.length() > 48 * 1024) throw new IllegalArgumentException("Data command exceeds limit");
                            String[] parts = line.substring("SEND_APP_DATA:".length()).split(":", 2);
                            if (parts.length != 2) throw new IllegalArgumentException("Data command is incomplete");
                            IdsApplicationRoute.forTopic(parts[0]);
                            payload = BridgeBase64.decode(parts[1], BridgeBase64.NO_WRAP);
                            if (payload.length > 32 * 1024) throw new IllegalArgumentException("Data payload exceeds limit");
                            OutboundAppMessage outgoing = new OutboundAppMessage(parts[0], -1, payload, true);
                            if (!outboundAppMessages.offer(outgoing)) {
                                Arrays.fill(outgoing.payload, (byte)0);
                                throw new IllegalStateException("Outbound queue is full");
                            }
                            log("COMMAND INPUT: queued SEND_APP_DATA topic=" + parts[0] + "; values logged=false.");
                        } catch (Exception invalid) {
                            log("COMMAND INPUT: invalid SEND_APP_DATA; values logged=false.");
                        } finally { if (payload != null) Arrays.fill(payload, (byte)0); }
                        continue;
                    }
                    if (line.startsWith("SEND_APP_PROTOBUF:")) {
                        byte[] payload = null;
                        try {
                            if (line.length() > 48 * 1024) throw new IllegalArgumentException("Protobuf command exceeds limit");
                            String[] parts = line.substring("SEND_APP_PROTOBUF:".length()).split(":", 3);
                            if (parts.length == 3) {
                                String topic = parts[0];
                                int pType = Integer.parseInt(parts[1]);
                                IdsApplicationRoute.forTopic(topic);
                                if (pType < 0 || pType > 65535) throw new IllegalArgumentException("Invalid protobuf type");
                                payload = BridgeBase64.decode(parts[2], BridgeBase64.NO_WRAP);
                                if (payload.length > 32 * 1024) throw new IllegalArgumentException("Protobuf payload exceeds limit");
                                boolean queued = offerOutbound(outboundAppMessages, new OutboundAppMessage(topic, pType, payload));
                                log("COMMAND INPUT: SEND_APP_PROTOBUF queued=" + queued + " topic=" + topic + " type=" + pType);
                            }
                        } catch (Exception e) {
                            log("COMMAND INPUT: invalid SEND_APP_PROTOBUF: " + e.getMessage());
                        } finally { if (payload != null) Arrays.fill(payload, (byte) 0); }
                        continue;
                    }
                    if (line.startsWith(
                            ActivationChallengeManager.CREDENTIALS_LINE_PREFIX)) {
                        ActivationChallengeManager.acceptCredentialsLine(
                                line);
                        continue;
                    }
                    if (line.startsWith(
                            ActivationChallengeManager.RETRY_LINE_PREFIX)) {
                        ActivationChallengeManager.acceptRetryLine(
                                line);
                        continue;
                    }
                    if (line.startsWith(
                            ActivationChallengeManager.CANCEL_LINE_PREFIX)) {
                        ActivationChallengeManager.acceptCancelLine(
                                line);
                        continue;
                    }
                    if ("FINISH_SETUP".equals(line.trim())) {
                        log("COMMAND INPUT REJECTED: setup completion requires the authenticated protocol flow; force-finish is disabled.");
                        continue;
                    }
                    if (line.startsWith("SYNC_PROGRESS:")) {
                        String[] parts = line.trim().split(":");
                        try {
                            requestSyncProgress(
                                    Double.parseDouble(parts[1]),
                                    Integer.parseInt(parts[2]));
                            log("COMMAND INPUT: SYNC_PROGRESS queued (PBBridge type 19).");
                        } catch (RuntimeException badArgs) {
                            log("COMMAND INPUT REJECTED: SYNC_PROGRESS:<progress 0..1>:<state>");
                        }
                        continue;
                    }
                    if ("PUBLISH_PAIRED_SYNC".equals(line.trim())) {
                        requestPublishPairedSync();
                        log("COMMAND INPUT: PUBLISH_PAIRED_SYNC queued; diagnostic republish lands at the next coordinator poll.");
                        continue;
                    }
                    if ("FORCE_ACTIVATION_CONFIRMED".equals(line.trim())) {
                        requestForceActivationConfirmed();
                        log("COMMAND INPUT: FORCE_ACTIVATION_CONFIRMED queued; checkpoint lands at the next coordinator poll.");
                        continue;
                    }
                    if ("REDRIVE_ACTIVATION".equals(line.trim())) {
                        requestRedriveActivation();
                        log("COMMAND INPUT: REDRIVE_ACTIVATION queued; durable activation checkpoint is cleared at the next coordinator poll and CanBeginActivation is re-asserted.");
                        continue;
                    }
                    if ("RETRY_ACTIVATION".equals(line.trim())) {
                        requestRetryActivation();
                        log("COMMAND INPUT: RETRY_ACTIVATION queued; PBBridge RetryActivation (message 15) is sent at the next coordinator poll, forcing the Watch activation state machine to Idle and re-starting activation.");
                        continue;
                    }
                    if ("FORCE_SETUP_OBSERVED".equals(line.trim())) {
                        requestForceSetupObserved();
                        log("COMMAND INPUT: FORCE_SETUP_OBSERVED queued; the consumed gizmoDidFinishActivating evidence is replayed at the next coordinator poll.");
                        continue;
                    }
                    if (line.length()
                            == 4 + AppleWatchPairingCrypto.PIN_LENGTH
                            && line.startsWith("PIN:")) {
                        char[] pin = new char[
                                AppleWatchPairingCrypto.PIN_LENGTH];
                        boolean valid = true;
                        for (int index = 0;
                                index < pin.length;
                                index++) {
                            char digit = line.charAt(index + 4);
                            if (digit < '0' || digit > '9') {
                                valid = false;
                                break;
                            }
                            pin[index] = digit;
                        }
                        if (!valid || !pinInputs.offer(pin)) {
                            Arrays.fill(pin, '\0');
                            log("PIN INPUT REJECTED: malformed or "
                                    + "a PIN is already queued.");
                        } else {
                            log("PIN INPUT RECEIVED: six digits accepted "
                                    + "in volatile memory; value "
                                    + "logged=false.");
                        }
                    } else if (!line.isBlank()) {
                        log("INPUT IGNORED: expected STOP, store "
                                + "acknowledgement, local identity, or a "
                                + "six-digit PIN command.");
                    }
                }
            } catch (IOException error) {
                log("STOP INPUT CLOSED: " + safeMessage(error));
            } finally {
                stopRequested.set(true);
                log("COMMAND OWNER CLOSED: root session must stop; pair retained.");
            }
        }, "apple-watch-stop-reader");
        thread.setDaemon(true);
        thread.start();
    }
}
