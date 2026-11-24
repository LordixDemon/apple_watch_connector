package dev.applewatchandroid.bridge;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/** Internal per-connector and per-connection state models for IDS NWSC transport. */
final class IdsServiceConnectorState {
    private IdsServiceConnectorState() {}

    static final class ConnectorContext
            implements AutoCloseable {
        final IdsIpsecServiceRoute.Connector connector;
        final int listenerPort;
        final String classDEndpointIdentifier;
        final String classCEndpointIdentifier;
        final NwServiceConnectorHandshake classDHandshake;
        final NwServiceConnectorHandshake classCHandshake;
        final Map<String, OutgoingAttempt> outgoingByService =
                new LinkedHashMap<>();
        final Map<String, ConnectionState> activeByService =
                new LinkedHashMap<>();
        final List<IncomingMetadata> pendingIncoming =
                new ArrayList<>();
        final Map<String, KeyProbe> keyProbes =
                new LinkedHashMap<>();

        ConnectorContext(
                IdsIpsecServiceRoute.Connector connector,
                int listenerPort,
                String classDEndpointIdentifier,
                String classCEndpointIdentifier,
                NwServiceConnectorHandshake classDHandshake,
                NwServiceConnectorHandshake classCHandshake) {
            this.connector =
                    connector;
            this.listenerPort =
                    listenerPort;
            this.classDEndpointIdentifier =
                    classDEndpointIdentifier;
            this.classCEndpointIdentifier =
                    classCEndpointIdentifier;
            this.classDHandshake =
                    classDHandshake;
            this.classCHandshake =
                    classCHandshake;
        }

        NwServiceConnectorHandshake handshake(
                OrdinaryIkeAuth.DataClass dataClass) {
            return dataClass == OrdinaryIkeAuth.DataClass.CLASS_D
                    ? classDHandshake
                    : classCHandshake;
        }

        NwServiceConnectorHandshake handshake(
                String endpointIdentifier) {
            if (classCEndpointIdentifier != null
                    && classCEndpointIdentifier.equals(endpointIdentifier)) {
                return classCHandshake;
            }
            return classDHandshake;
        }

        String endpointIdentifier(
                OrdinaryIkeAuth.DataClass dataClass) {
            return switch (dataClass) {
                case CLASS_D -> classDEndpointIdentifier;
                case CLASS_C -> classCEndpointIdentifier;
            };
        }

        List<IncomingMetadata> removePendingIncoming(
                String endpointIdentifier) {
            List<IncomingMetadata> matching =
                    new ArrayList<>();
            for (int index = 0;
                    index < pendingIncoming.size();) {
                IncomingMetadata metadata =
                        pendingIncoming.get(
                                index);
                if (metadata.endpointIdentifier.equals(
                        endpointIdentifier)) {
                    matching.add(
                            metadata);
                    pendingIncoming.remove(
                            index);
                } else {
                    index++;
                }
            }
            return matching;
        }

        @Override
        public void close() {
            for (OutgoingAttempt attempt :
                    outgoingByService.values()) {
                attempt.close();
            }
            outgoingByService.clear();
            activeByService.clear();
            for (IncomingMetadata metadata : pendingIncoming) {
                metadata.close();
            }
            pendingIncoming.clear();
            for (KeyProbe keyProbe :
                    keyProbes.values()) {
                keyProbe.close();
            }
            keyProbes.clear();
            if (classDHandshake != null) {
                classDHandshake.close();
            }
            if (classCHandshake != null) {
                classCHandshake.close();
            }
        }
    }

    static final class ConnectionState
            implements AutoCloseable {
        final long connectionId;
        final ConnectorContext connector;
        final OrdinaryIkeAuth.DataClass dataClass;
        final String endpointIdentifier;
        IdsServiceConnectorCoordinator.PreludeBuffer prelude =
                new IdsServiceConnectorCoordinator.PreludeBuffer();
        IdsServiceConnectorCoordinator.ConnectionPhase phase;
        int dynamicPort;
        boolean ownsDynamicPort;
        int localPort;
        boolean isAdopted;
        OutgoingAttempt outgoingAttempt;
        KeyProbe keyProbe;
        IncomingMetadata pendingIncoming;
        IdsIpsecServiceRoute route;
        String serviceName;
        long serviceSequence;
        byte[] serviceUuid =
                new byte[0];
        boolean closed;

        ConnectionState(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                IdsServiceConnectorCoordinator.ConnectionPhase phase,
                int dynamicPort) {
            this.connectionId =
                    connectionId;
            this.connector =
                    connector;
            this.dataClass =
                    dataClass;
            endpointIdentifier =
                    connector.endpointIdentifier(
                            dataClass);
            this.phase =
                    phase;
            this.dynamicPort =
                    dynamicPort;
        }

        static ConnectionState incoming(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                int localPort) {
            ConnectionState state = new ConnectionState(
                    connectionId,
                    connector,
                    dataClass,
                    IdsServiceConnectorCoordinator.ConnectionPhase.INCOMING_PRELUDE,
                    0);
            state.localPort = localPort;
            return state;
        }

        static ConnectionState outgoing(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                int dynamicPort,
                boolean ownsDynamicPort,
                OutgoingAttempt attempt) {
            ConnectionState state =
                    new ConnectionState(
                            connectionId,
                            connector,
                            dataClass,
                            IdsServiceConnectorCoordinator.ConnectionPhase.OUTGOING_NORMAL_PRELUDE,
                            dynamicPort);
            state.ownsDynamicPort =
                    ownsDynamicPort;
            state.outgoingAttempt =
                    attempt;
            return state;
        }

        static ConnectionState keyProbe(
                long connectionId,
                ConnectorContext connector,
                OrdinaryIkeAuth.DataClass dataClass,
                int dynamicPort,
                KeyProbe probe) {
            ConnectionState state =
                    new ConnectionState(
                            connectionId,
                            connector,
                            dataClass,
                            IdsServiceConnectorCoordinator.ConnectionPhase
                                    .OUTGOING_KEY_PROBE_PRELUDE,
                            dynamicPort);
            state.ownsDynamicPort =
                    true;
            state.keyProbe =
                    probe;
            return state;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            prelude.close();
            IdsServiceConnectorCoordinator.wipe(
                    serviceUuid);
            serviceUuid =
                    new byte[0];
        }
    }

