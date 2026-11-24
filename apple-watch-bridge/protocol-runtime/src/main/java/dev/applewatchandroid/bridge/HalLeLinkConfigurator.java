package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import static dev.applewatchandroid.bridge.ProtocolHost.*;

/**
 * Controller LE capability setup, connection data-length/PHY negotiation, and
 * HCI/ACL diagnostic logging for {@link HalTransportSession}.
 */
final class HalLeLinkConfigurator {
    private final HalTransportSession session;

    HalLeLinkConfigurator(HalTransportSession session) {
        this.session = session;
    }

    void disconnectBeforeFreshPairing() throws Exception {
        if (session.connectionHandle < 0) return;
        int oldHandle = session.connectionHandle;
        session.sendCommandStatus(HciCodec.OPCODE_DISCONNECT, HciCodec.buildDisconnectParameters(oldHandle));
        long deadline = BridgeClock.elapsedRealtime() + 3_000;
        while (BridgeClock.elapsedRealtime() < deadline) {
            HalTransportSession.Packet packet = session.nextPacket(deadline);
            if (packet.kind != HalTransportSession.PacketKind.EVENT) continue;
            HciCodec.DisconnectionComplete disconnected = HciCodec.parseDisconnectionComplete(packet.bytes);
            if (disconnected != null && disconnected.connectionHandle == oldHandle) {
                if (disconnected.status != 0) throw new HostException("Controller did not close the previous Watch connection");
                session.connectionHandle = -1;
                resetObservedLeLinkState();
                session.aclReassembler.reset();
                session.normalLink.fixedChannelRouter.reset();
                session.normalLink.normalAttServer.reset();
                session.clearDeferredSmpPdus();
                session.finalPairingAckAtMs = -1;
                session.smpRequestSubmittedAtMs = -1;
                session.pairingSmpReceived = 0;
                log("PAIRED TO FRESH: previous HCI connection is closed; new connection may start.");
                return;
            }
        }
        throw new HostException("Previous Watch connection did not close before fresh pairing");
    }

    void configureLocalLeCapabilities() throws Exception {
        session.localLeDataLengthSupported = false;
        session.localLe2mPhySupported = false;
        session.preferredLeTxOctets = HciCodec.LE_MAX_DATA_OCTETS;
        session.preferredLeTxTimeUs = HciCodec.LE_MAX_UNCODED_DATA_TIME_US;

        HciCodec.CommandComplete featuresComplete = sendOptionalCommandComplete(
                HciCodec.OPCODE_LE_READ_LOCAL_SUPPORTED_FEATURES, new byte[0], -1);
        if (featuresComplete != null && featuresComplete.status == 0) {
            try {
                HciCodec.LeLocalFeatures features =
                        HciCodec.parseLeLocalSupportedFeatures(featuresComplete);
                session.localLeDataLengthSupported = features.dataLengthExtension;
                session.localLe2mPhySupported = features.phy2m;
                log("HCI LE LOCAL FEATURES: edl=" + session.localLeDataLengthSupported
                        + " phy2m=" + session.localLe2mPhySupported + ".");
            } catch (IllegalArgumentException malformed) {
                log("HCI LE LOCAL FEATURES: malformed response ("
                        + safeMessage(malformed) + "); using conservative defaults.");
            }
        } else {
            log(String.format(
                    Locale.US,
                    "HCI LE LOCAL FEATURES: unavailable (status=%s); using conservative defaults.",
                    featuresComplete == null ? "timeout"
                            : String.format(Locale.US, "0x%02X", featuresComplete.status)));
        }

        try {
            session.sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_EVENT_MASK,
                    HciCodec.leEventMask(session.localLeDataLengthSupported, session.localLe2mPhySupported));
        } catch (HostException rejectedMask) {
            if (!session.localLeDataLengthSupported && !session.localLe2mPhySupported) {
                throw rejectedMask;
            }
            log("HCI LE SET EVENT MASK: extended mask rejected ("
                    + safeMessage(rejectedMask) + "); falling back to base mask.");
            session.localLeDataLengthSupported = false;
            session.localLe2mPhySupported = false;
            session.sendCommandComplete(
                    HciCodec.OPCODE_LE_SET_EVENT_MASK,
                    HciCodec.leEventMask());
        }

