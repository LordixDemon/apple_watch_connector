package dev.applewatchandroid.bridge;

import java.util.Arrays;

/**
 * Minimal Bluetooth LE Security Manager Protocol codec.
 *
 * <p>Secret-bearing payloads are returned only as defensive byte-array
 * copies. This class deliberately has no textual formatter.</p>
 */
final class BluetoothSmpCodec {
    static final int FIXED_CID = 0x0006;

    static final int PAIRING_REQUEST = 0x01;
    static final int PAIRING_RESPONSE = 0x02;
    static final int PAIRING_CONFIRM = 0x03;
    static final int PAIRING_RANDOM = 0x04;
    static final int PAIRING_FAILED = 0x05;
    static final int ENCRYPTION_INFORMATION = 0x06;
    static final int CENTRAL_IDENTIFICATION = 0x07;
    static final int IDENTITY_INFORMATION = 0x08;
    static final int IDENTITY_ADDRESS_INFORMATION = 0x09;
    static final int SIGNING_INFORMATION = 0x0a;
    static final int SECURITY_REQUEST = 0x0b;
    static final int PAIRING_PUBLIC_KEY = 0x0c;
    static final int PAIRING_DHKEY_CHECK = 0x0d;

    static final int IO_CAPABILITY_DISPLAY_ONLY = 0x00;
    static final int IO_CAPABILITY_DISPLAY_YES_NO = 0x01;
    static final int IO_CAPABILITY_KEYBOARD_ONLY = 0x02;
    static final int IO_CAPABILITY_NO_INPUT_NO_OUTPUT = 0x03;
    static final int IO_CAPABILITY_KEYBOARD_DISPLAY = 0x04;
    static final int OOB_DATA_NOT_PRESENT = 0x00;
    static final int OOB_DATA_PRESENT = 0x01;
    static final int AUTH_BONDING = 0x01;
    static final int AUTH_MITM = 0x04;
    static final int AUTH_SECURE_CONNECTIONS = 0x08;
    static final int AUTH_CT2 = 0x20;
    static final int AUTH_SC_MITM_BOND =
            AUTH_BONDING
                    | AUTH_MITM
                    | AUTH_SECURE_CONNECTIONS;
    static final int AUTH_SC_MITM_BOND_CT2 =
            AUTH_BONDING
                    | AUTH_MITM
                    | AUTH_SECURE_CONNECTIONS
                    | AUTH_CT2;
    static final int KEY_DISTRIBUTION_ENCRYPTION = 0x01;
    static final int KEY_DISTRIBUTION_IDENTITY = 0x02;
    static final int KEY_DISTRIBUTION_SIGNING = 0x04;
    static final int STANDARD_LE_KEY_DISTRIBUTION =
            KEY_DISTRIBUTION_ENCRYPTION
                    | KEY_DISTRIBUTION_IDENTITY
                    | KEY_DISTRIBUTION_SIGNING;
    static final int MAXIMUM_ENCRYPTION_KEY_SIZE = 16;

    static final int FAILURE_PASSKEY_ENTRY_FAILED = 0x01;
    static final int FAILURE_CONFIRM_VALUE = 0x04;
    static final int FAILURE_UNSPECIFIED_REASON = 0x08;
    static final int FAILURE_DHKEY_CHECK_FAILED = 0x0b;

    private BluetoothSmpCodec() {
    }

    static PairingFeatures createPairingRequest() {
        return new PairingFeatures(
                PAIRING_REQUEST,
                IO_CAPABILITY_DISPLAY_YES_NO,
                OOB_DATA_PRESENT,
                AUTH_SC_MITM_BOND_CT2,
                MAXIMUM_ENCRYPTION_KEY_SIZE,
                STANDARD_LE_KEY_DISTRIBUTION,
                STANDARD_LE_KEY_DISTRIBUTION);
    }

    /**
     * [EXPERIMENTAL LEGACY] Initiator Pairing Request for manual 6-digit passkey entry
     * (Watch displays passcode, companion acts as KeyboardOnly).
     */
    static PairingFeatures createLegacyPasskeyPairingRequest() {
        return new PairingFeatures(
                PAIRING_REQUEST,
                IO_CAPABILITY_KEYBOARD_ONLY,
                OOB_DATA_NOT_PRESENT,
                AUTH_SC_MITM_BOND_CT2,
                MAXIMUM_ENCRYPTION_KEY_SIZE,
                STANDARD_LE_KEY_DISTRIBUTION,
                STANDARD_LE_KEY_DISTRIBUTION);
    }

