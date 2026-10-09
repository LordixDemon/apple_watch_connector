package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

public final class AppleWatchInitialSetupIdsAdapterTest {
    @Test public void explicitArrayFlagIsNotMistakenForAnAbsentLocaleProperty() {
        // Native protobuf field 8=false means NSArray, not an unset property.
        byte[] wire = java.util.HexFormat.of().parseHex(
                "0a210a127072656665727265644c616e677561676573120b3a070a05756b2d55414000");
        var response = NanoRegistryPropertyCodec.decodePropertyResponse(wire);
        var adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        try {
            assertTrue(response.properties.get(0).value.hasIsSet);
            assertFalse(response.properties.get(0).value.isSet);
            adapter.acceptPropertyResponse(response);
            var current = new NanoRegistryPropertyCodec.PropertiesChanged(false, List.of(
                    new NanoRegistryPropertyCodec.Property("currentUserLocale",
                            NanoRegistryPropertyCodec.PropertyValue.string("uk_UA"))), null);
            var identity = watchSnapshot(false, "Watch7,5", 0x8310);
            try { adapter.acceptClassC(current); adapter.acceptClassC(identity); }
            finally { current.destroy(); identity.destroy(); }
            assertEquals(List.of("uk-UA"), adapter.watchLocaleSnapshot().languages());
        } finally { response.destroy(); adapter.close(); java.util.Arrays.fill(wire, (byte) 0); }
    }
    @Test public void localeIsRetainedOnlyFromTheAuthenticatedWatchIdentityAndSurvivesMessageClose() {
        var adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        var locale = new NanoRegistryPropertyCodec.PropertiesChanged(false, List.of(
                new NanoRegistryPropertyCodec.Property("preferredLanguages", NanoRegistryPropertyCodec.PropertyValue.array(List.of(
                        NanoRegistryPropertyCodec.PropertyValue.string("uk-UA")))),
                new NanoRegistryPropertyCodec.Property("currentUserLocale", NanoRegistryPropertyCodec.PropertyValue.string("uk_UA"))), null);
        try {
            assertTrue(adapter.acceptClassC(locale).isEmpty());
            assertThrows(IllegalStateException.class, adapter::watchLocaleSnapshot);
        } finally { locale.destroy(); }
        var identity = watchSnapshot(false, "Watch7,5", 0x8310);
        try { adapter.acceptClassC(identity); } finally { identity.destroy(); }
        try {
            assertEquals(new WatchLocaleSnapshot(List.of("uk-UA"), "uk_UA"), adapter.watchLocaleSnapshot());
        } finally { adapter.close(); }
    }
    @Test public void identityWithoutLocaleCannotCauseAnAutomaticPhoneLanguageUpdate() {
        var adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        var identity = watchSnapshot(true, "Watch7,5", 0x8310);
        try { adapter.acceptClassC(identity); } finally { identity.destroy(); }
        try { assertThrows(IllegalArgumentException.class, adapter::watchLocaleSnapshot); }
        finally { adapter.close(); }
    }
    @Test
    public void dataPathPublishesPairedSyncCapabilityOnlyAfterObservedIdentity() {
        var adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        assertEquals(PairedSyncCapabilityState.Status.UNKNOWN, adapter.pairedSyncCapabilityStatus());
        assertFalse(adapter.snapshot().idsAccountAndDevicePresent);
        var source = watchSnapshot(true, "Watch7,5", 0x8310);
        var properties = new java.util.ArrayList<>(source.properties.subList(0, 3));
        properties.add(new NanoRegistryPropertyCodec.Property("capabilities",
                NanoRegistryPropertyCodec.PropertyValue.set(List.of(
                        NanoRegistryPropertyCodec.PropertyValue.uuid(uuidBytes(
                                AppleWatchSetupCoordinator.PAIRING_SESSION_ID_CAPABILITY)),
                        NanoRegistryPropertyCodec.PropertyValue.uuid(PairedSyncCapabilityState.capabilityUuid())))));
        var complete = new NanoRegistryPropertyCodec.PropertiesChanged(true, properties, null);
        try { adapter.acceptClassC(complete); }
        finally { complete.destroy(); source.destroy(); }
        try {
            assertEquals(PairedSyncCapabilityState.Status.PRESENT, adapter.pairedSyncCapabilityStatus());
            assertTrue(adapter.snapshot().idsAccountAndDevicePresent);
        } finally { adapter.close(); }
    }

