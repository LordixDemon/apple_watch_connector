package dev.applewatchandroid.bridge.strategy;

import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.transport.HciTransport;
import dev.applewatchandroid.bridge.transport.L2capChannelMux;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-connection execution context injected into {@link PairingStrategy} plugins.
 *
 * <p>Owns optional transient optical OOB key material defensively and zeroizes it on
 * {@link #close()}. Never formats secrets or addresses in {@link #toString()}.</p>
 */
public final class StrategySessionContext implements AutoCloseable {
    public static final long DEFAULT_GATT_TIMEOUT_MS = 10_000L;
    public static final long DEFAULT_SMP_RESPONSE_TIMEOUT_MS = 20_000L;
    public static final long DEFAULT_PIN_INPUT_TIMEOUT_MS = 600_000L;
    public static final long DEFAULT_BT_CL_TIMEOUT_MS = 5_000L;
    public static final long DEFAULT_POST_SMP_WINDOW_MS = 5_000L;

    private final PairingStrategyId requestedStrategy;
    private final HciTransport transport;
    private final L2capChannelMux mux;
    private final PersistencePort persistence;
    private final UiPromptPort ui;
    private final SessionConfig config;
    private final SecureRandom random;
    private final String productType;
    private final String watchOsVersion;
    private final String localIdsDeviceUuid;
    private final String bluetoothCbUuid;
    private final String nrUuid;
    private final long gattTimeoutMs;
    private final long smpResponseTimeoutMs;
    private final long pinInputTimeoutMs;
    private final long btClTimeoutMs;
    private final long postSmpWindowMs;
    private byte[] opticalOobKey16;
    private boolean closed;

    public StrategySessionContext(
            PairingStrategyId requestedStrategy,
            HciTransport transport,
            L2capChannelMux mux,
            PersistencePort persistence,
            UiPromptPort ui,
            SessionConfig config,
            SecureRandom random,
            byte[] opticalOobKey16,
            String productType,
            String watchOsVersion,
            String localIdsDeviceUuid,
            String bluetoothCbUuid,
            String nrUuid,
            long gattTimeoutMs,
            long smpResponseTimeoutMs,
            long pinInputTimeoutMs,
            long btClTimeoutMs,
            long postSmpWindowMs) {
        this.requestedStrategy = Objects.requireNonNull(requestedStrategy, "requestedStrategy");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.mux = Objects.requireNonNull(mux, "mux");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.config = Objects.requireNonNull(config, "config");
        this.random = random == null ? new SecureRandom() : random;
        if (opticalOobKey16 != null) {
            if (opticalOobKey16.length != 16) {
                throw new IllegalArgumentException("Optical OOB key must be 16 bytes");
            }
            this.opticalOobKey16 = opticalOobKey16.clone();
        }
        this.productType = productType;
        this.watchOsVersion = watchOsVersion;
        this.localIdsDeviceUuid = localIdsDeviceUuid != null && !localIdsDeviceUuid.isBlank()
                ? localIdsDeviceUuid : UUID.randomUUID().toString();
        this.bluetoothCbUuid = bluetoothCbUuid != null && !bluetoothCbUuid.isBlank()
                ? bluetoothCbUuid : UUID.randomUUID().toString();
        this.nrUuid = nrUuid != null && !nrUuid.isBlank()
                ? nrUuid : UUID.randomUUID().toString();
        this.gattTimeoutMs = gattTimeoutMs > 0 ? gattTimeoutMs : DEFAULT_GATT_TIMEOUT_MS;
        this.smpResponseTimeoutMs = smpResponseTimeoutMs > 0 ? smpResponseTimeoutMs : DEFAULT_SMP_RESPONSE_TIMEOUT_MS;
        this.pinInputTimeoutMs = pinInputTimeoutMs > 0 ? pinInputTimeoutMs : DEFAULT_PIN_INPUT_TIMEOUT_MS;
        this.btClTimeoutMs = btClTimeoutMs > 0 ? btClTimeoutMs : DEFAULT_BT_CL_TIMEOUT_MS;
        this.postSmpWindowMs = postSmpWindowMs > 0 ? postSmpWindowMs : DEFAULT_POST_SMP_WINDOW_MS;
    }

    public PairingStrategyId requestedStrategy() {
        return requestedStrategy;
    }

    public HciTransport transport() {
        return transport;
    }

    public L2capChannelMux mux() {
        return mux;
    }

    public PersistencePort persistence() {
        return persistence;
    }

    public UiPromptPort ui() {
        return ui;
    }

    public SessionConfig config() {
        return config;
    }

    public SecureRandom random() {
        return random;
    }

    public boolean hasOpticalOobKey() {
        return !closed && opticalOobKey16 != null;
    }

    public byte[] copyOpticalOobKey16() {
        if (closed || opticalOobKey16 == null) {
            return null;
        }
        return opticalOobKey16.clone();
    }

    public String productType() {
        return productType;
    }

    public String watchOsVersion() {
        return watchOsVersion;
    }

    public String localIdsDeviceUuid() {
        return localIdsDeviceUuid;
    }

    public String bluetoothCbUuid() {
        return bluetoothCbUuid;
    }

    public String nrUuid() {
        return nrUuid;
    }

    public long gattTimeoutMs() {
        return gattTimeoutMs;
    }

    public long smpResponseTimeoutMs() {
        return smpResponseTimeoutMs;
    }

    public long pinInputTimeoutMs() {
        return pinInputTimeoutMs;
    }

    public long btClTimeoutMs() {
        return btClTimeoutMs;
    }

    public long postSmpWindowMs() {
        return postSmpWindowMs;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (opticalOobKey16 != null) {
            Arrays.fill(opticalOobKey16, (byte) 0);
            opticalOobKey16 = null;
        }
    }

    @Override
    public String toString() {
        return "StrategySessionContext[strategy=" + requestedStrategy.wireId()
                + ", hasOpticalOob=" + hasOpticalOobKey() + "]";
    }
}
