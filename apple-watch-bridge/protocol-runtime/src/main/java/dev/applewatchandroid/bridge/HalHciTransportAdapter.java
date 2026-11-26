package dev.applewatchandroid.bridge;

import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.strategy.PersistencePort;
import dev.applewatchandroid.bridge.strategy.SessionConfig;
import dev.applewatchandroid.bridge.strategy.StrategyRegistry;
import dev.applewatchandroid.bridge.strategy.StrategySessionContext;
import dev.applewatchandroid.bridge.strategy.UiPromptPort;
import dev.applewatchandroid.bridge.strategy.s1.SimultaneousLegacyStrategy;
import dev.applewatchandroid.bridge.strategy.s2.SequentialLegacyStrategy;
import dev.applewatchandroid.bridge.strategy.s3.DirectIpsecLegacyStrategy;
import dev.applewatchandroid.bridge.strategy.s4.NetworkRelayStrategy;
import dev.applewatchandroid.bridge.transport.HciTransport;
import dev.applewatchandroid.bridge.transport.L2capChannelMux;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Package-private adapter bridging {@link HalTransportSession} to the modular
 * {@code transport} and {@code strategy} packages.
 *
 * <p>Hooks {@link #openAttClientScope()} directly into
 * {@link BluetoothFixedChannelRouter#setAttClientActive(boolean)} so local GATT ATT client
 * transactions receive Server-to-Client PDUs on a live {@link HalTransportSession} without
 * disabling concurrent Watch-initiated GATT server servicing.</p>
 */
final class HalHciTransportAdapter implements HciTransport {
    private final HalTransportSession session;

    HalHciTransportAdapter(HalTransportSession session) {
        this.session = session;
    }

    @Override
    public int connectionHandle() {
        return session.connectionHandle;
    }

    @Override
    public int peerAddressType() {
        return session.peerConnectionAddressType;
    }

    @Override
    public byte[] peerAddress() {
        return session.peerConnectionAddress == null ? null : session.peerConnectionAddress.clone();
    }

    @Override
    public BluetoothLocalIdentity localIdentity() {
        return session.localIdentity;
    }

    @Override
    public void checkStop() throws Exception {
        session.checkStop();
    }

    @Override
    public void sendL2cap(int cid, byte[] payload, String label) throws Exception {
        session.sendL2cap(cid, payload, label);
    }

    @Override
    public Packet nextPacket(long deadlineMs) throws Exception {
        byte[] deferredSmp = session.deferredSmpPdus.poll();
        if (deferredSmp != null) {
            try {
                List<byte[]> fragments = HciCodec.buildLeAclL2capFragments(
                        session.connectionHandle,
                        BluetoothSmpCodec.FIXED_CID,
                        deferredSmp,
                        251);
                return new Packet(PacketKind.ACL, fragments.get(0));
            } finally {
                Arrays.fill(deferredSmp, (byte) 0);
            }
        }
        HalTransportSession.Packet p = session.nextPacket(deadlineMs);
        if (p == null) {
            throw new TimeoutException("packet deadline elapsed");
        }
        return new Packet(
                p.kind == HalTransportSession.PacketKind.EVENT ? PacketKind.EVENT : PacketKind.ACL,
                p.bytes);
    }

    @Override
    public void startLeEncryption(byte[] rand, int ediv, byte[] longTermKey) throws Exception {
        session.startLeEncryption(rand, ediv, longTermKey);
    }

    @Override
    public AutoCloseable openAttClientScope() {
        boolean previous = session.normalLink.fixedChannelRouter.isAttClientActive();
        session.normalLink.fixedChannelRouter.setAttClientActive(true);
        return () -> session.normalLink.fixedChannelRouter.setAttClientActive(previous);
    }

    @Override
    public byte[] handlePeerAttRequest(byte[] request) throws Exception {
        return session.normalLink.normalAttServer.accept(session.connectionHandle, request);
    }

    @Override
    public void log(String message) {
        ProtocolHost.log(message);
    }

    static PersistencePort persistencePort(HalTransportSession session) {
        return new PersistencePort() {
            @Override
            public void persistBondSecretOrFail(byte[] bondSecretRecord) throws Exception {
                session.persistBondSecretOrFail(bondSecretRecord);
            }

            @Override
            public void persistPairingSessionOrFail(PairingSessionRecord record) throws Exception {
                session.persistPairingSessionOrFail(record);
                byte[] serialized = record.serialize();
                try {
                    PairingSessionRecord copy = PairingSessionRecord.parse(serialized);
                    if (session.activePairingSessionRecord != null) {
                        session.activePairingSessionRecord.destroy();
                    }
                    session.activePairingSessionRecord = copy;
                } finally {
                    Arrays.fill(serialized, (byte) 0);
                }
            }

            @Override
            public void persistAndReplaceActivePairingSession(PairingSessionRecord replacement) throws Exception {
                session.persistAndReplaceActivePairingSession(replacement);
            }
        };
    }

    static UiPromptPort uiPromptPort(HalTransportSession session) {
        return new UiPromptPort() {
            @Override
            public char[] awaitSixDigitPin(long timeoutMs) throws Exception {
                ProtocolHost.log("PIN_REQUIRED: enter the six-digit code displayed on the Watch.");
                long deadline = BridgeClock.elapsedRealtime() + Math.max(1L, timeoutMs);
                while (BridgeClock.elapsedRealtime() < deadline) {
                    session.checkStop();
                    long remaining = Math.min(250L, Math.max(1L, deadline - BridgeClock.elapsedRealtime()));
                    char[] pin = session.pinInputs.poll(remaining, TimeUnit.MILLISECONDS);
                    if (pin != null) {
                        return pin;
                    }
                }
                throw new HostException("Timed out waiting for six-digit Watch PIN input");
            }

            @Override
            public void log(String message) {
                ProtocolHost.log(message);
            }
        };
    }

    static StrategyRegistry createSessionRegistry() {
        return new StrategyRegistry(List.of(
                new NetworkRelayStrategy(
                        ctx -> {
                            throw new UnsupportedOperationException(
                                    "Strategy 4 fresh pairing executes directly in HalTransportSession");
                        },
                        (ctx, restored) -> {
                            throw new UnsupportedOperationException(
                                    "Strategy 4 reconnect executes directly in PairedWatchReconnectFlow");
                        }),
                new DirectIpsecLegacyStrategy(),
                new SequentialLegacyStrategy(),
                new SimultaneousLegacyStrategy()));
    }

    static StrategySessionContext createContext(
            HalTransportSession session,
            PairingStrategyId strategyId,
            L2capChannelMux mux,
            byte[] legacyOpticalOobKey,
            String productType,
            String watchOsVersion,
            long idsObserveTimeoutMs) {
        SessionConfig config = new SessionConfig(
                ProtocolHost.effectiveStrategyPolicy(),
                session.commitIsPairedAuthorized,
                ProtocolHost.COMPANION_DISCOVERY.get(),
                session.operationalPairingId);
        return new StrategySessionContext(
                strategyId,
                mux.transport(),
                mux,
                persistencePort(session),
                uiPromptPort(session),
                config,
                new SecureRandom(),
                legacyOpticalOobKey,
                productType,
                watchOsVersion,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                ProtocolHost.LEGACY_GATT_TIMEOUT_MS,
                ProtocolHost.SMP_RESPONSE_TIMEOUT_MS,
                ProtocolHost.PIN_INPUT_TIMEOUT_MS,
                ProtocolHost.BT_CL_TIMEOUT_MS,
                idsObserveTimeoutMs);
    }
}
