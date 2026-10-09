package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One complete Apple normal-link data pipe above an already created dynamic
 * L2CAP channel.
 *
 * <p>The first ERTM SDU is the raw 38-byte terminus prelude. Subsequent SDUs
 * form the NetworkRelay stream: type 4 carries the independent Class-D and
 * Class-C IKE SAs, while the fixed IPv6 types carry the resulting ESP
 * transport packets.</p>
 */
final class NormalLinkPipeSession implements AutoCloseable {
    String localApplicationDeviceName() { return classD.localApplicationDeviceName(); }
    private final java.util.ArrayDeque<byte[]> deferredOrdinary = new java.util.ArrayDeque<>();
    enum Phase {
        PRELUDE,
        CLASS_D,
        CLASS_C,
        READY,
        POISONED,
        DESTROYED
    }

    public static volatile java.util.function.Consumer<String> diagnosticLogger = null;

    private final L2capErtmSession transport;
    private final NrLinkBluetoothPrelude.LocalRole localRole;
    private final byte[] localPrelude;
    private final byte[] expectedRemotePrelude;
    private final byte[] localClassD;
    private final byte[] remoteClassD;
    private final byte[] localClassC;
    private final byte[] remoteClassC;
    private byte[] storedLocalClassD;
    private byte[] storedRemoteClassD;
    private byte[] storedLocalClassC;
    private byte[] storedRemoteClassC;
    private int lastInboundEspTrafficClass;
    private final OrdinaryClassDSession classD;
    private final OrdinaryClassDSession classC;
    private NetworkRelayPacketCodec.StreamDecoder relayDecoder;

    private Phase phase = Phase.PRELUDE;
    private boolean started;
    private boolean remotePreludeAccepted;
    private boolean addressesValidated;
    private boolean localClassCUnlockAnnounced;
    private boolean ordinaryContinuationStarted;
    private boolean resourcesDestroyed;

    /**
     * Ownership of the transport, address set, and both ordinary sessions
     * transfers to this pipe.
     */
    NormalLinkPipeSession(
            L2capErtmSession transport,
            NrLinkBluetoothPrelude.LocalRole localRole,
            byte[] localPrelude,
            byte[] expectedRemotePrelude,
            AppleNetworkRelayInnerAddresses addresses,
            OrdinaryClassDSession classD,
            OrdinaryClassDSession classC) {
        this(
                transport,
                localRole,
                localPrelude,
                expectedRemotePrelude,
                addresses,
                classD,
                classC,
                false);
    }

    private NormalLinkPipeSession(
            L2capErtmSession transport,
            NrLinkBluetoothPrelude.LocalRole localRole,
            byte[] localPrelude,
            byte[] expectedRemotePrelude,
            AppleNetworkRelayInnerAddresses addresses,
            OrdinaryClassDSession classD,
            OrdinaryClassDSession classC,
            boolean preludeComplete) {
        if (transport == null
                || localRole == null
                || addresses == null
                || classD == null
                || classC == null) {
            throw new IllegalArgumentException(
                    "Complete normal-link pipe state is required");
        }
        requirePrelude(
                "local",
                localPrelude);
        requirePrelude(
                "remote",
                expectedRemotePrelude);
        NrLinkBluetoothPrelude.Parsed local =
                NrLinkBluetoothPrelude.parse(
                        localPrelude);
        NrLinkBluetoothPrelude.Parsed remote =
                NrLinkBluetoothPrelude.parse(
                        expectedRemotePrelude);
        byte[] localUuid = local.uuid();
        byte[] remoteUuid = remote.uuid();
        try {
            if (NrLinkBluetoothPrelude.electLocalRole(
                    localUuid,
                    remoteUuid) != localRole) {
                throw new IllegalArgumentException(
                        "Normal-link role contradicts the two preludes");
            }
        } finally {
            wipe(localUuid);
            wipe(remoteUuid);
        }
        if (classD.localRole() != localRole
                || classC.localRole() != localRole
                || classD.dataClass()
                != OrdinaryIkeAuth.DataClass.CLASS_D
                || classC.dataClass()
                != OrdinaryIkeAuth.DataClass.CLASS_C) {
            throw new IllegalArgumentException(
                    "Ordinary sessions do not match the pipe role/classes");
        }
        if (preludeComplete
                && (transport.nextTxSequence() != 1
                || transport.expectedRemoteTxSequence() != 1
                || transport.outstandingCount() > 1
                || (transport.outstandingCount() == 1
                && transport.oldestOutstandingSequence() != 0))) {
            throw new IllegalArgumentException(
                    "Detached ERTM state is not exactly after the "
                            + "bidirectional TxSeq-zero prelude exchange");
        }

        this.transport = transport;
        this.localRole = localRole;
        this.localPrelude = localPrelude.clone();
        this.expectedRemotePrelude =
                expectedRemotePrelude.clone();
        this.classD = classD;
        this.classC = classC;
        localClassD =
                addresses.localClassD(
                        localRole);
        remoteClassD =
                addresses.remoteClassD(
                        localRole);
        localClassC =
                addresses.localClassC(
                        localRole);
        remoteClassC =
                addresses.remoteClassC(
                        localRole);
        addresses.destroy();
        relayDecoder =
                new NetworkRelayPacketCodec.StreamDecoder(
                        remoteClassD,
                        localClassD);
        if (preludeComplete) {
            started = true;
            remotePreludeAccepted = true;
            phase = Phase.CLASS_D;
        }
    }

    /**
     * Continues an already acknowledged bootstrap without reconstructing the
     * ERTM scheduler. Ownership of the detached transport transfers only
     * after all prelude metadata has been copied.
     */
    static NormalLinkPipeSession resumeAfterPrelude(
            NrLinkBluetoothPipeBootstrap.PreludeHandoff handoff,
            AppleNetworkRelayInnerAddresses addresses,
            OrdinaryClassDSession classD,
            OrdinaryClassDSession classC) {
        if (handoff == null) {
            throw new IllegalArgumentException(
                    "Prelude handoff is required");
        }
        byte[] localPrelude = null;
        byte[] remotePrelude = null;
        L2capErtmSession transport = null;
        try {
            NrLinkBluetoothPrelude.LocalRole localRole =
                    handoff.localRole();
            localPrelude = handoff.localPrelude();
            remotePrelude = handoff.remotePrelude();
            transport = handoff.takeTransport();
            NormalLinkPipeSession output =
                    new NormalLinkPipeSession(
                            transport,
                            localRole,
                            localPrelude,
                            remotePrelude,
                            addresses,
                            classD,
                            classC,
                            true);
            transport = null;
            return output;
        } finally {
            if (transport != null) {
                transport.close();
            }
            wipe(
                    localPrelude);
            wipe(
                    remotePrelude);
            handoff.close();
        }
    }

