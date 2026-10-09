package dev.applewatchandroid.bridge;

import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.system.StructStat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private static final String REQUIRED_ACK =
            "CPH2653";
    private static final File CONFIG =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf");
    private static final File BACKUP =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf."
                            + "apple-watch-preimport");
    private static final File ADAPTER_IRK_BACKUP =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf."
                            + "apple-watch-preirk");
    private static final String ROTATION_BACKUP_PREFIX =
            "bt_config.conf.apple-watch-prerotation-";
    private static final String IRK_ROTATION_BACKUP_PREFIX =
            "bt_config.conf.apple-watch-preirk-rotation-";
    private static final String ROLLBACK_BACKUP_PREFIX =
            "bt_config.conf.apple-watch-pre-rollback-";
    private static final File COMMON_CRITERIA_KEYS =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.conf.encrypted");
    private static final File COMMON_CRITERIA_CHECKSUM =
            new File(
                    "/data/misc/bluedroid/"
                            + "bt_config.checksum.encrypted");
    private static final int MAX_CONFIG_BYTES =
            1024 * 1024;
    private static final byte[] ADAPTER_SECTION =
            StockBluetoothBondConfig.ascii(
                    "[Adapter]");
    private static final byte[] ADAPTER_IRK_PREFIX =
            StockBluetoothBondConfig.ascii(
                    "LE_LOCAL_KEY_IRK = ");
    private static final byte[] ENCRYPTED_MARKER =
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
                log("RESULT: PASS — the last protected pre-rotation "
                        + "Bluetooth config was restored after exact "
                        + "redacted-structure validation; BMW and OEM "
                        + "metadata retained; key bytes logged=false.");
                return;
            }
            if ("--audit-stock-bond"
                    .equals(args[2])) {
                auditBond(record);
                log("RESULT: PASS — stock bond audit completed "
                        + "without opening Bluetooth config for write; "
                        + "key bytes logged=false.");
            } else if ("--import-stock-bond"
                    .equals(args[2])) {
                importBond(record);
                log("RESULT: PASS — one additive Apple Watch LE bond "
                        + "section imported; original config prefix "
                        + "verified byte-for-byte; key bytes logged=false.");
            } else {
                alignAdapterIdentity(record);
                log("RESULT: PASS — stock adapter IRK now matches "
                        + "the identity distributed to this Watch; "
                        + "BMW and all unrelated config bytes "
                        + "unchanged; key bytes logged=false.");
            }
        } catch (Throwable error) {
            exitStatus = 1;
            log("RESULT: FAIL — "
                    + error.getClass().getSimpleName()
                    + ": "
                    + safeMessage(error));
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
                        containsExactlyOnce(
                                original,
                                canonicalPenc)
                                && containsExactlyOnce(
                                        original,
                                        canonicalPid)
                                && containsExactlyOnce(
                                        original,
                                        canonicalLenc)
                                && containsExactlyOnce(
                                        original,
                                        canonicalLid);
                log("STOCK BOND AUDIT: exact target section "
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
                log("STOCK BOND AUDIT: target exists only with "
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
            if (!prefixMatches(
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
            log("STOCK BOND AUDIT: additive append is structurally "
                    + "valid; original-bytes="
                    + original.length
                    + ", proposed-total-bytes="
                    + hypothetical.length
                    + ", BMW-prefix-unchanged=true, "
                    + "write-performed=false.");
        } finally {
            wipe(original);
            wipe(targetHeader);
            wipe(uppercaseHeader);
            wipe(appendix);
            wipe(hypothetical);
            wipe(canonicalPenc);
            wipe(canonicalPid);
            wipe(canonicalLenc);
            wipe(canonicalLid);
        }
    }

    private static void requireStockBluetoothOff()
            throws Exception {
        String setting =
                runAndReadFirstLine(
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
                runAndReadAll(
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
                if (upgradeLegacyKeyEncodingIfRequired(
                        original,
                        record,
                        targetHeader)) {
                    log("STOCK BOND IMPORT KEY REFRESH PASS: "
                            + "the existing Watch section now contains "
                            + "the current authenticated bond in AOSP "
                            + "structures; OEM metadata and unrelated "
                            + "config bytes unchanged; protected "
                            + "pre-refresh backup created; key bytes "
                            + "logged=false.");
                } else {
                    log("STOCK BOND IMPORT: target section already "
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
                normalizeLegacySectionCase(
                        original,
                        legacyUppercaseHeader,
                        targetHeader);
                log("STOCK BOND IMPORT CASE NORMALIZATION PASS: "
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
            writeNewBackup(
                    original,
                    metadata);

            appendix =
                    StockBluetoothBondConfig
                            .buildAppendix(
                                    record,
                                    System.currentTimeMillis()
                                            / 1000L);
            appendStarted = true;
            appendAndSync(appendix);

            after = Files.readAllBytes(
                    CONFIG.toPath());
            if (after.length
                    != original.length
                    + appendix.length
                    || !prefixMatches(
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
            log("STOCK BOND IMPORT PASS: protected pre-import "
                    + "backup created; BMW prefix unchanged; "
                    + "Watch PENC/PID/LENC/LID added; "
                    + "secret bytes logged=false.");
        } catch (Throwable error) {
            if (appendStarted
                    && original != null) {
                try {
                    replaceAndSync(original);
                    log("STOCK BOND IMPORT ROLLBACK PASS: "
                            + "original config restored.");
                } catch (Throwable rollbackError) {
                    throw new ImportException(
                            "Import failed and rollback failed: "
                                    + safeMessage(
                                            rollbackError),
                            error);
                }
            }
            throw error;
        } finally {
            wipe(original);
            wipe(appendix);
            wipe(targetHeader);
            wipe(legacyUppercaseHeader);
            wipe(after);
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
            requireOnlyAllowedDeviceSections(
                    current,
                    targetHeader);
            requireOnlyAllowedDeviceSections(
                    previous,
                    targetHeader);

            redactedCurrent =
                    current.clone();
            redactedPrevious =
                    previous.clone();
            redactRotationFields(
                    redactedCurrent);
            redactRotationFields(
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
            writeProtectedVersionedBackup(
                    current,
                    metadata,
                    ROLLBACK_BACKUP_PREFIX);
            writeStarted = true;
            replaceAndSync(previous);
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
            log("STOCK BOND PRE-ROTATION RESTORE PASS: "
                    + "only guarded Watch bond/Adapter identity "
                    + "differences were rolled back; current-state "
                    + "backup retained; key bytes logged=false.");
        } catch (Throwable error) {
            if (writeStarted && current != null) {
                try {
                    replaceAndSync(current);
                } catch (Throwable rollbackError) {
                    throw new ImportException(
                            "Pre-rotation restore and recovery "
                                    + "both failed: "
                                    + safeMessage(
                                            rollbackError),
                            error);
                }
            }
            throw error;
        } finally {
            wipe(current);
            wipe(previous);
            wipe(redactedCurrent);
            wipe(redactedPrevious);
            wipe(verified);
        }
    }

    private static void redactRotationFields(
            byte[] config)
            throws ImportException {
        redactUniqueHexLine(
                config,
                ADAPTER_IRK_PREFIX,
                32,
                "Adapter IRK");
        redactUniqueHexLine(
                config,
                StockBluetoothBondConfig
                        .ascii("LE_KEY_PENC = "),
                56,
                "Watch PENC");
        redactUniqueHexLine(
                config,
                StockBluetoothBondConfig
                        .ascii("LE_KEY_PID = "),
                46,
                "Watch PID");
        redactUniqueHexLine(
                config,
                StockBluetoothBondConfig
                        .ascii("LE_KEY_LENC = "),
                40,
                "Watch LENC");
        redactUniqueHexLine(
                config,
                StockBluetoothBondConfig
                        .ascii("LE_KEY_LID = "),
                46,
                "Watch LID");
    }

    private static void redactUniqueHexLine(
            byte[] config,
            byte[] prefix,
            int valueLength,
            String label)
            throws ImportException {
        try {
            int offset =
                    uniqueIndexOf(
                            config,
                            prefix);
            if (offset < 0
                    || (offset > 0
                    && config[offset - 1] != '\n')) {
                throw new ImportException(
                        label
                                + " line is missing or duplicated");
            }
            int lineEnd =
                    findLineEndExclusive(
                            config,
                            offset);
            if (lineEnd < 0
                    || lineEnd - offset
                    != prefix.length
                            + valueLength
                            + 1
                    || !isHexRange(
                            config,
                            offset + prefix.length,
                            valueLength)) {
                throw new ImportException(
                        label
                                + " line has an unknown format");
            }
            Arrays.fill(
                    config,
                    offset + prefix.length,
                    offset + prefix.length
                            + valueLength,
                    (byte) '0');
        } finally {
            if (prefix != ADAPTER_IRK_PREFIX) {
                wipe(prefix);
            }
        }
    }

    private static void normalizeLegacySectionCase(
            byte[] current,
            byte[] uppercaseHeader,
            byte[] lowercaseHeader)
            throws Exception {
        if (!BACKUP.isFile()
                || uppercaseHeader.length
                != lowercaseHeader.length) {
            throw new ImportException(
                    "Legacy case normalization prerequisites "
                            + "are not satisfied");
        }
        int offset =
                uniqueIndexOf(
                        current,
                        uppercaseHeader);
        if (offset < 0) {
            throw new ImportException(
                    "Legacy Watch section is missing or duplicated");
        }
        byte[] corrected = current.clone();
        try {
            System.arraycopy(
                    lowercaseHeader,
                    0,
                    corrected,
                    offset,
                    lowercaseHeader.length);
            int expectedDifferences = 0;
            int observedDifferences = 0;
            for (int index = 0;
                    index < current.length;
                    index++) {
                if (current[index]
                        != corrected[index]) {
                    observedDifferences++;
                }
            }
            for (int index = 0;
                    index < uppercaseHeader.length;
                    index++) {
                if (uppercaseHeader[index]
                        != lowercaseHeader[index]) {
                    expectedDifferences++;
                }
            }
            if (observedDifferences
                    != expectedDifferences
                    || expectedDifferences == 0) {
                throw new ImportException(
                        "Legacy case normalization change set "
                                + "is invalid");
            }
            replaceAndSync(corrected);
            byte[] verified =
                    Files.readAllBytes(
                            CONFIG.toPath());
            try {
                if (!Arrays.equals(
                        corrected,
                        verified)
                        || !StockBluetoothBondConfig
                                .contains(
                                        verified,
                                        lowercaseHeader)
                        || StockBluetoothBondConfig
                                .contains(
                                        verified,
                                        uppercaseHeader)) {
                    throw new ImportException(
                            "Legacy case normalization validation "
                                    + "failed");
                }
            } finally {
                wipe(verified);
            }
        } catch (Throwable error) {
            try {
                replaceAndSync(current);
            } catch (Throwable rollbackError) {
                throw new ImportException(
                        "Case normalization and rollback failed: "
                                + safeMessage(
                                        rollbackError),
                        error);
            }
            throw error;
        } finally {
            wipe(corrected);
        }
    }

    private static boolean upgradeLegacyKeyEncodingIfRequired(
            byte[] current,
            byte[] record,
            byte[] targetHeader)
            throws Exception {
        if (!BACKUP.isFile()) {
            throw new ImportException(
                    "Key-format upgrade requires the protected "
                            + "pre-import backup");
        }
        int sectionStart =
                uniqueIndexOf(
                        current,
                        targetHeader);
        if (sectionStart < 0) {
            throw new ImportException(
                    "Watch section is missing or duplicated");
        }
        int sectionEnd =
                findNextSectionStart(
                        current,
                        sectionStart
                                + targetHeader.length);
        if (sectionEnd < 0) {
            sectionEnd = current.length;
        }

        byte[] section = null;
        byte[] canonicalPenc = null;
        byte[] legacyPenc = null;
        byte[] canonicalPid = null;
        byte[] canonicalLenc = null;
        byte[] legacyLenc = null;
        byte[] canonicalLid = null;
        byte[] legacyLid = null;
        byte[] legacyLidBeforeRewrite = null;
        byte[] correctedSection = null;
        byte[] nextSection = null;
        byte[] corrected = null;
        byte[] verified = null;
        boolean writeStarted = false;
        try {
            section =
                    Arrays.copyOfRange(
                            current,
                            sectionStart,
                            sectionEnd);
            canonicalPenc =
                    StockBluetoothBondConfig
                            .canonicalPencEntry(
                                    record);
            legacyPenc =
                    StockBluetoothBondConfig
                            .legacyPencEntry(
                                    record);
            canonicalPid =
                    StockBluetoothBondConfig
                            .canonicalPidEntry(
                                    record);
            canonicalLenc =
                    StockBluetoothBondConfig
                            .canonicalLencEntry(
                                    record);
            legacyLenc =
                    StockBluetoothBondConfig
                            .legacyLencEntry(
                                    record);
            canonicalLid =
                    StockBluetoothBondConfig
                            .canonicalLidEntry(
                                    record);
            legacyLid =
                    StockBluetoothBondConfig
                            .legacyEmptyLidEntry();
            legacyLidBeforeRewrite =
                    StockBluetoothBondConfig
                            .legacyEmptyLidEntryBeforeConfigRewrite();

            boolean pencCanonical =
                    containsExactlyOnce(
                            section,
                            canonicalPenc);
            boolean pidCanonical =
                    containsExactlyOnce(
                            section,
                            canonicalPid);
            boolean lencCanonical =
                    containsExactlyOnce(
                            section,
                            canonicalLenc);
            boolean lidCanonical =
                    containsExactlyOnce(
                            section,
                            canonicalLid);
            if (pencCanonical
                    && pidCanonical
                    && lencCanonical
                    && lidCanonical) {
                return false;
            }

            correctedSection =
                    replaceRotatableKeyLine(
                            section,
                            canonicalPenc,
                            legacyPenc,
                            56,
                            24,
                            "PENC");
            nextSection =
                    replaceRotatableKeyLine(
                            correctedSection,
                            canonicalPid,
                            null,
                            46,
                            14,
                            "PID");
            wipe(correctedSection);
            correctedSection = nextSection;
            nextSection = null;
            nextSection =
                    replaceRotatableKeyLine(
                            correctedSection,
                            canonicalLenc,
                            legacyLenc,
                            40,
                            8,
                            "LENC");
            wipe(correctedSection);
            correctedSection = nextSection;
            nextSection = null;
            if (containsExactlyOnce(
                    correctedSection,
                    legacyLid)) {
                nextSection =
                        replaceLegacyOrRequireCanonical(
                                correctedSection,
                                legacyLid,
                                canonicalLid,
                                "LID");
            } else if (containsExactlyOnce(
                    correctedSection,
                    legacyLidBeforeRewrite)) {
                nextSection =
                        replaceLegacyOrRequireCanonical(
                                correctedSection,
                                legacyLidBeforeRewrite,
                                canonicalLid,
                                "LID");
            } else {
                nextSection =
                        replaceRotatableKeyLine(
                                correctedSection,
                                canonicalLid,
                                null,
                                46,
                                14,
                                "LID");
            }
            wipe(correctedSection);
            correctedSection = nextSection;
            nextSection = null;

            if (!containsExactlyOnce(
                    correctedSection,
                    canonicalPenc)
                    || !containsExactlyOnce(
                            correctedSection,
                            canonicalPid)
                    || !containsExactlyOnce(
                            correctedSection,
                            canonicalLenc)
                    || !containsExactlyOnce(
                            correctedSection,
                            canonicalLid)
                    || StockBluetoothBondConfig.contains(
                            correctedSection,
                            legacyPenc)
                    || StockBluetoothBondConfig.contains(
                            correctedSection,
                            legacyLenc)) {
                throw new ImportException(
                        "Canonical key-format validation failed "
                                + "before write");
            }

            corrected =
                    new byte[
                            sectionStart
                                    + correctedSection.length
                                    + current.length
                                    - sectionEnd];
            System.arraycopy(
                    current,
                    0,
                    corrected,
                    0,
                    sectionStart);
            System.arraycopy(
                    correctedSection,
                    0,
                    corrected,
                    sectionStart,
                    correctedSection.length);
            System.arraycopy(
                    current,
                    sectionEnd,
                    corrected,
                    sectionStart
                            + correctedSection.length,
                    current.length - sectionEnd);

            StructStat metadata =
                    Os.stat(CONFIG.getAbsolutePath());
            writeProtectedVersionedBackup(
                    current,
                    metadata,
                    ROTATION_BACKUP_PREFIX);
            writeStarted = true;
            replaceAndSync(corrected);
            verified =
                    Files.readAllBytes(
                            CONFIG.toPath());
            if (!Arrays.equals(
                    corrected,
                    verified)) {
                throw new ImportException(
                        "Canonical key-format post-write "
                                + "validation failed");
            }
            return true;
        } catch (Throwable error) {
            if (writeStarted) {
                try {
                    replaceAndSync(current);
                } catch (Throwable rollbackError) {
                    throw new ImportException(
                            "Key refresh and rollback failed: "
                                    + safeMessage(
                                            rollbackError),
                            error);
                }
            }
            throw error;
        } finally {
            wipe(section);
            wipe(canonicalPenc);
            wipe(legacyPenc);
            wipe(canonicalPid);
            wipe(canonicalLenc);
            wipe(legacyLenc);
            wipe(canonicalLid);
            wipe(legacyLid);
            wipe(legacyLidBeforeRewrite);
            wipe(correctedSection);
            wipe(nextSection);
            wipe(corrected);
            wipe(verified);
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
            requireOnlyAllowedDeviceSections(
                    current,
                    targetHeader);

            canonicalIrkEntry =
                    StockBluetoothBondConfig
                            .canonicalAdapterIrkEntry(
                                    record);
            int irkOffset =
                    uniqueIndexOf(
                            current,
                            ADAPTER_IRK_PREFIX);
            if (irkOffset < 0) {
                throw new ImportException(
                        "Adapter IRK entry is missing or duplicated");
            }
            int irkLineEnd =
                    findLineEndExclusive(
                            current,
                            irkOffset);
            if (irkLineEnd < 0
                    || irkLineEnd - irkOffset
                    != canonicalIrkEntry.length
                    || !isHexRange(
                            current,
                            irkOffset
                                    + ADAPTER_IRK_PREFIX.length,
                            32)) {
                throw new ImportException(
                        "Adapter IRK entry has an unknown format");
            }
            if (equalsAsciiIgnoringHexCase(
                    current,
                    irkOffset,
                    canonicalIrkEntry)) {
                log("STOCK ADAPTER IDENTITY: already aligned; "
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
                    writeProtectedVersionedBackup(
                            current,
                            metadata,
                            IRK_ROTATION_BACKUP_PREFIX);
                }
            } else {
                writeProtectedIrkBackup(
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
            replaceAndSync(corrected);
            verified =
                    Files.readAllBytes(
                            CONFIG.toPath());
            if (!Arrays.equals(
                    corrected,
                    verified)) {
                throw new ImportException(
                        "Adapter IRK post-write validation failed");
            }
            log("STOCK ADAPTER IDENTITY ALIGNMENT PASS: "
                    + "only Adapter/LE_LOCAL_KEY_IRK changed; "
                    + "protected pre-IRK backup created; "
                    + "key bytes logged=false.");
        } catch (Throwable error) {
            if (writeStarted && current != null) {
                try {
                    replaceAndSync(current);
                    log("STOCK ADAPTER IDENTITY ROLLBACK PASS: "
                            + "pre-alignment config restored.");
                } catch (Throwable rollbackError) {
                    throw new ImportException(
                            "Adapter IRK alignment and rollback "
                                    + "failed: "
                                    + safeMessage(
                                            rollbackError),
                            error);
                }
            }
            throw error;
        } finally {
            wipe(current);
            wipe(targetHeader);
            wipe(canonicalIrkEntry);
            wipe(corrected);
            wipe(verified);
            wipe(existingBackup);
        }
    }

    private static void requireOnlyAllowedDeviceSections(
            byte[] config,
            byte[] targetHeader)
            throws ImportException {
        int lineStart = 0;
        while (lineStart < config.length) {
            int lineEnd =
                    findLineEndExclusive(
                            config,
                            lineStart);
            if (lineEnd < 0) {
                lineEnd = config.length;
            }
            int contentLength =
                    lineEnd - lineStart;
            if (contentLength > 0
                    && config[lineEnd - 1] == '\n') {
                contentLength--;
            }
            if (contentLength == 19
                    && config[lineStart] == '['
                    && config[lineStart + 18] == ']'
                    && looksLikeAddressHeader(
                            config,
                            lineStart)) {
                boolean allowed = equalsAsciiIgnoringHexCase(
                                        config,
                                        lineStart,
                                        targetHeader);
                if (!allowed) {
                    throw new ImportException(
                            "Another Bluetooth device section "
                                    + "exists; global IRK alignment "
                                    + "refused");
                }
            }
            lineStart = lineEnd;
        }
    }

    private static boolean looksLikeAddressHeader(
            byte[] value,
            int offset) {
        for (int index = 1;
                index < 18;
                index++) {
            if (index == 3
                    || index == 6
                    || index == 9
                    || index == 12
                    || index == 15) {
                if (value[offset + index] != ':') {
                    return false;
                }
            } else if (!isHex(
                    value[offset + index])) {
                return false;
            }
        }
        return true;
    }

    private static int findLineEndExclusive(
            byte[] value,
            int from) {
        for (int index = from;
                index < value.length;
                index++) {
            if (value[index] == '\n') {
                return index + 1;
            }
        }
        return -1;
    }

    private static boolean isHexRange(
            byte[] value,
            int offset,
            int length) {
        if (offset < 0
                || length < 0
                || offset + length > value.length) {
            return false;
        }
        for (int index = 0;
                index < length;
                index++) {
            if (!isHex(value[offset + index])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(
            byte value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    private static boolean equalsAsciiIgnoringHexCase(
            byte[] complete,
            int offset,
            byte[] expected) {
        if (offset < 0
                || offset + expected.length
                > complete.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0;
                index < expected.length;
                index++) {
            difference |= lowercaseAscii(
                    complete[offset + index])
                    ^ lowercaseAscii(
                            expected[index]);
        }
        return difference == 0;
    }

    private static int lowercaseAscii(
            byte value) {
        int unsigned = value & 0xff;
        if (unsigned >= 'A'
                && unsigned <= 'Z') {
            return unsigned + ('a' - 'A');
        }
        return unsigned;
    }

    private static void writeProtectedIrkBackup(
            byte[] original,
            StructStat metadata)
            throws Exception {
        if (!ADAPTER_IRK_BACKUP.createNewFile()) {
            throw new ImportException(
                    "Cannot create unique pre-IRK backup");
        }
        boolean complete = false;
        try (FileOutputStream output =
                     new FileOutputStream(
                             ADAPTER_IRK_BACKUP,
                             false)) {
            output.write(original);
            output.flush();
            output.getFD().sync();
            Os.chown(
                    ADAPTER_IRK_BACKUP
                            .getAbsolutePath(),
                    metadata.st_uid,
                    metadata.st_gid);
            Os.chmod(
                    ADAPTER_IRK_BACKUP
                            .getAbsolutePath(),
                    0600);
            complete = true;
        } finally {
            if (!complete) {
                try {
                    Os.chmod(
                            ADAPTER_IRK_BACKUP
                                    .getAbsolutePath(),
                            0600);
                } catch (Throwable ignored) {
                    // Preserve the original error.
                }
            }
        }
    }

    private static byte[] replaceRotatableKeyLine(
            byte[] input,
            byte[] canonical,
            byte[] acceptedLegacyLayout,
            int valueHexCharacters,
            int stableSuffixCharacters,
            String label)
            throws ImportException {
        byte[] separator =
                StockBluetoothBondConfig
                        .ascii(" = ");
        int separatorOffset =
                indexOf(
                        canonical,
                        separator);
        wipe(separator);
        if (separatorOffset <= 0) {
            throw new ImportException(
                    "Internal "
                            + label
                            + " line prefix is invalid");
        }
        int prefixLength =
                separatorOffset + 3;
        byte[] prefix =
                Arrays.copyOfRange(
                        canonical,
                        0,
                        prefixLength);
        try {
            int offset =
                    uniqueIndexOf(
                            input,
                            prefix);
            if (offset < 0
                    || (offset > 0
                    && input[offset - 1] != '\n')) {
                throw new ImportException(
                        "Watch "
                                + label
                                + " entry is absent or duplicated");
            }
            int lineEnd =
                    findLineEndExclusive(
                            input,
                            offset);
            if (lineEnd < 0
                    || lineEnd - offset
                    != canonical.length
                    || canonical.length
                    != prefixLength
                            + valueHexCharacters
                            + 1
                    || !isHexRange(
                            input,
                            offset + prefixLength,
                            valueHexCharacters)) {
                throw new ImportException(
                        "Watch "
                                + label
                                + " entry has an unknown format");
            }
            if (stableSuffixCharacters > 0
                    && !lineSuffixMatches(
                            input,
                            offset,
                            canonical,
                            stableSuffixCharacters)
                    && (acceptedLegacyLayout == null
                    || !lineSuffixMatches(
                            input,
                            offset,
                            acceptedLegacyLayout,
                            stableSuffixCharacters))) {
                throw new ImportException(
                        "Watch "
                                + label
                                + " identity/layout suffix changed; "
                                + "key rotation refused");
            }
            byte[] output =
                    input.clone();
            System.arraycopy(
                    canonical,
                    0,
                    output,
                    offset,
                    canonical.length);
            return output;
        } finally {
            wipe(prefix);
        }
    }

    private static boolean lineSuffixMatches(
            byte[] input,
            int inputOffset,
            byte[] expectedLine,
            int suffixCharacters) {
        int inputStart =
                inputOffset
                        + expectedLine.length
                        - 1
                        - suffixCharacters;
        int expectedStart =
                expectedLine.length
                        - 1
                        - suffixCharacters;
        for (int index = 0;
                index < suffixCharacters;
                index++) {
            if (lowercaseAscii(
                    input[inputStart + index])
                    != lowercaseAscii(
                            expectedLine[
                                    expectedStart + index])) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(
            byte[] haystack,
            byte[] needle) {
        if (haystack == null
                || needle == null
                || needle.length == 0
                || needle.length > haystack.length) {
            return -1;
        }
        outer:
        for (int offset = 0;
                offset <= haystack.length - needle.length;
                offset++) {
            for (int index = 0;
                    index < needle.length;
                    index++) {
                if (haystack[offset + index]
                        != needle[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    private static void writeProtectedVersionedBackup(
            byte[] original,
            StructStat metadata,
            String filenamePrefix)
            throws Exception {
        File directory =
                CONFIG.getParentFile();
        long timestamp =
                System.currentTimeMillis();
        for (int attempt = 0;
                attempt < 16;
                attempt++) {
            File backup =
                    new File(
                            directory,
                            filenamePrefix
                                    + timestamp
                                    + "-"
                                    + attempt);
            if (!backup.createNewFile()) {
                continue;
            }
            boolean complete = false;
            try (FileOutputStream output =
                         new FileOutputStream(
                                 backup,
                                 false)) {
                output.write(original);
                output.flush();
                output.getFD().sync();
                Os.chown(
                        backup.getAbsolutePath(),
                        metadata.st_uid,
                        metadata.st_gid);
                Os.chmod(
                        backup.getAbsolutePath(),
                        0600);
                complete = true;
                return;
            } finally {
                if (!complete) {
                    try {
                        Os.chmod(
                                backup.getAbsolutePath(),
                                0600);
                    } catch (Throwable ignored) {
                        // Preserve the original error.
                    }
                }
            }
        }
        throw new ImportException(
                "Cannot create a unique protected rotation backup");
    }

    private static byte[] replaceLegacyOrRequireCanonical(
            byte[] input,
            byte[] legacy,
            byte[] canonical,
            String label)
            throws ImportException {
        boolean hasLegacy =
                containsExactlyOnce(
                        input,
                        legacy);
        boolean hasCanonical =
                containsExactlyOnce(
                        input,
                        canonical);
        if (hasCanonical && !hasLegacy) {
            return input.clone();
        }
        if (!hasLegacy || hasCanonical) {
            throw new ImportException(
                    "Watch "
                            + label
                            + " entry is absent, duplicated, or "
                            + "has an unknown format");
        }
        int offset =
                uniqueIndexOf(
                        input,
                        legacy);
        byte[] output =
                new byte[
                        input.length
                                - legacy.length
                                + canonical.length];
        System.arraycopy(
                input,
                0,
                output,
                0,
                offset);
        System.arraycopy(
                canonical,
                0,
                output,
                offset,
                canonical.length);
        System.arraycopy(
                input,
                offset + legacy.length,
                output,
                offset + canonical.length,
                input.length
                        - offset
                        - legacy.length);
        return output;
    }

    private static boolean containsExactlyOnce(
            byte[] haystack,
            byte[] needle) {
        return StockBluetoothBondConfig.contains(
                haystack,
                needle)
                && uniqueIndexOf(
                        haystack,
                        needle) >= 0;
    }

    private static int findNextSectionStart(
            byte[] config,
            int from) {
        for (int index = Math.max(1, from);
                index < config.length - 1;
                index++) {
            if (config[index - 1] == '\n'
                    && config[index] == '[') {
                return index;
            }
        }
        return -1;
    }

    private static int uniqueIndexOf(
            byte[] haystack,
            byte[] needle) {
        int found = -1;
        outer:
        for (int offset = 0;
                offset <= haystack.length - needle.length;
                offset++) {
            for (int index = 0;
                    index < needle.length;
                    index++) {
                if (haystack[offset + index]
                        != needle[index]) {
                    continue outer;
                }
            }
            if (found != -1) {
                return -1;
            }
            found = offset;
        }
        return found;
    }

    private static void writeNewBackup(
            byte[] original,
            StructStat metadata)
            throws Exception {
        if (!BACKUP.createNewFile()) {
            throw new ImportException(
                    "Cannot create unique pre-import backup");
        }
        boolean complete = false;
        try (FileOutputStream output =
                     new FileOutputStream(BACKUP, false)) {
            output.write(original);
            output.flush();
            output.getFD().sync();
            Os.chown(
                    BACKUP.getAbsolutePath(),
                    metadata.st_uid,
                    metadata.st_gid);
            Os.chmod(
                    BACKUP.getAbsolutePath(),
                    0600);
            complete = true;
        } finally {
            if (!complete) {
                // An incomplete protected backup is intentionally retained as
                // evidence; a later run will fail closed on its presence.
                try {
                    Os.chmod(
                            BACKUP.getAbsolutePath(),
                            0600);
                } catch (Throwable ignored) {
                    // Preserve the original error.
                }
            }
        }
    }

    private static void appendAndSync(
            byte[] appendix)
            throws IOException {
        try (FileOutputStream output =
                     new FileOutputStream(CONFIG, true)) {
            output.write(appendix);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void replaceAndSync(
            byte[] original)
            throws IOException {
        try (FileOutputStream output =
                     new FileOutputStream(CONFIG, false)) {
            output.write(original);
            output.flush();
            output.getFD().sync();
        }
    }

    private static boolean prefixMatches(
            byte[] complete,
            byte[] prefix) {
        if (complete.length < prefix.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0;
                index < prefix.length;
                index++) {
            difference |= complete[index]
                    ^ prefix[index];
        }
        return difference == 0;
    }

    private static String runAndReadFirstLine(
            String... command)
            throws Exception {
        java.lang.Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();
        String line;
        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     process.getInputStream(),
                                     StandardCharsets.UTF_8))) {
            line = reader.readLine();
        }
        int status = process.waitFor();
        if (status != 0) {
            throw new ImportException(
                    "Safety command failed with exit="
                            + status);
        }
        return line == null
                ? ""
                : line.trim();
    }

    private static String runAndReadAll(
            String... command)
            throws Exception {
        java.lang.Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .start();
        StringBuilder output =
                new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     process.getInputStream(),
                                     StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        int status = process.waitFor();
        if (status != 0) {
            throw new ImportException(
                    "Safety command failed with exit="
                            + status);
        }
        return output.toString();
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static void log(String message) {
        System.out.println(
                "[WatchBondImport] " + message);
        System.out.flush();
    }

    private static String safeMessage(
            Throwable error) {
        String message = error.getMessage();
        return message == null
                || message.isBlank()
                ? "(no message)"
                : message;
    }

    private static final class ImportException
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
