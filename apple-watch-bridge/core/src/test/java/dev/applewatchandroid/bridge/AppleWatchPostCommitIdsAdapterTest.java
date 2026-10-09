package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

public final class AppleWatchPostCommitIdsAdapterTest {
    private static final long GENERATION =
            71;
    private static final int WATCH_PAIRING_VERSION =
            25;

    @Test
    public void orchestratorSendsWatchChoicesInBothPbBridgeFieldsAndArchive() {
        Locale phoneLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ru-RU"));
        AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
        coordinator.begin(GENERATION, PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                false, WATCH_PAIRING_VERSION);
        FakeTransport transport = new FakeTransport();
        var action = AppleWatchPostCommitCoordinator.Action.simple(GENERATION,
                AppleWatchPostCommitCoordinator.ActionType.SEND_LANGUAGE_AND_LOCALE);
        try (var adapter = new AppleWatchPostCommitIdsAdapter(coordinator, transport)) {
            assertThrows(IllegalStateException.class,
                    () -> HalPostCommitOrchestrator.prepareActionSend(action, adapter));
            assertTrue(transport.captures.isEmpty());
            var observed = new WatchLocaleSnapshot(List.of("de-DE", "en-GB"), "de_AT");
            try (var send = HalPostCommitOrchestrator.prepareActionSend(action, adapter, observed)) {
                var capture = transport.captures.get(0);
                assertEquals(PbBridgeCodec.TYPE_LANGUAGE_AND_LOCALE, capture.protobufType);
                var decoded = PbBridgeCodec.decodeLanguageAndLocale(capture.payload);
                byte[] archive = decoded.archivedPreferences();
                try {
                    assertEquals(observed.languages(), decoded.appleLanguages());
                    assertEquals(observed.locale(), decoded.appleLocale());
                    var preferences = LocalePreferencesArchiveCodec.decode(archive);
                    assertEquals(observed.languages(), preferences.appleLanguages());
                    assertEquals(observed.locale(), preferences.appleLocale());
                    assertFalse(coordinator.snapshot().languageAndLocaleComplete);
                } finally { wipe(archive); decoded.destroy(); }
            }
        } finally { Locale.setDefault(phoneLocale); }
    }

    @Test
    public void diagnosticCompletionReturnsBothDomainsWithoutReopeningBuddy() {
        AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
        coordinator.begin(GENERATION, PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                false, WATCH_PAIRING_VERSION);
        FakeTransport transport = new FakeTransport();
        try (AppleWatchPostCommitIdsAdapter adapter = new AppleWatchPostCommitIdsAdapter(coordinator, transport);
             AppleWatchPostCommitIdsAdapter.DiagnosticSend send =
                     adapter.prepareDiagnosticPairedSyncRepublish(800000000.5)) {
            List<byte[]> frames = send.outboundFrames();
            assertEquals(2, frames.size());
            assertEquals(2, transport.captures.size());
            PairedSyncCodec.UserDefaultsMessage psy = PairedSyncCodec.decode(frames.get(0));
            PairedSyncCodec.UserDefaultsMessage nps = PairedSyncCodec.decodeInbound(frames.get(1));
            try {
                assertEquals(PairedSyncCodec.DOMAIN, psy.domain);
                assertEquals(PairedSyncCodec.NANOPREFSYNCD_DOMAIN, nps.domain);
                assertEquals(PairedSyncCodec.PAST_INITIAL_SYNC_KEY, nps.keys().get(0).key);
            } finally { psy.destroy(); nps.destroy(); }
        }
    }

    @Test
    public void bufferedEventOwnsItsPayloadAfterOriginalOutputIsClosed() {
        byte[] payload = new byte[] {1, 2, 3};
        var original = incomingEvent(NanoRegistryPropertyCodec.CLASS_C_SERVICE, 2, false, null, payload);
        var buffered = original.copy();
        original.close();
        try { assertArrayEquals(payload, buffered.payload()); }
        finally { buffered.close(); }
        assertThrows(IllegalStateException.class, original::copy);
    }

    @Test
    public void observedIsSetupUsesTheFirmwarePropertyAndActualBooleanValue() {
        AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
        coordinator.begin(GENERATION, PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                false, WATCH_PAIRING_VERSION);
        try (var adapter = new AppleWatchPostCommitIdsAdapter(coordinator, new FakeTransport())) {
            for (boolean ready : new boolean[] {false, true}) {
                var properties = new NanoRegistryPropertyCodec.PropertiesChanged(false, List.of(
                        new NanoRegistryPropertyCodec.Property("isSetup",
                                NanoRegistryPropertyCodec.PropertyValue.number(
                                        NanoRegistryPropertyCodec.NumberValue.ofBoolean(ready)))), null);
                byte[] payload = NanoRegistryPropertyCodec.encode(properties);
                try (var event = incomingEvent(NanoRegistryPropertyCodec.CLASS_C_SERVICE,
                        NanoRegistryPropertyCodec.TYPE_PROPERTIES_CHANGED, false, null, payload);
                     var result = adapter.accept(event)) {
                    if (!ready) {
                        assertFalse(coordinator.snapshot().isSetupObserved);
                        assertFalse(result.actions().stream().anyMatch(action -> action.type
                                == AppleWatchPostCommitCoordinator.ActionType.PERSIST_DURABLE_STATE));
                    } else {
                        assertTrue(coordinator.snapshot().isSetupObserved);
                        assertTrue(result.actions().stream().anyMatch(action -> action.durableState
                                == PairingSessionRecord.DurableState.IS_SETUP_CONFIRMED));
                    }
                } finally { properties.destroy(); wipe(payload); }
            }
        }
    }

