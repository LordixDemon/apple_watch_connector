package dev.applewatchandroid.bridge;

import android.content.res.AssetManager;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;

/**
 * Restores the RAM patch of common Realtek USB Bluetooth adapters (what Linux
 * btrtl does at probe time). Only the bundled linux-firmware "Realtech" v1
 * epatch format is supported; flash and BD_ADDR are never written. Adapters
 * that are already patched, or that are not Realtek, are left unchanged.
 */
final class RealtekFirmwareLoader {
    private static final int REALTEK_VENDOR = 0x0bda;
    private static final int OP_RESET = 0x0c03;
    private static final int OP_READ_LOCAL_VERSION = 0x1001;
    private static final int OP_READ_ROM_VERSION = 0xfc6d;
    private static final int OP_DOWNLOAD = 0xfc20;
    private static final int MAX_FIRMWARE = 1024 * 1024;
    private static final byte[] SIGNATURE = "Realtech".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] EXTENSION = {0x51, 0x04, (byte) 0xfd, 0x77};

    private static volatile AssetManager assets;

    static void installAssets(AssetManager manager) { assets = manager; }

    /** lmp_subver, hci_rev, firmware, config, expected epatch project ID. */
    private record Chip(int lmpSubversion, int hciRevision, String firmware, String config, int project) { }

    private static final Chip[] CHIPS = {
            new Chip(0x8723, 0xb, "rtl8723b_fw.bin", null, 1),
            new Chip(0x8723, 0xd, "rtl8723d_fw.bin", "rtl8723d_config.bin", 9),
            new Chip(0x8821, 0xa, "rtl8821a_fw.bin", "rtl8821a_config.bin", 2),
            new Chip(0x8821, 0xc, "rtl8821c_fw.bin", "rtl8821c_config.bin", 10),
            new Chip(0x8761, 0xa, "rtl8761a_fw.bin", "rtl8761a_config.bin", 3),
            new Chip(0x8761, 0xb, "rtl8761bu_fw.bin", "rtl8761bu_config.bin", 14),
            new Chip(0x8761, 0xe, "rtl8761cu_fw.bin", "rtl8761cu_config.bin", 51),
            new Chip(0x8822, 0xb, "rtl8822b_fw.bin", "rtl8822b_config.bin", 8),
    };

    private RealtekFirmwareLoader() { }

    static void prepare(UsbBluetoothController controller, int vendor) throws IOException {
        // Many Realtek modules are sold under other USB vendor IDs; the HCI
        // manufacturer (0x005d) is authoritative, so every adapter is asked.
        controller.vendorCommand(OP_RESET, new byte[0], 5_000);
        byte[] version = controller.vendorCommand(OP_READ_LOCAL_VERSION, new byte[0], 5_000);
        if (version.length < 9 || version[0] != 0) {
            throw new IOException("Controller did not report its HCI version");
        }
        int hciVersion = version[1] & 0xff;
        int hciRevision = u16(version, 2);
        int manufacturer = u16(version, 5);
        int lmpSubversion = u16(version, 7);
        ProtocolHost.log(String.format(Locale.US,
                "USB HCI CONTROLLER: hciVersion=%d hciRevision=0x%04x manufacturer=0x%04x lmpSubversion=0x%04x",
                hciVersion, hciRevision, manufacturer, lmpSubversion));
        if (hciVersion < 8) {
            ProtocolHost.log("USB HCI NOTE: controller reports Bluetooth < 4.2; this is untested. "
                    + "A Bluetooth 5.x adapter is recommended.");
        }
        if (manufacturer != 0x005d && vendor != REALTEK_VENDOR) return;
        Chip chip = null;
        for (Chip candidate : CHIPS) {
            if (candidate.lmpSubversion == lmpSubversion && candidate.hciRevision == hciRevision) chip = candidate;
        }
        if (chip == null) {
            // A patched controller reports the firmware subversion, not the ROM one.
            ProtocolHost.log("REALTEK FIRMWARE: no ROM-state match; controller firmware left in place.");
            return;
        }
        byte[] rom = controller.vendorCommand(OP_READ_ROM_VERSION, new byte[0], 5_000);
        if (rom.length < 2 || rom[0] != 0) throw new IOException("Invalid Realtek ROM version response");
        byte[] firmware = read(chip.firmware, true);
        byte[] config = chip.config == null ? null : read(chip.config, false);
        byte[] image = patch(firmware, rom[1] & 0xff, chip.project, config);
        ProtocolHost.log("REALTEK FIRMWARE: downloading " + chip.firmware
                + (config == null ? "" : " + " + chip.config) + " (" + image.length + " bytes, rom="
                + (rom[1] & 0xff) + ").");
        download(controller, image);
        byte[] updated = controller.vendorCommand(OP_READ_LOCAL_VERSION, new byte[0], 5_000);
        if (updated.length < 9 || updated[0] != 0) throw new IOException("Realtek firmware version readback failed");
        ProtocolHost.log(String.format(Locale.US,
                "REALTEK FIRMWARE PASS: hciRevision=0x%04x lmpSubversion=0x%04x after download.",
                u16(updated, 2), u16(updated, 7)));
    }

    static byte[] patch(byte[] firmware, int rom, int expectedProject, byte[] config) throws IOException {
        IOException invalid = new IOException("Invalid bundled Realtek firmware");
        if (firmware.length < 22 || !Arrays.equals(Arrays.copyOf(firmware, 8), SIGNATURE)
                || !Arrays.equals(Arrays.copyOfRange(firmware, firmware.length - 4, firmware.length), EXTENSION)) {
            throw invalid;
        }
        int cursor = firmware.length - 4;
        Integer project = null;
        while (cursor >= 3) {
            int opcode = firmware[cursor - 1] & 0xff;
            if (opcode == 0xff) break;
            int length = firmware[cursor - 2] & 0xff;
            if (length == 0 || length + 2 > cursor) throw invalid;
            if (opcode == 0 && length == 1) {
                project = firmware[cursor - 3] & 0xff;
                break;
            }
            cursor -= length + 2;
        }
        if (project == null || project != expectedProject) throw invalid;
        int count = u16(firmware, 12);
        int tableEnd = 14 + 8 * count;
        if (count == 0 || tableEnd > firmware.length - 4) throw invalid;
        byte[] selected = null;
        for (int index = 0; index < count; index++) {
            if (u16(firmware, 14 + 2 * index) != rom + 1) continue;
            if (selected != null) throw invalid;
            int length = u16(firmware, 14 + 2 * count + 2 * index);
            int at = 14 + 4 * count + 4 * index;
            long offset = (firmware[at] & 0xffL) | ((firmware[at + 1] & 0xffL) << 8)
                    | ((firmware[at + 2] & 0xffL) << 16) | ((firmware[at + 3] & 0xffL) << 24);
            if (length < 4 || offset < tableEnd || offset + length > firmware.length - 4) throw invalid;
            selected = Arrays.copyOfRange(firmware, (int) offset, (int) offset + length);
            // The last four bytes carry the epatch firmware version.
            System.arraycopy(firmware, 8, selected, length - 4, 4);
        }
        if (selected == null) throw new IOException("Bundled Realtek firmware has no patch for ROM " + rom);
        if (config == null || config.length == 0) return selected;
        byte[] combined = Arrays.copyOf(selected, selected.length + config.length);
        System.arraycopy(config, 0, combined, selected.length, config.length);
        return combined;
    }

    private static void download(UsbBluetoothController controller, byte[] image) throws IOException {
        // The final fragment is required even for an exact multiple of 252 bytes.
        int fragments = image.length / 252 + 1;
        for (int index = 0; index < fragments; index++) {
            int sequence = index > 0x7f ? (index & 0x7f) + 1 : index;
            if (index + 1 == fragments) sequence |= 0x80;
            int start = index * 252;
            int end = Math.min(start + 252, image.length);
            byte[] parameters = new byte[1 + end - start];
            parameters[0] = (byte) sequence;
            System.arraycopy(image, start, parameters, 1, end - start);
            byte[] reply = controller.vendorCommand(OP_DOWNLOAD, parameters, 5_000);
            if (reply.length < 2 || reply[0] != 0 || reply[1] != parameters[0]) {
                throw new IOException("Realtek firmware fragment " + index + " was not acknowledged");
            }
        }
    }

    private static byte[] read(String name, boolean required) throws IOException {
        AssetManager manager = assets;
        if (manager == null) throw new IOException("Firmware assets are unavailable");
        try (InputStream input = manager.open("rtl_bt/" + name)) {
            byte[] bytes = input.readNBytes(MAX_FIRMWARE + 1);
            if (bytes.length > MAX_FIRMWARE) throw new IOException("Bundled firmware is oversized");
            return bytes;
        } catch (FileNotFoundException missing) {
            if (required) throw new IOException("Bundled firmware " + name + " is missing");
            return null;
        }
    }

    private static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }
}
