package dev.applewatchandroid.bridge;

import java.util.UUID;

/**
 * Reconciles simultaneous IDS Setup attempts for one generic connection.
 *
 * <p>Both peers may create a connection and send Setup before receiving the
 * other's message. watchOS 26.2 converges by comparing the canonical GUID
 * strings. If the local GUID sorts after the remote GUID, the remote address
 * pair wins; otherwise the local attempt stays authoritative. Because the
 * canonical UUID strings have fixed width and lower-case hexadecimal, this
 * is also unsigned byte order for the UUID.</p>
 */
final class IdsSetupGuidReconciler {
    enum Action {
        IGNORE_REPLY_TO_CLEANED_CONNECTION,
        CREATE_FROM_REMOTE_AND_REPLY,
        SEND_CLOSE_FOR_REMOTE_ATTEMPT,
        IGNORE_REPEATED_CURRENT_SETUP,
        REPLACE_STALE_CONNECTION_AND_REPLY,
        COMPLETE_LOCAL_INITIATED_SETUP,
        COMPLETE_SIMULTANEOUS_LOCAL_WINS,
        COMPLETE_SIMULTANEOUS_REMOTE_WINS
    }

    private IdsSetupGuidReconciler() {
    }

    static Decision reconcile(
            String localConnectionGuid,
            boolean established,
            IdsControlChannelCodec.SetupChannelMessage incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException(
                    "Incoming IDS Setup is absent");
        }
        return reconcile(
                localConnectionGuid,
                established,
                incoming.remoteConnectionGuid,
                incoming.forLocalGuid,
                incoming.remotePort,
                incoming.localPort);
    }

    static Decision reconcile(
            String localConnectionGuid,
            boolean established,
            IdsControlChannelCodec.SetupEncryptedChannelMessage
                    incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException(
                    "Incoming encrypted IDS Setup is absent");
        }
        return reconcile(
                localConnectionGuid,
                established,
                incoming.remoteConnectionGuid,
                incoming.forLocalGuid,
                incoming.remotePort,
                incoming.localPort);
    }

    private static Decision reconcile(
            String localConnectionGuid,
            boolean established,
            String remoteConnectionGuid,
            String forLocalGuid,
            int incomingLocalPort,
            int incomingRemotePort) {
        requireCanonicalGuid(
                remoteConnectionGuid,
                "remote");
        if (forLocalGuid != null) {
            requireCanonicalGuid(
                    forLocalGuid,
                    "for-local");
        }
        if (localConnectionGuid == null) {
            if (established) {
                throw new IllegalArgumentException(
                        "Absent IDS connection cannot be established");
            }
            return forLocalGuid != null
                    ? Decision.of(
                            Action
                                    .IGNORE_REPLY_TO_CLEANED_CONNECTION,
                            incomingLocalPort,
                            incomingRemotePort)
                    : Decision.of(
                            Action.CREATE_FROM_REMOTE_AND_REPLY,
                            incomingLocalPort,
                            incomingRemotePort);
        }
        requireCanonicalGuid(
                localConnectionGuid,
                "local");

        if (forLocalGuid != null) {
            if (!sameGuid(
                    localConnectionGuid,
                    forLocalGuid)) {
                return Decision.of(
                        Action.SEND_CLOSE_FOR_REMOTE_ATTEMPT,
                        incomingLocalPort,
                        incomingRemotePort);
            }
            return established
                    ? Decision.of(
                            Action
                                    .IGNORE_REPEATED_CURRENT_SETUP,
                            incomingLocalPort,
                            incomingRemotePort)
                    : Decision.of(
                            Action.COMPLETE_LOCAL_INITIATED_SETUP,
                            incomingLocalPort,
                            incomingRemotePort);
        }

        if (established) {
            return Decision.of(
                    Action
                            .REPLACE_STALE_CONNECTION_AND_REPLY,
                    incomingLocalPort,
                    incomingRemotePort);
        }
        if (remoteConnectionGuid == null || remoteConnectionGuid.isEmpty()) {
            return Decision.of(
                    Action.COMPLETE_LOCAL_INITIATED_SETUP,
                    incomingLocalPort,
                    incomingRemotePort);
        }
        return uuidCompare(
                localConnectionGuid,
                remoteConnectionGuid) > 0
                ? Decision.of(
                        Action
                                .COMPLETE_SIMULTANEOUS_REMOTE_WINS,
                        incomingLocalPort,
                        incomingRemotePort)
                : Decision.of(
                        Action
                                .COMPLETE_SIMULTANEOUS_LOCAL_WINS,
                        incomingLocalPort,
                        incomingRemotePort);
    }

    private static void requireCanonicalGuid(
            String guid,
            String label) {
        if (guid == null || guid.isEmpty()) {
            return;
        }
        try {
            UUID.fromString(
                    guid);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "IDS "
                            + label
                            + " connection GUID is not canonical",
                    invalid);
        }
    }

    private static boolean sameGuid(
            String left,
            String right) {
        if (left == null || right == null) {
            return left == right;
        }
        try {
            return UUID.fromString(
                    left).equals(
                    UUID.fromString(
                            right));
        } catch (RuntimeException invalid) {
            return left.equalsIgnoreCase(
                    right);
        }
    }

    private static int uuidCompare(
            String left,
            String right) {
        try {
            return UUID.fromString(
                    left).toString().compareTo(
                    UUID.fromString(
                            right).toString());
        } catch (RuntimeException invalid) {
            return left.compareToIgnoreCase(
                    right);
        }
    }

    static final class Decision {
        final Action action;
        final boolean sendSetupReply;
        final boolean sendClose;
        final boolean replaceExisting;
        final boolean adoptIncomingAddressPair;
        final boolean completeSetup;
        final int incomingLocalPort;
        final int incomingRemotePort;

        private Decision(
                Action action,
                int incomingLocalPort,
                int incomingRemotePort) {
            this.action = action;
            this.incomingLocalPort =
                    incomingLocalPort;
            this.incomingRemotePort =
                    incomingRemotePort;
            sendSetupReply =
                    action
                            == Action.CREATE_FROM_REMOTE_AND_REPLY
                            || action
                            == Action
                            .REPLACE_STALE_CONNECTION_AND_REPLY;
            sendClose =
                    action
                            == Action.SEND_CLOSE_FOR_REMOTE_ATTEMPT;
            replaceExisting =
                    action
                            == Action
                            .REPLACE_STALE_CONNECTION_AND_REPLY;
            adoptIncomingAddressPair =
                    action
                            == Action.CREATE_FROM_REMOTE_AND_REPLY
                            || action
                            == Action
                            .REPLACE_STALE_CONNECTION_AND_REPLY
                            || action
                            == Action
                            .COMPLETE_SIMULTANEOUS_REMOTE_WINS;
            completeSetup =
                    action
                            == Action.CREATE_FROM_REMOTE_AND_REPLY
                            || action
                            == Action
                            .REPLACE_STALE_CONNECTION_AND_REPLY
                            || action
                            == Action.COMPLETE_LOCAL_INITIATED_SETUP
                            || action
                            == Action
                            .COMPLETE_SIMULTANEOUS_LOCAL_WINS
                            || action
                            == Action
                            .COMPLETE_SIMULTANEOUS_REMOTE_WINS;
        }

        private static Decision of(
                Action action,
                int incomingLocalPort,
                int incomingRemotePort) {
            return new Decision(
                    action,
                    incomingLocalPort,
                    incomingRemotePort);
        }
    }
}
