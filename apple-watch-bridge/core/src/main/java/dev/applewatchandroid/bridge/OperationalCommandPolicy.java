package dev.applewatchandroid.bridge;

/** Application commands cannot replay setup or smuggle another stdin line. */
final class OperationalCommandPolicy {
    private static final int MAX_COMMAND = 180_000;
    static boolean isAllowed(String command) {
        if (command == null || command.length() > MAX_COMMAND) return false;
        String line = command.stripTrailing();
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0 || line.indexOf('\0') >= 0) return false;
        if (line.equals("PING_WATCH")) return true;
        if (line.equals("REQUEST_REGISTRY")) return true;
        if (line.equals("REQUEST_DEVICE_ABOUT")) return true;
        if (line.equals(WifiNetworkSyncCodec.COMMAND)) return true;
        if (line.equals("REQUEST_FACE_COLLECTION")) return true;
        if (line.equals(NativeWatchReboot.COMMAND)) return true;
        if (line.equals(SysdiagnoseArchiveInventory.COMMAND)) return true;
        if (line.equals(SysdiagnoseCollection.COMMAND)) return true;
        if (PigmentPreferenceCommand.matches(line)) {
            try { PigmentPreferenceCommand.parse(line); return true; }
            catch (IllegalArgumentException invalid) { return false; }
        }
        if (MonogramPreferenceCommand.matches(line)) {
            try { MonogramPreferenceCommand.parse(line); return true; }
            catch (IllegalArgumentException invalid) { return false; }
        }
        if (ClockFaceDeltaCommand.matches(line)) {
            try { ClockFaceDeltaCommand.parse(line); return true; }
            catch (IllegalArgumentException invalid) { return false; }
        }
        if (line.startsWith(WatchSettingsCodec.COMMAND_PREFIX)) {
            try { WatchSettingsCodec.parseCommand(line); return true; }
            catch (IllegalArgumentException invalid) { return false; }
        }
        if (line.startsWith("SET_ACTIVE_FACE:")) {
            return line.substring("SET_ACTIVE_FACE:".length()).matches("[A-Za-z0-9._-]{1,128}");
        }
        if (line.startsWith("SEND_BULLETIN:")) {
            return line.substring("SEND_BULLETIN:".length()).matches("[A-Za-z0-9+/]+={0,2}");
        }
        if (line.startsWith("REMOVE_BULLETIN:")) {
            return line.substring("REMOVE_BULLETIN:".length()).matches("[A-Za-z0-9+/]+={0,2}");
        }
        return false;
    }
    static long reconnectDelayMs(int failures) {
        if (failures < 1) throw new IllegalArgumentException("Failure count must be positive");
        return failures == 1 ? 15_000L : failures == 2 ? 30_000L : 60_000L;
    }
}
