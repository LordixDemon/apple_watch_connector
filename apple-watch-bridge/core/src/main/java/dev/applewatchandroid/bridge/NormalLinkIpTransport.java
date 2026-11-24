package dev.applewatchandroid.bridge;

import java.util.Arrays;

/**
 * Inner Class-D/Class-C IPv6 addressing, ESP encryption/decryption policy, and
 * STALE/authoritative address verification for one {@link NormalLinkPipeSession}.
 */
final class NormalLinkIpTransport {
    private final NrLinkBluetoothPrelude.LocalRole localRole;
    private final byte[] localClassD;
    private final byte[] remoteClassD;
    private final byte[] localClassC;
    private final byte[] remoteClassC;
    private byte[] storedLocalClassD;
    private byte[] storedRemoteClassD;
    private byte[] storedLocalClassC;
    private byte[] storedRemoteClassC;
    private int lastInboundEspTrafficClass;
    private boolean addressesValidated;

    NormalLinkIpTransport(
            NrLinkBluetoothPrelude.LocalRole localRole,
            AppleNetworkRelayInnerAddresses addresses) {
        this.localRole = localRole;
        this.localClassD = addresses.localClassD(localRole);
        this.remoteClassD = addresses.remoteClassD(localRole);
        this.localClassC = addresses.localClassC(localRole);
        this.remoteClassC = addresses.remoteClassC(localRole);
        addresses.destroy();
    }

    byte[] localClassD() {
        return localClassD.clone();
    }

    byte[] remoteClassD() {
        return remoteClassD.clone();
    }

    byte[] localClassC() {
        return localClassC.clone();
    }

    byte[] remoteClassC() {
        return remoteClassC.clone();
    }

    NetworkRelayPacketCodec.StreamDecoder newRelayDecoder() {
        return new NetworkRelayPacketCodec.StreamDecoder(
                remoteClassD,
                localClassD);
    }