    @Test
    public void capabilitiesSurviveDestructionOfEarlierPartialMessage() {
        AppleWatchInitialSetupIdsAdapter adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        NanoRegistryPropertyCodec.PropertiesChanged source = watchSnapshot(false, "Watch7,5", 0x8310);
        NanoRegistryPropertyCodec.PropertiesChanged capabilities = new NanoRegistryPropertyCodec.PropertiesChanged(
                false, List.of(source.properties.get(3)), null);
        assertTrue(adapter.acceptClassC(capabilities).isEmpty());
        capabilities.destroy();
        assertFalse(adapter.snapshot().identityValidated);
        NanoRegistryPropertyCodec.PropertiesChanged identity = new NanoRegistryPropertyCodec.PropertiesChanged(
                false, source.properties.subList(0, 3), null);
        try {
            assertEquals(2, adapter.acceptClassC(identity).size());
            assertTrue(adapter.snapshot().identityValidated);
        } finally {
            identity.destroy();
            source.destroy();
        }
    }

    @Test
    public void invalidCapabilitiesAreRejectedInsteadOfReplacingWithExpectedValues() {
        AppleWatchInitialSetupIdsAdapter adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        NanoRegistryPropertyCodec.PropertiesChanged source = watchSnapshot(true, "Watch7,5", 0x8310);
        java.util.ArrayList<NanoRegistryPropertyCodec.Property> properties =
                new java.util.ArrayList<>(source.properties.subList(0, 3));
        properties.add(new NanoRegistryPropertyCodec.Property("capabilities",
                NanoRegistryPropertyCodec.PropertyValue.data(new byte[] {1, 2, 3})));
        NanoRegistryPropertyCodec.PropertiesChanged invalid =
                new NanoRegistryPropertyCodec.PropertiesChanged(true, properties, null);
        try {
            assertThrows(IllegalArgumentException.class, () -> adapter.acceptClassC(invalid));
            assertFalse(adapter.snapshot().identityValidated);
            assertFalse(adapter.snapshot().idsAccountAndDevicePresent);
        } finally {
            invalid.destroy();
            source.destroy();
        }
    }

    @Test
    public void unrelatedOrWrongLaneAcknowledgementsDoNotAdvanceSetup() {
        AppleWatchInitialSetupIdsAdapter adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        adapter.onPhoneMessageQueued(
                AppleWatchInitialSetupIdsAdapter.ActionType.SEND_COMPATIBILITY_STATE, 41);
        String classD = NanoRegistryPropertyCodec.CLASS_D_SERVICE;
        String classC = NanoRegistryPropertyCodec.CLASS_C_SERVICE;
        assertTrue(adapter.onAcknowledgement(classD, 40).isEmpty());
        assertTrue(adapter.onAcknowledgement(classC, 41).isEmpty());
        assertFalse(adapter.snapshotSent());
        assertEquals(AppleWatchInitialSetupIdsAdapter.ActionType.SEND_PHONE_FULL_SNAPSHOT,
                adapter.onAcknowledgement(classD, 41).get(0).type);
        assertTrue(adapter.onAcknowledgement(classD, 41).isEmpty());
        adapter.onPhoneMessageQueued(
                AppleWatchInitialSetupIdsAdapter.ActionType.SEND_PHONE_FULL_SNAPSHOT, 42);
        assertTrue(adapter.onAcknowledgement(classC, 43).isEmpty());
        assertFalse(adapter.phoneSnapshotAckObserved());
        adapter.onAcknowledgement(classC, 42);
        assertTrue(adapter.phoneSnapshotAckObserved());
        assertFalse(adapter.snapshot().identityValidated);
    }