    static final class OutgoingAttempt
            implements AutoCloseable {
        final ConnectorContext connector;
        final IdsIpsecServiceRoute route;
        final String endpointIdentifier;
        final byte[] requestUuid;
        long sequence;
        byte[] frame;
        long connectionId;
        boolean waitingForRetry;
        boolean closed;

        OutgoingAttempt(
                ConnectorContext connector,
                IdsIpsecServiceRoute route,
                String endpointIdentifier,
                long sequence,
                byte[] requestUuid,
                byte[] frame) {
            this.connector =
                    connector;
            this.route = route;
            this.endpointIdentifier =
                    endpointIdentifier;
            this.sequence =
                    sequence;
            this.requestUuid =
                    requestUuid.clone();
            this.frame =
                    frame.clone();
        }

        void replaceFrame(long newSequence, byte[] newFrame) {
            IdsServiceConnectorCoordinator.wipe(this.frame);
            this.sequence = newSequence;
            this.frame = newFrame.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            IdsServiceConnectorCoordinator.wipe(
                    requestUuid);
            IdsServiceConnectorCoordinator.wipe(
                    frame);
        }
    }

    static final class KeyProbe
            implements AutoCloseable {
        final ConnectorContext connector;
        final String endpointIdentifier;
        final long sequence;
        final byte[] frame;
        long connectionId;
        boolean closed;

        KeyProbe(
                ConnectorContext connector,
                String endpointIdentifier,
                long sequence,
                byte[] frame) {
            this.connector =
                    connector;
            this.endpointIdentifier =
                    endpointIdentifier;
            this.sequence =
                    sequence;
            this.frame =
                    frame.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            IdsServiceConnectorCoordinator.wipe(
                    frame);
        }
    }

    static final class IncomingMetadata
            implements AutoCloseable {
        final long connectionId;
        final IdsIpsecServiceRoute route;
        final String endpointIdentifier;
        final String serviceName;
        final long sequence;
        final byte[] requestUuid;
        boolean abandoned;
        boolean closed;

        IncomingMetadata(
                long connectionId,
                IdsIpsecServiceRoute route,
                String endpointIdentifier,
                String serviceName,
                long sequence,
                byte[] requestUuid) {
            this.connectionId =
                    connectionId;
            this.route = route;
            this.endpointIdentifier =
                    endpointIdentifier;
            this.serviceName =
                    serviceName;
            this.sequence =
                    sequence;
            this.requestUuid =
                    requestUuid.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            IdsServiceConnectorCoordinator.wipe(
                    requestUuid);
        }
    }

    static final class ActiveTransport
            implements AutoCloseable {
        final long connectionId;
        final OrdinaryIkeAuth.DataClass dataClass;
        byte[] packet;
        boolean closed;

        ActiveTransport(
                long connectionId,
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] packet) {
            this.connectionId =
                    connectionId;
            this.dataClass =
                    dataClass;
            this.packet =
                    packet.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            IdsServiceConnectorCoordinator.wipe(
                    packet);
            packet =
                    new byte[0];
        }
    }

    static byte[] deriveEd25519PrivateKey(
            byte[] address,
            String purpose) {
        if (address == null
                || address.length == 0) {
            byte[] randomKey =
                    new byte[32];
            new SecureRandom().nextBytes(
                    randomKey);
            return randomKey;
        }
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            digest.update(
                    purpose.getBytes(StandardCharsets.UTF_8));
            digest.update(
                    address);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static byte[] deriveEd25519PublicKey(
            byte[] privateKey) {
        if (privateKey == null
                || privateKey.length != 32) {
            return null;
        }
        return new Ed25519PrivateKeyParameters(
                privateKey,
                0)
                .generatePublicKey()
                .getEncoded();
    }
}