        if (session.localLeDataLengthSupported) {
            HciCodec.CommandComplete maxLengthComplete = sendOptionalCommandComplete(
                    HciCodec.OPCODE_LE_READ_MAXIMUM_DATA_LENGTH, new byte[0], -1);
            if (maxLengthComplete != null && maxLengthComplete.status == 0) {
                try {
                    HciCodec.LeMaximumDataLength maxLength =
                            HciCodec.parseLeMaximumDataLength(maxLengthComplete);
                    session.preferredLeTxOctets = maxLength.suggestedTxOctets();
                    session.preferredLeTxTimeUs = maxLength.suggestedTxTimeUs();
                } catch (IllegalArgumentException ignored) {
                }
            }
            HciCodec.CommandComplete suggestedComplete = sendOptionalCommandComplete(
                    HciCodec.OPCODE_LE_WRITE_SUGGESTED_DEFAULT_DATA_LENGTH,
                    HciCodec.buildLeWriteSuggestedDefaultDataLengthParameters(
                            session.preferredLeTxOctets, session.preferredLeTxTimeUs),
                    -1);
            log(String.format(
                    Locale.US,
                    "HCI LE SUGGESTED DEFAULT DATA LENGTH: tx=%dB/%dus status=%s.",
                    session.preferredLeTxOctets,
                    session.preferredLeTxTimeUs,
                    suggestedComplete == null ? "timeout"
                            : String.format(Locale.US, "0x%02X", suggestedComplete.status)));
        }

