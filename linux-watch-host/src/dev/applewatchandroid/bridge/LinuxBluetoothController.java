package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.file.Path;

/** H4 packets through the Rust HCI user-channel lease, with bounded binary envelopes. */
final class LinuxBluetoothController implements BluetoothController, AutoCloseable {
    private final Path helper;
    private Process process;
    private DataOutputStream output;
    LinuxBluetoothController(Path helper) { this.helper = helper; }
    public void initialize(Callbacks callbacks) throws IOException {
        if (process != null) throw new IOException("Controller already leased");
        String index = System.getProperty("watch.hci", "");
        if (!index.matches("[0-9]{1,5}") || Integer.parseInt(index) >= 65535) throw new IOException("Invalid controller index");
        String expected = System.getProperty("watch.adapter", "");
        if (!expected.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) throw new IOException("Selected Bluetooth adapter identity is required");
        {
            Process address = new ProcessBuilder("busctl", "--system", "--timeout=2", "get-property", "org.bluez",
                    "/org/bluez/hci" + index, "org.bluez.Adapter1", "Address")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!address.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) || address.exitValue() != 0)
                    throw new IOException("Selected Bluetooth adapter unavailable");
                verifyAdapterAddress(expected, new String(address.getInputStream().readNBytes(128), java.nio.charset.StandardCharsets.UTF_8));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); throw new IOException("Adapter verification interrupted", interrupted);
            } finally {
                address.destroyForcibly();
                try { address.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
        // BlueZ may power the adapter again after the preceding lease closes.
        // Release exactly the selected adapter on every acquisition, rather
        // than stopping the system Bluetooth service or modifying its config.
        Process release = new ProcessBuilder("busctl", "--system", "set-property", "org.bluez",
                "/org/bluez/hci" + index, "org.bluez.Adapter1", "Powered", "b", "false")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            if (!release.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) || release.exitValue() != 0) {
                release.destroyForcibly(); throw new IOException("BlueZ did not release the selected controller");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); release.destroyForcibly(); throw new IOException("Controller release interrupted", interrupted);
        }
        process = new ProcessBuilder(helper.toString(), index)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        output = new DataOutputStream(process.getOutputStream());
        DataInputStream input = new DataInputStream(process.getInputStream());
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    int length = input.readUnsignedShort();
                    if (length < 1 || length > 65535) throw new IOException("Invalid HCI envelope");
                    byte[] packet = input.readNBytes(length);
                    if (packet.length != length) throw new EOFException("HCI lease ended");
                    byte[] payload = java.util.Arrays.copyOfRange(packet, 1, length);
                    switch (packet[0]) {
                        case 0 -> callbacks.initializationComplete(SUCCESS);
                        case 2 -> callbacks.aclDataReceived(payload);
                        case 4 -> callbacks.hciEventReceived(payload);
                        default -> { }
                    }
                }
            } catch (IOException failure) {
                ProtocolHost.log("LINUX HCI CLOSED: " + failure.getMessage());
                callbacks.initializationComplete(-1);
            }
        }, "linux-hci-read");
        reader.setDaemon(true);
        reader.start();
    }
    static void verifyAdapterAddress(String expected, String report) throws IOException {
        if (!expected.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") ||
                !report.trim().equalsIgnoreCase("s \"" + expected + "\""))
            throw new IOException("Selected Bluetooth adapter changed; refresh the adapter list");
    }
    private synchronized void send(int kind, byte[] bytes) throws IOException {
        if (output == null || bytes.length > 65534) throw new IOException("Invalid HCI send");
        output.writeShort(bytes.length + 1);
        output.writeByte(kind);
        output.write(bytes);
        output.flush();
    }
    public void sendHciCommand(byte[] bytes) throws IOException { send(1, bytes); }
    public void sendAclData(byte[] bytes) throws IOException { send(2, bytes); }
    public synchronized void close() throws IOException {
        try {
            if (output != null) output.close();
        } finally {
            output = null;
            if (process != null) {
                try {
                    if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IOException("HCI broker did not exit");
                        }
                    }
                } catch (InterruptedException interrupted) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                } finally { process = null; }
            }
        }
    }
}
