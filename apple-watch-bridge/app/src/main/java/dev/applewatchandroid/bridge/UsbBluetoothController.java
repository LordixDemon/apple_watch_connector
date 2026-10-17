package dev.applewatchandroid.bridge;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Unprivileged HCI transport: an external USB Bluetooth controller leased through
 * the Android USB host API. The phone's own Bluetooth stack is never touched, so
 * neither root nor a disabled stock adapter is required.
 */
final class UsbBluetoothController implements BluetoothController {
    private static final int CONTROL_TIMEOUT_MS = 2_000;
    private static final int BULK_TIMEOUT_MS = 2_000;

    /** USB Bluetooth primary controller interface: Wireless / RF / Bluetooth. */
    static boolean isHciInterface(UsbInterface intf) {
        return intf.getInterfaceClass() == 0xe0
                && intf.getInterfaceSubclass() == 0x01
                && intf.getInterfaceProtocol() == 0x01
                && intf.getAlternateSetting() == 0;
    }

    static boolean isBluetoothController(UsbDevice device) {
        return endpoints(device) != null;
    }

    private static final class Endpoints {
        UsbInterface hci;
        UsbInterface voice;
        UsbEndpoint events;
        UsbEndpoint aclIn;
        UsbEndpoint aclOut;
    }

    private static Endpoints endpoints(UsbDevice device) {
        Endpoints result = null;
        UsbInterface voice = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() == 0xe0 && intf.getInterfaceSubclass() == 0x01
                    && intf.getInterfaceProtocol() == 0x01 && intf.getAlternateSetting() == 0) {
                UsbEndpoint events = null, aclIn = null, aclOut = null;
                boolean isochronous = false;
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint endpoint = intf.getEndpoint(e);
                    int type = endpoint.getType();
                    boolean in = endpoint.getDirection() == UsbConstants.USB_DIR_IN;
                    if (type == UsbConstants.USB_ENDPOINT_XFER_INT && in) events = endpoint;
                    else if (type == UsbConstants.USB_ENDPOINT_XFER_BULK && in) aclIn = endpoint;
                    else if (type == UsbConstants.USB_ENDPOINT_XFER_BULK) aclOut = endpoint;
                    else if (type == UsbConstants.USB_ENDPOINT_XFER_ISOC) isochronous = true;
                }
                if (result == null && events != null && aclIn != null && aclOut != null) {
                    result = new Endpoints();
                    result.hci = intf;
                    result.events = events;
                    result.aclIn = aclIn;
                    result.aclOut = aclOut;
                } else if (voice == null && isochronous) {
                    voice = intf;
                }
            }
        }
        if (result != null && voice != null && voice.getId() != result.hci.getId()) result.voice = voice;
        return result;
    }

    /** All attached controllers in a stable order; access is checked by the caller. */
    static List<UsbDevice> controllers(UsbManager manager) {
        List<UsbDevice> devices = new ArrayList<>();
        if (manager == null) return devices;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (isBluetoothController(device)) devices.add(device);
        }
        devices.sort((a, b) -> a.getDeviceName().compareTo(b.getDeviceName()));
        return devices;
    }

    static String describe(UsbDevice device) {
        String product = device.getProductName();
        return String.format(java.util.Locale.US, "%04x:%04x%s", device.getVendorId(), device.getProductId(),
                product == null || product.isBlank() ? "" : " (" + product.trim() + ")");
    }

    private final UsbManager manager;
    private final UsbDevice device;
    private UsbDeviceConnection connection;
    private Endpoints pipes;
    private Thread reader;
    private volatile boolean closing;
    private volatile IOException transportFailure;
    private final Object writeLock = new Object();

    UsbBluetoothController(UsbManager manager, UsbDevice device) {
        this.manager = manager;
        this.device = device;
    }

    @Override
    public synchronized void initialize(Callbacks callbacks) throws IOException {
        if (connection != null) {
            // A retried initialize after close() starts over on a fresh lease.
            callbacks.initializationComplete(ALREADY_INITIALIZED);
            return;
        }
        closing = false;
        transportFailure = null;
        if (!manager.hasPermission(device)) {
            throw new IOException("USB permission for Bluetooth adapter " + describe(device) + " is not granted");
        }
        Endpoints endpoints = endpoints(device);
        if (endpoints == null) throw new IOException("USB device is not a Bluetooth HCI controller");
        UsbDeviceConnection opened = manager.openDevice(device);
        if (opened == null) throw new IOException("Cannot open USB Bluetooth adapter " + describe(device));
        try {
            // force=true detaches a kernel btusb driver where the vendor kernel ships one.
            if (!opened.claimInterface(endpoints.hci, true)) {
                throw new IOException("Cannot claim USB Bluetooth HCI interface");
            }
            opened.setInterface(endpoints.hci);
            if (endpoints.voice != null && opened.claimInterface(endpoints.voice, true)) {
                // Alternate setting 0: no SCO bandwidth is reserved.
                UsbInterface zero = null;
                for (int i = 0; i < device.getInterfaceCount(); i++) {
                    UsbInterface candidate = device.getInterface(i);
                    if (candidate.getId() == endpoints.voice.getId() && candidate.getAlternateSetting() == 0) {
                        zero = candidate;
                    }
                }
                if (zero != null) opened.setInterface(zero);
            }
        } catch (IOException failure) {
            opened.close();
            throw failure;
        }
        connection = opened;
        pipes = endpoints;
        ProtocolHost.log("USB HCI LEASE: adapter=" + describe(device)
                + " interface=" + endpoints.hci.getId()
                + " events=0x" + Integer.toHexString(endpoints.events.getAddress())
                + " aclIn=0x" + Integer.toHexString(endpoints.aclIn.getAddress())
                + " aclOut=0x" + Integer.toHexString(endpoints.aclOut.getAddress())
                + "; stock Android Bluetooth untouched.");
        try {
            RealtekFirmwareLoader.prepare(this, device.getVendorId());
        } catch (IOException failure) {
            close();
            throw failure;
        }
        reader = new Thread(() -> readLoop(callbacks), "usb-hci-read");
        reader.setDaemon(true);
        reader.start();
        callbacks.initializationComplete(SUCCESS);
    }

    @Override
    public void sendHciCommand(byte[] bytes) throws IOException {
        if (transportFailure != null) throw transportFailure;
        UsbDeviceConnection current = connection;
        if (current == null) throw new IOException("USB HCI lease is closed");
        synchronized (writeLock) {
            int count = current.controlTransfer(0x20, 0, 0, pipes.hci.getId(), bytes, bytes.length,
                    CONTROL_TIMEOUT_MS);
            if (count != bytes.length) throw new IOException("USB HCI command write failed (" + count + ")");
        }
    }

    @Override
    public void sendAclData(byte[] bytes) throws IOException {
        if (transportFailure != null) throw transportFailure;
        UsbDeviceConnection current = connection;
        if (current == null) throw new IOException("USB HCI lease is closed");
        synchronized (writeLock) {
            int offset = 0;
            while (offset < bytes.length) {
                int chunk = Math.min(16_384, bytes.length - offset);
                int count = current.bulkTransfer(pipes.aclOut, bytes, offset, chunk, BULK_TIMEOUT_MS);
                if (count <= 0) throw new IOException("USB HCI ACL write failed (" + count + ")");
                offset += count;
            }
        }
    }

    @Override
    public synchronized void close() {
        closing = true;
        Thread current = reader;
        reader = null;
        if (current != null) {
            current.interrupt();
            try { current.join(2_000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        UsbDeviceConnection opened = connection;
        connection = null;
        if (opened != null) {
            try {
                opened.releaseInterface(pipes.hci);
                if (pipes.voice != null) opened.releaseInterface(pipes.voice);
            } finally {
                opened.close();
            }
        }
    }

    /**
     * Synchronous command used only before the reader starts (vendor firmware
     * setup). Returns the Command Complete return parameters (status first).
     */
    byte[] vendorCommand(int opcode, byte[] parameters, long timeoutMs) throws IOException {
        byte[] command = new byte[3 + parameters.length];
        command[0] = (byte) opcode;
        command[1] = (byte) (opcode >> 8);
        command[2] = (byte) parameters.length;
        System.arraycopy(parameters, 0, command, 3, parameters.length);
        sendHciCommand(command);
        PacketAssembler assembler = new PacketAssembler(true);
        byte[] buffer = new byte[Math.max(64, pipes.events.getMaxPacketSize())];
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int count = connection.bulkTransfer(pipes.events, buffer, buffer.length, 500);
            if (count <= 0) continue;
            for (byte[] event : assembler.accept(buffer, count)) {
                // Command Complete: 0x0e, len, numPackets, opcode(2), returnParameters...
                if (event.length >= 6 && (event[0] & 0xff) == 0x0e
                        && ((event[3] & 0xff) | ((event[4] & 0xff) << 8)) == opcode) {
                    return java.util.Arrays.copyOfRange(event, 5, event.length);
                }
                // Command Status failure for a vendor command.
                if (event.length >= 6 && (event[0] & 0xff) == 0x0f
                        && ((event[4] & 0xff) | ((event[5] & 0xff) << 8)) == opcode && event[2] != 0) {
                    throw new IOException(String.format(java.util.Locale.US,
                            "Controller rejected command 0x%04x: status 0x%02x", opcode, event[2] & 0xff));
                }
            }
        }
        throw new IOException(String.format(java.util.Locale.US, "Controller command 0x%04x timed out", opcode));
    }

    private void readLoop(Callbacks callbacks) {
        UsbDeviceConnection current = connection;
        UsbRequest events = new UsbRequest();
        UsbRequest acl = new UsbRequest();
        try {
            if (!events.initialize(current, pipes.events) || !acl.initialize(current, pipes.aclIn)) {
                throw new IOException("Cannot initialize USB HCI receive requests");
            }
            int eventSize = Math.max(16, pipes.events.getMaxPacketSize());
            int aclPacket = Math.max(64, pipes.aclIn.getMaxPacketSize());
            // Whole USB packets only; HCI frames are reassembled across transfers.
            int aclSize = aclPacket * Math.max(1, 2048 / aclPacket);
            ByteBuffer eventBuffer = ByteBuffer.allocate(eventSize);
            ByteBuffer aclBuffer = ByteBuffer.allocate(aclSize);
            PacketAssembler eventAssembler = new PacketAssembler(true);
            PacketAssembler aclAssembler = new PacketAssembler(false);
            if (!events.queue(eventBuffer) || !acl.queue(aclBuffer)) {
                throw new IOException("Cannot queue USB HCI receive requests");
            }
            while (!closing) {
                UsbRequest done;
                try {
                    done = current.requestWait(1_000);
                } catch (TimeoutException idle) {
                    continue;
                }
                if (done == null) {
                    if (closing) return;
                    throw new IOException("USB Bluetooth adapter disconnected");
                }
                boolean isEvent = done == events;
                ByteBuffer buffer = isEvent ? eventBuffer : aclBuffer;
                int count = buffer.position();
                byte[] data = buffer.array();
                for (byte[] packet : (isEvent ? eventAssembler : aclAssembler).accept(data, count)) {
                    if (isEvent) callbacks.hciEventReceived(packet);
                    else callbacks.aclDataReceived(packet);
                }
                buffer.clear();
                if (!done.queue(buffer)) {
                    if (closing) return;
                    throw new IOException("USB Bluetooth adapter disconnected");
                }
            }
        } catch (IOException failure) {
            if (closing) return;
            transportFailure = failure;
            ProtocolHost.log("USB HCI FAILED: " + failure.getMessage());
            callbacks.transportFailed(failure);
        } catch (RuntimeException failure) {
            if (closing) return;
            IOException wrapped = new IOException("USB HCI receive failed: " + failure.getMessage(), failure);
            transportFailure = wrapped;
            callbacks.transportFailed(wrapped);
        } finally {
            events.cancel();
            acl.cancel();
            events.close();
            acl.close();
        }
    }

    /** Reassembles HCI events (2-byte header) or ACL packets (4-byte header). */
    static final class PacketAssembler {
        private final boolean event;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        PacketAssembler(boolean event) { this.event = event; }

        List<byte[]> accept(byte[] data, int count) throws IOException {
            pending.write(data, 0, count);
            List<byte[]> packets = new ArrayList<>();
            byte[] bytes = pending.toByteArray();
            int header = event ? 2 : 4;
            int offset = 0;
            while (bytes.length - offset >= header) {
                int payload = event
                        ? bytes[offset + 1] & 0xff
                        : (bytes[offset + 2] & 0xff) | ((bytes[offset + 3] & 0xff) << 8);
                int length = header + payload;
                if (bytes.length - offset < length) break;
                packets.add(java.util.Arrays.copyOfRange(bytes, offset, offset + length));
                offset += length;
            }
            pending.reset();
            if (bytes.length - offset > 65_536) throw new IOException("HCI packet exceeds envelope");
            pending.write(bytes, offset, bytes.length - offset);
            return packets;
        }
    }
}
