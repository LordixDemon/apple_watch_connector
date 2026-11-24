package dev.applewatchandroid.bridge;

import android.os.Process;
import android.system.Os;
import android.system.StructStat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;


/** Bluedroid {@code bt_config.conf} mutation, key-encoding upgrade, redaction, backup I/O, and shell helpers. */
final class RootBluetoothConfigCodec {
    private RootBluetoothConfigCodec() {}

    static void redactRotationFields(
            byte[] config)
            throws RootBluetoothBondImporter.ImportException {
        redactUniqueHexLine(
                config,
                RootBluetoothBondImporter.ADAPTER_IRK_PREFIX,
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

    static void redactUniqueHexLine(
            byte[] config,
            byte[] prefix,
            int valueLength,
            String label)
            throws RootBluetoothBondImporter.ImportException {
        try {
            int offset =
                    uniqueIndexOf(
                            config,
                            prefix);
            if (offset < 0
                    || (offset > 0
                    && config[offset - 1] != '\n')) {
                throw new RootBluetoothBondImporter.ImportException(
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
                throw new RootBluetoothBondImporter.ImportException(
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
            if (prefix != RootBluetoothBondImporter.ADAPTER_IRK_PREFIX) {
                wipe(prefix);
            }
        }
    }

    static void normalizeLegacySectionCase(
            byte[] current,
            byte[] uppercaseHeader,
            byte[] lowercaseHeader)
            throws Exception {
        if (!RootBluetoothBondImporter.BACKUP.isFile()
                || uppercaseHeader.length
                != lowercaseHeader.length) {
            throw new RootBluetoothBondImporter.ImportException(
                    "Legacy case normalization prerequisites "
                            + "are not satisfied");
        }
        int offset =
                uniqueIndexOf(
                        current,
                        uppercaseHeader);
        if (offset < 0) {
            throw new RootBluetoothBondImporter.ImportException(
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
                throw new RootBluetoothBondImporter.ImportException(
                        "Legacy case normalization change set "
                                + "is invalid");
            }
            replaceAndSync(corrected);
            byte[] verified =
                    Files.readAllBytes(
                            RootBluetoothBondImporter.CONFIG.toPath());
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
                    throw new RootBluetoothBondImporter.ImportException(
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
                throw new RootBluetoothBondImporter.ImportException(
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

    static boolean upgradeLegacyKeyEncodingIfRequired(
            byte[] current,
            byte[] record,
            byte[] targetHeader)
            throws Exception {
        if (!RootBluetoothBondImporter.BACKUP.isFile()) {
            throw new RootBluetoothBondImporter.ImportException(
                    "Key-format upgrade requires the protected "
                            + "pre-import backup");
        }
        int sectionStart =
                uniqueIndexOf(
                        current,
                        targetHeader);
        if (sectionStart < 0) {
            throw new RootBluetoothBondImporter.ImportException(
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
                throw new RootBluetoothBondImporter.ImportException(
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
                    Os.stat(RootBluetoothBondImporter.CONFIG.getAbsolutePath());
            writeProtectedVersionedBackup(
                    current,
                    metadata,
                    RootBluetoothBondImporter.ROTATION_BACKUP_PREFIX);
            writeStarted = true;
            replaceAndSync(corrected);
            verified =
                    Files.readAllBytes(
                            RootBluetoothBondImporter.CONFIG.toPath());
            if (!Arrays.equals(
                    corrected,
                    verified)) {
                throw new RootBluetoothBondImporter.ImportException(
                        "Canonical key-format post-write "
                                + "validation failed");
            }
            return true;
        } catch (Throwable error) {
            if (writeStarted) {
                try {
                    replaceAndSync(current);
                } catch (Throwable rollbackError) {
                    throw new RootBluetoothBondImporter.ImportException(
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

    static void requireOnlyAllowedDeviceSections(
            byte[] config,
            byte[] targetHeader)
            throws RootBluetoothBondImporter.ImportException {
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
                    throw new RootBluetoothBondImporter.ImportException(
                            "Another Bluetooth device section "
                                    + "exists; global IRK alignment "
                                    + "refused");
                }
            }
            lineStart = lineEnd;
        }
    }

    static boolean looksLikeAddressHeader(
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

    static int findLineEndExclusive(
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

    static boolean isHexRange(
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

    static boolean isHex(
            byte value) {
        return (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'f')
                || (value >= 'A' && value <= 'F');
    }

    static boolean equalsAsciiIgnoringHexCase(
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

    static int lowercaseAscii(
            byte value) {
        int unsigned = value & 0xff;
        if (unsigned >= 'A'
                && unsigned <= 'Z') {
            return unsigned + ('a' - 'A');
        }
        return unsigned;
    }

    static void writeProtectedIrkBackup(
            byte[] original,
            StructStat metadata)
            throws Exception {
        if (!RootBluetoothBondImporter.ADAPTER_IRK_BACKUP.createNewFile()) {
            throw new RootBluetoothBondImporter.ImportException(
                    "Cannot create unique pre-IRK backup");
        }
        boolean complete = false;
        try (FileOutputStream output =
                     new FileOutputStream(
                             RootBluetoothBondImporter.ADAPTER_IRK_BACKUP,
                             false)) {
            output.write(original);
            output.flush();
            output.getFD().sync();
            Os.chown(
                    RootBluetoothBondImporter.ADAPTER_IRK_BACKUP
                            .getAbsolutePath(),
                    metadata.st_uid,
                    metadata.st_gid);
            Os.chmod(
                    RootBluetoothBondImporter.ADAPTER_IRK_BACKUP
                            .getAbsolutePath(),
                    0600);
            complete = true;
        } finally {
            if (!complete) {
                try {
                    Os.chmod(
                            RootBluetoothBondImporter.ADAPTER_IRK_BACKUP
                                    .getAbsolutePath(),
                            0600);
                } catch (Throwable ignored) {
                    // Preserve the original error.
                }
            }
        }
    }

    static byte[] replaceRotatableKeyLine(
            byte[] input,
            byte[] canonical,
            byte[] acceptedLegacyLayout,
            int valueHexCharacters,
            int stableSuffixCharacters,
            String label)
            throws RootBluetoothBondImporter.ImportException {
        byte[] separator =
                StockBluetoothBondConfig
                        .ascii(" = ");
        int separatorOffset =
                indexOf(
                        canonical,
                        separator);
        wipe(separator);
        if (separatorOffset <= 0) {
            throw new RootBluetoothBondImporter.ImportException(
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
                throw new RootBluetoothBondImporter.ImportException(
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
                throw new RootBluetoothBondImporter.ImportException(
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
                throw new RootBluetoothBondImporter.ImportException(
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

    static boolean lineSuffixMatches(
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

    static int indexOf(
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

    static void writeProtectedVersionedBackup(
            byte[] original,
            StructStat metadata,
            String filenamePrefix)
            throws Exception {
        File directory =
                RootBluetoothBondImporter.CONFIG.getParentFile();
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
        throw new RootBluetoothBondImporter.ImportException(
                "Cannot create a unique protected rotation backup");
    }

    static byte[] replaceLegacyOrRequireCanonical(
            byte[] input,
            byte[] legacy,
            byte[] canonical,
            String label)
            throws RootBluetoothBondImporter.ImportException {
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
            throw new RootBluetoothBondImporter.ImportException(
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

    static boolean containsExactlyOnce(
            byte[] haystack,
            byte[] needle) {
        return StockBluetoothBondConfig.contains(
                haystack,
                needle)
                && uniqueIndexOf(
                        haystack,
                        needle) >= 0;
    }

    static int findNextSectionStart(
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

    static int uniqueIndexOf(
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

    static void writeNewBackup(
            byte[] original,
            StructStat metadata)
            throws Exception {
        if (!RootBluetoothBondImporter.BACKUP.createNewFile()) {
            throw new RootBluetoothBondImporter.ImportException(
                    "Cannot create unique pre-import backup");
        }
        boolean complete = false;
        try (FileOutputStream output =
                     new FileOutputStream(RootBluetoothBondImporter.BACKUP, false)) {
            output.write(original);
            output.flush();
            output.getFD().sync();
            Os.chown(
                    RootBluetoothBondImporter.BACKUP.getAbsolutePath(),
                    metadata.st_uid,
                    metadata.st_gid);
            Os.chmod(
                    RootBluetoothBondImporter.BACKUP.getAbsolutePath(),
                    0600);
            complete = true;
        } finally {
            if (!complete) {
                // An incomplete protected backup is intentionally retained as
                // evidence; a later run will fail closed on its presence.
                try {
                    Os.chmod(
                            RootBluetoothBondImporter.BACKUP.getAbsolutePath(),
                            0600);
                } catch (Throwable ignored) {
                    // Preserve the original error.
                }
            }
        }
    }

    static void appendAndSync(
            byte[] appendix)
            throws IOException {
        try (FileOutputStream output =
                     new FileOutputStream(RootBluetoothBondImporter.CONFIG, true)) {
            output.write(appendix);
            output.flush();
            output.getFD().sync();
        }
    }

    static void replaceAndSync(
            byte[] original)
            throws IOException {
        try (FileOutputStream output =
                     new FileOutputStream(RootBluetoothBondImporter.CONFIG, false)) {
            output.write(original);
            output.flush();
            output.getFD().sync();
        }
    }

    static boolean prefixMatches(
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

    static String runAndReadFirstLine(
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
            throw new RootBluetoothBondImporter.ImportException(
                    "Safety command failed with exit="
                            + status);
        }
        return line == null
                ? ""
                : line.trim();
    }

    static String runAndReadAll(
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
            throw new RootBluetoothBondImporter.ImportException(
                    "Safety command failed with exit="
                            + status);
        }
        return output.toString();
    }

    static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static void log(String message) {
        System.out.println(
                "[WatchBondImport] " + message);
        System.out.flush();
    }

    static String safeMessage(
            Throwable error) {
        String message = error.getMessage();
        return message == null
                || message.isBlank()
                ? "(no message)"
                : message;
    }
}