    /**
     * [EXPERIMENTAL LEGACY] Initiator Pairing Request when a legacy 16-byte optical OOB key
     * is available alongside KeyboardDisplay fallback.
     */
    static PairingFeatures createLegacyOobPairingRequest() {
        return new PairingFeatures(
                PAIRING_REQUEST,
                IO_CAPABILITY_KEYBOARD_DISPLAY,
                OOB_DATA_PRESENT,
                AUTH_SC_MITM_BOND_CT2,
                MAXIMUM_ENCRYPTION_KEY_SIZE,
                STANDARD_LE_KEY_DISTRIBUTION,
                STANDARD_LE_KEY_DISTRIBUTION);
    }

    static PairingFeatures parsePairingResponse(
            byte[] pdu) {
        PairingFeatures response =
                PairingFeatures.parse(
                        PAIRING_RESPONSE,
                        pdu);
        if (response.ioCapability < 0
                || response.ioCapability > 4
                || (response.oobDataFlag != 0
                && response.oobDataFlag != 1)
                || (response.authenticationRequirements
                & 0xc2) != 0
                || (response.authenticationRequirements
                & 0x03) != AUTH_BONDING
                || response.maximumEncryptionKeySize < 7
                || response.maximumEncryptionKeySize > 16
                || (response.initiatorKeyDistribution
                & ~0x0f) != 0
                || (response.responderKeyDistribution
                & ~0x0f) != 0) {
            throw new IllegalArgumentException(
                    "Watch Pairing Response has invalid feature values");
        }
        return response;
    }

    static byte[] pairingFailed(int reason) {
        if (reason < 1 || reason > 0x0e) {
            throw new IllegalArgumentException(
                    "SMP failure reason is outside the standard range");
        }
        return new byte[]{
                (byte) PAIRING_FAILED,
                (byte) reason
        };
    }

    static int parseSecurityRequest(byte[] pdu) {
        requireOpcodeAndLength(pdu, SECURITY_REQUEST, 2);
        int authentication = unsigned(pdu[1]);
        if ((authentication & 0xc2) != 0) {
            throw new IllegalArgumentException(
                    "SMP Security Request has invalid authentication requirements");
        }
        return authentication;
    }

    static int parsePairingFailure(byte[] pdu) {
        requireOpcodeAndLength(
                pdu,
                PAIRING_FAILED,
                2);
        return unsigned(pdu[1]);
    }

    static String pairingFailureReasonName(int reason) {
        switch (reason) {
            case 0x01:
                return "Passkey Entry Failed";
            case 0x02:
                return "OOB Not Available";
            case 0x03:
                return "Authentication Requirements";
            case 0x04:
                return "Confirm Value Failed";
            case 0x05:
                return "Pairing Not Supported";
            case 0x06:
                return "Encryption Key Size";
            case 0x07:
                return "Command Not Supported";
            case 0x08:
                return "Unspecified Reason";
            case 0x09:
                return "Repeated Attempts";
            case 0x0a:
                return "Invalid Parameters";
            case 0x0b:
                return "DHKey Check Failed";
            case 0x0c:
                return "Numeric Comparison Failed";
            case 0x0d:
                return "BR/EDR Pairing In Progress";
            case 0x0e:
                return "Cross-Transport Key Derivation Not Allowed";
            case 0x0f:
                return "Key Rejected";
            default:
                return "Unknown";
        }
    }

    static byte[] pairingPublicKey(
            byte[] publicKey) {
        return valuePdu(
                PAIRING_PUBLIC_KEY,
                publicKey,
                BleSecureConnectionsCrypto
                        .PUBLIC_KEY_LENGTH);
    }

    static byte[] pairingConfirm(byte[] confirmValue) {
        return valuePdu(
                PAIRING_CONFIRM,
                confirmValue,
                BleSecureConnectionsCrypto.AES_VALUE_LENGTH);
    }

