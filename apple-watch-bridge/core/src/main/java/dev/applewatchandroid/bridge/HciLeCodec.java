package dev.applewatchandroid.bridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Low-Energy HCI command/event, legacy advertising, encryption, data length,
 * and PHY codecs used by {@link HciCodec}.
 */
final class HciLeCodec {
    private static final int LE_MAX_DATA_TIME_US = 17040;

    private HciLeCodec() {
    }

    static byte[] leEventMask() {
        return new byte[]{0x03, 0, 0, 0, 0, 0, 0, 0};
    }

    static byte[] leEventMask(boolean dataLengthChange, boolean phyUpdateComplete) {
        int byte0 = 0x01 | 0x02 | 0x08 | (dataLengthChange ? 0x40 : 0);
        int byte1 = phyUpdateComplete ? 0x08 : 0;
        return new byte[]{(byte) byte0, (byte) byte1, 0, 0, 0, 0, 0, 0};
    }

    static byte[] passiveScanParameters() {
        return new byte[]{0x00, 0x60, 0x00, 0x60, 0x00, 0x00, 0x00};
    }

    static byte[] activeScanParameters(int ownAddressType) {
        if (ownAddressType != 0 && ownAddressType != 1) {
            throw new IllegalArgumentException("Active scan needs a configured public or random address");
        }
        return new byte[]{0x01, 0x60, 0x00, 0x60, 0x00, (byte) ownAddressType, 0x00};
    }

    static byte[] scanEnableParameters(boolean enabled) {
        return new byte[]{(byte) (enabled ? 1 : 0), 0x00};
    }

    static byte[] advertisingParameters() {
        return new byte[]{
                0x20, 0x00, // min interval (20ms)
                0x30, 0x00, // max interval (30ms)
                0x00,       // adv type: ADV_IND (connectable undirected)
                0x00,       // own addr type: public
                0x00,       // peer addr type: public
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // peer addr
                0x07,       // channel map: 37, 38, 39
                0x00        // filter policy: allow all
        };
    }

    static byte[] advertisingData(byte[] applePayload, String localName) {
        byte[] data = new byte[32];
        int cursor = 1;
        // Flags: 02 01 1A
        data[cursor++] = 0x02;
        data[cursor++] = 0x01;
        data[cursor++] = 0x1A;
        if (applePayload != null && applePayload.length > 0 && cursor + 1 + applePayload.length <= 31) {
            data[cursor++] = (byte) (applePayload.length);
            System.arraycopy(applePayload, 0, data, cursor, applePayload.length);
            cursor += applePayload.length;
        }
        if (localName != null && !localName.isEmpty()) {
            byte[] nameBytes = localName.getBytes(StandardCharsets.UTF_8);
            if (cursor + 2 + nameBytes.length <= 31) {
                data[cursor++] = (byte) (nameBytes.length + 1);
                data[cursor++] = 0x09; // Complete Local Name
                System.arraycopy(nameBytes, 0, data, cursor, nameBytes.length);
                cursor += nameBytes.length;
            }
        }
        data[0] = (byte) (cursor - 1);
        return data;
    }

    static byte[] advertiseEnableParameters(boolean enabled) {
        return new byte[]{(byte) (enabled ? 1 : 0)};
    }

