package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.Test;

public final class IdsModernSessionCoordinatorTest {
    private static final byte[] PHONE_D =
            address(
                    0x10);
    private static final byte[] WATCH_D =
            address(
                    0x20);
    private static final byte[] PHONE_C =
            address(
                    0x30);
    private static final byte[] WATCH_C =
            address(
                    0x40);

    @Test
    public void nativeResourceChunksAreAckedOnlyAfterCompleteFile() throws Exception {
        java.io.File directory = java.nio.file.Files.createTempDirectory("ids-resource-ack").toFile();
        PairedSyncResourceStore.setDirectoryForTesting(directory);
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var delivered = pump(phone, watch, start)) {
                assertEquals(1, delivered.count("phone", IdsModernSessionCoordinator.EventType.CONTROL_READY));
            }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var delivered = pump(phone, watch, start)) {
                assertTrue(delivered.count("phone", IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED) >= 1);
            }
            // Feed actual IDSFoundation same-sequence resource fixtures into
            // the established lane's decoder. A gap must not retire the sender
            // or prevent the subsequent in-order chunks from completing.
            int[] order = {0, 2, 1, 2, 3, 4};
            for (int index = 0; index < order.length; index++) {
                byte[] frame;
                try (var input = getClass().getResourceAsStream("/ids/resource-chunks/chunk-" + order[index] + ".bin")) {
                    assertTrue(input != null);
                    frame = input.readAllBytes();
                }
                try (var output = receiveNativeResourceFrame(phone.coordinator, frame)) {
                    assertEquals(index == order.length - 1 ? 1 : 0,
                            countEvents(output, IdsModernSessionCoordinator.EventType.ACK_SENT));
                    if (index < order.length - 1) {
                        assertEquals(0, countEvents(output, IdsModernSessionCoordinator.EventType.APP_ACK_SENT));
                    }
                } finally { wipe(frame); }
            }
            try (var input = getClass().getResourceAsStream("/ids/resource-chunks/input.bin")) {
                assertArrayEquals(input.readAllBytes(), java.nio.file.Files.readAllBytes(directory.toPath()
                        .resolve("native/7C48B838-FE90-4B21-839D-1A93B1A5C111-input.bin")));
            }
        } finally { PairedSyncResourceStore.setDirectoryForTesting(null); }
    }

    private static IdsModernSessionCoordinator.Output receiveNativeResourceFrame(
            IdsModernSessionCoordinator coordinator, byte[] frame) throws Exception {
        // The public API intentionally does not expose arbitrary resource TX.
        var field = IdsModernSessionCoordinator.class.getDeclaredField("dataByConnection");
        field.setAccessible(true);
        var lanes = (java.util.Map<?, ?>) field.get(coordinator);
        long connection = ((Number) lanes.keySet().iterator().next()).longValue();
        Class<?> builderClass = Class.forName(IdsModernSessionCoordinator.class.getName() + "$OutputBuilder");
        var constructor = builderClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object builder = constructor.newInstance();
        var receive = IdsModernSessionCoordinator.class.getDeclaredMethod("acceptDataBytes",
                long.class, byte[].class, builderClass);
        receive.setAccessible(true);
        receive.invoke(coordinator, connection, frame, builder);
        var build = builderClass.getDeclaredMethod("build");
        build.setAccessible(true);
        return (IdsModernSessionCoordinator.Output) build.invoke(builder);
    }

    @Test
    public void nativeResourceCancellationPreservesPartialWithoutAcknowledgingCompletion() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("ids-resource-cancel");
        PairedSyncResourceStore.setDirectoryForTesting(directory.toFile());
        List<String> diagnostics = new ArrayList<>();
        var previousLogger = IdsModernSessionCoordinator.diagnosticLogger;
        IdsModernSessionCoordinator.diagnosticLogger = diagnostics::add;
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var delivered = pump(phone, watch, start)) {
                assertEquals(1, delivered.count("phone", IdsModernSessionCoordinator.EventType.CONTROL_READY));
            }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var delivered = pump(phone, watch, start)) {
                assertTrue(delivered.count("phone", IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED) >= 1);
            }
            byte[] initial;
            try (var input = getClass().getResourceAsStream("/ids/resource-chunks/chunk-0.bin")) {
                initial = input.readAllBytes();
            }
            var header = (IdsSocketPairCodec.DataMessage) IdsSocketPairCodec.decode(initial);
            try (var received = receiveNativeResourceFrame(phone.coordinator, initial)) {
                var partial = directory.resolve("native/" + header.messageUuid + ".partial");
                byte[] before = java.nio.file.Files.readAllBytes(partial);
                var cancel = new IdsSocketPairCodec.DataMessage(
                        IdsSocketPairCodec.COMMAND_RESOURCE_TRANSFER, header.sequence,
                        header.streamId, 0, null, header.messageUuid, null,
                        new byte[]{3, 5}, null);
                try {
                    byte[] frame = IdsSocketPairCodec.encodeData(cancel);
                    try (var output = receiveNativeResourceFrame(phone.coordinator, frame)) {
                        assertEquals(0, countEvents(output, IdsModernSessionCoordinator.EventType.ACK_SENT));
                        assertEquals(0, countEvents(output, IdsModernSessionCoordinator.EventType.APP_ACK_SENT));
                        assertArrayEquals(before, java.nio.file.Files.readAllBytes(partial));
                        assertTrue(diagnostics.stream().anyMatch(s -> s.startsWith("RESOURCE RX CANCELLED:")
                                && s.contains("reason=5") && s.contains("resource not completed")));
                        assertFalse(diagnostics.stream().anyMatch(s -> s.startsWith("RESOURCE RX REJECTED:")));
                    } finally { wipe(frame); }
                } finally { cancel.destroy(); wipe(before); }
            } finally { header.destroy(); wipe(initial); }
        } finally {
            IdsModernSessionCoordinator.diagnosticLogger = previousLogger;
            PairedSyncResourceStore.setDirectoryForTesting(null);
        }
    }

    @Test
    public void failingObserverCannotChangeHelloOrPoisonConnection() {
        try (Side phone = phone(); Side watch = watch()) {
            int[] calls = {0};
            phone.coordinator.setOutgoingFrameObserver((kind, topic, type, response, frame) -> {
                calls[0]++;
                Arrays.fill(frame, (byte) 0);
                throw new IllegalStateException("diagnostic failure");
            });
            try (var start = phone.coordinator.startControl(); var delivered = pump(phone, watch, start)) {
                assertEquals(1, delivered.count("phone", IdsModernSessionCoordinator.EventType.CONTROL_READY));
            }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var delivered = pump(phone, watch, start)) {
                assertTrue(delivered.count("phone", IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED) >= 1);
            }
            assertEquals(1, calls[0]);
        }
    }

    @Test
    public void observesActualEncodedNrFrameBeforeProducerWipesIt() {
        List<byte[]> captured = new ArrayList<>();
        try (Side phone = phone(); Side watch = watch()) {
            phone.coordinator.setOutgoingFrameObserver((kind, topic, type, response, frame) -> {
                if ("protobuf".equals(kind)) captured.add(frame.clone());
            });
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            var check = NanoRegistryClassDCodec.PairingModeRequest.modernIos26_6Ultra2(1, 25);
            try (var sent = phone.coordinator.sendClassD(check); var delivered = pump(phone, watch, sent)) {
                assertEquals(1, captured.size());
                var decoded = (IdsSocketPairCodec.ProtobufMessage) IdsSocketPairCodec.decode(captured.get(0));
                try {
                    var received = delivered.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,
                            NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                    assertEquals(3, decoded.protobufType);
                    assertEquals(received.sequence, decoded.sequence);
                    assertArrayEquals(received.payload, decoded.payload);
                } finally { decoded.destroy(); }
            } finally { check.destroy(); }
        } finally { captured.forEach(frame -> Arrays.fill(frame, (byte) 0)); }
    }

    @Test
    public void dictionaryIsDeliveredAcknowledgedAndKeepsItsTopicAcrossFragmentation() {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_C_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            String topic = IdsApplicationRoute.BULLETIN_DISTRIBUTOR_SERVICE;
            for (int size : new int[]{80, 20000, 120}) {
                byte[] payload = AppleBinaryPropertyList.encode(java.util.Map.of("fixture", new byte[size]));
                try (var sent = watch.coordinator.sendApplicationDictionary(topic, payload);
                     var received = pump(watch, phone, sent)) {
                    var message = received.first("phone", IdsModernSessionCoordinator.EventType.DATA_RECEIVED, topic);
                    assertEquals(6, message.command);
                    assertArrayEquals(payload, message.payload);
                    assertEquals(message.sequence, received.first("watch", IdsModernSessionCoordinator.EventType.ACK_RECEIVED, null).sequence);
                    assertEquals(0, received.count("phone", IdsModernSessionCoordinator.EventType.UNKNOWN_COMMAND_IGNORED));
                    assertTrue(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_C));
                    assertFalse(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_D));
                }
            }
        }
    }

    @Test public void wifiArchiveUsesClassCDataAndRequestsClientAckWithoutInventingReply() {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_C_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            byte[] payload = WifiNetworkSyncCodec.addWpa2("synthetic-wifi", "testpass123");
            try (var sent = phone.coordinator.sendApplicationData(WifiNetworkSyncCodec.TOPIC, payload);
                 var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.DATA_RECEIVED, WifiNetworkSyncCodec.TOPIC);
                assertArrayEquals(payload, message.payload);
                assertEquals(IdsSocketPairCodec.COMMAND_DATA, message.command);
                assertEquals(IdsSocketPairCodec.FLAG_WANTS_APP_ACK, message.flags & IdsSocketPairCodec.FLAG_WANTS_APP_ACK);
                assertEquals(0, message.flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE);
                assertTrue(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_C));
                assertFalse(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_D));
            } finally { wipe(payload); }
        }
    }

    @Test
    public void credentialsDataUsesClassDAndSurvivesFragmentationAndTopicReuse() {
        try (Side phone = phone(); Side watch = watch()) {
            assertEquals("Hello not received", phone.coordinator.peerIdentitySummary(null, null));
            assertFalse(phone.coordinator.peerHelloMatchesIdentity("00000000-0000-4000-8000-000000000002"));
            try (var started = phone.coordinator.startControl(); var delivered = pump(phone, watch, started)) {
                assertEquals(1, delivered.count("phone", IdsModernSessionCoordinator.EventType.CONTROL_READY));
            }
            String watchId = "00000000-0000-4000-8000-000000000002";
            assertTrue(phone.coordinator.peerHelloMatchesIdentity(watchId));
            assertFalse(phone.coordinator.peerHelloMatchesIdentity(UUID.randomUUID().toString()));
            assertFalse(phone.coordinator.peerHelloMatchesIdentity("malformed"));
            String identity = phone.coordinator.peerIdentitySummary(watchId, UUID.randomUUID().toString());
            assertTrue(identity.contains("matchesAuthenticatedIdsId=true"));
            assertTrue(identity.contains("matchesNetworkRelayId=false"));
            assertFalse(identity.contains(watchId));
            assertTrue(phone.coordinator.peerIdentitySummary(null, "malformed")
                    .contains("matchesAuthenticatedIdsId=false"));
            try (var started = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var delivered = pump(phone, watch, started)) {
                assertTrue(delivered.count("phone", IdsModernSessionCoordinator.EventType.DATA_CHANNEL_JOINED) >= 1);
            }
            for (int size : new int[]{109, 20000, 249}) {
                byte[] payload = new byte[size];
                new SecureRandom().nextBytes(payload);
                try (var sent = phone.coordinator.sendApplicationData(IdsDeviceInfoExchange.TOPIC, payload);
                     var delivered = pump(phone, watch, sent)) {
                    ObservedEvent outgoing = delivered.first("phone", IdsModernSessionCoordinator.EventType.DATA_SENT,
                            IdsDeviceInfoExchange.TOPIC);
                    ObservedEvent received = delivered.first("watch", IdsModernSessionCoordinator.EventType.DATA_RECEIVED,
                            IdsDeviceInfoExchange.TOPIC);
                    assertArrayEquals(payload, received.payload);
                    assertEquals(outgoing.sequence, received.sequence);
                    assertEquals(outgoing.messageUuid, received.messageUuid);
                    assertEquals(0, received.flags & (IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE
                            | IdsSocketPairCodec.FLAG_WANTS_APP_ACK));
                    assertTrue(delivered.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_D));
                    assertFalse(delivered.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_C));
                    assertEquals(outgoing.sequence, delivered.first("phone",
                            IdsModernSessionCoordinator.EventType.ACK_RECEIVED, null).sequence);
                } finally { wipe(payload); }
            }
        }
    }

    @Test
    public void eachPeerAnnouncesItsOwnDirectMessagingSupportOnce() {
        for (boolean supported : new boolean[]{false, true}) {
            try (Side phone = phone(supported); Side watch = watch();
                 var start = phone.coordinator.startControl(); var exchanged = pump(phone, watch, start)) {
                assertEquals(1, exchanged.count("phone", IdsModernSessionCoordinator.EventType.CONTROL_READY));
                assertEquals(supported ? 1 : 0,
                        exchanged.count("phone", IdsModernSessionCoordinator.EventType.DIRECT_MESSAGING_INFO_SENT));
                assertEquals(1, exchanged.count("watch", IdsModernSessionCoordinator.EventType.DIRECT_MESSAGING_INFO_SENT));
                assertEquals(supported, exchanged.events.stream().anyMatch(event -> event.owner.equals("watch")
                        && "DirectMsgInfo/version=1,features=0x0".equals(event.topic)));
            }
        }
    }

    @Test
    public void propertyQueryAndDiagnosticPingCarryExplicitResponseContracts() {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            for (String topic : new String[]{NanoRegistryPropertyCodec.CLASS_D_SERVICE,
                    NanoRegistryPropertyCodec.CLASS_C_SERVICE}) {
                try (var start = phone.coordinator.startInitialLane(topic);
                     var ignored = pump(phone, watch, start)) { }
            }
            var query = new NanoRegistryPropertyCodec.PropertyRequest();
            try (var sent = phone.coordinator.sendClassC(query); var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,
                        NanoRegistryPropertyCodec.CLASS_C_SERVICE);
                assertEquals(4, message.protobufType);
                assertEquals(IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE,
                        message.flags & (IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE | IdsSocketPairCodec.FLAG_WANTS_APP_ACK));
                assertEquals(message.messageUuid, message.outgoingResponseIdentifier);
            } finally { query.destroy(); }
            var ping = new NanoRegistryClassDCodec.PingRequest(300, 8, 0, null);
            try (var sent = phone.coordinator.sendClassDRequestWithClientAcknowledgement(ping);
                 var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,
                        NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                assertEquals(5, message.protobufType);
                assertEquals(IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE | IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                        message.flags & (IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE | IdsSocketPairCodec.FLAG_WANTS_APP_ACK));
                assertEquals(message.messageUuid, message.outgoingResponseIdentifier);
            } finally { ping.destroy(); }
        }
    }

    @Test
    public void genericNanoRegistryQueriesAlsoRequestAnActualPeerResponse() {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            for (String topic : new String[]{NanoRegistryPropertyCodec.CLASS_D_SERVICE, NanoRegistryPropertyCodec.CLASS_C_SERVICE}) {
                try (var start = phone.coordinator.startInitialLane(topic); var ignored = pump(phone, watch, start)) { }
                int type = topic.equals(NanoRegistryPropertyCodec.CLASS_C_SERVICE) ? 4 : 5;
                byte[] payload = type == 4 ? new byte[0] : NanoRegistryClassDCodec.encode(new NanoRegistryClassDCodec.PingRequest(300,8,0,null));
                try (var sent = phone.coordinator.sendApplicationProtobuf(topic,type,payload);
                     var received = pump(phone, watch, sent)) {
                    var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,topic);
                    assertEquals(IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE | IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                            message.flags & (IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE | IdsSocketPairCodec.FLAG_WANTS_APP_ACK));
                    assertEquals(message.messageUuid,message.outgoingResponseIdentifier);
                } finally { wipe(payload); }
            }
            String nss = NanoSystemSettingsDiagnostics.TOPIC;
            assertThrows(IllegalArgumentException.class,
                    () -> phone.coordinator.sendApplicationProtobuf(nss, 8, new byte[0]));
            assertThrows(IllegalArgumentException.class,
                    () -> phone.coordinator.sendApplicationData(nss, new byte[0]));
            try (var sent = phone.coordinator.sendApplicationProtobuf(nss, 24, new byte[0]);
                 var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, nss);
                assertEquals(24, (int) message.protobufType);
                assertEquals(5, message.flags & 5);
                assertEquals(message.messageUuid, message.outgoingResponseIdentifier);
            }
            String diagnostics = SysdiagnoseArchiveInventory.TOPIC;
            byte[] nonce = "6B48CE92-7491-49EC-B1D7-231F0B550127".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            assertThrows(IllegalArgumentException.class,
                    () -> phone.coordinator.sendApplicationProtobuf(diagnostics, 2, nonce));
            assertThrows(IllegalArgumentException.class,
                    () -> phone.coordinator.sendApplicationData(diagnostics, nonce));
            assertThrows(IllegalArgumentException.class,
                    () -> phone.coordinator.sendApplicationDictionary(diagnostics, nonce));
            try (var sent = phone.coordinator.sendApplicationProtobuf(diagnostics, 6, nonce);
                 var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, diagnostics);
                assertEquals(6, message.protobufType);
                assertFalse(message.response);
                assertEquals(4, message.flags & 5); // native result arrives as a new request, not a peer response
                assertArrayEquals(nonce, message.payload);
            }
            try (var sent = phone.coordinator.sendApplicationProtobuf(diagnostics, 1, nonce);
                 var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, diagnostics);
                assertEquals(1, (int) message.protobufType);
                assertFalse(message.response);
                assertArrayEquals(nonce, message.payload);
            }
        }
    }

    @Test public void explicitWatchRebootCarriesOnlyEmptyNativeNss19AndDoesNotInventAResponse() {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            assertThrows(IllegalArgumentException.class, () ->
                    phone.coordinator.sendApplicationProtobuf(NativeWatchReboot.TOPIC, 19, new byte[0]));
            try (var sent = phone.coordinator.sendWatchReboot(); var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,
                        NativeWatchReboot.TOPIC);
                assertEquals(19, (int) message.protobufType);
                assertEquals(0, message.payload.length);
                assertEquals(IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                        message.flags & (IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE | IdsSocketPairCodec.FLAG_WANTS_APP_ACK));
            }
        }
    }

    @Test public void durableHealthRequestRoutesCiphertextOverClassCWithExactReplyAndAckIdentity() {
        try(Side phone=phone();Side watch=watch()) {
            try(var start=phone.coordinator.startControl();var ignored=pump(phone,watch,start)) { }
            try(var start=phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);var ignored=pump(phone,watch,start)) { }
            UUID id=UUID.randomUUID();String topic=IdsApplicationRoute.HEALTH_SYNC_SERVICE;
            byte[] ciphertext=AppleBinaryPropertyList.encode(java.util.Map.of("ekd",new byte[]{2,0,1,0},"sed",new byte[16]));
            try(var sent=phone.coordinator.sendHealthSyncRequest(ciphertext,id);var received=pump(phone,watch,sent)) {
                var message=received.first("watch",IdsModernSessionCoordinator.EventType.DATA_RECEIVED,topic);
                assertArrayEquals(ciphertext,message.payload);assertEquals(id.toString().toUpperCase(java.util.Locale.ROOT),message.messageUuid);
                assertEquals(message.messageUuid,message.outgoingResponseIdentifier);assertEquals(5,message.flags&5);
                assertTrue(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_C));
                assertEquals(message.messageUuid,received.first("phone",IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED,topic).peerResponseIdentifier);
            }
            try(var sent=watch.coordinator.sendApplicationDataResponse(topic,ciphertext,id.toString().toUpperCase(java.util.Locale.ROOT));
                var received=pump(watch,phone,sent)) {
                var response=received.first("phone",IdsModernSessionCoordinator.EventType.DATA_RECEIVED,topic);
                assertEquals(id.toString().toUpperCase(java.util.Locale.ROOT),response.peerResponseIdentifier);assertArrayEquals(ciphertext,response.payload);
            }
            assertThrows(IllegalArgumentException.class,()->phone.coordinator.sendHealthSyncRequest(ciphertext,id));
            assertThrows(IllegalArgumentException.class,()->phone.coordinator.sendHealthSyncRequest(new byte[]{1,0,0},UUID.randomUUID()));
        }
    }

    @Test public void nativeClockfaceReadRequestCarriesReplyAndAckFlagsAndExactResponseIdentity() {
        for (String unfinished : java.util.Arrays.asList(null, "W2026-10-06T03:33:11.407")) {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            UUID peer = UUID.randomUUID();
            byte[] request = ClockFaceSyncHeaderCodec.fullRequest(ClockFaceSyncHeaderCodec.header(peer,
                    UUID.randomUUID(), 1, 1791257108000L, java.util.Map.of(peer, 1L)), unfinished);
            assertEquals(unfinished, ClockFaceSyncHeaderCodec.recoverySession(request));
            String topic = IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE, uuid;
            try (var sent = phone.coordinator.sendClockFaceCollectionRequest(request);
                 var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.DATA_RECEIVED, topic);
                assertArrayEquals(request, message.payload);
                assertEquals(5, message.flags & 5);
                assertTrue(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_D));
                uuid = message.messageUuid; assertEquals(uuid, message.outgoingResponseIdentifier);
                assertEquals(uuid, received.first("phone", IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED, topic).peerResponseIdentifier);
            }
            try (var sent = watch.coordinator.sendApplicationDataResponse(topic, new byte[]{0x65, 0}, uuid);
                 var received = pump(watch, phone, sent)) {
                var response = received.first("phone", IdsModernSessionCoordinator.EventType.DATA_RECEIVED, topic);
                assertEquals(uuid, response.peerResponseIdentifier);
                assertEquals(0, response.flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE);
            }
            byte[] restart = request.clone(); restart[0] = 0x68;
            assertThrows(IllegalArgumentException.class, () -> phone.coordinator.sendClockFaceCollectionRequest(restart));
        }
        }
    }

    @Test public void automaticClockfaceReceiptsUseOutgoingMapAndFreshSequenceAfterAdvertisement() throws Exception {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            String topic = IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE;
            // Both peers allocate ID 1 independently, for different services.
            // After three inline messages the phone advertises clockface ID 2;
            // subsequent Watch messages omit the topic and use that ID.
            var field = IdsModernSessionCoordinator.class.getDeclaredField("control");
            field.setAccessible(true);
            var phoneMap = ((IdsControlChannelSession) field.get(phone.coordinator)).serviceMap();
            var watchMap = ((IdsControlChannelSession) field.get(watch.coordinator)).serviceMap();
            phoneMap.routeOutgoing(IdsDeviceInfoExchange.TOPIC);
            watchMap.routeOutgoing(topic);
            try (var sent = phone.coordinator.sendApplicationData(IdsDeviceInfoExchange.TOPIC, new byte[]{1});
                 var ignored = pump(phone, watch, sent)) { }
            long previousReceipt = 0;
            for (int index = 0; index < 6; index++) {
                byte[] packet = ClockFaceDeltaProtocol.start(
                        ClockFaceDeltaProtocolTest.header(ClockFaceDeltaProtocolTest.WATCH, index + 1),
                        ClockFaceDeltaProtocolTest.SESSION);
                try (var sent = watch.coordinator.sendClockFaceDeltaRequest(packet);
                     var delivered = pump(watch, phone, sent)) {
                    var incoming = delivered.first("phone", IdsModernSessionCoordinator.EventType.DATA_RECEIVED, topic);
                    var receipt = delivered.first("watch", IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED, topic);
                    assertEquals(incoming.messageUuid, receipt.peerResponseIdentifier);
                    assertEquals(previousReceipt + 1, receipt.sequence);
                    assertNotEquals(incoming.sequence, receipt.sequence);
                    assertEquals(incoming.sequence,
                            delivered.first("watch", IdsModernSessionCoordinator.EventType.ACK_RECEIVED, null).sequence);
                    previousReceipt = receipt.sequence;
                } finally { wipe(packet); }
            }
        }
    }

    @Test public void nativeDeltaPacketsUseClassDDataAndUniqueCorrelatedResponseIdentifiers() {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            String topic = IdsApplicationRoute.CLOCKFACE_SYNC_SERVICE;
            var ids = new java.util.HashSet<String>();
            byte[][] packets = {
                ClockFaceDeltaProtocol.start(ClockFaceDeltaProtocolTest.header(ClockFaceDeltaProtocolTest.PHONE, 1), ClockFaceDeltaProtocolTest.SESSION),
                ClockFaceDeltaProtocol.batch(ClockFaceDeltaProtocolTest.header(ClockFaceDeltaProtocolTest.PHONE, 2), ClockFaceDeltaProtocolTest.SESSION,
                    java.util.List.of(NtkFaceChangeEncoder.select(ClockFaceDeltaProtocolTest.FACE))),
                ClockFaceDeltaProtocol.end(ClockFaceDeltaProtocolTest.header(ClockFaceDeltaProtocolTest.PHONE, 3), ClockFaceDeltaProtocolTest.SESSION)
            };
            for (byte[] packet : packets) {
                String uuid;
                try (var sent = phone.coordinator.sendClockFaceDeltaRequest(packet); var received = pump(phone, watch, sent)) {
                    var message = received.first("watch", IdsModernSessionCoordinator.EventType.DATA_RECEIVED, topic);
                    assertArrayEquals(packet, message.payload); assertEquals(5, message.flags & 5);
                    assertTrue(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_D));
                    uuid = message.messageUuid; assertTrue(ids.add(uuid));
                    assertEquals(uuid, message.outgoingResponseIdentifier);
                    assertEquals(uuid, received.first("phone", IdsModernSessionCoordinator.EventType.APP_ACK_RECEIVED, topic).peerResponseIdentifier);
                }
                byte[] reply = ClockFaceDeltaProtocolTest.reply(packet[0] & 255, ClockFaceDeltaProtocolTest.SESSION,
                        ClockFaceDeltaProtocolTest.WATCH, true, false, false, 0);
                try (var sent = watch.coordinator.sendApplicationDataResponse(topic, reply, uuid); var received = pump(watch, phone, sent)) {
                    var response = received.first("phone", IdsModernSessionCoordinator.EventType.DATA_RECEIVED, topic);
                    assertEquals(uuid, response.peerResponseIdentifier); assertEquals(0, response.flags & 1);
                    assertEquals(packet[0] & 255, ClockFaceDeltaProtocol.response(response.payload).type());
                }
            }
            assertThrows(IllegalArgumentException.class, () -> phone.coordinator.sendClockFaceDeltaRequest(new byte[]{0x68, 0, 0, 0}));
        }
    }

    @Test public void nativeFindMyPingUsesClassDAndSameTypeCorrelatedResponse() {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            String topic = IdsApplicationRoute.FIND_MY_LOCAL_SERVICE;
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            byte[] request = new FindMyLocalDeviceCodec.PlaySoundRequest(1700000000.25, null).encode();
            String uuid;
            try (var sent = phone.coordinator.sendApplicationProtobuf(topic, 1, request);
                 var received = pump(phone, watch, sent)) {
                var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, topic);
                assertFalse(message.response);
                assertEquals(1, (int) message.protobufType);
                assertArrayEquals(request, message.payload);
                assertEquals(5, message.flags & 5);
                assertTrue(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_D));
                assertFalse(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_C));
                uuid = message.messageUuid;
                assertEquals(uuid, message.outgoingResponseIdentifier);
            }
            try (var sent = watch.coordinator.sendFindMyLocalResponse(1, new byte[]{8, 0}, uuid);
                 var received = pump(watch, phone, sent)) {
                var message = received.first("phone", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, topic);
                assertTrue(message.response);
                assertEquals(1, (int) message.protobufType);
                assertEquals(uuid, message.peerResponseIdentifier);
                assertEquals(0, message.flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE);
                assertFalse(FindMyLocalDeviceCodec.PlaySoundResponse.decode(message.payload).didPlay());
            }
            assertThrows(IllegalArgumentException.class,
                    () -> phone.coordinator.sendApplicationProtobuf(topic, 3, new byte[0]));
        }
    }

    @Test public void watchPhonePingRequiresPrivateEffectCompletionBeforeNativeReply() throws Exception {
        try (Side phone = phone(); Side watch = watch()) {
            try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
            String topic = IdsApplicationRoute.FIND_MY_LOCAL_SERVICE;
            try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                 var ignored = pump(phone, watch, start)) { }
            var ledger = new FindMyPhoneSession(); ledger.reset(UUID.randomUUID());
            var backend = new FindMyPhoneClaims.Backend() {
                byte[] saved = new byte[0];
                public byte[] read() { return saved.clone(); }
                public void write(byte[] frame) { saved = frame.clone(); }
            };
            var claims = new FindMyPhoneClaims(backend);
            String pairing = UUID.randomUUID().toString();
            int effects = 0;
            for (int type : new int[]{1, 2}) {
                byte[] body = new FindMyLocalDeviceCodec.PlaySoundRequest(1791250000.25, null).encode();
                FindMyPhoneIpcCodec.Request request;
                try (var sent = watch.coordinator.sendApplicationProtobuf(topic, type, body);
                     var received = pump(watch, phone, sent)) {
                    var message = received.first("phone", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, topic);
                    assertFalse(message.response);
                    request = ledger.receive(message.protobufType, message.response, message.messageUuid,
                            message.payload, 1791250000250L, 100).request();
                    assertTrue(received.sawDataClass(OrdinaryIkeAuth.DataClass.CLASS_D));
                    assertEquals(0, received.count("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED));
                }
                var privateRequest = FindMyPhoneIpcCodec.decodeRequest(FindMyPhoneIpcCodec.encode(request));
                var claim = claims.claim(pairing, privateRequest, 1791250000250L);
                assertTrue(claim.fresh());
                effects++; // Synthetic effect: actual AudioTrack/torch is a separate hardware acceptance.
                boolean didPlay = type == 1;
                claims.complete(claim, didPlay);
                var result = FindMyPhoneIpcCodec.decodeResult(FindMyPhoneIpcCodec.encode(
                        new FindMyPhoneIpcCodec.Result(request.epoch(), request.messageId(), type, didPlay)));
                assertEquals(request, ledger.complete(result, 101));
                assertNull(ledger.complete(result, 102));
                try (var sent = phone.coordinator.sendFindMyLocalResponse(type,
                        new FindMyLocalDeviceCodec.PlaySoundResponse(didPlay).encode(), request.messageId());
                     var received = pump(phone, watch, sent)) {
                    var message = received.first("watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED, topic);
                    assertTrue(message.response); assertEquals(type, message.protobufType);
                    assertEquals(request.messageId(), message.peerResponseIdentifier);
                    assertEquals(0, message.flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE);
                    assertEquals(didPlay, FindMyLocalDeviceCodec.PlaySoundResponse.decode(message.payload).didPlay());
                }
                assertFalse(new FindMyPhoneClaims(backend).claim(pairing, request, 1791250000251L).fresh());
            }
            assertEquals(2, effects);
        }
    }

    @Test
    public void cloudDataLanesDoNotBecomeControlBeforeOrAfterHello() {
        try (Side phone = phone(); Side watch = watch()) {
            for (int protection : new int[]{IdsUtunConnectionName.PROTECTION_CLASS_D,
                    IdsUtunConnectionName.PROTECTION_CLASS_C}) {
                IdsServiceConnectorName cloud = IdsServiceConnectorName.localDelivery(
                        IdsUtunConnectionName.defaultPairedCloud(
                                IdsUtunConnectionName.PRIORITY_URGENT, protection));
                if (protection == IdsUtunConnectionName.PROTECTION_CLASS_C) {
                    IdsControlChannelCodec.SetupChannelMessage setup =
                            new IdsControlChannelCodec.SetupChannelMessage(
                                    IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                                    IdsControlChannelCodec.PROTOCOL_TCP, 20001,
                                    IdsIpsecServiceRoute.CLOUD_LISTENER_PORT,
                                    "11111111-1111-4111-8111-111111111111", null,
                                    IdsServiceConnectorName.LOCAL_ACCOUNT,
                                    IdsServiceConnectorName.LOCAL_DELIVERY_SERVICE,
                                    cloud.name, null);
                    try (IdsModernSessionCoordinator.Output sent =
                                 watch.coordinator.sendControlMessage(setup);
                         PumpResult prepared = pump(watch, phone, sent)) {
                        assertTrue(prepared.count("phone",
                                IdsModernSessionCoordinator.EventType.DATA_SETUP_ESTABLISHED) >= 1);
                    } finally {
                        setup.destroy();
                    }
                }
                try (IdsModernSessionCoordinator.Output started =
                             watch.coordinator.startOutgoingService(cloud);
                     PumpResult joined = pump(watch, phone, started)) {
                    assertTrue(joined.count("phone",
                            IdsModernSessionCoordinator.EventType.DATA_SERVICE_ACCEPTED) >= 1);
                    if (protection == IdsUtunConnectionName.PROTECTION_CLASS_C) {
                        assertTrue(joined.count("phone",
                                IdsModernSessionCoordinator.EventType.HANDSHAKE_RECEIVED) >= 1);
                    }
                    assertEquals(0, joined.count("phone",
                            IdsModernSessionCoordinator.EventType.CONTROL_SERVICE_ACCEPTED));
                    assertEquals(0, joined.count("phone",
                            IdsModernSessionCoordinator.EventType.CONTROL_HELLO_SENT));
                }
                if (protection == IdsUtunConnectionName.PROTECTION_CLASS_D) {
                    assertFalse(phone.coordinator.snapshot().peerHelloReceived);
                    try (IdsModernSessionCoordinator.Output control =
                                 phone.coordinator.startControl();
                         PumpResult accepted = pump(phone, watch, control)) {
                        assertEquals(1, accepted.count("phone",
                                IdsModernSessionCoordinator.EventType.CONTROL_READY));
                    }
                }
                assertTrue(phone.coordinator.snapshot().peerHelloReceived);
            }
        }
    }

    @Test
    public void cloudAndOrdinaryClassDKeepSeparateConnectionsInEitherArrivalOrder() {
        for (boolean cloudFirst : new boolean[]{false, true}) {
            try (Side phone = phone(); Side watch = watch()) {
                try (var start = phone.coordinator.startControl(); var ignored = pump(phone, watch, start)) { }
                long ordinaryConnection = 0;
                for (boolean cloud : new boolean[]{cloudFirst, !cloudFirst}) {
                    if (cloud) {
                        var name = IdsServiceConnectorName.localDelivery(IdsUtunConnectionName.defaultPairedCloud(
                                IdsUtunConnectionName.PRIORITY_URGENT, IdsUtunConnectionName.PROTECTION_CLASS_D));
                        var setup = new IdsControlChannelCodec.SetupChannelMessage(
                                IdsControlChannelCodec.TYPE_SETUP_CHANNEL, IdsControlChannelCodec.PROTOCOL_TCP,
                                20011, IdsIpsecServiceRoute.CLOUD_LISTENER_PORT,
                                "33333333-3333-4333-8333-333333333333", null,
                                IdsServiceConnectorName.LOCAL_ACCOUNT, IdsServiceConnectorName.LOCAL_DELIVERY_SERVICE,
                                name.name, null);
                        try (var start = watch.coordinator.sendControlMessage(setup);
                             var ignored = pump(watch, phone, start)) { }
                        finally { setup.destroy(); }
                        try (var start = watch.coordinator.startOutgoingService(name);
                             var joined = pump(watch, phone, start)) {
                            assertTrue(joined.count("phone", IdsModernSessionCoordinator.EventType.DATA_SERVICE_ACCEPTED) >= 1);
                        }
                        if (cloudFirst) {
                            org.junit.Assert.assertThrows(IllegalStateException.class,
                                    () -> phone.coordinator.dataChannel(NanoRegistryPropertyCodec.CLASS_D_SERVICE));
                        } else {
                            assertEquals(ordinaryConnection,
                                    phone.coordinator.dataChannel(NanoRegistryPropertyCodec.CLASS_D_SERVICE).connectionId);
                        }
                    } else {
                        try (var start = phone.coordinator.startInitialLane(NanoRegistryPropertyCodec.CLASS_D_SERVICE);
                             var ignored = pump(phone, watch, start)) { }
                        ordinaryConnection = phone.coordinator.dataChannel(NanoRegistryPropertyCodec.CLASS_D_SERVICE).connectionId;
                        assertTrue(ordinaryConnection != 0);
                    }
                }
                assertEquals(2, phone.coordinator.snapshot().joinedDataCount);
                byte[] payload = {1, 2, 3};
                try (var sent = phone.coordinator.sendApplicationData(IdsDeviceInfoExchange.TOPIC, payload);
                     var delivered = pump(phone, watch, sent)) {
                    assertArrayEquals(payload, delivered.first("watch", IdsModernSessionCoordinator.EventType.DATA_RECEIVED,
                            IdsDeviceInfoExchange.TOPIC).payload);
                    assertEquals(ordinaryConnection,
                            phone.coordinator.dataChannel(IdsDeviceInfoExchange.TOPIC).connectionId);
                }
            }
        }
    }

    @Test
    public void incomingPlainSetupChannelIsRepliedAndDataConnectorStarts() {
        try (Side phone =
                     phone();
             Side watch =
                     watch()) {
            try (IdsModernSessionCoordinator.Output started =
                         phone.coordinator.startControl();
                 PumpResult control =
                         pump(
                                 phone,
                                 watch,
                                 started)) {
                assertEquals(
                        1,
                        control.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.CONTROL_READY));
            }

            IdsControlChannelCodec.SetupChannelMessage request =
                    new IdsControlChannelCodec.SetupChannelMessage(
                            IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                            IdsControlChannelCodec.PROTOCOL_TCP,
                            20001,
                            IdsControlChannelCodec.DATA_PORT,
                            "11111111-1111-4111-8111-111111111111",
                            null,
                            IdsServiceConnectorName.LOCAL_ACCOUNT,
                            IdsServiceConnectorName.LOCAL_DELIVERY_SERVICE,
                            IdsUtunConnectionName.defaultPaired(
                                    IdsUtunConnectionName.PRIORITY_SYNC,
                                    IdsUtunConnectionName.PROTECTION_CLASS_D),
                            null);
            try (IdsModernSessionCoordinator.Output sent =
                         watch.coordinator.sendControlMessage(
                                 request);
                 PumpResult delivered =
                         pump(
                                 watch,
                                 phone,
                                 sent)) {
                assertTrue(
                        delivered.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_SETUP_REPLIED)
                                >= 1);
                assertTrue(
                        delivered.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_SETUP_ESTABLISHED)
                                >= 1);
                assertEquals(
                        0,
                        delivered.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CONNECT_STARTED));
            } finally {
                request.destroy();
            }

            IdsServiceConnectorName syncD =
                    IdsServiceConnectorName.localDelivery(
                            IdsUtunConnectionName.defaultPaired(
                                    IdsUtunConnectionName.PRIORITY_SYNC,
                                    IdsUtunConnectionName.PROTECTION_CLASS_D));
            try (IdsModernSessionCoordinator.Output syn =
                         watch.coordinator.startOutgoingService(
                                 syncD);
                 PumpResult joined =
                         pump(
                                 watch,
                                 phone,
                                 syn)) {
                assertTrue(
                        joined.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED)
                                >= 1);
                assertTrue(
                        joined.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.HANDSHAKE_SENT)
                                >= 1);
                assertTrue(
                        joined.count(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.HANDSHAKE_RECEIVED)
                                >= 1);
            }
        }
    }

    @Test
    public void incomingLaneParksProtobufUntilHandshakeCompletes() {
        exerciseQueuedSnapshot(false);
    }

    @Test
    public void incomingLaneRetainsLargeSnapshotAndExplicitFragmentIdUntilHandshake() {
        exerciseQueuedSnapshot(true);
    }

    private void exerciseQueuedSnapshot(boolean largeQueuedSnapshot) {
        try (Side phone =
                     phone();
             Side watch =
                     watch()) {
            try (IdsModernSessionCoordinator.Output started =
                         phone.coordinator.startControl();
                 PumpResult control =
                         pump(
                                 phone,
                                 watch,
                                 started)) {
                assertEquals(
                        1,
                        control.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.CONTROL_READY));
            }

            IdsControlChannelCodec.SetupChannelMessage request =
                    new IdsControlChannelCodec.SetupChannelMessage(
                            IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                            IdsControlChannelCodec.PROTOCOL_TCP,
                            20001,
                            IdsControlChannelCodec.DATA_PORT,
                            "11111111-1111-4111-8111-111111111111",
                            null,
                            IdsServiceConnectorName.LOCAL_ACCOUNT,
                            IdsServiceConnectorName.LOCAL_DELIVERY_SERVICE,
                            IdsUtunConnectionName.defaultPaired(
                                    IdsUtunConnectionName.PRIORITY_URGENT,
                                    IdsUtunConnectionName.PROTECTION_CLASS_C),
                            null);
            try (IdsModernSessionCoordinator.Output sent =
                         watch.coordinator.sendControlMessage(
                                 request);
                 PumpResult delivered =
                         pump(
                                 watch,
                                 phone,
                                 sent)) {
                assertTrue(
                        delivered.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_SETUP_REPLIED)
                                >= 1);
            } finally {
                request.destroy();
            }

            NanoRegistryPropertyCodec.PropertiesChanged snapshot =
                    largeQueuedSnapshot
                            ? PhonePropertySnapshot26_6.fullSnapshot(java.util.Map.of(
                                    "productType", NanoRegistryPropertyCodec.PropertyValue.string("iPhone18,1"),
                                    "capabilities", NanoRegistryUuidSetCodec.encode(
                                            IosCompanionProfile26_6.PHONE_CAPABILITY_UUIDS)), 1.0)
                            : classCSnapshot("iPhone18,1", 1.0);
            try {
                try (IdsModernSessionCoordinator.Output parked =
                             phone.coordinator.sendClassC(
                                     new IdsModernSessionCoordinator.MessageMetadata(
                                             17, "00000000-0000-4000-8000-000000000017",
                                             IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE,
                                             null, null, 0xaabbccddL), snapshot)) {
                    assertEquals(
                            1,
                            countEvents(
                                    parked,
                                    IdsModernSessionCoordinator
                                            .EventType.PROTOBUF_SENT));
                    assertEquals(
                            IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE,
                            protobufSentFlags(
                                    parked)
                                    & (IdsSocketPairCodec
                                    .FLAG_EXPECTS_PEER_RESPONSE
                                    | IdsSocketPairCodec
                                    .FLAG_WANTS_APP_ACK));
                    assertTrue(
                            phone.coordinator.hasPendingApplicationFrames());
                }

                IdsServiceConnectorName urgentC =
                        IdsServiceConnectorName.localDelivery(
                                IdsUtunConnectionName.defaultPaired(
                                        IdsUtunConnectionName.PRIORITY_URGENT,
                                        IdsUtunConnectionName.PROTECTION_CLASS_C));
                try (IdsModernSessionCoordinator.Output syn =
                             watch.coordinator.startOutgoingService(
                                     urgentC);
                     PumpResult joined =
                             pump(
                                     watch,
                                     phone,
                                     syn)) {
                    assertTrue(
                            joined.count(
                                    "phone",
                                    IdsModernSessionCoordinator
                                            .EventType.HANDSHAKE_SENT)
                                    >= 1);
                    assertTrue(
                            joined.count(
                                    "phone",
                                    IdsModernSessionCoordinator
                                            .EventType.HANDSHAKE_RECEIVED)
                                    >= 1);
                    ObservedEvent queuedReceived = joined.first(
                            "watch", IdsModernSessionCoordinator.EventType.PROTOBUF_RECEIVED,
                            NanoRegistryPropertyCodec.CLASS_C_SERVICE);
                    assertArrayEquals(NanoRegistryPropertyCodec.encode(snapshot), queuedReceived.payload);
                    assertTrue(
                            joined.count(
                                    "watch",
                                    IdsModernSessionCoordinator
                                            .EventType.ACK_SENT)
                                    >= 1);
                    assertFalse(
                            phone.coordinator.hasPendingApplicationFrames());
                    java.util.Map<String, NanoRegistryPropertyCodec.PropertyValue>
                            largeValues =
                            new java.util.HashMap<>();
                    largeValues.put(
                            "productType",
                            NanoRegistryPropertyCodec.PropertyValue.string(
                                    "iPhone18,1"));
                    largeValues.put(
                            "capabilities",
                            NanoRegistryUuidSetCodec.encode(
                                    IosCompanionProfile26_6
                                            .PHONE_CAPABILITY_UUIDS));
                    NanoRegistryPropertyCodec.PropertiesChanged large =
                            PhonePropertySnapshot26_6.fullSnapshot(
                                    largeValues,
                                    2.0);
                    try (IdsModernSessionCoordinator.Output sent =
                                 phone.coordinator.sendClassC(
                                         large);
                         PumpResult delivered =
                                 pump(
                                         phone,
                                         watch,
                                         sent)) {
                        ObservedEvent received =
                                delivered.first(
                                        "watch",
                                        IdsModernSessionCoordinator
                                                .EventType.PROTOBUF_RECEIVED,
                                        NanoRegistryPropertyCodec
                                                .CLASS_C_SERVICE);
                        NanoRegistryPropertyCodec.PropertiesChanged decoded =
                                NanoRegistryPropertyCodec
                                        .decodePropertiesChanged(
                                                received.payload);
                        try {
                            assertEquals(
                                    345,
                                    NanoRegistryUuidSetCodec.decode(
                                            decoded.properties.get(
                                                    48).value).size());
                        } finally {
                            decoded.destroy();
                        }
                    } finally {
                        large.destroy();
                    }
                }
            } finally {
                snapshot.destroy();
            }
        }
    }

    @Test
    public void controlTypeSixClassDClassCAndServiceMapRunEndToEnd() {
        try (Side phone =
                     phone();
             Side watch =
                     watch()) {
            try (IdsModernSessionCoordinator.Output started =
                         phone.coordinator.startControl();
                 PumpResult control =
                         pump(
                                 phone,
                                 watch,
                                 started)) {
                assertEquals(
                        1,
                        control.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.CONTROL_READY));
                assertEquals(
                        1,
                        control.count(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.CONTROL_READY));
            }

            assertControlReady(
                    phone);
            assertControlReady(
                    watch);
            assertEquals(
                    26,
                    phone.coordinator
                            .effectivePeerMaxPairingVersion());
            assertEquals(
                    26,
                    watch.coordinator
                            .effectivePeerMaxPairingVersion());

            try (IdsModernSessionCoordinator.Output started =
                         phone.coordinator.startInitialLane(
                                 NanoRegistryPropertyCodec
                                         .CLASS_D_SERVICE)) {
                assertEquals(
                        0,
                        countEvents(
                                started,
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED));
                assertEquals(
                        1,
                        countEvents(
                                started,
                                IdsModernSessionCoordinator
                                        .EventType.DATA_SETUP_SENT));
                assertEquals(
                        0,
                        countEvents(
                                started,
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CONNECT_STARTED));
                assertEquals(
                        0,
                        phone.coordinator.snapshot().joinedDataCount);
                try (IdsModernSessionCoordinator.Output polled =
                             phone.coordinator.pollRetransmissions()) {
                    assertEquals(
                            0,
                            countEvents(
                                    polled,
                                    IdsModernSessionCoordinator
                                            .EventType.DATA_CONNECT_STARTED));
                }
                try (PumpResult classD =
                             pump(
                                     phone,
                                     watch,
                                     started)) {
                    assertTrue(
                            classD.count(
                                    "phone",
                                    IdsModernSessionCoordinator
                                            .EventType.DATA_CHANNEL_JOINED)
                                    >= 1);
                    assertTrue(
                            classD.count(
                                    "watch",
                                    IdsModernSessionCoordinator
                                            .EventType.DATA_CHANNEL_JOINED)
                                    >= 1);
                    assertTrue(
                            classD.sawDataClass(
                                    OrdinaryIkeAuth.DataClass.CLASS_D));
                    assertTrue(
                            phone.coordinator.snapshot().joinedDataCount
                                    >= 1);
                    assertTrue(
                            phone.coordinator
                                    .dataChannel(
                                            NanoRegistryPropertyCodec
                                                    .CLASS_D_SERVICE)
                                    .joined);
                }
            }

            try (IdsModernSessionCoordinator.Output started =
                         phone.coordinator.startInitialLane(
                                 NanoRegistryPropertyCodec
                                         .CLASS_C_SERVICE);
                 PumpResult classC =
                         pump(
                                 phone,
                                 watch,
                                 started)) {
                assertTrue(
                        classC.count(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED)
                                >= 1);
                assertTrue(
                        classC.count(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.DATA_CHANNEL_JOINED)
                                >= 1);
                assertTrue(
                        classC.sawDataClass(
                                OrdinaryIkeAuth.DataClass.CLASS_C));
            }

            assertTwoJoinedLanes(
                    phone);
            assertTwoJoinedLanes(
                    watch);
            assertAlignedPortNamespaces(
                    phone);

            NanoRegistryClassDCodec.PairingModeRequest pairingMode =
                    NanoRegistryClassDCodec
                            .PairingModeRequest
                            .modernIos26_6Ultra2(
                                    NanoRegistryClassDCodec
                                            .COMPATIBILITY_STATE_CONFIGURE,
                                    25);
            try (IdsModernSessionCoordinator.Output sent =
                         phone.coordinator.sendClassD(
                                 pairingMode);
                 PumpResult delivered =
                         pump(
                                 phone,
                                 watch,
                                 sent)) {
                ObservedEvent outgoing =
                        delivered.first(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_SENT,
                                NanoRegistryPropertyCodec
                                        .CLASS_D_SERVICE);
                assertEquals(
                        0,
                        outgoing.flags
                                & (IdsSocketPairCodec
                                .FLAG_EXPECTS_PEER_RESPONSE
                                | IdsSocketPairCodec
                                .FLAG_WANTS_APP_ACK));
                ObservedEvent received =
                        delivered.first(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                NanoRegistryPropertyCodec
                                        .CLASS_D_SERVICE);
                assertEquals(
                        NanoRegistryClassDCodec.TYPE_PAIRING_MODE,
                        received.protobufType);
                assertFalse(
                        received.response);
                assertEquals(outgoing.messageUuid, received.outgoingResponseIdentifier);
                NanoRegistryClassDCodec.PairingModeRequest decoded =
                        NanoRegistryClassDCodec
                                .decodePairingModeRequest(
                                        received.payload);
                assertEquals(
                        NanoRegistryClassDCodec
                                .COMPATIBILITY_STATE_CONFIGURE,
                        decoded.pairingMode);
                assertEquals(
                        Integer.valueOf(
                                25),
                        decoded.watchPairingProtocolVersion);
            }

            for (int index = 0;
                    index < 3;
                    index++) {
                NanoRegistryPropertyCodec.PropertiesChanged snapshot =
                        classCSnapshot(
                                "iPhone18,"
                                        + (index + 1),
                                42.0 + index);
                try (IdsModernSessionCoordinator.Output sent =
                             phone.coordinator.sendClassC(
                                     metadata(
                                             10 + index,
                                             String.format(
                                                     "20000000-0000-4000-8000-%012d",
                                                     index + 1)),
                                     snapshot);
                     PumpResult delivered =
                             pump(
                                     phone,
                                     watch,
                                     sent)) {
                    ObservedEvent received =
                            delivered.first(
                                    "watch",
                                    IdsModernSessionCoordinator
                                            .EventType.PROTOBUF_RECEIVED,
                                    NanoRegistryPropertyCodec
                                            .CLASS_C_SERVICE);
                    NanoRegistryPropertyCodec.PropertiesChanged decoded =
                            NanoRegistryPropertyCodec
                                    .decodePropertiesChanged(
                                            received.payload);
                    try {
                        assertTrue(
                                decoded.thisIsAllOfThem);
                        assertEquals(
                                "productType",
                                decoded.properties.get(
                                        0).name);
                    } finally {
                        decoded.destroy();
                    }
                    if (index == 2) {
                        assertEquals(
                                1,
                                delivered.count(
                                        "watch",
                                        IdsModernSessionCoordinator
                                                .EventType.SERVICE_MAP_SENT));
                        assertEquals(
                                1,
                                delivered.count(
                                        "phone",
                                        IdsModernSessionCoordinator
                                                .EventType
                                                .SERVICE_MAP_RECEIVED));
                    }
                } finally {
                    snapshot.destroy();
                }
            }

            NanoRegistryPropertyCodec.PropertiesChanged mappedSnapshot =
                    classCSnapshot(
                            "iPhone18,1",
                            50.0);
            try (IdsModernSessionCoordinator.Output sent =
                         phone.coordinator.sendClassC(
                                 metadata(
                                         20,
                                         "20000000-0000-4000-8000-000000000020"),
                                 mappedSnapshot);
                 PumpResult delivered =
                         pump(
                                 phone,
                                 watch,
                                 sent)) {
                ObservedEvent outgoing =
                        delivered.first(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_SENT,
                                NanoRegistryPropertyCodec
                                        .CLASS_C_SERVICE);
                assertEquals(
                        0,
                        outgoing.flags
                                & IdsSocketPairCodec.FLAG_HAS_TOPIC);
                delivered.first(
                        "watch",
                        IdsModernSessionCoordinator
                                .EventType.PROTOBUF_RECEIVED,
                        NanoRegistryPropertyCodec
                                .CLASS_C_SERVICE);
            } finally {
                mappedSnapshot.destroy();
            }

            long phoneClassCConnection =
                    phone.coordinator.dataChannel(
                            NanoRegistryPropertyCodec.CLASS_C_SERVICE)
                            .connectionId;
            long watchClassCConnection =
                    watch.coordinator.dataChannel(
                            NanoRegistryPropertyCodec.CLASS_C_SERVICE)
                            .connectionId;

            try (IdsModernSessionCoordinator.Output sent =
                         phone.coordinator.sendPbBridge(
                                 new PbBridgeCodec.CanBeginActivation());
                 PumpResult delivered =
                         pump(
                                 phone,
                                 watch,
                                 sent)) {
                ObservedEvent received =
                        delivered.first(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                PbBridgeCodec.SERVICE);
                assertEquals(
                        PbBridgeCodec.TYPE_CAN_BEGIN_ACTIVATION,
                        received.protobufType);
                assertFalse(
                        received.response);
                assertEquals(
                        1,
                        received.sequence);
                assertEquals(
                        received.messageUuid.toUpperCase(
                                Locale.ROOT),
                        received.messageUuid);
                assertEquals(
                        4,
                        UUID.fromString(
                                received.messageUuid).version());
                assertEquals(
                        2,
                        UUID.fromString(
                                received.messageUuid).variant());
                assertArrayEquals(
                        new byte[0],
                        received.payload);
                assertTrue(
                        delivered.sawDataClass(
                                OrdinaryIkeAuth.DataClass.CLASS_C));
            }

            PbBridgeCodec.ActivationFetchRequest activationFetch =
                    new PbBridgeCodec.ActivationFetchRequest(
                            new byte[] {
                                    0x01,
                                    0x02,
                                    0x03
                            });
            try (IdsModernSessionCoordinator.Output sent =
                         watch.coordinator.sendPbBridge(
                                 metadata(
                                         30,
                                         "50000000-0000-4000-8000-000000000030"),
                                 activationFetch);
                 PumpResult delivered =
                         pump(
                                 watch,
                                 phone,
                                 sent)) {
                ObservedEvent received =
                        delivered.first(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                PbBridgeCodec.SERVICE);
                PbBridgeCodec.ActivationFetchRequest decoded =
                        PbBridgeCodec.decodeActivationFetch(
                                received.payload);
                try {
                    assertArrayEquals(
                            new byte[] {
                                    0x01,
                                    0x02,
                                    0x03
                            },
                            decoded.archivedRequest());
                } finally {
                    decoded.destroy();
                }
            } finally {
                activationFetch.destroy();
            }

            PbBridgeCodec.ActivationData activationData =
                    new PbBridgeCodec.ActivationData(
                            new byte[] {
                                    0x11,
                                    0x22
                            },
                            new byte[] {
                                    0x33,
                                    0x44
                            });
            try (IdsModernSessionCoordinator.Output sent =
                         phone.coordinator.sendPbBridge(
                                 metadata(
                                         31,
                                         "40000000-0000-4000-8000-000000000031"),
                                 activationData);
                 PumpResult delivered =
                         pump(
                                 phone,
                                 watch,
                                 sent)) {
                ObservedEvent received =
                        delivered.first(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                PbBridgeCodec.SERVICE);
                PbBridgeCodec.ActivationData decoded =
                        PbBridgeCodec.decodeActivationData(
                                received.payload);
                try {
                    assertArrayEquals(
                            new byte[] {
                                    0x11,
                                    0x22
                            },
                            decoded.activationData());
                    assertArrayEquals(
                            new byte[] {
                                    0x33,
                                    0x44
                            },
                            decoded.archivedResponseHeaders());
                } finally {
                    decoded.destroy();
                }
            } finally {
                activationData.destroy();
            }

            String prepareRequestUuid =
                    "40000000-0000-4000-8000-000000000031";
            try (IdsModernSessionCoordinator.Output sent =
                         phone.coordinator.sendPbBridge(
                                 metadata(
                                         31,
                                         prepareRequestUuid,
                                         IdsSocketPairCodec
                                                 .FLAG_EXPECTS_PEER_RESPONSE),
                                 new PbBridgeCodec.PrepareInitialSync());
                 PumpResult delivered =
                         pump(
                                 phone,
                                 watch,
                                 sent)) {
                ObservedEvent request =
                        delivered.first(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                PbBridgeCodec.SERVICE);
                assertEquals(
                        PbBridgeCodec.TYPE_PREPARE_INITIAL_SYNC,
                        request.protobufType);
                assertEquals(
                        prepareRequestUuid,
                        request.messageUuid);
                assertEquals(
                        prepareRequestUuid,
                        request.outgoingResponseIdentifier);
                assertNull(
                        request.peerResponseIdentifier);
            }

            String prepareResponseUuid =
                    "50000000-0000-4000-8000-000000000032";
            IdsModernSessionCoordinator.MessageMetadata responseMetadata =
                    IdsModernSessionCoordinator.MessageMetadata
                            .responseToIdentifier(
                                    32,
                                    prepareResponseUuid,
                                    prepareRequestUuid);
            try (IdsModernSessionCoordinator.Output sent =
                         watch.coordinator.sendPbBridge(
                                 responseMetadata,
                                 new PbBridgeCodec
                                         .PrepareInitialSyncResponse());
                 PumpResult delivered =
                         pump(
                                 watch,
                                 phone,
                                 sent)) {
                ObservedEvent response =
                        delivered.first(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                PbBridgeCodec.SERVICE);
                assertEquals(
                        PbBridgeCodec
                                .TYPE_PREPARE_INITIAL_SYNC_RESPONSE,
                        response.protobufType);
                assertTrue(
                        response.response);
                assertEquals(
                        prepareResponseUuid,
                        response.messageUuid);
                assertEquals(
                        prepareRequestUuid,
                        response.peerResponseIdentifier);
                assertArrayEquals(
                        new byte[0],
                        response.payload);
            }

            try (IdsModernSessionCoordinator.Output sent =
                         phone.coordinator.sendPbBridge(
                                 metadata(
                                         33,
                                         "40000000-0000-4000-8000-000000000033"),
                                 new PbBridgeCodec
                                         .UpdateNanoRegistryNormal());
                 PumpResult delivered =
                         pump(
                                 phone,
                                 watch,
                                 sent)) {
                ObservedEvent outgoing =
                        delivered.first(
                                "phone",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_SENT,
                                PbBridgeCodec.SERVICE);
                assertEquals(
                        0,
                        outgoing.flags
                                & IdsSocketPairCodec.FLAG_HAS_TOPIC);
                ObservedEvent received =
                        delivered.first(
                                "watch",
                                IdsModernSessionCoordinator
                                        .EventType.PROTOBUF_RECEIVED,
                                PbBridgeCodec.SERVICE);
                assertEquals(
                        PbBridgeCodec
                                .TYPE_UPDATE_NANO_REGISTRY_NORMAL,
                        received.protobufType);
                assertEquals(
                        0,
                        delivered.count(
                                null,
                                IdsModernSessionCoordinator
                                        .EventType.DATA_SETUP_SENT));
            }

            assertEquals(
                    phoneClassCConnection,
                    phone.coordinator.dataChannel(
                            PbBridgeCodec.SERVICE)
                            .connectionId);
            assertEquals(
                    watchClassCConnection,
                    watch.coordinator.dataChannel(
                            PbBridgeCodec.SERVICE)
                            .connectionId);
            assertTwoJoinedLanes(
                    phone);
            assertTwoJoinedLanes(
                    watch);

            long phoneClassDConnection =
                    phone.coordinator.dataChannel(
                            NanoRegistryPropertyCodec.CLASS_D_SERVICE)
                            .connectionId;
            long watchClassDConnection =
                    watch.coordinator.dataChannel(
                            NanoRegistryPropertyCodec.CLASS_D_SERVICE)
                            .connectionId;
            NanoRegistryPropertyCodec.PropertiesChanged
                    supportedWatchSnapshot =
                    classCSnapshot(
                            "Watch7,5",
                            50.0,
                            true);
            try (IdsModernSessionCoordinator.Output sent =
                         watch.coordinator.sendClassC(
                                 metadata(
                                         30,
                                         "30000000-0000-4000-8000-000000000030"),
                                 supportedWatchSnapshot);
                 PumpResult delivered =
                         pump(
                                 watch,
                                 phone,
                                 sent)) {
                delivered.first(
                        "phone",
                        IdsModernSessionCoordinator
                                .EventType.PROTOBUF_RECEIVED,
                        NanoRegistryPropertyCodec.CLASS_C_SERVICE);
                assertEquals(
                        PairedSyncCapabilityState.Status.PRESENT,
                        phone.coordinator.snapshot()
                                .pairedSyncCapabilityStatus);
            } finally {
                supportedWatchSnapshot.destroy();
            }

            PairedSyncCodec.UserDefaultsMessage completion =
                    PairedSyncCodec.initialSyncCompletion(
                            800000000.5);
            try {
                try (IdsModernSessionCoordinator.Output sent =
                             phone.coordinator.sendPairedSync(
                                     completion);
                     PumpResult delivered =
                             pump(
                                     phone,
                                     watch,
                                     sent)) {
                    ObservedEvent received =
                            delivered.first(
                                    "watch",
                                    IdsModernSessionCoordinator
                                            .EventType.PROTOBUF_RECEIVED,
                                    PairedSyncCodec.PREFERRED_SERVICE);
                    PairedSyncCodec.UserDefaultsMessage decoded =
                            PairedSyncCodec.decode(
                                    received.payload);
                    try {
                        assertEquals(
                                PairedSyncCodec
                                        .PROTOBUF_TYPE_USER_DEFAULTS,
                                received.protobufType);
                        assertFalse(
                                received.response);
                        assertEquals(
                                2,
                                received.sequence);
                        assertEquals(
                                IdsSocketPairCodec.FLAG_WANTS_APP_ACK,
                                received.flags
                                        & IdsSocketPairCodec
                                        .FLAG_WANTS_APP_ACK);
                        assertTrue(
                                decoded.isInitialSyncCompletion());
                        assertTrue(
                                delivered.sawDataClass(
                                        OrdinaryIkeAuth.DataClass.CLASS_C));
                        assertEquals(
                                0,
                                delivered.count(
                                        null,
                                        IdsModernSessionCoordinator
                                                .EventType.DATA_SETUP_SENT));
                    } finally {
                        decoded.destroy();
                    }
                }

                NanoRegistryPropertyCodec.PropertiesChanged
                        unsupportedWatchSnapshot =
                        classCSnapshot(
                                "Watch7,5",
                                50.5,
                                false);
                try (IdsModernSessionCoordinator.Output sent =
                             watch.coordinator.sendClassC(
                                     metadata(
                                             31,
                                             "30000000-0000-4000-8000-000000000031"),
                                     unsupportedWatchSnapshot);
                     PumpResult delivered =
                             pump(
                                     watch,
                                     phone,
                                     sent)) {
                    delivered.first(
                            "phone",
                            IdsModernSessionCoordinator
                                    .EventType.PROTOBUF_RECEIVED,
                            NanoRegistryPropertyCodec.CLASS_C_SERVICE);
                    assertEquals(
                            PairedSyncCapabilityState.Status.ABSENT,
                            phone.coordinator.snapshot()
                                    .pairedSyncCapabilityStatus);
                } finally {
                    unsupportedWatchSnapshot.destroy();
                }

                try (IdsModernSessionCoordinator.Output sent =
                             phone.coordinator.sendPairedSync(
                                     metadata(
                                             35,
                                             "60000000-0000-4000-8000-000000000035",
                                             IdsSocketPairCodec
                                                     .FLAG_WANTS_APP_ACK),
                                     completion);
                     PumpResult delivered =
                             pump(
                                     phone,
                                     watch,
                                     sent)) {
                    ObservedEvent received =
                            delivered.first(
                                    "watch",
                                    IdsModernSessionCoordinator
                                            .EventType.PROTOBUF_RECEIVED,
                                    PairedSyncCodec.FALLBACK_SERVICE);
                    PairedSyncCodec.UserDefaultsMessage decoded =
                            PairedSyncCodec.decode(
                                    received.payload);
                    try {
                        assertTrue(
                                decoded.isInitialSyncCompletion());
                        assertTrue(
                                delivered.sawDataClass(
                                        OrdinaryIkeAuth.DataClass.CLASS_D));
                        try (IdsModernSessionCoordinator.Output receipt =
                                     watch.coordinator.sendAppAck(
                                             PairedSyncCodec.FALLBACK_SERVICE,
                                             received.messageUuid);
                             PumpResult acknowledged =
                                     pump(
                                             watch,
                                             phone,
                                             receipt)) {
                            ObservedEvent sentReceipt =
                                    acknowledged.first(
                                            "watch",
                                            IdsModernSessionCoordinator
                                                    .EventType.APP_ACK_SENT,
                                            PairedSyncCodec.FALLBACK_SERVICE);
                            ObservedEvent receivedReceipt =
                                    acknowledged.first(
                                            "phone",
                                            IdsModernSessionCoordinator
                                                    .EventType.APP_ACK_RECEIVED,
                                            PairedSyncCodec.FALLBACK_SERVICE);
                            assertEquals(
                                    2, // Two preceding automatic receipts consumed sequences 0 and 1.
                                    sentReceipt.sequence);
                            assertEquals(
                                    sentReceipt.sequence,
                                    receivedReceipt.sequence);
                            assertNotEquals(
                                    received.sequence,
                                    receivedReceipt.sequence);
                            assertEquals(
                                    received.messageUuid,
                                    receivedReceipt
                                            .peerResponseIdentifier);
                        }
                    } finally {
                        decoded.destroy();
                    }
                }
            } finally {
                completion.destroy();
            }

            assertEquals(
                    phoneClassCConnection,
                    phone.coordinator.dataChannel(
                            PairedSyncCodec.PREFERRED_SERVICE)
                            .connectionId);
            assertEquals(
                    phoneClassDConnection,
                    phone.coordinator.dataChannel(
                            PairedSyncCodec.FALLBACK_SERVICE)
                            .connectionId);
            assertEquals(
                    watchClassCConnection,
                    watch.coordinator.dataChannel(
                            PairedSyncCodec.PREFERRED_SERVICE)
                            .connectionId);
            assertEquals(
                    watchClassDConnection,
                    watch.coordinator.dataChannel(
                            PairedSyncCodec.FALLBACK_SERVICE)
                            .connectionId);
            assertTwoJoinedLanes(
                    phone);
            assertTwoJoinedLanes(
                    watch);

            NanoRegistryPropertyCodec.PropertiesChanged watchSnapshot =
                    classCSnapshot(
                            "Watch7,5",
                            51.0);
            try (IdsModernSessionCoordinator.Output sent =
                         watch.coordinator.sendClassC(
                                 metadata(
                                         1,
                                         "30000000-0000-4000-8000-000000000001"),
                                 watchSnapshot);
                 PumpResult delivered =
                         pump(
                                 watch,
                                 phone,
                                 sent)) {
                delivered.first(
                        "phone",
                        IdsModernSessionCoordinator
                                .EventType.PROTOBUF_RECEIVED,
                        NanoRegistryPropertyCodec
                                .CLASS_C_SERVICE);
            } finally {
                watchSnapshot.destroy();
            }
        }
    }

    private static void assertControlReady(
            Side side) {
        IdsModernSessionCoordinator.Snapshot snapshot =
                side.coordinator.snapshot();
        assertTrue(
                snapshot.controlAccepted);
        assertTrue(
                snapshot.peerHelloReceived);
    }

    private static void assertTwoJoinedLanes(
            Side side) {
        IdsModernSessionCoordinator.Snapshot snapshot =
                side.coordinator.snapshot();
        assertEquals(
                2,
                snapshot.dataSessionCount);
        assertEquals(
                2,
                snapshot.joinedDataCount);
        assertTrue(
                side.coordinator.dataLanesReadyForApplication());
        assertEquals(
                2,
                snapshot.setupSsrcCount);
    }

    private static void assertAlignedPortNamespaces(
            Side phone) {
        IdsModernSessionCoordinator.DataSnapshot classD =
                phone.coordinator.dataChannel(
                        NanoRegistryPropertyCodec
                                .CLASS_D_SERVICE);
        assertTrue(
                classD.setupEstablished);
        assertTrue(
                classD.joined);
        assertEquals(
                IdsControlChannelCodec.DATA_PORT,
                classD.setupRemotePort);
        assertTrue(
                classD.setupLocalPort
                        >= IdsPortMap.FIRST_DYNAMIC_PORT);
        // JOINED can outlive a reverse-probe TCP FIN; Class-D send still
        // uses the active service tuple (stand 15:39 keeps the original SYN).
    }

    private static IdsModernSessionCoordinator.MessageMetadata metadata(
            long sequence,
            String uuid) {
        return metadata(
                sequence,
                uuid,
                0);
    }

    private static IdsModernSessionCoordinator.MessageMetadata metadata(
            long sequence,
            String uuid,
            int flags) {
        return new IdsModernSessionCoordinator.MessageMetadata(
                sequence,
                uuid,
                flags,
                null,
                null,
                null);
    }

    private static NanoRegistryPropertyCodec.PropertiesChanged
            classCSnapshot(
                    String productType,
                    double bornOn) {
        return classCSnapshot(
                productType,
                bornOn,
                true);
    }

    private static NanoRegistryPropertyCodec.PropertiesChanged
            classCSnapshot(
                    String productType,
                    double bornOn,
                    boolean pairedSyncSupported) {
        List<NanoRegistryPropertyCodec.PropertyValue> capabilities =
                pairedSyncSupported
                        ? List.of(
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .uuid(
                                                PairedSyncCapabilityState
                                                        .capabilityUuid()))
                        : List.of();
        return new NanoRegistryPropertyCodec.PropertiesChanged(
                true,
                List.of(
                        new NanoRegistryPropertyCodec.Property(
                                "productType",
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .string(
                                                productType)),
                        new NanoRegistryPropertyCodec.Property(
                                PairedSyncCapabilityState.PROPERTY_NAME,
                                NanoRegistryPropertyCodec
                                        .PropertyValue
                                        .set(
                                                capabilities))),
                bornOn);
    }

    private static PumpResult pump(
            Side source,
            Side target,
            IdsModernSessionCoordinator.Output initial) {
        Deque<Transit> queue =
                new ArrayDeque<>();
        List<ObservedEvent> events =
                new ArrayList<>();
        List<OrdinaryIkeAuth.DataClass> dataClasses =
                new ArrayList<>();
        captureOutput(
                source,
                target,
                initial,
                queue,
                events);
        int steps = 0;
        try {
            while (!queue.isEmpty()) {
                if (++steps > 4096) {
                    throw new IllegalStateException(
                            "IDS modern-session packet pump did not quiesce");
                }
                Transit transit =
                        queue.removeFirst();
                dataClasses.add(
                        transit.dataClass);
                byte[] packet =
                        transit.packet.clone();
                try (IdsModernSessionCoordinator.Output accepted =
                             transit.target.coordinator.accept(
                                     transit.dataClass,
                                     packet)) {
                    captureOutput(
                            transit.target,
                            transit.source,
                            accepted,
                            queue,
                            events);
                } finally {
                    wipe(
                            packet);
                    transit.close();
                }
            }
            return new PumpResult(
                    events,
                    dataClasses);
        } catch (RuntimeException failure) {
            closeObserved(
                    events);
            throw failure;
        } finally {
            while (!queue.isEmpty()) {
                queue.removeFirst()
                        .close();
            }
        }
    }

    private static void captureOutput(
            Side owner,
            Side peer,
            IdsModernSessionCoordinator.Output output,
            Deque<Transit> queue,
            List<ObservedEvent> events) {
        for (IdsModernSessionCoordinator.SessionEvent event :
                output.events()) {
            events.add(
                    new ObservedEvent(
                            owner.name,
                            event));
        }
        for (IdsModernSessionCoordinator.RoutedPacket packet :
                output.packets()) {
            byte[] bytes =
                    packet.clearIpv6Packet();
            try {
                queue.addLast(
                        new Transit(
                                owner,
                                peer,
                                packet.dataClass,
                                bytes));
            } finally {
                wipe(
                        bytes);
            }
        }
    }

    private static Side phone() {
        return phone(true);
    }

    private static Side phone(boolean directMessagingSupported) {
        return new Side(
                "phone",
                new IdsModernSessionCoordinator(
                        new SecureRandom(),
                        100,
                        PHONE_D,
                        WATCH_D,
                        PHONE_C,
                        WATCH_C,
                        "00000000-0000-4000-8000-000000000001",
                        directMessagingSupported));
    }

    private static Side watch() {
        return new Side(
                "watch",
                new IdsModernSessionCoordinator(
                        new SecureRandom(),
                        300,
                        WATCH_D,
                        PHONE_D,
                        WATCH_C,
                        PHONE_C,
                        "00000000-0000-4000-8000-000000000002",
                        true));
    }

    private static byte[] address(
            int suffix) {
        byte[] value =
                new byte[16];
        value[0] =
                (byte) 0xfd;
        value[1] =
                0x74;
        value[2] =
                0x65;
        value[3] =
                0x72;
        value[4] =
                0x6d;
        value[5] =
                0x6e;
        value[6] =
                0x75;
        value[7] =
                0x73;
        value[15] =
                (byte) suffix;
        return value;
    }

    private static void closeObserved(
            List<ObservedEvent> events) {
        for (ObservedEvent event :
                events) {
            event.close();
        }
        events.clear();
    }

    private static int countEvents(
            IdsModernSessionCoordinator.Output output,
            IdsModernSessionCoordinator.EventType type) {
        int count = 0;
        for (IdsModernSessionCoordinator.SessionEvent event :
                output.events()) {
            if (event.type == type) {
                count++;
            }
        }
        return count;
    }

    private static int protobufSentFlags(
            IdsModernSessionCoordinator.Output output) {
        for (IdsModernSessionCoordinator.SessionEvent event :
                output.events()) {
            if (event.type
                    == IdsModernSessionCoordinator.EventType.PROTOBUF_SENT) {
                return event.flags;
            }
        }
        throw new AssertionError(
                "PROTOBUF_SENT is absent");
    }

    private static void wipe(
            byte[] value) {
        if (value != null) {
            Arrays.fill(
                    value,
                    (byte) 0);
        }
    }

    private static final class Side
            implements AutoCloseable {
        final String name;
        final IdsModernSessionCoordinator coordinator;

        private Side(
                String name,
                IdsModernSessionCoordinator coordinator) {
            this.name = name;
            this.coordinator =
                    coordinator;
        }

        @Override
        public void close() {
            coordinator.close();
        }
    }

    private static final class Transit
            implements AutoCloseable {
        final Side source;
        final Side target;
        final OrdinaryIkeAuth.DataClass dataClass;
        byte[] packet;
        boolean closed;

        private Transit(
                Side source,
                Side target,
                OrdinaryIkeAuth.DataClass dataClass,
                byte[] packet) {
            this.source = source;
            this.target = target;
            this.dataClass =
                    dataClass;
            this.packet =
                    packet.clone();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            wipe(
                    packet);
            packet =
                    new byte[0];
        }
    }

    private static final class ObservedEvent
            implements AutoCloseable {
        final String owner;
        final IdsModernSessionCoordinator.EventType type;
        final IdsServiceConnectorCoordinator.EventType transportEvent;
        final String topic;
        final int command;
        final long sequence;
        final int flags;
        final int protobufType;
        final boolean response;
        final String messageUuid;
        final String peerResponseIdentifier;
        final String outgoingResponseIdentifier;
        byte[] payload;

        private ObservedEvent(
                String owner,
                IdsModernSessionCoordinator.SessionEvent event) {
            this.owner = owner;
            type =
                    event.type;
            command = event.command;
            transportEvent =
                    event.transportEvent;
            topic =
                    event.topic;
            sequence =
                    event.sequence;
            flags =
                    event.flags;
            protobufType =
                    event.protobufType;
            response =
                    event.response;
            messageUuid =
                    event.messageUuid;
            peerResponseIdentifier =
                    event.peerResponseIdentifier;
            outgoingResponseIdentifier =
                    (event.type
                            == IdsModernSessionCoordinator
                            .EventType.PROTOBUF_RECEIVED
                            && !event.response
                            || event.type == IdsModernSessionCoordinator.EventType.DATA_RECEIVED
                            && (event.flags & IdsSocketPairCodec.FLAG_EXPECTS_PEER_RESPONSE) != 0)
                            ? event.outgoingResponseIdentifier()
                            : null;
            payload =
                    event.payload();
        }

        @Override
        public void close() {
            wipe(
                    payload);
            payload =
                    new byte[0];
        }
    }

    private static final class PumpResult
            implements AutoCloseable {
        final List<ObservedEvent> events;
        final List<OrdinaryIkeAuth.DataClass> dataClasses;

        private PumpResult(
                List<ObservedEvent> events,
                List<OrdinaryIkeAuth.DataClass> dataClasses) {
            this.events =
                    new ArrayList<>(
                            events);
            this.dataClasses =
                    List.copyOf(
                            dataClasses);
        }

        int count(
                String owner,
                IdsModernSessionCoordinator.EventType type) {
            int count = 0;
            for (ObservedEvent event :
                    events) {
                if ((owner == null
                        || owner.equals(
                                event.owner))
                        && type == event.type) {
                    count++;
                }
            }
            return count;
        }

        ObservedEvent first(
                String owner,
                IdsModernSessionCoordinator.EventType type,
                String topic) {
            for (ObservedEvent event :
                    events) {
                if ((owner == null
                        || owner.equals(
                                event.owner))
                        && type == event.type
                        && (topic == null
                        || topic.equals(
                                event.topic))) {
                    return event;
                }
            }
            throw new AssertionError(
                    "Missing IDS event "
                            + owner
                            + "/"
                            + type
                            + "/"
                            + topic);
        }

        boolean sawDataClass(
                OrdinaryIkeAuth.DataClass dataClass) {
            return dataClasses.contains(
                    dataClass);
        }

        @Override
        public void close() {
            closeObserved(
                    events);
        }
    }
}
