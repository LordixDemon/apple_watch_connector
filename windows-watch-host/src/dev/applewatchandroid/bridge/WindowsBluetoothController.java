package dev.applewatchandroid.bridge;

import java.io.*;
import java.nio.file.Path;

/** H4 packets through the Rust HCI user-channel lease, with bounded binary envelopes. */
final class WindowsBluetoothController implements BluetoothController, AutoCloseable {
    private final Path helper;
    private Process process;
    private DataOutputStream output;
    private volatile IOException transportFailure;
    private volatile boolean closing;
    WindowsBluetoothController(Path helper) { this.helper = helper; }
    public void initialize(Callbacks callbacks) throws IOException {
        if (process != null) throw new IOException("Controller already leased");
        String selected = System.getProperty("watch.adapter", "");
        if (!selected.matches("(?:usb|winusb):[0-9]{1,3}:[0-9.]{1,32}:[0-9a-f]{4}:[0-9a-f]{4}")) throw new IOException("Explicit USB Bluetooth selection is required");
        process = new ProcessBuilder(helper.toString(), "--open", selected)
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
                        case 0x7f -> throw new IOException(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                        default -> throw new IOException("Unknown HCI envelope type");
                    }
                }
            } catch (IOException failure) {
                if (closing) return;
                transportFailure = failure;
                ProtocolHost.log("WINDOWS HCI FAILED: " + failure.getMessage());
                callbacks.initializationComplete(-1);
            }
        }, "windows-hci-read");
        reader.setDaemon(true);
        reader.start();
    }
    private synchronized void send(int kind, byte[] bytes) throws IOException {
        if (transportFailure != null) throw transportFailure;
        if (output == null || bytes.length > 65534) throw new IOException("Invalid HCI send");
        output.writeShort(bytes.length + 1);
        output.writeByte(kind);
        output.write(bytes);
        output.flush();
    }
    public void sendHciCommand(byte[] bytes) throws IOException { send(1, bytes); }
    public void sendAclData(byte[] bytes) throws IOException { send(2, bytes); }
    public synchronized void close() throws IOException {
        closing = true;
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
