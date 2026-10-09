package dev.applewatchandroid.bridge;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple private IKE notify payloads exchanged after pairing IKE_AUTH.
 *
 * <p>The ordering, type codes, and fixed field lengths match iOS 26.6
 * {@code terminusd}. Values are deliberately exposed only as defensive
 * copies and this class has no textual formatter.</p>
 */
final class ApplePairingNotifyPayloads {
    static final int BLUETOOTH_OOB_KEY = 0xc547;
    static final int PUBLIC_CLASS_D_KEYS = 0xc548;
    static final int PUBLIC_CLASS_C_KEYS = 0xc549;
    static final int DEVICE_IDENTITY = 0xc4af;
    static final int TERMINUS_VERSION = 0xbdda;
    static final int DEVICE_NAME = 0xbddb;
    static final int BUILD_VERSION = 0xbddc;
    static final int DEVICE_TYPE = 0xbdde;
    static final int IDS_DEVICE_ID = 0xbddf;
    static final int INNER_INITIATOR_CLASS_D = 0xc671;
    static final int INNER_RESPONDER_CLASS_D = 0xc672;
    static final int INNER_INITIATOR_CLASS_C = 0xc67b;
    static final int INNER_RESPONDER_CLASS_C = 0xc67c;
    static final int ALWAYS_ON_WIFI = 0xc8c9;

    static final int CURRENT_PROTOCOL_VERSION = 24;
    static final int COMPANION_DEVICE_TYPE = 1;

    private ApplePairingNotifyPayloads() {
    }

    static LocalBatch createLocalBatch(
            BleSecureConnectionsCrypto.LocalOobMaterial oob,
            AppleNetworkRelayPairingMaterial keys,
            AppleNetworkRelayInnerAddresses addresses,
            String deviceName,
            String buildVersion,
            String idsDeviceId) {
        if (oob == null
                || keys == null
                || addresses == null) {
            throw new IllegalArgumentException(
                    "OOB, key, and address material is required");
        }
        byte[] name = encodeRequiredUtf8(
                "device name",
                deviceName);
        byte[] build = encodeRequiredUtf8(
                "build version",
                buildVersion);
        byte[] ids = idsDeviceId == null
                || idsDeviceId.isEmpty()
                ? null
                : encodeRequiredUtf8(
                        "IDS device ID",
                        idsDeviceId);
        List<PrivateNotify> notifies =
                new ArrayList<>(ids == null ? 13 : 14);
        try {
            notifies.add(new PrivateNotify(
                    BLUETOOTH_OOB_KEY,
                    oob.appleOobData()));
            notifies.add(new PrivateNotify(
                    PUBLIC_CLASS_D_KEYS,
                    keys.classDPublicKeysPayload()));
            notifies.add(new PrivateNotify(
                    PUBLIC_CLASS_C_KEYS,
                    keys.classCPublicKeysPayload()));
            notifies.add(new PrivateNotify(
                    DEVICE_IDENTITY,
                    keys.identityPayload()));
            notifies.add(new PrivateNotify(
                    TERMINUS_VERSION,
                    new byte[]{
                            0,
                            (byte) CURRENT_PROTOCOL_VERSION
                    }));
            notifies.add(new PrivateNotify(
                    DEVICE_NAME,
                    name));
            notifies.add(new PrivateNotify(
                    BUILD_VERSION,
                    build));
            notifies.add(new PrivateNotify(
                    DEVICE_TYPE,
                    new byte[]{
                            (byte) COMPANION_DEVICE_TYPE
                    }));
            notifies.add(new PrivateNotify(
                    ALWAYS_ON_WIFI,
                    new byte[]{1}));
            if (ids != null) {
                notifies.add(new PrivateNotify(
                        IDS_DEVICE_ID,
                        ids));
            }
            notifies.add(new PrivateNotify(
                    INNER_INITIATOR_CLASS_D,
                    addresses.initiatorClassD()));
            notifies.add(new PrivateNotify(
                    INNER_RESPONDER_CLASS_D,
                    addresses.responderClassD()));
            notifies.add(new PrivateNotify(
                    INNER_INITIATOR_CLASS_C,
                    addresses.initiatorClassC()));
            notifies.add(new PrivateNotify(
                    INNER_RESPONDER_CLASS_C,
                    addresses.responderClassC()));
            return new LocalBatch(notifies);
        } finally {
            wipe(name);
            wipe(build);
            wipe(ids);
            for (PrivateNotify notify : notifies) {
                notify.destroy();
            }
        }
    }