    static byte[] parsePairingConfirm(byte[] pdu) {
        return parseValuePdu(
                PAIRING_CONFIRM,
                pdu,
                BleSecureConnectionsCrypto.AES_VALUE_LENGTH);
    }

    static byte[] encryptionInformation(byte[] longTermKey) {
        return valuePdu(
                ENCRYPTION_INFORMATION,
                longTermKey,
                BleSecureConnectionsCrypto.AES_VALUE_LENGTH);
    }

    static byte[] parseEncryptionInformation(byte[] pdu) {
        return parseValuePdu(
                ENCRYPTION_INFORMATION,
                pdu,
                BleSecureConnectionsCrypto.AES_VALUE_LENGTH);
    }

    static byte[] centralIdentification(int ediv, byte[] rand) {
        if ((ediv & ~0xffff) != 0 || rand == null || rand.length != 8) {
            throw new IllegalArgumentException(
                    "SMP central identification requires uint16 EDIV and 8-byte Rand");
        }
        byte[] pdu = new byte[11];
        pdu[0] = (byte) CENTRAL_IDENTIFICATION;
        pdu[1] = (byte) (ediv & 0xff);
        pdu[2] = (byte) ((ediv >>> 8) & 0xff);
        System.arraycopy(rand, 0, pdu, 3, rand.length);
        return pdu;
    }

    static CentralIdentification parseCentralIdentification(byte[] pdu) {
        requireOpcodeAndLength(pdu, CENTRAL_IDENTIFICATION, 11);
        int ediv = unsigned(pdu[1]) | (unsigned(pdu[2]) << 8);
        return new CentralIdentification(ediv, Arrays.copyOfRange(pdu, 3, 11));
    }

    static byte[] signingInformation(byte[] csrk) {
        return valuePdu(
                SIGNING_INFORMATION,
                csrk,
                BleSecureConnectionsCrypto.AES_VALUE_LENGTH);
    }

    static byte[] parseSigningInformation(byte[] pdu) {
        return parseValuePdu(
                SIGNING_INFORMATION,
                pdu,
                BleSecureConnectionsCrypto.AES_VALUE_LENGTH);
    }

    static byte[] parsePairingPublicKey(
            byte[] pdu) {
        return parseValuePdu(
                PAIRING_PUBLIC_KEY,
                pdu,
                BleSecureConnectionsCrypto
                        .PUBLIC_KEY_LENGTH);
    }

    static byte[] pairingRandom(byte[] nonce) {
        return valuePdu(
                PAIRING_RANDOM,
                nonce,
                BleSecureConnectionsCrypto
                        .AES_VALUE_LENGTH);
    }

    static byte[] parsePairingRandom(
            byte[] pdu) {
        return parseValuePdu(
                PAIRING_RANDOM,
                pdu,
                BleSecureConnectionsCrypto
                        .AES_VALUE_LENGTH);
    }

    static byte[] pairingDhKeyCheck(
            byte[] check) {
        return valuePdu(
                PAIRING_DHKEY_CHECK,
                check,
                BleSecureConnectionsCrypto
                        .AES_VALUE_LENGTH);
    }

    static byte[] parsePairingDhKeyCheck(
            byte[] pdu) {
        return parseValuePdu(
                PAIRING_DHKEY_CHECK,
                pdu,
                BleSecureConnectionsCrypto
                        .AES_VALUE_LENGTH);
    }

    static byte[] identityInformation(
            byte[] identityResolvingKey) {
        return valuePdu(
                IDENTITY_INFORMATION,
                identityResolvingKey,
                BleSecureConnectionsCrypto
                        .AES_VALUE_LENGTH);
    }

    static byte[] parseIdentityInformation(
            byte[] pdu) {
        return parseValuePdu(
                IDENTITY_INFORMATION,
                pdu,
                BleSecureConnectionsCrypto
                        .AES_VALUE_LENGTH);
    }

    static byte[] identityAddressInformation(
            int addressType,
            byte[] address) {
        if ((addressType != 0 && addressType != 1)
                || address == null
                || address.length != 6) {
            throw new IllegalArgumentException(
                    "SMP identity address and public/random type "
                            + "are required");
        }
        byte[] pdu = new byte[8];
        pdu[0] =
                (byte) IDENTITY_ADDRESS_INFORMATION;
        pdu[1] = (byte) addressType;
        System.arraycopy(
                address,
                0,
                pdu,
                2,
                address.length);
        return pdu;
    }

