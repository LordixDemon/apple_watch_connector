package dev.applewatchandroid.bridge;

import java.util.LinkedHashMap;
import java.util.Map;

/** Joins scan-response setup data only to a recently observed connectable peer. */
final class WatchSetupTargetSelector {
    private static final long RESPONSE_WINDOW_MS = 2_000;
    private static final int MAX_CANDIDATES = 64;
    private final Map<String, Long> connectable = new LinkedHashMap<>();

    HciCodec.AdvertisingReport accept(HciCodec.AdvertisingReport report, long elapsedMs) {
        if (report == null || report.address == null || report.address.length != 6
                || (report.addressType != 0 && report.addressType != 1)) return null;
        connectable.entrySet().removeIf(entry -> elapsedMs - entry.getValue() > RESPONSE_WINDOW_MS
                || elapsedMs < entry.getValue());
        String address = report.addressType + ":" + HciCodec.toHex(report.address);
        if (report.eventType == 0) {
            connectable.remove(address);
            connectable.put(address, elapsedMs);
            if (connectable.size() > MAX_CANDIDATES) connectable.remove(connectable.keySet().iterator().next());
        }
        if (report.isExpectedTarget()) return report;
        if (report.eventType != 4 || report.setup == null || !connectable.containsKey(address)) return null;
        // The connectability comes from ADV_IND; all setup bytes come from the
        // actual SCAN_RSP. No identifiers, metadata or PIN binding are invented.
        var combined = new HciCodec.AdvertisingReport(0, report.addressType, report.address,
                report.rssi, report.data, report.setup);
        return combined.isExpectedTarget() ? combined : null;
    }
}
