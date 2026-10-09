package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class IdsDataChannelJoinStateTest {
    private static final String DEFAULT_NAME =
            "UTunDelivery-Default-Default-C";
    private static final String DEFAULT_SERVICE =
            "idstest/localdelivery/"
                    + DEFAULT_NAME;

    @Test
    public void setupFirstStartsConnectorThenJoinsItsCompletion() {
        List<Object> released =
                new ArrayList<>();
        Object connection =
                new Object();
        try (IdsDataChannelJoinState<Object> state =
                     new IdsDataChannelJoinState<>(
                             released::add)) {
            IdsDataChannelJoinState.Result<Object> first =
                    state.receiveSetup(
                            setup(
                                    DEFAULT_NAME));

            assertEquals(
                    IdsDataChannelJoinState.Action
                            .START_OUTGOING_CONNECTOR,
                    first.action);
            assertEquals(
                    DEFAULT_SERVICE,
                    first.connectorService);
            assertNull(
                    first.connection);
            assertEquals(
                    1,
                    state.pendingSetupCount());

            IdsDataChannelJoinState.Result<Object> joined =
                    state.receiveServiceConnection(
                            DEFAULT_SERVICE,
                            connection);

            assertEquals(
                    IdsDataChannelJoinState.Action.JOINED,
                    joined.action);
            assertSame(
                    connection,
                    joined.connection);
            assertEquals(
                    0,
                    state.pendingSetupCount());
            assertEquals(
                    0,
                    state.cachedConnectionCount());
        }
        assertEquals(
                List.of(),
                released);
    }

    @Test
    public void connectionFirstIsCachedThenConsumedBySetup() {
        List<Object> released =
                new ArrayList<>();
        Object connection =
                new Object();
        try (IdsDataChannelJoinState<Object> state =
                     new IdsDataChannelJoinState<>(
                             released::add)) {
            IdsDataChannelJoinState.Result<Object> first =
                    state.receiveServiceConnection(
                            DEFAULT_SERVICE,
                            connection);

            assertEquals(
                    IdsDataChannelJoinState.Action
                            .CACHED_FOR_LATER_SETUP,
                    first.action);
            assertNull(
                    first.connection);
            assertEquals(
                    1,
                    state.cachedConnectionCount());

            IdsDataChannelJoinState.Result<Object> joined =
                    state.receiveSetup(
                            setup(
                                    DEFAULT_NAME));

            assertEquals(
                    IdsDataChannelJoinState.Action.JOINED,
                    joined.action);
            assertSame(
                    connection,
                    joined.connection);
            assertEquals(
                    0,
                    state.cachedConnectionCount());
        }
        assertEquals(
                List.of(),
                released);
    }

    @Test
    public void fullCompositeKeyKeepsRuntimeConnectionsSeparate() {
        String urgentName =
                "UTunDelivery-Default-Urgent-C";
        String urgentService =
                "idstest/localdelivery/"
                        + urgentName;
        Object defaultConnection =
                new Object();
        Object urgentConnection =
                new Object();
        try (IdsDataChannelJoinState<Object> state =
                     new IdsDataChannelJoinState<>(
                             ignored -> {
                             })) {
            state.receiveServiceConnection(
                    DEFAULT_SERVICE,
                    defaultConnection);

            IdsDataChannelJoinState.Result<Object> urgentSetup =
                    state.receiveSetup(
                            setup(
                                    urgentName));
            assertEquals(
                    IdsDataChannelJoinState.Action
                            .START_OUTGOING_CONNECTOR,
                    urgentSetup.action);
            assertEquals(
                    1,
                    state.cachedConnectionCount());
            assertEquals(
                    1,
                    state.pendingSetupCount());

            IdsDataChannelJoinState.Result<Object> urgentJoin =
                    state.receiveServiceConnection(
                            urgentService,
                            urgentConnection);
            IdsDataChannelJoinState.Result<Object> defaultJoin =
                    state.receiveSetup(
                            setup(
                                    DEFAULT_NAME));

            assertSame(
                    urgentConnection,
                    urgentJoin.connection);
            assertSame(
                    defaultConnection,
                    defaultJoin.connection);
        }
    }

    @Test
    public void aNewEarlyConnectionReplacesAndReleasesTheOldCacheEntry() {
        List<Object> released =
                new ArrayList<>();
        Object oldConnection =
                new Object();
        Object newConnection =
                new Object();
        IdsDataChannelJoinState<Object> state =
                new IdsDataChannelJoinState<>(
                        released::add);

        state.receiveServiceConnection(
                DEFAULT_SERVICE,
                oldConnection);
        state.receiveServiceConnection(
                DEFAULT_SERVICE,
                newConnection);

        assertEquals(
                List.of(
                        oldConnection),
                released);
        state.close();
        assertEquals(
                List.of(
                        oldConnection,
                        newConnection),
                released);
        assertThrows(
                IllegalStateException.class,
                state::cachedConnectionCount);
    }

    @Test
    public void duplicateSetupDoesNotStartASecondConnector() {
        try (IdsDataChannelJoinState<Object> state =
                     new IdsDataChannelJoinState<>(
                             ignored -> {
                             })) {
            assertEquals(
                    IdsDataChannelJoinState.Action
                            .START_OUTGOING_CONNECTOR,
                    state.receiveSetup(
                                    setup(
                                            DEFAULT_NAME))
                            .action);
            assertEquals(
                    IdsDataChannelJoinState.Action
                            .WAITING_FOR_SERVICE_CONNECTION,
                    state.receiveSetup(
                                    setup(
                                            DEFAULT_NAME))
                            .action);
            assertEquals(
                    1,
                    state.pendingSetupCount());
        }
    }

    @Test
    public void encryptedSetupUsesTheSameRecoveredCompositeKey() {
        try (IdsDataChannelJoinState<Object> state =
                     new IdsDataChannelJoinState<>(
                             ignored -> {
                             })) {
            IdsDataChannelJoinState.Result<Object> result =
                    state.receiveSetup(
                            encryptedSetup(
                                    DEFAULT_NAME));

            assertEquals(
                    IdsDataChannelJoinState.Action
                            .START_OUTGOING_CONNECTOR,
                    result.action);
            assertEquals(
                    DEFAULT_SERVICE,
                    result.connectorService);
        }
    }

    @Test
    public void malformedOrTopicOnlyServiceCannotEnterTheCache() {
        try (IdsDataChannelJoinState<Object> state =
                     new IdsDataChannelJoinState<>(
                             ignored -> {
                             })) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> state.receiveServiceConnection(
                            NanoRegistryPropertyCodec
                                    .CLASS_D_SERVICE,
                            new Object()));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> state.receiveServiceConnection(
                            "idstest/localdelivery/"
                                    + DEFAULT_NAME
                                    + "/ambiguous",
                            new Object()));
            assertEquals(
                    0,
                    state.cachedConnectionCount());
        }
    }

    private static IdsControlChannelCodec.SetupChannelMessage
            setup(
                    String name) {
        return new IdsControlChannelCodec.SetupChannelMessage(
                IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                IdsControlChannelCodec.PROTOCOL_TCP,
                49152,
                IdsControlChannelCodec.DATA_PORT,
                "00112233-4455-6677-8899-aabbccddeeff",
                null,
                IdsServiceConnectorName.LOCAL_ACCOUNT,
                IdsServiceConnectorName.LOCAL_DELIVERY_SERVICE,
                name,
                null);
    }

    private static IdsControlChannelCodec
            .SetupEncryptedChannelMessage encryptedSetup(
                    String name) {
        return new IdsControlChannelCodec
                .SetupEncryptedChannelMessage(
                        IdsControlChannelCodec.PROTOCOL_TCP,
                        49152,
                        IdsControlChannelCodec.DATA_PORT,
                        "00112233-4455-6677-8899-aabbccddeeff",
                        null,
                        IdsServiceConnectorName.LOCAL_ACCOUNT,
                        IdsServiceConnectorName
                                .LOCAL_DELIVERY_SERVICE,
                        name,
                        1,
                        2,
                        new byte[60]);
    }
}
