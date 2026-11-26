package dev.applewatchandroid.bridge.strategy;

/**
 * Interactive owner prompt & structured diagnostic logging port injected into strategies.
 * Implementations must emit the unified {@code PIN_REQUIRED:} marker for all strategies (S1–S4)
 * and must never log PIN digits, numeric comparison digits, or cryptographic secrets.
 */
public interface UiPromptPort {
    /** Prompts the owner with {@code PIN_REQUIRED:} and returns a 6-digit {@code char[]} (caller wipes). */
    char[] awaitSixDigitPin(long timeoutMs) throws Exception;

    /** Prompts the owner to confirm a 6-digit LE SC Numeric Comparison value. */
    default boolean confirmNumericComparison(int sixDigitCode, long timeoutMs) throws Exception {
        return false;
    }

    /** Emits a structured, secret-free diagnostic line. */
    void log(String message);
}
