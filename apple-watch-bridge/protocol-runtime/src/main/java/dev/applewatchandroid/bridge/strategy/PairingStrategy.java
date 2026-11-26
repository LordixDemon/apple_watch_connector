package dev.applewatchandroid.bridge.strategy;

import dev.applewatchandroid.bridge.PairingSessionRecord;
import dev.applewatchandroid.bridge.spi.PairingStrategyId;
import dev.applewatchandroid.bridge.spi.StrategyCapabilities;

/**
 * Plug-in SPI for one Apple Watch pairing & reconnection strategy (Strategies 1–4).
 *
 * <p>Each strategy owns its own GATT/IKE/SMP/BT_CL orchestration while sharing modular
 * {@code dev.applewatchandroid.bridge.transport} primitives. Adding or evolving a strategy
 * requires zero changes to unrelated strategies.</p>
 */
public interface PairingStrategy {
    PairingStrategyId id();

    StrategyCapabilities capabilities();

    /** Executes a fresh pairing handshake on an already-connected LE ACL link. */
    PairingOutcome pair(StrategySessionContext context) throws Exception;

    /** Re-encrypts and reopens post-SMP transport on an already-connected LE ACL link. */
    ReconnectOutcome reconnect(StrategySessionContext context, PairingSessionRecord restored) throws Exception;
}
