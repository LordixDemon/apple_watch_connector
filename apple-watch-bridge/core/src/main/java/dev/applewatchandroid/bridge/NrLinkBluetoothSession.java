package dev.applewatchandroid.bridge;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Per-link, non-durable state for the normal NRLinkBluetooth prelude.
 *
 * <p>The UUID and elected ordinary-IKE role are deliberately scoped to one
 * normal pipe. A reconnect creates another instance and runs role election
 * again. The Android companion advertises state 13 with flags zero, selecting
 * the recovered ordinary IPsec path instead of CompanionAPL/TLS.</p>
 */
final class NrLinkBluetoothSession implements AutoCloseable {
    enum Phase {
        PRELUDE_READY,
        PRELUDE_NEGOTIATED,
        DESTROYED
    }

    private final byte[] localUuid;
    private final byte[] localPrelude;

    private Phase phase;
    private byte[] remoteUuid;
    private NrLinkBluetoothPrelude.LocalRole localRole;
    private NrLinkBluetoothPrelude.PairingState usedState;
    private int remoteFlags;
    private String jointUuidHash;

    private final NrLinkBluetoothPrelude.PairingState localState;

    private NrLinkBluetoothSession(
            byte[] localUuid,
            NrLinkBluetoothPrelude.PairingState localState) {
        if (localUuid == null
                || localUuid.length
                != NrLinkBluetoothPrelude.UUID_LENGTH) {
            throw new IllegalArgumentException(
                    "Normal-link UUID must contain exactly 16 bytes");
        }
        this.localUuid = localUuid.clone();
        this.localState = localState != null
                ? localState
                : NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION;
        this.localPrelude =
                NrLinkBluetoothPrelude.encode(
                        this.localState,
                        this.localUuid,
                        0);
        phase = Phase.PRELUDE_READY;
    }

    static NrLinkBluetoothSession fresh(
            SecureRandom random) {
        return freshWithPreferredRole(
                random,
                null,
                NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION);
    }

    /**
     * Mirrors watchOS 26.2 {@code NRIKEv2RoleInitiator}: the first eight
     * UUID bytes are zero and the remaining eight bytes stay random.
     *
     * <p>This is a preference, not an asserted result. The peer may use the
     * same prefix, so the final role is still elected from both complete
     * UUIDs in {@link #acceptRemotePrelude(byte[])}.</p>
     */
    static NrLinkBluetoothSession freshInitiatorPreferred(
            SecureRandom random) {
        return freshWithPreferredRole(
                random,
                NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION);
    }

    static NrLinkBluetoothSession pairedInitiatorPreferred(
            SecureRandom random) {
        return freshWithPreferredRole(
                random,
                NrLinkBluetoothPrelude.LocalRole.INITIATOR,
                NrLinkBluetoothPrelude.PairingState.HAS_COMPLETED_PAIRING);
    }

    static NrLinkBluetoothSession pairedWithPreferredRole(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole preferredRole) {
        return pairedWithPreferredRole(
                random,
                preferredRole,
                NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION);
    }

    static NrLinkBluetoothSession pairedWithPreferredRole(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole preferredRole,
            NrLinkBluetoothPrelude.PairingState pairingState) {
        return freshWithPreferredRole(
                random,
                preferredRole,
                pairingState != null
                        ? pairingState
                        : NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION);
    }

    static NrLinkBluetoothSession freshResponderPreferred(
            SecureRandom random) {
        return freshWithPreferredRole(
                random,
                NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION);
    }

    private static NrLinkBluetoothSession freshWithPreferredRole(
            SecureRandom random,
            NrLinkBluetoothPrelude.LocalRole preferredRole,
            NrLinkBluetoothPrelude.PairingState pairingState) {
        if (random == null) {
            throw new IllegalArgumentException(
                    "SecureRandom is required");
        }
        byte[] uuid =
                new byte[NrLinkBluetoothPrelude.UUID_LENGTH];
        if (preferredRole == null) {
            random.nextBytes(uuid);
        } else {
            // A reconnect used to fill only the first 8 bytes and leave the
            // tail random, so the Watch could win the other role. The inner
            // address quartet is projected through that role; the flip made
            // the phone take the Watch's address (live 20:04 local 9315,
            // reconnect local c932) and the old TCP/control socket was left
            // behind. The whole UUID has to lose or win.
            Arrays.fill(
                    uuid,
                    preferredRole
                            == NrLinkBluetoothPrelude.LocalRole.INITIATOR
                            ? (byte) 0x00
                            : (byte) 0xff);
        }
        try {
            return new NrLinkBluetoothSession(uuid, pairingState);
        } finally {
            Arrays.fill(uuid, (byte) 0);
        }
    }

