package dev.applewatchandroid.bridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import static dev.applewatchandroid.bridge.ProtocolHost.*;


/** IDS session event dispatcher and diagnostic label formatting. */
final class ModernIdsOutputProcessor {
    private final HalTransportSession session;
    private final ModernIdsSessionRunner runner;

    ModernIdsOutputProcessor(HalTransportSession session, ModernIdsSessionRunner runner) {
        this.session = session;
        this.runner = runner;
    }

    void processModernIdsOutput(
            NormalLinkIdsSessionBridge.Output output,
            int remoteCid,
            String label)
            throws Exception {
        List<IdsBootstrapState.Action> actions =
                new ArrayList<>();
        List<AppleWatchInitialSetupIdsAdapter.Action>
                initialActions =
                new ArrayList<>();
        try (output) {
            session.normalLink.sendNormalFrames(
                    remoteCid,
                    output.ertmFrames(),
                    label);
            session.postCommit.completePendingPostCommitSends(remoteCid);

            for (IdsModernSessionCoordinator.SessionEvent event
                    : output.idsEvents()) {
                if (event.type == IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED) {
                    OPERATIONAL_REQUESTS.appAck(event.topic, event.peerResponseIdentifier, BridgeClock.elapsedRealtime());
                    if (!runner.initialWifiReceiptLogged && runner.initialWifiMessageUuid != null
                            && WifiNetworkSyncCodec.TOPIC.equals(event.topic)
                            && runner.initialWifiMessageUuid.equals(event.peerResponseIdentifier)) {
                        runner.initialWifiReceiptLogged = true;
                        log("WIFI SETUP AUTO APP ACK: Watch received the network archive; joining the network remains unconfirmed.");
                    }
                } else if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                        && !IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(event.topic)) {
                    OPERATIONAL_REQUESTS.appResponse(event.topic, event.peerResponseIdentifier,
                            event.protobufType, event.response, BridgeClock.elapsedRealtime());
                }
                // Live 0.2.190: a committed-but-unactivated Watch can drive
                // ProxyActivation right after the timesync answer, before
                // any this-session Class-C snapshot. That authenticated
                // request is itself the Albert evidence: stash it and let
                // it authorize post-commit instead of dropping it into the
                // NanoRegistry-only initial adapter.
                if (event.type
                                == IdsModernSessionCoordinator.EventType
                                .PROTOBUF_RECEIVED
                        && IdsApplicationRoute.PB_BRIDGE_SERVICE.equals(
                                event.topic)
                        && event.protobufType
                                == PbBridgeCodec.TYPE_PROXY_ACTIVATION
                        && session.postCommit.activePostCommitAdapter == null) {
                    session.postCommit.watchProxyActivationObserved = true;
                    if (session.postCommit.pendingWatchProxyActivation == null) {
                        session.postCommit.pendingWatchProxyActivation = event.copy();
                        log("WATCH PROXY ACTIVATION OBSERVED: stashing the "
                                + "request and authorizing post-commit "
                                + "without a this-session Class-C snapshot "
                                + "(live 0.2.190).");
                    }
                    session.postCommit.startPostCommitIfAuthorized(
                            remoteCid);
                }
                if (SysdiagnoseArchiveInventory.TOPIC.equals(event.topic)
                        && (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                        || event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED)) {
                    byte[] diagnosticPayload = event.payload();
                    try {
                        if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT) {
                            runner.sysdiagnoseCollection.queued(event.protobufType, diagnosticPayload);
                            if (event.protobufType == SysdiagnoseArchiveInventory.LIST_REQUEST) {
                                runner.sysdiagnoseInventory.queued(event.protobufType, diagnosticPayload);
                            }
                            log("SYSDIAGNOSE DIAGNOSTIC TX: type=" + event.protobufType
                                    + "; nonce logged=false; setup state unchanged.");
                        } else {
                            java.nio.file.Path status = runner.sysdiagnoseCollection.receive(event.protobufType,
                                    diagnosticPayload, BridgePaths.temporary());
                            if (status != null) log("SYSDIAGNOSE COLLECTION STATUS: type=" + event.protobufType
                                    + " bytes=" + diagnosticPayload.length + " privateFile=" + status
                                    + "; content logged=false; setup state unchanged.");
                            SysdiagnoseArchiveInventory.Observation result = runner.sysdiagnoseInventory.receive(
                                    event.protobufType, diagnosticPayload, BridgePaths.temporary());
                            if (result != null && result.correlation != null) {
                                OPERATIONAL_REQUESTS.diagnosticInventoryResponse(
                                        UUID.fromString(result.correlation), BridgeClock.elapsedRealtime());
                            }
                            log("SYSDIAGNOSE INVENTORY RX: type=" + event.protobufType
                                    + " response=" + event.response + " accepted=" + (result != null)
                                    + (result != null ? " correlated=" + result.correlated + " entries="
                                    + result.entries + " privateFile=" + result.file : "")
                                    + "; names/content logged=false; setup state unchanged.");
                        }
                    } catch (java.io.IOException | RuntimeException failure) {
                        log("SYSDIAGNOSE INVENTORY: response/storage unavailable ("
                                + failure.getClass().getSimpleName() + "); setup state unchanged.");
                    } finally { HalTransportSession.wipe(diagnosticPayload); }
                }
                if (IdsApplicationRoute.FIND_MY_LOCAL_SERVICE.equals(event.topic)) {
                    if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT) {
                        runner.findMySession.queued(event.protobufType, event.response, event.messageUuid,
                                BridgeClock.elapsedRealtime());
                        log("FIND MY LOCAL TX: type=" + event.protobufType + " response=" + event.response
                                + "; native Unix timestamp; pairing/setup state unchanged.");
                    } else if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED) {
                        byte[] pingPayload = event.payload();
                        byte[] frame = null;
                        try {
                            var reply = runner.findMySession.receive(event.protobufType, event.response,
                                    event.peerResponseIdentifier, pingPayload, BridgeClock.elapsedRealtime());
                            FindMyLocalDeviceCodec.PlaySoundResponse unboundObservation = null;
                            if (event.response && (event.protobufType == FindMyLocalDeviceCodec.TYPE_PLAY_SOUND
                                    || event.protobufType == FindMyLocalDeviceCodec.TYPE_PLAY_SOUND_AND_FLASH)) {
                                unboundObservation = FindMyLocalDeviceCodec.PlaySoundResponse.decode(pingPayload);
                            }
                            log("FIND MY LOCAL RX: type=" + event.protobufType + " response=" + event.response
                                    + " hasResponseId=" + (event.peerResponseIdentifier != null)
                                    + " correlated=" + (reply != null)
                                    + (unboundObservation == null ? "" : " nativeDidPlay=" + unboundObservation.didPlay())
                                    + "; actual audible effect requires observation.");
                            if (reply != null) {
                                OPERATIONAL_REQUESTS.appResponse(event.topic, event.peerResponseIdentifier,
                                        event.protobufType, true, BridgeClock.elapsedRealtime());
                                try (var pingEvent = new BridgeApplicationEventCodec.Event(event.topic,
                                        event.protobufType, true, pingPayload)) {
                                    frame = BridgeApplicationEventCodec.encode(pingEvent);
                                    System.out.println(BridgeApplicationEventCodec.PREFIX
                                            + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                                    System.out.flush();
                                }
                            }
                        } catch (IllegalArgumentException invalid) {
                            log("FIND MY LOCAL RX REJECTED: malformed native response; effect not inferred.");
                        } finally { HalTransportSession.wipe(pingPayload); HalTransportSession.wipe(frame); }
                    }
                }
                if (NanoSystemSettingsDiagnostics.TOPIC.equals(event.topic)) {
                    if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT
                            && (event.protobufType == NanoSystemSettingsDiagnostics.ABOUT_REQUEST
                            || event.protobufType == NanoSystemSettingsDiagnostics.LOGS_REQUEST)) {
                        runner.nssDiagnostics.queued(event.protobufType, event.messageUuid);
                        log("NSS DIAGNOSTIC REQUEST: type=" + event.protobufType
                                + "; read-only; awaiting a correlated application response.");
                    } else if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED) {
                        byte[] nssPayload = event.payload();
                        try {
                            String observation = runner.nssDiagnostics.response(event.protobufType,
                                    event.response, event.peerResponseIdentifier, nssPayload);
                            log("NSS DIAGNOSTIC RX: type=" + event.protobufType + " correlated="
                                    + (observation != null) + (observation != null ? "; " + observation : "")
                                    + "; pairing/setup state unchanged.");
                            // Only this verified response is forwarded; unsolicited About frames
                            // cannot become device telemetry in the APK.
                            if (observation != null && event.protobufType == NanoSystemSettingsDiagnostics.ABOUT_RESPONSE) {
                                byte[] frame = null;
                                try (BridgeApplicationEventCodec.Event aboutEvent =
                                             new BridgeApplicationEventCodec.Event(event.topic,
                                                     event.protobufType, true, nssPayload)) {
                                    frame = BridgeApplicationEventCodec.encode(aboutEvent);
                                    System.out.println(BridgeApplicationEventCodec.PREFIX
                                            + BridgeBase64.encodeToString(frame, BridgeBase64.NO_WRAP));
                                    System.out.flush();
                                } finally { HalTransportSession.wipe(frame); }
                            }
                        } catch (IllegalArgumentException invalid) {
                            log("NSS DIAGNOSTIC RX REJECTED: " + invalid.getMessage());
                        } finally { HalTransportSession.wipe(nssPayload); }
                    }
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && event.command == IdsSocketPairCodec.COMMAND_DICTIONARY) {
                    byte[] dictionary = event.payload();
                    try {
                        log("IDS DICTIONARY RX: topic=" + idsTopicLogLabel(event.topic)
                                + " " + IdsCredentialsDiagnostics.describe(dictionary));
                    } finally { HalTransportSession.wipe(dictionary); }
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && SoftwareUpdateCodec.TOPIC.equals(event.topic) && runner.softwareUpdate != null
                        && runner.operationalSnapshotPublished) {
                    byte[] updatePayload = event.payload();
                    try {
                        if (runner.softwareUpdate.receive(updatePayload, event.peerResponseIdentifier, System.currentTimeMillis()))
                            runner.appServices.publishSoftwareUpdate();
                    } catch (IllegalArgumentException invalid) {
                        log("SOFTWARE UPDATE observation rejected; native payload logged=false.");
                    } finally { HalTransportSession.wipe(updatePayload); }
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DIRECT_MESSAGING_INFO_SENT) {
                    log("IDS DIRECT MSG INFO TX: " + event.topic + "; basic protocol only; device-connection/concise-ACK extensions disabled.");
                } else if (event.type == IdsModernSessionCoordinator.EventType.CONTROL_MESSAGE
                        && event.topic != null && event.topic.startsWith("DirectMsgInfo/")) {
                    log("IDS DIRECT MSG INFO RX: " + event.topic.substring("DirectMsgInfo/".length()));
                }
                if (runner.idsDeviceInfoExchange != null) {
                    if (event.type == IdsModernSessionCoordinator.EventType.ACK_RECEIVED
                            && runner.idsDeviceInfoExchange.onAcknowledgement(event.service, event.sequence)) {
                        log("IDS DEVICE INFO: local public-identity response acknowledged by Watch.");
                    }
                    if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                            && IdsDeviceInfoExchange.TOPIC.equals(event.topic)
                            && IdsUtunConnectionName.isClassDIdentifier(event.service)) {
                        byte[] payload = event.payload();
                        byte[] response = null;
                        try {
                            String before = runner.idsDeviceInfoExchange.summary();
                            log("IDS CREDENTIALS STRUCTURE: " + IdsCredentialsDiagnostics.describe(payload));
                            String deviceInfoValues = IdsCredentialsDiagnostics.describeDeviceInfoValues(payload);
                            if (deviceInfoValues != null) {
                                log("IDS " + deviceInfoValues);
                            }
                            response = runner.idsDeviceInfoExchange.accept(payload);
                            runner.appServices.publishHealthPeerIdentity();
                            if (!before.equals(runner.idsDeviceInfoExchange.summary())) {
                                log("IDS DEVICE INFO: " + runner.idsDeviceInfoExchange.summary() + "; key/identifier values logged=false.");
                            }
                            if (response != null) {
                                runner.sendIdsDeviceInfo(response, true, remoteCid);
                            } else {
                                IdsRemoteAccountExchange.Reply accountReply = null;
                                try {
                                    accountReply = IdsRemoteAccountExchange.accept(payload, runner::persistPeerSpsMetadata);
                                } catch (IllegalArgumentException invalidAccountRequest) {
                                    log("IDS ACCOUNT RPC: invalid request; reply not sent; values logged=false.");
                                }
                                if (accountReply != null) {
                                    try (IdsRemoteAccountExchange.Reply reply = accountReply;
                                         NormalLinkIdsSessionBridge.Output sent = runner.activeIdsBridge.sendApplicationData(
                                                 IdsDeviceInfoExchange.TOPIC, reply.payload)) {
                                        log("IDS ACCOUNT RPC TX: command=" + reply.command + "; " + reply.summary);
                                        // Account replies must never satisfy the command-12 key delivery barrier.
                                        processModernIdsOutput(sent, remoteCid, "NORMAL IDS ACCOUNT RPC");
                                    }
                                }
                            }
                        } finally { HalTransportSession.wipe(payload); HalTransportSession.wipe(response); }
                    }
                }
                if (session.postCommit.activePostCommitAdapter != null) {
                    session.postCommit.executePostCommitActions(session.postCommit.pendingPostCommitSends.completeReceipt(event), remoteCid);
                }
                log("IDS EVENT: type="
                        + event.type
                        + (event.type == IdsModernSessionCoordinator.EventType.UNKNOWN_COMMAND_IGNORED
                        ? " command=0x" + Integer.toHexString(event.command) : "")
                        + (event.transportEvent != null
                        ? " (" + event.transportEvent + ")"
                        : "")
                        + " service="
                        + idsServiceLogLabel(
                                event.service)
                        + " topic="
                        + idsTopicLogLabel(
                                event.topic)
                        + " protobufType="
                        + (event.protobufType < 0
                        ? "none"
                        : Integer.toString(
                                event.protobufType))
                        + "; payload/sequence/identifier "
                        + "logged=false.");
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && event.topic != null
                        && event.topic.contains("timesync")) {
                    byte[] tsPayload = event.payload();
                    try {
                        int tsType = TimeSyncLinkCodec.messageType(tsPayload);
                        log("TIMESYNC LINK RX: kTMLSLinkMsgKey=" + tsType
                                + " payloadBytes=" + event.payloadLength());
                        if (tsType == TimeSyncLinkCodec.MSG_REQUEST_STATE
                                && runner.activeIdsBridge != null) {
                            String timeZoneId = java.util.TimeZone.getDefault().getID();
                            byte[][] replies = new byte[][] {
                                    TimeSyncLinkCodec.automaticTimeEnabled(),
                                    TimeSyncLinkCodec.requestAcknowledgement(),
                                    TimeSyncLinkCodec.sourceAndTimeZone(timeZoneId),
                            };
                            for (byte[] reply : replies) {
                                try {
                                    NormalLinkIdsSessionBridge.Output sent =
                                            runner.activeIdsBridge.sendApplicationData(
                                                    event.topic, reply);
                                    processModernIdsOutput(sent, remoteCid,
                                            "TIMESYNC LINK TX");
                                    runner.flushPendingIdsFrames(remoteCid);
                                } finally {
                                    HalTransportSession.wipe(reply);
                                }
                            }
                            log("TIMESYNC LINK TX: answered Watch kTMLSLinkMsgKey=6 "
                                    + "with msg 12 (automatic time enabled), msg 7 (ack), "
                                    + "msg 4 (TMLSSourceDevice, " + timeZoneId + ").");
                        } else if (tsType == TimeSyncLinkCodec.MSG_CONVERT_BT_TIME
                                && runner.activeIdsBridge != null) {
                            byte[][] replies = TimeSyncLinkCodec.clockConversionResponses(
                                    tsPayload, System.currentTimeMillis(), System.nanoTime());
                            for (byte[] reply : replies) {
                                try {
                                    processModernIdsOutput(runner.activeIdsBridge.sendApplicationData(
                                                    event.topic, reply), remoteCid, "TIMESYNC RTC TX");
                                    runner.flushPendingIdsFrames(remoteCid);
                                } finally { HalTransportSession.wipe(reply); }
                            }
                            log("TIMESYNC RTC TX: msg 9 conversion followed by msg 1 current device time.");
                        }
                    } catch (IllegalArgumentException malformed) {
                        log("TIMESYNC LINK RX rejected: " + malformed.getMessage());
                    } finally {
                        HalTransportSession.wipe(tsPayload);
                    }
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && event.topic != null
                        && event.topic.contains("clockface")) {
                    byte[] facePayload = event.payload();
                    try {
                        log("CLOCKFACE SYNC RX: topic="
                                + idsTopicLogLabel(event.topic)
                                + " command=0x"
                                + Integer.toHexString(event.command)
                                + " flags=0x"
                                + Integer.toHexString(event.flags)
                                + " "
                                + ClockFaceSyncDiagnostics.summarize(facePayload));
                        captureActivationArtifact("clockface-sync", facePayload);
                        if (runner.clockFaceDelta != null && event.peerResponseIdentifier != null) {
                            try {
                                var previous = runner.clockFaceDelta.stage();
                                if (runner.clockFaceDelta.response(event.topic, event.flags, UUID.fromString(event.peerResponseIdentifier),
                                        facePayload, System.currentTimeMillis(), BridgeClock.elapsedRealtime())) {
                                    if (previous == ClockFaceDeltaSession.Stage.START_WAIT) OPERATIONAL_REQUESTS.appResponse(
                                            event.topic, event.peerResponseIdentifier, ClockFaceSyncAccept.START, true, BridgeClock.elapsedRealtime());
                                    log("CLOCKFACE DELTA RESPONSE: stage=" + runner.clockFaceDelta.stage() + "; state not inferred from ACK.");
                                }
                            } catch (IllegalArgumentException malformedIdentifier) {
                                log("CLOCKFACE DELTA response identifier refused.");
                            }
                            runner.appServices.finishClockFaceDeltaIfTerminal();
                        }
                        if (IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE.equals(event.topic)
                                && OPERATIONAL_REQUESTS.expectsResponse(event.topic, event.peerResponseIdentifier,
                                ClockFaceSyncHeaderCodec.FULL_REQUEST, BridgeClock.elapsedRealtime())) {
                            try {
                                ClockFaceSyncHeaderCodec.FullResponse response = ClockFaceSyncHeaderCodec.fullResponse(facePayload);
                                OPERATIONAL_REQUESTS.appResponse(event.topic, event.peerResponseIdentifier,
                                        ClockFaceSyncHeaderCodec.FULL_REQUEST, true, BridgeClock.elapsedRealtime());
                                log("CLOCKFACE COLLECTION REQUEST RESPONSE: correlated=true accepted=" + response.accepted()
                                        + " error=" + response.hasError() + "; current collection still requires successful END.");
                            } catch (IllegalArgumentException malformed) {
                                log("CLOCKFACE COLLECTION REQUEST RESPONSE refused: " + safeMessage(malformed));
                            }
                        }
                        // flags 0xd is a CompanionSync request. The reply is
                        // the matching response protobuf, not a copy of the
                        // request. A copied start collides and the Watch
                        // ends the session.
                        boolean expectsResponse = (event.flags
                                & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) != 0;
                        if (expectsResponse
                                && runner.activeIdsBridge != null
                                && IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE.equals(event.topic)) {
                            byte[] reply = null;
                            try {
                                if (session.activePairingSessionRecord == null) {
                                    throw new IllegalStateException("Clockface has no durable pair");
                                }
                                ClockFaceSyncReceiver receiver = runner.appServices.clockFaceReceiver();
                                ClockFaceSyncReceiver.Receipt receipt = receiver.accept(facePayload, System.currentTimeMillis());
                                if (receiver.takeJournalMaintenanceFailure()) {
                                    log("CLOCKFACE JOURNAL: maintenance deferred after durable commit");
                                }
                                if (receipt.stage().equals("COMPLETED")) {
                                    try (ClockFaceSyncFrame frame = ClockFaceSyncFrame.parse(facePayload)) {
                                        runner.appServices.clockFaceClient().noteCommittedHeader(frame.header);
                                    }
                                    runner.appServices.publishClockFaceObservation(runner.clockFaceObservationEpoch);
                                    if (runner.clockFaceDelta != null && session.operationalPairingId != null) {
                                        runner.clockFaceDelta.observe(UUID.fromString(session.operationalPairingId), runner.clockFaceObservationEpoch,
                                                receiver.snapshot(), BridgeClock.elapsedRealtime());
                                        runner.appServices.finishClockFaceDeltaIfTerminal();
                                    }
                                }
                                byte[] localHeader = runner.appServices.clockFaceClient().reserveHeader(System.currentTimeMillis(), false);
                                try { reply = ClockFaceSyncAccept.reply(facePayload, localHeader); }
                                finally { HalTransportSession.wipe(localHeader); }
                                log("CLOCKFACE RECEIVER: stage=" + receipt.stage() + " changes=" + receipt.changes()
                                        + " bytes=" + facePayload.length + " duplicate=" + receipt.duplicate()
                                        + " batches=" + receipt.batches() + " observedFaces=" + receipt.observedFaces()
                                        + " inventoryComplete=" + receipt.complete() + " application=unverified");
                            } catch (Exception failure) {
                                log("CLOCKFACE SYNC RX NOT ACCEPTED: " + safeMessage(failure));
                            }
                            if (reply != null) {
                                try (NormalLinkIdsSessionBridge.Output sent =
                                             runner.activeIdsBridge.sendApplicationDataResponse(
                                                     event.topic,
                                                     reply,
                                                     event.outgoingResponseIdentifier())) {
                                    processModernIdsOutput(
                                            sent, remoteCid, "CLOCKFACE SYNC");
                                    log("CLOCKFACE SYNC TX: replied message=0x"
                                            + Integer.toHexString(reply[0] & 0xff)
                                            + " bytes=" + reply.length);
                                } catch (Exception failure) {
                                    log("CLOCKFACE SYNC TX FAILED: " + safeMessage(failure));
                                } finally {
                                    HalTransportSession.wipe(reply);
                                }
                            }
                        }
                    } finally {
                        HalTransportSession.wipe(facePayload);
                    }
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .PROTOBUF_RECEIVED) {
                    runner.appServices.handleIncomingProtobufEvent(event);
                }
                if (event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                        && IdsApplicationRoute.HEALTH_SYNC_SERVICE.equals(event.topic)) {
                    runner.appServices.forwardEncryptedHealthData(event);
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .TRANSPORT_EVENT
                        && event.transportEvent
                        == IdsServiceConnectorCoordinator.EventType
                        .SERVICE_REJECTED_BY_POLICY
                        ) {
                    if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(event.service)) {
                        if (runner.activeIdsBridge != null && runner.activeIdsBridge.idsSnapshot().peerHelloReceived) {
                            log("IDS: redundant control request rejected; keeping the independently authenticated active Hello.");
                            continue;
                        }
                        // Live 13:56: the Watch RST'd our control SYN and
                        // opened control itself. Live 0.2.209: the same
                        // race completed the TCP handshake first, so the
                        // Watch answered REJECTED_BY_POLICY while its own
                        // 49153→61315 SYN was already on the wire. Aborting
                        // here dropped that connector. Do not open another
                        // SYN; the outgoing attempt is already cleared so
                        // the Watch-initiated connector can be adopted.
                        log("IDS: outgoing control rejected by policy; "
                                + "not opening another SYN. Waiting for the Watch-initiated control connector.");
                        continue;
                    }
                    if (runner.activeIdsBootstrap != null
                            && runner.activeIdsBootstrap.requiredLaneRejected(event.service)) {
                        // Live 0.2.213 reconnect: Urgent-D POLICY aborted
                        // the link before a Watch-initiated lane could
                        // join. The outgoing SYN is already cancelled.
                        if (!session.freshPairingPerformed) {
                            log("IDS: outgoing data lane rejected by policy during paired reconnect ("
                                    + idsServiceLogLabel(event.service)
                                    + "); probing all C/D endpoint keys before one control recovery request.");
                            IdsBootstrapState.Action recovery =
                                    runner.activeIdsBootstrap.onPairedLanePolicyRejection();
                            if (recovery != IdsBootstrapState.Action.NONE) actions.add(recovery);
                            continue;
                        }
                        throw new HostException("Watch rejected required IDS data lane by policy (NWSC 0x40): "
                                + idsServiceLogLabel(event.service) + "; not retrying SYN");
                    }
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .CONTROL_READY) {
                    session.postCommit.checkpointIdsControlReady();
                    runner.idsControlReadyObserved = true;
                    runner.persistStaleFlowObservations(
                            "control ready");
                    log("IDS PEER VERSIONS: " + runner.activeIdsBridge.ids().peerVersionSummary()
                            + "; setup advertisement=" + (session.activeWatchSetupMetadata != null
                            ? session.activeWatchSetupMetadata.pairingVersion : "unknown"));
                    Integer maximum = runner.activeIdsBridge.observedPeerMaxPairingVersion();
                    String build = runner.activeIdsBridge.observedPeerBuildVersion();
                    if (maximum != null && build != null && session.activePairingSessionRecord != null
                            && runner.activeIdsBridge.ids().peerHelloMatchesIdentity(session.activePairingSessionRecord.peerIdsDeviceId())
                            && (!maximum.equals(session.activePairingSessionRecord.observedPeerMaximum())
                                || !build.equals(session.activePairingSessionRecord.peerBuildVersion()))) {
                        PairingSessionRecord observed = session.activePairingSessionRecord.withObservedPeerSystem(
                                session.activePairingSessionRecord.peerIdsDeviceId(), maximum, build);
                        try { session.persistAndReplaceActivePairingSession(observed); observed = null; }
                        finally { if (observed != null) observed.destroy(); }
                    }
                    if (session.postCommit.activeInitialSetup == null && runner.activeIdsBridge.dataLanesReadyForApplication()) {
                        // Restored setup may have joined data lanes before the Hello
                        // supplied an observed version. Retry without invented metadata.
                        runner.pendingNanoSetup = true;
                    }
                    if (session.activePairingSessionRecord != null) {
                        log("IDS PEER IDENTITY RELATION: " + runner.activeIdsBridge.ids().peerIdentitySummary(
                                session.activePairingSessionRecord.peerIdsDeviceId(),
                                session.activePairingSessionRecord.peerNetworkRelayIdentifier()));
                    }
                    // The setup messages can only be delivered once the peer
                    // Hello has completed; before that the Watch resets the
                    // service connectors that would carry them.
                    session.postCommit.startPostCommitIfAuthorized(
                            remoteCid);
                }
                if (runner.activeIdsBootstrap != null) {
                    IdsBootstrapState.Action action =
                            event.type
                                    == IdsModernSessionCoordinator.EventType.TRANSPORT_EVENT
                                    && event.transportEvent
                                    == IdsServiceConnectorCoordinator.EventType.KEY_PROBE_COMPLETED
                                    ? runner.activeIdsBootstrap.onPairedKeyProbeCompleted()
                                    : runner.activeIdsBootstrap.observe(
                                            event.type,
                                            event.service);
                    if (action
                            == IdsBootstrapState.Action.READY) {
                        if (session.activePairingSessionRecord != null
                                && session.activePairingSessionRecord.state().wireValue()
                                        >= PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED.wireValue()) {
                            runner.modernIdsReady = true;
                            session.postCommit.readyToCommitIsPaired = true;
                            session.postCommit.initialPropertiesReceived = true;
                            runner.idsBootstrapBarrierPassed = true;
                            session.postCommit.startPostCommitIfAuthorized(
                                    remoteCid);
                        } else if (session.postCommit.activeInitialSetup == null
                                && !runner.pendingNanoSetup) {
                            runner.modernIdsReady = true;
                            runner.pendingNanoSetup = true;
                            runner.pendingNanoSetupAtMs =
                                    BridgeClock.elapsedRealtime();
                            if (runner.activeIdsBridge != null) {
                                runner.activeIdsBridge
                                        .setPayloadFastRetransmitEnabled(
                                                false);
                            }
                            int outstanding =
                                    runner.activeIdsBridge != null
                                            ? runner.activeIdsBridge.ertmOutstandingCount()
                                            : -1;
                            long tcpUna =
                                    runner.activeIdsBridge != null
                                            ? runner.activeIdsBridge
                                            .tcpUnacknowledgedSendBytes()
                                            : -1L;
                            log("INITIAL NANO SETUP WAIT: Urgent Class-D/C "
                                    + "Handshake received; holding Check "
                                    + "until ERTM Tx outstanding<="
                                    + IdsNanoSetupPacer.QUIET_OUTSTANDING
                                    + " and TCP una==nxt (live 0.2.126 PIN "
                                    + "Check sat behind TCP una 117 bytes; "
                                    + "live 0.2.127 tcpUna=27 never "
                                    + "retransmitted). "
                                    + "outstanding="
                                    + outstanding
                                    + " tcpUna="
                                    + tcpUna
                                    + " streams="
                                    + (runner.activeIdsBridge != null
                                            ? runner.activeIdsBridge
                                            .tcpUnacknowledgedSendSummary()
                                            : "none")
                                    + "; identifiers logged=false.");
                        }
                    } else if (action
                            != IdsBootstrapState.Action.NONE) {
                        actions.add(
                                action);
                    }
                }
                if (session.postCommit.activeInitialSetup == null && runner.pendingNanoSetup) {
                    runner.maybeFlushPendingNanoSetup(remoteCid);
                }
                if (session.postCommit.activeInitialSetup != null) {
                    if (event.type == IdsModernSessionCoordinator.EventType.ACK_RECEIVED
                            && runner.nanoRegistryProbe.onAcknowledgement(event.service, event.sequence)) {
                        log("NANOREGISTRY PROBE: delivery ACK received; application response still required.");
                    }
                    if (event.type == IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED
                            && runner.nanoRegistryProbe.onClientAcknowledgement(event.service, event.topic,
                            event.peerResponseIdentifier)) {
                        log("NANOREGISTRY PROBE: correlated client ACK received; Ping response still required.");
                    }
                    if (event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED) {
                        byte[] probePayload = event.payload();
                        try {
                            if (runner.nanoRegistryProbe.onResponse(event.topic, event.protobufType,
                                    event.response, event.peerResponseIdentifier, probePayload)) {
                                log("NANOREGISTRY PROBE: correlated Ping response received from the Watch daemon; setup state unchanged.");
                            }
                        } finally {
                            HalTransportSession.wipe(probePayload);
                        }
                    }
                    if (event.type
                            == IdsModernSessionCoordinator.EventType
                            .PROTOBUF_RECEIVED
                            || event.type
                            == IdsModernSessionCoordinator.EventType
                            .DATA_RECEIVED) {
                        int before =
                                initialActions.size();
                        initialActions.addAll(
                                session.postCommit.activeInitialSetup.accept(
                                        event));
                        if (event.topic != null
                                && event.topic.contains(
                                        "timesync")) {
                            byte[] tsPayload =
                                    event.payload();
                            if (tsPayload != null) {
                                log("TIMESYNC DIAG RX: payloadBytes="
                                        + tsPayload.length
                                        + " hex="
                                        + HciCodec.toHex(
                                                tsPayload));
                                captureActivationArtifact(
                                        "timesync-rx",
                                        tsPayload);
                            }
                        }
                        log("INITIAL NANO RX: type="
                                + event.type
                                + " service="
                                + idsServiceLogLabel(
                                        event.service)
                                + " topic="
                                + idsTopicLogLabel(
                                        event.topic)
                                + " protobufType="
                                + (event.protobufType < 0
                                        ? "none"
                                        : Integer.toString(
                                                event.protobufType))
                                + " response="
                                + event.response
                                + " payloadBytes="
                                + event.payloadLength()
                                + " actions="
                                + (initialActions.size()
                                        - before)
                                + "; payload logged=false.");

                    } else {
                        initialActions.addAll(
                                session.postCommit.activeInitialSetup.accept(
                                        event));
                    }

                } else if (runner.pendingNanoSetup && (event.type
                        == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                        || event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED)) {
                    // ACKs received before Check was even queued cannot acknowledge it.
                    long bufferedBytes = session.postCommit.earlyInitialEvents.stream().mapToLong(
                            IdsModernSessionCoordinator.SessionEvent::payloadLength).sum();
                    if (session.postCommit.earlyInitialEvents.size() >= 128
                            || bufferedBytes + event.payloadLength() > 4L * 1024 * 1024) {
                        throw new HostException("Initial IDS event backlog exceeded its capacity");
                    }
                    session.postCommit.earlyInitialEvents.add(event.copy());
                    log("[WatchHal] buffered early setup event: type="
                            + event.type
                            + " service="
                            + idsServiceLogLabel(event.service)
                            + " topic="
                            + idsTopicLogLabel(event.topic));
                    // Early events are replayed into the setup adapter only, so
                    // decode their payload here or the Watch state is lost.
                    runner.appServices.logWatchTelemetryEvent(event);
                }
                if (event.type
                        == IdsModernSessionCoordinator.EventType
                        .DATA_CHANNEL_JOINED
                        || event.type
                        == IdsModernSessionCoordinator.EventType
                        .DATA_SERVICE_ACCEPTED
                        || event.type
                        == IdsModernSessionCoordinator.EventType
                        .HANDSHAKE_RECEIVED) {
                    log("IDS SERVICE ACTIVE: data channel / service active (topic="
                            + idsTopicLogLabel(event.topic)
                            + ").");

                    if (!initialActions.isEmpty()) {
                        for (AppleWatchInitialSetupIdsAdapter.Action initialAction : initialActions) {
                            session.postCommit.executeInitialSetupAction(initialAction, remoteCid);
                        }
                        initialActions.clear();
                    }
                    runner.flushPendingIdsFrames(
                            remoteCid);
                    session.postCommit.startPostCommitIfAuthorized(
                            remoteCid);
                }
                if (session.postCommit.activePostCommitAdapter != null) {
                    session.postCommit.answerLivePropertyRequest(
                            event,
                            remoteCid);
                    if (event.type
                            == IdsModernSessionCoordinator.EventType
                            .PROTOBUF_RECEIVED
                            || event.type
                            == IdsModernSessionCoordinator.EventType
                            .DATA_RECEIVED) {
                        boolean isSetupMessage = event.type
                                == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                                && (IdsApplicationRoute.PB_BRIDGE_SERVICE.equals(event.topic)
                                || NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(event.topic)
                                || NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(event.topic)
                                || PairedSyncCodec.isService(event.topic));
                        if (isSetupMessage) {
                            runner.appServices.logWatchRegistryState(event);
                            try (AppleWatchPostCommitIdsAdapter.InboundResult incoming =
                                         session.postCommit.activePostCommitAdapter.accept(event)) {
                                if (incoming.hasActivationRequest()) {
                                    if (session.postCommit.pendingActivationPayload != null) {
                                        throw new HostException("Concurrent activation request before prior proxy completion");
                                    }
                                    session.postCommit.pendingActivationPayload = incoming.archivedActivationRequest();
                                    captureActivationArtifact(
                                            incoming.activationKind.name().toLowerCase(Locale.US)
                                                    + "-request",
                                            session.postCommit.pendingActivationPayload);
                                    session.postCommit.watchDrivenActivationObserved = true;
                                    log("ACTIVATION PROXY RX: kind=" + incoming.activationKind
                                            + " flags=0x" + Integer.toHexString(event.flags)
                                            + " expectsPeerResponse="
                                            + ((event.flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) != 0)
                                            + "; authenticated Watch request accepted.");
                                }
                                session.postCommit.executePostCommitActions(incoming.actions(), remoteCid);
                            }
                        }
                    } else if (event.type
                            == IdsModernSessionCoordinator.EventType
                            .APP_ACK_RECEIVED) {
                        AppleWatchPostCommitIdsAdapter.AppAckReceipt receipt =
                                session.postCommit.activePostCommitAdapter.acceptAppAck(
                                        event);
                        if (receipt == AppleWatchPostCommitIdsAdapter.AppAckReceipt.PAIRED_SYNC_MATCHED) {
                            log("IDS APP ACK ACCEPTED: topic="
                                    + idsTopicLogLabel(event.topic));
                            // AppAck is receipt evidence only. Watch-local sync
                            // progress is processed from its incoming state payload.

                        }
                    }
                }
            }

            List<NormalLinkPipeSession.DeliveredIp> passthrough =
                    output.passthroughIp();
            try {
                if (!passthrough.isEmpty()) {
                    NormalLinkPipeSession.DeliveredIp first = passthrough.get(0);
                    int nextHeader = (first.packet != null && first.packet.length > 6) ? (first.packet[6] & 0xff) : -1;
                    int len = first.packet != null ? first.packet.length : 0;
                    boolean hasPorts = (nextHeader == 6 || nextHeader == 17) && len >= 44;
                    String ports = hasPorts ? " ports="
                            + (((first.packet[40] & 0xff) << 8) | (first.packet[41] & 0xff)) + "->"
                            + (((first.packet[42] & 0xff) << 8) | (first.packet[43] & 0xff)) : "";
                    log("IDS PASSTHROUGH IP RX: count="
                            + passthrough.size()
                            + " proto=" + nextHeader + " len=" + len + ports
                            + "; payload logged=false; consumer attached=false.");
                }
            } finally {
                for (NormalLinkPipeSession.DeliveredIp packet
                        : passthrough) {
                    packet.destroy();
                }
                passthrough.clear();
            }

            List<byte[]> controls =
                    output.normalControlMessages();
            try {
                if (!controls.isEmpty()) {
                    log("IDS NORMAL CONTROL RX: count="
                            + controls.size()
                            + "; bytes logged=false.");
                }
            } finally {
                for (byte[] control : controls) {
                    HalTransportSession.wipe(
                            control);
                }
                controls.clear();
            }
        }

        for (IdsBootstrapState.Action action : actions) {
            runner.executeIdsBootstrapAction(
                    action,
                    remoteCid);
        }
        for (AppleWatchInitialSetupIdsAdapter.Action action :
                initialActions) {
            session.postCommit.executeInitialSetupAction(
                    action,
                    remoteCid);
        }
        runner.maybeProgressIdsDeviceInfo(remoteCid);
        runner.maybeFlushPendingNanoSetup(
                remoteCid);
        runner.maybeStartNanoRegistryProbe(remoteCid);
    }

    static String idsServiceLogLabel(
            String service) {
        if (service == null) {
            return "none";
        }
        if (IdsIpsecServiceRoute.CONTROL_SERVICE.equals(
                service)) {
            return "control";
        }
        if (NanoRegistryInitialIdsRoute
                .classDSetup()
                .serviceConnectorName
                .encode()
                .equals(
                        service)) {
            return "nano-class-d";
        }
        if (NanoRegistryInitialIdsRoute
                .classCProperties()
                .serviceConnectorName
                .encode()
                .equals(
                        service)) {
            return "nano-class-c";
        }
        int slash =
                service.lastIndexOf(
                        '/');
        if (slash >= 0
                && slash + 1 < service.length()
                && service.substring(
                        slash + 1).startsWith(
                        "UTunDelivery-")) {
            return service.substring(
                    slash + 1);
        }
        return "other";
    }

    static String idsTopicLogLabel(
            String topic) {
        if (topic == null) {
            return "none";
        }
        if (NanoRegistryPropertyCodec.CLASS_D_SERVICE.equals(
                topic)) {
            return "nano-class-d";
        }
        if (NanoRegistryPropertyCodec.CLASS_C_SERVICE.equals(
                topic)) {
            return "nano-class-c";
        }
        if (IdsControlChannelCodec.isKnownTypeName(
                topic)) {
            return topic;
        }
        int slash =
                topic.indexOf(
                        '/');
        if (slash > 0
                && IdsControlChannelCodec.isKnownTypeName(
                        topic.substring(
                                0,
                                slash))) {
            return topic;
        }
        if (IdsApplicationRoute.PB_BRIDGE_SERVICE.equals(
                topic)) {
            return "pbbridge";
        }
        if (IdsApplicationRoute.PAIRED_SYNC_SERVICE.equals(
                topic)) {
            return "pairedsync";
        }
        if (IdsApplicationRoute.PREFERENCE_SYNC_SERVICE.equals(
                topic)) {
            return "preferences";
        }
        final String alloyPrefix =
                "com.apple.private.alloy.";
        if (topic.startsWith(
                alloyPrefix)
                && topic.length() > alloyPrefix.length()) {
            return topic.substring(
                    alloyPrefix.length());
        }
        return "other";
    }
}
