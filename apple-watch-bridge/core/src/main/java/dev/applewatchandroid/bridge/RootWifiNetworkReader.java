package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Runs inside the existing root HAL. No credentials in shell arguments, stdout, IPC or files. */
final class RootWifiNetworkReader {
    interface Reader { byte[] currentArchive() throws Exception; }
    private static volatile Reader platformReader;
    static void installPlatformReader(Reader reader) { platformReader = java.util.Objects.requireNonNull(reader); }
    private RootWifiNetworkReader() { }
    static byte[] currentArchive() throws Exception {
        Reader reader = platformReader;
        if (reader != null) return reader.currentArchive();
        String before = wifi("status");
        String scan = wifi("list-scan-results");
        Path path = java.nio.file.Paths.get("/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml");
        if (Files.size(path) > AndroidWifiNetwork.MAX_CONFIG) throw new IllegalArgumentException("Wi-Fi config exceeds limit");
        byte[] xml;
        try (InputStream input = Files.newInputStream(path)) {
            xml = input.readNBytes(AndroidWifiNetwork.MAX_CONFIG + 1);
        }
        try {
            AndroidWifiNetwork.select(xml, before, scan);
            String after = wifi("status");
            if (!AndroidWifiNetwork.currentSsid(before).equals(AndroidWifiNetwork.currentSsid(after))
                    || !AndroidWifiNetwork.currentBssid(before).equals(AndroidWifiNetwork.currentBssid(after))) {
                throw new IllegalArgumentException("Wi-Fi connection changed");
            }
            // Recheck security, validation and config against the final observed connection.
            return AndroidWifiNetwork.select(xml, after, scan).archive();
        } finally { Arrays.fill(xml, (byte) 0); }
    }
    /** Root-only read/encode diagnostic; does not open Bluetooth or send anything. */
    public static void main(String[] args) {
        byte[] archive = null;
        try {
            archive = currentArchive();
            System.out.println("WIFI LOCAL PROBE archiveBytes=" + archive.length + "; secrets logged=false; sent=false.");
        } catch (Exception invalid) {
            System.out.println("WIFI LOCAL PROBE refused; secrets logged=false; sent=false.");
            System.exit(1);
        } finally { if (archive != null) Arrays.fill(archive, (byte) 0); }
    }
    private static String wifi(String verb) throws Exception {
        Process process = new ProcessBuilder("/system/bin/cmd", "wifi", verb).redirectErrorStream(true).start();
        try {
            // Read in parallel with the timeout: neither pipe fullness nor Binder may stall HAL forever.
            var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try (InputStream input = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (bytes.size() + count > 64 * 1024) throw new IllegalArgumentException("Wi-Fi status exceeds limit");
                        bytes.write(buffer, 0, count);
                    }
                    return bytes.toString(StandardCharsets.UTF_8);
                } catch (Exception invalid) { throw new IllegalStateException("Wi-Fi status unavailable"); }
            });
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalArgumentException("Wi-Fi status unavailable");
            }
            return future.get(1, TimeUnit.SECONDS);
        } finally { process.destroyForcibly(); }
    }
}