    @Test
    public void nativePropertyResponseObservesSetupWithoutClaimingPairedSyncOrClock() {
        AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
        coordinator.begin(GENERATION, PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                false, WATCH_PAIRING_VERSION);
        try (var adapter = new AppleWatchPostCommitIdsAdapter(coordinator, new FakeTransport())) {
            var response = new NanoRegistryPropertyCodec.PropertyResponse(List.of(
                    new NanoRegistryPropertyCodec.Property("isSetup",
                            NanoRegistryPropertyCodec.PropertyValue.number(
                                    NanoRegistryPropertyCodec.NumberValue.ofBoolean(true)))));
            byte[] payload = NanoRegistryPropertyCodec.encodePropertyResponse(response);
            try (var event = incomingEvent(NanoRegistryPropertyCodec.CLASS_C_SERVICE,
                    NanoRegistryPropertyCodec.TYPE_PROPERTY_REQUEST, true, null, payload);
                 var result = adapter.accept(event)) {
                assertTrue(coordinator.snapshot().isSetupObserved);
                assertFalse(coordinator.snapshot().pairedSyncObserved);
                assertEquals(PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                        coordinator.snapshot().durableState);
            } finally { response.destroy(); Arrays.fill(payload, (byte) 0); }
        }
    }

    @Test
    public void backupUserDefaultsAreStored() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("paired-sync-backup").toFile();
        java.io.File file = new java.io.File(dir, "prefs.v1");
        PairedSyncPreferenceStore.setStoreFileForTesting(file);
        try {
            AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
            coordinator.begin(GENERATION, PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                    false, WATCH_PAIRING_VERSION);
            byte[] payload;
            PairedSyncCodec.UserDefaultsMessage message = new PairedSyncCodec.UserDefaultsMessage(
                    4.0,
                    "com.apple.sleepd",
                    List.of(new PairedSyncCodec.UserDefaultsKey(
                            "HKSPSleepTracking", new byte[] {4, 5, 6}, null, 4.0)),
                    false);
            try {
                payload = PairedSyncCodec.encode(message);
            } finally {
                message.destroy();
            }
            try (var adapter = new AppleWatchPostCommitIdsAdapter(coordinator, new FakeTransport());
                 var event = incomingEvent(
                         IdsApplicationRoute.PREFERENCE_SYNC_SERVICE,
                         PairedSyncCodec.PROTOBUF_TYPE_USER_DEFAULT_BACKUP,
                         false,
                         null,
                         payload);
                 var ignored = adapter.accept(event)) {
                assertEquals(3, PairedSyncPreferenceStore.valueLengthForTesting(
                        "com.apple.sleepd", "HKSPSleepTracking"));
            }
        } finally {
            PairedSyncPreferenceStore.setStoreFileForTesting(null);
        }
    }

    @Test
    public void normalModeReplyMustMatchTheActualClassDRequest() {
        var coordinator = new AppleWatchPostCommitCoordinator();
        var start = coordinator.begin(GENERATION, PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED,
                false, WATCH_PAIRING_VERSION);
        try (var adapter = new AppleWatchPostCommitIdsAdapter(coordinator, new FakeTransport())) {
            String requestId;
            try (var send = adapter.prepareSimple(action(start,
                    AppleWatchPostCommitCoordinator.ActionType.SEND_PAIRING_MODE_NORMAL))) {
                requestId = send.messageUuid();
                send.complete(true);
            }
            byte[] reply = NanoRegistryClassDCodec.encode(new NanoRegistryClassDCodec.PairingModeResponse(true));
            try (var wrong = incomingEvent(NanoRegistryPropertyCodec.CLASS_D_SERVICE,
                    NanoRegistryClassDCodec.TYPE_PAIRING_MODE, true,
                    "ffffffff-ffff-4fff-8fff-ffffffffffff", reply);
                 var ignored = adapter.accept(wrong)) {
                assertFalse(coordinator.snapshot().watchCompatibilityNormalObserved);
            }
            try (var correct = incomingEvent(NanoRegistryPropertyCodec.CLASS_D_SERVICE,
                    NanoRegistryClassDCodec.TYPE_PAIRING_MODE, true, requestId, reply);
                 var accepted = adapter.accept(correct)) {
                assertTrue(coordinator.snapshot().watchCompatibilityNormalObserved);
            } finally { wipe(reply); }
        }
    }

    private static IdsModernSessionCoordinator.SessionEvent incomingEvent(
            String topic, int type, boolean response, String peerId, byte[] payload) {
        var message = new IdsSocketPairCodec.ProtobufMessage(99, 1, 0, peerId,
                "00000000-0000-4000-8000-000000000099", null, type, response, payload, null);
        try {
            return IdsModernSessionCoordinator.SessionEvent.protobuf(
                    IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, 1, topic, topic, message);
        } finally { message.destroy(); }
    }

    @Test
    public void activationTypeOnAnotherTopicCannotConfirmActivation() {
        for (String topic : new String[] {null, "other.bridge", "nano-class-c", "pbbridge"}) {
            AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
            coordinator.begin(GENERATION, PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED,
                    false, WATCH_PAIRING_VERSION);
            try (AppleWatchPostCommitIdsAdapter adapter = new AppleWatchPostCommitIdsAdapter(coordinator, new FakeTransport());
                 AppleWatchPostCommitIdsAdapter.InboundMessage incoming = new AppleWatchPostCommitIdsAdapter.InboundMessage(
                         10, 1, 0, null, "00000000-0000-4000-8000-000000000001", topic,
                         PbBridgeCodec.TYPE_ACTIVATION_SUCCEEDED, false, new byte[0]);
                 AppleWatchPostCommitIdsAdapter.InboundResult result = adapter.acceptPbBridge(incoming)) {
                assertTrue(result.actions().isEmpty());
                assertFalse(coordinator.snapshot().activationConfirmed);
            }
        }
    }

    @Test
    public void matchingReceiptCompletesOnlyItsPendingSendWhileAnotherLaneIsStalled() {
        AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> start = coordinator.begin(GENERATION,
                PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED, false, WATCH_PAIRING_VERSION);
        try (AppleWatchPostCommitIdsAdapter adapter = new AppleWatchPostCommitIdsAdapter(coordinator, new FakeTransport());
             PostCommitDeliveryQueue queue = new PostCommitDeliveryQueue()) {
            var normal = adapter.prepareSimple(action(start,
                    AppleWatchPostCommitCoordinator.ActionType.SEND_PAIRING_MODE_NORMAL));
            long sequence = normal.sequence();
            queue.add(normal);
            queue.add(adapter.prepareSimple(action(start,
                    AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_PERMIT)));
            try (var wrong = IdsModernSessionCoordinator.SessionEvent.ack(1,
                    NanoRegistryPropertyCodec.CLASS_D_SERVICE, new IdsSocketPairCodec.AckMessage(IdsSocketPairCodec.COMMAND_ACK, 9999))) {
                assertTrue(queue.completeReceipt(wrong).isEmpty());
                assertEquals(2, queue.size());
            }
            try (var receipt = IdsModernSessionCoordinator.SessionEvent.ack(1,
                    NanoRegistryPropertyCodec.CLASS_D_SERVICE, new IdsSocketPairCodec.AckMessage(IdsSocketPairCodec.COMMAND_ACK, sequence))) {
                queue.completeReceipt(receipt);
                assertEquals(1, queue.size());
                assertTrue(coordinator.snapshot().pairingModeNormalSent);
                assertFalse(coordinator.snapshot().activationConfirmed);
            }
        }
    }

    @Test
    public void deferredPermitCannotStartActivationUntilBothTransportQueuesDrain() {
        AppleWatchPostCommitCoordinator coordinator = new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> start = coordinator.begin(
                GENERATION, PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED,
                false, WATCH_PAIRING_VERSION);
        FakeTransport transport = new FakeTransport();
        try (AppleWatchPostCommitIdsAdapter adapter =
                     new AppleWatchPostCommitIdsAdapter(coordinator, transport);
             PostCommitDeliveryQueue queue = new PostCommitDeliveryQueue()) {
            queue.add(adapter.prepareSimple(action(start,
                    AppleWatchPostCommitCoordinator.ActionType.SEND_PAIRING_MODE_NORMAL)));
            queue.add(adapter.prepareSimple(action(start,
                    AppleWatchPostCommitCoordinator.ActionType.SEND_ACTIVATION_PERMIT)));
            assertEquals(2, queue.size());
            assertTrue(queue.completeDrained(true, false).isEmpty());
            assertEquals(2, queue.size());
            assertFalse(coordinator.snapshot().pairingModeNormalSent);
            assertTrue(queue.completeDrained(false, true).isEmpty());
            assertEquals(2, queue.size());
            assertFalse(coordinator.snapshot().pairingModeNormalSent);
            queue.completeDrained(false, false);
            assertEquals(0, queue.size());
            assertEquals(AppleWatchPostCommitCoordinator.ActionType.EXECUTE_ACTIVATION_HTTPS,
                    coordinator.onActivationRequest(GENERATION).get(0).type);
            assertFalse(coordinator.snapshot().activationConfirmed);
        }
    }

    @Test
    public void mapsFullWirePathAndRequiresExactType18Correlation() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> start =
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        FakeTransport transport =
                new FakeTransport();
        try (AppleWatchPostCommitIdsAdapter adapter =
                     new AppleWatchPostCommitIdsAdapter(
                             coordinator,
                             transport)) {
            AppleWatchPostCommitCoordinator.Action timezone =
                    action(
                            start,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_COMPUTED_TIME_ZONE);
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.prepareComputedTimeZone(
                                 timezone,
                                 "Europe/Kyiv")) {
                assertEquals(
                        0,
                        prepared.sequence());
                assertEquals(
                        PbBridgeCodec.TYPE_COMPUTED_TIME_ZONE,
                        prepared.protobufType());
                assertEquals(
                        1,
                        prepared.outboundFrames().size());
                assertTrue(
                        prepared.complete(
                                true).isEmpty());
            }

            AppleWatchPostCommitCoordinator.Action permit =
                    action(
                            start,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_ACTIVATION_PERMIT);
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.prepareSimple(
                                 permit)) {
                assertEquals(
                        1,
                        prepared.sequence());
                assertTrue(
                        prepared.complete(
                                true).isEmpty());
            }

            AppleWatchPostCommitCoordinator.Action sessionHttps;
            try (AppleWatchPostCommitIdsAdapter.InboundMessage request =
                         activationRequest(
                                 101,
                                 new byte[] {
                                         0x11,
                                         0x12
                                 });
                 AppleWatchPostCommitIdsAdapter.InboundResult result =
                         adapter.acceptPbBridge(
                                 request)) {
                sessionHttps =
                        action(
                                result.actions(),
                                AppleWatchPostCommitCoordinator.ActionType
                                        .EXECUTE_ACTIVATION_HTTPS);
                assertEquals(
                        MobileActivationHttpProxy.RequestKind.SESSION,
                        result.activationKind);
                assertEquals(
                        permit.activationAttempt,
                        result.activationAttempt);
                assertArrayEquals(
                        new byte[] {
                                0x11,
                                0x12
                        },
                        result.archivedActivationRequest());
            }

            AppleWatchPostCommitCoordinator.Action sessionData =
                    action(
                            adapter.onActivationProxyResult(
                                    sessionHttps,
                                    true),
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_ACTIVATION_DATA);
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.prepareActivationData(
                                 sessionData,
                                 new byte[] {
                                         0x21
                                 },
                                 new byte[] {
                                         0x22
                                 })) {
                assertEquals(
                        2,
                        prepared.sequence());
                // Live 0.2.302: landing the session data re-asserts
                // CanBeginActivation so the Watch proceeds to the
                // deviceActivation request.
                action(
                        prepared.complete(true),
                        AppleWatchPostCommitCoordinator.ActionType
                                .SEND_ACTIVATION_PERMIT);
            }

            AppleWatchPostCommitCoordinator.Action activationHttps;
            try (AppleWatchPostCommitIdsAdapter.InboundMessage request =
                         activationRequest(
                                 102,
                                 new byte[] {
                                         0x31,
                                         0x32
                                 });
                 AppleWatchPostCommitIdsAdapter.InboundResult result =
                         adapter.acceptPbBridge(
                                 request)) {
                activationHttps =
                        action(
                                result.actions(),
                                AppleWatchPostCommitCoordinator.ActionType
                                        .EXECUTE_ACTIVATION_HTTPS);
                assertEquals(
                        MobileActivationHttpProxy.RequestKind.ACTIVATION,
                        result.activationKind);
            }

            AppleWatchPostCommitCoordinator.Action activationData =
                    action(
                            adapter.onActivationProxyResult(
                                    activationHttps,
                                    true),
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_ACTIVATION_DATA);
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.prepareActivationData(
                                 activationData,
                                 new byte[] {
                                         0x41
                                 },
                                 new byte[] {
                                         0x42
                                 })) {
                assertEquals(
                        3,
                        prepared.sequence());
                assertTrue(
                        prepared.complete(
                                true).isEmpty());
            }

            List<AppleWatchPostCommitCoordinator.Action> activationOutcome;
            try (AppleWatchPostCommitIdsAdapter.InboundMessage success =
                         incoming(
                                 new PbBridgeCodec.ActivationSucceeded(),
                                 103,
                                 null);
                 AppleWatchPostCommitIdsAdapter.InboundResult result =
                         adapter.acceptPbBridge(
                                 success)) {
                activationOutcome =
                        result.actions();
                assertTypes(
                        activationOutcome,
                        AppleWatchPostCommitCoordinator.ActionType
                                .PERSIST_DURABLE_STATE);
            }
            assertEquals(
                    PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED,
                    activationOutcome.get(
                            0).durableState);

            List<AppleWatchPostCommitCoordinator.Action> afterCheckpoint =
                    coordinator.onDurableStatePersisted(
                            GENERATION,
                            PairingSessionRecord.DurableState
                                    .ACTIVATION_CONFIRMED,
                            true);
            AppleWatchPostCommitCoordinator.Action language =
                    action(
                            afterCheckpoint,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_LANGUAGE_AND_LOCALE);
            assertFalse(coordinator.snapshot().isSetupObserved);
            byte[] preferences =
                    LocalePreferencesArchiveCodec.encodeSetupPreferences(
                            List.of(
                                    "uk-UA"),
                            "uk_UA");
            try {
                try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                             adapter.prepareLanguageAndLocale(
                                     language,
                                     List.of(
                                             "uk-UA"),
                                     "uk_UA",
                                     preferences)) {
                    assertEquals(
                            4,
                            prepared.sequence());
                    assertTrue(
                            prepared.complete(
                                    true).isEmpty());
                }
            } finally {
                wipe(
                        preferences);
            }

            List<AppleWatchPostCommitCoordinator.Action> afterStatus;
            try (AppleWatchPostCommitIdsAdapter.InboundMessage status =
                         incoming(
                                 new PbBridgeCodec.LanguageAndLocaleStatus(
                                         1),
                                 100,
                                 null)) {
                try (AppleWatchPostCommitIdsAdapter.InboundResult result =
                             adapter.acceptPbBridge(
                                     status)) {
                    afterStatus =
                            result.actions();
                    assertTypes(
                            afterStatus,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_PB_BRIDGE_NORMAL);
                    assertFalse(
                            result.hasActivationRequest());
                }
            }

            AppleWatchPostCommitCoordinator.Action normalState =
                    action(
                            afterStatus,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_PB_BRIDGE_NORMAL);
            AppleWatchPostCommitCoordinator.Action waitNormal =
                    action(
                            afterCheckpoint,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .WAIT_FOR_PB_BRIDGE_NORMAL_PREREQUISITES);
            assertNotNull(
                    waitNormal);

            List<AppleWatchPostCommitCoordinator.Action> afterNormal;
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.prepareSimple(
                                 normalState)) {
                assertEquals(
                        5,
                        prepared.sequence());
                afterNormal =
                        prepared.complete(
                                true);
            }
            assertTypes(
                    afterNormal,
                    AppleWatchPostCommitCoordinator.ActionType
                            .SEND_PREPARE_INITIAL_SYNC);

            AppleWatchPostCommitCoordinator.Action prepareInitial =
                    action(
                            afterNormal,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_PREPARE_INITIAL_SYNC);

            String requestIdentifier;
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.prepareSimple(
                                 prepareInitial)) {
                assertEquals("Buddy must precede beginning and PB prepare", 3,
                        prepared.outboundFrames().size());
                Capture buddy = transport.captures.get(6);
                assertEquals(PbBridgeCodec.SERVICE, buddy.topic);
                assertEquals(PbBridgeCodec.TYPE_PUSH_CONTROLLER, buddy.protobufType);
                assertArrayEquals(new byte[] {8, 10}, prepared.outboundFrames().get(0));
                Capture syncStart = transport.captures.get(7);
                assertEquals(PairedSyncCodec.PREFERRED_SERVICE, syncStart.topic);
                assertArrayEquals(syncStart.payload, prepared.outboundFrames().get(1));
                assertArrayEquals(transport.last().payload, prepared.outboundFrames().get(2));
                PairedSyncCodec.UserDefaultsMessage startDefaults = PairedSyncCodec.decode(syncStart.payload);
                try {
                    assertFalse(startDefaults.isInitialSyncCompletion());
                } finally {
                    startDefaults.destroy();
                }
                requestIdentifier =
                        prepared.messageUuid();
                assertEquals(
                        8,
                        prepared.sequence());
                assertEquals(
                        IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE,
                        transport.last().flags);

                try (AppleWatchPostCommitIdsAdapter.InboundMessage early =
                             prepareResponse(
                                     104,
                                     requestIdentifier);
                     AppleWatchPostCommitIdsAdapter.InboundResult result =
                             adapter.acceptPbBridge(
                                     early)) {
                    assertTrue(
                            result.actions().isEmpty());
                }
                assertTrue(
                        prepared.complete(
                                true).isEmpty());
            }

            try (AppleWatchPostCommitIdsAdapter.InboundMessage wrong =
                         prepareResponse(
                                 105,
                                 "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE");
                 AppleWatchPostCommitIdsAdapter.InboundResult result =
                         adapter.acceptPbBridge(
                                 wrong)) {
                assertTrue(
                        result.actions().isEmpty());
            }
            AppleWatchPostCommitCoordinator.Action paired;
            try (AppleWatchPostCommitIdsAdapter.InboundMessage exact =
                         prepareResponse(
                                 106,
                                 requestIdentifier);
                 AppleWatchPostCommitIdsAdapter.InboundResult result =
                         adapter.acceptPbBridge(
                                 exact)) {
                // The correlated protobuf 18 response alone opens the
                // completion-publication gate: a direct reconnect never
                // delivers the Class-C capabilities property, so the wire
                // proof stands in for it.
                paired =
                        action(
                                result.actions(),
                                AppleWatchPostCommitCoordinator.ActionType
                                        .SEND_PAIRED_SYNC_COMPLETION);
            }
            assertTrue(
                    coordinator.snapshot().initialSyncPrepared);

            assertTrue(coordinator.onNormalTransitionPrerequisitesSatisfied(GENERATION).isEmpty());
            assertTrue(
                    coordinator.onCompatibilityStateObserved(
                            GENERATION,
                            AppleWatchPostCommitCoordinator
                                    .COMPATIBILITY_STATE_NORMAL).isEmpty());
            assertTypes(coordinator.onIsSetupObserved(GENERATION, true),
                    AppleWatchPostCommitCoordinator.ActionType.PERSIST_DURABLE_STATE);
            assertTrue(coordinator.onDurableStatePersisted(GENERATION,
                    PairingSessionRecord.DurableState.IS_SETUP_CONFIRMED, true).isEmpty());
            // The capability observation is diagnostic only once the
            // completion publication is in flight.
            assertTrue(
                    adapter.observePairedSyncCapability().isEmpty());
            String pairedSyncIdentifier;
            String pairedSyncTopic;
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.preparePairedSyncCompletion(
                                 paired,
                                 800000000.5)) {
                assertEquals("Completion must not reopen the Buddy observer", 2,
                        prepared.outboundFrames().size());
                pairedSyncIdentifier =
                        prepared.messageUuid();
                pairedSyncTopic =
                        prepared.topic();
                assertEquals(
                        9,
                        prepared.sequence());
                assertEquals(
                        PairedSyncCodec.PROTOBUF_TYPE_USER_DEFAULTS,
                        prepared.protobufType());
                assertEquals(
                        IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                        transport.captures.get(9).flags
                                & IdsSocketPairCodec.FLAG_WANTS_APP_ACK);
                assertTrue(prepared.complete(true).isEmpty());
            }
            assertTypes(coordinator.onPeerPairedSyncCompletionObserved(GENERATION),
                    AppleWatchPostCommitCoordinator.ActionType.PERSIST_DURABLE_STATE);
            assertEquals(PairingSessionRecord.DurableState.PAIRED_SYNC_COMPLETE,
                    coordinator.snapshot().checkpointPending);
            assertFalse(
                    adapter.pairedSyncAppAckObserved());
            try (IdsModernSessionCoordinator.SessionEvent wrong =
                         appAckEvent(
                                 7,
                                 pairedSyncTopic,
                                 "AAAAAAAA-BBBB-4CCC-8DDD-EEEEEEEEEEEE")) {
                assertEquals(
                        AppleWatchPostCommitIdsAdapter.AppAckReceipt.UNRELATED,
                        adapter.acceptAppAck(
                                wrong));
            }
            try (IdsModernSessionCoordinator.SessionEvent exact =
                         appAckEvent(
                                 1234,
                                 pairedSyncTopic,
                                 pairedSyncIdentifier)) {
                assertEquals(
                        AppleWatchPostCommitIdsAdapter.AppAckReceipt
                                .PAIRED_SYNC_MATCHED,
                        adapter.acceptAppAck(
                                exact));
            }
            assertTrue(
                    adapter.pairedSyncAppAckObserved());
            try (IdsModernSessionCoordinator.SessionEvent duplicate =
                         appAckEvent(
                                 1235,
                                 pairedSyncTopic,
                                 pairedSyncIdentifier)) {
                assertEquals(
                        AppleWatchPostCommitIdsAdapter.AppAckReceipt
                                .PAIRED_SYNC_DUPLICATE,
                        adapter.acceptAppAck(
                                duplicate));
            }
            assertEquals(
                    PairingSessionRecord.DurableState.IS_SETUP_CONFIRMED,
                    coordinator.snapshot().durableState);

            // pairedSyncObserved was already latched by the raced-ahead
            // publication receipt, so the PSY-state observation is a
            // duplicate and returns no actions.
            List<AppleWatchPostCommitCoordinator.Action> observed =
                    coordinator.onWatchPairedSyncCompletionObserved(
                            GENERATION,
                            3,
                            100,
                            3,
                            1);
            assertTrue(
                    observed.isEmpty());

            // Apply PAIRED_SYNC_COMPLETE and run the chain to its end:
            // local setup-completion dispatch, SETUP_COMPLETE and — on the
            // latched IsSetup evidence — the automatic Clock checkpoint.
            assertTypes(
                    coordinator.onDurableStatePersisted(
                            GENERATION,
                            PairingSessionRecord.DurableState
                                    .PAIRED_SYNC_COMPLETE,
                            true),
                    AppleWatchPostCommitCoordinator.ActionType
                            .SEND_IDS_LOCAL_PAIRING_SETUP_COMPLETED);
            assertTypes(
                    coordinator.onIdsSetupCompletedDispatchResult(
                            GENERATION,
                            true),
                    AppleWatchPostCommitCoordinator.ActionType
                            .PERSIST_DURABLE_STATE);
            assertTypes(coordinator.onDurableStatePersisted(GENERATION,
                    PairingSessionRecord.DurableState.SETUP_COMPLETE, true),
                    AppleWatchPostCommitCoordinator.ActionType.VERIFY_CLOCK_VISIBLE);
            assertEquals(AppleWatchPostCommitCoordinator.Phase.WAITING_FOR_CLOCK,
                    coordinator.snapshot().phase);

            // Buddy was opened before beginning; final captures publish PSY and NPS.
            assertEquals(
                    11,
                    transport.captures.size());
            assertEquals(
                    PbBridgeCodec.TYPE_COMPUTED_TIME_ZONE,
                    transport.captures.get(
                            0).protobufType);
            assertEquals(
                    PbBridgeCodec.TYPE_CAN_BEGIN_ACTIVATION,
                    transport.captures.get(
                            1).protobufType);
            assertEquals(
                    PbBridgeCodec.TYPE_LANGUAGE_AND_LOCALE,
                    transport.captures.get(
                            4).protobufType);
            assertEquals(
                    PairedSyncCodec.PREFERRED_SERVICE,
                    transport.captures.get(
                            9).topic);
        }
        assertTrue(
                transport.closed);
    }

    @Test
    public void firstForwardPreparationFailureBlocksEveryPbBridgeSend() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> actions =
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        FakeTransport transport =
                new FakeTransport();
        transport.failNext =
                true;
        try (AppleWatchPostCommitIdsAdapter adapter =
                     new AppleWatchPostCommitIdsAdapter(
                             coordinator,
                             transport)) {
            AppleWatchPostCommitIdsAdapter.DispatchException failure =
                    assertThrows(
                            AppleWatchPostCommitIdsAdapter
                                    .DispatchException.class,
                            () -> adapter.prepareComputedTimeZone(
                                    action(
                                            actions,
                                            AppleWatchPostCommitCoordinator
                                                    .ActionType
                                                    .SEND_COMPUTED_TIME_ZONE),
                                    "Europe/Kyiv"));
            assertTypes(
                    failure.followUpActions(),
                    AppleWatchPostCommitCoordinator.ActionType
                            .REPORT_FAILURE);
            assertEquals(
                    AppleWatchPostCommitCoordinator.Failure.TRANSPORT_SEND,
                    failure.followUpActions().get(
                            0).failure);
            assertTrue(
                    transport.captures.isEmpty());
            assertEquals(
                    AppleWatchPostCommitCoordinator.Phase
                            .FORWARD_RECOVERY_REQUIRED,
                    coordinator.snapshot().phase);
        }
    }

    @Test
    public void tracksDispatchedActionsCorrectly() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> actions =
                coordinator.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        FakeTransport transport =
                new FakeTransport();
        try (AppleWatchPostCommitIdsAdapter adapter =
                     new AppleWatchPostCommitIdsAdapter(
                             coordinator,
                             transport)) {
            AppleWatchPostCommitCoordinator.Action timezone =
                    action(
                            actions,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_COMPUTED_TIME_ZONE);
            assertFalse(
                    adapter.isDispatched(timezone));
            try (AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                         adapter.prepareComputedTimeZone(
                                 timezone,
                                 "Europe/Kyiv")) {
                assertTrue(
                        adapter.isDispatched(timezone));
                prepared.complete(true);
            }
            assertTrue(
                    adapter.isDispatched(timezone));
        }
    }

    @Test
    public void abandonedOrFailedPreparationMovesCoordinatorToRecovery() {
        AppleWatchPostCommitCoordinator abandoned =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> actions =
                abandoned.begin(
                        GENERATION,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        FakeTransport abandonedTransport =
                new FakeTransport();
        try (AppleWatchPostCommitIdsAdapter adapter =
                     new AppleWatchPostCommitIdsAdapter(
                             abandoned,
                             abandonedTransport)) {
            AppleWatchPostCommitCoordinator.Action timezone =
                    action(
                            actions,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_COMPUTED_TIME_ZONE);
            AppleWatchPostCommitIdsAdapter.PreparedSend prepared =
                    adapter.prepareComputedTimeZone(
                            timezone,
                            "Europe/Kyiv");
            assertThrows(
                    IllegalStateException.class,
                    () -> adapter.prepareComputedTimeZone(
                            timezone,
                            "Europe/Kyiv"));
            prepared.close();
            assertEquals(
                    AppleWatchPostCommitCoordinator.Phase
                            .FORWARD_RECOVERY_REQUIRED,
                    abandoned.snapshot().phase);
        }

        AppleWatchPostCommitCoordinator failed =
                new AppleWatchPostCommitCoordinator();
        List<AppleWatchPostCommitCoordinator.Action> failedActions =
                failed.begin(
                        GENERATION + 1,
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        false,
                        WATCH_PAIRING_VERSION);
        FakeTransport transport =
                new FakeTransport();
        try (AppleWatchPostCommitIdsAdapter adapter =
                     new AppleWatchPostCommitIdsAdapter(
                             failed,
                             transport)) {
            transport.failNext =
                    true;
            AppleWatchPostCommitCoordinator.Action timezone =
                    action(
                            failedActions,
                            AppleWatchPostCommitCoordinator.ActionType
                                    .SEND_COMPUTED_TIME_ZONE);
            AppleWatchPostCommitIdsAdapter.DispatchException error =
                    assertThrows(
                            AppleWatchPostCommitIdsAdapter
                                    .DispatchException.class,
                            () -> adapter.prepareComputedTimeZone(
                                    timezone,
                                    "Europe/Kyiv"));
            assertTypes(
                    error.followUpActions(),
                    AppleWatchPostCommitCoordinator.ActionType
                            .REPORT_FAILURE);
            assertEquals(
                    AppleWatchPostCommitCoordinator.Failure.TRANSPORT_SEND,
                    error.followUpActions().get(
                            0).failure);
        }
    }

    @Test
    public void acceptPbBridgeConsumesBeganActivatingWithoutError() {
        AppleWatchPostCommitCoordinator coordinator =
                new AppleWatchPostCommitCoordinator();
        coordinator.begin(
                GENERATION,
                PairingSessionRecord.DurableState
                        .IS_PAIRED_COMMITTED,
                false,
                WATCH_PAIRING_VERSION);
        FakeTransport transport =
                new FakeTransport();
        try (AppleWatchPostCommitIdsAdapter adapter =
                     new AppleWatchPostCommitIdsAdapter(
                             coordinator,
                             transport)) {
            try (AppleWatchPostCommitIdsAdapter.InboundMessage began =
                         incoming(
                                 new PbBridgeCodec.BeganActivating(),
                                 105,
                                 null);
                 AppleWatchPostCommitIdsAdapter.InboundResult result =
                         adapter.acceptPbBridge(
                                 began)) {
                assertTrue(
                        result.actions().isEmpty());
                assertFalse(
                        result.hasActivationRequest());
            }
        }
    }

    private static AppleWatchPostCommitIdsAdapter.InboundMessage
            activationRequest(
                    long sequence,
                    byte[] archivedRequest) {
        PbBridgeCodec.ActivationFetchRequest message =
                new PbBridgeCodec.ActivationFetchRequest(
                        archivedRequest);
        try {
            return incoming(
                    message,
                    sequence,
                    null);
        } finally {
            message.destroy();
        }
    }

    private static AppleWatchPostCommitIdsAdapter.InboundMessage
            prepareResponse(
                    long sequence,
                    String peerResponseIdentifier) {
        return incoming(
                new PbBridgeCodec.PrepareInitialSyncResponse(),
                sequence,
                peerResponseIdentifier);
    }

    private static AppleWatchPostCommitIdsAdapter.InboundMessage incoming(
            PbBridgeCodec.ApplicationMessage message,
            long sequence,
            String peerResponseIdentifier) {
        byte[] payload =
                PbBridgeCodec.encode(
                        message);
        try {
            return new AppleWatchPostCommitIdsAdapter.InboundMessage(
                    sequence,
                    1,
                    0,
                    peerResponseIdentifier,
                    String.format(
                            Locale.ROOT,
                            "10000000-0000-4000-8000-%012X",
                            sequence),
                    PbBridgeCodec.SERVICE,
                    message.protobufType(),
                    message.response(),
                    payload);
        } finally {
            wipe(
                    payload);
        }
    }

    private static AppleWatchPostCommitCoordinator.Action action(
            List<AppleWatchPostCommitCoordinator.Action> actions,
            AppleWatchPostCommitCoordinator.ActionType type) {
        for (AppleWatchPostCommitCoordinator.Action action :
                actions) {
            if (action.type == type) {
                return action;
            }
        }
        throw new AssertionError(
                "Missing action " + type);
    }

    private static void assertTypes(
            List<AppleWatchPostCommitCoordinator.Action> actions,
            AppleWatchPostCommitCoordinator.ActionType... types) {
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

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    private static IdsModernSessionCoordinator.SessionEvent appAckEvent(
            long sequence,
            String topic,
            String peerResponseIdentifier) {
        return IdsModernSessionCoordinator.SessionEvent.appAck(
                IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED,
                1,
                "ids-test-service",
                topic,
                new IdsSocketPairCodec.AppAckMessage(
                        sequence,
                        1,
                        peerResponseIdentifier,
                        topic));
    }

    private static final class FakeTransport
            implements AppleWatchPostCommitIdsAdapter.Transport {
        final List<Capture> captures =
                new ArrayList<>();
        long nextSequence;
        boolean failNext;
        boolean closed;
        PairedSyncCapabilityState.Status capability =
                PairedSyncCapabilityState.Status.PRESENT;

        @Override
        public AppleWatchPostCommitIdsAdapter.DispatchOutput sendClassD(
                NanoRegistryClassDCodec.ApplicationMessage message) {
            requireOpen();
            if (failNext) {
                failNext =
                        false;
                throw new IllegalStateException(
                        "scripted Class-D failure");
            }
            return capture(
                    NanoRegistryClassDCodec.SERVICE,
                    0,
                    message.protobufType(),
                    message.response(),
                    NanoRegistryClassDCodec.encode(
                            message));
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.DispatchOutput sendPbBridge(
                PbBridgeCodec.ApplicationMessage message) {
            requireOpen();
            if (failNext) {
                failNext =
                        false;
                throw new IllegalStateException(
                        "scripted PBBridge failure");
            }
            byte[] payload =
                    PbBridgeCodec.encode(
                            message);
            int flags =
                    message instanceof PbBridgeCodec.PrepareInitialSync
                            ? IdsSocketPairCodec
                            .FLAG_EXPECTS_PEER_RESPONSE
                            : 0;
            return capture(
                    PbBridgeCodec.SERVICE,
                    flags,
                    message.protobufType(),
                    message.response(),
                    payload);
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.DispatchOutput sendPairedSync(
                PairedSyncCodec.UserDefaultsMessage message) {
            requireOpen();
            if (failNext) {
                failNext =
                        false;
                throw new IllegalStateException(
                        "scripted PairedSync failure");
            }
            return capture(
                    PairedSyncCodec.PREFERRED_SERVICE,
                    IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                    message.protobufType(),
                    message.response(),
                    PairedSyncCodec.encode(
                            message));
        }

        @Override
        public PairedSyncCapabilityState.Status
                pairedSyncCapabilityStatus() {
            requireOpen();
            return capability;
        }

        private AppleWatchPostCommitIdsAdapter.DispatchOutput capture(
                String topic,
                int flags,
                int protobufType,
                boolean response,
                byte[] payload) {
            long sequence =
                    nextSequence++;
            String uuid =
                    String.format(
                            Locale.ROOT,
                            "00000000-0000-4000-8000-%012X",
                            sequence);
            Capture capture =
                    new Capture(
                            topic,
                            flags,
                            protobufType,
                            response,
                            payload);
            captures.add(
                    capture);
            return new FakeOutput(
                    new AppleWatchPostCommitIdsAdapter.SentMessage(
                            sequence,
                            uuid,
                            topic,
                            flags,
                            protobufType,
                            response), payload);
        }

        Capture last() {
            return captures.get(
                    captures.size() - 1);
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "fake transport is closed");
            }
        }

        @Override
        public void close() {
            closed =
                    true;
            for (Capture capture :
                    captures) {
                capture.destroy();
            }
        }
    }

    private static final class Capture {
        final String topic;
        final int flags;
        final int protobufType;
        final boolean response;
        byte[] payload;

        private Capture(
                String topic,
                int flags,
                int protobufType,
                boolean response,
                byte[] payload) {
            this.topic =
                    topic;
            this.flags =
                    flags;
            this.protobufType =
                    protobufType;
            this.response =
                    response;
            this.payload =
                    payload.clone();
        }

        private void destroy() {
            wipe(
                    payload);
            payload =
                    new byte[0];
        }
    }

    private static final class FakeOutput
            implements AppleWatchPostCommitIdsAdapter.DispatchOutput {
        private final AppleWatchPostCommitIdsAdapter.SentMessage sent;
        private byte[] frame =
                new byte[] {
                        0x01
                };
        private boolean closed;

        private FakeOutput(
                AppleWatchPostCommitIdsAdapter.SentMessage sent, byte[] payload) {
            this.sent = sent;
            this.frame = payload.clone();
            wipe(payload);
        }

        @Override
        public List<byte[]> outboundFrames() {
            requireOpen();
            return List.of(
                    frame.clone());
        }

        @Override
        public AppleWatchPostCommitIdsAdapter.SentMessage sentMessage() {
            requireOpen();
            return sent;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "fake output is closed");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed =
                    true;
            wipe(
                    frame);
            frame =
                    new byte[0];
        }
    }
}