    static PeerBatch parsePeerBatch(
            List<PrivateNotify> notifies) {
        if (notifies == null || notifies.isEmpty()) {
            throw new IllegalArgumentException(
                    "Watch private-notify batch is empty");
        }
        Map<Integer, byte[]> values = new HashMap<>();
        try {
            for (PrivateNotify notify : notifies) {
                if (notify == null) {
                    throw new IllegalArgumentException(
                            "Watch private-notify batch contains null");
                }
                int type = notify.type();
                byte[] data = notify.data();
                if (isKnownPeerType(type)
                        && values.containsKey(type)) {
                    wipe(data);
                    throw new IllegalArgumentException(
                            "Watch repeated private notify 0x"
                                    + Integer.toHexString(type));
                }
                if (isKnownPeerType(type)) {
                    values.put(type, data);
                } else {
                    wipe(data);
                }
            }

            requireLength(
                    values,
                    BLUETOOTH_OOB_KEY,
                    AppleLeScOobData.SERIALIZED_LENGTH);
            requireModernKeys(
                    values,
                    PUBLIC_CLASS_D_KEYS);
            requireModernKeys(
                    values,
                    PUBLIC_CLASS_C_KEYS);
            requireLength(
                    values,
                    DEVICE_IDENTITY,
                    AppleNetworkRelayPairingMaterial
                            .IDENTITY_PAYLOAD_LENGTH);
            byte[] version = requireMinimumLength(
                    values,
                    TERMINUS_VERSION,
                    2);
            byte[] name = requirePresent(
                    values,
                    DEVICE_NAME);
            byte[] build = requirePresent(
                    values,
                    BUILD_VERSION);
            requireLength(values, DEVICE_TYPE, 1);
            requireLength(values, ALWAYS_ON_WIFI, 1);
            decodeUtf8("Watch device name", name);
            decodeUtf8("Watch build version", build);
            if (values.containsKey(IDS_DEVICE_ID)) {
                decodeUtf8(
                        "Watch IDS device ID",
                        values.get(IDS_DEVICE_ID));
            }

            AppleLeScOobData parsedOob =
                    AppleLeScOobData.parse(
                            values.get(BLUETOOTH_OOB_KEY));
            int protocolVersion =
                    ((version[0] & 0xff) << 8)
                            | (version[1] & 0xff);
            return new PeerBatch(
                    parsedOob,
                    values.get(PUBLIC_CLASS_D_KEYS),
                    values.get(PUBLIC_CLASS_C_KEYS),
                    values.get(DEVICE_IDENTITY),
                    protocolVersion,
                    decodeUtf8(
                            "Watch device name",
                            name),
                    decodeUtf8(
                            "Watch build version",
                            build),
                    values.get(DEVICE_TYPE)[0] & 0xff,
                    values.get(ALWAYS_ON_WIFI)[0] != 0,
                    values.containsKey(IDS_DEVICE_ID)
                            ? decodeUtf8(
                                    "Watch IDS device ID",
                                    values.get(IDS_DEVICE_ID))
                            : null);
        } finally {
            for (byte[] value : values.values()) {
                wipe(value);
            }
        }
    }

    static List<PrivateNotify> createResponderAddressNotifies(
            AppleNetworkRelayInnerAddresses addresses) {
        if (addresses == null) {
            throw new IllegalArgumentException(
                    "Authoritative inner addresses are required");
        }
        List<PrivateNotify> output = new ArrayList<>(4);
        output.add(new PrivateNotify(
                INNER_INITIATOR_CLASS_D,
                addresses.initiatorClassD()));
        output.add(new PrivateNotify(
                INNER_RESPONDER_CLASS_D,
                addresses.responderClassD()));
        output.add(new PrivateNotify(
                INNER_INITIATOR_CLASS_C,
                addresses.initiatorClassC()));
        output.add(new PrivateNotify(
                INNER_RESPONDER_CLASS_C,
                addresses.responderClassC()));
        return List.copyOf(output);
    }

