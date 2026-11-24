package dev.applewatchandroid.bridge;

import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.system.StructStat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;

/**
 * One-shot root helper that adds only the already authenticated Watch LE bond
 * to the stock Android Bluetooth configuration.
 *
 * <p>It accepts the fixed-length binary bond record on stdin. No secret value
 * is accepted on argv or written to stdout/stderr.</p>
 */
public final class RootBluetoothBondImporter {
    static final String REQUIRED_ACK =
            "CPH2653";
    static final File CONFIG =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf");
    static final File BACKUP =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf."
                            + "apple-watch-preimport");
    static final File ADAPTER_IRK_BACKUP =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf."
                            + "apple-watch-preirk");
    static final String ROTATION_BACKUP_PREFIX =
            "bt_config.conf.apple-watch-prerotation-";
    static final String IRK_ROTATION_BACKUP_PREFIX =
            "bt_config.conf.apple-watch-preirk-rotation-";
    static final String ROLLBACK_BACKUP_PREFIX =
            "bt_config.conf.apple-watch-pre-rollback-";
    static final File COMMON_CRITERIA_KEYS =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf.encrypted");
    static final File COMMON_CRITERIA_CHECKSUM =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.checksum.encrypted");
    static final int MAX_CONFIG_BYTES =
            1024 * 1024;
    static final byte[] ADAPTER_SECTION =
            StockBluetoothBondConfig.ascii(
                    "[Adapter]");
    static final byte[] ADAPTER_IRK_PREFIX =
            StockBluetoothBondConfig.ascii(
                    "LE_LOCAL_KEY_IRK = ");
    static final byte[] ENCRYPTED_MARKER =
            StockBluetoothBondConfig.ascii(
                    "= encrypted");

    private RootBluetoothBondImporter() {
    }

    public static void main(String[] args) {
        byte[] record = null;
        int exitStatus = 0;
        try {
            validateInvocation(args);
            requireStockBluetoothOff();
            record = readExactRecord();
            StockBluetoothBondConfig.validateRecordForStockImport(record);
            if ("--restore-stock-prerotation"
                    .equals(args[2])) {
                restoreStockPreRotation(record);
                RootBluetoothConfigCodec.log("RESULT: PASS — the last protected pre-rotation "
                        + "Bluetooth config was restored after exact "
                        + "redacted-structure validation; BMW and OEM "
                        + "metadata retained; key bytes logged=false.");
                return;
            }
            if ("--audit-stock-bond"
                    .equals(args[2])) {
                auditBond(record);
                RootBluetoothConfigCodec.log("RESULT: PASS — stock bond audit completed "
                        + "without opening Bluetooth config for write; "
                        + "key bytes logged=false.");
            } else if ("--import-stock-bond"
                    .equals(args[2])) {
                importBond(record);
                RootBluetoothConfigCodec.log("RESULT: PASS — one additive Apple Watch LE bond "
                        + "section imported; original config prefix "
                        + "verified byte-for-byte; key bytes logged=false.");
            } else {
                alignAdapterIdentity(record);
                RootBluetoothConfigCodec.log("RESULT: PASS — stock adapter IRK now matches "
                        + "the identity distributed to this Watch; "
                        + "BMW and all unrelated config bytes "
                        + "unchanged; key bytes logged=false.");
            }
        } catch (Throwable error) {
            exitStatus = 1;
            RootBluetoothConfigCodec.log("RESULT: FAIL — "
                    + error.getClass().getSimpleName()
                    + ": "
                    + RootBluetoothConfigCodec.safeMessage(error));
        } finally {
            if (record != null) {
                Arrays.fill(record, (byte) 0);
            }
        }
        if (exitStatus != 0) {
            System.exit(exitStatus);
        }
    }

    private static void validateInvocation(
            String[] args)
            throws ImportException {
        if (args.length != 3
                || !"--acknowledge-target"
                        .equals(args[0])
                || !REQUIRED_ACK.equals(args[1])
                || (!"--import-stock-bond"
                        .equals(args[2])
                && !"--audit-stock-bond"
                        .equals(args[2])
                && !"--align-stock-identity"
                        .equals(args[2])
                && !"--restore-stock-prerotation"
                        .equals(args[2]))) {
            throw new ImportException(
                    "Exact stock-import target acknowledgement "
                            + "is missing");
        }
        if (Process.myUid() != 0) {
            throw new ImportException(
                    "Stock bond importer requires uid=0");
        }
        if (!"OnePlus".equalsIgnoreCase(
                Build.MANUFACTURER)
                || !"CPH2653".equalsIgnoreCase(
                Build.MODEL)) {
            throw new ImportException(
                    "Importer is fail-closed to OnePlus CPH2653");
        }
    }

    private static void auditBond(
            byte[] record)
            throws Exception {
        if (!CONFIG.isFile()
                || CONFIG.length() <= 0
                || CONFIG.length()
                > MAX_CONFIG_BYTES) {
            throw new ImportException(
                    "Stock Bluetooth config is missing or "
                            + "outside the safe size bound");
        }
        if (COMMON_CRITERIA_KEYS.exists()
                || COMMON_CRITERIA_CHECKSUM.exists()) {
            throw new ImportException(
                    "Common Criteria Bluetooth key storage is "
                            + "active; plaintext config audit refused");
        }

        byte[] original = null;
        byte[] targetHeader = null;
        byte[] uppercaseHeader = null;
        byte[] appendix = null;
        byte[] hypothetical = null;
        byte[] canonicalPenc = null;
        byte[] canonicalPid = null;
        byte[] canonicalLenc = null;
        byte[] canonicalLid = null;
        try {
            original =
                    Files.readAllBytes(
                            CONFIG.toPath());
            if (StockBluetoothBondConfig.contains(
                    original,
                    ENCRYPTED_MARKER)
                    || !StockBluetoothBondConfig.contains(
                            original,
                            ADAPTER_SECTION)) {
                throw new ImportException(
                        "Config encryption/topology preflight failed");
            }
            StructStat metadata =
                    Os.stat(
                            CONFIG.getAbsolutePath());
            if (metadata.st_size != original.length) {
                throw new ImportException(
                        "Bluetooth config changed during audit read");
            }

            targetHeader =
                    StockBluetoothBondConfig
                            .identitySectionHeader(
                                    record);
            if (StockBluetoothBondConfig.contains(
                    original,
                    targetHeader)) {
                canonicalPenc =
                        StockBluetoothBondConfig
                                .canonicalPencEntry(record);
                canonicalPid =
                        StockBluetoothBondConfig
                                .canonicalPidEntry(record);
                canonicalLenc =
                        StockBluetoothBondConfig
                                .canonicalLencEntry(record);
                canonicalLid =
                        StockBluetoothBondConfig
                                .canonicalLidEntry(record);
                boolean canonical =
                        RootBluetoothConfigCodec.containsExactlyOnce(
                                original,
                                canonicalPenc)
                                && RootBluetoothConfigCodec.containsExactlyOnce(
                                        original,
                                        canonicalPid)
                                && RootBluetoothConfigCodec.containsExactlyOnce(
                                        original,
                                        canonicalLenc)
                                && RootBluetoothConfigCodec.containsExactlyOnce(
                                        original,
                                        canonicalLid);
                RootBluetoothConfigCodec.log("STOCK BOND AUDIT: exact target section "
                        + "already exists; canonical-current-keys="
                        + canonical
                        + "; write-performed=false.");
                return;
            }

            uppercaseHeader =
                    new String(
                            targetHeader,
                            StandardCharsets.US_ASCII)
                            .toUpperCase(Locale.ROOT)
                            .getBytes(StandardCharsets.US_ASCII);
            if (StockBluetoothBondConfig.contains(
                    original,
                    uppercaseHeader)) {
                RootBluetoothConfigCodec.log("STOCK BOND AUDIT: target exists only with "
                        + "legacy uppercase section address; actual "
                        + "import would require guarded case "
                        + "normalization; write-performed=false.");
                return;
            }
            if (BACKUP.exists()) {
                throw new ImportException(
                        "Pre-import backup already exists while "
                                + "target section is absent; "
                                + "manual audit required");
            }

            appendix =
                    StockBluetoothBondConfig
                            .buildAppendix(
                                    record,
                                    Math.max(
                                            1,
                                            System.currentTimeMillis()
                                                    / 1000L));
            hypothetical =
                    new byte[
                            original.length
                                    + appendix.length];
            System.arraycopy(
                    original,
                    0,
                    hypothetical,
                    0,
                    original.length);
            System.arraycopy(
                    appendix,
                    0,
                    hypothetical,
                    original.length,
                    appendix.length);
            if (!RootBluetoothConfigCodec.prefixMatches(
                    hypothetical,
                    original)
                    || !StockBluetoothBondConfig.endsWith(
                            hypothetical,
                            appendix)
                    || !StockBluetoothBondConfig.contains(
                            hypothetical,
                            targetHeader)) {
                throw new ImportException(
                        "Hypothetical additive import validation failed");
            }
            RootBluetoothConfigCodec.log("STOCK BOND AUDIT: additive append is structurally "
                    + "valid; original-bytes="
                    + original.length
                    + ", proposed-total-bytes="
                    + hypothetical.length
                    + ", BMW-prefix-unchanged=true, "
                    + "write-performed=false.");
        } finally {
            RootBluetoothConfigCodec.wipe(original);
            RootBluetoothConfigCodec.wipe(targetHeader);
            RootBluetoothConfigCodec.wipe(uppercaseHeader);
            RootBluetoothConfigCodec.wipe(appendix);
            RootBluetoothConfigCodec.wipe(hypothetical);
            RootBluetoothConfigCodec.wipe(canonicalPenc);
            RootBluetoothConfigCodec.wipe(canonicalPid);
            RootBluetoothConfigCodec.wipe(canonicalLenc);
            RootBluetoothConfigCodec.wipe(canonicalLid);
        }
    }

    private static void requireStockBluetoothOff()
            throws Exception {
        String setting =
                RootBluetoothConfigCodec.runAndReadFirstLine(
                        "/system/bin/settings",
                        "get",
                        "global",
                        "bluetooth_on");
        if (!"0".equals(setting)) {
            throw new ImportException(
                    "Refusing config import: stock Bluetooth "
                            + "setting is not OFF");
        }
        String manager =
                RootBluetoothConfigCodec.runAndReadAll(
                        "/system/bin/dumpsys",
                        "bluetooth_manager");
        if (!manager.contains("state: OFF")) {
            throw new ImportException(
                    "Refusing config import: Bluetooth Manager "
                            + "has not reached OFF");
        }
    }

    private static byte[] readExactRecord()
            throws IOException, ImportException {
        byte[] record =
                System.in.readNBytes(
                        BluetoothBondSecretRecord
                                .SERIALIZED_LENGTH);
        if (record.length
                != BluetoothBondSecretRecord
                        .SERIALIZED_LENGTH
                || System.in.read() != -1) {
            Arrays.fill(record, (byte) 0);
            throw new ImportException(
                    "Importer stdin is not one exact bond record");
        }
        return record;
    }

    private static void importBond(
            byte[] record)
            throws Exception {
        if (!CONFIG.isFile()
                || CONFIG.length() <= 0
                || CONFIG.length()
                > MAX_CONFIG_BYTES) {
            throw new ImportException(
                    "Stock Bluetooth config is missing or "
                            + "outside the safe size bound");
        }
        if (COMMON_CRITERIA_KEYS.exists()
                || COMMON_CRITERIA_CHECKSUM.exists()) {
            throw new ImportException(
                    "Common Criteria Bluetooth key storage is "
                            + "active; plaintext config import refused");
        }

        byte[] original = null;
        byte[] appendix = null;
        byte[] targetHeader = null;
        byte[] legacyUppercaseHeader = null;
        byte[] after = null;
        boolean appendStarted = false;
        try {
            original = Files.readAllBytes(
                    CONFIG.toPath());
            if (StockBluetoothBondConfig.contains(
                    original,
                    ENCRYPTED_MARKER)) {
                throw new ImportException(
                        "Encrypted Bluetooth config marker found; "
                                + "import refused");
            }
            if (!StockBluetoothBondConfig.contains(
                    original,
                    ADAPTER_SECTION)) {
                throw new ImportException(
                        "Adapter section is absent; "
                                + "import refused");
            }
            targetHeader =
                    StockBluetoothBondConfig
                            .identitySectionHeader(
                                    record);
            if (StockBluetoothBondConfig.contains(
                    original,
                    targetHeader)) {
                if (RootBluetoothConfigCodec.upgradeLegacyKeyEncodingIfRequired(
                        original,
                        record,
                        targetHeader)) {
                    RootBluetoothConfigCodec.log("STOCK BOND IMPORT KEY REFRESH PASS: "
                            + "the existing Watch section now contains "
                            + "the current authenticated bond in AOSP "
                            + "structures; OEM metadata and unrelated "
                            + "config bytes unchanged; protected "
                            + "pre-refresh backup created; key bytes "
                            + "logged=false.");
                } else {
                    RootBluetoothConfigCodec.log("STOCK BOND IMPORT: target section already "
                            + "uses canonical AOSP key structures; "
                            + "no file write performed.");
                }
                return;
            }
            legacyUppercaseHeader =
                    new String(
                            targetHeader,
                            StandardCharsets.US_ASCII)
                            .toUpperCase(
                                    Locale.ROOT)
                            .getBytes(
                                    StandardCharsets.US_ASCII);
            if (StockBluetoothBondConfig.contains(
                    original,
                    legacyUppercaseHeader)) {
                RootBluetoothConfigCodec.normalizeLegacySectionCase(
                        original,
                        legacyUppercaseHeader,
                        targetHeader);
                RootBluetoothConfigCodec.log("STOCK BOND IMPORT CASE NORMALIZATION PASS: "
                        + "only the Watch section address was "
                        + "canonicalized to lowercase; "
                        + "key bytes logged=false.");
                return;
            }
            if (BACKUP.exists()) {
                throw new ImportException(
                        "Pre-import backup already exists while "
                                + "target section is absent; "
                                + "manual audit required");
            }

            StructStat metadata =
                    Os.stat(CONFIG.getAbsolutePath());
            RootBluetoothConfigCodec.writeNewBackup(
                    original,
                    metadata);

            appendix =
                    StockBluetoothBondConfig
                            .buildAppendix(
                                    record,
                                    System.currentTimeMillis()
                                            / 1000L);
            appendStarted = true;
            RootBluetoothConfigCodec.appendAndSync(appendix);

            after = Files.readAllBytes(
                    CONFIG.toPath());
            if (after.length
                    != original.length
                    + appendix.length
                    || !RootBluetoothConfigCodec.prefixMatches(
                            after,
                            original)
                    || !StockBluetoothBondConfig
                            .endsWith(
                                    after,
                                    appendix)
                    || !StockBluetoothBondConfig
                            .contains(
                                    after,
                                    targetHeader)) {
                throw new ImportException(
                        "Post-write byte validation failed");
            }
            RootBluetoothConfigCodec.log("STOCK BOND IMPORT PASS: protected pre-import "
                    + "backup created; BMW prefix unchanged; "
                    + "Watch PENC/PID/LENC/LID added; "
                    + "secret bytes logged=false.");
        } catch (Throwable error) {
            if (appendStarted
                    && original != null) {
                try {
                    RootBluetoothConfigCodec.replaceAndSync(original);
                    RootBluetoothConfigCodec.log("STOCK BOND IMPORT ROLLBACK PASS: "
                            + "original config restored.");
                } catch (Throwable rollbackError) {
                    throw new ImportException(
                            "Import failed and rollback failed: "
                                    + RootBluetoothConfigCodec.safeMessage(
                                            rollbackError),
                            error);
                }
            }
            throw error;
        } finally {
            RootBluetoothConfigCodec.wipe(original);
            RootBluetoothConfigCodec.wipe(appendix);
            RootBluetoothConfigCodec.wipe(targetHeader);
            RootBluetoothConfigCodec.wipe(legacyUppercaseHeader);
            RootBluetoothConfigCodec.wipe(after);
        }
    }

    private static void restoreStockPreRotation(byte[] record)
            throws Exception {
        byte[] targetHeader = StockBluetoothBondConfig.identitySectionHeader(record);
        File directory =
                CONFIG.getParentFile();
        File[] candidates =
                directory.listFiles(
                        (ignored, name) ->
                                name.startsWith(
                                        ROTATION_BACKUP_PREFIX));
        if (candidates == null
                || candidates.length != 1
                || !candidates[0].isFile()) {
            throw new ImportException(
                    "Exactly one protected pre-rotation backup "
                            + "is required");
        }
        File backup =
                candidates[0];
        if (!CONFIG.isFile()
                || CONFIG.length() <= 0
                || CONFIG.length() > MAX_CONFIG_BYTES
                || backup.length() <= 0
                || backup.length() > MAX_CONFIG_BYTES) {
            throw new ImportException(
                    "Current or backup Bluetooth config is "
                            + "outside the safe size bound");
        }

        byte[] current = null;
        byte[] previous = null;
        byte[] redactedCurrent = null;
        byte[] redactedPrevious = null;
        byte[] verified = null;
        boolean writeStarted = false;
        try {
            current =
                    Files.readAllBytes(
                            CONFIG.toPath());
            previous =
                    Files.readAllBytes(
                            backup.toPath());
            if (current.length != previous.length
                    || StockBluetoothBondConfig.contains(
                            current,
                            ENCRYPTED_MARKER)
                    || StockBluetoothBondConfig.contains(
                            previous,
                            ENCRYPTED_MARKER)
                    || !StockBluetoothBondConfig.contains(
                            current,
                            targetHeader)
                    || !StockBluetoothBondConfig.contains(
                            previous,
                            targetHeader)
                    || !StockBluetoothBondConfig.contains(
                            current,
                            ADAPTER_SECTION)
                    || !StockBluetoothBondConfig.contains(
                            previous,
                            ADAPTER_SECTION)) {
                throw new ImportException(
                        "Current/backup config topology does not "
                                + "match the guarded target");
            }
            RootBluetoothConfigCodec.requireOnlyAllowedDeviceSections(
                    current,
                    targetHeader);
            RootBluetoothConfigCodec.requireOnlyAllowedDeviceSections(
                    previous,
                    targetHeader);

            redactedCurrent =
                    current.clone();
            redactedPrevious =
                    previous.clone();
            RootBluetoothConfigCodec.redactRotationFields(
                    redactedCurrent);
            RootBluetoothConfigCodec.redactRotationFields(
                    redactedPrevious);
            if (!Arrays.equals(
                    redactedCurrent,
                    redactedPrevious)) {
                throw new ImportException(
                        "Configs differ outside the exact Watch "
                                + "bond and Adapter IRK fields; "
                                + "rollback refused");
            }

            StructStat metadata =
                    Os.stat(
                            CONFIG.getAbsolutePath());
            RootBluetoothConfigCodec.writeProtectedVersionedBackup(
                    current,
                    metadata,
                    ROLLBACK_BACKUP_PREFIX);
            writeStarted = true;
            RootBluetoothConfigCodec.replaceAndSync(previous);
            verified =
                    Files.readAllBytes(
                            CONFIG.toPath());
            if (!Arrays.equals(
                    previous,
                    verified)) {
                throw new ImportException(
                        "Pre-rotation rollback post-write "
                                + "validation failed");
            }
            RootBluetoothConfigCodec.log("STOCK BOND PRE-ROTATION RESTORE PASS: "
                    + "only guarded Watch bond/Adapter identity "
                    + "differences were rolled back; current-state "
                    + "backup retained; key bytes logged=false.");
        } catch (Throwable error) {
            if (writeStarted && current != null) {
                try {
                    RootBluetoothConfigCodec.replaceAndSync(current);
                } catch (Throwable rollbackError) {
                    throw new ImportException(
                            "Pre-rotation restore and recovery "
                                    + "both failed: "
                                    + RootBluetoothConfigCodec.safeMessage(
                                            rollbackError),
                            error);
                }
            }
            throw error;
        } finally {
            RootBluetoothConfigCodec.wipe(current);
            RootBluetoothConfigCodec.wipe(previous);
            RootBluetoothConfigCodec.wipe(redactedCurrent);
            RootBluetoothConfigCodec.wipe(redactedPrevious);
            RootBluetoothConfigCodec.wipe(verified);
        }
    }





    private static void alignAdapterIdentity(
            byte[] record)
            throws Exception {
        if (!CONFIG.isFile()
                || CONFIG.length() <= 0
                || CONFIG.length()
                > MAX_CONFIG_BYTES) {
            throw new ImportException(
                    "Stock Bluetooth config is missing or "
                            + "outside the safe size bound");
        }
        if (COMMON_CRITERIA_KEYS.exists()
                || COMMON_CRITERIA_CHECKSUM.exists()) {
            throw new ImportException(
                    "Common Criteria Bluetooth key storage is "
                            + "active; adapter IRK alignment refused");
        }

        byte[] current = null;
        byte[] targetHeader = null;
        byte[] canonicalIrkEntry = null;
        byte[] corrected = null;
        byte[] verified = null;
        byte[] existingBackup = null;
        boolean writeStarted = false;
        try {
            current =
                    Files.readAllBytes(
                            CONFIG.toPath());
            if (StockBluetoothBondConfig.contains(
                    current,
                    ENCRYPTED_MARKER)) {
                throw new ImportException(
                        "Encrypted Bluetooth config marker found; "
                                + "adapter IRK alignment refused");
            }
            targetHeader =
                    StockBluetoothBondConfig
                            .identitySectionHeader(
                                    record);
            if (!StockBluetoothBondConfig.contains(
                    current,
                    ADAPTER_SECTION)
                    || !StockBluetoothBondConfig.contains(
                            current,
                            targetHeader)
                    || !StockBluetoothBondConfig.contains(
                            current,
                            ADAPTER_SECTION)) {
                throw new ImportException(
                        "Required Adapter/Watch config "
                                + "sections are not all present");
            }
            RootBluetoothConfigCodec.requireOnlyAllowedDeviceSections(
                    current,
                    targetHeader);

            canonicalIrkEntry =
                    StockBluetoothBondConfig
                            .canonicalAdapterIrkEntry(
                                    record);
            int irkOffset =
                    RootBluetoothConfigCodec.uniqueIndexOf(
                            current,
                            ADAPTER_IRK_PREFIX);
            if (irkOffset < 0) {
                throw new ImportException(
                        "Adapter IRK entry is missing or duplicated");
            }
            int irkLineEnd =
                    RootBluetoothConfigCodec.findLineEndExclusive(
                            current,
                            irkOffset);
            if (irkLineEnd < 0
                    || irkLineEnd - irkOffset
                    != canonicalIrkEntry.length
                    || !RootBluetoothConfigCodec.isHexRange(
                            current,
                            irkOffset
                                    + ADAPTER_IRK_PREFIX.length,
                            32)) {
                throw new ImportException(
                        "Adapter IRK entry has an unknown format");
            }
            if (RootBluetoothConfigCodec.equalsAsciiIgnoringHexCase(
                    current,
                    irkOffset,
                    canonicalIrkEntry)) {
                RootBluetoothConfigCodec.log("STOCK ADAPTER IDENTITY: already aligned; "
                        + "no file write performed.");
                return;
            }

            StructStat metadata =
                    Os.stat(
                            CONFIG.getAbsolutePath());
            if (ADAPTER_IRK_BACKUP.exists()) {
                existingBackup =
                        Files.readAllBytes(
                                ADAPTER_IRK_BACKUP
                                        .toPath());
                if (!Arrays.equals(
                        existingBackup,
                        current)) {
                    RootBluetoothConfigCodec.writeProtectedVersionedBackup(
                            current,
                            metadata,
                            IRK_ROTATION_BACKUP_PREFIX);
                }
            } else {
                RootBluetoothConfigCodec.writeProtectedIrkBackup(
                        current,
                        metadata);
            }

            corrected = current.clone();
            System.arraycopy(
                    canonicalIrkEntry,
                    0,
                    corrected,
                    irkOffset,
                    canonicalIrkEntry.length);
            for (int index = 0;
                    index < current.length;
                    index++) {
                if (index >= irkOffset
                        && index
                        < irkOffset
                                + canonicalIrkEntry.length) {
                    continue;
                }
                if (current[index]
                        != corrected[index]) {
                    throw new ImportException(
                            "Adapter IRK alignment touched bytes "
                                    + "outside the exact IRK line");
                }
            }
            writeStarted = true;
            RootBluetoothConfigCodec.replaceAndSync(corrected);
            verified =
                    Files.readAllBytes(
                            CONFIG.toPath());
            if (!Arrays.equals(
                    corrected,
                    verified)) {
                throw new ImportException(
                        "Adapter IRK post-write validation failed");
            }
            RootBluetoothConfigCodec.log("STOCK ADAPTER IDENTITY ALIGNMENT PASS: "
                    + "only Adapter/LE_LOCAL_KEY_IRK changed; "
                    + "protected pre-IRK backup created; "
                    + "key bytes logged=false.");
        } catch (Throwable error) {
            if (writeStarted && current != null) {
                try {
                    RootBluetoothConfigCodec.replaceAndSync(current);
                    RootBluetoothConfigCodec.log("STOCK ADAPTER IDENTITY ROLLBACK PASS: "
                            + "pre-alignment config restored.");
                } catch (Throwable rollbackError) {
                    throw new ImportException(
                            "Adapter IRK alignment and rollback "
                                    + "failed: "
                                    + RootBluetoothConfigCodec.safeMessage(
                                            rollbackError),
                            error);
                }
            }
            throw error;
        } finally {
            RootBluetoothConfigCodec.wipe(current);
            RootBluetoothConfigCodec.wipe(targetHeader);
            RootBluetoothConfigCodec.wipe(canonicalIrkEntry);
            RootBluetoothConfigCodec.wipe(corrected);
            RootBluetoothConfigCodec.wipe(verified);
            RootBluetoothConfigCodec.wipe(existingBackup);
        }
    }


























    static final class ImportException
            extends Exception {
        ImportException(String message) {
            super(message);
        }

        ImportException(
                String message,
                Throwable cause) {
            super(message, cause);
        }
    }
}
