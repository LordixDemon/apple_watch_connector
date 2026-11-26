package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [EXPERIMENTAL] Codecs for Apple's legacy Watch pairing protocol
 * ({@code NRExtensiblePair}, strategies 1-3, pre-NetworkRelay watchOS 1-9).
 *
 * <p>Constants and framing layouts were derived statically from iOS 26.6
 * ({@code 23G71}) {@code nanoregistryd}, {@code NanoRegistry}, {@code terminusd},
 * and {@code identityservicesd}, and have not been verified against physical
 * legacy Apple Watch hardware. Secret-bearing objects intentionally expose no
 * textual {@code toString()} formatting.</p>
 */
public final class LegacyWatchPairingCodec {
    public static final int STRATEGY_UNKNOWN = 0;
    /** watchOS 1-3: simultaneous BT SMP + IDS over com.apple.ids. */
    public static final int STRATEGY_SIMULTANEOUS = 1;
    /** watchOS 3-5: GATT "NANO" write -> BT SMP -> IDSBTLink over com.apple.ids. */
    public static final int STRATEGY_SEQUENTIAL = 2;
    /** watchOS 6-9: GATT "NANO" write -> BT SMP -> Direct IPSec (terminusLink) or IDSBTLink fallback. */
    public static final int STRATEGY_SEQUENTIAL_DIRECT_IPSEC = 3;

    public static final int STYLE_OOB = 1;
    public static final int STYLE_PASSCODE = 2;

    static final String EXTENSIBLE_PAIR_SERVICE_UUID = "9AA4730F-B25C-4CC3-B821-C931559FC196";
    static final String EXTENSIBLE_PAIR_CONTROL_CHAR_UUID = "5F6C6A23-8AC8-400E-810B-017134943460";

    /** Little-endian BLE wire bytes for 9AA4730F-B25C-4CC3-B821-C931559FC196. */
    static final byte[] EXTENSIBLE_PAIR_SERVICE_UUID_LE = new byte[]{
            (byte) 0x96, (byte) 0xC1, (byte) 0x9F, (byte) 0x55,
            (byte) 0x31, (byte) 0xC9, (byte) 0x21, (byte) 0xB8,
            (byte) 0xC3, (byte) 0x4C, (byte) 0x5C, (byte) 0xB2,
            (byte) 0x0F, (byte) 0x73, (byte) 0xA4, (byte) 0x9A
    };

    /** Little-endian BLE wire bytes for 5F6C6A23-8AC8-400E-810B-017134943460. */
    static final byte[] EXTENSIBLE_PAIR_CONTROL_CHAR_UUID_LE = new byte[]{
            (byte) 0x60, (byte) 0x34, (byte) 0x94, (byte) 0x34,
            (byte) 0x71, (byte) 0x01, (byte) 0x0B, (byte) 0x81,
            (byte) 0x0E, (byte) 0x40, (byte) 0xC8, (byte) 0x8A,
            (byte) 0x23, (byte) 0x6A, (byte) 0x6C, (byte) 0x5F
    };

    static final int ATT_FIXED_CID = 0x0004;
    static final int ATT_OPCODE_ERROR_RSP = 0x01;
    static final int ATT_OPCODE_EXCHANGE_MTU_REQ = 0x02;
    static final int ATT_OPCODE_EXCHANGE_MTU_RSP = 0x03;
    static final int ATT_OPCODE_FIND_BY_TYPE_VALUE_REQ = 0x06;
    static final int ATT_OPCODE_FIND_BY_TYPE_VALUE_RSP = 0x07;
    static final int ATT_OPCODE_READ_BY_TYPE_REQ = 0x08;
    static final int ATT_OPCODE_READ_BY_TYPE_RSP = 0x09;
    static final int ATT_OPCODE_READ_BY_GROUP_TYPE_REQ = 0x10;
    static final int ATT_OPCODE_READ_BY_GROUP_TYPE_RSP = 0x11;
    static final int ATT_OPCODE_WRITE_REQ = 0x12;
    static final int ATT_OPCODE_WRITE_RSP = 0x13;

    static final int ATT_ERR_REQUEST_NOT_SUPPORTED = 0x06;
    static final int ATT_ERR_ATTRIBUTE_NOT_FOUND = 0x0A;
    static final int ATT_ERR_UNSUPPORTED_GROUP_TYPE = 0x10;

    static final String TERMINUS_OOBK_KEY_ID = "com.apple.networkrelay.companionlink.pairing.oobk";
    static final int LEGACY_NOTIFY_CLASS_D_KEYS = 0xC4EB;
    static final int LEGACY_NOTIFY_CLASS_C_KEYS = 0xC4EC;
    static final int LEGACY_NOTIFY_CLASS_A_KEYS = 0xC4ED;
    static final int LEGACY_NOTIFY_IDS_KEYS = 0xC4E1;
    static final int LEGACY_NOTIFY_IDENTITY = 0xC4AF;

    static final int IDS_BT_LINK_MAX_PACKET_LENGTH = 0x7FFF;

