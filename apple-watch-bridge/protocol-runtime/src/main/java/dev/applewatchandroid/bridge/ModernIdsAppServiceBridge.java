package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static dev.applewatchandroid.bridge.ProtocolHost.*;


/** Outbound/inbound IDS application services: Wi-Fi, Health, ClockFace, Pigment/Monogram, FindMy, OTA, Sysdiagnose. */
final class ModernIdsAppServiceBridge {
    private final HalTransportSession session;
    private final ModernIdsSessionRunner runner;

    ModernIdsAppServiceBridge(HalTransportSession session, ModernIdsSessionRunner runner) {
        this.session = session;
        this.runner = runner;
    }

    void pollInitialWifiSync(int remoteCid) {
        if (session.stopRequested.get() || session.operationalPairingId != null || runner.activeIdsBridge == null
                || session.postCommit.activePostCommitCoordinator == null || session.activePairingSessionRecord == null) return;
        var setup = session.postCommit.activePostCommitCoordinator.snapshot();
        var context = new InitialWifiSyncWorker.Context(UUID.fromString(session.activePairingSessionRecord.nrUuid()),
                setup.generation, setup.activationConfirmed
                    && session.activePairingSessionRecord.state().wireValue() >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue(),
                setup.initialSyncPrepared && setup.pbBridgeNormalSent && setup.languageAndLocaleComplete,
                runner.modernIdsReady && session.connectionHandle != -1, false);
        long now = BridgeClock.elapsedRealtime();
        if (runner.initialWifiSync.request(context, now)) {
            log("WIFI SETUP AUTO: reading current validated phone network asynchronously; attempt="
                    + runner.initialWifiSync.attempts() + "; credentials logged=false.");
        }
        if (runner.activeIdsBridge.hasPendingApplicationFrames() || runner.activeIdsBridge.hasDeferredIpv6()) return;
        byte[] archive = runner.initialWifiSync.take(context, now);
        if (archive != null) {
            try {
                NormalLinkIdsSessionBridge.Output output = runner.activeIdsBridge.sendApplicationData(WifiNetworkSyncCodec.TOPIC, archive);
                try (output) {
                    for (var event : output.idsEvents()) {
                        if (event.type == IdsModernSessionCoordinator.EventType.DATA_SENT
                                && WifiNetworkSyncCodec.TOPIC.equals(event.topic)) runner.initialWifiMessageUuid = event.messageUuid;
                    }
                    if (runner.initialWifiMessageUuid == null) throw new IllegalStateException("No initial Wi-Fi IDS message allocated");
                    runner.initialWifiSync.sent();
                    log("WIFI SETUP AUTO IDS QUEUED: current WPA2/CCMP network, V2 ADD; Watch apply unconfirmed; credentials logged=false.");
                    runner.outputProcessor.processModernIdsOutput(output, remoteCid, "WIFI SETUP AUTO TX");
                    runner.flushPendingIdsFrames(remoteCid);
                }
            } catch (Exception failure) {
                runner.initialWifiSync.failed();
                log("WIFI SETUP AUTO unavailable: delivery failed; manual retry remains available after connection; credentials logged=false.");
            } finally { HalTransportSession.wipe(archive); }
        }
        if (!runner.initialWifiUnavailableLogged && runner.initialWifiSync.phase() == InitialWifiSyncWorker.Phase.UNAVAILABLE) {
            runner.initialWifiUnavailableLogged = true;
            log("WIFI SETUP AUTO unavailable: no supported validated current network or delivery failed; setup continues; credentials logged=false.");
        }
    }