        if (session.localLe2mPhySupported) {
            HciCodec.CommandComplete defaultPhyComplete = sendOptionalCommandComplete(
                    HciCodec.OPCODE_LE_SET_DEFAULT_PHY,
                    HciCodec.buildLeSetDefaultPhyParameters(
                            0, HciCodec.LE_PHY_2M_BIT, HciCodec.LE_PHY_2M_BIT),
                    -1);
            log(String.format(
                    Locale.US,
                    "HCI LE SET DEFAULT PHY: preferred=2M status=%s.",
                    defaultPhyComplete == null ? "timeout"
                            : String.format(Locale.US, "0x%02X", defaultPhyComplete.status)));
        }
    }

    void negotiateLeLinkParameters(int handle) throws Exception {
        if (handle < 0) {
            return;
        }
        if (!session.localLe2mPhySupported && !session.localLeDataLengthSupported) {
            log(String.format(
                    Locale.US,
                    "LE LINK NEGOTIATION SKIPPED: handle=0x%04X localControllerEdl=false localController2mPhy=false.",
                    handle));
            return;
        }

        if (session.localLe2mPhySupported) {
            if (session.leObservedHandle == handle && session.lePhyUpdateObserved) {
                log(String.format(
                        Locale.US,
                        "LE PHY NEGOTIATION: handle=0x%04X already updated (status=0x%02X txPhy=%d rxPhy=%d).",
                        handle, session.leObservedPhyStatus, session.leObservedTxPhy, session.leObservedRxPhy));
            } else {
                Integer phyStatus = sendOptionalCommandStatus(
                        HciCodec.OPCODE_LE_SET_PHY,
                        HciCodec.buildLeSetPhyParameters(
                                handle, 0, HciCodec.LE_PHY_2M_BIT, HciCodec.LE_PHY_2M_BIT, 0),
                        handle);
                if (phyStatus != null && phyStatus == 0) {
                    boolean completed = awaitObservedLeEvent(handle, true);
                    if (!completed) {
                        log(String.format(
                                Locale.US,
                                "LE PHY NEGOTIATION: handle=0x%04X timed out after %d ms; continuing.",
                                handle, HalTransportSession.LE_LINK_NEGOTIATION_TIMEOUT_MS));
                    }
                } else {
                    log(String.format(
                            Locale.US,
                            "LE PHY NEGOTIATION: LE_Set_PHY status=%s; continuing.",
                            phyStatus == null ? "timeout"
                                    : String.format(Locale.US, "0x%02X", phyStatus)));
                }
            }
        }

        if (session.localLeDataLengthSupported) {
            if (session.leObservedHandle == handle && session.leDataLengthChangeObserved) {
                log(String.format(
                        Locale.US,
                        "LE DATA LENGTH NEGOTIATION: handle=0x%04X already updated (tx=%dB rx=%dB).",
                        handle, session.leObservedMaxTxOctets, session.leObservedMaxRxOctets));
            } else {
                HciCodec.CommandComplete dleComplete = sendOptionalCommandComplete(
                        HciCodec.OPCODE_LE_SET_DATA_LENGTH,
                        HciCodec.buildLeSetDataLengthParameters(
                                handle, session.preferredLeTxOctets, session.preferredLeTxTimeUs),
                        handle);
                if (dleComplete != null && dleComplete.status == 0) {
                    boolean completed = awaitObservedLeEvent(handle, false);
                    if (!completed) {
                        log(String.format(
                                Locale.US,
                                "LE DATA LENGTH NEGOTIATION: handle=0x%04X timed out after %d ms; continuing.",
                                handle, HalTransportSession.LE_LINK_NEGOTIATION_TIMEOUT_MS));
                    }
                } else {
                    log(String.format(
                            Locale.US,
                            "LE DATA LENGTH NEGOTIATION: LE_Set_Data_Length status=%s; continuing.",
                            dleComplete == null ? "timeout"
                                    : String.format(Locale.US, "0x%02X", dleComplete.status)));
                }
            }
        }

        boolean phyReady = session.leObservedHandle == handle && session.lePhyUpdateObserved;
        boolean dleReady = session.leObservedHandle == handle && session.leDataLengthChangeObserved;
        log(String.format(
                Locale.US,
                "LE LINK NEGOTIATION DONE: handle=0x%04X phyObserved=%b(status=%s,tx=%d,rx=%d) dleObserved=%b(tx=%dB,rx=%dB).",
                handle,
                phyReady,
                phyReady ? String.format(Locale.US, "0x%02X", session.leObservedPhyStatus) : "none",
                phyReady ? session.leObservedTxPhy : -1,
                phyReady ? session.leObservedRxPhy : -1,
                dleReady,
                dleReady ? session.leObservedMaxTxOctets : -1,
                dleReady ? session.leObservedMaxRxOctets : -1));
    }

    boolean awaitObservedLeEvent(int handle, boolean waitForPhy) throws Exception {
        long deadline = BridgeClock.elapsedRealtime() + HalTransportSession.LE_LINK_NEGOTIATION_TIMEOUT_MS;
        List<HalTransportSession.Packet> deferred = new ArrayList<>();
        try {
            while (true) {
                if (waitForPhy && session.leObservedHandle == handle && session.lePhyUpdateObserved) {
                    return true;
                }
                if (!waitForPhy && session.leObservedHandle == handle && session.leDataLengthChangeObserved) {
                    return true;
                }
                if (BridgeClock.elapsedRealtime() >= deadline) {
                    return false;
                }
                HalTransportSession.Packet packet;
                try {
                    packet = session.nextPacket(deadline);
                } catch (TimeoutException timedOut) {
                    return false;
                }
                if (packet.kind == HalTransportSession.PacketKind.ACL) {
                    deferred.add(packet);
                    continue;
                }
                if (packet.kind == HalTransportSession.PacketKind.EVENT) {
                    HciCodec.DisconnectionComplete disconnected =
                            HciCodec.parseDisconnectionComplete(packet.bytes);
                    if (disconnected != null && disconnected.connectionHandle == handle) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Watch disconnected during LE link negotiation (reason=0x%02X)",
                                disconnected.reason));
                    }
                }
            }
        } finally {
            restoreDeferredControllerPackets(deferred);
        }
    }

    HciCodec.CommandComplete sendOptionalCommandComplete(
            int opcode, byte[] parameters, int activeHandle) throws Exception {
        session.checkStop();
        session.hci.sendHciCommand(HciCodec.buildCommand(opcode, parameters));
        long deadline = BridgeClock.elapsedRealtime() + COMMAND_TIMEOUT_MS;
        List<HalTransportSession.Packet> deferred = new ArrayList<>();
        try {
            while (BridgeClock.elapsedRealtime() < deadline) {
                HalTransportSession.Packet packet;
                try {
                    packet = session.nextPacket(deadline);
                } catch (TimeoutException timedOut) {
                    break;
                }
                if (packet.kind == HalTransportSession.PacketKind.ACL) {
                    deferred.add(packet);
                    continue;
                }
                if (packet.kind != HalTransportSession.PacketKind.EVENT) {
                    continue;
                }
                if (activeHandle >= 0) {
                    HciCodec.DisconnectionComplete disconnected =
                            HciCodec.parseDisconnectionComplete(packet.bytes);
                    if (disconnected != null && disconnected.connectionHandle == activeHandle) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Watch disconnected while awaiting HCI command 0x%04X (reason=0x%02X)",
                                opcode,
                                disconnected.reason));
                    }
                }
                HciCodec.CommandComplete complete =
                        HciCodec.parseCommandComplete(packet.bytes);
                if (complete != null && complete.opcode == opcode) {
                    return complete;
                }
                HciCodec.CommandStatus status =
                        HciCodec.parseCommandStatus(packet.bytes);
                if (status != null && status.opcode == opcode && status.status != 0) {
                    return new HciCodec.CommandComplete(
                            opcode, status.status, new byte[]{(byte) status.status});
                }
            }
            return null;
        } finally {
            restoreDeferredControllerPackets(deferred);
        }
    }

    Integer sendOptionalCommandStatus(
            int opcode, byte[] parameters, int activeHandle) throws Exception {
        session.checkStop();
        session.hci.sendHciCommand(HciCodec.buildCommand(opcode, parameters));
        long deadline = BridgeClock.elapsedRealtime() + COMMAND_TIMEOUT_MS;
        List<HalTransportSession.Packet> deferred = new ArrayList<>();
        try {
            while (BridgeClock.elapsedRealtime() < deadline) {
                HalTransportSession.Packet packet;
                try {
                    packet = session.nextPacket(deadline);
                } catch (TimeoutException timedOut) {
                    break;
                }
                if (packet.kind == HalTransportSession.PacketKind.ACL) {
                    deferred.add(packet);
                    continue;
                }
                if (packet.kind != HalTransportSession.PacketKind.EVENT) {
                    continue;
                }
                if (activeHandle >= 0) {
                    HciCodec.DisconnectionComplete disconnected =
                            HciCodec.parseDisconnectionComplete(packet.bytes);
                    if (disconnected != null && disconnected.connectionHandle == activeHandle) {
                        throw new HostException(String.format(
                                Locale.US,
                                "Watch disconnected while awaiting HCI command 0x%04X (reason=0x%02X)",
                                opcode,
                                disconnected.reason));
                    }
                }
                HciCodec.CommandStatus status =
                        HciCodec.parseCommandStatus(packet.bytes);
                if (status != null && status.opcode == opcode) {
                    return status.status;
                }
                HciCodec.CommandComplete complete =
                        HciCodec.parseCommandComplete(packet.bytes);
                if (complete != null && complete.opcode == opcode) {
                    return complete.status;
                }
            }
            return null;
        } finally {
            restoreDeferredControllerPackets(deferred);
        }
    }

    void restoreDeferredControllerPackets(List<HalTransportSession.Packet> deferred) {
        for (int index = deferred.size() - 1; index >= 0; index--) {
            session.deferredControllerPackets.addFirst(deferred.get(index));
        }
    }

    void resetObservedLeLinkState() {
        session.leObservedHandle = -1;
        session.lePhyUpdateObserved = false;
        session.leObservedPhyStatus = -1;
        session.leObservedTxPhy = -1;
        session.leObservedRxPhy = -1;
        session.leDataLengthChangeObserved = false;
        session.leObservedMaxTxOctets = -1;
        session.leObservedMaxRxOctets = -1;
    }

    void logControllerDiagnostics(HalTransportSession.Packet packet) {
        if (packet.kind != HalTransportSession.PacketKind.EVENT) {
            return;
        }
        HciCodec.LeConnectionComplete connected =
                HciCodec.parseLeConnectionComplete(packet.bytes);
        if (connected != null) {
            try {
                if (connected.status == 0) {
                    resetObservedLeLinkState();
                    session.leObservedHandle = connected.connectionHandle;
                }
            } finally {
                HalTransportSession.wipe(connected.peerAddress);
            }
        }
        HciCodec.LeReadRemoteFeaturesComplete remoteFeatures =
                HciCodec.parseLeReadRemoteFeaturesComplete(packet.bytes);
        if (remoteFeatures != null) {
            log(String.format(
                    Locale.US,
                    "LE REMOTE FEATURES: handle=0x%04X status=0x%02X edl=%b phy2m=%b.",
                    remoteFeatures.connectionHandle,
                    remoteFeatures.status,
                    remoteFeatures.dataLengthExtension,
                    remoteFeatures.phy2m));
        }
        HciCodec.LePhyUpdateComplete phyUpdate =
                HciCodec.parseLePhyUpdateComplete(packet.bytes);
        if (phyUpdate != null) {
            if (phyUpdate.connectionHandle != session.leObservedHandle) {
                resetObservedLeLinkState();
                session.leObservedHandle = phyUpdate.connectionHandle;
            }
            session.lePhyUpdateObserved = true;
            session.leObservedPhyStatus = phyUpdate.status;
            session.leObservedTxPhy = phyUpdate.txPhy;
            session.leObservedRxPhy = phyUpdate.rxPhy;
            log(String.format(
                    Locale.US,
                    "LE PHY UPDATE COMPLETE: handle=0x%04X status=0x%02X txPhy=%d rxPhy=%d.",
                    phyUpdate.connectionHandle,
                    phyUpdate.status,
                    phyUpdate.txPhy,
                    phyUpdate.rxPhy));
        }
        HciCodec.LeDataLengthChange dataLengthChange =
                HciCodec.parseLeDataLengthChange(packet.bytes);
        if (dataLengthChange != null) {
            if (dataLengthChange.connectionHandle != session.leObservedHandle) {
                resetObservedLeLinkState();
                session.leObservedHandle = dataLengthChange.connectionHandle;
            }
            session.leDataLengthChangeObserved = true;
            session.leObservedMaxTxOctets = dataLengthChange.maxTxOctets;
            session.leObservedMaxRxOctets = dataLengthChange.maxRxOctets;
            log(String.format(
                    Locale.US,
                    "LE DATA LENGTH CHANGE: handle=0x%04X tx=%dB/%dus rx=%dB/%dus.",
                    dataLengthChange.connectionHandle,
                    dataLengthChange.maxTxOctets,
                    dataLengthChange.maxTxTimeUs,
                    dataLengthChange.maxRxOctets,
                    dataLengthChange.maxRxTimeUs));
        }
        HciCodec.DisconnectionComplete disconnected =
                HciCodec.parseDisconnectionComplete(packet.bytes);
        if (disconnected != null && disconnected.connectionHandle == session.leObservedHandle) {
            resetObservedLeLinkState();
        }
        Integer hardwareError =
                HciCodec.parseHardwareError(packet.bytes);
        if (hardwareError != null) {
            log(String.format(
                    Locale.US,
                    "HCI HARDWARE ERROR: code=0x%02X raw=%s.",
                    hardwareError,
                    HciCodec.toHex(packet.bytes)));
        }
        Integer overflow =
                HciCodec.parseDataBufferOverflow(packet.bytes);
        if (overflow != null) {
            log(String.format(
                    Locale.US,
                    "HCI DATA BUFFER OVERFLOW: linkType=0x%02X raw=%s.",
                    overflow,
                    HciCodec.toHex(packet.bytes)));
        }
    }

}