    static IdentityAddress
            parseIdentityAddressInformation(
            byte[] pdu) {
        requireOpcodeAndLength(
                pdu,
                IDENTITY_ADDRESS_INFORMATION,
                8);
        int addressType = unsigned(pdu[1]);
        if (addressType != 0 && addressType != 1) {
            throw new IllegalArgumentException(
                    "Watch SMP identity address type is invalid");
        }
        return new IdentityAddress(
                addressType,
                Arrays.copyOfRange(
                        pdu,
                        2,
                        8));
    }

    private static byte[] valuePdu(
            int opcode,
            byte[] value,
            int requiredLength) {
        if (value == null
                || value.length != requiredLength) {
            throw new IllegalArgumentException(
                    "SMP value has an invalid length");
        }
        byte[] pdu = new byte[1 + value.length];
        pdu[0] = (byte) opcode;
        System.arraycopy(
                value,
                0,
                pdu,
                1,
                value.length);
        return pdu;
    }

    private static byte[] parseValuePdu(
            int opcode,
            byte[] pdu,
            int requiredLength) {
        requireOpcodeAndLength(
                pdu,
                opcode,
                requiredLength + 1);
        return Arrays.copyOfRange(
                pdu,
                1,
                pdu.length);
    }

    static int opcode(byte[] pdu) {
        if (pdu == null || pdu.length == 0) {
            throw new IllegalArgumentException(
                    "SMP PDU is empty");
        }
        return unsigned(pdu[0]);
    }

    private static void requireOpcodeAndLength(
            byte[] pdu,
            int opcode,
            int length) {
        if (pdu == null
                || pdu.length != length
                || unsigned(pdu[0]) != opcode) {
            throw new IllegalArgumentException(
                    "Unexpected SMP opcode or length");
        }
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    static final class PairingFeatures {
        final int opcode;
        final int ioCapability;
        final int oobDataFlag;
        final int authenticationRequirements;
        final int maximumEncryptionKeySize;
        final int initiatorKeyDistribution;
        final int responderKeyDistribution;

        PairingFeatures(
                int opcode,
                int ioCapability,
                int oobDataFlag,
                int authenticationRequirements,
                int maximumEncryptionKeySize,
                int initiatorKeyDistribution,
                int responderKeyDistribution) {
            this.opcode = opcode;
            this.ioCapability = ioCapability;
            this.oobDataFlag = oobDataFlag;
            this.authenticationRequirements =
                    authenticationRequirements;
            this.maximumEncryptionKeySize =
                    maximumEncryptionKeySize;
            this.initiatorKeyDistribution =
                    initiatorKeyDistribution;
            this.responderKeyDistribution =
                    responderKeyDistribution;
        }

        byte[] encode() {
            return new byte[]{
                    (byte) opcode,
                    (byte) ioCapability,
                    (byte) oobDataFlag,
                    (byte) authenticationRequirements,
                    (byte) maximumEncryptionKeySize,
                    (byte) initiatorKeyDistribution,
                    (byte) responderKeyDistribution
            };
        }

        private static PairingFeatures parse(
                int expectedOpcode,
                byte[] pdu) {
            requireOpcodeAndLength(
                    pdu,
                    expectedOpcode,
                    7);
            return new PairingFeatures(
                    unsigned(pdu[0]),
                    unsigned(pdu[1]),
                    unsigned(pdu[2]),
                    unsigned(pdu[3]),
                    unsigned(pdu[4]),
                    unsigned(pdu[5]),
                    unsigned(pdu[6]));
        }
    }

    static final class IdentityAddress {
        final int addressType;
        final byte[] address;

        IdentityAddress(
                int addressType,
                byte[] address) {
            this.addressType = addressType;
            this.address = address.clone();
        }
    }

    static final class CentralIdentification {
        final int ediv;
        private final byte[] rand;

        CentralIdentification(int ediv, byte[] rand) {
            this.ediv = ediv;
            this.rand = rand.clone();
        }

        byte[] rand() {
            return rand.clone();
        }

        void destroy() {
            Arrays.fill(rand, (byte) 0);
        }
    }
}
