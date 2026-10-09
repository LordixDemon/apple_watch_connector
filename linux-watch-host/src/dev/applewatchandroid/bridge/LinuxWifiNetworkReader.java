package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** NetworkManager adapter. Credentials never become command arguments or public events. */
final class LinuxWifiNetworkReader {
    private LinuxWifiNetworkReader() { }
    static byte[] currentArchive() throws Exception {
        if (!nm("-g", "CONNECTIVITY", "general").trim().equals("full")) throw new IOException("Wi-Fi has no validated Internet access");
        String uuid = null, device = null;
        for (String line : nm("-t", "-e", "no", "-f", "UUID,TYPE,DEVICE", "connection", "show", "--active").split("\n")) {
            String[] fields = line.split(":", -1);
            if (fields.length == 3 && fields[1].equals("802-11-wireless")) {
                if (uuid != null) throw new IOException("Multiple active Wi-Fi connections");
                uuid = fields[0]; device = fields[2];
            }
        }
        if (uuid == null || !UUID.fromString(uuid).toString().equals(uuid)
                || !device.matches("[a-zA-Z0-9_.-]{1,64}")) throw new IOException("No supported active Wi-Fi connection");
        String[] profile = nm("--show-secrets", "-e", "no", "-g",
                "802-11-wireless.ssid,802-11-wireless.hidden,802-11-wireless-security.key-mgmt,802-11-wireless-security.pairwise,802-11-wireless-security.psk",
                "connection", "show", "uuid", uuid).split("\n", -1);
        if (profile.length != 6 || !profile[1].equals("no") || !profile[2].equals("wpa-psk")
                || !(profile[3].isEmpty() || profile[3].equals("ccmp"))) throw new IOException("Unsupported Wi-Fi security/profile");
        boolean broadcast = false;
        for (String line : nm("-t", "-e", "yes", "-f", "IN-USE,SSID,SECURITY", "device", "wifi", "list", "ifname", device, "--rescan", "no").split("\n")) {
            List<String> fields = splitEscaped(line);
            if (fields.size() == 3 && fields.get(0).equals("*") && fields.get(1).equals(profile[0])
                    && fields.get(2).equals("WPA2")) broadcast = true;
        }
        if (!broadcast || !nm("-g", "GENERAL.CON-UUID", "device", "show", device).trim().equals(uuid)) {
            throw new IOException("Wi-Fi connection changed or broadcast WPA2 was not observed");
        }
        return WifiNetworkSyncCodec.addWpa2(profile[0], profile[4]);
    }
    static List<String> splitEscaped(String line) {
        List<String> fields = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean escaped = false;
        for (char c : line.toCharArray()) {
            if (escaped) { value.append(c); escaped = false; }
            else if (c == '\\') escaped = true;
            else if (c == ':') { fields.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        if (escaped) throw new IllegalArgumentException("Truncated NetworkManager escape");
        fields.add(value.toString()); return fields;
    }
    private static String nm(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("nmcli")); command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command); builder.environment().put("LC_ALL", "C");
        Process process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            CompletableFuture<byte[]> result = CompletableFuture.supplyAsync(() -> {
                try (InputStream input = process.getInputStream()) { return input.readNBytes(64 * 1024 + 1); }
                catch (IOException failure) { throw new CompletionException(failure); }
            });
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IOException("NetworkManager query unavailable");
            byte[] bytes = result.get(1, TimeUnit.SECONDS);
            try {
                if (bytes.length > 64 * 1024) throw new IOException("NetworkManager output exceeded limit");
                return new String(bytes, StandardCharsets.UTF_8);
            } finally { Arrays.fill(bytes, (byte) 0); }
        } finally { process.destroyForcibly(); }
    }
}
