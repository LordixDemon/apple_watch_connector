package dev.applewatchandroid.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Joins an IDS Setup control message to its Network.framework
 * service-connector data connection.
 *
 * <p>The two events may arrive in either order. watchOS keeps an early
 * service connection in {@code serviceConnectionCache}, keyed by the exact
 * {@code account/service/name} string. A later Setup consumes and removes
 * that entry. When Setup arrives first, IDS starts an outgoing service
 * connector and associates its completion with the already-created generic
 * connection. This class preserves that two-sided race without confusing
 * the connector key with a NanoRegistry topic.</p>
 *
 * <p>A connection returned by a {@link Result} with action {@link
 * Action#JOINED} transfers to the caller. Connections still cached when this
 * state is closed, and cached connections displaced by a newer connection
 * for the same key, are passed to the supplied releaser.</p>
 */
final class IdsDataChannelJoinState<T>
        implements AutoCloseable {
    enum Action {
        START_OUTGOING_CONNECTOR,
        WAITING_FOR_SERVICE_CONNECTION,
        CACHED_FOR_LATER_SETUP,
        JOINED
    }

    private final Consumer<T> releaser;
    private final Set<IdsServiceConnectorName>
            pendingSetups =
            new LinkedHashSet<>();
    private final Map<IdsServiceConnectorName, T>
            cachedConnections =
            new LinkedHashMap<>();

    private boolean closed;

    IdsDataChannelJoinState(
            Consumer<T> releaser) {
        if (releaser == null) {
            throw new IllegalArgumentException(
                    "IDS connection releaser is absent");
        }
        this.releaser = releaser;
    }

    synchronized Result<T> receiveSetup(
            IdsControlChannelCodec.SetupChannelMessage setup) {
        requireUsable();
        if (setup == null) {
            throw new IllegalArgumentException(
                    "IDS Setup message is absent");
        }
        return receiveSetup(
                IdsServiceConnectorName.of(
                        setup.account,
                        setup.service,
                        setup.name));
    }

    synchronized Result<T> receiveSetup(
            IdsControlChannelCodec.SetupEncryptedChannelMessage setup) {
        requireUsable();
        if (setup == null) {
            throw new IllegalArgumentException(
                    "IDS encrypted Setup message is absent");
        }
        return receiveSetup(
                IdsServiceConnectorName.of(
                        setup.account,
                        setup.service,
                        setup.name));
    }

    synchronized Result<T> receiveServiceConnection(
            String connectorService,
            T connection) {
        requireUsable();
        if (connection == null) {
            throw new IllegalArgumentException(
                    "IDS service connection is absent");
        }
        IdsServiceConnectorName key =
                IdsServiceConnectorName.parseCanonical(
                        connectorService);
        if (pendingSetups.remove(
                key)) {
            return new Result<>(
                    Action.JOINED,
                    key,
                    connection);
        }

        T displaced =
                cachedConnections.put(
                        key,
                        connection);
        if (displaced != null
                && displaced != connection) {
            releaser.accept(
                    displaced);
        }
        return new Result<>(
                Action.CACHED_FOR_LATER_SETUP,
                key,
                null);
    }

    synchronized int pendingSetupCount() {
        requireUsable();
        return pendingSetups.size();
    }

    synchronized int cachedConnectionCount() {
        requireUsable();
        return cachedConnections.size();
    }

    synchronized Result<T> receiveSetup(
            IdsServiceConnectorName key) {
        T cached =
                cachedConnections.remove(
                        key);
        if (cached != null) {
            return new Result<>(
                    Action.JOINED,
                    key,
                    cached);
        }
        if (!pendingSetups.add(
                key)) {
            return new Result<>(
                    Action.WAITING_FOR_SERVICE_CONNECTION,
                    key,
                    null);
        }
        return new Result<>(
                Action.START_OUTGOING_CONNECTOR,
                key,
                null);
    }

    private void requireUsable() {
        if (closed) {
            throw new IllegalStateException(
                    "IDS data-channel join state is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        List<T> cached =
                new ArrayList<>(
                        cachedConnections.values());
        cachedConnections.clear();
        pendingSetups.clear();
        for (T connection : cached) {
            releaser.accept(
                    connection);
        }
    }

    static final class Result<T> {
        final Action action;
        final String connectorService;
        final T connection;

        private Result(
                Action action,
                IdsServiceConnectorName connectorService,
                T connection) {
            this.action = action;
            this.connectorService =
                    connectorService.encode();
            this.connection = connection;
        }
    }
}
