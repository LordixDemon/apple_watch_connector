package dev.applewatchandroid.bridge.transport;

import java.util.Arrays;

/**
 * Authentication mode selection for {@link SmpEngine}.
 *
 * <p>Secret-bearing modes defensively copy their OOB key arrays on construction and accessor
 * read, wipe their internal copy on {@link #close()}, and never format key bytes in
 * {@code toString()}.</p>
 */
public sealed interface SmpAuthMode extends AutoCloseable
        permits SmpAuthMode.LeScOob,
                SmpAuthMode.LeScPasskey,
                SmpAuthMode.LeScNumericComparison,
                SmpAuthMode.LeLegacy {

    @Override
    default void close() {
    }

    /**
     * LE Secure Connections optical OOB pairing (with automatic fallback to LE Legacy {@code SC=0}
     * if the Watch clears {@code SC}). Supports both one-way OOB ({@code resp.oob == 0},
     * {@code ra = 0[16], rb = opticalOobKey16}) and mutual OOB ({@code resp.oob == 1},
     * {@code ra = rb = opticalOobKey16}).
     */
    record LeScOob(byte[] opticalOobKey16) implements SmpAuthMode {
        public LeScOob {
            if (opticalOobKey16 == null || opticalOobKey16.length != 16) {
                throw new IllegalArgumentException("LE SC optical OOB key must be 16 bytes");
            }
            opticalOobKey16 = opticalOobKey16.clone();
        }

        @Override
        public byte[] opticalOobKey16() {
            return opticalOobKey16.clone();
        }

        @Override
        public void close() {
            Arrays.fill(opticalOobKey16, (byte) 0);
        }

        @Override
        public String toString() {
            return "LeScOob[keyPresent=true]";
        }
    }

    /**
     * LE Secure Connections 20-round 6-digit passkey pairing (omitting {@code CT2}), with
     * automatic fallback to LE Legacy {@code SC=0} passkey if the Watch clears {@code SC}.
     */
    record LeScPasskey() implements SmpAuthMode {}

    /**
     * LE Secure Connections Numeric Comparison ({@code DisplayYesNo}, {@code f4} + {@code g2}
     * 6-digit user approval + {@code f5} + {@code f6}).
     */
    record LeScNumericComparison() implements SmpAuthMode {}

    /**
     * Explicit Bluetooth 4.0 LE Legacy ({@code SC=0}) pairing using either a 16-byte optical OOB
     * key or 6-digit passkey when {@code optionalOobKey16} is {@code null}.
     */
    record LeLegacy(byte[] optionalOobKey16) implements SmpAuthMode {
        public LeLegacy {
            if (optionalOobKey16 != null) {
                if (optionalOobKey16.length != 16) {
                    throw new IllegalArgumentException("Optional LE Legacy OOB key must be 16 bytes");
                }
                optionalOobKey16 = optionalOobKey16.clone();
            }
        }

        @Override
        public byte[] optionalOobKey16() {
            return optionalOobKey16 == null ? null : optionalOobKey16.clone();
        }

        @Override
        public void close() {
            if (optionalOobKey16 != null) {
                Arrays.fill(optionalOobKey16, (byte) 0);
            }
        }

        @Override
        public String toString() {
            return "LeLegacy[oobKeyPresent=" + (optionalOobKey16 != null) + "]";
        }
    }
}