    /**
     * Restores both independent ordinary IKE identities from one sealed
     * pairing generation and consumes a live post-prelude ERTM handoff.
     *
     * <p>The record contains remote Watch metadata and durable key material.
     * Local Android metadata is supplied separately to prevent the two
     * endpoint identities from being accidentally interchanged.</p>
     */
    static NormalLinkPipeSession resumeFromPairingRecord(
            SecureRandom random,
            NrLinkBluetoothPipeBootstrap.PreludeHandoff handoff,
            PairingSessionRecord record,
            NormalLinkLocalDeviceProfile localProfile) {
        if (random == null
                || handoff == null
                || record == null
                || localProfile == null) {
            throw new IllegalArgumentException(
                    "Complete durable normal-link inputs are required");
        }
        if (!record.hasBluetoothBond()
                || record.hasPendingSmpOob()) {
            throw new IllegalStateException(
                    "Normal link requires a completed SMP bond");
        }

        byte[] localPrelude = null;
        byte[] remotePrelude = null;
        AppleNetworkRelayPairingMaterial classDMaterial = null;
        AppleNetworkRelayPairingMaterial classCMaterial = null;
        OrdinaryIkeAuth.PeerMaterial classDPeer = null;
        OrdinaryIkeAuth.PeerMaterial classCPeer = null;
        OrdinaryIkeAuth.ResponderProfile classDProfile = null;
        OrdinaryIkeAuth.ResponderProfile classCProfile = null;
        AppleNetworkRelayInnerAddresses pipeAddresses = null;
        OrdinaryClassDSession classD = null;
        OrdinaryClassDSession classC = null;
        try {
            NrLinkBluetoothPrelude.LocalRole role =
                    record.addressesConfirmed() && record.localRole() != null
                            ? record.localRole()
                            : handoff.localRole();
            localPrelude = handoff.localPrelude();
            remotePrelude = handoff.remotePrelude();

            classDMaterial =
                    record.restoreLocalMaterial();
            classCMaterial =
                    record.restoreLocalMaterial();
            classDPeer =
                    record.restoreRemotePeerMaterial();
            classCPeer =
                    record.restoreRemotePeerMaterial();
            classDProfile =
                    bindLocalProfile(
                            record,
                            localProfile,
                            localPrelude);
            classCProfile =
                    bindLocalProfile(
                            record,
                            localProfile,
                            localPrelude);

            classD =
                    OrdinaryClassDSession.create(
                            random,
                            role,
                            classDMaterial,
                            classDPeer,
                            localPrelude,
                            remotePrelude,
                            classDProfile);
            classDMaterial = null;
            classDPeer = null;
            classDProfile = null;

            classC =
                    OrdinaryClassDSession.createClassC(
                            random,
                            role,
                            classCMaterial,
                            classCPeer,
                            localPrelude,
                            remotePrelude,
                            classCProfile);
            classCMaterial = null;
            classCPeer = null;
            classCProfile = null;

            pipeAddresses =
                    record.innerAddresses();
            NormalLinkPipeSession output =
                    resumeAfterPrelude(
                            handoff,
                            pipeAddresses,
                            classD,
                            classC);
            pipeAddresses = null;
            classD = null;
            classC = null;
            return output;
        } finally {
            wipe(
                    localPrelude);
            wipe(
                    remotePrelude);
            if (classDMaterial != null) {
                classDMaterial.destroy();
            }
            if (classCMaterial != null) {
                classCMaterial.destroy();
            }
            if (classDPeer != null) {
                classDPeer.destroy();
            }
            if (classCPeer != null) {
                classCPeer.destroy();
            }
            if (classDProfile != null) {
                classDProfile.destroy();
            }
            if (classCProfile != null) {
                classCProfile.destroy();
            }
            if (pipeAddresses != null) {
                pipeAddresses.destroy();
            }
            if (classD != null) {
                classD.close();
            }
            if (classC != null) {
                classC.close();
            }
            handoff.close();
        }
    }

    private static OrdinaryIkeAuth.ResponderProfile bindLocalProfile(
            PairingSessionRecord record,
            NormalLinkLocalDeviceProfile localProfile,
            byte[] localPrelude) {
        AppleNetworkRelayInnerAddresses addresses =
                record.innerAddresses();
        try {
            return localProfile.bind(
                    addresses,
                    localPrelude);
        } finally {
            addresses.destroy();
        }
    }

    /**
     * Sends this endpoint's raw terminus prelude as ERTM TxSeq zero.
     */
    NrLinkBluetoothPrelude.LocalRole localRole() {
        requireUsable();
        return localRole;
    }

    byte[] localClassD() {
        requireUsable();
        return localClassD.clone();
    }

    byte[] remoteClassD() {
        requireUsable();
        return remoteClassD.clone();
    }

    byte[] localClassC() {
        requireUsable();
        return localClassC.clone();
    }

    byte[] remoteClassC() {
        requireUsable();
        return remoteClassC.clone();
    }

    byte[] start() {
        requireUsable();
        if (started) {
            throw new IllegalStateException(
                    "Normal-link pipe was already started");
        }
        started = true;
        return transport.sendInformation(
                localPrelude);
    }

    /**
     * Starts ordinary Class D after a detached prelude bootstrap. The
     * initiator returns one TxSeq-one I-frame; the responder returns an
     * empty list and waits for that frame.
     */
    List<byte[]> continueAfterPrelude() {
        requireUsable();
        if (!started
                || !remotePreludeAccepted
                || phase != Phase.CLASS_D) {
            throw new IllegalStateException(
                    "Normal-link prelude is not complete");
        }
        if (ordinaryContinuationStarted) {
            throw new IllegalStateException(
                    "Ordinary normal-link continuation already started");
        }
        ordinaryContinuationStarted = true;
        List<byte[]> output =
                new ArrayList<>();
        if (localRole
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR) {
            addOrdinary(
                    classD.start(),
                    output);
        }
        return output;
    }

    /**
     * Consumes one complete ERTM payload and returns frames that must be sent
     * immediately plus any authenticated clear IP/control deliveries.
     */
    InboundResult acceptErtmFrame(
            byte[] l2capPayload) {
        requireUsable();
        if (!started) {
            throw new IllegalStateException(
                    "Normal-link local prelude was not sent");
        }
        List<byte[]> outbound =
                new ArrayList<>();
        List<DeliveredIp> deliveredIp =
                new ArrayList<>();
        List<byte[]> controlMessages =
                new ArrayList<>();
        List<String> linkDirectorObservations =
                new ArrayList<>();
        int[] ikeRetransmissions = new int[1];
        int[] ikeSaInitCapabilityFlags = new int[1];
        int[] preSaStaleEspDrops = new int[1];
        boolean emittedInformation = false;
        try (L2capErtmSession.InboundResult transportResult =
                transport.accept(
                        l2capPayload)) {
            outbound.addAll(
                    transportResult
                            .immediateOutboundFrames());
            emittedInformation |= drainDeferredOrdinary(outbound);
            List<byte[]> sdus =
                    transportResult.deliveredSdus();
            try {
                for (byte[] sdu : sdus) {
                    if (!remotePreludeAccepted) {
                        emittedInformation |=
                                acceptRemotePrelude(
                                        sdu,
                                        outbound);
                    } else {
                        emittedInformation |=
                                acceptRelayBytes(
                                        sdu,
                                        outbound,
                                        deliveredIp,
                                        controlMessages,
                                        ikeRetransmissions,
                                        ikeSaInitCapabilityFlags,
                                        preSaStaleEspDrops,
                                        linkDirectorObservations);
                    }
                }
            } finally {
                wipeAll(
                        sdus);
            }
            if (transportResult.acknowledgementRecommended
                    && !emittedInformation) {
                outbound.add(
                        transport.buildReceiverReady());
            }
            return new InboundResult(
                    outbound,
                    deliveredIp,
                    controlMessages,
                    ikeRetransmissions[0],
                    ikeSaInitCapabilityFlags[0],
                    preSaStaleEspDrops[0],
                    linkDirectorObservations,
                    transport.lastInboundSupervisory(),
                    transport.lastInboundSupervisoryFunction(),
                    transport.lastInboundTxSequence(),
                    transport.lastInboundRequestSequence(),
                    transport.lastInboundInformationLength());
        } catch (RuntimeException failure) {
            wipeAll(
                    outbound);
            destroyDelivered(
                    deliveredIp);
            wipeAll(
                    controlMessages);
            linkDirectorObservations.clear();
            poison();
            throw failure;
        }
    }

