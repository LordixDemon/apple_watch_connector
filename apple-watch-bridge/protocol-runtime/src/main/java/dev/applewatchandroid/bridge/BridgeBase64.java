package dev.applewatchandroid.bridge;

/** Standard RFC 4648 envelopes used by the existing private process IPC. */
final class BridgeBase64 {
    static final int NO_WRAP = 2;
    private BridgeBase64() { }
    static String encodeToString(byte[] bytes, int flags) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }
    static byte[] encode(byte[] bytes, int flags) {
        return java.util.Base64.getEncoder().encode(bytes);
    }
    static byte[] decode(String text, int flags) {
        return java.util.Base64.getDecoder().decode(text.replaceAll("\\s", ""));
    }
}
