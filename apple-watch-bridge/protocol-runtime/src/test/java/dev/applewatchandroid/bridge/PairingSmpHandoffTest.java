package dev.applewatchandroid.bridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic ACL/event traces through the real IKE ACK and SMP wait loops. */
public final class PairingSmpHandoffTest {
    private static final int HANDLE = 0x0042;
    private static final byte[] RESPONSE = {2, 3, 1, 0x2d, 16, 7, 7};
    private static final byte[] SECURITY = {0x0b, 0x2d};
    private final FakeController controller = new FakeController();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private PrintStream previousOutput;
    private HalTransportSession session;
    private BluetoothController.Callbacks callbacks;

    @Before public void setUp() throws Exception {
        previousOutput = System.out;
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        initSession();
    }

    private void initSession() throws Exception {
        controller.sent.clear();
        controller.sentCommands.clear();
        controller.commandHandler = null;
        output.reset();
        session = new HalTransportSession(controller, stopped,
                new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(),
                new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(),
                new LinkedBlockingQueue<>(), new LinkedBlockingQueue<>(),
                new LinkedBlockingQueue<>(), false, null);
        field("connectionHandle").set(session, HANDLE);
        HciAclCreditWindow credits = (HciAclCreditWindow) field("aclCredits").get(session);
        credits.configure(16);
        credits.connected(HANDLE);
        callbacks = (BluetoothController.Callbacks) field("callbacks").get(session);
        controller.callbacks = callbacks;
        invoke("resetObservedLeLinkState");
    }

    @After public void tearDown() throws Exception {
        try { invoke("clearDeferredSmpPdus"); }
        finally { System.setOut(previousOutput); }
    }