    /**
     * Announces the local Class-C availability on the established Class-D
     * IKE SA. The returned ERTM frame is ready for the dynamic channel.
     */
    byte[] announceLocalClassCUnlocked() {
        requireUsable();
        if (!classD.established()) {
            throw new IllegalStateException(
                    "Class D is not ready for a Class-C unlock notify");
        }
        localClassCUnlockAnnounced = true;
        OrdinaryClassDSession.Outbound outbound =
                classD.announceLocalClassCUnlocked();
        return encodeOrdinary(
                outbound);
    }

    List<byte[]> requestIkeDeletes() {
        requireUsable();
        List<byte[]> frames = new ArrayList<>();
        try {
            // Both independent IKE SAs keep the NR link alive. Close C first.
            if (classC.established()) addOrdinary(classC.requestIkeDelete(), frames);
            addOrdinary(classD.requestIkeDelete(), frames);
            return frames;
        } catch (RuntimeException failure) {
            wipeAll(frames);
            throw failure;
        }
    }

    /**
     * Encrypts one clear IPv6 packet for a selected Apple data class and
     * returns its complete ERTM frame.
     */
    byte[] sendIpv6(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] clearIpv6Packet) {
        requireUsable();
        OrdinaryClassDSession session =
                sessionFor(
                        dataClass);
        if (!session.established()) {
            throw new IllegalStateException(
                    dataClass
                            + " ordinary SA is not established");
        }
        requireOutboundAddresses(
                dataClass,
                clearIpv6Packet);
        // Leave room for IKE responses and link-control requests while a
        // large IPv6/reset burst waits for its ERTM receipts.
        if (transport.transmitBlocked() || !deferredOrdinary.isEmpty()
                || transport.outstandingCount() >= L2capErtmSession.TARGET_TX_WINDOW - 2) {
            return null;
        }
        byte[] protectedPacket = null;
        byte[] relay = null;
        byte[] clearForEsp = clearIpv6Packet;
        try {
            if (dataClass == OrdinaryIkeAuth.DataClass.CLASS_C
                    && lastInboundEspTrafficClass > 0
                    && clearIpv6Packet.length >= 40
                    && clearIpv6Packet[17] == AppleNetworkRelayInnerAddresses.CLASS_D_MARKER
                    && clearIpv6Packet[33] == AppleNetworkRelayInnerAddresses.CLASS_D_MARKER) {
                clearForEsp = clearIpv6Packet.clone();
                int dscp = switch (lastInboundEspTrafficClass) {
                    case 1 -> 8;
                    case 2 -> 26;
                    case 3 -> 46;
                    default -> 0;
                };
                int trafficClass = dscp << 2;
                clearForEsp[0] = (byte) (0x60 | (trafficClass >>> 4));
                clearForEsp[1] = (byte) ((clearForEsp[1] & 0x0f)
                        | ((trafficClass & 0x0f) << 4));
            }
            protectedPacket =
                    session.encryptIpv6Transport(
                            clearForEsp);
            boolean classDAddressesOnClassC =
                    dataClass == OrdinaryIkeAuth.DataClass.CLASS_C
                            && protectedPacket.length >= 40
                            && protectedPacket[17]
                            == AppleNetworkRelayInnerAddresses.CLASS_D_MARKER
                            && protectedPacket[33]
                            == AppleNetworkRelayInnerAddresses.CLASS_D_MARKER;
            if (classDAddressesOnClassC) {
                // The Watch type 3 decoder reads source then destination.
                // encodeLowpanClassC writes the opposite order, so the
                // restored header misses the Class-C SA. Type 2 keeps the
                // stored quartet intact.
                relay = NetworkRelayPacketCodec.encodeUncompressedIp(
                        protectedPacket);
                if (diagnosticLogger != null) {
                    diagnosticLogger.accept(
                            "NORMAL RELAY: stored quartet on Class-C SA as type 2");
                }
            } else if (isLinkLocalOrMulticast(clearForEsp)) {
                // Type 100 rebuilds Class-D context addresses and drops
                // Neighbor Advertisement destined to the Watch link-local.
                relay = NetworkRelayPacketCodec.encodeUncompressedIp(
                        protectedPacket);
                if (diagnosticLogger != null) {
                    diagnosticLogger.accept(
                            "NORMAL RELAY: link-local/multicast as type 2");
                }
            } else {
                relay = NetworkRelayPacketCodec.encodeBestIpv6(
                        protectedPacket,
                        localClassD,
                        remoteClassD);
            }
            return transport.sendInformation(
                    relay);
        } finally {
            if (clearForEsp != clearIpv6Packet) {
                wipe(clearForEsp);
            }
            wipe(protectedPacket);
            wipe(relay);
        }
    }

    Phase phase() {
        return phase;
    }

    boolean classDEstablished() {
        requireUsable();
        return classD.established();
    }

    boolean classCEstablished() {
        requireUsable();
        return classC.established();
    }

    /**
     * Physical watchOS 26.6 completes modern device registration only after
     * both ordinary Class-D and Class-C IKE sessions reach connected.
     */
    boolean registrationBarrierSatisfied() {
        requireUsable();
        return classD.established()
                && classC.established();
    }

    boolean linkDirectorHelloAcknowledged() {
        requireUsable();
        return classD.linkDirectorHelloAcknowledged();
    }

    boolean linkDirectorStateAcknowledged() {
        requireUsable();
        return classD.linkDirectorStateAcknowledged();
    }

    void enableLinkDirectorAnnouncements(long firstIdentifier, java.util.function.Consumer<String> logger) {
        requireUsable();
        classD.enableLinkDirectorAnnouncements(firstIdentifier, logger);
    }

    void requestApplicationService(byte[] request, java.util.function.Consumer<byte[]> listener) {
        requireUsable();
        if (!registrationBarrierSatisfied()) throw new IllegalStateException("Normal registration is incomplete");
        classD.requestApplicationService(request, listener);
        classC.setApplicationServiceListener(listener);
    }

    List<byte[]> pollLinkDirectorAnnouncements(long nowMillis) {
        requireUsable();
        List<byte[]> outbound = new ArrayList<>();
        if (!registrationBarrierSatisfied() || transport.transmitBlocked()
                || transport.outstandingCount() >= L2capErtmSession.TARGET_TX_WINDOW) return outbound;
        OrdinaryClassDSession.Outbound message = classD.pollLinkDirectorAnnouncement(nowMillis);
        if (message != null) addOrdinary(message, outbound);
        return outbound;
    }

    /**
     * Returns whether every authenticated packet in this batch is safe to
     * discard while the IDS consumer is not attached yet.
     *
     * <p>Apple brings up its packet nexus after Class D and before Class C.
     * During that interval the Watch can therefore emit link-local multicast
     * control traffic. Keep this exception deliberately structural and
     * narrow: authenticated Class-D ESP, IPv6, fe80::/10 source, multicast
     * destination, and hop limit one. No clear, Class-C, unicast, or routed
     * packet is admitted by this pre-IDS policy.</p>
     */
    static boolean canDiscardBeforeIdsAttachment(
            List<DeliveredIp> packets) {
        if (packets == null || packets.isEmpty()) {
            return false;
        }
        for (DeliveredIp packet : packets) {
            if (packet == null
                    || !packet
                            .isAuthenticatedLinkLocalMulticast()) {
                return false;
            }
        }
        return true;
    }

    boolean transportSettled() {
        requireUsable();
        return transport.outstandingCount() == 0
                && !transport.transmitBlocked() && deferredOrdinary.isEmpty();
    }

    int ertmOutstandingCount() {
        requireUsable();
        return transport.outstandingCount();
    }

    int ertmOldestTxSequence() {
        requireUsable();
        return transport.oldestOutstandingSequence();
    }

    byte[] copyOldestUnacknowledgedErtmFrame() {
        requireUsable();
        return transport.copyOldestUnacknowledged();
    }

    java.util.List<byte[]> copyUnacknowledgedErtmFrames(int limit) {
        requireUsable();
        return transport.copyUnacknowledged(limit);
    }

    byte[] buildReceiverReady() {
        requireUsable();
        return transport.buildReceiverReady();
    }

    byte[] buildReceiverReadyPoll() {
        requireUsable();
        return transport.buildReceiverReadyPoll();
    }

    /**
     * Returns the authenticated Class-D responder quartet for durable
     * checkpointing. A local initiator receives this from the Watch; a local
     * responder confirms the quartet it supplied itself.
     */
    AppleNetworkRelayInnerAddresses
            copyAuthoritativeAddresses() {
        requireUsable();
        if (!classD.established()) {
            throw new IllegalStateException(
                    "Class D has not authenticated an address quartet");
        }
        validateAuthoritativeAddresses();
        return classD.copyAuthoritativeAddresses();
    }

    private boolean acceptRemotePrelude(
            byte[] sdu,
            List<byte[]> outbound) {
        if (!Arrays.equals(
                expectedRemotePrelude,
                sdu)) {
            throw new IllegalArgumentException(
                    "First normal-pipe ERTM SDU is not the "
                            + "expected remote terminus prelude");
        }
        remotePreludeAccepted = true;
        phase = Phase.CLASS_D;
        ordinaryContinuationStarted = true;
        if (localRole
                != NrLinkBluetoothPrelude.LocalRole.INITIATOR) {
            return false;
        }
        addOrdinary(
                classD.start(),
                outbound);
        return true;
    }

    private boolean acceptRelayBytes(
            byte[] sdu,
            List<byte[]> outbound,
            List<DeliveredIp> deliveredIp,
            List<byte[]> controlMessages,
            int[] ikeRetransmissions,
            int[] ikeSaInitCapabilityFlags,
            int[] preSaStaleEspDrops,
            List<String> linkDirectorObservations) {
        boolean emittedInformation = false;
        List<NetworkRelayPacketCodec.DecodedFrame> frames =
                relayDecoder.push(
                        sdu);
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            logger.accept(String.format(
                    java.util.Locale.US,
                    "[NormalLink] push sduLen=%d -> decodedFrames=%d bufLen=%d",
                    sdu.length,
                    frames.size(),
                    relayDecoder.bufferedLength()));
        }
        try {
            for (int frameIndex = 0;
                    frameIndex < frames.size();
                    frameIndex++) {
                NetworkRelayPacketCodec.DecodedFrame frame =
                        frames.get(frameIndex);
                if (logger != null) {
                    logger.accept(String.format(
                            java.util.Locale.US,
                            "[NormalLink] frame #%d: kind=%s type=%d payloadLen=%d",
                            frameIndex + 1,
                            frame.kind,
                            frame.type,
                            frame.payload.length));
                }
                switch (frame.kind) {
                    case PADDING -> {
                        // Padding has no semantic delivery.
                    }
                    case CONTROL ->
                            controlMessages.add(
                                    frame.payload.clone());
                    case IP -> {
                        if (isDiscardablePreSaStaleEsp(frame)) {
                            preSaStaleEspDrops[0]++;
                        } else {
                            DeliveredIp ip =
                                    acceptIp(
                                            frame,
                                            frameIndex,
                                            frames.size());
                            if (ip != null) {
                                deliveredIp.add(ip);
                            }
                        }
                    }
                    case IKE -> {
                        OrdinaryClassDSession target =
                                selectIkeSession(
                                        frame.payload);
                        OrdinaryClassDSession.Outbound response =
                                target.acceptInboundIke(
                                        frame.payload);
                        if (target.lastInboundWasRetransmission()) {
                            ikeRetransmissions[0]++;
                        }
                        ikeSaInitCapabilityFlags[0] |=
                                target.consumeSaInitCapabilityFlags();
                        String linkDirector =
                                target.consumeLinkDirectorObservation();
                        if (linkDirector != null) {
                            linkDirectorObservations.add(
                                    linkDirector);
                        }
                        if (response != null) {
                            addOrdinary(
                                    response,
                                    outbound);
                            emittedInformation = true;
                        }
                        if (advanceOrdinaryState(
                                outbound)) {
                            emittedInformation = true;
                        }
                    }
                }
            }
            return emittedInformation;
        } finally {
            for (NetworkRelayPacketCodec.DecodedFrame frame
                    : frames) {
                frame.destroy();
            }
        }
    }

    private OrdinaryClassDSession selectIkeSession(
            byte[] ikePacket) {
        if (classD.matchesIkePacket(
                ikePacket)) {
            return classD;
        }
        if (classC.matchesIkePacket(
                ikePacket)) {
            return classC;
        }
        // A responder can receive the Class-C IKE_SA_INIT before Class D
        // reaches READY. The two fresh requests have independent SPIs, so
        // the second one cannot be selected by an established-SA match yet.
        // watchOS starts Class D first; bind the next fresh SA_INIT to the
        // first still-unused responder slot and demultiplex every subsequent
        // exchange by that slot's learned SPI pair.
        if (isFreshSaInitRequest(
                ikePacket)) {
            if (classD.waitingForFreshSaInitRequest()) {
                return classD;
            }
            if (classC.waitingForFreshSaInitRequest()) {
                return classC;
            }
        }
        if (!classD.established()) {
            return classD;
        }
        if (!classC.established()) {
            return classC;
        }
        throw new IllegalArgumentException(
                "IKE packet matches neither established "
                        + "Class-D nor Class-C SA");
    }

    private static boolean isFreshSaInitRequest(
            byte[] packet) {
        if (packet == null
                || packet.length
                != OrdinaryIkeSaInit.REQUEST_LENGTH
                || (packet[16] & 0xff)
                != IkeV2Codec.PAYLOAD_SA
                || (packet[17] & 0xff) != 0x20
                || (packet[18] & 0xff)
                != IkeV2Codec.EXCHANGE_IKE_SA_INIT
                || (packet[19] & 0xff)
                != IkeV2Codec.IKE_FLAG_INITIATOR
                || be32(packet, 20) != 0
                || be32(packet, 24) != packet.length) {
            return false;
        }
        for (int index = 8; index < 16; index++) {
            if (packet[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean advanceOrdinaryState(
            List<byte[]> outbound) {
        if (!classD.established()) {
            phase = Phase.CLASS_D;
            return false;
        }
        validateAuthoritativeAddresses();
        boolean emittedInformation = false;
        if (localRole
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                && !localClassCUnlockAnnounced) {
            addOrdinary(
                    classD.announceLocalClassCUnlocked(),
                    outbound);
            localClassCUnlockAnnounced = true;
            emittedInformation = true;
        }
        if (classC.established()) {
            phase = Phase.READY;
            return emittedInformation;
        }
        phase = Phase.CLASS_C;
        if (localRole
                == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                && classC.phase()
                == OrdinaryClassDSession.Phase.INITIATOR_READY
                && classD.remoteClassCUnlocked()) {
            addOrdinary(
                    classC.start(),
                    outbound);
            emittedInformation = true;
        }
        return emittedInformation;
    }

    private DeliveredIp acceptIp(
            NetworkRelayPacketCodec.DecodedFrame frame,
            int relayFrameIndex,
            int relayFrameCount) {
        byte[] packet =
                frame.ipPacket;
        if (packet == null
                || packet.length == 0) {
            throw new IllegalArgumentException(
                    "NetworkRelay IP delivery is empty");
        }
        int version =
                (packet[0] & 0xf0) >>> 4;
        if (version != 6
                || packet.length < 40
                || (packet[6] & 0xff)
                != OrdinaryChildSaCrypto.ESP_PROTOCOL_NUMBER) {
            return new DeliveredIp(
                    inferClearDataClass(
                            packet),
                    false,
                    packet);
        }

        OrdinaryIkeAuth.DataClass dataClass =
                espDataClass(
                        frame.type,
                        packet,
                        relayFrameIndex,
                        relayFrameCount);
        OrdinaryClassDSession session =
                sessionFor(
                        dataClass);
        if (!session.established()) {
            return null;
        }
        byte[] clear =
                session.decryptIpv6Transport(
                        packet);
        if (!isLinkLocalOrMulticast(clear)
                && (clear[6] & 0xff) == 6) {
            byte[] swapped = clear.clone();
            byte[] classC = clear.clone();
            byte[] classCSwapped = clear.clone();
            try {
                System.arraycopy(clear, 24, swapped, 8, 16);
                System.arraycopy(clear, 8, swapped, 24, 16);
                classC[17] = AppleNetworkRelayInnerAddresses.CLASS_C_MARKER;
                classC[33] = AppleNetworkRelayInnerAddresses.CLASS_C_MARKER;
                System.arraycopy(classC, 24, classCSwapped, 8, 16);
                System.arraycopy(classC, 8, classCSwapped, 24, 16);
                boolean parsedChecksum = tcpChecksumMatches(clear);
                boolean swappedChecksum = tcpChecksumMatches(swapped);
                boolean classCChecksum = tcpChecksumMatches(classC);
                boolean classCSwappedChecksum = tcpChecksumMatches(classCSwapped);
                long espSequence = packet.length >= 48
                        ? ((packet[44] & 0xffL) << 24)
                        | ((packet[45] & 0xffL) << 16)
                        | ((packet[46] & 0xffL) << 8)
                        | (packet[47] & 0xffL)
                        : 0L;
                int espClass = (int) (espSequence >>> 30);
                lastInboundEspTrafficClass = espClass;
                if (diagnosticLogger != null) {
                    diagnosticLogger.accept(
                            "TCP CHECKSUM: "
                                    + Ipv6TcpPacketCodec.checksumFacts(clear)
                                    + " espClass="
                                    + espClass
                                    + " espLow="
                                    + (espSequence & 0x3fffffffL));
                }
                if (!parsedChecksum && swappedChecksum) {
                    System.arraycopy(swapped, 8, clear, 8, 32);
                } else if (!parsedChecksum && classCChecksum) {
                    System.arraycopy(classC, 8, clear, 8, 32);
                } else if (!parsedChecksum && classCSwappedChecksum) {
                    System.arraycopy(classCSwapped, 8, clear, 8, 32);
                }
            } finally {
                wipe(swapped);
                wipe(classC);
                wipe(classCSwapped);
            }
        } else if (!isLinkLocalOrMulticast(clear)) {
            byte[] expectedRemote = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D ? remoteClassD : remoteClassC;
            byte[] expectedLocal = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D ? localClassD : localClassC;
            System.arraycopy(expectedRemote, 0, clear, 8, 16);
            System.arraycopy(expectedLocal, 0, clear, 24, 16);
        }
        java.util.function.Consumer<String> logger = diagnosticLogger;
        if (logger != null) {
            logger.accept(String.format(
                    java.util.Locale.US,
                    "[NormalLink acceptIp] type=%d dataClass=%s encSrc=%s encDst=%s clearSrc=%s clearDst=%s clearLen=%d",
                    frame.type,
                    dataClass,
                    IdsIpv6TcpRouter.formatIpv6(Arrays.copyOfRange(packet, 8, 24)),
                    IdsIpv6TcpRouter.formatIpv6(Arrays.copyOfRange(packet, 24, 40)),
                    IdsIpv6TcpRouter.formatIpv6(Arrays.copyOfRange(clear, 8, 24)),
                    IdsIpv6TcpRouter.formatIpv6(Arrays.copyOfRange(clear, 24, 40)),
                    clear.length));
        }
        try {
            return new DeliveredIp(
                    dataClass,
                    true,
                    clear);
        } finally {
            wipe(clear);
        }
    }

    /**
     * A responder has no usable Child SA until the Watch completes a fresh
     * IKE_AUTH. A link-local multicast ESP packet queued by the Watch's
     * previous, already closed connection can therefore arrive before or
     * during SA_INIT/Intermediate, but cannot be authenticated by this new
     * pipe. Drop only that exact pre-Child-SA shape so ERTM can ACK it and
     * wait for IKE; never decrypt or deliver it.
     */
    private boolean isDiscardablePreSaStaleEsp(
            NetworkRelayPacketCodec.DecodedFrame frame) {
        byte[] packet = frame.ipPacket;
        if (frame.type != NetworkRelayPacketCodec.TYPE_ENCAPSULATED_6LOWPAN
                && frame.type != NetworkRelayPacketCodec.TYPE_UNCOMPRESSED_IP
                && frame.type != NetworkRelayPacketCodec.TYPE_KNOWN_IPV6_ESP
                && frame.type != NetworkRelayPacketCodec.TYPE_KNOWN_IPV6_ESP_ECT0
                && frame.type != NetworkRelayPacketCodec.TYPE_KNOWN_IPV6_ESP_CLASS_C
                && frame.type != NetworkRelayPacketCodec.TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0) {
            return false;
        }
        if (packet == null
                || packet.length
                < NetworkRelayPacketCodec.IPV6_HEADER_LENGTH
                + OrdinaryChildSaCrypto.CHILD_SPI_LENGTH
                + 4
                || ((packet[0] & 0xf0) >>> 4) != 6
                || be16(packet, 4)
                != packet.length
                - NetworkRelayPacketCodec.IPV6_HEADER_LENGTH
                || (packet[6] & 0xff)
                != OrdinaryChildSaCrypto.ESP_PROTOCOL_NUMBER) {
            return false;
        }
        boolean matchesNeitherSa =
                !classD.matchesInboundEspPacket(packet)
                        && !classC.matchesInboundEspPacket(packet);
        if (matchesNeitherSa) {
            return true;
        }
        if (!classD.childSaEstablished() || !classC.childSaEstablished()) {
            return true;
        }
        return false;
    }

    private static boolean tcpChecksumMatches(byte[] packet) {
        Ipv6TcpPacketCodec.Packet decoded = null;
        try {
            decoded = Ipv6TcpPacketCodec.decode(packet, true);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (decoded != null) {
                decoded.close();
            }
        }
    }

    private OrdinaryIkeAuth.DataClass espDataClass(
            int relayType,
            byte[] packet,
            int relayFrameIndex,
            int relayFrameCount) {
        boolean classDSpiMatch =
                classD.matchesInboundEspPacket(
                        packet);
        boolean classCSpiMatch =
                classC.matchesInboundEspPacket(
                        packet);
        if (classDSpiMatch && !classCSpiMatch) {
            return OrdinaryIkeAuth.DataClass.CLASS_D;
        }
        if (classCSpiMatch && !classDSpiMatch) {
            return OrdinaryIkeAuth.DataClass.CLASS_C;
        }
        if (relayType
                == NetworkRelayPacketCodec
                .TYPE_KNOWN_IPV6_ESP_CLASS_C
                || relayType
                == NetworkRelayPacketCodec
                .TYPE_KNOWN_IPV6_ESP_CLASS_C_ECT0) {
            requireInboundAddresses(
                    OrdinaryIkeAuth.DataClass.CLASS_C,
                    packet);
            return OrdinaryIkeAuth.DataClass.CLASS_C;
        }
        if (relayType
                == NetworkRelayPacketCodec
                .TYPE_KNOWN_IPV6_ESP
                || relayType
                == NetworkRelayPacketCodec
                .TYPE_KNOWN_IPV6_ESP_ECT0) {
            requireInboundAddresses(
                    OrdinaryIkeAuth.DataClass.CLASS_D,
                    packet);
            return OrdinaryIkeAuth.DataClass.CLASS_D;
        }
        if (addressesMatch(
                packet,
                remoteClassD,
                localClassD)) {
            return OrdinaryIkeAuth.DataClass.CLASS_D;
        }
        if (addressesMatch(
                packet,
                remoteClassC,
                localClassC)) {
            return OrdinaryIkeAuth.DataClass.CLASS_C;
        }
        throw new IllegalArgumentException(
                "UNKNOWN-SA DIAGNOSTIC: ESP packet cannot be uniquely "
                        + "assigned to a Child SA: "
                        + "relayType="
                        + relayType
                        + " relayFrameIndex="
                        + relayFrameIndex
                        + " relayFrameCount="
                        + relayFrameCount
                        + " ertmTxSeq="
                        + transport.lastInboundTxSequence()
                        + " ertmReqSeq="
                        + transport.lastInboundRequestSequence()
                        + " ertmInformationBytes="
                        + transport.lastInboundInformationLength()
                        + " length="
                        + packet.length
                        + " nextHeader="
                        + (packet[6] & 0xff)
                        + " hopLimit="
                        + (packet[7] & 0xff)
                        + " payloadLengthMatches="
                        + (be16(packet, 4)
                        == packet.length
                        - NetworkRelayPacketCodec.IPV6_HEADER_LENGTH)
                        + " sourceKind="
                        + safeAddressKind(
                                packet,
                                8)
                        + " destinationKind="
                        + safeAddressKind(
                                packet,
                                24)
                        + " classDSpiMatch="
                        + classDSpiMatch
                        + " classCSpiMatch="
                        + classCSpiMatch
                        + " localRole="
                        + localRole
                        + " pipePhase="
                        + phase
                        + " continuationStarted="
                        + ordinaryContinuationStarted
                        + " classDPhase="
                        + classD.phase()
                        + " classCPhase="
                        + classC.phase()
                        + " classDChildEstablished="
                        + classD.childSaEstablished()
                        + " classCChildEstablished="
                        + classC.childSaEstablished()
                        + " classDEstablished="
                        + classD.established()
                        + " classCEstablished="
                        + classC.established()
                        + " stalePredicateRole="
                        + (localRole
                        == NrLinkBluetoothPrelude.LocalRole.RESPONDER)
                        + " stalePredicatePhase="
                        + (phase == Phase.CLASS_D)
                        + " stalePredicateNoDChild="
                        + !classD.childSaEstablished()
                        + " stalePredicateNoCChild="
                        + !classC.childSaEstablished()
                        + " stalePredicateType3="
                        + (relayType
                        == NetworkRelayPacketCodec
                                .TYPE_ENCAPSULATED_6LOWPAN)
                        + "; address/SPI/packet bytes logged=false");
    }

    private OrdinaryIkeAuth.DataClass inferClearDataClass(
            byte[] packet) {
        if (packet.length >= 40
                && ((packet[0] & 0xf0) >>> 4) == 6) {
            if (addressesMatch(
                    packet,
                    remoteClassD,
                    localClassD)) {
                return OrdinaryIkeAuth.DataClass.CLASS_D;
            }
            if (addressesMatch(
                    packet,
                    remoteClassC,
                    localClassC)) {
                return OrdinaryIkeAuth.DataClass.CLASS_C;
            }
        }
        return null;
    }

    private void validateAuthoritativeAddresses() {
        if (addressesValidated) {
            return;
        }
        AppleNetworkRelayInnerAddresses learned =
                classD.copyAuthoritativeAddresses();
        byte[] learnedLocalD =
                learned.localClassD(
                        localRole);
        byte[] learnedRemoteD =
                learned.remoteClassD(
                        localRole);
        byte[] learnedLocalC =
                learned.localClassC(
                        localRole);
        byte[] learnedRemoteC =
                learned.remoteClassC(
                        localRole);
        try {
            if (localRole
                    == NrLinkBluetoothPrelude.LocalRole.RESPONDER) {
                if (!Arrays.equals(
                        localClassD,
                        learnedLocalD)
                        || !Arrays.equals(
                        remoteClassD,
                        learnedRemoteD)
                        || !Arrays.equals(
                        localClassC,
                        learnedLocalC)
                        || !Arrays.equals(
                        remoteClassC,
                        learnedRemoteC)) {
                    throw new IllegalArgumentException(
                            "Local IKE responder changed its "
                                    + "authoritative address quartet");
                }
            } else {
                if (relayDecoder.bufferedLength() != 0) {
                    throw new IllegalStateException(
                            "Cannot replace provisional addresses with "
                                    + "a partial NetworkRelay frame buffered");
                }
                boolean projectionChanged =
                        !Arrays.equals(localClassD, learnedLocalD)
                                || !Arrays.equals(remoteClassD, learnedRemoteD);
                if (projectionChanged && diagnosticLogger != null) {
                    diagnosticLogger.accept(
                            "NORMAL IKE: Watch responder quartet replaced "
                                    + "the provisional address projection.");
                }
                System.arraycopy(
                        learnedLocalD,
                        0,
                        localClassD,
                        0,
                        localClassD.length);
                System.arraycopy(
                        learnedRemoteD,
                        0,
                        remoteClassD,
                        0,
                        remoteClassD.length);
                System.arraycopy(
                        learnedLocalC,
                        0,
                        localClassC,
                        0,
                        localClassC.length);
                System.arraycopy(
                        learnedRemoteC,
                        0,
                        remoteClassC,
                        0,
                        remoteClassC.length);
                relayDecoder.close();
                relayDecoder =
                        new NetworkRelayPacketCodec.StreamDecoder(
                                remoteClassD,
                                localClassD);
            }
            addressesValidated = true;
        } finally {
            learned.destroy();
            wipe(learnedLocalD);
            wipe(learnedRemoteD);
            wipe(learnedLocalC);
            wipe(learnedRemoteC);
        }
    }

    private void requireOutboundAddresses(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] packet) {
        requireIpv6(
                packet);
        if (isLinkLocalOrMulticast(packet)) {
            return;
        }
        byte[] source =
                dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                        ? localClassD
                        : localClassC;
        byte[] destination =
                dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                        ? remoteClassD
                        : remoteClassC;
        if (addressesMatch(packet, source, destination)
                || addressesMatch(packet, destination, source)
                || sameHostPair(packet, localClassD, remoteClassD)
                || sameHostPair(packet, remoteClassD, localClassD)) {
            return;
        }
        byte[] storedSource = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                ? storedLocalClassD
                : storedLocalClassC;
        byte[] storedDestination = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                ? storedRemoteClassD
                : storedRemoteClassC;
        if (storedSource != null
                && addressesMatch(packet, storedSource, storedDestination)) {
            return;
        }
        throw new IllegalArgumentException(
                "Outbound IPv6 addresses do not match "
                        + dataClass
                        + " local/remote roles");
    }

    /**
     * Allows TCP closes aimed at the quartet persisted with the pair when
     * this IKE exchange assigned the opposite addresses.
     */
    void acceptStoredInnerAddresses(
            byte[] localD,
            byte[] remoteD,
            byte[] localC,
            byte[] remoteC) {
        requireUsable();
        if (localD == null || remoteD == null || localC == null || remoteC == null) {
            throw new IllegalArgumentException("Stored inner addresses are absent");
        }
        wipe(storedLocalClassD);
        wipe(storedRemoteClassD);
        wipe(storedLocalClassC);
        wipe(storedRemoteClassC);
        storedLocalClassD = localD.clone();
        storedRemoteClassD = remoteD.clone();
        storedLocalClassC = localC.clone();
        storedRemoteClassC = remoteC.clone();
    }

    private static boolean isLinkLocalOrMulticast(byte[] packet) {
        if (packet == null || packet.length < 40) {
            return false;
        }
        if ((packet[24] & 0xff) == 0xff) {
            return true;
        }
        if ((packet[8] & 0xff) == 0xfe && (packet[9] & 0xc0) == 0x80) {
            return true;
        }
        if ((packet[24] & 0xff) == 0xfe && (packet[25] & 0xc0) == 0x80) {
            return true;
        }
        return false;
    }

    private void requireInboundAddresses(
            OrdinaryIkeAuth.DataClass dataClass,
            byte[] packet) {
        byte[] source =
                dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                        ? remoteClassD
                        : remoteClassC;
        byte[] destination =
                dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                        ? localClassD
                        : localClassC;
        if (!addressesMatch(
                packet,
                source,
                destination)) {
            throw new IllegalArgumentException(
                    "Inbound IPv6 addresses do not match "
                            + dataClass
                            + " remote/local roles");
        }
    }

    /** Class marker (byte 9) may differ from the SA that carries the packet. */
    private static boolean sameHostPair(
            byte[] packet,
            byte[] source,
            byte[] destination) {
        if (packet == null
                || packet.length < 40
                || source == null
                || source.length != 16
                || destination == null
                || destination.length != 16) {
            return false;
        }
        for (int index = 0; index < 16; index++) {
            if (index == 9) {
                continue;
            }
            if (packet[8 + index] != source[index]
                    || packet[24 + index] != destination[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean addressesMatch(
            byte[] packet,
            byte[] source,
            byte[] destination) {
        if (packet == null
                || packet.length < 40) {
            return false;
        }
        for (int index = 0;
                index < 16;
                index++) {
            if (packet[8 + index]
                    != source[index]
                    || packet[24 + index]
                    != destination[index]) {
                return false;
            }
        }
        return true;
    }

    private static String safeAddressKind(
            byte[] packet,
            int offset) {
        if (packet == null
                || offset < 0
                || offset > packet.length - 16) {
            return "TRUNCATED";
        }
        if ((packet[offset] & 0xff) == 0xff) {
            return "MULTICAST";
        }
        if ((packet[offset] & 0xff) == 0xfe
                && (packet[offset + 1] & 0xc0) == 0x80) {
            return "LINK_LOCAL";
        }
        byte[] prefix = new byte[]{
                (byte) 0xfd, 0x74, 0x65, 0x72,
                0x6d, 0x6e, 0x75, 0x73,
                0x00
        };
        for (int index = 0;
                index < prefix.length;
                index++) {
            if (packet[offset + index] != prefix[index]) {
                return "OTHER";
            }
        }
        int marker = packet[offset + 9] & 0xff;
        if (marker == 0x0d) {
            return "APPLE_CLASS_D";
        }
        if (marker == 0x0c) {
            return "APPLE_CLASS_C";
        }
        return "APPLE_OTHER";
    }

    private static void requireIpv6(
            byte[] packet) {
        if (packet == null
                || packet.length < 40
                || (packet[0] & 0xf0) != 0x60
                || (((packet[4] & 0xff) << 8)
                | (packet[5] & 0xff))
                != packet.length - 40) {
            throw new IllegalArgumentException(
                    "A complete baseline IPv6 packet is required");
        }
    }

    private OrdinaryClassDSession sessionFor(
            OrdinaryIkeAuth.DataClass dataClass) {
        if (dataClass == null) {
            throw new IllegalArgumentException(
                    "Apple data class is required");
        }
        return dataClass
                == OrdinaryIkeAuth.DataClass.CLASS_D
                ? classD
                : classC;
    }

    private void addOrdinary(
            OrdinaryClassDSession.Outbound ordinary,
            List<byte[]> destination) {
        byte[] frame = encodeOrdinary(ordinary);
        if (frame != null) destination.add(frame);
    }

    private boolean drainDeferredOrdinary(List<byte[]> destination) {
        boolean emitted = false;
        while (!deferredOrdinary.isEmpty() && !transport.transmitBlocked()
                && transport.outstandingCount() < L2capErtmSession.TARGET_TX_WINDOW) {
            byte[] relay = deferredOrdinary.peekFirst();
            byte[] frame = transport.sendInformation(relay);
            wipe(deferredOrdinary.removeFirst());
            destination.add(frame);
            emitted = true;
        }
        return emitted;
    }

    private byte[] encodeOrdinary(
            OrdinaryClassDSession.Outbound ordinary) {
        byte[] relay = null;
        try {
            relay =
                    NetworkRelayPacketCodec.encodeIke(
                            ordinary.ikePacket);
            if (transport.transmitBlocked() || !deferredOrdinary.isEmpty()
                    || transport.outstandingCount() >= L2capErtmSession.TARGET_TX_WINDOW) {
                if (deferredOrdinary.size() >= 32) throw new IllegalStateException("ERTM deferred IKE queue full");
                deferredOrdinary.addLast(relay.clone());
                return null;
            }
            return transport.sendInformation(
                    relay);
        } finally {
            wipe(relay);
            ordinary.destroy();
        }
    }

    private void requireUsable() {
        if (phase == Phase.POISONED) {
            throw new IllegalStateException(
                    "Normal-link pipe is poisoned");
        }
        if (phase == Phase.DESTROYED) {
            throw new IllegalStateException(
                    "Normal-link pipe is destroyed");
        }
    }

    private void poison() {
        destroyResources();
        phase = Phase.POISONED;
    }

    private void destroyResources() {
        if (resourcesDestroyed) {
            return;
        }
        resourcesDestroyed = true;
        while (!deferredOrdinary.isEmpty()) wipe(deferredOrdinary.removeFirst());
        relayDecoder.close();
        classD.close();
        classC.close();
        transport.close();
        wipe(localPrelude);
        wipe(expectedRemotePrelude);
        wipe(localClassD);
        wipe(remoteClassD);
        wipe(localClassC);
        wipe(remoteClassC);
        wipe(storedLocalClassD);
        wipe(storedRemoteClassD);
        wipe(storedLocalClassC);
        wipe(storedRemoteClassC);
    }

    @Override
    public void close() {
        if (phase == Phase.DESTROYED) {
            return;
        }
        destroyResources();
        phase = Phase.DESTROYED;
    }

    private static int be16(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 8)
                | (value[offset + 1] & 0xff);
    }

    private static int be32(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 24)
                | ((value[offset + 1] & 0xff) << 16)
                | ((value[offset + 2] & 0xff) << 8)
                | (value[offset + 3] & 0xff);
    }

    private static void requirePrelude(
            String label,
            byte[] value) {
        if (value == null
                || value.length
                != NrLinkBluetoothPrelude.EXACT_ENCODED_LENGTH) {
            throw new IllegalArgumentException(
                    label + " prelude must contain exactly "
                            + NrLinkBluetoothPrelude
                            .EXACT_ENCODED_LENGTH
                            + " bytes");
        }
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    private static void wipeAll(
            List<byte[]> values) {
        for (byte[] value : values) {
            wipe(value);
        }
        values.clear();
    }

    private static void destroyDelivered(
            List<DeliveredIp> values) {
        for (DeliveredIp value : values) {
            value.destroy();
        }
        values.clear();
    }

    static final class InboundResult implements AutoCloseable {
        private final List<byte[]> immediateErtmFrames;
        private final List<DeliveredIp> deliveredIp;
        private final List<byte[]> controlMessages;
        private final int ikeRetransmissions;
        private final int ikeSaInitCapabilityFlags;
        private final int preSaStaleEspDrops;
        private final List<String> linkDirectorObservations;
        private final boolean ertmSupervisory;
        private final int ertmSupervisoryFunction;
        private final int ertmTxSequence;
        private final int ertmRequestSequence;
        private final int ertmInformationLength;
        private boolean destroyed;

        InboundResult(
                List<byte[]> immediateErtmFrames,
                List<DeliveredIp> deliveredIp,
                List<byte[]> controlMessages,
                int ikeRetransmissions,
                int ikeSaInitCapabilityFlags,
                int preSaStaleEspDrops,
                List<String> linkDirectorObservations,
                boolean ertmSupervisory,
                int ertmSupervisoryFunction,
                int ertmTxSequence,
                int ertmRequestSequence,
                int ertmInformationLength) {
            this.immediateErtmFrames =
                    deepCopy(
                            immediateErtmFrames);
            this.deliveredIp =
                    copyDelivered(
                            deliveredIp);
            this.controlMessages =
                    deepCopy(
                            controlMessages);
            this.ikeRetransmissions = ikeRetransmissions;
            this.ikeSaInitCapabilityFlags =
                    ikeSaInitCapabilityFlags;
            this.preSaStaleEspDrops = preSaStaleEspDrops;
            this.linkDirectorObservations =
                    new ArrayList<>(linkDirectorObservations);
            this.ertmSupervisory = ertmSupervisory;
            this.ertmSupervisoryFunction =
                    ertmSupervisoryFunction;
            this.ertmTxSequence = ertmTxSequence;
            this.ertmRequestSequence =
                    ertmRequestSequence;
            this.ertmInformationLength =
                    ertmInformationLength;
            wipeAll(
                    immediateErtmFrames);
            destroyDelivered(
                    deliveredIp);
            wipeAll(
                    controlMessages);
            linkDirectorObservations.clear();
        }

        List<byte[]> immediateErtmFrames() {
            requireAlive();
            return deepCopy(
                    immediateErtmFrames);
        }

        List<DeliveredIp> deliveredIp() {
            requireAlive();
            return copyDelivered(
                    deliveredIp);
        }

        List<byte[]> controlMessages() {
            requireAlive();
            return deepCopy(
                    controlMessages);
        }

        int ikeRetransmissions() {
            requireAlive();
            return ikeRetransmissions;
        }

        int ikeSaInitCapabilityFlags() {
            requireAlive();
            return ikeSaInitCapabilityFlags;
        }

        int preSaStaleEspDrops() {
            requireAlive();
            return preSaStaleEspDrops;
        }

        List<String> linkDirectorObservations() {
            requireAlive();
            return List.copyOf(linkDirectorObservations);
        }

        boolean ertmSupervisory() {
            requireAlive();
            return ertmSupervisory;
        }

        int ertmSupervisoryFunction() {
            requireAlive();
            return ertmSupervisoryFunction;
        }

        int ertmTxSequence() {
            requireAlive();
            return ertmTxSequence;
        }

        int ertmRequestSequence() {
            requireAlive();
            return ertmRequestSequence;
        }

        int ertmInformationLength() {
            requireAlive();
            return ertmInformationLength;
        }

        private void requireAlive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Normal-link inbound result was destroyed");
            }
        }

        @Override
        public void close() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            wipeAll(
                    immediateErtmFrames);
            destroyDelivered(
                    deliveredIp);
            wipeAll(
                    controlMessages);
            linkDirectorObservations.clear();
        }
    }

    static final class DeliveredIp {
        final OrdinaryIkeAuth.DataClass dataClass;
        final boolean protectedByEsp;
        final byte[] packet;
        private boolean destroyed;

        DeliveredIp(
                OrdinaryIkeAuth.DataClass dataClass,
                boolean protectedByEsp,
                byte[] packet) {
            this.dataClass = dataClass;
            this.protectedByEsp = protectedByEsp;
            this.packet = packet.clone();
        }

        DeliveredIp copy() {
            requireAlive();
            return new DeliveredIp(
                    dataClass,
                    protectedByEsp,
                    packet);
        }

        String safeStructure() {
            requireAlive();
            int version = packet.length == 0
                    ? -1
                    : (packet[0] & 0xf0) >>> 4;
            int nextHeader = version == 6 && packet.length >= 40
                    ? packet[6] & 0xff
                    : -1;
            int hopLimit = version == 6 && packet.length >= 40
                    ? packet[7] & 0xff
                    : -1;
            boolean sourceMulticast = version == 6
                    && packet.length >= 40
                    && (packet[8] & 0xff) == 0xff;
            boolean sourceLinkLocal = version == 6
                    && packet.length >= 40
                    && (packet[8] & 0xff) == 0xfe
                    && (packet[9] & 0xc0) == 0x80;
            boolean destinationMulticast = version == 6
                    && packet.length >= 40
                    && (packet[24] & 0xff) == 0xff;
            return "version="
                    + version
                    + " length="
                    + packet.length
                    + " nextHeader="
                    + nextHeader
                    + " hopLimit="
                    + hopLimit
                    + " sourceMulticast="
                    + sourceMulticast
                    + " sourceLinkLocal="
                    + sourceLinkLocal
                    + " destinationMulticast="
                    + destinationMulticast
                    + " protectedByEsp="
                    + protectedByEsp
                    + " dataClass="
                    + dataClass
                    + "; address and payload bytes logged=false";
        }

        boolean isAuthenticatedLinkLocalMulticast() {
            requireAlive();
            return protectedByEsp
                    && (dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                            || dataClass == OrdinaryIkeAuth.DataClass.CLASS_C)
                    && packet.length >= 40
                    && ((packet[0] & 0xf0) >>> 4) == 6
                    && (packet[7] & 0xff) == 1
                    && (packet[8] & 0xff) == 0xfe
                    && (packet[9] & 0xc0) == 0x80
                    && (packet[24] & 0xff) == 0xff;
        }

        boolean isAuthenticatedClassDLinkLocalMulticast() {
            return isAuthenticatedLinkLocalMulticast()
                    && dataClass == OrdinaryIkeAuth.DataClass.CLASS_D;
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            destroyed = true;
            wipe(packet);
        }

        private void requireAlive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Delivered IP packet was destroyed");
            }
        }
    }

    private static List<byte[]> deepCopy(
            List<byte[]> values) {
        if (values.isEmpty()) {
            return new ArrayList<>();
        }
        List<byte[]> output =
                new ArrayList<>(
                        values.size());
        for (byte[] value : values) {
            output.add(
                    value.clone());
        }
        return output;
    }

    private static List<DeliveredIp> copyDelivered(
            List<DeliveredIp> values) {
        if (values.isEmpty()) {
            return new ArrayList<>();
        }
        List<DeliveredIp> output =
                new ArrayList<>(
                        values.size());
        for (DeliveredIp value : values) {
            output.add(
                    value.copy());
        }
        return output;
    }
}
