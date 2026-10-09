package dev.applewatchandroid.bridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PairingSessionRecordTest {
    @Test
    public void spsMetadataSurvivesRecordCopiesWithoutClaimingSetupOrActivation() {
        Fixture fixture = Fixture.create();
        List<PairingSessionRecord> records = new ArrayList<>();
        byte[] metadata = null;
        byte[] serialized = null;
        try (var sps = IdsSpsCompanionInfo.fromMessage(java.util.Map.of("command",5L,
                "sps-device-udid",IdsSpsCompanionInfoTest.UDID,"sps-phone-numbers",List.of()))) {
            metadata = sps.serialize();
            final byte[] payload = metadata;
            PairingSessionRecord initial = fixture.createRecord(); records.add(initial);
            assertNull(initial.peerSpsMetadata());
            assertThrows(IllegalStateException.class, () -> initial.withPeerSpsMetadata(payload));
            byte[] bond = fixture.bondRecord();
            PairingSessionRecord current;
            try { current = initial.withBluetoothBond(bond); } finally { wipe(bond); }
            records.add(current);
            current = current.withPreludeNegotiated(NrLinkBluetoothPrelude.LocalRole.RESPONDER); records.add(current);
            current = current.withClassDEstablished(null); records.add(current);
            current = current.advanceTo(PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED); records.add(current);
            current = current.markIdsAuthenticationAccepted(); records.add(current);
            long before = current.transitionCounter();
            current = current.withPeerSpsMetadata(metadata); records.add(current);
            assertEquals(PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED, current.state());
            assertEquals(before + 1, current.transitionCounter());
            serialized = current.serialize();
            current = PairingSessionRecord.parse(serialized); records.add(current);
            assertArrayEquals(metadata,current.peerSpsMetadata());
            current = current.advanceTo(PairingSessionRecord.DurableState.IDS_CONTROL_READY); records.add(current);
            assertArrayEquals(metadata,current.peerSpsMetadata());
            assertFalse(current.hasObservedSetupEvidence());
            assertFalse(current.isPairedCommitIntentPersisted());
            byte[] detached = current.peerSpsMetadata();
            Arrays.fill(detached,(byte)0);
            assertArrayEquals(metadata,current.peerSpsMetadata());
        } finally {
            records.forEach(PairingSessionRecord::destroy);
            fixture.destroy(); wipe(metadata); wipe(serialized);
        }
    }

    @Test
    public void roundTripsAndEnforcesTransientSecretBarriers() {
        Fixture fixture = Fixture.create();
        PairingSessionRecord initial = null;
        PairingSessionRecord parsed = null;
        PairingSessionRecord bonded = null;
        PairingSessionRecord idsAccepted = null;
        PairingSessionRecord negotiated = null;
        PairingSessionRecord established = null;
        PairingSessionRecord classCEstablished = null;
        byte[] serialized = null;
        byte[] bondedSerialized = null;
        try {
            initial = fixture.createRecord();
            assertEquals(
                    PairingSessionRecord.DurableState
                            .PAIRING_MATERIAL_PERSISTED,
                    initial.state());
            assertEquals(1, initial.transitionCounter());
            assertTrue(initial.hasPendingSmpOob());
            assertTrue(
                    initial.hasPendingIdsAuthenticationData());
            assertFalse(initial.hasBluetoothBond());
            assertNull(initial.localRole());
            assertFalse(initial.addressesConfirmed());

            serialized = initial.serialize();
            parsed = PairingSessionRecord.parse(serialized);
            assertArrayEquals(
                    fixture.generation,
                    parsed.generationUuid());
            assertEquals(
                    "00112233-4455-6677-8899-aabbccddeeff",
                    parsed.bluetoothCbUuid());
            assertEquals(
                    "10213243-5465-7687-98a9-bacbdcedfe0f",
                    parsed.nrUuid());
            assertEquals(
                    "20314253-6475-8697-a8b9-cadbecfd0e1f",
                    parsed.localIdsDeviceUuid());
            assertEquals(
                    initial.state(),
                    parsed.state());
            AppleNetworkRelayPairingMaterial restored =
                    parsed.restoreLocalMaterial();
            try {
                assertArrayEquals(
                        fixture.localMaterial.identityPayload(),
                        restored.identityPayload());
                assertArrayEquals(
                        fixture.localMaterial
                                .classDPublicKeysPayload(),
                        restored.classDPublicKeysPayload());
            } finally {
                restored.destroy();
            }

            byte[] bond = fixture.bondRecord();
            try {
                bonded = parsed.withBluetoothBond(bond);
            } finally {
                Arrays.fill(bond, (byte) 0);
            }
            assertEquals(
                    PairingSessionRecord.DurableState
                            .SMP_BONDED_RAW,
                    bonded.state());
            assertEquals(2, bonded.transitionCounter());
            assertTrue(bonded.hasBluetoothBond());
            assertFalse(bonded.hasPendingSmpOob());
            assertTrue(
                    bonded.hasPendingIdsAuthenticationData());

            PairingSessionRecord finalBonded = bonded;
            assertThrows(
                    IllegalStateException.class,
                    finalBonded::markIdsAuthenticationAccepted);

            negotiated =
                    bonded.withPreludeNegotiated(
                            NrLinkBluetoothPrelude
                                    .LocalRole.RESPONDER);
            assertEquals(
                    PairingSessionRecord.DurableState
                            .NETWORK_RELAY_PRELUDE_NEGOTIATED,
                    negotiated.state());
            assertEquals(
                    NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                    negotiated.localRole());
            assertTrue(negotiated.addressesConfirmed());
            AppleNetworkRelayInnerAddresses restoredAddresses =
                    negotiated.innerAddresses();
            try {
                assertArrayEquals(
                        fixture.addresses.initiatorClassD(),
                        restoredAddresses.initiatorClassD());
            } finally {
                restoredAddresses.destroy();
            }

            established =
                    negotiated.withClassDEstablished(null);
            assertEquals(
                    PairingSessionRecord.DurableState
                            .CLASS_D_ESTABLISHED,
                    established.state());
            assertTrue(established.addressesConfirmed());

            classCEstablished =
                    established.advanceTo(
                            PairingSessionRecord.DurableState
                                    .CLASS_C_ESTABLISHED);
            idsAccepted =
                    classCEstablished
                            .markIdsAuthenticationAccepted();
            assertTrue(
                    idsAccepted.idsAuthenticationAccepted());
            assertFalse(
                    idsAccepted
                            .hasPendingIdsAuthenticationData());
            assertNull(
                    idsAccepted
                            .pendingIdsAuthenticationData());

            bondedSerialized = idsAccepted.serialize();
            PairingSessionRecord finalParsed =
                    PairingSessionRecord.parse(
                            bondedSerialized);
            try {
                assertEquals(
                        idsAccepted.transitionCounter(),
                        finalParsed.transitionCounter());
                assertTrue(finalParsed.hasBluetoothBond());
                assertTrue(
                        finalParsed.idsAuthenticationAccepted());
                assertFalse(
                        finalParsed.hasPendingSmpOob());
                assertFalse(
                        finalParsed
                                .hasPendingIdsAuthenticationData());
            } finally {
                finalParsed.destroy();
            }
        } finally {
            wipe(serialized);
            wipe(bondedSerialized);
            if (established != null) {
                established.destroy();
            }
            if (classCEstablished != null) {
                classCEstablished.destroy();
            }
            if (negotiated != null) {
                negotiated.destroy();
            }
            if (idsAccepted != null) {
                idsAccepted.destroy();
            }
            if (bonded != null) {
                bonded.destroy();
            }
            if (parsed != null) {
                parsed.destroy();
            }
            if (initial != null) {
                initial.destroy();
            }
            fixture.destroy();
        }
    }

    @Test
    public void rejectsCorruptionVersionAndInvalidTransitions() {
        Fixture fixture = Fixture.create();
        PairingSessionRecord initial = null;
        byte[] serialized = null;
        try {
            initial = fixture.createRecord();
            serialized = initial.serialize();

            byte[] corrupt = serialized.clone();
            corrupt[100] ^= 1;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PairingSessionRecord.parse(corrupt));
            wipe(corrupt);

            byte[] wrongVersion = serialized.clone();
            wrongVersion[5] = 3;
            rewriteDigest(wrongVersion);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> PairingSessionRecord.parse(
                            wrongVersion));
            wipe(wrongVersion);

            PairingSessionRecord current = initial;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> current.advanceTo(
                            PairingSessionRecord.DurableState
                                    .CLASS_D_ESTABLISHED));
            assertThrows(
                    IllegalStateException.class,
                    () -> current.withPreludeNegotiated(
                            NrLinkBluetoothPrelude
                                    .LocalRole.INITIATOR));
        } finally {
            wipe(serialized);
            if (initial != null) {
                initial.destroy();
            }
            fixture.destroy();
        }
    }

    @Test
    public void separatesPreludeRoleFromAddressAuthority() {
        Fixture fixture = Fixture.create();
        PairingSessionRecord initial = null;
        PairingSessionRecord bonded = null;
        PairingSessionRecord responderPrelude = null;
        PairingSessionRecord responderClassD = null;
        PairingSessionRecord initiatorPrelude = null;
        PairingSessionRecord initiatorClassD = null;
        AppleNetworkRelayInnerAddresses swapped = null;
        try {
            initial = fixture.createRecord();
            byte[] bond = fixture.bondRecord();
            try {
                bonded = initial.withBluetoothBond(bond);
            } finally {
                wipe(bond);
            }
            swapped = fixture.addressesWithRolesSwapped();

            responderPrelude =
                    bonded.withPreludeNegotiated(
                            NrLinkBluetoothPrelude
                                    .LocalRole.RESPONDER);
            assertTrue(responderPrelude.addressesConfirmed());
            PairingSessionRecord finalResponderPrelude =
                    responderPrelude;
            AppleNetworkRelayInnerAddresses finalSwapped =
                    swapped;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> finalResponderPrelude
                            .withClassDEstablished(
                                    finalSwapped));
            responderClassD =
                    responderPrelude
                            .withClassDEstablished(null);
            assertEquals(
                    PairingSessionRecord.DurableState
                            .CLASS_D_ESTABLISHED,
                    responderClassD.state());

            initiatorPrelude =
                    bonded.withPreludeNegotiated(
                            NrLinkBluetoothPrelude
                                    .LocalRole.INITIATOR);
            assertFalse(initiatorPrelude.addressesConfirmed());
            PairingSessionRecord finalInitiatorPrelude =
                    initiatorPrelude;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> finalInitiatorPrelude
                            .withClassDEstablished(null));
            initiatorClassD =
                    initiatorPrelude
                            .withClassDEstablished(swapped);
            assertTrue(initiatorClassD.addressesConfirmed());
            AppleNetworkRelayInnerAddresses restored =
                    initiatorClassD.innerAddresses();
            try {
                assertArrayEquals(
                        swapped.initiatorClassD(),
                        restored.initiatorClassD());
                assertArrayEquals(
                        swapped.responderClassD(),
                        restored.responderClassD());
            } finally {
                restored.destroy();
            }
            byte[] localBefore =
                    initiatorClassD.copyConfirmedLocalAddress(true);
            byte[] remoteBefore =
                    initiatorClassD.copyConfirmedRemoteAddress(true);
            PairingSessionRecord pinned = null;
            try {
                pinned = initiatorClassD.pinLocalRole(
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER);
                assertEquals(
                        NrLinkBluetoothPrelude.LocalRole.RESPONDER,
                        pinned.localRole());
                assertEquals(
                        initiatorClassD.state(),
                        pinned.state());
                assertArrayEquals(
                        remoteBefore,
                        pinned.copyConfirmedLocalAddress(true));
                assertArrayEquals(
                        localBefore,
                        pinned.copyConfirmedRemoteAddress(true));
            } finally {
                wipe(localBefore);
                wipe(remoteBefore);
                if (pinned != null) {
                    pinned.destroy();
                }
            }
        } finally {
            if (swapped != null) {
                swapped.destroy();
            }
            if (initiatorClassD != null) {
                initiatorClassD.destroy();
            }
            if (initiatorPrelude != null) {
                initiatorPrelude.destroy();
            }
            if (responderClassD != null) {
                responderClassD.destroy();
            }
            if (responderPrelude != null) {
                responderPrelude.destroy();
            }
            if (bonded != null) {
                bonded.destroy();
            }
            if (initial != null) {
                initial.destroy();
            }
            fixture.destroy();
        }
    }

    @Test
    public void restoresPostPreludeNormalPipeWithoutSequenceReset() {
        Fixture fixture =
                Fixture.create();
        PairingSessionRecord initial = null;
        PairingSessionRecord bonded = null;
        NrLinkBluetoothPipeBootstrap bootstrap = null;
        NrLinkBluetoothPipeBootstrap.PreludeHandoff detached = null;
        NormalLinkPipeSession pipe = null;
        List<byte[]> initialOrdinary = null;
        byte[] localUuid = new byte[16];
        byte[] remoteUuid = new byte[16];
        remoteUuid[15] = 1;
        byte[] remotePrelude =
                NrLinkBluetoothPrelude.encodeFreshModern(
                        remoteUuid,
                        false,
                        false);
        try {
            initial =
                    fixture.createRecord();
            byte[] bond =
                    fixture.bondRecord();
            try {
                bonded =
                        initial.withBluetoothBond(
                                bond);
            } finally {
                wipe(
                        bond);
            }

            bootstrap =
                    NrLinkBluetoothPipeBootstrap
                            .withSessionForTest(
                                    openNormalPipe(),
                                    NrLinkBluetoothSession
                                            .withLocalUuidForTest(
                                                    localUuid));
            byte[] localFrame =
                    bootstrap.buildOutboundPreludeFrame();
            wipe(
                    localFrame);
            byte[] remoteFrame =
                    L2capErtmCodec.encodeInformationFrame(
                            0x0041,
                            0,
                            1,
                            remotePrelude,
                            false);
            try {
                bootstrap.acceptInboundPreludeFrame(
                        remoteFrame);
            } finally {
                wipe(
                        remoteFrame);
            }
            byte[] acknowledgement =
                    bootstrap
                            .buildRemotePreludeAcknowledgementFrame();
            wipe(
                    acknowledgement);
            detached =
                    bootstrap.detach();

            pipe =
                    NormalLinkPipeSession
                            .resumeFromPairingRecord(
                                    fixture.random,
                                    detached,
                                    bonded,
                                    NormalLinkLocalDeviceProfile
                                            .androidCompanion(
                                                    "OnePlus 13",
                                                    "CPH2653"));
            initialOrdinary =
                    pipe.continueAfterPrelude();
            assertEquals(
                    1,
                    initialOrdinary.size());
            L2capErtmCodec.Frame decoded =
                    L2capErtmCodec.decode(
                            0x0042,
                            initialOrdinary.get(0),
                            false);
            try {
                assertEquals(
                        1,
                        decoded.txSequence);
                assertEquals(
                        1,
                        decoded.requestSequence);
                assertEquals(
                        NetworkRelayPacketCodec
                                .TYPE_IKEV2_POINT_TO_POINT,
                        decoded.information[0] & 0xff);
            } finally {
                wipe(
                        decoded.information);
            }
        } finally {
            if (initialOrdinary != null) {
                for (byte[] frame : initialOrdinary) {
                    wipe(
                            frame);
                }
                initialOrdinary.clear();
            }
            if (pipe != null) {
                pipe.close();
            }
            if (detached != null) {
                detached.close();
            }
            if (bootstrap != null) {
                bootstrap.close();
            }
            if (bonded != null) {
                bonded.destroy();
            }
            if (initial != null) {
                initial.destroy();
            }
            fixture.destroy();
            wipe(
                    localUuid);
            wipe(
                    remoteUuid);
            wipe(
                    remotePrelude);
        }
    }

    @Test
    public void destroyClosesAllSecretAccessors() {
        Fixture fixture = Fixture.create();
        PairingSessionRecord record =
                fixture.createRecord();
        record.destroy();
        assertThrows(
                IllegalStateException.class,
                record::serialize);
        assertThrows(
                IllegalStateException.class,
                record::restoreLocalMaterial);
        fixture.destroy();
    }

    @Test
    public void deviceInfoCheckpointSurvivesStorageWithoutAdvancingSetup() {
        Fixture fixture = Fixture.create();
        List<PairingSessionRecord> records = new ArrayList<>();
        byte[] serialized = null;
        AppleNetworkRelayInnerAddresses addresses = null;
        try {
            PairingSessionRecord initial = fixture.createRecord();
            records.add(initial);
            assertFalse(initial.hasExchangedIdsDeviceInfo());
            assertThrows(IllegalStateException.class, initial::markIdsDeviceInfoExchanged);
            byte[] bond = fixture.bondRecord();
            PairingSessionRecord current;
            try { current = initial.withBluetoothBond(bond); }
            finally { wipe(bond); }
            records.add(current);
            current = current.withPreludeNegotiated(NrLinkBluetoothPrelude.LocalRole.INITIATOR);
            records.add(current);
            addresses = fixture.addressesWithRolesSwapped();
            current = current.withClassDEstablished(addresses);
            records.add(current);
            current = current.advanceTo(PairingSessionRecord.DurableState.CLASS_C_ESTABLISHED);
            records.add(current);
            current = current.markIdsAuthenticationAccepted();
            records.add(current);
            current = current.advanceTo(PairingSessionRecord.DurableState.IDS_CONTROL_READY);
            records.add(current);
            long before = current.transitionCounter();
            current = current.markIdsDeviceInfoExchanged();
            records.add(current);
            assertEquals(PairingSessionRecord.DurableState.IDS_CONTROL_READY, current.state());
            assertEquals(before + 1, current.transitionCounter());
            assertThrows(IllegalStateException.class, current::markIdsDeviceInfoExchanged);
            serialized = current.serialize();
            current = PairingSessionRecord.parse(serialized);
            records.add(current);
            assertTrue(current.hasExchangedIdsDeviceInfo());
            assertEquals(PairingSessionRecord.DurableState.IDS_CONTROL_READY, current.state());
            current = current.advanceTo(PairingSessionRecord.DurableState.IDS_DATA_READY);
            records.add(current);
            assertTrue(current.hasExchangedIdsDeviceInfo());
            assertFalse(current.isPairedCommitIntentPersisted());
        } finally {
            records.forEach(PairingSessionRecord::destroy);
            if (addresses != null) addresses.destroy();
            fixture.destroy();
            wipe(serialized);
        }
    }

    @Test
    public void isPairedCommitRequiresDurableWriteAheadIntent() {
        Fixture fixture = Fixture.create();
        PairingSessionRecord initial = null;
        PairingSessionRecord bonded = null;
        PairingSessionRecord negotiated = null;
        PairingSessionRecord established = null;
        AppleNetworkRelayInnerAddresses authoritative = null;
        List<PairingSessionRecord> later = new ArrayList<>();
        try {
            initial = fixture.createRecord();
            byte[] bond = fixture.bondRecord();
            try {
                bonded = initial.withBluetoothBond(bond);
            } finally {
                wipe(bond);
            }
            negotiated =
                    bonded.withPreludeNegotiated(
                            NrLinkBluetoothPrelude
                                    .LocalRole.INITIATOR);
            assertFalse(negotiated.addressesConfirmed());
            authoritative =
                    fixture.addressesWithRolesSwapped();
            established =
                    negotiated.withClassDEstablished(
                            authoritative);
            PairingSessionRecord current =
                    established.advanceTo(
                            PairingSessionRecord.DurableState
                                    .CLASS_C_ESTABLISHED);
            later.add(current);
            current =
                    current.markIdsAuthenticationAccepted();
            later.add(current);
            for (PairingSessionRecord.DurableState state
                    : new PairingSessionRecord.DurableState[]{
                    PairingSessionRecord.DurableState
                            .IDS_CONTROL_READY,
                    PairingSessionRecord.DurableState
                            .IDS_DATA_READY,
                    PairingSessionRecord.DurableState
                            .INITIAL_PROPERTIES_RECEIVED,
                    PairingSessionRecord.DurableState
                            .READY_TO_COMMIT_IS_PAIRED}) {
                current = current.advanceTo(state);
                later.add(current);
            }
            PairingSessionRecord ready = current;
            assertFalse(
                    ready.isPairedCommitIntentPersisted());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ready.advanceTo(
                            PairingSessionRecord.DurableState
                                    .IS_PAIRED_COMMITTED));
            assertThrows(
                    IllegalStateException.class,
                    () -> ready.confirmIsPairedCommit(false));
            assertThrows(
                    IllegalStateException.class,
                    () -> ready.confirmIsPairedCommit(true));
            assertThrows(
                    IllegalStateException.class,
                    () -> ready.prepareIsPairedCommit(false));
            PairingSessionRecord intent =
                    ready.prepareIsPairedCommit(true);
            later.add(intent);
            assertTrue(
                    intent.isPairedCommitIntentPersisted());
            assertEquals(
                    PairingSessionRecord.DurableState
                            .READY_TO_COMMIT_IS_PAIRED,
                    intent.state());

            byte[] serializedIntent =
                    intent.serialize();
            PairingSessionRecord restoredIntent;
            try {
                restoredIntent =
                        PairingSessionRecord.parse(
                                serializedIntent);
            } finally {
                wipe(serializedIntent);
            }
            later.add(restoredIntent);
            assertTrue(
                    restoredIntent.isPairedCommitIntentPersisted());
            assertThrows(
                    IllegalStateException.class,
                    () -> restoredIntent
                            .clearIsPairedCommitIntentForExplicitRetry(
                                    false));

            PairingSessionRecord retryReady =
                    restoredIntent
                            .clearIsPairedCommitIntentForExplicitRetry(
                                    true);
            later.add(retryReady);
            assertFalse(
                    retryReady.isPairedCommitIntentPersisted());
            assertThrows(
                    IllegalStateException.class,
                    () -> retryReady.confirmIsPairedCommit(true));

            PairingSessionRecord committed =
                    restoredIntent.confirmIsPairedCommit(true);
            List<PairingSessionRecord> completionStates =
                    new ArrayList<>();
            try {
                assertEquals(
                        PairingSessionRecord.DurableState
                                .IS_PAIRED_COMMITTED,
                        committed.state());
                assertFalse(
                        committed.isPairedCommitIntentPersisted());
                PairingSessionRecord completion =
                        committed;
                for (PairingSessionRecord.DurableState state
                        : new PairingSessionRecord.DurableState[]{
                        PairingSessionRecord.DurableState
                                .ACTIVATION_CONFIRMED,
                        PairingSessionRecord.DurableState
                                .IS_SETUP_CONFIRMED,
                        PairingSessionRecord.DurableState
                                .PAIRED_SYNC_COMPLETE,
                        PairingSessionRecord.DurableState.SETUP_COMPLETE,
                        PairingSessionRecord.DurableState
                                .CLOCK_VISIBLE_CONFIRMED,
                        PairingSessionRecord.DurableState
                                .OPERATIONAL_HEALTH_CONFIRMED}) {
                    completion =
                            completion.advanceTo(
                                    state);
                    completionStates.add(
                            completion);
                }
                assertEquals(
                        PairingSessionRecord.DurableState
                                .OPERATIONAL_HEALTH_CONFIRMED,
                        completion.state());
                assertFalse(completion.hasObservedSetupEvidence());
                PairingSessionRecord replay = completion.revalidateLegacySetupEvidence();
                PairingSessionRecord observed = null;
                PairingSessionRecord decoded = null;
                byte[] serializedObserved = null;
                try {
                    assertEquals(PairingSessionRecord.DurableState.IS_PAIRED_COMMITTED, replay.state());
                    assertEquals(completion.transitionCounter() + 1, replay.transitionCounter());
                    assertArrayEquals(completion.bluetoothBond(), replay.bluetoothBond());
                    assertEquals(completion.localIdsDeviceUuid(), replay.localIdsDeviceUuid());
                    observed = replay.advanceObservedSetupTo(PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED);
                    assertTrue(observed.hasObservedSetupEvidence());
                    serializedObserved = observed.serialize();
                    decoded = PairingSessionRecord.parse(serializedObserved);
                    assertTrue(decoded.hasObservedSetupEvidence());
                    PairingSessionRecord terminal = decoded;
                    java.util.List<PairingSessionRecord> verifiedStates = new java.util.ArrayList<>();
                    try {
                        for (PairingSessionRecord.DurableState state : new PairingSessionRecord.DurableState[]{
                                PairingSessionRecord.DurableState.IS_SETUP_CONFIRMED,
                                PairingSessionRecord.DurableState.PAIRED_SYNC_COMPLETE,
                                PairingSessionRecord.DurableState.SETUP_COMPLETE,
                                PairingSessionRecord.DurableState.CLOCK_VISIBLE_CONFIRMED,
                                PairingSessionRecord.DurableState.OPERATIONAL_HEALTH_CONFIRMED}) {
                            terminal = terminal.advanceObservedSetupTo(state);
                            verifiedStates.add(terminal);
                        }
                        PairingSessionRecord syncReplay = terminal.redriveInitialSyncCheckpoint();
                        try {
                            assertEquals(PairingSessionRecord.DurableState.ACTIVATION_CONFIRMED, syncReplay.state());
                            assertTrue(syncReplay.hasObservedSetupEvidence());
                            assertArrayEquals(terminal.bluetoothBond(), syncReplay.bluetoothBond());
                            assertEquals(terminal.localIdsDeviceUuid(), syncReplay.localIdsDeviceUuid());
                            assertEquals(terminal.transitionCounter() + 1, syncReplay.transitionCounter());
                        } finally { syncReplay.destroy(); }
                    } finally {
                        for (PairingSessionRecord state : verifiedStates) state.destroy();
                    }
                    PairingSessionRecord verified = decoded;
                    assertThrows(IllegalStateException.class, verified::revalidateLegacySetupEvidence);
                } finally {
                    replay.destroy();
                    if (observed != null) observed.destroy();
                    if (decoded != null) decoded.destroy();
                    wipe(serializedObserved);
                }
            } finally {
                for (PairingSessionRecord completion : completionStates) {
                    completion.destroy();
                }
                committed.destroy();
            }
        } finally {
            for (PairingSessionRecord record : later) {
                record.destroy();
            }
            if (authoritative != null) {
                authoritative.destroy();
            }
            if (established != null) {
                established.destroy();
            }
            if (negotiated != null) {
                negotiated.destroy();
            }
            if (bonded != null) {
                bonded.destroy();
            }
            if (initial != null) {
                initial.destroy();
            }
            fixture.destroy();
        }
    }

    private static void rewriteDigest(byte[] serialized) {
        int digestOffset = serialized.length - 32;
        byte[] digest;
        try {
            java.security.MessageDigest sha =
                    java.security.MessageDigest
                            .getInstance("SHA-256");
            sha.update(serialized, 0, digestOffset);
            digest = sha.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        System.arraycopy(
                digest,
                0,
                serialized,
                digestOffset,
                digest.length);
        wipe(digest);
    }

    private static final class Fixture {
        final SecureRandom random;
        final byte[] generation;
        final BleSecureConnectionsCrypto.LocalOobMaterial localOob;
        final AppleNetworkRelayPairingMaterial localMaterial;
        final AppleNetworkRelayInnerAddresses addresses;
        final ApplePairingNotifyPayloads.LocalBatch peerSource;
        final List<ApplePairingNotifyPayloads.PrivateNotify>
                peerNotifies;
        final ApplePairingNotifyPayloads.PeerBatch peer;

        private Fixture(
                SecureRandom random,
                byte[] generation,
                BleSecureConnectionsCrypto.LocalOobMaterial localOob,
                AppleNetworkRelayPairingMaterial localMaterial,
                AppleNetworkRelayInnerAddresses addresses,
                ApplePairingNotifyPayloads.LocalBatch peerSource,
                List<ApplePairingNotifyPayloads.PrivateNotify>
                        peerNotifies,
                ApplePairingNotifyPayloads.PeerBatch peer) {
            this.random = random;
            this.generation = generation;
            this.localOob = localOob;
            this.localMaterial = localMaterial;
            this.addresses = addresses;
            this.peerSource = peerSource;
            this.peerNotifies = peerNotifies;
            this.peer = peer;
        }

        static Fixture create() {
            SecureRandom random = new SecureRandom();
            byte[] generation = new byte[16];
            random.nextBytes(generation);
            BleSecureConnectionsCrypto.LocalOobMaterial
                    localOob =
                    BleSecureConnectionsCrypto
                            .generateLocalOob(random);
            AppleNetworkRelayPairingMaterial localMaterial =
                    AppleNetworkRelayPairingMaterial.generate(
                            random);
            AppleNetworkRelayInnerAddresses addresses =
                    AppleNetworkRelayInnerAddresses.generate(
                            random);

            BleSecureConnectionsCrypto.LocalOobMaterial
                    peerOob =
                    BleSecureConnectionsCrypto
                            .generateLocalOob(random);
            AppleNetworkRelayPairingMaterial peerMaterial =
                    AppleNetworkRelayPairingMaterial.generate(
                            random);
            AppleNetworkRelayInnerAddresses peerAddresses =
                    AppleNetworkRelayInnerAddresses.generate(
                            random);
            ApplePairingNotifyPayloads.LocalBatch peerSource =
                    ApplePairingNotifyPayloads.createLocalBatch(
                            peerOob,
                            peerMaterial,
                            peerAddresses,
                            "Apple Watch Ultra 2",
                            "23U67",
                            "ids-device");
            List<ApplePairingNotifyPayloads.PrivateNotify> all =
                    peerSource.notifies();
            List<ApplePairingNotifyPayloads.PrivateNotify>
                    peerNotifies =
                    new ArrayList<>(all.subList(0, 10));
            for (int index = 10; index < all.size(); index++) {
                all.get(index).destroy();
            }
            ApplePairingNotifyPayloads.PeerBatch peer =
                    ApplePairingNotifyPayloads.parsePeerBatch(
                            peerNotifies);
            peerAddresses.destroy();
            peerMaterial.destroy();
            peerOob.destroy();
            return new Fixture(
                    random,
                    generation,
                    localOob,
                    localMaterial,
                    addresses,
                    peerSource,
                    peerNotifies,
                    peer);
        }

        PairingSessionRecord createRecord() {
            return PairingSessionRecord.createPreSmp(
                    generation,
                    new byte[]{0, 1, 2, 3},
                    localOob,
                    localMaterial,
                    addresses,
                    peer,
                    "Watch7,5",
                    "n210ap",
                    "00112233-4455-6677-8899-aabbccddeeff",
                    "10213243-5465-7687-98a9-bacbdcedfe0f",
                    "20314253-6475-8697-a8b9-cadbecfd0e1f");
        }

        byte[] bondRecord() {
            return BluetoothBondSecretRecord.encode(
                    0,
                    sequence(1, 6),
                    1,
                    sequence(11, 6),
                    0,
                    sequence(21, 6),
                    sequence(31, 16),
                    sequence(51, 16),
                    sequence(71, 16),
                    16);
        }

        AppleNetworkRelayInnerAddresses
                addressesWithRolesSwapped() {
            byte[] initiatorD = null;
            byte[] responderD = null;
            byte[] initiatorC = null;
            byte[] responderC = null;
            try {
                initiatorD = addresses.responderClassD();
                responderD = addresses.initiatorClassD();
                initiatorC = addresses.responderClassC();
                responderC = addresses.initiatorClassC();
                return AppleNetworkRelayInnerAddresses
                        .fromAuthoritative(
                                initiatorD,
                                responderD,
                                initiatorC,
                                responderC);
            } finally {
                wipe(initiatorD);
                wipe(responderD);
                wipe(initiatorC);
                wipe(responderC);
            }
        }

        void destroy() {
            peer.destroy();
            for (ApplePairingNotifyPayloads.PrivateNotify
                    notify : peerNotifies) {
                notify.destroy();
            }
            peerSource.destroy();
            addresses.destroy();
            localMaterial.destroy();
            localOob.destroy();
            wipe(generation);
        }
    }

    private static byte[] sequence(
            int first,
            int length) {
        byte[] output = new byte[length];
        for (int index = 0; index < output.length; index++) {
            output[index] = (byte) (first + index);
        }
        return output;
    }

    private static BtClNormalLinkHandoff openNormalPipe() {
        BtClNormalLinkHandoff handoff =
                BtClNormalLinkHandoff.begin(
                        0x0b,
                        true);
        byte[] serviceAdded =
                handoff.advertise();
        wipe(
                serviceAdded);
        byte[] createChannel =
                handoff.acceptCommonServices(
                        HciCodec.parseBtCl(
                                0x0b,
                                hex(
                                        "02 03 00 01 02 00")));
        wipe(
                createChannel);
        handoff.acceptChannel(
                HciCodec.parseBtCl(
                        0x0b,
                        hex(
                                "04 05 00 00 02 00 42 00")));
        return handoff;
    }

    private static byte[] hex(
            String text) {
        String compact =
                text.replaceAll(
                        "\\s+",
                        "");
        byte[] output =
                new byte[compact.length() / 2];
        for (int index = 0;
                index < output.length;
                index++) {
            output[index] =
                    (byte) Integer.parseInt(
                            compact.substring(
                                    index * 2,
                                    index * 2 + 2),
                            16);
        }
        return output;
    }

    private static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