    void drainOutboundAppMessages(int remoteCid) {
        OPERATIONAL_REQUESTS.expire(BridgeClock.elapsedRealtime());
        drainPhonePingReplies(remoteCid);
        advanceClockFaceDelta(remoteCid);
        if (runner.activeIdsBridge == null || session.outboundAppMessages.isEmpty()) {
            return;
        }
        OutboundAppMessage msg;
        while ((msg = session.outboundAppMessages.poll()) != null) {
            byte[] preparedBulletin = null;
            byte[] preparedPing = null;
            byte[] preparedFace = null;
            byte[] preparedWifi = null;
            byte[] preparedPigments = null;
            byte[] preparedMonogram = null;
            byte[] preparedUpdate = null;
            boolean retained=false;
            try {
                if(msg.health!=null) {
                    if(BridgeClock.elapsedRealtime()>=msg.health.deadline()) {
                        publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.EXPIRED);continue;
                    }
                    if(session.activePairingSessionRecord==null || session.operationalPairingId==null || !runner.operationalSnapshotPublished) {
                        throw new IllegalArgumentException("No activated Health sender context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(session.activePairingSessionRecord,session.operationalPairingId);
                    msg.health.requireContext(UUID.fromString(session.operationalPairingId),
                            UUID.fromString(session.activePairingSessionRecord.localIdsDeviceUuid()),
                            UUID.fromString(session.activePairingSessionRecord.peerIdsDeviceId()),runner.clockFaceObservationEpoch,BridgeClock.elapsedRealtime());
                    if(runner.idsDeviceInfoExchange==null || !runner.idsDeviceInfoExchange.ready()) {
                        retained=session.outboundAppMessages.offer(msg);
                        if(retained)return;
                        publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.REJECTED);continue;
                    }
                }
                if (msg.request != null && !OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                if (msg.request != null && ClockFaceDeltaCommand.matches(msg.request.command())) {
                    beginClockFaceDelta(msg.request, remoteCid);
                    continue;
                }
                byte[] outgoingPayload = msg.payload;
                if (SoftwareUpdateCodec.TOPIC.equals(msg.topic)) {
                    if (!runner.operationalSnapshotPublished || runner.softwareUpdate == null || session.activePairingSessionRecord == null)
                        throw new IllegalStateException("No authenticated update session");
                    OperationalSessionPolicy.requireMatchingActivatedPair(session.activePairingSessionRecord, session.operationalPairingId);
                    if (msg.request != null && !msg.request.epoch().equals(runner.clockFaceObservationEpoch))
                        throw new IllegalArgumentException("Update session changed");
                    String updateCommand = new String(msg.payload, java.nio.charset.StandardCharsets.US_ASCII);
                    preparedUpdate = runner.softwareUpdate.prepare(updateCommand, System.currentTimeMillis());
                    outgoingPayload = preparedUpdate;
                }
                if (msg.request != null && MonogramPreferenceCommand.matches(msg.request.command())) {
                    if (session.activePairingSessionRecord == null || session.operationalPairingId == null || !runner.operationalSnapshotPublished)
                        throw new IllegalArgumentException("No activated monogram sender context");
                    OperationalSessionPolicy.requireMatchingActivatedPair(session.activePairingSessionRecord, session.operationalPairingId);
                    if (!msg.request.epoch().equals(runner.clockFaceObservationEpoch)) throw new IllegalArgumentException("Monogram session changed");
                    preparedMonogram = monogramMirror(UUID.fromString(session.operationalPairingId)).prepare(
                        MonogramPreferenceCommand.parse(msg.request.command()), runner.clockFaceObservationEpoch,
                        msg.request.id(), System.currentTimeMillis());
                    outgoingPayload = preparedMonogram;
                    publishMonogramMirror();
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                }
                if (msg.request != null && PigmentPreferenceCommand.matches(msg.request.command())) {
                    if (session.activePairingSessionRecord == null || session.operationalPairingId == null || !runner.operationalSnapshotPublished) {
                        throw new IllegalArgumentException("No activated pigment sender context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(session.activePairingSessionRecord, session.operationalPairingId);
                    UUID pair = UUID.fromString(session.operationalPairingId);
                    if (!msg.request.epoch().equals(runner.clockFaceObservationEpoch)) throw new IllegalArgumentException("Pigment session changed");
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                    preparedPigments = pigmentMirror().prepare(
                            PigmentPreferenceCommand.parse(msg.request.command()), runner.clockFaceObservationEpoch,
                            msg.request.id(), System.currentTimeMillis());
                    outgoingPayload = preparedPigments;
                    publishPigmentMirror();
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                }
                boolean rebootRequest = msg.request != null
                        && NativeWatchReboot.COMMAND.equals(msg.request.command());
                if (rebootRequest) {
                    if (session.activePairingSessionRecord == null || session.operationalPairingId == null
                            || !runner.operationalSnapshotPublished) {
                        throw new IllegalStateException("No activated operational reboot context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(session.activePairingSessionRecord, session.operationalPairingId);
                    runner.watchReboot.begin(true, runner.clockFaceDelta != null && !runner.clockFaceDelta.terminal());
                    log("WATCH REBOOT TX: native NSS type=19 empty; ordinary reboot; no erase/setup; no automatic retry; completion unverified.");
                }
                if (msg.request != null && WifiNetworkSyncCodec.COMMAND.equals(msg.request.command())) {
                    if (session.activePairingSessionRecord == null || session.operationalPairingId == null || !runner.operationalSnapshotPublished) {
                        throw new IllegalArgumentException("No activated Wi-Fi sender context");
                    }
                    OperationalSessionPolicy.requireMatchingActivatedPair(session.activePairingSessionRecord, session.operationalPairingId);
                    preparedWifi = RootWifiNetworkReader.currentArchive();
                    if (!OPERATIONAL_REQUESTS.canSend(msg.request, BridgeClock.elapsedRealtime())) continue;
                    outgoingPayload = preparedWifi;
                    log("WIFI SYNC TX: current validated WPA2/CCMP network; V2 ADD only; credentials logged=false; Watch apply unconfirmed.");
                }
                boolean faceRequest = msg.request != null && msg.request.command().equals("REQUEST_FACE_COLLECTION");
                if (faceRequest) {
                    if (runner.clockFaceDelta != null && !runner.clockFaceDelta.terminal()) throw new IllegalStateException("Native face delta is busy");
                    if (session.activePairingSessionRecord == null) throw new IllegalStateException("No durable clockface pair");
                    byte[] header = clockFaceClient().reserveHeader(System.currentTimeMillis(), true);
                    try {
                        String unfinished = clockFaceReceiver().unfinishedSessionForRecovery();
                        preparedFace = ClockFaceSyncHeaderCodec.fullRequest(header, unfinished);
                        log("CLOCKFACE COLLECTION REQUEST: knownUnfinishedSession=" + (unfinished != null)
                                + "; native synchronization cancellation only, Watch data retained.");
                    }
                    finally { HalTransportSession.wipe(header); }
                    outgoingPayload = preparedFace;
                }
                if (!msg.dataFrame && IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(msg.topic)) {
                    if (!runner.findMySession.canQueue(BridgeClock.elapsedRealtime())) {
                        if (msg.request != null) OPERATIONAL_REQUESTS.failed(msg.request);
                        log("FIND MY LOCAL TX REJECTED: bounded reply window full; not sent.");
                        continue;
                    }
                    FindMyLocalDeviceCodec.requireSoundType(msg.protobufType);
                    preparedPing = new FindMyLocalDeviceCodec.PlaySoundRequest(
                            System.currentTimeMillis() / 1000.0, null).encode();
                    outgoingPayload = preparedPing;
                }
                if (!msg.dataFrame && IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(msg.topic)) {
                    BulletinDistributorCodec.MessageAndTrailer split = BulletinDistributorCodec.splitTrailer(msg.payload);
                    try {
                        preparedBulletin = runner.bulletinTransport.wrap(split.trailer != null ? split.messagePayload : msg.payload);
                        outgoingPayload = preparedBulletin;
                    } finally {
                        if (split.messagePayload != msg.payload) HalTransportSession.wipe(split.messagePayload);
                    }
                }
                log(String.format(
                        Locale.US,
                        "[OUTBOUND APP TX] sending topic=%s type=%d len=%d",
                        msg.topic,
                        msg.protobufType,
                        outgoingPayload != null ? outgoingPayload.length : 0));
                NormalLinkIdsSessionBridge.Output out =
                        msg.health!=null ? runner.activeIdsBridge.sendHealthSyncRequest(outgoingPayload,msg.health.message())
                                : preparedUpdate != null ? runner.activeIdsBridge.sendSoftwareUpdateRequest(outgoingPayload)
                                : rebootRequest ? runner.activeIdsBridge.sendWatchReboot()
                                : faceRequest ? runner.activeIdsBridge.sendClockFaceCollectionRequest(outgoingPayload)
                                : msg.dataFrame ? runner.activeIdsBridge.sendApplicationData(msg.topic, outgoingPayload)
                                : runner.activeIdsBridge.sendApplicationProtobuf(
                                msg.topic,
                                msg.protobufType,
                                outgoingPayload);
                if (preparedUpdate != null) {
                    for (var event : out.idsEvents()) {
                        if (event.type == IdsModernSessionCoordinator.EventType.DATA_SENT
                                && SoftwareUpdateCodec.TOPIC.equals(event.topic))
                            runner.softwareUpdate.queued(event.messageUuid, preparedUpdate);
                    }
                    publishSoftwareUpdate();
                }
                if(msg.health!=null) {
                    boolean allocated=false;
                    for(var event:out.idsEvents()) {
                        if(event.type==IdsModernSessionCoordinator.EventType.DATA_SENT
                                && IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)
                                && msg.health.message().equals(UUID.fromString(event.messageUuid))) { allocated=true;break; }
                    }
                    if(!allocated)throw new IllegalStateException("Health IDS UUID not allocated");
                    publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.IDS_QUEUED);
                    log("HEALTH SEND IDS queued: encrypted native request; ciphertext only; samples/anchors not committed.");
                }
                if (msg.request != null) {
                    String messageUuid = null;
                    for (IdsModernSessionCoordinator.SessionEvent event : out.idsEvents()) {
                        if (msg.topic.equals(event.topic)
                                && (msg.dataFrame && event.type == IdsModernSessionCoordinator.EventType.DATA_SENT
                                || event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                                && event.protobufType == msg.protobufType)) {
                            messageUuid = event.messageUuid;
                            break;
                        }
                    }
                    OPERATIONAL_REQUESTS.idsQueued(msg.request, msg.topic, messageUuid,
                            msg.request.command().equals("REQUEST_REGISTRY")
                                    ? NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                                    : msg.request.command().equals("REQUEST_DEVICE_ABOUT")
                                    ? NanoSystemSettingsDiagnostics.ABOUT_RESPONSE
                                    : msg.request.command().equals(SysdiagnoseArchiveInventory.COMMAND)
                                    ? SysdiagnoseArchiveInventory.LIST_RESULT
                                    : msg.request.command().equals("PING_WATCH")
                                    ? FindMyLocalDeviceCodec.TYPE_PLAY_SOUND
                                    : faceRequest ? ClockFaceSyncHeaderCodec.FULL_REQUEST : -1);
                }
                runner.outputProcessor.processModernIdsOutput(
                        out,
                        remoteCid,
                        "NORMAL OUTBOUND APP TX");
                runner.flushPendingIdsFrames(remoteCid);
            } catch (Exception err) {
                if(msg.health!=null)publishHealthSendStatus(msg.health,BridgeCommandCodec.Stage.FAILED);
                if (msg.request != null) OPERATIONAL_REQUESTS.failed(msg.request);
                log("[OUTBOUND APP TX ERROR] failed to send " + msg.topic + ": " + err.getMessage());
            } finally {
                HalTransportSession.wipe(preparedBulletin);
                HalTransportSession.wipe(preparedPing);
                HalTransportSession.wipe(preparedFace);
                HalTransportSession.wipe(preparedWifi);
                HalTransportSession.wipe(preparedPigments);
                HalTransportSession.wipe(preparedMonogram);
                HalTransportSession.wipe(preparedUpdate);
                if(!retained)HalTransportSession.wipe(msg.payload);
            }
        }
    }

    void publishSoftwareUpdate() {
        if (runner.softwareUpdate != null) {
            System.out.println(SoftwareUpdateSession.PREFIX + runner.softwareUpdate.json());
            System.out.flush();
        }
    }

    void publishClockFaceObservation(UUID epoch) {
        if (session.operationalPairingId == null || epoch == null) return;
        byte[] frame = null;
        try {
            ClockFaceCollection collection = clockFaceReceiver().snapshot();
            if (collection.observedAt <= 0) return;
            try {
                NativeFacePackageAccess.prepare(BridgePaths.files(), session.operationalPairingId);
            } catch (Exception unavailable) {
                log("CLOCKFACE EXPORT ACCESS unavailable; collection facts are still published.");
            }
            frame = ClockFaceObservationCodec.encode(ClockFaceObservationCodec.fromCollection(
                    UUID.fromString(session.operationalPairingId), epoch, collection));
            System.out.println(ClockFaceObservationCodec.PREFIX
                    + java.util.Base64.getEncoder().encodeToString(frame));
            System.out.flush();
            log("CLOCKFACE OBSERVATION published: faces=" + collection.configurations.size()
                    + " complete=" + collection.complete() + " observedAt=" + collection.observedAt);
        } catch (Exception invalid) {
            log("CLOCKFACE OBSERVATION unavailable; native session acceptance unchanged.");
        } finally { HalTransportSession.wipe(frame); }
    }

    void beginClockFaceDelta(BridgeCommandCodec.Request request, int remoteCid) throws Exception {
        if (session.operationalPairingId == null || runner.clockFaceObservationEpoch == null
                || !runner.clockFaceObservationEpoch.equals(request.epoch()) || runner.activeIdsBridge == null
                || runner.clockFaceDelta != null && !runner.clockFaceDelta.terminal()) throw new IllegalStateException("Native face delta is unavailable or busy");
        ClockFaceSyncReceiver receiver = clockFaceReceiver();
        if (receiver.unfinishedSessionForRecovery() != null) throw new IllegalStateException("Native collection receive is unfinished");
        ClockFaceCollection baseline = receiver.snapshot();
        long unixMs = System.currentTimeMillis();
        if (baseline.observedAt > unixMs || unixMs - baseline.observedAt > 300_000) {
            throw new IllegalStateException("Refresh the native face collection before a mutation");
        }
        ClockFaceDeltaPlan plan = ClockFaceDeltaCommand.parse(request.command()).plan(baseline,
                BridgePaths.files().resolve("face-imports"));
        try {
            String deltaSession = java.time.format.DateTimeFormatter.ofPattern("'P'uuuu-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(unixMs));
            runner.clockFaceDelta = new ClockFaceDeltaSession(UUID.fromString(session.operationalPairingId), request.epoch(),
                    clockFaceClient().remotePeerForDelta(), deltaSession, plan, BridgeClock.elapsedRealtime());
            runner.clockFaceDeltaRequest = request; runner.clockFaceDeltaReadRequested = false;
            log("CLOCKFACE DELTA begin: kind=" + plan.kind + " nativeSession=" + deltaSession
                    + "; delta only, application requires committed readback.");
            advanceClockFaceDelta(remoteCid);
        } catch (Exception failure) { plan.close(); clearClockFaceDeltaOnDisconnect(); throw failure; }
    }

    void advanceClockFaceDelta(int remoteCid) {
        if (runner.clockFaceDelta == null) return;
        runner.clockFaceDelta.tick(BridgeClock.elapsedRealtime());
        if (runner.activeIdsBridge == null || !runner.clockFaceDelta.epoch.equals(runner.clockFaceObservationEpoch)
                || !runner.clockFaceDelta.pair.toString().equals(session.operationalPairingId)) runner.clockFaceDelta.disconnected();
        try {
            var stage = runner.clockFaceDelta.stage();
            if (stage == ClockFaceDeltaSession.Stage.START_READY || stage == ClockFaceDeltaSession.Stage.BATCH_READY
                    || stage == ClockFaceDeltaSession.Stage.END_READY) {
                byte[] header = clockFaceClient().reserveHeader(System.currentTimeMillis(), false);
                byte[] packet = null;
                try {
                    packet = runner.clockFaceDelta.prepare(header, BridgeClock.elapsedRealtime());
                    try (var out = runner.activeIdsBridge.sendClockFaceDeltaRequest(packet)) {
                        String identifier = null;
                        for (var event : out.idsEvents()) if (event.type == IdsModernSessionCoordinator.EventType.DATA_SENT
                                && IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE.equals(event.topic)) { identifier = event.messageUuid; break; }
                        if (identifier == null) throw new IllegalStateException("Native delta has no IDS response identifier");
                        runner.clockFaceDelta.sent(UUID.fromString(identifier), BridgeClock.elapsedRealtime());
                        if (stage == ClockFaceDeltaSession.Stage.START_READY) OPERATIONAL_REQUESTS.idsQueued(runner.clockFaceDeltaRequest,
                                IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, identifier, ClockFaceSyncAccept.START);
                        log("CLOCKFACE DELTA TX: stage=" + runner.clockFaceDelta.stage() + " bytes=" + packet.length);
                        runner.outputProcessor.processModernIdsOutput(out, remoteCid, "CLOCKFACE DELTA TX"); runner.flushPendingIdsFrames(remoteCid);
                    }
                } finally { HalTransportSession.wipe(header); HalTransportSession.wipe(packet); }
            } else if (stage == ClockFaceDeltaSession.Stage.READBACK_WAIT && !runner.clockFaceDeltaReadRequested
                    && clockFaceReceiver().unfinishedSessionForRecovery() == null
                    && System.currentTimeMillis() >= clockFaceClient().earliestFullRequestMs()) {
                byte[] header = clockFaceClient().reserveHeader(System.currentTimeMillis(), true);
                byte[] packet = null;
                try {
                    packet = ClockFaceSyncHeaderCodec.fullRequest(header);
                    try (var out = runner.activeIdsBridge.sendClockFaceCollectionRequest(packet)) {
                        runner.clockFaceDeltaReadRequested = true;
                        runner.outputProcessor.processModernIdsOutput(out, remoteCid, "CLOCKFACE DELTA READBACK"); runner.flushPendingIdsFrames(remoteCid);
                        log("CLOCKFACE DELTA READBACK requested: no cancellation; current facts require native END.");
                    }
                } finally { HalTransportSession.wipe(header); HalTransportSession.wipe(packet); }
            }
        } catch (Exception failure) {
            if (runner.clockFaceDelta != null) runner.clockFaceDelta.disconnected();
            log("CLOCKFACE DELTA outcome unknown: " + safeMessage(failure));
        }
        finishClockFaceDeltaIfTerminal();
    }

    void finishClockFaceDeltaIfTerminal() {
        if (runner.clockFaceDelta == null || !runner.clockFaceDelta.terminal()) return;
        log("CLOCKFACE DELTA result: kind=" + runner.clockFaceDelta.kind() + " stage=" + runner.clockFaceDelta.stage()
                + "; APPLIED is emitted only after matching committed native collection.");
        if (runner.clockFaceDeltaRequest != null) {
            publishCommandStatus(new BridgeCommandCodec.Status(runner.clockFaceDeltaRequest.id(), runner.clockFaceDeltaRequest.epoch(),
                    runner.clockFaceDelta.stage() == ClockFaceDeltaSession.Stage.APPLIED
                            ? BridgeCommandCodec.Stage.NATIVE_FACE_APPLIED
                            : runner.clockFaceDelta.stage() == ClockFaceDeltaSession.Stage.REJECTED
                            ? BridgeCommandCodec.Stage.REJECTED : BridgeCommandCodec.Stage.UNKNOWN));
        }
        runner.clockFaceDelta.close(); runner.clockFaceDelta = null; runner.clockFaceDeltaRequest = null; runner.clockFaceDeltaReadRequested = false;
    }

    void clearClockFaceDeltaOnDisconnect() {
        if (runner.clockFaceDelta != null) { runner.clockFaceDelta.disconnected(); finishClockFaceDeltaIfTerminal(); }
    }

    ClockFaceSyncClient clockFaceClient() {
        if (session.activePairingSessionRecord == null) throw new IllegalStateException("No durable clockface pair");
        return new ClockFaceSyncClient(BridgePaths.files().resolve("clockface-client"),
                OperationalSessionPolicy.pairingId(session.activePairingSessionRecord));
    }

    ClockFaceSyncReceiver clockFaceReceiver() {
        if (session.activePairingSessionRecord == null) throw new IllegalStateException("No durable clockface pair");
        String pair = OperationalSessionPolicy.pairingId(session.activePairingSessionRecord);
        if (runner.retainedClockFaceReceiver != null && pair.equals(runner.retainedClockFacePair)) return runner.retainedClockFaceReceiver;
        ClockFaceSyncReceiver receiver = new ClockFaceSyncReceiver(
                BridgePaths.files().resolve("clockface-journal"),
                BridgePaths.files().resolve("clockface-receiver"),
                pair);
        try { log("CLOCKFACE JOURNAL: retired=" + receiver.compactJournal()); }
        catch (IOException | IllegalArgumentException failure) { log("CLOCKFACE JOURNAL: maintenance deferred; durable records preserved"); }
        runner.retainedClockFaceReceiver = receiver;
        runner.retainedClockFacePair = pair;
        return receiver;
    }

    void forwardEncryptedHealthData(IdsModernSessionCoordinator.SessionEvent event) {
        if (session.operationalPairingId == null || runner.clockFaceObservationEpoch == null
                || !runner.operationalSnapshotPublished
                || !IdsUtunConnectionName.isClassCIdentifier(event.service)
                || event.command != IdsSocketPairCodec.COMMAND_DATA) {
            log("HEALTH DATA ignored: pair/epoch/Class-C data unavailable; no native reply or anchors.");
            return;
        }
        byte[] payload = event.payload(), key = null, frame = null;
        try {
            if (runner.idsDeviceInfoExchange != null && runner.idsDeviceInfoExchange.hasVerifiedPeerIdentity()) {
                key = runner.idsDeviceInfoExchange.peerClassAPublicKey();
            }
            try (var incoming = new HealthDataEventCodec.Event(UUID.fromString(session.operationalPairingId),
                    runner.clockFaceObservationEpoch, UUID.fromString(event.messageUuid),
                    event.peerResponseIdentifier == null || event.peerResponseIdentifier.isEmpty()
                            ? null : UUID.fromString(event.peerResponseIdentifier),
                    event.streamId, event.flags, key, payload)) {
                frame = HealthDataEventCodec.encode(incoming);
                System.out.println(HealthDataEventCodec.PREFIX + BridgeBase64.encodeToString(frame,BridgeBase64.NO_WRAP));
                System.out.flush();
                log("HEALTH DATA forwarded: encryptedBytes=" + incoming.encryptedSize()
                        + " verifiedPeerKey=" + (key != null)
                        + "; no plaintext/sample/anchor publication; payload logged=false.");
            }
        } catch (IllegalArgumentException invalid) {
            log("HEALTH DATA rejected: invalid or oversized encrypted native dictionary; payload logged=false.");
        } finally { HalTransportSession.wipe(payload); HalTransportSession.wipe(key); HalTransportSession.wipe(frame); }
    }

    void publishHealthPeerIdentity() {
        if (runner.healthPeerIdentityPublished || session.operationalPairingId == null || !runner.operationalSnapshotPublished
                || runner.clockFaceObservationEpoch == null || runner.idsDeviceInfoExchange == null
                || !runner.idsDeviceInfoExchange.hasVerifiedPeerIdentity()) return;
        byte[] key = runner.idsDeviceInfoExchange.peerClassAPublicKey(), frame = null;
        try (var identity = new HealthPeerIdentityCodec.Identity(UUID.fromString(session.operationalPairingId),
                runner.clockFaceObservationEpoch, UUID.fromString(session.activePairingSessionRecord.localIdsDeviceUuid()),
                UUID.fromString(runner.idsDeviceInfoExchange.verifiedPeerIdentifier()), key)) {
            frame = HealthPeerIdentityCodec.encode(identity);
            System.out.println(HealthPeerIdentityCodec.PREFIX + BridgeBase64.encodeToString(frame,BridgeBase64.NO_WRAP));
            System.out.flush();
            runner.healthPeerIdentityPublished = true;
            log("HEALTH PEER forwarded: correlated IDS command-12 public identity; key values logged=false.");
        } finally { HalTransportSession.wipe(key); HalTransportSession.wipe(frame); }
    }

    PigmentPreferenceMirror pigmentMirror() throws IOException {
        return pigmentMirror(UUID.fromString(session.operationalPairingId));
    }

    MonogramPreferenceMirror monogramMirror(UUID pair) throws IOException {
        if (runner.monogramMirror == null || !runner.monogramMirror.available() || !pair.equals(runner.monogramMirrorPair)) {
            runner.monogramMirror = new MonogramPreferenceMirror(BridgePaths.files().resolve("monogram-mirrors"), pair);
            runner.monogramMirrorPair = pair;
        }
        return runner.monogramMirror;
    }

    void publishMonogramMirror() throws IOException {
        if (!runner.operationalSnapshotPublished || session.operationalPairingId == null || runner.clockFaceObservationEpoch == null) return;
        byte[] frame = MonogramMirrorIpcCodec.encode(monogramMirror(UUID.fromString(session.operationalPairingId)).snapshot(runner.clockFaceObservationEpoch));
        try {
            System.out.println(MonogramMirrorIpcCodec.PREFIX + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
            System.out.flush();
        } finally { HalTransportSession.wipe(frame); }
    }

    PigmentPreferenceMirror pigmentMirror(UUID pair) throws IOException {
        if (runner.pigmentMirror == null || !runner.pigmentMirror.available() || !pair.equals(runner.pigmentMirrorPair)) {
            var next = new PigmentPreferenceMirror(BridgePaths.files().resolve("pigment-mirrors"), pair);
            runner.pigmentMirror = next;
            runner.pigmentMirrorPair = pair;
        }
        return runner.pigmentMirror;
    }

    void publishPigmentMirror() throws IOException {
        if (!runner.operationalSnapshotPublished || session.operationalPairingId == null || runner.clockFaceObservationEpoch == null) return;
        var snapshot = pigmentMirror().snapshot(runner.clockFaceObservationEpoch);
        if (snapshot == null) return;
        byte[] frame = PigmentMirrorIpcCodec.encode(snapshot);
        try {
            System.out.println(PigmentMirrorIpcCodec.PREFIX + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
            System.out.flush();
        } finally { HalTransportSession.wipe(frame); }
    }

    void handleIncomingProtobufEvent(
            IdsModernSessionCoordinator.SessionEvent event) {
        if (event == null || event.topic == null) {
            return;
        }
        publishHealthRegistryObservation(event);
        UUID pigmentOwner = runner.activeIdsBridge == null
                || !PigmentPreferenceCodec.isObservationEnvelope(event.topic, event.protobufType, event.response)
                ? null : PigmentPreferenceOwnerPolicy.owner(
                        session.activePairingSessionRecord, session.operationalPairingId, runner.idsControlReadyObserved);
        if (pigmentOwner != null) {
            byte[] payload = event.payload();
            try (var envelope = NtkPreferenceEnvelope.decode(payload, System.currentTimeMillis())) {
                long now = System.currentTimeMillis();
                var lists = PigmentPreferenceCodec.decodeObservedLists(envelope);
                try { if (pigmentMirror(pigmentOwner).observe(lists.getOrDefault(PigmentPreferenceCodec.KEY, List.of()),
                        lists.getOrDefault(PigmentPreferenceCodec.AUTO_KEY, List.of()), now)) {
                    // Setup has no operational epoch yet. Its durable paired
                    // value is published only when the same pair becomes READY.
                    publishPigmentMirror();
                } } catch (IOException invalid) { log("PIGMENT MIRROR update unavailable; values logged=false."); }
                try { if (monogramMirror(pigmentOwner).observe(MonogramPreferenceCodec.decodeObserved(envelope), now)) publishMonogramMirror(); }
                catch (IOException invalid) { log("MONOGRAM MIRROR update unavailable; values logged=false."); }
            } catch (IllegalArgumentException invalid) {
                log("NATIVE PREFERENCE MIRROR receipt refused; values logged=false.");
            }
            finally { HalTransportSession.wipe(payload); }
        }
        if (IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(event.topic)) {
            byte[] payload = event.payload();
            byte[] acknowledgement = null;
            try {
                BulletinTransportSession.Received received = runner.bulletinTransport.receive(event.protobufType, event.response, payload);
                if (!received.fresh()) {
                    log("BULLETIN RX DUPLICATE: action not forwarded; identifiers/content logged=false.");
                    return;
                }
                acknowledgement = received.acknowledgement();
                if (acknowledgement != null) {
                    if (!offerOutbound(session.outboundAppMessages, new OutboundAppMessage(
                            IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE, 12, acknowledgement))) {
                        log("BULLETIN SESSION ACK rejected: outbound queue full.");
                    }
                }
                log("BULLETIN SESSION RX: type=" + event.protobufType + " localState=" + received.localState()
                        + "; handshake state is not notification delivery.");
            } catch (IllegalArgumentException malformed) {
                log("BULLETIN RX rejected: invalid native session envelope; content logged=false.");
                return;
            } finally { HalTransportSession.wipe(payload); HalTransportSession.wipe(acknowledgement); }
        }
        if (IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(event.topic)
                || IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)
                || IdsApplicationRoute.TELEPHONY_SERVICE.equals(event.topic)
                || PigmentPreferenceCodec.isObservationEnvelope(event.topic, event.protobufType, event.response)) {
            byte[] payload = event.payload();
            byte[] frame = null;
            try (BridgeApplicationEventCodec.Event applicationEvent =
                         new BridgeApplicationEventCodec.Event(event.topic, event.protobufType, event.response, payload)) {
                frame = BridgeApplicationEventCodec.encode(applicationEvent);
                System.out.println(BridgeApplicationEventCodec.PREFIX + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                System.out.flush();
            } catch (IllegalArgumentException rejected) {
                log("APPLICATION IPC REJECTED: invalid or oversized event; payload logged=false.");
            } finally {
                HalTransportSession.wipe(payload);
                HalTransportSession.wipe(frame);
            }
        }
        if (IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(event.topic)) {
            if (!event.response) {
                byte[] payload = event.payload();
                byte[] frame = null;
                try {
                    var incoming = runner.findMyPhone.receive(event.protobufType, false, event.messageUuid,
                            payload, System.currentTimeMillis(), BridgeClock.elapsedRealtime());
                    if (incoming != null && incoming.request() != null) {
                        frame = FindMyPhoneIpcCodec.encode(incoming.request());
                        System.out.println(FindMyPhoneIpcCodec.REQUEST_PREFIX
                                + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                        System.out.flush();
                        log("FIND MY PHONE REQUEST forwarded: type=" + event.protobufType
                                + " fresh=true; waiting for APK effect result; identifiers logged=false.");
                    } else if (incoming != null && incoming.cached() != null) {
                        queuePhonePingReply(incoming.cached());
                        log("FIND MY PHONE duplicate: cached reply only; effect not replayed.");
                    } else log("FIND MY PHONE request ignored: stale/duplicate/unsupported/unready.");
                } catch (IllegalArgumentException malformed) {
                    log("FIND MY PHONE request rejected: malformed native request; no effect.");
                } finally { HalTransportSession.wipe(payload); HalTransportSession.wipe(frame); }
            }
        } else if (IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE.equals(event.topic)) {
            if (event.protobufType == BulletinDistributorCodec.TYPE_DISMISS_ACTION) {
                log("WATCH ACTION: Notification dismissed on Apple Watch");
                System.out.println("EVENT:NOTIFICATION_DISMISS");
                System.out.flush();
            } else if (event.protobufType == BulletinDistributorCodec.TYPE_SUPPLEMENTARY_ACTION) {
                log("WATCH ACTION: Notification reply from Apple Watch");
                System.out.println("EVENT:NOTIFICATION_REPLY");
                System.out.flush();
            }
        } else if (IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)) {
            log("WATCH TELEMETRY: Health sync packet received from Apple Watch");
            System.out.println("EVENT:HEALTH_UPDATE");
            System.out.flush();
        }
        logWatchTelemetryEvent(event);
    }

    void publishHealthRegistryObservation(IdsModernSessionCoordinator.SessionEvent event) {
        if(session.operationalPairingId==null || runner.clockFaceObservationEpoch==null || !runner.operationalSnapshotPublished
                || session.activePairingSessionRecord==null || !NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                || event.protobufType!=NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                || !IdsUtunConnectionName.isClassCIdentifier(event.service))return;
        byte[] payload=event.payload(),frame=null;
        NanoRegistryPropertyCodec.PropertiesChanged snapshot=null;
        try {
            snapshot=NanoRegistryPropertyCodec.decodePropertiesChanged(payload);
            var observation=HealthRegistryObservationCodec.fromSnapshot(UUID.fromString(session.operationalPairingId),
                    runner.clockFaceObservationEpoch,UUID.fromString(session.activePairingSessionRecord.localIdsDeviceUuid()),
                    UUID.fromString(session.activePairingSessionRecord.peerIdsDeviceId()),snapshot);
            frame=HealthRegistryObservationCodec.encode(observation);
            System.out.println(HealthRegistryObservationCodec.PREFIX+BridgeBase64.encodeToString(frame,BridgeBase64.NO_WRAP));System.out.flush();
        } catch(IllegalArgumentException invalid) {
            log("HEALTH registry observation unavailable: "+(snapshot==null?"snapshot malformed":HealthRegistryObservationCodec.describe(snapshot))
                    +"; values logged=false.");
        } finally { if(snapshot!=null)snapshot.destroy();HalTransportSession.wipe(payload);HalTransportSession.wipe(frame); }
    }

    void queuePhonePingReply(FindMyPhoneIpcCodec.Result result) {
        if (runner.pendingPhoneReplies.size() >= 16) {
            log("FIND MY PHONE reply queue full; delivery unconfirmed.");
        } else runner.pendingPhoneReplies.add(result);
    }

    void drainPhonePingReplies(int remoteCid) {
        FindMyPhoneIpcCodec.Result result;
        while ((result = FIND_MY_PHONE_RESULTS.poll()) != null) {
            var request = runner.findMyPhone.complete(result, BridgeClock.elapsedRealtime());
            if (request != null) queuePhonePingReply(new FindMyPhoneIpcCodec.Result(
                    request.epoch(), request.messageId(), request.type(), result.didPlay()));
            else log("FIND MY PHONE result ignored: wrong epoch/id/type, expired or already completed.");
        }
        if (runner.activeIdsBridge == null) return;
        while ((result = runner.pendingPhoneReplies.poll()) != null) {
            if (!runner.findMyPhone.current(result.epoch())) continue;
            byte[] payload = new FindMyLocalDeviceCodec.PlaySoundResponse(result.didPlay()).encode();
            try {
                runner.outputProcessor.processModernIdsOutput(runner.activeIdsBridge.sendFindMyLocalResponse(
                        result.type(), payload, result.messageId()), remoteCid, "FIND MY PHONE RESULT");
                runner.flushPendingIdsFrames(remoteCid);
                log("FIND MY PHONE RESULT sent: type=" + result.type() + " didPlay=" + result.didPlay()
                        + " response=true; actual phone effect, not a transport ACK.");
            } catch (Exception failure) {
                log("FIND MY PHONE reply send failed: " + failure.getClass().getSimpleName()
                        + "; effect will not be replayed.");
            } finally { HalTransportSession.wipe(payload); }
        }
    }

    void logWatchRegistryState(IdsModernSessionCoordinator.SessionEvent event) {
        if (!NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                && !NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(event.topic)) return;
        if (event.protobufType != NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                && !(event.protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST
                && event.response)) return;
        byte[] payload = event.payload();
        NanoRegistryPropertyCodec.ApplicationMessage decoded = null;
        try {
            decoded = event.protobufType == NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED
                    ? NanoRegistryPropertyCodec.decodePropertiesChanged(payload)
                    : NanoRegistryPropertyCodec.decodePropertyResponse(payload);
            List<NanoRegistryPropertyCodec.Property> properties =
                    decoded instanceof NanoRegistryPropertyCodec.PropertiesChanged changed
                            ? changed.properties
                            : ((NanoRegistryPropertyCodec.PropertyResponse) decoded).properties;
            StringBuilder state = new StringBuilder();
            int matchingKeys = 0;
            int numberValues = 0;
            int stringValues = 0;
            int dataValues = 0;
            for (NanoRegistryPropertyCodec.Property property : properties) {
                if (property.value != null) {
                    if (property.value.numberValue != null) numberValues++;
                    if (property.value.stringValue != null) stringValues++;
                    if (property.value.dataValue != null) dataValues++;
                }
                if (!List.of("isSetup", "isPaired", "compatibilityState", "pairingMode",
                        "status", "isActive", "isConnected").contains(property.name)) continue;
                matchingKeys++;
                if (property.value == null || property.value.isError
                        || (property.value.hasIsSet && !property.value.isSet)
                        || property.value.numberValue == null) {
                    state.append(' ').append(property.name).append("=unavailable");
                    continue;
                }
                NanoRegistryPropertyCodec.NumberValue value = property.value.numberValue;
                Object scalar = null;
                if (value.boolValue != null) scalar = value.boolValue;
                else if (value.int32Value != null) scalar = value.int32Value;
                else if (value.int64Value != null) scalar = value.int64Value;
                state.append(' ').append(property.name).append('=').append(
                        scalar != null ? scalar : "unsupported-number");
            }
            log("WATCH REGISTRY RX: type=" + event.protobufType + " response=" + event.response
                    + " properties=" + properties.size() + " setupKeys=" + matchingKeys
                    + " numberValues=" + numberValues + " stringValues=" + stringValues
                    + " dataValues=" + dataValues + state + "; other values logged=false.");
        } catch (IllegalArgumentException rejected) {
            log("WATCH REGISTRY RX: decode failed: " + rejected.getMessage());
        } finally {
            if (decoded != null) decoded.destroy();
            HalTransportSession.wipe(payload);
        }
    }

    void logWatchTelemetryEvent(
            IdsModernSessionCoordinator.SessionEvent event) {
        if (event == null || event.topic == null) {
            return;
        }
        if (event.type != IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                && event.type != IdsModernSessionCoordinator.EventType.DATA_RECEIVED) {
            return;
        }
        if (event.topic.endsWith("sharing.paireddevice")) {
            byte[] statePayload = event.payload();
            try {
                Object state = OpackDecoder.decode(statePayload);
                log("WATCH STATE RX: sharing.paireddevice type=" + event.protobufType
                        + " " + OpackDecoder.summarize(state));
            } catch (IllegalArgumentException undecodable) {
                log("WATCH STATE RX: sharing.paireddevice type=" + event.protobufType
                        + " not OPACK; " + ProtobufTelemetry.describe(statePayload));
            } finally { HalTransportSession.wipe(statePayload); }
        } else if (event.topic.endsWith("ct.commcenter.sim")
                || event.topic.endsWith("pushproxy")
                || event.topic.endsWith("phonecontinuity")) {
            byte[] telemetryPayload = event.payload();
            try {
                log("WATCH TELEMETRY RX: topic=" + ModernIdsOutputProcessor.idsTopicLogLabel(event.topic)
                        + " type=" + event.protobufType + " "
                        + ProtobufTelemetry.describe(telemetryPayload));
            } finally { HalTransportSession.wipe(telemetryPayload); }
        }
    }
}
