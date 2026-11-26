package dev.applewatchandroid.bridge.spi;

/** User authentication styles supported across Watch pairing strategies. */
public enum AuthStyle {
    PIN,
    OPTICAL,
    OOB_KEY,
    NUMERIC_COMPARISON
}
