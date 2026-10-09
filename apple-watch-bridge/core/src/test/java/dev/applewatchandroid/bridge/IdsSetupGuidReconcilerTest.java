package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IdsSetupGuidReconcilerTest {
    private static final String LOWER_GUID =
            "00112233-4455-6677-8899-aabbccddeeff";
    private static final String HIGHER_GUID =
            "f0112233-4455-6677-8899-aabbccddeeff";

    @Test
    public void freshRemoteRequestCreatesConnectionAndReciprocalSetup() {
        IdsSetupGuidReconciler.Decision decision =
                IdsSetupGuidReconciler.reconcile(
                        null,
                        false,
                        setup(
                                HIGHER_GUID,
                                null));

        assertEquals(
                IdsSetupGuidReconciler.Action
                        .CREATE_FROM_REMOTE_AND_REPLY,
                decision.action);
        assertTrue(
                decision.sendSetupReply);
        assertTrue(
                decision.adoptIncomingAddressPair);
        assertEquals(
                IdsControlChannelCodec.DATA_PORT,
                decision.incomingLocalPort);
        assertEquals(
                49152,
                decision.incomingRemotePort);
        assertTrue(
                decision.completeSetup);
        assertFalse(
                decision.sendClose);
    }

    @Test
    public void replyForCleanedConnectionIsIgnored() {
        IdsSetupGuidReconciler.Decision decision =
                IdsSetupGuidReconciler.reconcile(
                        null,
                        false,
                        setup(
                                HIGHER_GUID,
                                LOWER_GUID));

        assertEquals(
                IdsSetupGuidReconciler.Action
                        .IGNORE_REPLY_TO_CLEANED_CONNECTION,
                decision.action);
        assertFalse(
                decision.completeSetup);
        assertFalse(
                decision.sendSetupReply);
    }

    @Test
    public void replyMustNameTheCurrentLocalGuid() {
        IdsSetupGuidReconciler.Decision wrong =
                IdsSetupGuidReconciler.reconcile(
                        LOWER_GUID,
                        false,
                        setup(
                                HIGHER_GUID,
                                HIGHER_GUID));
        IdsSetupGuidReconciler.Decision current =
                IdsSetupGuidReconciler.reconcile(
                        LOWER_GUID,
                        false,
                        setup(
                                HIGHER_GUID,
                                LOWER_GUID));

        assertEquals(
                IdsSetupGuidReconciler.Action
                        .SEND_CLOSE_FOR_REMOTE_ATTEMPT,
                wrong.action);
        assertTrue(
                wrong.sendClose);
        assertFalse(
                wrong.completeSetup);
        assertEquals(
                IdsSetupGuidReconciler.Action
                        .COMPLETE_LOCAL_INITIATED_SETUP,
                current.action);
        assertTrue(
                current.completeSetup);
        assertFalse(
                current.adoptIncomingAddressPair);
    }

    @Test
    public void repeatedCurrentReplyDoesNotRestartEstablishedChannel() {
        IdsSetupGuidReconciler.Decision decision =
                IdsSetupGuidReconciler.reconcile(
                        LOWER_GUID,
                        true,
                        setup(
                                HIGHER_GUID,
                                LOWER_GUID));

        assertEquals(
                IdsSetupGuidReconciler.Action
                        .IGNORE_REPEATED_CURRENT_SETUP,
                decision.action);
        assertFalse(
                decision.completeSetup);
        assertFalse(
                decision.sendClose);
    }

    @Test
    public void newInitialRequestReplacesAnEstablishedStaleChannel() {
        IdsSetupGuidReconciler.Decision decision =
                IdsSetupGuidReconciler.reconcile(
                        LOWER_GUID,
                        true,
                        setup(
                                HIGHER_GUID,
                                null));

        assertEquals(
                IdsSetupGuidReconciler.Action
                        .REPLACE_STALE_CONNECTION_AND_REPLY,
                decision.action);
        assertTrue(
                decision.replaceExisting);
        assertTrue(
                decision.sendSetupReply);
        assertTrue(
                decision.adoptIncomingAddressPair);
    }

    @Test
    public void smallerGuidWinsARealSimultaneousOpen() {
        IdsSetupGuidReconciler.Decision remoteWins =
                IdsSetupGuidReconciler.reconcile(
                        HIGHER_GUID,
                        false,
                        setup(
                                LOWER_GUID,
                                null));
        IdsSetupGuidReconciler.Decision localWins =
                IdsSetupGuidReconciler.reconcile(
                        LOWER_GUID,
                        false,
                        setup(
                                HIGHER_GUID,
                                null));

        assertEquals(
                IdsSetupGuidReconciler.Action
                        .COMPLETE_SIMULTANEOUS_REMOTE_WINS,
                remoteWins.action);
        assertTrue(
                remoteWins.adoptIncomingAddressPair);
        assertEquals(
                IdsSetupGuidReconciler.Action
                        .COMPLETE_SIMULTANEOUS_LOCAL_WINS,
                localWins.action);
        assertFalse(
                localWins.adoptIncomingAddressPair);
        assertFalse(
                localWins.sendSetupReply);
    }

    @Test
    public void encryptedSetupUsesTheSameGuidArbitration() {
        IdsSetupGuidReconciler.Decision decision =
                IdsSetupGuidReconciler.reconcile(
                        HIGHER_GUID,
                        false,
                        encryptedSetup(
                                LOWER_GUID,
                                null));

        assertEquals(
                IdsSetupGuidReconciler.Action
                        .COMPLETE_SIMULTANEOUS_REMOTE_WINS,
                decision.action);
    }

    @Test
    public void localStateMustBeInternallyConsistent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> IdsSetupGuidReconciler.reconcile(
                        null,
                        true,
                        setup(
                                HIGHER_GUID,
                                null)));
        IdsSetupGuidReconciler.Decision upper =
                IdsSetupGuidReconciler.reconcile(
                        HIGHER_GUID.toUpperCase(),
                        false,
                        setup(
                                LOWER_GUID,
                                null));
        assertEquals(
                IdsSetupGuidReconciler.Action
                        .COMPLETE_SIMULTANEOUS_REMOTE_WINS,
                upper.action);
    }

    private static IdsControlChannelCodec.SetupChannelMessage
            setup(
                    String remoteGuid,
                    String forLocalGuid) {
        return new IdsControlChannelCodec.SetupChannelMessage(
                IdsControlChannelCodec.TYPE_SETUP_CHANNEL,
                IdsControlChannelCodec.PROTOCOL_TCP,
                49152,
                IdsControlChannelCodec.DATA_PORT,
                remoteGuid,
                forLocalGuid,
                IdsServiceConnectorName.LOCAL_ACCOUNT,
                IdsServiceConnectorName.LOCAL_DELIVERY_SERVICE,
                IdsUtunConnectionName.defaultPaired(
                        IdsUtunConnectionName.PRIORITY_DEFAULT,
                        IdsUtunConnectionName.PROTECTION_CLASS_C),
                null);
    }

    private static IdsControlChannelCodec
            .SetupEncryptedChannelMessage encryptedSetup(
                    String remoteGuid,
                    String forLocalGuid) {
        return new IdsControlChannelCodec
                .SetupEncryptedChannelMessage(
                        IdsControlChannelCodec.PROTOCOL_TCP,
                        49152,
                        IdsControlChannelCodec.DATA_PORT,
                        remoteGuid,
                        forLocalGuid,
                        IdsServiceConnectorName.LOCAL_ACCOUNT,
                        IdsServiceConnectorName
                                .LOCAL_DELIVERY_SERVICE,
                        IdsUtunConnectionName.defaultPaired(
                                IdsUtunConnectionName
                                        .PRIORITY_DEFAULT,
                                IdsUtunConnectionName
                                        .PROTECTION_CLASS_C),
                        1,
                        2,
                        new byte[60]);
    }
}