    private static final String HUMAN_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890";
    private static final String APPLE_WATCH_PREFIX = "Apple Watch ";
    private static final byte[] CONTROL_MAGIC = "NANO".getBytes(StandardCharsets.US_ASCII);

    private LegacyWatchPairingCodec() {
    }

    static boolean isSupportedLegacyStrategy(int strategy) {
        return strategy >= STRATEGY_SIMULTANEOUS && strategy <= STRATEGY_SEQUENTIAL_DIRECT_IPSEC;
    }

    /**
     * Parses legacy Watch setup advertisements ({@code 9AA4730F} service UUID, 8-char human
     * readable name with strategy B/C/D, {@code "Apple Watch NNNNN"} manual name, and/or
     * {@code 0xFE25} subtype {@code 0x06} with strategy 1-3). Modern Strategy 4 advertisements
     * are never matched as legacy.
     */
    static LegacyAdvertisement parseAdvertisement(
            byte[] advertisingData,
            HciCodec.WatchSetupPayload setup) {
        if (setup != null
                && setup.decodedIdentifier != null
                && setup.decodedIdentifier.pairingStrategy
                == WatchSetupMetadataCodec.NETWORK_RELAY_PAIRING_STRATEGY) {
            return null;
        }
        boolean hasExtensiblePairUuid = false;
        String localName = null;
        if (advertisingData != null) {
            int offset = 0;
            while (offset < advertisingData.length) {
                int fieldLength = advertisingData[offset] & 0xff;
                if (fieldLength == 0) {
                    break;
                }
                int fieldEnd = offset + 1 + fieldLength;
                if (fieldEnd > advertisingData.length) {
                    break;
                }
                int adType = advertisingData[offset + 1] & 0xff;
                int valueStart = offset + 2;
                int valueLength = fieldEnd - valueStart;
                if ((adType == 0x06 || adType == 0x07) && valueLength >= 16) {
                    for (int pos = valueStart; pos + 16 <= fieldEnd; pos += 16) {
                        if (matchesSlice(advertisingData, pos, EXTENSIBLE_PAIR_SERVICE_UUID_LE)) {
                            hasExtensiblePairUuid = true;
                            break;
                        }
                    }
                } else if ((adType == 0x08 || adType == 0x09) && valueLength > 0 && localName == null) {
                    localName = decodePrintableAscii(advertisingData, valueStart, valueLength);
                }
                offset = fieldEnd;
            }
        }

        int strategy = STRATEGY_UNKNOWN;
        int advertisingIdentifier = -1;
        int enclosureMaterial = -1;
        int deviceSize = -1;
        boolean manualNameMatch = false;
        String productType = null;
        String systemVersion = null;
        int pairingVersion = -1;

        if (setup != null && setup.decodedIdentifier != null
                && isSupportedLegacyStrategy(setup.decodedIdentifier.pairingStrategy)) {
            strategy = setup.decodedIdentifier.pairingStrategy;
            advertisingIdentifier = setup.decodedIdentifier.advertisingIdentifier;
            deviceSize = setup.decodedIdentifier.deviceSize;
            enclosureMaterial = setup.decodedIdentifier.enclosureMaterial;
            if (setup.decodedMetadata != null) {
                productType = setup.decodedMetadata.productType();
                systemVersion = setup.decodedMetadata.systemVersionString();
                pairingVersion = setup.decodedMetadata.pairingVersion;
            }
        }

        if (localName != null) {
            if (localName.length() == 8 && localName.matches("[0-9]{5}[A-Za-z0-9]{3}")) {
                int nameStrategy = HUMAN_ALPHABET.indexOf(localName.charAt(5));
                if (nameStrategy == WatchSetupMetadataCodec.NETWORK_RELAY_PAIRING_STRATEGY) {
                    return null;
                }
                if (isSupportedLegacyStrategy(nameStrategy)) {
                    if (strategy == STRATEGY_UNKNOWN) {
                        strategy = nameStrategy;
                    }
                    if (advertisingIdentifier < 0) {
                        advertisingIdentifier = Integer.parseInt(localName.substring(0, 5));
                    }
                    if (enclosureMaterial < 0) {
                        enclosureMaterial = HUMAN_ALPHABET.indexOf(localName.charAt(6));
                    }
                    if (deviceSize < 0) {
                        deviceSize = HUMAN_ALPHABET.indexOf(localName.charAt(7));
                    }
                }
            } else if (localName.startsWith(APPLE_WATCH_PREFIX)
                    && localName.length() == APPLE_WATCH_PREFIX.length() + 5
                    && localName.substring(APPLE_WATCH_PREFIX.length()).matches("[0-9]{5}")) {
                manualNameMatch = true;
                if (advertisingIdentifier < 0) {
                    advertisingIdentifier = Integer.parseInt(localName.substring(APPLE_WATCH_PREFIX.length()));
                }
            }
        }

        if (!hasExtensiblePairUuid && !isSupportedLegacyStrategy(strategy) && !manualNameMatch) {
            return null;
        }
        return new LegacyAdvertisement(
                hasExtensiblePairUuid,
                strategy,
                advertisingIdentifier,
                enclosureMaterial,
                deviceSize,
                manualNameMatch,
                localName,
                productType,
                systemVersion,
                pairingVersion);
    }