    @Test
    public void dataReadySendsCheckAndMinimalCanonicalPhoneSnapshot() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);

        List<AppleWatchInitialSetupIdsAdapter.Action> actions =
                adapter.startAfterIdsDataReady();

        assertTypes(
                actions,
                AppleWatchInitialSetupIdsAdapter.ActionType
                        .SEND_COMPATIBILITY_STATE);
        assertEquals(
                Integer.valueOf(
                        NanoRegistryClassDCodec
                                .COMPATIBILITY_STATE_CHECK),
                actions.get(
                        0).compatibilityState);
        byte[] pairingMode =
                NanoRegistryClassDCodec.encode(
                        actions.get(
                                0).compatibilityMessage());
        try {
            assertEquals(
                    "0801101a18192019",
                    hex(
                            pairingMode));
        } finally {
            wipe(
                    pairingMode);
        }

        List<AppleWatchInitialSetupIdsAdapter.Action> snapshotActions =
                adapter.onPhoneCheckAcknowledged();
        assertTypes(
                snapshotActions,
                AppleWatchInitialSetupIdsAdapter.ActionType
                        .SEND_PHONE_FULL_SNAPSHOT);
        assertTrue(
                adapter.onPhoneCheckAcknowledged().isEmpty());

        NanoRegistryPropertyCodec.PropertiesChanged phone =
                snapshotActions.get(
                        0).phoneFullSnapshot(
                                978_307_201_250L,
                                staticRandomMac());
        try {
            assertTrue(
                    phone.thisIsAllOfThem);
            assertEquals(
                    Double.valueOf(
                            1.25),
                    phone.bornOn);
            assertEquals(
                    60,
                    phone.properties.size());
            assertEquals(
                    IosCompanionProfile26_6.MODEL,
                    phone.properties.get(
                            20).value.stringValue);
            assertArrayEquals(
                    staticRandomMac(),
                    phone.properties.get(
                            16).value.dataValue);
            assertEquals(
                    "bluetoothMACAddress",
                    phone.properties.get(
                            16).name);
            assertEquals(
                    23,
                    phone.properties.stream()
                            .filter(property -> property.value != null)
                            .count());
            assertEquals(
                    IosCompanionProfile26_6.HW_MODEL_STRING,
                    phone.properties.get(
                            26).value.stringValue);
            assertEquals(
                    IosCompanionProfile26_6.CHIP_ID,
                    phone.properties.get(
                            57).value.numberValue.int64Value.longValue());
            assertEquals(
                    IosCompanionProfile26_6.MARKETING_PRODUCT_NAME,
                    phone.properties.get(
                            53).value.stringValue);
            assertEquals(
                    Integer.valueOf(
                            IosCompanionProfile26_6.SCREEN_SCALE),
                    phone.properties.get(
                            32).value.numberValue.int32Value);
            assertEquals(
                    Integer.valueOf(
                            IosCompanionProfile26_6.RELEASE_TYPE),
                    phone.properties.get(
                            33).value.numberValue.int32Value);
            java.util.Set<String> capabilities =
                    NanoRegistryUuidSetCodec.decode(
                            phone.properties.get(
                                    48).value);
            assertEquals(
                    IosCompanionProfile26_6.PHONE_CAPABILITY_UUIDS.length,
                    capabilities.size());
            assertTrue(
                    capabilities.contains(
                            AppleWatchSetupCoordinator
                                    .PAIRING_SESSION_ID_CAPABILITY));
            assertTrue(
                    capabilities.contains(
                            "36a0eb23-e045-4e99-9d71-8fb9a853ada7"));
            byte[] payload =
                    NanoRegistryPropertyCodec.encode(
                            phone);
            IdsSocketPairCodec.ProtobufMessage envelope =
                    new IdsSocketPairCodec.ProtobufMessage(
                            0,
                            1,
                            IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE
                                    | IdsSocketPairCodec.FLAG_HAS_TOPIC,
                            null,
                            "00112233-4455-6677-8899-aabbccddeeff",
                            NanoRegistryPropertyCodec.CLASS_C_SERVICE,
                            NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED,
                            false,
                            payload,
                            null);
            byte[] frame =
                    IdsSocketPairCodec.encodeProtobuf(
                            envelope);
            try {
                assertTrue(
                        frame.length
                                > IdsSocketPairCodec.FRAGMENT_FRAME_LIMIT);
                List<byte[]> fragments =
                        IdsSocketPairCodec.fragment(
                                0,
                                frame);
                try {
                    assertTrue(
                            fragments.size() >= 2);
                } finally {
                    for (byte[] fragment : fragments) {
                        wipe(
                                fragment);
                    }
                }
            } finally {
                wipe(
                        frame);
                wipe(
                        payload);
                envelope.destroy();
            }
        } finally {
            phone.destroy();
        }

        assertTrue(
                adapter.startAfterIdsDataReady().isEmpty());
        assertFalse(
                adapter.snapshot().transportCompletionIssued);
        assertFalse(
                adapter.snapshot().identityValidated);
    }

    @Test
    public void credentialsAfterSnapshotAckDoNotInventWatchProperties() {
        AppleWatchInitialSetupIdsAdapter adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        adapter.onPhoneCheckAcknowledged();
        adapter.onPhoneSnapshotAcknowledged();
        adapter.onWatchCredentialsReceived();
        assertTrue(adapter.phoneSnapshotAckObserved());
        assertTrue(adapter.watchCredentialsReceived());
        assertFalse(adapter.snapshot().transportCompletionIssued);
        assertFalse(adapter.snapshot().identityValidated);
    }

    @Test
    public void snapshotAckAfterCredentialsDoesNotInventWatchProperties() {
        AppleWatchInitialSetupIdsAdapter adapter = adapter(25);
        adapter.startAfterIdsDataReady();
        adapter.onPhoneCheckAcknowledged();
        adapter.onWatchCredentialsReceived();
        adapter.onPhoneSnapshotAcknowledged();
        assertTrue(adapter.phoneSnapshotAckObserved());
        assertTrue(adapter.watchCredentialsReceived());
        assertFalse(adapter.snapshot().transportCompletionIssued);
        assertFalse(adapter.snapshot().identityValidated);
    }

    @Test
    public void classCAckRequestsWatchPropertiesExactlyOnceAfterPhoneSnapshot() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);
        adapter.startAfterIdsDataReady();
        assertTrue(
                adapter.onPhoneSnapshotAcknowledged().isEmpty());
        adapter.onPhoneCheckAcknowledged();

        List<AppleWatchInitialSetupIdsAdapter.Action> request = adapter.onPhoneSnapshotAcknowledged();
        assertEquals(1, request.size());
        assertEquals(AppleWatchInitialSetupIdsAdapter.ActionType.SEND_PROPERTY_REQUEST, request.get(0).type);
        assertTrue(
                adapter.phoneSnapshotAckObserved());
        assertTrue(
                adapter.onPhoneSnapshotAcknowledged().isEmpty());
        assertFalse(
                adapter.snapshot().identityValidated);
        assertFalse(
                adapter.snapshot().transportCompletionIssued);
    }

    @Test
    public void phoneSnapshotRejectsNonStaticRandomBluetoothMac() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);
        adapter.startAfterIdsDataReady();
        AppleWatchInitialSetupIdsAdapter.Action snapshot =
                adapter.onPhoneCheckAcknowledged().get(
                        0);
        assertThrows(
                IllegalArgumentException.class,
                () -> snapshot.phoneFullSnapshot(
                        1L,
                        hexBytes(
                                "112233445586")));
    }

    @Test
    public void classCAckDoesNotPollAfterWatchIdentityCompletes() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);
        adapter.startAfterIdsDataReady();
        NanoRegistryPropertyCodec.PropertiesChanged watch =
                watchSnapshot(
                        true,
                        "Watch7,5",
                        0x8310);
        try {
            adapter.acceptClassC(
                    watch);
            assertTrue(
                    adapter.onPhoneSnapshotAcknowledged().isEmpty());
        } finally {
            watch.destroy();
        }
    }

    @Test
    public void partialIdentityDumpCompletesTransportWithoutThisIsAllOfThem() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);
        adapter.startAfterIdsDataReady();
        NanoRegistryPropertyCodec.PropertiesChanged watch =
                watchSnapshot(
                        false,
                        "Watch7,5",
                        0x8310);
        try {
            List<AppleWatchInitialSetupIdsAdapter.Action> afterWatch =
                    adapter.acceptClassC(
                            watch);
            assertTypes(
                    afterWatch,
                    AppleWatchInitialSetupIdsAdapter.ActionType
                            .SEND_COMPATIBILITY_STATE,
                    AppleWatchInitialSetupIdsAdapter.ActionType
                            .PAIRING_TRANSPORT_COMPLETE);
            assertTrue(
                    adapter.snapshot().transportCompletionIssued);
        } finally {
            watch.destroy();
        }
    }

    @Test
    public void fullUltra2SnapshotCompletesTransportAfterWatchClassC() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);
        List<AppleWatchInitialSetupIdsAdapter.Action> start =
                adapter.startAfterIdsDataReady();
        assertTypes(
                start,
                AppleWatchInitialSetupIdsAdapter.ActionType
                        .SEND_COMPATIBILITY_STATE);
        assertFalse(
                adapter.snapshot().transportCompletionIssued);
        assertFalse(
                adapter.snapshot().initialPropertiesReceived);

        NanoRegistryPropertyCodec.PropertiesChanged watch =
                watchSnapshot(
                        true,
                        "Watch7,5",
                        0x8310);
        try {
            List<AppleWatchInitialSetupIdsAdapter.Action> afterWatch =
                    adapter.acceptClassC(
                            watch);
            assertTypes(
                    afterWatch,
                    AppleWatchInitialSetupIdsAdapter.ActionType
                            .SEND_COMPATIBILITY_STATE,
                    AppleWatchInitialSetupIdsAdapter.ActionType
                            .PAIRING_TRANSPORT_COMPLETE);
            assertEquals(
                    Integer.valueOf(
                            NanoRegistryClassDCodec
                                    .COMPATIBILITY_STATE_CONFIGURE),
                    afterWatch.get(
                            0).compatibilityState);
            assertEquals(
                    AppleWatchSetupCoordinator.Phase.TRANSPORT_COMPLETE,
                    adapter.snapshot().phase);
            assertTrue(
                    adapter.snapshot().initialPropertiesReceived);
            assertTrue(
                    adapter.snapshot().transportCompletionIssued);
            assertTrue(
                    adapter.snapshot().identityValidated);
            assertFalse(
                    adapter.snapshot().phase
                            == AppleWatchSetupCoordinator.Phase.NORMAL);
            assertTrue(
                    adapter.acceptClassC(
                            watch).isEmpty());
        } finally {
            watch.destroy();
        }
    }

    @Test
    public void propertyResponseCompletesTransportWhenIdentityIsPresent() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);
        adapter.startAfterIdsDataReady();

        NanoRegistryPropertyCodec.PropertiesChanged watch =
                watchSnapshot(
                        true,
                        "Watch7,5",
                        0x8310);
        NanoRegistryPropertyCodec.PropertyResponse response =
                new NanoRegistryPropertyCodec.PropertyResponse(
                        watch.properties);
        try {
            List<AppleWatchInitialSetupIdsAdapter.Action> afterWatch =
                    adapter.acceptPropertyResponse(
                            response);
            assertTypes(
                    afterWatch,
                    AppleWatchInitialSetupIdsAdapter.ActionType
                            .SEND_COMPATIBILITY_STATE,
                    AppleWatchInitialSetupIdsAdapter.ActionType
                            .PAIRING_TRANSPORT_COMPLETE);
            assertTrue(
                    adapter.snapshot().transportCompletionIssued);
        } finally {
            watch.destroy();
        }
    }

    @Test
    public void watchPropertyRequestRepliesWithPhoneMiniStore() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(
                        25);
        adapter.startAfterIdsDataReady();
        String requestId =
                "00112233-4455-6677-8899-aabbccddeeff";
        List<AppleWatchInitialSetupIdsAdapter.Action> reply =
                adapter.onWatchPropertyRequest(
                        requestId);
        assertTypes(
                reply,
                AppleWatchInitialSetupIdsAdapter.ActionType
                        .SEND_PHONE_PROPERTY_RESPONSE);
        assertEquals(
                requestId,
                reply.get(
                        0).outgoingResponseIdentifier);
        NanoRegistryPropertyCodec.PropertyResponse response =
                reply.get(
                        0).propertyResponse(
                                staticRandomMac());
        NanoRegistryPropertyCodec.PropertyResponse live =
                AppleWatchInitialSetupIdsAdapter.Action.livePhonePropertyResponse(
                        staticRandomMac());
        try {
            assertTrue(live.response());
            assertEquals(response.properties.size(), live.properties.size());
        } finally {
            live.destroy();
        }
        try {
            assertTrue(
                    response.response());
            assertEquals(
                    NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST,
                    response.protobufType());
            assertEquals(
                    60,
                    response.properties.size());
            java.util.Set<String> capabilities =
                    NanoRegistryUuidSetCodec.decode(
                            response.properties.get(
                                    48).value);
            assertTrue(
                    capabilities.contains(
                            AppleWatchSetupCoordinator
                                    .PAIRING_SESSION_ID_CAPABILITY));
        } finally {
            response.destroy();
        }
    }

    @Test
    public void pbBridgeFinishedActivatingIsNotAPropertyRequest() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(25);
        adapter.startAfterIdsDataReady();
        IdsSocketPairCodec.ProtobufMessage message =
                new IdsSocketPairCodec.ProtobufMessage(
                        1,
                        0,
                        0,
                        null,
                        "00112233-4455-6677-8899-aabbccddeeff",
                        null,
                        PbBridgeCodec.TYPE_ACTIVATION_SUCCEEDED,
                        false,
                        new byte[0],
                        null);
        IdsModernSessionCoordinator.SessionEvent event =
                IdsModernSessionCoordinator.SessionEvent.protobuf(
                        IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,
                        1,
                        "nano-class-c",
                        IdsApplicationRoute.PB_BRIDGE_SERVICE,
                        message);
        assertTrue(adapter.accept(event).isEmpty());
    }

    @Test
    public void mismatchedVersionFailsClosed() {
        AppleWatchInitialSetupIdsAdapter wrongVersion =
                adapter(
                        26);
        assertThrows(
                IllegalStateException.class,
                wrongVersion::startAfterIdsDataReady);
    }

    @Test
    public void snapshotSentTracksPhoneSnapshot() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(25);
        adapter.startAfterIdsDataReady();
        assertFalse(adapter.snapshotSent());

        adapter.onPhoneCheckAcknowledged();
        assertTrue(adapter.snapshotSent());
    }

    @Test
    public void missingModelAndCapabilitiesCannotBeInventedFromSetupMetadata() {
        AppleWatchInitialSetupIdsAdapter adapter =
                adapter(25);
        adapter.startAfterIdsDataReady();

        NanoRegistryPropertyCodec.PropertiesChanged watch =
                new NanoRegistryPropertyCodec.PropertiesChanged(
                        true,
                        List.of(
                                new NanoRegistryPropertyCodec.Property(
                                        "productType",
                                        NanoRegistryPropertyCodec.PropertyValue.data(
                                                "Watch7,5".getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                                new NanoRegistryPropertyCodec.Property(
                                        "chipID",
                                        NanoRegistryPropertyCodec.PropertyValue.string(
                                                "0x8310"))),
                        42.0);
        try {
            List<AppleWatchInitialSetupIdsAdapter.Action> afterWatch =
                    adapter.acceptClassC(watch);
            assertTrue(afterWatch.isEmpty());
            assertFalse(adapter.snapshot().transportCompletionIssued);
            assertFalse(adapter.snapshot().identityValidated);
        } finally {
            watch.destroy();
        }
    }

    private static byte[] staticRandomMac() {
        return hexBytes(
                "1122334455c6");
    }

    private static AppleWatchInitialSetupIdsAdapter adapter(
            int effectiveRemoteMaxPairingVersion) {
        return new AppleWatchInitialSetupIdsAdapter(
                1,
                WatchSetupMetadataCodec.decodeIdentifier(
                        hexBytes(
                                "8693f4ce")),
                WatchSetupMetadataCodec.decodeExtendedMetadata(
                        hexBytes(
                                "64385000d01000")),
                effectiveRemoteMaxPairingVersion);
    }

    private static NanoRegistryPropertyCodec.PropertiesChanged
            watchSnapshot(
                    boolean full,
                    String productType,
                    long chipId) {
        return new NanoRegistryPropertyCodec.PropertiesChanged(
                full,
                List.of(
                        new NanoRegistryPropertyCodec.Property(
                                "productType",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .string(
                                                productType)),
                        new NanoRegistryPropertyCodec.Property(
                                "modelNumber",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .string(
                                                "TEST-ULTRA2-SKU")),
                        new NanoRegistryPropertyCodec.Property(
                                "chipID",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .number(
                                                NanoRegistryPropertyCodec
                                                        .NumberValue
                                                        .ofInt64(
                                                                chipId,
                                                                true))),
                        new NanoRegistryPropertyCodec.Property(
                                "capabilities",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .set(
                                                List.of(
                                                        NanoRegistryPropertyCodec
                                                                .PropertyValue
                                                                .uuid(
                                                                uuidBytes(
                                                                                AppleWatchSetupCoordinator
                                                                                        .PAIRING_SESSION_ID_CAPABILITY)))))),
                42.0);
    }

    private static byte[] uuidBytes(
            String value) {
        UUID uuid =
                UUID.fromString(
                        value);
        return ByteBuffer.allocate(
                16)
                .putLong(
                        uuid.getMostSignificantBits())
                .putLong(
                        uuid.getLeastSignificantBits())
                .array();
    }

    private static void assertTypes(
            List<AppleWatchInitialSetupIdsAdapter.Action> actions,
            AppleWatchInitialSetupIdsAdapter.ActionType... types) {
        assertEquals(
                types.length,
                actions.size());
        for (int index = 0;
                index < types.length;
                index++) {
            assertEquals(
                    types[index],
                    actions.get(
                            index).type);
        }
    }

    private static String hex(
            byte[] value) {
        StringBuilder result =
                new StringBuilder();
        for (byte current : value) {
            result.append(
                    String.format(
                            "%02x",
                            current & 0xff));
        }
        return result.toString();
    }

    private static byte[] hexBytes(
            String value) {
        byte[] output =
                new byte[value.length() / 2];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            value.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
    }

    private static void wipe(
            byte[] value) {
        java.util.Arrays.fill(
                value,
                (byte) 0);
    }
}
