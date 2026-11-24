package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Live transport and composite dispatch-output wrappers for
 * {@link AppleWatchPostCommitIdsAdapter}.
 */
final class PostCommitIdsLiveTransport {
    private PostCommitIdsLiveTransport() {
    }

    static AppleWatchPostCommitIdsAdapter.Transport liveTransport(
            NormalLinkIdsSessionBridge bridge) {
        return new LiveTransport(bridge);
    }

    static AppleWatchPostCommitIdsAdapter.DispatchOutput initialSyncOutput(
            AppleWatchPostCommitIdsAdapter.DispatchOutput buddy,
            AppleWatchPostCommitIdsAdapter.DispatchOutput start,
            AppleWatchPostCommitIdsAdapter.DispatchOutput prepare) {
        return new InitialSyncDispatchOutput(buddy, start, prepare);
    }

    static AppleWatchPostCommitIdsAdapter.DispatchOutput completionOutput(
            AppleWatchPostCommitIdsAdapter.DispatchOutput primary,
            AppleWatchPostCommitIdsAdapter.DispatchOutput initialSync) {
        return new CompletionDispatchOutput(primary, initialSync);
    }

    /** Open Buddy before PSY changes, retaining PB prepare reply correlation. */
    private static final class InitialSyncDispatchOutput
            implements AppleWatchPostCommitIdsAdapter.DispatchOutput {
        private AppleWatchPostCommitIdsAdapter.DispatchOutput buddy;
        private AppleWatchPostCommitIdsAdapter.DispatchOutput start;
        private AppleWatchPostCommitIdsAdapter.DispatchOutput prepare;

        InitialSyncDispatchOutput(
                AppleWatchPostCommitIdsAdapter.DispatchOutput buddy,
                AppleWatchPostCommitIdsAdapter.DispatchOutput start,
                AppleWatchPostCommitIdsAdapter.DispatchOutput prepare) {
            this.buddy = Objects.requireNonNull(buddy);
            this.start = Objects.requireNonNull(start);
            this.prepare = Objects.requireNonNull(prepare);
        }

        @Override
        public List<byte[]> outboundFrames() {
            requireOpen();
            List<byte[]> frames = new ArrayList<>(buddy.outboundFrames());
            frames.addAll(start.outboundFrames());
            frames.addAll(prepare.outboundFrames());
            return Collections.unmodifiableList(frames);
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.SentMessage sentMessage() {
            requireOpen();
            return prepare.sentMessage();
        }

        private void requireOpen() {
            if (prepare == null) throw new IllegalStateException("Initial sync output is closed");
        }

        @Override
        public void close() {
            AppleWatchPostCommitIdsAdapter.closeQuietly(buddy);
            AppleWatchPostCommitIdsAdapter.closeQuietly(start);
            AppleWatchPostCommitIdsAdapter.closeQuietly(prepare);
            buddy = null;
            start = null;
            prepare = null;
        }
    }

    /** Own both completion domains until the HAL submits every frame. */
    private static final class CompletionDispatchOutput
            implements AppleWatchPostCommitIdsAdapter.DispatchOutput {
        private AppleWatchPostCommitIdsAdapter.DispatchOutput primary;
        private AppleWatchPostCommitIdsAdapter.DispatchOutput initialSync;

        CompletionDispatchOutput(
                AppleWatchPostCommitIdsAdapter.DispatchOutput primary,
                AppleWatchPostCommitIdsAdapter.DispatchOutput initialSync) {
            this.primary = Objects.requireNonNull(primary);
            this.initialSync = Objects.requireNonNull(initialSync);
        }

        @Override
        public List<byte[]> outboundFrames() {
            requireOpen();
            List<byte[]> frames = new ArrayList<>(primary.outboundFrames());
            frames.addAll(initialSync.outboundFrames());
            return Collections.unmodifiableList(frames);
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.SentMessage sentMessage() {
            requireOpen();
            return primary.sentMessage();
        }

        private void requireOpen() {
            if (primary == null) throw new IllegalStateException("Completion output is closed");
        }

        @Override
        public void close() {
            AppleWatchPostCommitIdsAdapter.closeQuietly(primary);
            AppleWatchPostCommitIdsAdapter.closeQuietly(initialSync);
            primary = null;
            initialSync = null;
        }
    }

    private static final class LiveTransport
            implements AppleWatchPostCommitIdsAdapter.Transport {
        private NormalLinkIdsSessionBridge bridge;

        private LiveTransport(
                NormalLinkIdsSessionBridge bridge) {
            if (bridge == null) {
                throw new IllegalArgumentException(
                        "Normal-link IDS bridge is absent");
            }
            this.bridge =
                    bridge;
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.DispatchOutput sendClassD(
                NanoRegistryClassDCodec.ApplicationMessage message) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendClassD(
                            message));
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.DispatchOutput sendPbBridge(
                PbBridgeCodec.ApplicationMessage message) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendPbBridge(
                            message));
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.DispatchOutput sendPbBridgeReplyToFetch(
                PbBridgeCodec.ApplicationMessage message,
                String watchMessageUuid) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendPbBridgeReplyToFetch(
                            message,
                            watchMessageUuid));
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.DispatchOutput sendPairedSync(
                PairedSyncCodec.UserDefaultsMessage message) {
            requireOpen();
            return new LiveDispatchOutput(
                    bridge.sendPairedSync(
                            message));
        }

        @Override
        public PairedSyncCapabilityState.Status
                pairedSyncCapabilityStatus() {
            requireOpen();
            return bridge.idsSnapshot()
                    .pairedSyncCapabilityStatus;
        }

        private void requireOpen() {
            if (bridge == null) {
                throw new IllegalStateException(
                        "Live post-commit IDS transport is closed");
            }
        }

        @Override
        public void close() {
            if (bridge == null) {
                return;
            }
            NormalLinkIdsSessionBridge owned =
                    bridge;
            bridge =
                    null;
            owned.close();
        }
    }