    /** Encodes the 6-byte {@code NRExtensiblePair} GATT control write payload: {@code "NANO" + strategy + style}. */
    public static byte[] encodeControlPayload(int strategy, int style) {
        if (!isSupportedLegacyStrategy(strategy)) {
            throw new IllegalArgumentException("Unsupported legacy pairing strategy: " + strategy);
        }
        if (style != STYLE_OOB && style != STYLE_PASSCODE) {
            throw new IllegalArgumentException("Unsupported legacy pairing style: " + style);
        }
        return new byte[]{
                CONTROL_MAGIC[0],
                CONTROL_MAGIC[1],
                CONTROL_MAGIC[2],
                CONTROL_MAGIC[3],
                (byte) strategy,
                (byte) style
        };
    }

    /** Decodes the 6-byte {@code NRExtensiblePair} GATT control payload: {@code "NANO" + strategy + style}. */
    static ControlPayload decodeControlPayload(byte[] data) {
        if (data == null || data.length != 6) {
            throw new IllegalArgumentException("Legacy NANO control payload must be 6 bytes");
        }
        for (int i = 0; i < CONTROL_MAGIC.length; i++) {
            if (data[i] != CONTROL_MAGIC[i]) {
                throw new IllegalArgumentException("Legacy control payload lacks NANO prefix");
            }
        }
        int strategy = data[4] & 0xff;
        int style = data[5] & 0xff;
        if (!isSupportedLegacyStrategy(strategy)) {
            throw new IllegalArgumentException("Unsupported legacy control strategy: " + strategy);
        }
        if (style != STYLE_OOB && style != STYLE_PASSCODE) {
            throw new IllegalArgumentException("Unsupported legacy control style: " + style);
        }
        return new ControlPayload(strategy, style);
    }