    static AppleNetworkRelayInnerAddresses
            parseResponderAddressNotifies(
            List<PrivateNotify> notifies,
            NrLinkBluetoothPrelude.LocalRole localRole) {
        if (localRole
                != NrLinkBluetoothPrelude.LocalRole.INITIATOR) {
            throw new IllegalArgumentException(
                    "Only an IKE initiator may accept responder "
                            + "inner-address notifies");
        }
        if (notifies == null || notifies.isEmpty()) {
            throw new IllegalArgumentException(
                    "Responder inner-address notify batch is empty");
        }
        Map<Integer, byte[]> values = new HashMap<>();
        try {
            for (PrivateNotify notify : notifies) {
                if (notify == null) {
                    throw new IllegalArgumentException(
                            "Responder notify batch contains null");
                }
                int type = notify.type();
                if (!isAddressType(type)) {
                    continue;
                }
                byte[] data = notify.data();
                if (values.putIfAbsent(type, data) != null) {
                    wipe(data);
                    throw new IllegalArgumentException(
                            "Responder repeated inner-address notify 0x"
                                    + Integer.toHexString(type));
                }
            }
            requireLength(
                    values,
                    INNER_INITIATOR_CLASS_D,
                    AppleNetworkRelayInnerAddresses.ADDRESS_LENGTH);
            requireLength(
                    values,
                    INNER_RESPONDER_CLASS_D,
                    AppleNetworkRelayInnerAddresses.ADDRESS_LENGTH);
            requireLength(
                    values,
                    INNER_INITIATOR_CLASS_C,
                    AppleNetworkRelayInnerAddresses.ADDRESS_LENGTH);
            requireLength(
                    values,
                    INNER_RESPONDER_CLASS_C,
                    AppleNetworkRelayInnerAddresses.ADDRESS_LENGTH);
            return AppleNetworkRelayInnerAddresses.fromAuthoritative(
                    values.get(INNER_INITIATOR_CLASS_D),
                    values.get(INNER_RESPONDER_CLASS_D),
                    values.get(INNER_INITIATOR_CLASS_C),
                    values.get(INNER_RESPONDER_CLASS_C));
        } finally {
            for (byte[] value : values.values()) {
                wipe(value);
            }
        }
    }

    private static boolean isKnownPeerType(int type) {
        return type == BLUETOOTH_OOB_KEY
                || type == PUBLIC_CLASS_D_KEYS
                || type == PUBLIC_CLASS_C_KEYS
                || type == DEVICE_IDENTITY
                || type == TERMINUS_VERSION
                || type == DEVICE_NAME
                || type == BUILD_VERSION
                || type == DEVICE_TYPE
                || type == ALWAYS_ON_WIFI
                || type == IDS_DEVICE_ID;
    }

    private static boolean isAddressType(int type) {
        return type == INNER_INITIATOR_CLASS_D
                || type == INNER_RESPONDER_CLASS_D
                || type == INNER_INITIATOR_CLASS_C
                || type == INNER_RESPONDER_CLASS_C;
    }

    private static void requireModernKeys(
            Map<Integer, byte[]> values,
            int type) {
        byte[] payload = requirePresent(values, type);
        if (payload.length
                != AppleNetworkRelayPairingMaterial
                .MODERN_PUBLIC_KEYS_PAYLOAD_LENGTH
                || payload[0] != 1
                || payload[1] != 0
                || payload[2] != 32
                || payload[35] != 2
                || payload[36] != 0
                || payload[37] != 32) {
            throw new IllegalArgumentException(
                    "Watch modern public-key notify 0x"
                            + Integer.toHexString(type)
                            + " has an invalid NRTLV layout");
        }
    }

    private static void requireLength(
            Map<Integer, byte[]> values,
            int type,
            int length) {
        byte[] value = requirePresent(values, type);
        if (value.length != length) {
            throw new IllegalArgumentException(
                    "Watch private notify 0x"
                            + Integer.toHexString(type)
                            + " must be "
                            + length
                            + " bytes");
        }
    }

    private static byte[] requireMinimumLength(
            Map<Integer, byte[]> values,
            int type,
            int length) {
        byte[] value = requirePresent(values, type);
        if (value.length < length) {
            throw new IllegalArgumentException(
                    "Watch private notify 0x"
                            + Integer.toHexString(type)
                            + " is too short");
        }
        return value;
    }