    static NrLinkBluetoothSession withLocalUuidForTest(
            byte[] localUuid) {
        return new NrLinkBluetoothSession(localUuid, NrLinkBluetoothPrelude.PairingState.MODERN_PAIRING_KEY_CONFIRMATION);
    }

    byte[] outboundPrelude() {
        requirePhase(Phase.PRELUDE_READY);
        return localPrelude.clone();
    }

    Negotiated acceptRemotePrelude(
            byte[] encoded) {
        requirePhase(Phase.PRELUDE_READY);
        NrLinkBluetoothPrelude.Parsed remote =
                NrLinkBluetoothPrelude.parse(encoded);
        if (remote.version
                != NrLinkBluetoothPrelude.VERSION) {
            throw new IllegalArgumentException(
                    "Normal-link prelude version is unsupported");
        }
        if (remote.state
                != NrLinkBluetoothPrelude.PairingState
                .MODERN_PAIRING_KEY_CONFIRMATION
                && remote.state
                != NrLinkBluetoothPrelude.PairingState
                .HAS_COMPLETED_PAIRING) {
            throw new IllegalArgumentException(
                    "Normal-link setup requires remote state 13 or 20, got " + remote.state);
        }
        if (!NrLinkBluetoothPrelude.isBilaterallyCompatible(
                localState,
                remote.state)) {
            throw new IllegalArgumentException(
                    "Normal-link pairing states are not bilateral: local="
                            + localState + " remote=" + remote.state);
        }
        if ((remote.flags
                & ~(NrLinkBluetoothPrelude.FLAG_COMPANION_APL
                | NrLinkBluetoothPrelude.FLAG_USES_TLS)) != 0) {
            throw new IllegalArgumentException(
                    "Normal-link prelude contains unknown flags");
        }
        if (remote.usesTls()) {
            throw new IllegalArgumentException(
                    "CompanionAPL/TLS is outside the Android IPsec path");
        }

        byte[] parsedRemoteUuid = remote.uuid();
        try {
            localRole =
                    NrLinkBluetoothPrelude.electLocalRole(
                            localUuid,
                            parsedRemoteUuid);
            usedState =
                    NrLinkBluetoothPrelude.reconcile(
                            localState,
                            remote.state);
            remoteFlags = remote.flags;
            jointUuidHash =
                    NrLinkBluetoothPrelude.jointUuidHash(
                            localUuid,
                            parsedRemoteUuid);
            remoteUuid = parsedRemoteUuid.clone();
            phase = Phase.PRELUDE_NEGOTIATED;
            return new Negotiated(
                    localRole,
                    usedState,
                    remoteFlags,
                    jointUuidHash);
        } finally {
            Arrays.fill(parsedRemoteUuid, (byte) 0);
        }
    }

    Phase phase() {
        return phase;
    }

    NrLinkBluetoothPrelude.LocalRole localRole() {
        requirePhase(Phase.PRELUDE_NEGOTIATED);
        return localRole;
    }

    @Override
    public void close() {
        if (phase == Phase.DESTROYED) {
            return;
        }
        Arrays.fill(localUuid, (byte) 0);
        Arrays.fill(localPrelude, (byte) 0);
        if (remoteUuid != null) {
            Arrays.fill(remoteUuid, (byte) 0);
            remoteUuid = null;
        }
        localRole = null;
        usedState = null;
        remoteFlags = 0;
        jointUuidHash = null;
        phase = Phase.DESTROYED;
    }

    private void requirePhase(
            Phase required) {
        if (phase != required) {
            throw new IllegalStateException(
                    "Normal-link session is "
                            + phase
                            + ", expected "
                            + required);
        }
    }

    static final class Negotiated {
        final NrLinkBluetoothPrelude.LocalRole localRole;
        final NrLinkBluetoothPrelude.PairingState usedState;
        final int remoteFlags;
        final String jointUuidHash;

        Negotiated(
                NrLinkBluetoothPrelude.LocalRole localRole,
                NrLinkBluetoothPrelude.PairingState usedState,
                int remoteFlags,
                String jointUuidHash) {
            this.localRole = localRole;
            this.usedState = usedState;
            this.remoteFlags = remoteFlags;
            this.jointUuidHash = jointUuidHash;
        }
    }
}