    static List<HciCodec.AdvertisingReport> parseLegacyAdvertisingReports(byte[] event) {
        if (!HciCodec.hasDeclaredEventLength(event, 2)
                || HciCodec.unsigned(event[0]) != 0x3E
                || HciCodec.unsigned(event[2]) != 0x02) {
            return List.of();
        }
        int end = HciCodec.unsigned(event[1]) + 2;
        int count = HciCodec.unsigned(event[3]);
        int offset = 4;
        List<HciCodec.AdvertisingReport> reports = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (offset + 9 > end) {
                return List.of();
            }
            int eventType = HciCodec.unsigned(event[offset]);
            int addressType = HciCodec.unsigned(event[offset + 1]);
            byte[] address = Arrays.copyOfRange(event, offset + 2, offset + 8);
            int dataLength = HciCodec.unsigned(event[offset + 8]);
            int dataStart = offset + 9;
            if (dataStart + dataLength + 1 > end) {
                return List.of();
            }
            byte[] data = Arrays.copyOfRange(
                    event,
                    dataStart,
                    dataStart + dataLength);
            int rssi = event[dataStart + dataLength];
            reports.add(new HciCodec.AdvertisingReport(
                    eventType,
                    addressType,
                    address,
                    rssi,
                    data,
                    parseWatchSetupPayload(data)));
            offset = dataStart + dataLength + 1;
        }
        if (offset != end) {
            return List.of();
        }
        return reports;
    }

    static HciCodec.WatchSetupPayload parseWatchSetupPayload(byte[] advertisingData) {
        int offset = 0;
        while (offset < advertisingData.length) {
            int fieldLength = HciCodec.unsigned(advertisingData[offset]);
            if (fieldLength == 0) {
                return null;
            }
            int fieldEnd = offset + 1 + fieldLength;
            if (fieldLength < 1 || fieldEnd > advertisingData.length) {
                return null;
            }
            int type = HciCodec.unsigned(advertisingData[offset + 1]);
            int valueStart = offset + 2;
            if (type == 0x16 && fieldEnd - valueStart >= 3) {
                int serviceUuid = HciCodec.le16(advertisingData, valueStart);
                int cursor = valueStart + 2;
                int encodedSubtype = HciCodec.unsigned(advertisingData[cursor++]);
                int subtype = encodedSubtype & 0x7F;
                int payloadEnd = fieldEnd;
                if ((encodedSubtype & 0x80) != 0) {
                    if (cursor >= fieldEnd) {
                        return null;
                    }
                    int explicitLength = HciCodec.unsigned(advertisingData[cursor++]) & 0x1F;
                    payloadEnd = cursor + explicitLength;
                    if (payloadEnd > fieldEnd) {
                        return null;
                    }
                }
                if (serviceUuid == 0xFE25 && subtype == 0x06) {
                    byte[] setupData = Arrays.copyOfRange(
                            advertisingData,
                            cursor,
                            payloadEnd);
                    if (setupData.length < 5 || (HciCodec.unsigned(setupData[0]) & 0xE0) != 0x20) {
                        return null;
                    }
                    byte[] identifier =
                            Arrays.copyOfRange(
                                    setupData,
                                    1,
                                    5);
                    byte[] metadata =
                            Arrays.copyOfRange(
                                    setupData,
                                    5,
                                    setupData.length);
                    WatchSetupMetadataCodec.Identifier
                            decodedIdentifier =
                            WatchSetupMetadataCodec
                                    .decodeIdentifier(
                                            identifier);
                    WatchSetupMetadataCodec.ExtendedMetadata
                            decodedMetadata = null;
                    if (metadata.length
                            >= WatchSetupMetadataCodec
                                    .CURRENT_METADATA_LENGTH) {
                        decodedMetadata =
                                WatchSetupMetadataCodec
                                        .decodeExtendedMetadata(
                                                metadata);
                    }
                    return new HciCodec.WatchSetupPayload(
                            setupData,
                            (HciCodec.unsigned(setupData[0]) >>> 5) & 0x07,
                            identifier,
                            metadata,
                            decodedIdentifier,
                            decodedMetadata);
                }
            }
            offset = fieldEnd;
        }
        return null;
    }

    static byte[] buildLeSetRandomAddressParameters(
            byte[] randomAddress) {
        BluetoothLocalIdentity.requireStaticRandomAddress(
                randomAddress);
        return randomAddress.clone();
    }

    static byte[] buildLeCreateConnectionParameters(
            HciCodec.AdvertisingReport target,
            int ownAddressType) {
        return buildLeCreateConnectionParameters(
                target.address,
                target.addressType,
                ownAddressType);
    }

    static byte[] buildLeCreateConnectionParameters(
            byte[] peerAddress,
            int peerAddressType,
            int ownAddressType) {
        if (peerAddress == null || peerAddress.length != 6) {
            throw new IllegalArgumentException("LE address must contain six bytes");
        }
        if (peerAddressType != 0 && peerAddressType != 1) {
            throw new IllegalArgumentException("Only public/random legacy LE address is accepted");
        }
        if (ownAddressType != 0 && ownAddressType != 1) {
            throw new IllegalArgumentException(
                    "Own LE address type must be public or random");
        }
        byte[] parameters = new byte[25];
        HciCodec.putLe16(parameters, 0, 0x0060);
        HciCodec.putLe16(parameters, 2, 0x0060);
        parameters[4] = 0x00;
        parameters[5] = (byte) peerAddressType;
        System.arraycopy(peerAddress, 0, parameters, 6, 6);
        parameters[12] = (byte) ownAddressType;
        HciCodec.putLe16(parameters, 13, 0x0018);
        HciCodec.putLe16(parameters, 15, 0x0028);
        HciCodec.putLe16(parameters, 17, 0x0000);
        HciCodec.putLe16(parameters, 19, 0x01F4);
        HciCodec.putLe16(parameters, 21, 0x0000);
        HciCodec.putLe16(parameters, 23, 0x0000);
        return parameters;
    }

    static HciCodec.LeConnectionComplete parseLeConnectionComplete(byte[] event) {
        if (!HciCodec.hasDeclaredEventLength(event, 2)
                || HciCodec.unsigned(event[0]) != 0x3E
                || HciCodec.unsigned(event[2]) != 0x01) {
            return null;
        }
        int status = HciCodec.unsigned(event[3]);
        if (status != 0) {
            return new HciCodec.LeConnectionComplete(status, -1, -1, new byte[0]);
        }
        if (HciCodec.unsigned(event[1]) < 19 || event.length < 21) {
            return null;
        }
        return new HciCodec.LeConnectionComplete(
                status,
                HciCodec.le16(event, 4) & 0x0FFF,
                HciCodec.unsigned(event[7]),
                Arrays.copyOfRange(event, 8, 14));
    }

    static byte[] buildLeStartEncryptionParameters(
            int connectionHandle,
            byte[] longTermKey) {
        return buildLeStartEncryptionParameters(connectionHandle, new byte[8], 0, longTermKey);
    }

    static byte[] buildLeStartEncryptionParameters(
            int connectionHandle,
            byte[] randomNumber,
            int ediv,
            byte[] longTermKey) {
        HciCodec.validateConnectionHandle(connectionHandle);
        if (randomNumber == null || randomNumber.length != 8) {
            throw new IllegalArgumentException(
                    "LE Start Encryption random number must be 8 bytes");
        }
        if ((ediv & ~0xFFFF) != 0) {
            throw new IllegalArgumentException(
                    "LE Start Encryption EDIV must fit in uint16");
        }
        if (longTermKey == null
                || longTermKey.length != 16) {
            throw new IllegalArgumentException(
                    "LE Long Term Key must be exactly 16 bytes");
        }
        byte[] parameters = new byte[28];
        HciCodec.putLe16(parameters, 0, connectionHandle);
        System.arraycopy(randomNumber, 0, parameters, 2, 8);
        HciCodec.putLe16(parameters, 10, ediv);
        System.arraycopy(longTermKey, 0, parameters, 12, longTermKey.length);
        return parameters;
    }

    static HciCodec.EncryptionChange parseEncryptionChange(
            byte[] event) {
        if (event == null || event.length < 6) {
            return null;
        }
        int eventCode = HciCodec.unsigned(event[0]);
        if ((eventCode != 0x08 && eventCode != 0x59)
                || !HciCodec.hasDeclaredEventLength(event, 4)) {
            return null;
        }
        return new HciCodec.EncryptionChange(
                HciCodec.unsigned(event[2]),
                HciCodec.le16(event, 3) & 0x0fff,
                HciCodec.unsigned(event[5]),
                eventCode == 0x59 && event.length >= 7
                        ? HciCodec.unsigned(event[6])
                        : -1);
    }

    static HciCodec.EncryptionChange
            parseEncryptionKeyRefreshComplete(
            byte[] event) {
        if (!HciCodec.hasDeclaredEventLength(event, 3)
                || HciCodec.unsigned(event[0]) != 0x30) {
            return null;
        }
        return new HciCodec.EncryptionChange(
                HciCodec.unsigned(event[2]),
                HciCodec.le16(event, 3) & 0x0fff,
                1,
                -1);
    }

    static int recommendedUncodedTxTimeUs(int txOctets) {
        validateLeDataLength(txOctets, HciCodec.LE_MIN_DATA_TIME_US);
        return Math.max(HciCodec.LE_MIN_DATA_TIME_US, txOctets * 8 + 112);
    }

    static byte[] buildLeWriteSuggestedDefaultDataLengthParameters(int txOctets, int txTimeUs) {
        validateLeDataLength(txOctets, txTimeUs);
        byte[] parameters = new byte[4];
        HciCodec.putLe16(parameters, 0, txOctets);
        HciCodec.putLe16(parameters, 2, txTimeUs);
        return parameters;
    }

    static byte[] buildLeSetDataLengthParameters(int connectionHandle, int txOctets, int txTimeUs) {
        HciCodec.validateConnectionHandle(connectionHandle);
        validateLeDataLength(txOctets, txTimeUs);
        byte[] parameters = new byte[6];
        HciCodec.putLe16(parameters, 0, connectionHandle);
        HciCodec.putLe16(parameters, 2, txOctets);
        HciCodec.putLe16(parameters, 4, txTimeUs);
        return parameters;
    }

    static byte[] buildLeSetDefaultPhyParameters(int allPhys, int txPhys, int rxPhys) {
        validateLePhyPreferences(allPhys, txPhys, rxPhys);
        return new byte[]{(byte) allPhys, (byte) txPhys, (byte) rxPhys};
    }

    static byte[] buildLeSetPhyParameters(
            int connectionHandle, int allPhys, int txPhys, int rxPhys, int phyOptions) {
        HciCodec.validateConnectionHandle(connectionHandle);
        validateLePhyPreferences(allPhys, txPhys, rxPhys);
        if (phyOptions < 0 || phyOptions > 3) {
            throw new IllegalArgumentException("Invalid LE PHY options");
        }
        byte[] parameters = new byte[7];
        HciCodec.putLe16(parameters, 0, connectionHandle);
        parameters[2] = (byte) allPhys;
        parameters[3] = (byte) txPhys;
        parameters[4] = (byte) rxPhys;
        HciCodec.putLe16(parameters, 5, phyOptions);
        return parameters;
    }

    static HciCodec.LeLocalFeatures parseLeLocalSupportedFeatures(HciCodec.CommandComplete complete) {
        if (complete == null
                || complete.opcode != HciCodec.OPCODE_LE_READ_LOCAL_SUPPORTED_FEATURES
                || complete.status != 0
                || complete.returnParameters.length != 9) {
            throw new IllegalArgumentException(
                    "Expected successful HCI LE Read Local Supported Features response");
        }
        int featureByte0 = HciCodec.unsigned(complete.returnParameters[1]);
        int featureByte1 = HciCodec.unsigned(complete.returnParameters[2]);
        return new HciCodec.LeLocalFeatures(
                (featureByte0 & 0x20) != 0,
                (featureByte1 & 0x01) != 0);
    }

    static HciCodec.LeMaximumDataLength parseLeMaximumDataLength(HciCodec.CommandComplete complete) {
        if (complete == null
                || complete.opcode != HciCodec.OPCODE_LE_READ_MAXIMUM_DATA_LENGTH
                || complete.status != 0
                || complete.returnParameters.length != 9) {
            throw new IllegalArgumentException(
                    "Expected successful HCI LE Read Maximum Data Length response");
        }
        int maxTxOctets = HciCodec.le16(complete.returnParameters, 1);
        int maxTxTimeUs = HciCodec.le16(complete.returnParameters, 3);
        int maxRxOctets = HciCodec.le16(complete.returnParameters, 5);
        int maxRxTimeUs = HciCodec.le16(complete.returnParameters, 7);
        if (maxTxOctets < HciCodec.LE_MIN_DATA_OCTETS
                || maxTxTimeUs < HciCodec.LE_MIN_DATA_TIME_US
                || maxRxOctets < HciCodec.LE_MIN_DATA_OCTETS
                || maxRxTimeUs < HciCodec.LE_MIN_DATA_TIME_US) {
            throw new IllegalArgumentException(
                    "Controller LE Maximum Data Length is below Bluetooth minimum");
        }
        return new HciCodec.LeMaximumDataLength(maxTxOctets, maxTxTimeUs, maxRxOctets, maxRxTimeUs);
    }

    static HciCodec.LeReadRemoteFeaturesComplete parseLeReadRemoteFeaturesComplete(byte[] event) {
        if (!HciCodec.hasDeclaredEventLength(event, 12)
                || HciCodec.unsigned(event[0]) != 0x3E
                || HciCodec.unsigned(event[1]) != 12
                || HciCodec.unsigned(event[2]) != 0x04) {
            return null;
        }
        return new HciCodec.LeReadRemoteFeaturesComplete(
                HciCodec.unsigned(event[3]),
                HciCodec.le16(event, 4) & 0x0FFF,
                (HciCodec.unsigned(event[6]) & 0x20) != 0,
                (HciCodec.unsigned(event[7]) & 0x01) != 0);
    }

    static HciCodec.LeDataLengthChange parseLeDataLengthChange(byte[] event) {
        if (!HciCodec.hasDeclaredEventLength(event, 11)
                || HciCodec.unsigned(event[0]) != 0x3E
                || HciCodec.unsigned(event[1]) != 11
                || HciCodec.unsigned(event[2]) != 0x07) {
            return null;
        }
        return new HciCodec.LeDataLengthChange(
                HciCodec.le16(event, 3) & 0x0FFF,
                HciCodec.le16(event, 5),
                HciCodec.le16(event, 7),
                HciCodec.le16(event, 9),
                HciCodec.le16(event, 11));
    }

    static HciCodec.LePhyUpdateComplete parseLePhyUpdateComplete(byte[] event) {
        if (!HciCodec.hasDeclaredEventLength(event, 6)
                || HciCodec.unsigned(event[0]) != 0x3E
                || HciCodec.unsigned(event[1]) != 6
                || HciCodec.unsigned(event[2]) != 0x0C) {
            return null;
        }
        return new HciCodec.LePhyUpdateComplete(
                HciCodec.unsigned(event[3]),
                HciCodec.le16(event, 4) & 0x0FFF,
                HciCodec.unsigned(event[6]),
                HciCodec.unsigned(event[7]));
    }

    private static void validateLeDataLength(int txOctets, int txTimeUs) {
        if (txOctets < HciCodec.LE_MIN_DATA_OCTETS || txOctets > HciCodec.LE_MAX_DATA_OCTETS) {
            throw new IllegalArgumentException("Invalid LE TX octets: " + txOctets);
        }
        if (txTimeUs < HciCodec.LE_MIN_DATA_TIME_US || txTimeUs > LE_MAX_DATA_TIME_US) {
            throw new IllegalArgumentException("Invalid LE TX time (us): " + txTimeUs);
        }
    }

    private static void validateLePhyPreferences(int allPhys, int txPhys, int rxPhys) {
        if (allPhys < 0 || allPhys > 3) {
            throw new IllegalArgumentException("Invalid LE ALL_PHYS mask: " + allPhys);
        }
        if (txPhys < 0 || txPhys > 7 || rxPhys < 0 || rxPhys > 7) {
            throw new IllegalArgumentException("Invalid LE PHY bitmask");
        }
    }
}
