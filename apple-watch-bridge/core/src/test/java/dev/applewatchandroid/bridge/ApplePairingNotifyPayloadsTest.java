package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class ApplePairingNotifyPayloadsTest {
    @Test
    public void buildsExactCompanionNotifyOrderAndParsesPeerMaterial() {
        SecureRandom random = new SecureRandom();
        BleSecureConnectionsCrypto.LocalOobMaterial oob =
                BleSecureConnectionsCrypto.generateLocalOob(
                        random);
        AppleNetworkRelayPairingMaterial keys =
                AppleNetworkRelayPairingMaterial.generate(
                        random);
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(
                        random);
        ApplePairingNotifyPayloads.LocalBatch batch =
                ApplePairingNotifyPayloads.createLocalBatch(
                        oob,
                        keys,
                        addresses,
                        "OnePlus 13",
                        "CPH2653_15",
                        null);
        List<ApplePairingNotifyPayloads.PrivateNotify>
                notifies = batch.notifies();
        try {
            assertEquals(
                    List.of(
                            0xc547,
                            0xc548,
                            0xc549,
                            0xc4af,
                            0xbdda,
                            0xbddb,
                            0xbddc,
                            0xbdde,
                            0xc8c9,
                            0xc671,
                            0xc672,
                            0xc67b,
                            0xc67c),
                    types(notifies));
            assertEquals(34, notifies.get(0).data().length);
            assertEquals(70, notifies.get(1).data().length);
            assertEquals(70, notifies.get(2).data().length);
            assertEquals(48, notifies.get(3).data().length);
            assertArrayEquals(
                    new byte[]{0, 24},
                    notifies.get(4).data());
            assertArrayEquals(
                    new byte[]{1},
                    notifies.get(7).data());
            assertArrayEquals(
                    new byte[]{1},
                    notifies.get(8).data());
            assertArrayEquals(
                    addresses.initiatorClassD(),
                    notifies.get(9).data());
            assertArrayEquals(
                    addresses.responderClassD(),
                    notifies.get(10).data());
            assertArrayEquals(
                    addresses.initiatorClassC(),
                    notifies.get(11).data());
            assertArrayEquals(
                    addresses.responderClassC(),
                    notifies.get(12).data());

            List<ApplePairingNotifyPayloads.PrivateNotify>
                    peer = new ArrayList<>(
                    notifies.subList(0, 9));
            ApplePairingNotifyPayloads.PeerBatch parsed =
                    ApplePairingNotifyPayloads.parsePeerBatch(
                            peer);
            assertEquals(24, parsed.protocolVersion);
            assertEquals("OnePlus 13", parsed.deviceName);
            assertEquals("CPH2653_15", parsed.buildVersion);
            assertEquals(1, parsed.deviceType);
            assertTrue(parsed.alwaysOnWifi);
            assertNull(parsed.idsDeviceId);
            assertArrayEquals(
                    oob.appleOobData(),
                    parsed.appleOobData());
            parsed.destroy();
            assertThrows(
                    IllegalStateException.class,
                    parsed::appleOobData);
        } finally {
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                notify.destroy();
            }
            batch.destroy();
            addresses.destroy();
            keys.destroy();
            oob.destroy();
        }
    }

    @Test
    public void includesOptionalIdsAtRecoveredPosition() {
        SecureRandom random = new SecureRandom();
        BleSecureConnectionsCrypto.LocalOobMaterial oob =
                BleSecureConnectionsCrypto.generateLocalOob(
                        random);
        AppleNetworkRelayPairingMaterial keys =
                AppleNetworkRelayPairingMaterial.generate(
                        random);
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(
                        random);
        ApplePairingNotifyPayloads.LocalBatch batch =
                ApplePairingNotifyPayloads.createLocalBatch(
                        oob,
                        keys,
                        addresses,
                        "Android",
                        "test",
                        "ids-test");
        List<ApplePairingNotifyPayloads.PrivateNotify>
                notifies = batch.notifies();
        try {
            assertEquals(14, notifies.size());
            assertEquals(
                    ApplePairingNotifyPayloads.IDS_DEVICE_ID,
                    notifies.get(9).type());
            assertEquals(
                    ApplePairingNotifyPayloads
                            .INNER_INITIATOR_CLASS_D,
                    notifies.get(10).type());
            assertFalse(
                    notifies.get(9).data().length == 0);
        } finally {
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                notify.destroy();
            }
            batch.destroy();
            addresses.destroy();
            keys.destroy();
            oob.destroy();
        }
    }

    @Test
    public void responderAddressesRoundTripWithRoleEnforcement() {
        AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(
                        new SecureRandom());
        List<ApplePairingNotifyPayloads.PrivateNotify>
                notifies =
                ApplePairingNotifyPayloads
                        .createResponderAddressNotifies(
                                addresses);
        AppleNetworkRelayInnerAddresses parsed = null;
        try {
            assertEquals(
                    List.of(0xc671, 0xc672, 0xc67b, 0xc67c),
                    types(notifies));
            parsed =
                    ApplePairingNotifyPayloads
                            .parseResponderAddressNotifies(
                                    notifies,
                                    NrLinkBluetoothPrelude
                                            .LocalRole.INITIATOR);
            assertArrayEquals(
                    addresses.initiatorClassD(),
                    parsed.initiatorClassD());
            assertArrayEquals(
                    addresses.responderClassD(),
                    parsed.responderClassD());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ApplePairingNotifyPayloads
                            .parseResponderAddressNotifies(
                                    notifies,
                                    NrLinkBluetoothPrelude
                                            .LocalRole.RESPONDER));
        } finally {
            if (parsed != null) {
                parsed.destroy();
            }
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : notifies) {
                notify.destroy();
            }
            addresses.destroy();
        }
    }

    private static List<Integer> types(
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    notifies) {
        List<Integer> types =
                new ArrayList<>(notifies.size());
        for (ApplePairingNotifyPayloads.PrivateNotify notify
                : notifies) {
            types.add(notify.type());
        }
        return List.copyOf(types);
    }
}