    @Test public void retainsSecurityAndResponseBeforeFinalAckInOrder() throws Exception {
        peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        peer(BluetoothSmpCodec.FIXED_CID, RESPONSE);
        peerAck();
        finalAck();
        List<byte[]> retained = new ArrayList<>(deferred());
        assertEquals(2, retained.size());
        sendRequest();
        assertArrayEquals(RESPONSE, await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertEquals(0, deferred().size());
        for (byte[] pdu : retained) assertArrayEquals(new byte[pdu.length], pdu);
        assertEquals(1, controller.smpRequests());
        assertTrue(logs().contains("SMP RX DEFERRED"));
        assertTrue(logs().contains("SMP HANDOFF: final IKE transport ACK received"));
    }

    @Test public void acceptsSecurityAfterAckWithoutResendingRequest() throws Exception {
        peerAck();
        finalAck();
        sendRequest();
        peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        peer(BluetoothSmpCodec.FIXED_CID, RESPONSE);
        assertArrayEquals(RESPONSE, await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertEquals(1, controller.smpRequests());
        assertTrue(logs().contains("count=2"));
    }

    @Test public void preservesFollowingDeferredPublicKeyForNextPhase() throws Exception {
        byte[] key = new byte[64];
        Arrays.fill(key, (byte) 0x7e);
        byte[] keyPdu = BluetoothSmpCodec.pairingPublicKey(key);
        peer(BluetoothSmpCodec.FIXED_CID, RESPONSE);
        peer(BluetoothSmpCodec.FIXED_CID, keyPdu);
        peerAck();
        finalAck();
        sendRequest();
        assertArrayEquals(RESPONSE, await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertArrayEquals(keyPdu, await(BluetoothSmpCodec.PAIRING_PUBLIC_KEY, 500));
        assertFalse(logs().contains(HciCodec.toHex(key)));
        assertFalse(logs().contains(HciCodec.toHex(RESPONSE)));
    }

    @Test public void reassemblesEarlyFragmentedSmpBeforeAck() throws Exception {
        for (byte[] fragment : HciCodec.buildLeAclL2capFragments(
                HANDLE, BluetoothSmpCodec.FIXED_CID, RESPONSE, 5)) {
            callbacks.aclDataReceived(fragment);
        }
        peerAck();
        finalAck();
        sendRequest();
        assertArrayEquals(RESPONSE, await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertEquals(1, field("pairingSmpReceived").getInt(session));
    }

    @Test public void doesNotAdoptSmpFromAnotherHandle() throws Exception {
        for (byte[] packet : HciCodec.buildLeAclL2capFragments(
                HANDLE + 1, BluetoothSmpCodec.FIXED_CID, SECURITY, 256)) {
            callbacks.aclDataReceived(packet);
        }
        peerAck();
        finalAck();
        assertTrue(deferred().isEmpty());
        assertEquals(0, field("pairingSmpReceived").getInt(session));
    }

    @Test public void reportsEarlyPairingFailureRatherThanTimingOut() throws Exception {
        peer(BluetoothSmpCodec.FIXED_CID, new byte[]{5, 3});
        peerAck();
        finalAck();
        sendRequest();
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertTrue(failure.getMessage().contains("reason=0x03 (Authentication Requirements)"));
        assertFalse(failure.getMessage().contains("timed out"));
    }

    @Test public void reportsLivePairingFailure() throws Exception {
        sendRequest();
        peer(BluetoothSmpCodec.FIXED_CID, new byte[]{5, 9});
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertTrue(failure.getMessage().contains("Repeated Attempts"));
    }

    @Test public void rejectsMalformedSecurityRequest() throws Exception {
        sendRequest();
        peer(BluetoothSmpCodec.FIXED_CID, new byte[]{0x0b});
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertTrue(failure.getMessage().contains("Malformed SMP Security Request"));
        assertEquals(1, controller.smpRequests());
    }

    @Test public void doesNotRestartSecurityNegotiationDuringKeyExchange() throws Exception {
        peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_PUBLIC_KEY, 500));
        assertTrue(failure.getMessage().contains("Unexpected SMP opcode=0x0B"));
        assertEquals(0, controller.smpRequests());
    }

    @Test(timeout = 2500) public void acceptsDelayedResponseAfterSecurityRequest() throws Exception {
        sendRequest();
        peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        ScheduledExecutorService delivery = Executors.newSingleThreadScheduledExecutor();
        try {
            delivery.schedule(() -> peer(BluetoothSmpCodec.FIXED_CID, RESPONSE), 30, TimeUnit.MILLISECONDS);
            assertArrayEquals(RESPONSE, await(BluetoothSmpCodec.PAIRING_RESPONSE, 1000));
        } finally { delivery.shutdownNow(); delivery.awaitTermination(1, TimeUnit.SECONDS); }
        assertEquals(1, controller.smpRequests());
    }

    @Test(timeout = 2500) public void securityTrafficCannotExtendResponseDeadline() throws Exception {
        sendRequest();
        ScheduledExecutorService delivery = Executors.newSingleThreadScheduledExecutor();
        try {
            delivery.scheduleAtFixedRate(() -> peer(BluetoothSmpCodec.FIXED_CID, SECURITY),
                    0, 30, TimeUnit.MILLISECONDS);
            Exception failure = assertThrows(Exception.class,
                    () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 150));
            assertTrue(failure.getMessage(), failure.getMessage().contains("timed out after 150 ms"));
        } finally { delivery.shutdownNow(); delivery.awaitTermination(1, TimeUnit.SECONDS); }
        assertEquals(1, controller.smpRequests());
    }

    @Test public void silentPeerTimeoutIncludesHandoffAndControllerState() throws Exception {
        peerAck();
        finalAck();
        sendRequest();
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 20));
        assertTrue(failure.getMessage().contains("received=0"));
        assertTrue(failure.getMessage().contains("requestSubmittedAgeMs="));
        assertTrue(failure.getMessage().contains("finalIkeAckAgeMs="));
        assertTrue(failure.getMessage().contains("completed=2"));
        assertTrue(logs().contains("peer receipt unconfirmed"));
    }

    @Test public void failsClosedOnEarlySmpQueueOverflow() throws Exception {
        for (int count = 0; count < 17; count++) peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        peerAck();
        Exception failure = assertThrows(Exception.class, this::finalAck);
        assertTrue(failure.getMessage().contains("SMP deferred queue overflow"));
        assertEquals(16, deferred().size());
        List<byte[]> retained = new ArrayList<>(deferred());
        invoke("clearDeferredSmpPdus");
        for (byte[] pdu : retained) assertArrayEquals(new byte[pdu.length], pdu);
        assertEquals(0, controller.smpRequests());
    }

    @Test public void disconnectBeforeAckClearsDeferredSmp() throws Exception {
        peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        controller.afterWrite = this::disconnect;
        Exception failure = assertThrows(Exception.class, this::finalAck);
        assertTrue(failure.getMessage().contains("reason=0x13"));
        assertTrue(deferred().isEmpty());
        assertEquals(-1, field("connectionHandle").getInt(session));
        assertEquals(0, controller.smpRequests());
    }

    @Test public void disconnectDuringSmpIsReportedWithoutWaitingForTimeout() throws Exception {
        sendRequest();
        disconnect();
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertTrue(failure.getMessage().contains("reason=0x13"));
        assertFalse(failure.getMessage().contains("timed out"));
    }

    @Test public void cancellationWinsOverDeferredResponse() throws Exception {
        peer(BluetoothSmpCodec.FIXED_CID, RESPONSE);
        peerAck();
        finalAck();
        stopped.set(true);
        assertThrows(HalTransportSession.StopRequested.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
    }

    @Test public void controllerWriteFailureDoesNotClaimRequestSubmission() throws Exception {
        controller.failWrite = true;
        assertThrows(IOException.class, this::sendRequest);
        assertEquals(-1, field("smpRequestSubmittedAtMs").getLong(session));
        assertFalse(logs().contains("SMP REQUEST SUBMITTED"));
    }

    @Test public void controllerClosureWinsOverDeferredResponse() throws Exception {
        peer(BluetoothSmpCodec.FIXED_CID, RESPONSE);
        peerAck();
        finalAck();
        callbacks.transportFailed(new IOException("synthetic broker EOF"));
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertTrue(failure.getMessage().contains("controller transport failed"));
    }

    @Test public void boundsSecurityRequestFloodWithoutSendingAnotherRequest() throws Exception {
        sendRequest();
        for (int count = 0; count < 17; count++) peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
        Exception failure = assertThrows(Exception.class,
                () -> await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
        assertTrue(failure.getMessage().contains("Too many SMP Security Requests"));
        assertEquals(1, controller.smpRequests());
    }

    @Test public void retainsIdentityDistributionThatArrivesBeforeEncryptionEvent() throws Exception {
        byte[] identity = BluetoothSmpCodec.identityInformation(new byte[16]);
        callbacks.hciEventReceived(new byte[]{0x0f, 4, 0, 1, 0x19, 0x20});
        peer(BluetoothSmpCodec.FIXED_CID, identity);
        callbacks.hciEventReceived(new byte[]{8, 4, 0, (byte) HANDLE, 0, 1});
        invoke("startLeEncryption", new byte[16]);
        assertEquals(1, deferred().size());
        assertArrayEquals(identity, await(BluetoothSmpCodec.IDENTITY_INFORMATION, 500));
    }

    @Test public void encryptionPhaseAlsoFailsOnDeferredQueueOverflow() throws Exception {
        byte[] identity = BluetoothSmpCodec.identityInformation(new byte[16]);
        for (int count = 0; count < 17; count++) peer(BluetoothSmpCodec.FIXED_CID, identity);
        Exception failure = assertThrows(Exception.class,
                () -> invoke("startLeEncryption", new byte[16]));
        assertTrue(failure.getMessage().contains("SMP deferred queue overflow"));
    }

    @Test public void resendsCachedFinalAckOnByteIdenticalRetryWithEarlierReqSeq() throws Exception {
        try (RetryFixture fixture = new RetryFixture(true)) {
            peerWatchIFrame(23, 13, fixture.watchRequestPacket);
            peerSupervisoryRr(14);
            peerSupervisoryRr(15);

            Object result = finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher);
            assertEquals(15, fieldOf(result, "nextOutboundTxSequence"));
            assertEquals(24, fieldOf(result, "nextExpectedWatchTxSequence"));

            List<L2capErtmCodec.Frame> iFrames = controller.sentTerminusIFrames();
            assertEquals(2, iFrames.size());
            assertEquals(13, iFrames.get(0).txSequence);
            assertEquals(23, iFrames.get(0).requestSequence);
            assertEquals(14, iFrames.get(1).txSequence);
            assertEquals(24, iFrames.get(1).requestSequence);
            assertArrayEquals(iFrames.get(0).information, iFrames.get(1).information);
            assertEquals(List.of(24), controller.sentTerminusRrReqSeqs());
            assertTrue(logs().contains("WATCH REQUEST RETRY RX: authenticated byte-identical retry #1 TxSeq=23 ReqSeq=13"));
            assertTrue(logs().contains("SMP HANDOFF: final IKE transport ACK received"));
            assertFalse(logs().contains(HciCodec.toHex(fixture.watchRequestPacket)));
            assertFalse(logs().contains(HciCodec.toHex(fixture.ackPackets.get(0))));
        }
    }

    @Test public void resendsCachedFinalAckOnByteIdenticalRetryCarryingExpectedReqSeq() throws Exception {
        try (RetryFixture fixture = new RetryFixture(true)) {
            peerWatchIFrame(23, 14, fixture.watchRequestPacket);
            peerSupervisoryRr(15);

            Object result = finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher);
            assertEquals(15, fieldOf(result, "nextOutboundTxSequence"));
            assertEquals(24, fieldOf(result, "nextExpectedWatchTxSequence"));

            List<L2capErtmCodec.Frame> iFrames = controller.sentTerminusIFrames();
            assertEquals(2, iFrames.size());
            assertEquals(13, iFrames.get(0).txSequence);
            assertEquals(14, iFrames.get(1).txSequence);
            assertEquals(24, iFrames.get(1).requestSequence);
            assertArrayEquals(iFrames.get(0).information, iFrames.get(1).information);
            assertEquals(List.of(24), controller.sentTerminusRrReqSeqs());
            assertTrue(logs().contains("WATCH REQUEST RETRY RX: authenticated byte-identical retry #1 TxSeq=23 ReqSeq=14"));
        }
    }

    @Test public void preservesEarlySmpTrafficAcrossWatchRetryAndResend() throws Exception {
        try (RetryFixture fixture = new RetryFixture(false)) {
            peer(BluetoothSmpCodec.FIXED_CID, SECURITY);
            peerWatchIFrame(23, 13, fixture.watchRequestPacket);
            peer(BluetoothSmpCodec.FIXED_CID, RESPONSE);
            peerSupervisoryRr(15);

            Object result = finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher);
            assertEquals(15, fieldOf(result, "nextOutboundTxSequence"));
            assertEquals(24, fieldOf(result, "nextExpectedWatchTxSequence"));
            assertEquals(2, deferred().size());
            sendRequest();
            assertArrayEquals(RESPONSE, await(BluetoothSmpCodec.PAIRING_RESPONSE, 500));
            assertEquals(0, deferred().size());
        }
    }

    @Test public void handlesDuplicateErtmIFrameAndSequenceWraparound() throws Exception {
        try (RetryFixture fixture = new RetryFixture(false)) {
            peerWatchIFrame(62, 63, new byte[]{0x01, 0x02});
            peerWatchIFrame(63, 63, fixture.watchRequestPacket);
            peerSupervisoryRr(1);

            Object result = finalAckWithMatcher(63, 63, fixture.ackPackets, fixture.matcher);
            assertEquals(1, fieldOf(result, "nextOutboundTxSequence"));
            assertEquals(0, fieldOf(result, "nextExpectedWatchTxSequence"));
            List<L2capErtmCodec.Frame> iFrames = controller.sentTerminusIFrames();
            assertEquals(2, iFrames.size());
            assertEquals(63, iFrames.get(0).txSequence);
            assertEquals(0, iFrames.get(1).txSequence);
            assertEquals(0, iFrames.get(1).requestSequence);
            assertEquals(List.of(63, 0), controller.sentTerminusRrReqSeqs());
        }
    }

    @Test public void rejectsFreshEncryptionAndDifferentContentInBothReqSeqPositions() throws Exception {
        for (int reqSeq : new int[]{13, 14}) {
            initSession();
            try (RetryFixture fixture = new RetryFixture(false)) {
                byte[] freshSameBatch = IkeV2SessionCrypto.createPairingPrivateNotifyWatchRequestForTest(
                        fixture.random, fixture.keys, fixture.watchNotifies).get(0);
                peerWatchIFrame(23, reqSeq, freshSameBatch);
                Exception failure = assertThrows(Exception.class,
                        () -> finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher));
                assertTrue(failure.getMessage().contains("Unexpected Watch I-frame"));
                assertEquals(1, controller.sentTerminusIFrames().size());
                assertTrue(controller.sentTerminusRrReqSeqs().isEmpty());
            }

            initSession();
            try (RetryFixture fixture = new RetryFixture(false)) {
                List<ApplePairingNotifyPayloads.PrivateNotify> differentNotifies =
                        new ArrayList<>(fixture.watchNotifies.subList(0, 8));
                byte[] differentContent = IkeV2SessionCrypto.createPairingPrivateNotifyWatchRequestForTest(
                        fixture.random, fixture.keys, differentNotifies).get(0);
                peerWatchIFrame(23, reqSeq, differentContent);
                Exception failure = assertThrows(Exception.class,
                        () -> finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher));
                assertTrue(failure.getMessage().contains("Unexpected Watch I-frame"));
                assertEquals(1, controller.sentTerminusIFrames().size());
                assertTrue(controller.sentTerminusRrReqSeqs().isEmpty());
            }
        }
    }

    @Test public void ignoresOtherHandleAndBoundsRetryFloodAndRejectsStrictIFrameAndSupervisoryRej()
            throws Exception {
        try (RetryFixture fixture = new RetryFixture(false)) {
            byte[] wrongHandleIFrame = L2capErtmCodec.encodeInformationFrame(
                    HciCodec.TERMINUS_LOCAL_CID, 23, 13,
                    IkeV2Codec.encodeUikeFrame(new byte[]{0x7f, 0x00}), false);
            for (byte[] packet : HciCodec.buildLeAclL2capFragments(
                    HANDLE + 1, HciCodec.TERMINUS_LOCAL_CID, wrongHandleIFrame, 256)) {
                callbacks.aclDataReceived(packet);
            }
            peerSupervisoryRr(14);
            Object result = finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher);
            assertEquals(14, fieldOf(result, "nextOutboundTxSequence"));
            assertEquals(1, controller.sentTerminusIFrames().size());
        }

        initSession();
        try (RetryFixture fixture = new RetryFixture(false)) {
            for (int i = 0; i < 5; i++) {
                peerWatchIFrame(23 + i, 13 + i, fixture.watchRequestPacket);
            }
            Exception failure = assertThrows(Exception.class,
                    () -> finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher));
            assertTrue(failure.getMessage().contains("Too many Watch request retries"));
            assertEquals(5, controller.sentTerminusIFrames().size());
        }

        initSession();
        try (RetryFixture fixture = new RetryFixture(false)) {
            peerWatchIFrame(23, 14, fixture.watchRequestPacket);
            Exception failure = assertThrows(Exception.class, this::finalAck);
            assertTrue(failure.getMessage().contains("Unexpected Watch I-frame"));
        }

        initSession();
        try (RetryFixture fixture = new RetryFixture(false)) {
            peer(HciCodec.TERMINUS_LOCAL_CID, L2capErtmCodec.encodeSupervisoryFrame(
                    HciCodec.TERMINUS_LOCAL_CID, L2capErtmCodec.SUPERVISORY_REJ, 14, false, false, false));
            Exception failure = assertThrows(Exception.class,
                    () -> finalAckWithMatcher(13, 23, fixture.ackPackets, fixture.matcher));
            assertTrue(failure.getMessage().contains("Watch rejected"));
        }
    }

    private void peerWatchIFrame(int txSeq, int reqSeq, byte[] ikePacket) {
        byte[] uike = IkeV2Codec.encodeUikeFrame(ikePacket);
        byte[] ertm = L2capErtmCodec.encodeInformationFrame(
                HciCodec.TERMINUS_LOCAL_CID, txSeq, reqSeq, uike, false);
        peer(HciCodec.TERMINUS_LOCAL_CID, ertm);
    }

    private void peerSupervisoryRr(int reqSeq) {
        peer(HciCodec.TERMINUS_LOCAL_CID,
                L2capErtmCodec.encodeReceiverReady(HciCodec.TERMINUS_LOCAL_CID, reqSeq, false, false));
    }

    private Object finalAckWithMatcher(
            int nextOutboundTx,
            int nextWatchTx,
            List<byte[]> packets,
            IkeV2SessionCrypto.WatchRequestRetryMatcher matcher) throws Exception {
        Class<?> type = Class.forName(HalTransportSession.class.getName() + "$PairingTransportCursor");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        return invoke("sendPairingIkeOneWayAndAwaitAck",
                0x0041,
                constructor.newInstance(nextOutboundTx, nextWatchTx),
                packets,
                "PAIRING PRIVATE-NOTIFIES WATCH-REQUEST ACK",
                500L,
                matcher);
    }

    private int fieldOf(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static byte[] sequenceBytes(int first, int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }

    private static final class RetryFixture implements AutoCloseable {
        final SecureRandom random = new SecureRandom();
        final IkeV2SessionCrypto.IkeSaKeys keys = new IkeV2SessionCrypto.IkeSaKeys(
                sequenceBytes(0x00, 64),
                sequenceBytes(0x20, 36),
                sequenceBytes(0x50, 36),
                sequenceBytes(0x40, 64),
                sequenceBytes(0x80, 64),
                sequenceBytes(0x10, 8),
                sequenceBytes(0x30, 8),
                sequenceBytes(0xa0, 32),
                sequenceBytes(0xc0, 32));
        final BleSecureConnectionsCrypto.LocalOobMaterial oob =
                BleSecureConnectionsCrypto.generateLocalOob(random);
        final AppleNetworkRelayPairingMaterial networkKeys =
                AppleNetworkRelayPairingMaterial.generate(random);
        final AppleNetworkRelayInnerAddresses addresses =
                AppleNetworkRelayInnerAddresses.generate(random);
        final ApplePairingNotifyPayloads.LocalBatch batch =
                ApplePairingNotifyPayloads.createLocalBatch(
                        oob, networkKeys, addresses, "Android", "test-build", null);
        final List<ApplePairingNotifyPayloads.PrivateNotify> localNotifies = batch.notifies();
        final List<ApplePairingNotifyPayloads.PrivateNotify> watchNotifies =
                new ArrayList<>(localNotifies.subList(0, 9));
        final byte[] watchRequestPacket =
                IkeV2SessionCrypto.createPairingPrivateNotifyWatchRequestForTest(
                        random, keys, watchNotifies).get(0);
        final List<byte[]> ackPackets;
        final IkeV2SessionCrypto.PairingPrivateNotifyResponseAccumulator accumulator =
                new IkeV2SessionCrypto.PairingPrivateNotifyResponseAccumulator(keys);
        final IkeV2SessionCrypto.PairingPrivateNotifyResponse response;
        final IkeV2SessionCrypto.WatchRequestRetryMatcher matcher;

        RetryFixture(boolean peerRequestBeforeLocalAck) {
            byte[] emptyAck = IkeV2SessionCrypto.createPairingPrivateNotifyEmptyResponseForTest(
                    random, keys).get(0);
            if (peerRequestBeforeLocalAck) {
                assertNull(accumulator.accept(watchRequestPacket));
                response = accumulator.accept(emptyAck);
            } else {
                assertNull(accumulator.accept(emptyAck));
                response = accumulator.accept(watchRequestPacket);
            }
            ackPackets = IkeV2SessionCrypto.createWatchInformationalAcknowledgement(
                    random, keys, response.peerRequestMessageId);
            matcher = response.createRetryMatcher();
        }

        @Override public void close() {
            matcher.close();
            accumulator.destroy();
            response.destroy();
            for (ApplePairingNotifyPayloads.PrivateNotify notify : localNotifies) notify.destroy();
            for (ApplePairingNotifyPayloads.PrivateNotify notify : watchNotifies) notify.destroy();
            batch.destroy();
            addresses.destroy();
            networkKeys.destroy();
            oob.destroy();
        }
    }

    private void peer(int cid, byte[] pdu) {
        for (byte[] packet : HciCodec.buildLeAclL2capFragments(HANDLE, cid, pdu, 256)) {
            callbacks.aclDataReceived(packet);
        }
    }

    private void peerAck() {
        peer(HciCodec.TERMINUS_LOCAL_CID,
                L2capErtmCodec.encodeReceiverReady(HciCodec.TERMINUS_LOCAL_CID, 1, false, false));
    }

    private void disconnect() {
        callbacks.hciEventReceived(new byte[]{5, 4, 0, (byte) HANDLE, 0, 0x13});
    }

    private void finalAck() throws Exception {
        Class<?> type = Class.forName(HalTransportSession.class.getName() + "$PairingTransportCursor");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, int.class);
        constructor.setAccessible(true);
        invoke("sendPairingIkeOneWayAndAwaitAck", 0x0041, constructor.newInstance(0, 0),
                List.of(new byte[28]), "PAIRING PRIVATE-NOTIFIES WATCH-REQUEST ACK", 500L);
    }

    private void sendRequest() throws Exception {
        invoke("sendL2cap", BluetoothSmpCodec.FIXED_CID,
                BluetoothSmpCodec.createPairingRequest().encode(), "SMP PAIRING REQUEST");
    }

    private byte[] await(int opcode, long timeout) throws Exception {
        return (byte[]) invoke("awaitSmpPdu", opcode, timeout, "SMP test response");
    }

    @SuppressWarnings("unchecked") private BlockingQueue<byte[]> deferred() throws Exception {
        return (BlockingQueue<byte[]>) field("deferredSmpPdus").get(session);
    }

    private String logs() { return output.toString(StandardCharsets.UTF_8); }

    private Field field(String name) throws Exception {
        Field field = HalTransportSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private Object invoke(String name, Object... arguments) throws Exception {
        for (Method method : HalTransportSession.class.getDeclaredMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            method.setAccessible(true);
            try { return method.invoke(session, arguments); }
            catch (InvocationTargetException error) {
                if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
                throw new AssertionError(error.getCause());
            }
        }
        throw new AssertionError("No method: " + name);
    }

    @Test public void configuresLocalLeCapabilitiesWhenSupported() throws Exception {
        controller.commandHandler = (opcode, cmd) -> {
            switch (opcode) {
                case HciCodec.OPCODE_LE_READ_LOCAL_SUPPORTED_FEATURES:
                    callbacks.hciEventReceived(new byte[]{
                            0x0E, 0x0C, 0x01, 0x03, 0x20, 0x00,
                            0x20, 0x01, 0, 0, 0, 0, 0, 0});
                    break;
                case HciCodec.OPCODE_LE_SET_EVENT_MASK:
                    assertArrayEquals(new byte[]{0x4B, 0x08, 0, 0, 0, 0, 0, 0},
                            Arrays.copyOfRange(cmd, 3, cmd.length));
                    callbacks.hciEventReceived(new byte[]{0x0E, 0x04, 0x01, 0x01, 0x20, 0x00});
                    break;
                case HciCodec.OPCODE_LE_READ_MAXIMUM_DATA_LENGTH:
                    callbacks.hciEventReceived(new byte[]{
                            0x0E, 0x0C, 0x01, 0x2F, 0x20, 0x00,
                            (byte) 0xFB, 0x00, (byte) 0x90, 0x42,
                            (byte) 0xFB, 0x00, (byte) 0x90, 0x42});
                    break;
                case HciCodec.OPCODE_LE_WRITE_SUGGESTED_DEFAULT_DATA_LENGTH:
                    assertArrayEquals(new byte[]{(byte) 251, 0x00, 0x48, 0x08},
                            Arrays.copyOfRange(cmd, 3, cmd.length));
                    callbacks.hciEventReceived(new byte[]{0x0E, 0x04, 0x01, 0x24, 0x20, 0x00});
                    break;
                case HciCodec.OPCODE_LE_SET_DEFAULT_PHY:
                    assertArrayEquals(new byte[]{0x00, 0x02, 0x02},
                            Arrays.copyOfRange(cmd, 3, cmd.length));
                    callbacks.hciEventReceived(new byte[]{0x0E, 0x04, 0x01, 0x31, 0x20, 0x00});
                    break;
                default:
                    throw new AssertionError("Unexpected HCI opcode: 0x" + Integer.toHexString(opcode));
            }
        };

        invoke("configureLocalLeCapabilities");
        assertTrue(field("localLeDataLengthSupported").getBoolean(session));
        assertTrue(field("localLe2mPhySupported").getBoolean(session));
        assertEquals(List.of(
                HciCodec.OPCODE_LE_READ_LOCAL_SUPPORTED_FEATURES,
                HciCodec.OPCODE_LE_SET_EVENT_MASK,
                HciCodec.OPCODE_LE_READ_MAXIMUM_DATA_LENGTH,
                HciCodec.OPCODE_LE_WRITE_SUGGESTED_DEFAULT_DATA_LENGTH,
                HciCodec.OPCODE_LE_SET_DEFAULT_PHY), controller.sentOpcodes());
    }

    @Test public void fallsBackCleanlyWhenLocalLeFeaturesCommandUnsupported() throws Exception {
        controller.commandHandler = (opcode, cmd) -> {
            if (opcode == HciCodec.OPCODE_LE_READ_LOCAL_SUPPORTED_FEATURES) {
                callbacks.hciEventReceived(new byte[]{0x0E, 0x04, 0x01, 0x03, 0x20, 0x01});
            } else if (opcode == HciCodec.OPCODE_LE_SET_EVENT_MASK) {
                assertArrayEquals(new byte[]{0x0B, 0x00, 0, 0, 0, 0, 0, 0},
                        Arrays.copyOfRange(cmd, 3, cmd.length));
                callbacks.hciEventReceived(new byte[]{0x0E, 0x04, 0x01, 0x01, 0x20, 0x00});
            } else {
                throw new AssertionError("Unexpected HCI opcode: 0x" + Integer.toHexString(opcode));
            }
        };

        invoke("configureLocalLeCapabilities");
        assertFalse(field("localLeDataLengthSupported").getBoolean(session));
        assertFalse(field("localLe2mPhySupported").getBoolean(session));
        assertEquals(List.of(
                HciCodec.OPCODE_LE_READ_LOCAL_SUPPORTED_FEATURES,
                HciCodec.OPCODE_LE_SET_EVENT_MASK), controller.sentOpcodes());
    }

    @Test public void negotiatesLePhyAndDataLengthAndPreservesEarlyAclOrder() throws Exception {
        field("localLeDataLengthSupported").setBoolean(session, true);
        field("localLe2mPhySupported").setBoolean(session, true);
        byte[] earlyBtCl1 = HciCodec.buildBtClVersionPdu();
        byte[] earlyBtCl2 = HciCodec.buildRemoteServicesPdu(HciCodec.BT_CL_CURRENT_VERSION);

        controller.commandHandler = (opcode, cmd) -> {
            if (opcode == HciCodec.OPCODE_LE_SET_PHY) {
                assertArrayEquals(new byte[]{HANDLE, 0x00, 0x00, 0x02, 0x02, 0x00, 0x00},
                        Arrays.copyOfRange(cmd, 3, cmd.length));
                callbacks.hciEventReceived(new byte[]{0x0F, 0x04, 0x00, 0x01, 0x32, 0x20});
                peer(HciCodec.BT_CL_SIGNALING_CID, earlyBtCl1);
                callbacks.hciEventReceived(new byte[]{0x3E, 0x06, 0x0C, 0x00, HANDLE, 0x00, 0x02, 0x02});
            } else if (opcode == HciCodec.OPCODE_LE_SET_DATA_LENGTH) {
                assertArrayEquals(new byte[]{HANDLE, 0x00, (byte) 251, 0x00, 0x48, 0x08},
                        Arrays.copyOfRange(cmd, 3, cmd.length));
                peer(HciCodec.BT_CL_SIGNALING_CID, earlyBtCl2);
                callbacks.hciEventReceived(new byte[]{0x0E, 0x04, 0x01, 0x22, 0x20, 0x00});
                callbacks.hciEventReceived(new byte[]{
                        0x3E, 0x0B, 0x07, HANDLE, 0x00,
                        (byte) 251, 0x00, 0x48, 0x08, (byte) 251, 0x00, 0x48, 0x08});
            } else {
                throw new AssertionError("Unexpected HCI opcode: 0x" + Integer.toHexString(opcode));
            }
        };

        invoke("negotiateLeLinkParameters", HANDLE);

        String diagnostics = (String) invoke("pairingSmpDiagnostics");
        assertTrue(diagnostics.contains("lePhy={observed=true,status=0x00,tx=2,rx=2}"));
        assertTrue(diagnostics.contains("leDle={observed=true,tx=251,rx=251}"));
        assertTrue(logs().contains("LE LINK NEGOTIATION DONE"));

        // Early ACL packets pushed during PHY & DLE wait must come out in exact FIFO order.
        Method nextPacket = HalTransportSession.class.getDeclaredMethod("nextPacket", long.class);
        nextPacket.setAccessible(true);
        Field bytesField = Class.forName(HalTransportSession.class.getName() + "$Packet")
                .getDeclaredField("bytes");
        bytesField.setAccessible(true);
        HciCodec.AclReassembler reassembler = new HciCodec.AclReassembler();
        long deadline = BridgeClock.elapsedRealtime() + 500L;
        HciCodec.L2capPdu first = reassembler.accept((byte[]) bytesField.get(nextPacket.invoke(session, deadline)));
        HciCodec.L2capPdu second = reassembler.accept((byte[]) bytesField.get(nextPacket.invoke(session, deadline)));
        assertEquals(HciCodec.BT_CL_SIGNALING_CID, first.destinationCid);
        assertArrayEquals(earlyBtCl1, first.payload);
        assertEquals(HciCodec.BT_CL_SIGNALING_CID, second.destinationCid);
        assertArrayEquals(earlyBtCl2, second.payload);
    }

    @Test public void skipsRedundantNegotiationWhenControllerAlreadyUpdatedPhyAndDle() throws Exception {
        field("localLeDataLengthSupported").setBoolean(session, true);
        field("localLe2mPhySupported").setBoolean(session, true);
        callbacks.hciEventReceived(new byte[]{0x3E, 0x06, 0x0C, 0x00, HANDLE, 0x00, 0x02, 0x02});
        callbacks.hciEventReceived(new byte[]{
                0x3E, 0x0B, 0x07, HANDLE, 0x00,
                (byte) 251, 0x00, 0x48, 0x08, (byte) 251, 0x00, 0x48, 0x08});
        // Pump both queued events through nextPacket via diagnostics before starting.
        Method nextPacket = HalTransportSession.class.getDeclaredMethod("nextPacket", long.class);
        nextPacket.setAccessible(true);
        long deadline = BridgeClock.elapsedRealtime() + 500L;
        nextPacket.invoke(session, deadline);
        nextPacket.invoke(session, deadline);

        invoke("negotiateLeLinkParameters", HANDLE);
        assertTrue(controller.sentOpcodes().isEmpty());
        assertTrue(((String) invoke("pairingSmpDiagnostics")).contains("lePhy={observed=true,status=0x00,tx=2,rx=2}"));
    }

    @Test public void continuesWithoutFailingOnCollisionOrUnsupportedPhyAndFailsFastOnDisconnect() throws Exception {
        field("localLeDataLengthSupported").setBoolean(session, true);
        field("localLe2mPhySupported").setBoolean(session, true);
        controller.commandHandler = (opcode, cmd) -> {
            if (opcode == HciCodec.OPCODE_LE_SET_PHY) {
                callbacks.hciEventReceived(new byte[]{0x0F, 0x04, 0x00, 0x01, 0x32, 0x20});
                callbacks.hciEventReceived(new byte[]{0x3E, 0x06, 0x0C, 0x2A, HANDLE, 0x00, 0x01, 0x01});
            } else if (opcode == HciCodec.OPCODE_LE_SET_DATA_LENGTH) {
                callbacks.hciEventReceived(new byte[]{0x0E, 0x04, 0x01, 0x22, 0x20, 0x01});
            }
        };

        invoke("negotiateLeLinkParameters", HANDLE);
        assertTrue(logs().contains("LE_Set_Data_Length status=0x01; continuing."));

        initSession();
        field("localLeDataLengthSupported").setBoolean(session, false);
        field("localLe2mPhySupported").setBoolean(session, true);
        controller.commandHandler = (opcode, cmd) -> {
            if (opcode == HciCodec.OPCODE_LE_SET_PHY) {
                callbacks.hciEventReceived(new byte[]{0x0F, 0x04, 0x00, 0x01, 0x32, 0x20});
                disconnect();
            }
        };
        Exception failed = assertThrows(Exception.class,
                () -> invoke("negotiateLeLinkParameters", HANDLE));
        assertTrue(failed.getMessage().contains("Watch disconnected during LE link negotiation"));
    }

    private static final class FakeController implements BluetoothController {
        private final List<byte[]> sent = new ArrayList<>();
        private final List<byte[]> sentCommands = new ArrayList<>();
        private Callbacks callbacks;
        private boolean failWrite;
        private Runnable afterWrite;
        private java.util.function.BiConsumer<Integer, byte[]> commandHandler;
        @Override public void initialize(Callbacks callbacks) { this.callbacks = callbacks; }
        @Override public void sendHciCommand(byte[] bytes) {
            byte[] copy = bytes.clone();
            sentCommands.add(copy);
            if (commandHandler != null && copy.length >= 3) {
                int opcode = (copy[0] & 0xFF) | ((copy[1] & 0xFF) << 8);
                commandHandler.accept(opcode, copy);
            }
        }
        private List<Integer> sentOpcodes() {
            List<Integer> opcodes = new ArrayList<>();
            for (byte[] cmd : sentCommands) {
                if (cmd.length >= 2) {
                    opcodes.add((cmd[0] & 0xFF) | ((cmd[1] & 0xFF) << 8));
                }
            }
            return opcodes;
        }
        @Override public void close() { }
        @Override public void sendAclData(byte[] bytes) throws IOException {
            if (failWrite) throw new IOException("synthetic controller write failure");
            sent.add(bytes.clone());
            callbacks.hciEventReceived(new byte[]{0x13, 5, 1, (byte) HANDLE, 0, 1, 0});
            if (afterWrite != null) afterWrite.run();
        }
        private List<L2capErtmCodec.Frame> sentTerminusFrames() {
            HciCodec.AclReassembler decoder = new HciCodec.AclReassembler();
            List<L2capErtmCodec.Frame> frames = new ArrayList<>();
            for (byte[] packet : sent) {
                HciCodec.L2capPdu pdu = decoder.accept(packet);
                if (pdu != null && pdu.destinationCid == 0x0041) {
                    frames.add(L2capErtmCodec.decode(HciCodec.TERMINUS_LOCAL_CID, pdu.payload, false));
                }
            }
            return frames;
        }
        private List<L2capErtmCodec.Frame> sentTerminusIFrames() {
            List<L2capErtmCodec.Frame> iFrames = new ArrayList<>();
            for (L2capErtmCodec.Frame frame : sentTerminusFrames()) {
                if (!frame.supervisory) iFrames.add(frame);
            }
            return iFrames;
        }
        private List<Integer> sentTerminusRrReqSeqs() {
            List<Integer> reqSeqs = new ArrayList<>();
            for (L2capErtmCodec.Frame frame : sentTerminusFrames()) {
                if (frame.supervisory && frame.supervisoryFunction == L2capErtmCodec.SUPERVISORY_RR) {
                    reqSeqs.add(frame.requestSequence);
                }
            }
            return reqSeqs;
        }
        private int smpRequests() {
            HciCodec.AclReassembler decoder = new HciCodec.AclReassembler();
            int requests = 0;
            for (byte[] packet : sent) {
                HciCodec.L2capPdu pdu = decoder.accept(packet);
                if (pdu != null && pdu.destinationCid == BluetoothSmpCodec.FIXED_CID
                        && (pdu.payload[0] & 255) == BluetoothSmpCodec.PAIRING_REQUEST) requests++;
            }
            return requests;
        }
    }
}