    void acceptStoredInnerAddresses(
            byte[] localD,
            byte[] remoteD,
            byte[] localC,
            byte[] remoteC) {
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

    byte[] sendIpv6(
            OrdinaryIkeAuth.DataClass dataClass,
            OrdinaryClassDSession session,
            L2capErtmSession transport,
            byte[] clearIpv6Packet) {
        requireOutboundAddresses(
                dataClass,
                clearIpv6Packet);
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
                if (NormalLinkPipeSession.diagnosticLogger != null) {
                    NormalLinkPipeSession.diagnosticLogger.accept(
                            "NORMAL RELAY: stored quartet on Class-C SA as type 2");
                }
            } else if (isLinkLocalOrMulticast(clearForEsp)) {
                // Type 100 rebuilds Class-D context addresses and drops
                // Neighbor Advertisement destined to the Watch link-local.
                relay = NetworkRelayPacketCodec.encodeUncompressedIp(
                        protectedPacket);
                if (NormalLinkPipeSession.diagnosticLogger != null) {
                    NormalLinkPipeSession.diagnosticLogger.accept(
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

    NormalLinkPipeSession.DeliveredIp acceptIp(
            NetworkRelayPacketCodec.DecodedFrame frame,
            int relayFrameIndex,
            int relayFrameCount,
            OrdinaryClassDSession classD,
            OrdinaryClassDSession classC,
            L2capErtmSession transport,
            NormalLinkPipeSession.Phase phase,
            boolean ordinaryContinuationStarted) {
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
            return new NormalLinkPipeSession.DeliveredIp(
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
                        relayFrameCount,
                        classD,
                        classC,
                        transport,
                        phase,
                        ordinaryContinuationStarted);
        OrdinaryClassDSession session =
                dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                        ? classD
                        : classC;
        if (!session.established()) {
            return null;
        }
        byte[] clear =
                session.decryptIpv6Transport(
                        packet);
        if (!isLinkLocalOrMulticast(clear)
                && (clear[6] & 0xff) == 6) {
            byte[] swapped = clear.clone();
            byte[] classCPacket = clear.clone();
            byte[] classCSwapped = clear.clone();
            try {
                System.arraycopy(clear, 24, swapped, 8, 16);
                System.arraycopy(clear, 8, swapped, 24, 16);
                classCPacket[17] = AppleNetworkRelayInnerAddresses.CLASS_C_MARKER;
                classCPacket[33] = AppleNetworkRelayInnerAddresses.CLASS_C_MARKER;
                System.arraycopy(classCPacket, 24, classCSwapped, 8, 16);
                System.arraycopy(classCPacket, 8, classCSwapped, 24, 16);
                boolean parsedChecksum = tcpChecksumMatches(clear);
                boolean swappedChecksum = tcpChecksumMatches(swapped);
                boolean classCChecksum = tcpChecksumMatches(classCPacket);
                boolean classCSwappedChecksum = tcpChecksumMatches(classCSwapped);
                long espSequence = packet.length >= 48
                        ? ((packet[44] & 0xffL) << 24)
                        | ((packet[45] & 0xffL) << 16)
                        | ((packet[46] & 0xffL) << 8)
                        | (packet[47] & 0xffL)
                        : 0L;
                int espClass = (int) (espSequence >>> 30);
                lastInboundEspTrafficClass = espClass;
                if (NormalLinkPipeSession.diagnosticLogger != null) {
                    NormalLinkPipeSession.diagnosticLogger.accept(
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
                    System.arraycopy(classCPacket, 8, clear, 8, 32);
                } else if (!parsedChecksum && classCSwappedChecksum) {
                    System.arraycopy(classCSwapped, 8, clear, 8, 32);
                }
            } finally {
                wipe(swapped);
                wipe(classCPacket);
                wipe(classCSwapped);
            }
        } else if (!isLinkLocalOrMulticast(clear)) {
            byte[] expectedRemote = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D ? remoteClassD : remoteClassC;
            byte[] expectedLocal = dataClass == OrdinaryIkeAuth.DataClass.CLASS_D ? localClassD : localClassC;
            System.arraycopy(expectedRemote, 0, clear, 8, 16);
            System.arraycopy(expectedLocal, 0, clear, 24, 16);
        }
        java.util.function.Consumer<String> logger = NormalLinkPipeSession.diagnosticLogger;
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
            return new NormalLinkPipeSession.DeliveredIp(
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
    boolean isDiscardablePreSaStaleEsp(
            NetworkRelayPacketCodec.DecodedFrame frame,
            OrdinaryClassDSession classD,
            OrdinaryClassDSession classC) {
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

    /**
     * Validates or adopts the authoritative Class-D quartet. Returns
     * {@code true} when the caller must rebuild {@code relayDecoder}.
     */
    boolean validateAuthoritativeAddresses(
            OrdinaryClassDSession classD,
            int relayDecoderBufferedLength) {
        if (addressesValidated) {
            return false;
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
                addressesValidated = true;
                return false;
            }
            if (relayDecoderBufferedLength != 0) {
                throw new IllegalStateException(
                        "Cannot replace provisional addresses with "
                                + "a partial NetworkRelay frame buffered");
            }
            boolean projectionChanged =
                    !Arrays.equals(localClassD, learnedLocalD)
                            || !Arrays.equals(remoteClassD, learnedRemoteD);
            if (projectionChanged && NormalLinkPipeSession.diagnosticLogger != null) {
                NormalLinkPipeSession.diagnosticLogger.accept(
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
            addressesValidated = true;
            return true;
        } finally {
            learned.destroy();
            wipe(learnedLocalD);
            wipe(learnedRemoteD);
            wipe(learnedLocalC);
            wipe(learnedRemoteC);
        }
    }

    void destroy() {
        wipe(localClassD);
        wipe(remoteClassD);
        wipe(localClassC);
        wipe(remoteClassC);
        wipe(storedLocalClassD);
        wipe(storedRemoteClassD);
        wipe(storedLocalClassC);
        wipe(storedRemoteClassC);
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
            int relayFrameCount,
            OrdinaryClassDSession classD,
            OrdinaryClassDSession classC,
            L2capErtmSession transport,
            NormalLinkPipeSession.Phase phase,
            boolean ordinaryContinuationStarted) {
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
                        + (phase == NormalLinkPipeSession.Phase.CLASS_D)
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

    void requireOutboundAddresses(
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

    private static int be16(
            byte[] value,
            int offset) {
        return ((value[offset] & 0xff) << 8)
                | (value[offset + 1] & 0xff);
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }
}