    /**
     * Frames one outbound {@code IDSBTLink} packet (derived from iOS 26.6 {@code identityservicesd}
     * {@code IDSBTLink::sendPacketBuffer} at {@code 0x1006f550c..0x1006f55bc}).
     * Supports 1-byte lengths ({@code <= 127}) and 2-byte big-endian lengths ({@code 128..32767})
     * without bit-7 checksum insertion.
     */
    public static byte[] encodeIdsBtLinkPacket(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > IDS_BT_LINK_MAX_PACKET_LENGTH) {
            throw new IllegalArgumentException("IDSBTLink payload must be 1..32767 bytes");
        }
        if ((payload[0] & 0x80) != 0) {
            throw new IllegalArgumentException("IDSBTLink bit-7 checksum-flagged payloads require a checksum builder");
        }
        if (payload.length <= 0x7F) {
            byte[] framed = new byte[1 + payload.length];
            framed[0] = (byte) payload.length;
            System.arraycopy(payload, 0, framed, 1, payload.length);
            return framed;
        }
        byte[] framed = new byte[2 + payload.length];
        framed[0] = (byte) (0x80 | ((payload.length >>> 8) & 0x7F));
        framed[1] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, framed, 2, payload.length);
        return framed;
    }

    private static boolean matchesSlice(byte[] haystack, int offset, byte[] needle) {
        for (int i = 0; i < needle.length; i++) {
            if (haystack[offset + i] != needle[i]) {
                return false;
            }
        }
        return true;
    }

    private static String decodePrintableAscii(byte[] bytes, int offset, int length) {
        int end = offset + length;
        while (end > offset && bytes[end - 1] == 0) {
            end--;
        }
        if (end == offset) {
            return null;
        }
        for (int i = offset; i < end; i++) {
            int b = bytes[i] & 0xff;
            if (b < 0x20 || b > 0x7E) {
                return null;
            }
        }
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static int readLe16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static void writeLe16(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >>> 8) & 0xff);
    }

    static final class LegacyAdvertisement {
        final boolean hasExtensiblePairServiceUuid;
        final int strategy;
        final int advertisingIdentifier;
        final int enclosureMaterial;
        final int deviceSize;
        final boolean manualNameMatch;
        private final String localName;
        final String productType;
        final String systemVersion;
        final int pairingVersion;

        LegacyAdvertisement(
                boolean hasExtensiblePairServiceUuid,
                int strategy,
                int advertisingIdentifier,
                int enclosureMaterial,
                int deviceSize,
                boolean manualNameMatch,
                String localName,
                String productType,
                String systemVersion,
                int pairingVersion) {
            this.hasExtensiblePairServiceUuid = hasExtensiblePairServiceUuid;
            this.strategy = strategy;
            this.advertisingIdentifier = advertisingIdentifier;
            this.enclosureMaterial = enclosureMaterial;
            this.deviceSize = deviceSize;
            this.manualNameMatch = manualNameMatch;
            this.localName = localName;
            this.productType = productType;
            this.systemVersion = systemVersion;
            this.pairingVersion = pairingVersion;
        }

        int effectiveStrategy() {
            return isSupportedLegacyStrategy(strategy)
                    ? strategy
                    : STRATEGY_SEQUENTIAL_DIRECT_IPSEC;
        }

        boolean matchesOpticalCode(LegacyOpticalCode code) {
            if (code == null) {
                return false;
            }
            if (localName != null && !code.advertisedName.equals(localName)) {
                return false;
            }
            if (localName == null) {
                if (advertisingIdentifier < 0 || enclosureMaterial < 0 || deviceSize < 0
                        || !isSupportedLegacyStrategy(strategy)) {
                    return false;
                }
                String synthesized = String.format(
                        java.util.Locale.US,
                        "%05d%c%c%c",
                        advertisingIdentifier,
                        HUMAN_ALPHABET.charAt(strategy),
                        HUMAN_ALPHABET.charAt(enclosureMaterial),
                        HUMAN_ALPHABET.charAt(deviceSize));
                if (!code.advertisedName.equals(synthesized)) {
                    return false;
                }
            }
            if (isSupportedLegacyStrategy(strategy) && strategy != code.strategy) {
                return false;
            }
            if (enclosureMaterial >= 0 && enclosureMaterial != code.material) {
                return false;
            }
            if (deviceSize >= 0 && deviceSize != code.size) {
                return false;
            }
            return true;
        }

        LegacyAdvertisement merge(LegacyAdvertisement newer) {
            if (newer == null) {
                return this;
            }
            int mergedStrategy = isSupportedLegacyStrategy(newer.strategy) ? newer.strategy : strategy;
            int mergedAdvId = newer.advertisingIdentifier >= 0 ? newer.advertisingIdentifier : advertisingIdentifier;
            int mergedMat = newer.enclosureMaterial >= 0 ? newer.enclosureMaterial : enclosureMaterial;
            int mergedSize = newer.deviceSize >= 0 ? newer.deviceSize : deviceSize;
            String mergedName = newer.localName != null ? newer.localName : localName;
            String mergedProduct = newer.productType != null ? newer.productType : productType;
            String mergedVersion = newer.systemVersion != null ? newer.systemVersion : systemVersion;
            int mergedPairingVer = newer.pairingVersion >= 0 ? newer.pairingVersion : pairingVersion;
            return new LegacyAdvertisement(
                    hasExtensiblePairServiceUuid || newer.hasExtensiblePairServiceUuid,
                    mergedStrategy,
                    mergedAdvId,
                    mergedMat,
                    mergedSize,
                    manualNameMatch || newer.manualNameMatch,
                    mergedName,
                    mergedProduct,
                    mergedVersion,
                    mergedPairingVer);
        }
    }

    /**
     * Merges {@code ADV_IND} and {@code SCAN_RSP} advertisements from legacy Watch peers
     * within a 2,000 ms window, releasing UUID-only peers after 400 ms if no {@code SCAN_RSP} arrives.
     */
    static final class LegacyTargetSelector {
        private static final long RESPONSE_WINDOW_MS = 2_000L;
        private static final long UUID_MERGE_HOLD_MS = 400L;
        private static final int MAX_CANDIDATES = 64;

        private static final class Candidate {
            final long firstSeenMs;
            long lastSeenMs;
            int addressType;
            byte[] address;
            int rssi;
            byte[] data;
            HciCodec.WatchSetupPayload setup;
            LegacyAdvertisement legacy;

            Candidate(long nowMs, HciCodec.AdvertisingReport report, LegacyAdvertisement legacy) {
                this.firstSeenMs = nowMs;
                this.lastSeenMs = nowMs;
                this.addressType = report.addressType;
                this.address = report.address.clone();
                this.rssi = report.rssi;
                this.data = report.data == null ? new byte[0] : report.data.clone();
                this.setup = report.setup;
                this.legacy = legacy;
            }

            HciCodec.AdvertisingReport toReport() {
                return new HciCodec.AdvertisingReport(
                        0, addressType, address.clone(), rssi, data.clone(), setup, legacy);
            }
        }

        private final Map<String, Candidate> connectable = new LinkedHashMap<>();

        HciCodec.AdvertisingReport accept(HciCodec.AdvertisingReport report, long elapsedMs) {
            if (report == null || report.address == null || report.address.length != 6
                    || (report.addressType != 0 && report.addressType != 1)) {
                return null;
            }
            pruneExpired(elapsedMs);
            if (report.rssi == 127 || report.rssi < HciCodec.WATCH_MIN_RSSI_DBM) {
                return null;
            }
            if (report.setup != null && report.setup.decodedIdentifier != null
                    && report.setup.decodedIdentifier.pairingStrategy
                    == WatchSetupMetadataCodec.NETWORK_RELAY_PAIRING_STRATEGY) {
                return null;
            }
            String key = report.addressType + ":" + HciCodec.toHex(report.address);
            if (report.eventType == 0) {
                Candidate existing = connectable.get(key);
                LegacyAdvertisement mergedLegacy = report.legacySetup;
                HciCodec.WatchSetupPayload mergedSetup = report.setup;
                if (existing != null) {
                    if (existing.legacy != null) {
                        mergedLegacy = existing.legacy.merge(mergedLegacy);
                    }
                    if (mergedSetup == null) {
                        mergedSetup = existing.setup;
                    }
                    connectable.remove(key);
                }
                Candidate updated = new Candidate(elapsedMs, report, mergedLegacy);
                updated.setup = mergedSetup;
                if (isImmediatelyReady(updated.legacy)) {
                    return updated.toReport();
                }
                connectable.put(key, updated);
                if (connectable.size() > MAX_CANDIDATES) {
                    connectable.remove(connectable.keySet().iterator().next());
                }
                return null;
            }
            if (report.eventType == 4) {
                Candidate existing = connectable.get(key);
                if (existing == null || report.legacySetup == null) {
                    return null;
                }
                existing.lastSeenMs = elapsedMs;
                existing.rssi = report.rssi;
                existing.legacy = existing.legacy == null
                        ? report.legacySetup
                        : existing.legacy.merge(report.legacySetup);
                if (report.setup != null) {
                    existing.setup = report.setup;
                }
                if (report.data != null && report.data.length > 0) {
                    existing.data = report.data.clone();
                }
                if (isImmediatelyReady(existing.legacy) || existing.legacy.hasExtensiblePairServiceUuid) {
                    connectable.remove(key);
                    return existing.toReport();
                }
            }
            return null;
        }

        HciCodec.AdvertisingReport pollReady(long elapsedMs) {
            pruneExpired(elapsedMs);
            var iterator = connectable.entrySet().iterator();
            while (iterator.hasNext()) {
                Candidate candidate = iterator.next().getValue();
                if (candidate.legacy != null
                        && candidate.legacy.hasExtensiblePairServiceUuid
                        && elapsedMs - candidate.firstSeenMs >= UUID_MERGE_HOLD_MS) {
                    iterator.remove();
                    return candidate.toReport();
                }
            }
            return null;
        }

        private static boolean isImmediatelyReady(LegacyAdvertisement legacy) {
            if (legacy == null) {
                return false;
            }
            return isSupportedLegacyStrategy(legacy.strategy) || legacy.manualNameMatch;
        }

        private void pruneExpired(long elapsedMs) {
            connectable.entrySet().removeIf(entry ->
                    elapsedMs - entry.getValue().lastSeenMs > RESPONSE_WINDOW_MS
                            || elapsedMs < entry.getValue().lastSeenMs);
        }
    }

    static final class ControlPayload {
        final int strategy;
        final int style;

        ControlPayload(int strategy, int style) {
            this.strategy = strategy;
            this.style = style;
        }
    }

    /**
     * Minimal dynamic GATT ATT client PDU builders/parsers for discovering and writing
     * {@code 9AA4730F-...} / {@code 5F6C6A23-...} without hardcoding handles.
     */
    public static final class GattAttClient {
        private GattAttClient() {
        }

        public static boolean isAttClientResponseOpcode(int opcode) {
            return opcode == ATT_OPCODE_ERROR_RSP
                    || opcode == ATT_OPCODE_EXCHANGE_MTU_RSP
                    || opcode == ATT_OPCODE_FIND_BY_TYPE_VALUE_RSP
                    || opcode == ATT_OPCODE_READ_BY_TYPE_RSP
                    || opcode == ATT_OPCODE_READ_BY_GROUP_TYPE_RSP
                    || opcode == ATT_OPCODE_WRITE_RSP;
        }

        public static byte[] buildExchangeMtuRequest(int clientRxMtu) {
            if (clientRxMtu < 23 || clientRxMtu > 517) {
                throw new IllegalArgumentException("ATT client RX MTU must be 23..517");
            }
            byte[] pdu = new byte[3];
            pdu[0] = ATT_OPCODE_EXCHANGE_MTU_REQ;
            writeLe16(pdu, 1, clientRxMtu);
            return pdu;
        }

        public static int parseExchangeMtuResponse(byte[] pdu) {
            if (pdu == null || pdu.length != 3 || (pdu[0] & 0xff) != ATT_OPCODE_EXCHANGE_MTU_RSP) {
                throw new IllegalArgumentException("Expected 3-byte ATT_EXCHANGE_MTU_RSP");
            }
            return Math.max(23, readLe16(pdu, 1));
        }

        public static byte[] buildFindExtensiblePairServiceRequest(int startHandle) {
            validateHandleRange(startHandle, 0xFFFF);
            byte[] pdu = new byte[7 + EXTENSIBLE_PAIR_SERVICE_UUID_LE.length];
            pdu[0] = ATT_OPCODE_FIND_BY_TYPE_VALUE_REQ;
            writeLe16(pdu, 1, startHandle);
            writeLe16(pdu, 3, 0xFFFF);
            writeLe16(pdu, 5, 0x2800); // Primary Service declaration UUID
            System.arraycopy(EXTENSIBLE_PAIR_SERVICE_UUID_LE, 0, pdu, 7, EXTENSIBLE_PAIR_SERVICE_UUID_LE.length);
            return pdu;
        }

        public static List<HandleRange> parseFindByTypeValueResponse(byte[] pdu) {
            if (pdu == null || pdu.length < 5 || (pdu[0] & 0xff) != ATT_OPCODE_FIND_BY_TYPE_VALUE_RSP
                    || ((pdu.length - 1) % 4) != 0) {
                throw new IllegalArgumentException("Malformed ATT_FIND_BY_TYPE_VALUE_RSP");
            }
            List<HandleRange> ranges = new ArrayList<>((pdu.length - 1) / 4);
            for (int off = 1; off < pdu.length; off += 4) {
                ranges.add(new HandleRange(readLe16(pdu, off), readLe16(pdu, off + 2)));
            }
            return List.copyOf(ranges);
        }

        public static byte[] buildReadPrimaryServicesRequest(int startHandle, int endHandle) {
            validateHandleRange(startHandle, endHandle);
            byte[] pdu = new byte[7];
            pdu[0] = ATT_OPCODE_READ_BY_GROUP_TYPE_REQ;
            writeLe16(pdu, 1, startHandle);
            writeLe16(pdu, 3, endHandle);
            writeLe16(pdu, 5, 0x2800);
            return pdu;
        }

        public static List<PrimaryService> parseReadPrimaryServicesResponse(byte[] pdu) {
            if (pdu == null || pdu.length < 2 || (pdu[0] & 0xff) != ATT_OPCODE_READ_BY_GROUP_TYPE_RSP) {
                throw new IllegalArgumentException("Expected ATT_READ_BY_GROUP_TYPE_RSP");
            }
            int entryLen = pdu[1] & 0xff;
            if ((entryLen != 6 && entryLen != 20) || (pdu.length - 2) == 0 || ((pdu.length - 2) % entryLen) != 0) {
                throw new IllegalArgumentException("Malformed ATT_READ_BY_GROUP_TYPE_RSP");
            }
            List<PrimaryService> services = new ArrayList<>((pdu.length - 2) / entryLen);
            for (int off = 2; off < pdu.length; off += entryLen) {
                services.add(new PrimaryService(
                        readLe16(pdu, off),
                        readLe16(pdu, off + 2),
                        Arrays.copyOfRange(pdu, off + 4, off + entryLen)));
            }
            return List.copyOf(services);
        }

        public static byte[] buildDiscoverCharacteristicsRequest(int startHandle, int endHandle) {
            validateHandleRange(startHandle, endHandle);
            byte[] pdu = new byte[7];
            pdu[0] = ATT_OPCODE_READ_BY_TYPE_REQ;
            writeLe16(pdu, 1, startHandle);
            writeLe16(pdu, 3, endHandle);
            writeLe16(pdu, 5, 0x2803); // Characteristic declaration UUID
            return pdu;
        }

        public static List<CharacteristicDeclaration> parseDiscoverCharacteristicsResponse(byte[] pdu) {
            if (pdu == null || pdu.length < 2 || (pdu[0] & 0xff) != ATT_OPCODE_READ_BY_TYPE_RSP) {
                throw new IllegalArgumentException("Expected ATT_READ_BY_TYPE_RSP");
            }
            int entryLen = pdu[1] & 0xff;
            if ((entryLen != 7 && entryLen != 21) || (pdu.length - 2) == 0 || ((pdu.length - 2) % entryLen) != 0) {
                throw new IllegalArgumentException("Malformed characteristic ATT_READ_BY_TYPE_RSP");
            }
            List<CharacteristicDeclaration> list = new ArrayList<>((pdu.length - 2) / entryLen);
            for (int off = 2; off < pdu.length; off += entryLen) {
                list.add(new CharacteristicDeclaration(
                        readLe16(pdu, off),
                        pdu[off + 2] & 0xff,
                        readLe16(pdu, off + 3),
                        Arrays.copyOfRange(pdu, off + 5, off + entryLen)));
            }
            return List.copyOf(list);
        }

        public static byte[] buildWriteRequest(int valueHandle, byte[] value) {
            if (valueHandle < 1 || valueHandle > 0xFFFF || value == null) {
                throw new IllegalArgumentException("Invalid ATT write handle or payload");
            }
            byte[] pdu = new byte[3 + value.length];
            pdu[0] = ATT_OPCODE_WRITE_REQ;
            writeLe16(pdu, 1, valueHandle);
            System.arraycopy(value, 0, pdu, 3, value.length);
            return pdu;
        }

        public static boolean isWriteResponse(byte[] pdu) {
            return pdu != null && pdu.length == 1 && (pdu[0] & 0xff) == ATT_OPCODE_WRITE_RSP;
        }

        public static AttError parseErrorResponse(byte[] pdu) {
            if (pdu == null || pdu.length != 5 || (pdu[0] & 0xff) != ATT_OPCODE_ERROR_RSP) {
                throw new IllegalArgumentException("Expected 5-byte ATT_ERROR_RSP");
            }
            return new AttError(pdu[1] & 0xff, readLe16(pdu, 2), pdu[4] & 0xff);
        }

        private static void validateHandleRange(int startHandle, int endHandle) {
            if (startHandle < 1 || endHandle < startHandle || endHandle > 0xFFFF) {
                throw new IllegalArgumentException("Invalid ATT handle range");
            }
        }
    }

    public static final class HandleRange {
        public final int startHandle;
        public final int endHandle;

        public HandleRange(int startHandle, int endHandle) {
            this.startHandle = startHandle;
            this.endHandle = endHandle;
        }
    }

    public static final class PrimaryService {
        public final int startHandle;
        public final int endHandle;
        public final byte[] uuidBytes;

        public PrimaryService(int startHandle, int endHandle, byte[] uuidBytes) {
            this.startHandle = startHandle;
            this.endHandle = endHandle;
            this.uuidBytes = uuidBytes.clone();
        }

        public boolean isExtensiblePairService() {
            return Arrays.equals(uuidBytes, EXTENSIBLE_PAIR_SERVICE_UUID_LE);
        }
    }

    public static final class CharacteristicDeclaration {
        public final int declarationHandle;
        public final int properties;
        public final int valueHandle;
        public final byte[] uuidBytes;

        public CharacteristicDeclaration(int declarationHandle, int properties, int valueHandle, byte[] uuidBytes) {
            this.declarationHandle = declarationHandle;
            this.properties = properties;
            this.valueHandle = valueHandle;
            this.uuidBytes = uuidBytes.clone();
        }

        public boolean isExtensiblePairControlCharacteristic() {
            return Arrays.equals(uuidBytes, EXTENSIBLE_PAIR_CONTROL_CHAR_UUID_LE);
        }
    }

    public static final class AttError {
        public final int requestOpcode;
        public final int attributeHandle;
        public final int errorCode;

        public AttError(int requestOpcode, int attributeHandle, int errorCode) {
            this.requestOpcode = requestOpcode;
            this.attributeHandle = attributeHandle;
            this.errorCode = errorCode;
        }
    }

    /**
     * [EXPERIMENTAL] Legacy PBBridge optical pairing payload for strategies 1-3 (16-byte / 32-hex
     * OOB key). Secret bytes are held in a mutable array wiped by {@link #close()}.
     */
    public static final class LegacyOpticalCode implements AutoCloseable {
        public final int strategy;
        public final int compatibilityVersion;
        public final String advertisedName;
        public final int material;
        public final int size;
        public final String systemVersion;
        private byte[] oobKey;

        private LegacyOpticalCode(
                int strategy,
                int compatibilityVersion,
                String advertisedName,
                int material,
                int size,
                String systemVersion,
                byte[] oobKey) {
            this.strategy = strategy;
            this.compatibilityVersion = compatibilityVersion;
            this.advertisedName = advertisedName;
            this.material = material;
            this.size = size;
            this.systemVersion = systemVersion;
            this.oobKey = oobKey;
        }

        public static LegacyOpticalCode parse(byte[] payload) {
            if (payload == null || payload.length != 110) {
                throw invalid();
            }
            int end = 0;
            while (end < payload.length && payload[end] != 0) {
                if (payload[end] < 33 || payload[end] > 126) {
                    throw invalid();
                }
                end++;
            }
            if (end == 0) {
                throw invalid();
            }
            int[] positions = new int[12];
            int field = 0;
            for (int i = 0; i + 1 < end; i++) {
                if (payload[i] == '-' && payload[i + 1] == '-') {
                    if (field >= 5) {
                        throw invalid();
                    }
                    positions[field * 2 + 1] = i;
                    positions[++field * 2] = i + 2;
                    i++;
                }
            }
            if (field != 5) {
                throw invalid();
            }
            positions[11] = end;
            for (int i = 0; i < 6; i++) {
                if (positions[i * 2] == positions[i * 2 + 1]) {
                    throw invalid();
                }
            }
            int strategy = parseBoundedInt(payload, positions[0], positions[1], STRATEGY_SEQUENTIAL_DIRECT_IPSEC);
            if (!isSupportedLegacyStrategy(strategy)) {
                throw invalid();
            }
            int compatibility = parseBoundedInt(payload, positions[2], positions[3], 63);
            if (compatibility == 0 || positions[5] - positions[4] != 8
                    || positions[7] - positions[6] != 32) {
                throw invalid();
            }
            String name = new String(payload, positions[4], 8, StandardCharsets.US_ASCII);
            if (!name.matches("[0-9]{5}[BCD][A-Za-z0-9]{2}")) {
                throw invalid();
            }
            if (HUMAN_ALPHABET.indexOf(name.charAt(5)) != strategy) {
                throw invalid();
            }
            int material = parseBoundedInt(payload, positions[8], positions[9], 61);
            int sizeEnd = positions[11];
            String version = "";
            for (int i = positions[10]; i + 1 < end; i++) {
                if (payload[i] == '&' && payload[i + 1] == '&') {
                    sizeEnd = i;
                    version = new String(payload, i + 2, end - i - 2, StandardCharsets.US_ASCII);
                    if (!version.matches("[0-9]{1,2}\\.[0-9]{1,2}(\\.[0-9]{1,2})?")) {
                        throw invalid();
                    }
                    break;
                }
            }
            int size = parseBoundedInt(payload, positions[10], sizeEnd, 61);
            if (name.charAt(6) != HUMAN_ALPHABET.charAt(material)
                    || name.charAt(7) != HUMAN_ALPHABET.charAt(size)) {
                throw invalid();
            }
            byte[] key = new byte[16];
            try {
                for (int i = 0; i < key.length; i++) {
                    int upper = Character.digit(payload[positions[6] + i * 2], 16);
                    int lower = Character.digit(payload[positions[6] + i * 2 + 1], 16);
                    if (upper < 0 || lower < 0) {
                        throw invalid();
                    }
                    key[i] = (byte) ((upper << 4) | lower);
                }
                LegacyOpticalCode code = new LegacyOpticalCode(
                        strategy, compatibility, name, material, size, version, key);
                key = null;
                return code;
            } finally {
                if (key != null) {
                    Arrays.fill(key, (byte) 0);
                }
            }
        }

        public byte[] oobKey() {
            if (oobKey == null) {
                throw new IllegalStateException("Legacy optical code is closed");
            }
            return oobKey.clone();
        }

        @Override
        public void close() {
            if (oobKey != null) {
                Arrays.fill(oobKey, (byte) 0);
                oobKey = null;
            }
        }

        private static int parseBoundedInt(byte[] input, int start, int end, int maximum) {
            if (start == end || end - start > 3) {
                throw invalid();
            }
            int value = 0;
            for (int i = start; i < end; i++) {
                if (input[i] < '0' || input[i] > '9') {
                    throw invalid();
                }
                value = value * 10 + (input[i] - '0');
            }
            if (value > maximum) {
                throw invalid();
            }
            return value;
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("Invalid or unsupported legacy optical pairing payload");
        }
    }

    public static final class IdsBtLinkFrame {
        public final byte[] payload;
        public final boolean checksumFlagged;
        public final int rawChecksum;

        public IdsBtLinkFrame(byte[] payload, boolean checksumFlagged, int rawChecksum) {
            this.payload = payload.clone();
            this.checksumFlagged = checksumFlagged;
            this.rawChecksum = rawChecksum;
        }
    }

    /**
     * Incremental decoder for {@code IDSBTLink} 1-byte/2-byte length-prefixed frames
     * (derived from iOS 26.6 {@code identityservicesd} {@code 0x1006f5ec8..0x1006f6100}).
     */
    public static final class IdsBtLinkStreamDecoder {
        private static final int MAX_BUFFERED_BYTES = 64 * 1024;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public IdsBtLinkStreamDecoder() {
        }

        public List<IdsBtLinkFrame> push(byte[] chunk) {
            if (chunk != null && chunk.length > 0) {
                if (buffer.size() + chunk.length > MAX_BUFFERED_BYTES) {
                    buffer.reset();
                    throw new IllegalArgumentException("IDSBTLink stream buffer exceeded 64 KiB");
                }
                buffer.writeBytes(chunk);
            }
            byte[] all = buffer.toByteArray();
            int cursor = 0;
            List<IdsBtLinkFrame> frames = new ArrayList<>();
            while (cursor < all.length) {
                int first = all[cursor] & 0xff;
                int headerLen;
                int bodyLen;
                if ((first & 0x80) == 0) {
                    headerLen = 1;
                    bodyLen = first;
                } else {
                    if (all.length - cursor < 2) {
                        break;
                    }
                    headerLen = 2;
                    bodyLen = ((first & 0x7F) << 8) | (all[cursor + 1] & 0xff);
                }
                if (bodyLen == 0 || bodyLen > IDS_BT_LINK_MAX_PACKET_LENGTH) {
                    buffer.reset();
                    throw new IllegalArgumentException("Invalid IDSBTLink frame length: " + bodyLen);
                }
                if (all.length - cursor < headerLen + bodyLen) {
                    break;
                }
                int bodyStart = cursor + headerLen;
                int firstPayloadByte = all[bodyStart] & 0xff;
                if ((firstPayloadByte & 0x80) != 0 && bodyLen >= 3) {
                    int checksum = ((all[bodyStart + 1] & 0xff) << 8) | (all[bodyStart + 2] & 0xff);
                    byte[] logical = new byte[bodyLen - 2];
                    logical[0] = all[bodyStart];
                    if (bodyLen > 3) {
                        System.arraycopy(all, bodyStart + 3, logical, 1, bodyLen - 3);
                    }
                    frames.add(new IdsBtLinkFrame(logical, true, checksum));
                } else {
                    frames.add(new IdsBtLinkFrame(
                            Arrays.copyOfRange(all, bodyStart, bodyStart + bodyLen),
                            false,
                            -1));
                }
                cursor = bodyStart + bodyLen;
            }
            buffer.reset();
            if (cursor < all.length) {
                buffer.write(all, cursor, all.length - cursor);
            }
            return List.copyOf(frames);
        }

        int bufferedByteCount() {
            return buffer.size();
        }
    }
}
