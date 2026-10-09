package dev.applewatchandroid.bridge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Keeps prepared sends alive until both IDS and ERTM backpressure are drained. */
final class PostCommitDeliveryQueue implements AutoCloseable {
    private static final int MAX_PENDING = 64;
    private final ArrayDeque<AppleWatchPostCommitIdsAdapter.PreparedSend> pending =
            new ArrayDeque<>();

    void add(AppleWatchPostCommitIdsAdapter.PreparedSend send) {
        if (send == null || pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("Post-commit pending-send capacity exceeded");
        }
        pending.addLast(send);
    }

    List<AppleWatchPostCommitCoordinator.Action> completeDrained(
            boolean applicationFramesPending, boolean ipv6FramesDeferred) {
        if (applicationFramesPending || ipv6FramesDeferred) {
            return List.of();
        }
        List<AppleWatchPostCommitCoordinator.Action> actions = new ArrayList<>();
        while (!pending.isEmpty()) {
            // This reports local transport submission only. Watch-local apply
            // evidence must arrive separately through the incoming IDS adapter.
            actions.addAll(pending.removeFirst().complete(true));
        }
        return actions;
    }

    int size() {
        return pending.size();
    }

    List<AppleWatchPostCommitCoordinator.Action> completeReceipt(
            IdsModernSessionCoordinator.SessionEvent event) {
        List<AppleWatchPostCommitCoordinator.Action> actions = new ArrayList<>();
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            var send = iterator.next();
            boolean ack = event.type == IdsModernSessionCoordinator.EventType.ACK_RECEIVED
                    && event.sequence == send.sequence();
            boolean response = event.type == IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED
                    && event.response && send.topic().equals(event.topic)
                    && send.messageUuid().equals(event.peerResponseIdentifier);
            if (ack || response) {
                iterator.remove();
                actions.addAll(send.complete(true));
            }
        }
        return actions;
    }

    @Override
    public void close() {
        while (!pending.isEmpty()) {
            pending.removeFirst().close();
        }
    }
}