    private static final class LiveDispatchOutput
            implements AppleWatchPostCommitIdsAdapter.DispatchOutput {
        private NormalLinkIdsSessionBridge.Output output;
        private final AppleWatchPostCommitIdsAdapter.SentMessage sent;

        private LiveDispatchOutput(
                NormalLinkIdsSessionBridge.Output output) {
            if (output == null) {
                throw new IllegalArgumentException(
                        "Normal-link IDS output is absent");
            }
            this.output =
                    output;
            AppleWatchPostCommitIdsAdapter.SentMessage found =
                    null;
            for (IdsModernSessionCoordinator.SessionEvent event :
                    output.idsEvents()) {
                if (event.type
                        != IdsModernSessionCoordinator.EventType
                        .PROTOBUF_SENT) {
                    continue;
                }
                if (found != null) {
                    output.close();
                    this.output =
                            null;
                    throw new IllegalStateException(
                            "One IDS action emitted multiple protobufs");
                }
                found =
                        new AppleWatchPostCommitIdsAdapter.SentMessage(
                                event.sequence,
                                event.messageUuid,
                                event.topic,
                                event.flags,
                                event.protobufType,
                                event.response);
            }
            if (found == null) {
                output.close();
                this.output =
                        null;
                throw new IllegalStateException(
                        "IDS action emitted no protobuf event");
            }
            sent =
                    found;
        }

        @Override
        public List<byte[]> outboundFrames() {
            requireOpen();
            return output.ertmFrames();
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.SentMessage sentMessage() {
            requireOpen();
            return sent;
        }

        private void requireOpen() {
            if (output == null) {
                throw new IllegalStateException(
                        "Live IDS dispatch output is closed");
            }
        }

        @Override
        public void close() {
            if (output == null) {
                return;
            }
            output.close();
            output =
                    null;
        }
    }
}