    private static byte[] requirePresent(
            Map<Integer, byte[]> values,
            int type) {
        byte[] value = values.get(type);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Watch lacks private notify 0x"
                            + Integer.toHexString(type));
        }
        return value;
    }

    private static byte[] encodeRequiredUtf8(
            String label,
            String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
        byte[] encoded =
                value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > 254) {
            wipe(encoded);
            throw new IllegalArgumentException(
                    label + " must encode to 1..254 UTF-8 bytes");
        }
        return encoded;
    }

    private static String decodeUtf8(
            String label,
            byte[] value) {
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException(
                    label + " is empty");
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                            CodingErrorAction.REPORT)
                    .onUnmappableCharacter(
                            CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException(
                    label + " is not valid UTF-8",
                    error);
        }
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    static final class PrivateNotify {
        private final int type;
        private final byte[] data;
        private boolean destroyed;

        PrivateNotify(int type, byte[] data) {
            if (type < 0 || type > 0xffff || data == null) {
                throw new IllegalArgumentException(
                        "Private notify type and data are required");
            }
            this.type = type;
            this.data = data.clone();
        }

        int type() {
            requireLive();
            return type;
        }

        byte[] data() {
            requireLive();
            return data.clone();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            wipe(data);
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Private notify has been destroyed");
            }
        }
    }

    static final class LocalBatch {
        private final List<PrivateNotify> notifies;
        private boolean destroyed;

        private LocalBatch(
                List<PrivateNotify> source) {
            List<PrivateNotify> copied =
                    new ArrayList<>(source.size());
            for (PrivateNotify notify : source) {
                copied.add(new PrivateNotify(
                        notify.type(),
                        notify.data()));
            }
            notifies = List.copyOf(copied);
        }

        List<PrivateNotify> notifies() {
            requireLive();
            List<PrivateNotify> copied =
                    new ArrayList<>(notifies.size());
            for (PrivateNotify notify : notifies) {
                copied.add(new PrivateNotify(
                        notify.type(),
                        notify.data()));
            }
            return List.copyOf(copied);
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            for (PrivateNotify notify : notifies) {
                notify.destroy();
            }
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Local private-notify batch "
                                + "has been destroyed");
            }
        }
    }

    static final class PeerBatch {
        private final AppleLeScOobData oob;
        private final byte[] classDPublicKeys;
        private final byte[] classCPublicKeys;
        private final byte[] identity;
        final int protocolVersion;
        final String deviceName;
        final String buildVersion;
        final int deviceType;
        final boolean alwaysOnWifi;
        final String idsDeviceId;
        private boolean destroyed;

        private PeerBatch(
                AppleLeScOobData oob,
                byte[] classDPublicKeys,
                byte[] classCPublicKeys,
                byte[] identity,
                int protocolVersion,
                String deviceName,
                String buildVersion,
                int deviceType,
                boolean alwaysOnWifi,
                String idsDeviceId) {
            this.oob = AppleLeScOobData.parse(
                    oob.serialize());
            this.classDPublicKeys =
                    classDPublicKeys.clone();
            this.classCPublicKeys =
                    classCPublicKeys.clone();
            this.identity = identity.clone();
            this.protocolVersion = protocolVersion;
            this.deviceName = deviceName;
            this.buildVersion = buildVersion;
            this.deviceType = deviceType;
            this.alwaysOnWifi = alwaysOnWifi;
            this.idsDeviceId = idsDeviceId;
            oob.destroy();
        }

        byte[] appleOobData() {
            requireLive();
            return oob.serialize();
        }

        byte[] classDPublicKeys() {
            requireLive();
            return classDPublicKeys.clone();
        }

        byte[] classCPublicKeys() {
            requireLive();
            return classCPublicKeys.clone();
        }

        byte[] identity() {
            requireLive();
            return identity.clone();
        }

        void destroy() {
            if (destroyed) {
                return;
            }
            oob.destroy();
            wipe(classDPublicKeys);
            wipe(classCPublicKeys);
            wipe(identity);
            destroyed = true;
        }

        private void requireLive() {
            if (destroyed) {
                throw new IllegalStateException(
                        "Watch private-notify material "
                                + "has been destroyed");
            }
        }
    }
}
