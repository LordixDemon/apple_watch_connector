package dev.applewatchandroid.bridge;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.Arrays;

/** Owns Health inbox, identity binding and observation lifetime for one service. */
final class OperationalHealthController implements AutoCloseable {
    private final OperationalSessionAccess host;
    OperationalHealthController(OperationalSessionAccess host) { this.host = host; }
    private final HealthObservationQueue healthObservationQueue=new HealthObservationQueue();

    private record HealthReadScope(HealthContext context,NativeHealthSyncCodec.Identity identity) { }

    private HealthReadScope lastHealthReadScope;

    void handleHealthData(String encoded) {
        byte[] bytes = null;
        try {
            if (!host.connected() || host.epoch() == null || host.pairing() == null
                    || encoded.length() > ((HealthDataEventCodec.MAX_FRAME + 2) / 3) * 4) {
                throw new IllegalArgumentException("Health Data outside operational epoch");
            }
            bytes = Base64.decode(encoded,Base64.NO_WRAP);
            try (var event = HealthDataEventCodec.decode(bytes)) {
                var journal = new HealthInboundJournal(new java.io.File(host.context().getFilesDir(),"health-inbox").toPath(),
                        java.util.UUID.fromString(host.pairing()));
                var outcome = journal.stage(event,host.epoch());
                log("HEALTH DATA inbox: " + outcome + "; encryptedBytes=" + event.encryptedSize()
                        + "; Health samples/anchors not committed; payload logged=false.");
                if (outcome == HealthInboundJournal.Outcome.STAGED_ENCRYPTED
                        || outcome == HealthInboundJournal.Outcome.DUPLICATE) {
                    inspectHealthData(event,null);
                }
            }
        } catch (Exception failure) {
            log("HEALTH DATA inbox rejected; no reply/anchors; payload logged=false.");
        } finally { wipe(bytes); }
    }

    private HealthPeerIdentityStore healthPeerStore() {
        return new HealthPeerIdentityStore(new java.io.File(host.context().getFilesDir(),"health-peer-identity").toPath());
    }

    private HealthSyncStateStore healthSyncStore() {
        return new HealthSyncStateStore(new java.io.File(host.context().getFilesDir(),"health-sync-state").toPath());
    }
    private HealthChangesSessionStore healthChangesStore() {
        return new HealthChangesSessionStore(new java.io.File(host.context().getFilesDir(),"health-changes-state").toPath());
    }
    private HealthRegistryObservationCodec.Observation healthRegistryObservation;
    private java.util.UUID healthPeerEpoch;
    void handleHealthRegistryObservation(String encoded) {
        byte[] frame=null;java.nio.file.Path temporary=null;
        try {
            if(!host.connected() || host.epoch()==null || encoded.length()>(HealthRegistryObservationCodec.MAX_FRAME+2)/3*4)throw new IllegalArgumentException();
            frame=Base64.decode(encoded,Base64.NO_WRAP);
            var observation=HealthRegistryObservationCodec.decode(frame);var owner=healthContext();
            observation.requireContext(owner.pair,host.epoch(),owner.local,owner.peer);
            var directory=new java.io.File(host.context().getFilesDir(),"health-registry-observation").toPath();
            java.nio.file.Files.createDirectories(directory);temporary=java.nio.file.Files.createTempFile(directory,"registry-",".tmp");
            try(var channel=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE)) {
                var buffer=java.nio.ByteBuffer.wrap(frame);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            java.nio.file.Files.move(temporary,directory.resolve(owner.pair+".public"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);temporary=null;
            try(var channel=java.nio.channels.FileChannel.open(directory,java.nio.file.StandardOpenOption.READ)) { channel.force(true); }
            log("HEALTH registry observation retained: product="+observation.product()+" build="+observation.build()
                    +" nativePairingPresent="+(observation.nativePairing()!=null)+"; no Health identity adopted.");
            healthRegistryObservation=observation;
            beginInitialHealthRestore();
        } catch(Exception invalid) { log("HEALTH registry observation rejected; existing identities retained."); }
        finally { wipe(frame);if(temporary!=null)try { java.nio.file.Files.deleteIfExists(temporary); }catch(IOException ignored) { } }
    }
    private record HealthContext(java.util.UUID pair,java.util.UUID local,java.util.UUID peer,String peerBuild) { }
    private HealthContext healthContext(PairingSessionRecord record) {
        OperationalSessionPolicy.requireMatchingActivatedPair(record,host.pairing());
        return new HealthContext(java.util.UUID.fromString(host.pairing()),java.util.UUID.fromString(record.localIdsDeviceUuid()),
                java.util.UUID.fromString(record.peerIdsDeviceId()),record.peerBuildVersion());
    }
    private HealthContext healthContext() throws Exception {
        PairingSessionRecord record=host.identities().readRecord();
        try { return healthContext(record); }finally { record.destroy(); }
    }
    private void beginInitialHealthRestore() {
        byte[] peer=null,plaintext=null,encrypted=null,frame=null;
        HealthContext owner=null;HealthSyncStateStore.State prepared=null;
        try {
            if(host.stopping() || !host.connected() || host.epoch()==null || host.input()==null)return;
            PairingSessionRecord record=host.identities().readRecord();
            try {
                owner=healthContext(record);
                var store=healthSyncStore();
                var observed=healthRegistryObservation;
                if(observed==null || !observed.epoch().equals(host.epoch()) || !host.epoch().equals(healthPeerEpoch)) {
                    log("HEALTH initial Restore held: fresh registry model not available; existing data retained.");
                    return;
                }
                var binding=NativeHealthRegistryBinding.bind(owner.pair,host.epoch(),owner.local,owner.peer,observed,
                        java.util.UUID.fromString(record.nrUuid()),record.peerBuildVersion());
                // A validated persisted peer key is required before even preparing a new native session.
                peer=savedHealthPeerKey();if(peer==null)return;
                var existing=store.initialize(owner.pair,owner.local,owner.peer,binding.registry(),binding.product(),binding.build(),java.util.UUID::randomUUID);
                if(existing.phase()==HealthSyncStateStore.Phase.REMOTE_FINISHED) {
                    beginInitialHealthChanges(owner,existing);return;
                }
                if(existing.epoch()!=null && existing.epoch().equals(host.epoch()))return;
                prepared=store.prepare(owner.pair,owner.local,owner.peer,host.epoch(),SystemClock.elapsedRealtime()+60_000,
                        java.util.UUID.randomUUID(),java.util.UUID.randomUUID());
            } finally { record.destroy(); }
            plaintext=NativeHealthInitialRestoreCodec.encode(prepared);
            encrypted=host.identities().encryptHealthData(plaintext,peer);
            var header=new HealthOutboundIpcCodec.Header(owner.pair,owner.local,owner.peer,prepared.epoch(),prepared.message(),prepared.deadline());
            try(var request=new HealthOutboundIpcCodec.Request(header,encrypted)) { frame=HealthOutboundIpcCodec.encode(request); }
            final byte[] outgoing=frame;final HealthContext context=owner;final HealthSyncStateStore.State state=prepared;
            final BufferedWriter session=host.input();
            host.commands().execute(() -> {
                try {
                    synchronized(session) {
                        if(host.stopping() || !host.connected() || host.input()!=session || !state.epoch().equals(host.epoch())
                                || SystemClock.elapsedRealtime()>=state.deadline())throw new IOException("Health epoch ended");
                        session.write(HealthOutboundIpcCodec.PREFIX);session.write(Base64.encodeToString(outgoing,Base64.NO_WRAP));
                        session.write('\n');session.flush();
                    }
                    log("HEALTH initial Restore submitted: native empty-profile Finished/sequence1; ciphertext only; remote result pending.");
                } catch(Exception failure) { failHealthSend(context,state); }
                finally { wipe(outgoing); }
            });
            frame=null;
            log("HEALTH identities/session durably prepared; separate NR/Health UUID roles; no samples/anchors committed.");
            host.handler().postDelayed(() -> {
                try {
                    var expired=healthSyncStore().expire(context.pair,context.local,context.peer,state.epoch(),state.message(),SystemClock.elapsedRealtime());
                    if(expired.phase()==HealthSyncStateStore.Phase.EXPIRED)log("HEALTH native Restore response deadline expired; identities/inbox retained; no retry in same epoch.");
                } catch(Exception unavailable) { log("HEALTH deadline state unavailable; native completion unconfirmed."); }
            },Math.max(1,state.deadline()-SystemClock.elapsedRealtime()));
        } catch(Exception failure) {
            if(owner!=null && prepared!=null)failHealthSend(owner,prepared);
            log("HEALTH initial Restore unavailable: profile/key/store/epoch mismatch; no identity regeneration or data deletion.");
        } finally { wipe(peer);wipe(plaintext);wipe(encrypted);wipe(frame); }
    }
    private void failHealthSend(HealthContext context,HealthSyncStateStore.State state) {
        try { healthSyncStore().receipt(context.pair,context.local,context.peer,
                new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.UNKNOWN)); }
        catch(Exception failure) { log("HEALTH send state storage unavailable; remote result unknown."); }
    }
    /** Invoked only after fresh registry/key binding and accepted Restore; encrypted inbox precedes inspection. */
    private void beginInitialHealthChanges(HealthContext context,HealthSyncStateStore.State profile) {
        byte[] peer=null,plaintext=null,encrypted=null,frame=null;
        HealthChangesSessionStore.State prepared=null;
        try {
            if(host.stopping() || !host.connected() || host.input()==null || host.epoch()==null
                    || !host.epoch().equals(healthPeerEpoch) || healthRegistryObservation==null
                    || !host.epoch().equals(healthRegistryObservation.epoch()))return;
            var store=healthChangesStore();
            if(store.read(context.pair,context.local,context.peer,profile.identity())!=null)return;
            peer=savedHealthPeerKey();if(peer==null)return;
            prepared=store.prepare(profile,host.epoch(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),
                    System.currentTimeMillis()/1000.0-978307200.0,SystemClock.elapsedRealtime()+60_000);
            plaintext=NativeHealthInitialChangesCodec.encode(prepared);
            encrypted=host.identities().encryptHealthData(plaintext,peer);
            var header=new HealthOutboundIpcCodec.Header(context.pair,context.local,context.peer,prepared.epoch(),prepared.message(),prepared.deadline());
            try(var request=new HealthOutboundIpcCodec.Request(header,encrypted)) { frame=HealthOutboundIpcCodec.encode(request); }
            final byte[] outgoing=frame;final var state=prepared;final BufferedWriter session=host.input();
            host.commands().execute(() -> {
                try {
                    synchronized(session) {
                        if(host.stopping() || !host.connected() || host.input()!=session || !state.epoch().equals(host.epoch())
                                || SystemClock.elapsedRealtime()>=state.deadline())throw new IOException("Health Changes epoch ended");
                        session.write(HealthOutboundIpcCodec.PREFIX);session.write(Base64.encodeToString(outgoing,Base64.NO_WRAP));
                        session.write('\n');session.flush();
                    }
                    log("HEALTH initial Changes submitted: native outgoing Finished2/ChangesRequested4; no acknowledged anchors; native status pending.");
                } catch(Exception failure) { failHealthChangesSend(profile,state); }
                finally { wipe(outgoing); }
            });
            frame=null;
            log("HEALTH Changes session durably prepared; initial pull only; samples/anchors not committed.");
            host.handler().postDelayed(() -> {
                try {
                    var ended=healthChangesStore().end(profile,state.epoch(),state.message(),SystemClock.elapsedRealtime(),false);
                    if(ended!=null && ended.phase()==HealthChangesSessionStore.Phase.EXPIRED)log("HEALTH Changes native status deadline expired; encrypted inbox retained; no automatic resend.");
                } catch(Exception failure) { log("HEALTH Changes deadline state unavailable; result unconfirmed."); }
            },Math.max(1,state.deadline()-SystemClock.elapsedRealtime()));
        } catch(Exception failure) {
            if(prepared!=null)failHealthChangesSend(profile,prepared);
            log("HEALTH initial Changes unavailable; native Restore/identities/inbox retained.");
        } finally { wipe(peer);wipe(plaintext);wipe(encrypted);wipe(frame); }
    }
    private void failHealthChangesSend(HealthSyncStateStore.State profile,HealthChangesSessionStore.State state) {
        try { healthChangesStore().receipt(profile,new BridgeCommandCodec.Status(state.message(),state.epoch(),BridgeCommandCodec.Stage.UNKNOWN)); }
        catch(Exception failure) { log("HEALTH Changes send state unavailable; native outcome unknown."); }
    }
    void handleHealthSendStatus(String encoded) {
        byte[] bytes=null;
        try {
            if(encoded.length()>128)throw new IllegalArgumentException();bytes=Base64.decode(encoded,Base64.NO_WRAP);
            var context=healthContext();var receipt=BridgeCommandCodec.decodeStatus(bytes);
            var state=healthSyncStore().receipt(context.pair,context.local,context.peer,receipt);
            var changes=healthChangesStore().receipt(state,receipt);
            log("HEALTH send receipt="+receipt.stage()+" restore="+state.phase()+" changes="+(changes==null ? "NONE" : changes.phase())
                    +"; transport receipt is not native Health completion.");
        } catch(Exception failure) { log("HEALTH send receipt rejected; state/result unconfirmed."); }
        finally { wipe(bytes); }
    }
    void disconnectHealthSession() {
        if(host.identities()==null || host.epoch()==null || host.pairing()==null)return;
        try {
            var context=healthContext();
            var state=healthSyncStore().disconnected(context.pair,context.local,context.peer,host.epoch());
            if(state!=null && state.phase()==HealthSyncStateStore.Phase.UNKNOWN)log("HEALTH pending session disconnected; identities retained; native outcome unknown.");
            if(state!=null) {
                var changes=healthChangesStore().end(state,host.epoch(),null,SystemClock.elapsedRealtime(),true);
                if(changes!=null && changes.phase()==HealthChangesSessionStore.Phase.UNKNOWN)log("HEALTH Changes disconnected; original ciphertext retained; no automatic resend.");
            }
        } catch(Exception failure) { log("HEALTH disconnect state unavailable; no identity reset."); }
    }
    private void observeHealthRestoreResponse(HealthDataEventCodec.Event event,NativeHealthSyncCodec.Identity identity,
                                             NativeHealthRestoreCodec.Header header) throws Exception {
        var context=healthContext();if(!context.pair.equals(event.pair))throw new IllegalArgumentException("Foreign Health response");
        var state=healthSyncStore().response(context.pair,context.local,context.peer,event.epoch,event.responseTo,
                SystemClock.elapsedRealtime(),identity,header);
        log("HEALTH native Restore response inspected: session="+state.phase()+"; sample/database/anchor acceptance remains separate.");
        if(state.phase()==HealthSyncStateStore.Phase.REMOTE_FINISHED)host.handler().post(this::beginInitialHealthRestore);
    }

    private byte[] savedHealthPeerKey() throws Exception {
        PairingSessionRecord record=host.identities().readRecord();
        try {
            OperationalSessionPolicy.requireMatchingActivatedPair(record,host.pairing());
            return healthPeerStore().read(java.util.UUID.fromString(host.pairing()),
                    java.util.UUID.fromString(record.localIdsDeviceUuid()),java.util.UUID.fromString(record.peerIdsDeviceId()));
        } finally { record.destroy(); }
    }

    void handleHealthPeerIdentity(String encoded) {
        byte[] bytes=null;
        try {
            if(!host.connected() || host.epoch()==null || host.pairing()==null
                    || encoded.length() > ((HealthPeerIdentityCodec.MAX_FRAME+2)/3)*4) {
                throw new IllegalArgumentException("Health peer outside operational epoch");
            }
            bytes=Base64.decode(encoded,Base64.NO_WRAP);
            try(var identity=HealthPeerIdentityCodec.decode(bytes)) {
                PairingSessionRecord record=host.identities().readRecord();
                try {
                    OperationalSessionPolicy.requireMatchingActivatedPair(record,host.pairing());
                    healthPeerStore().store(identity,java.util.UUID.fromString(host.pairing()),host.epoch(),
                            java.util.UUID.fromString(record.localIdsDeviceUuid()),java.util.UUID.fromString(record.peerIdsDeviceId()));
                    healthPeerEpoch=identity.epoch;
                } finally { record.destroy(); }
            }
            log("HEALTH PEER saved: verified public Class-A identity bound to activated pair; values logged=false.");
            replayHealthInbox();
            beginInitialHealthRestore();
        } catch(Exception rejected) {
            log("HEALTH PEER rejected: epoch/pair/installation/key mismatch or storage failure; values logged=false.");
        } finally { wipe(bytes); }
    }

    void replayHealthInbox() {
        byte[] key=null;
        try {
            if(!host.connected() || host.epoch()==null || host.pairing()==null) return;
            key=savedHealthPeerKey();
            if(key==null) { log("HEALTH inbox replay deferred: verified peer key unavailable; encrypted records retained."); return; }
            final byte[] verified=key;
            int count=new HealthInboundJournal(new java.io.File(host.context().getFilesDir(),"health-inbox").toPath(),
                    java.util.UUID.fromString(host.pairing())).replay(event -> inspectHealthData(event,verified));
            log("HEALTH inbox replay inspected="+count+"; records retained; native samples/anchors not committed.");
        } catch(Exception failure) {
            log("HEALTH inbox replay stopped: invalid record/key/storage; encrypted records retained; no reply/anchors.");
        } finally { wipe(key); }
    }

    private void inspectHealthData(HealthDataEventCodec.Event event, byte[] replayKey) {
        byte[] encrypted=event.encrypted(), peer=event.peerKey(), plaintext=null;
        try {
            if(peer==null) peer=replayKey==null ? savedHealthPeerKey() : replayKey.clone();
            if(peer==null) { log("HEALTH NATIVE deferred: verified peer key unavailable; encrypted inbox retained."); return; }
            plaintext=host.identities().decryptHealthData(encrypted,peer);
            try(var nativeFrame=NativeHealthSyncCodec.decodePlaintext(plaintext,event.responseTo!=null)) {
                String envelopeSummary="different native PB schema";
                if(nativeFrame.messageId==1 || nativeFrame.messageId==2 || nativeFrame.messageId==7) {
                    try(var envelope=nativeFrame.envelope()) {
                        envelope.requireIdentity();
                        envelopeSummary="version="+envelope.version+" changeSetBytes="+envelope.size(7)
                                +" statusBytes="+envelope.size(8)+" restorePresent="+envelope.has(9)+" syncIdentityPresent="+envelope.has(11);
                        try(var changes=envelope.changeSet(); var status=envelope.status()) {
                            if(changes!=null) {
                                int objects=0,requiredAnchors=0;
                                for(var change:changes.children(1)) {
                                    objects+=change.byteCount(4); requiredAnchors+=change.children(5).size();
                                    log("HEALTH Changes metadata: objectType="+change.int32(1)+" objects="+change.byteCount(4)
                                            +" dependencies="+change.children(5).size()+" complete="+change.bool(6)
                                            +" speculative="+change.bool(8)+" entityIdentifierPresent="+!change.children(9).isEmpty()
                                            +" currentEpoch="+event.epoch.equals(host.epoch())+"; object values/anchors/identities logged=false.");
                                    inspectHealthObjects(change,event.epoch.equals(host.epoch()));
                                    if(change.byteCount(4)>0)observeHealthObjects(event,new NativeHealthIncomingPolicy.Message(
                                            event.pair,event.epoch,nativeFrame.messageId,nativeFrame.response,
                                            envelope.requireIdentity(),envelope.has(7),envelope.has(8)),change);
                                }
                                envelopeSummary+=" changes="+changes.children(1).size()+" objectDataCount="+objects
                                        +" requiredAnchors="+requiredAnchors+" changeSetStatus="+changes.int32(5);
                            }
                            if(status!=null) {
                                envelopeSummary+=" syncStatus="+status.int32(1)+" reportedAnchors="+status.children(2).size();
                                if(nativeFrame.messageId==2 && nativeFrame.response && NativeHealthInitialChangesCodec.statusOnly(envelope)) {
                                    var context=healthContext();
                                    if(!context.pair.equals(event.pair))throw new IllegalArgumentException("Foreign Health Changes response");
                                    var profile=healthSyncStore().read(context.pair,context.local,context.peer);
                                    if(profile!=null) {
                                        var observed=healthChangesStore().response(profile,event.epoch,event.responseTo,SystemClock.elapsedRealtime(),envelope.requireIdentity(),status);
                                        envelopeSummary+=" initialChanges="+(observed==null ? "NONE" : observed.phase());
                                    }
                                }
                            }
                        }
                        try(var restore=envelope.restore()) {
                            if(restore!=null) {
                                var header=NativeHealthRestoreCodec.requireHeader(restore);
                                envelopeSummary+=" restoreStatus="+header.status()+" obliteratedIdentities="+header.obliterated().size();
                                if(nativeFrame.messageId==1 && nativeFrame.response) {
                                    observeHealthRestoreResponse(event,envelope.requireIdentity(),header);
                                }
                            }
                        }
                    }
                }
                if(nativeFrame.messageId==3 || nativeFrame.messageId==4 && !nativeFrame.response) {
                    try(var authorization=nativeFrame.authorization()) {
                        byte[] app=authorization.bytes(1), request=authorization.bytes(2);
                        try {
                            envelopeSummary+=" authorizationSchema="+authorization.schema
                                    +" appBytes="+(app==null ? -1 : app.length)
                                    +" requestIdBytes="+(request==null ? -1 : request.length);
                            if(authorization.schema==NativeHealthChangesCodec.Schema.AUTHORIZATION_REQUEST) {
                                envelopeSummary+=" readTypes="+authorization.int64s(10).size()
                                        +" writeTypes="+authorization.int64s(11).size();
                            }
                        } finally { wipe(app); wipe(request); }
                    }
                }
                log("HEALTH NATIVE verified sender/decrypted: id="+nativeFrame.messageId
                        +" name="+NativeHealthSyncCodec.messageName(nativeFrame.messageId)
                        +" response="+nativeFrame.response+" "+envelopeSummary
                        +"; samples/anchors not committed; plaintext/identities logged=false.");
            }
        } catch(Exception unsupportedOrInvalid) {
            log("HEALTH NATIVE not decoded: encrypted inbox retained; unsupported/invalid/key-unavailable; no reply/anchors.");
        } finally { wipe(encrypted); wipe(peer); wipe(plaintext); }
    }

    private void observeHealthObjects(HealthDataEventCodec.Event event,NativeHealthIncomingPolicy.Message message,
                                      NativeHealthChangesCodec.Node change) {
        byte[] bytes=change.original();java.util.UUID pair=event.pair,epoch=event.epoch;
        try {
            if(!healthObservationQueue.submit(bytes,owned-> {
                try(var node=NativeHealthChangesCodec.decode(NativeHealthChangesCodec.Schema.CHANGE,owned)) {
                    persistHealthObjects(pair,epoch,message,node);
                } catch(IllegalArgumentException invalid) { log("HEALTH observation queue rejected record; encrypted inbox retained; no ACK/anchors."); }
            }))log("HEALTH observation queue full/closed; encrypted inbox retained; no ACK/anchors.");
        } finally { wipe(bytes); }
    }
    private void persistHealthObjects(java.util.UUID pair,java.util.UUID epoch,NativeHealthIncomingPolicy.Message message,
                                      NativeHealthChangesCodec.Node change) {
        try {
            var context=healthContext();
            if(!context.pair.equals(pair))throw new IllegalArgumentException("Foreign Health observation");
            var identity=message.identity();
            var database=new HealthObservationDatabase(host.context());
            var result=database.observe(context.pair,context.local,context.peer,identity,change);
            var profile=healthSyncStore().read(context.pair,context.local,context.peer);
            var preflight=NativeHealthIncomingPolicy.inspect(new NativeHealthIncomingPolicy.Context(
                    context.pair,context.local,context.peer,host.epoch(),context.peerBuild),profile,message);
            log("HEALTH manager preflight: outcome="+preflight.outcome()+" reason="+preflight.reason()
                    +" restoreComplete="+preflight.restoreComplete()+" protocolMatches="+preflight.protocolMatches()
                    +" identityMatches="+preflight.identityMatches()
                    +"; observations separate; no data/anchors/response accepted; identities logged=false.");
            // There is no accepted-anchor/sequence store yet. Never project reported anchors as local state.
            var plan=preflight.eligible() ? NativeHealthReceivePolicy.prepare(change,context.peerBuild,null,entity->null) : null;
            var effect=plan==null ? null : plan.afterDataApplied();
            if(plan!=null)log("HEALTH receive controls: outcome="+plan.outcome()+" reason="+plan.reason()
                    +" nativeError="+(plan.nativeError()==null ? "none" : plan.nativeError())
                    +" versionRangePresent="+(plan.version()!=null && plan.version().present())
                    +" sequencePresent="+(change.int64(7)!=null)
                    +" wouldUpdateReceived="+(effect!=null && effect.receivedAnchor()!=null)
                    +" wouldUpdateValidated="+(effect!=null && effect.validatedAnchor()!=null)
                    +" currentEpoch="+epoch.equals(host.epoch())
                    +"; control plan only; entity data/anchors/sequence not accepted; values/identities logged=false.");
            if(plan!=null && plan.outcome()==NativeHealthReceivePolicy.Outcome.REQUIRES_DATA_TRANSACTION
                    && Boolean.TRUE.equals(change.bool(8))
                    && (plan.entity()==NativeHealthReceivePolicy.Entity.DEFAULTS || plan.entity()==NativeHealthReceivePolicy.Entity.PROTECTED_DEFAULTS)) {
                try {
                    var defaults=new HealthDefaultsDatabase(host.context());
                    var merged=defaults.mirror(context.pair,context.local,context.peer,identity,context.peerBuild,change,()-> {
                        if(!host.connected() || !epoch.equals(host.epoch()) || !context.equals(healthContext()))return false;
                        var current=healthSyncStore().read(context.pair,context.local,context.peer);
                        return NativeHealthIncomingPolicy.inspect(new NativeHealthIncomingPolicy.Context(
                                context.pair,context.local,context.peer,host.epoch(),context.peerBuild),current,message).eligible();
                    });
                    var read=defaults.visit(context.pair,context.local,context.peer,identity,context.peerBuild,record->{ });
                    log("HEALTH defaults mirror: replaced="+merged.replaced()+" retained="+merged.retained()
                            +" records="+read.records()+" doubles="+read.doubles()+" integers="+read.integers()
                            +" strings="+read.strings()+" bytes="+read.bytes()+" tombstones="+read.tombstones()
                            +"; authenticated private values only; native receipt/anchors/sequence/grants not accepted; values/identities logged=false.");
                } catch(Exception held) {
                    log("HEALTH defaults mirror held/rolled back; observations retained; no native receipt/anchors/sequence; values/identities logged=false.");
                }
            }
            log("HEALTH encrypted observations: inserted="+result.inserted()+" duplicate="+result.duplicate()
                    +" uniqueQuantity="+result.uniqueQuantity()+" uniqueCategory="+result.uniqueCategory()
                    +" sampleVariants="+result.sampleVariants()+" totalVariants="+result.variants()
                    +" currentEpoch="+epoch.equals(host.epoch())
                    +"; observed only; native data/anchors/grants not accepted; values/UUIDs logged=false.");
            var readScope=new HealthReadScope(context,identity);
            if(result.inserted()>0 || !readScope.equals(lastHealthReadScope)) {
                try {
                    var read=database.readSummary(context.pair,context.local,context.peer,identity,context.peerBuild);
                    log("HEALTH authenticated query: records="+read.records()+" quantityDefinitions="+read.quantityDefinitions()
                            +" categoryDefinitions="+read.categoryDefinitions()+" canonicalUnits="+read.canonicalUnits()
                            +" finiteCanonicalValues="+read.canonicalValues()+" finiteOriginalValues="+read.originalValues()
                            +" originalUnits="+read.originalUnits()+" categoryValues="+read.categoryValues()
                            +" unrecognizedSamples="+read.unrecognizedSamples()
                            +" pairingBuildMatchesSchema="+NativeHealthTypeCatalog.BUILD.equals(context.peerBuild)
                            +"; original observations only; units not converted; values/UUIDs logged=false; no grants/anchors.");
                    if(host.context().getDatabasePath("health-defaults-mirror-v1.db").isFile()) {
                        var defaults=new HealthDefaultsDatabase(host.context()).visit(context.pair,context.local,context.peer,
                                identity,context.peerBuild,record->{ });
                        log("HEALTH authenticated defaults query: records="+defaults.records()+" doubles="+defaults.doubles()
                                +" integers="+defaults.integers()+" strings="+defaults.strings()+" bytes="+defaults.bytes()
                                +" tombstones="+defaults.tombstones()
                                +"; private mirror only; native receipt/anchors/sequence/grants not accepted; values/identities logged=false.");
                    }
                    lastHealthReadScope=readScope;
                } catch(Exception invalidRead) {
                    log("HEALTH authenticated query held; errorType="+invalidRead.getClass().getSimpleName()
                            +"; committed original observations retained; no values/ACK/anchors.");
                }
            }
        } catch(Exception unavailableOrUnsupported) {
            StackTraceElement[] trace=unavailableOrUnsupported.getStackTrace();
            String location=trace.length==0 ? "unknown" : trace[0].getClassName()+"."+trace[0].getMethodName()+":"+trace[0].getLineNumber();
            log("HEALTH encrypted observations held; errorType="+unavailableOrUnsupported.getClass().getSimpleName()
                    +" location="+location+"; original inbox retained; no error values/ACK/anchors.");
        }
    }

    private void inspectHealthObjects(NativeHealthChangesCodec.Node change,boolean currentEpoch) {
        var schema=NativeHealthObjectCodec.schemaFor(change);
        if(schema==null) {
            log("HEALTH Objects unsupported entity; encrypted inbox retained; no fallback/reply/anchors.");
            return;
        }
        int decoded=0,invalid=0,categories=0,quantities=0,deleted=0,complete=0,opaque=0,unknown=0;
        for(int i=0;i<change.byteCount(4);i++) {
            byte[] data=change.bytesAt(4,i);
            try(var object=NativeHealthChangesCodec.decode(schema,data)) {
                var counts=NativeHealthObjectCodec.counts(object);
                decoded++;categories+=counts.categorySamples();quantities+=counts.quantitySamples();
                deleted+=counts.deletedSamples();complete+=counts.completeSampleHeaders();
                opaque+=counts.opaqueChildren();unknown+=counts.unknownFields();
                if(schema==NativeHealthChangesCodec.Schema.DOMAIN_DICTIONARY) {
                    var ids=change.children(9);
                    boolean protectedValues=ids.isEmpty() ? Integer.valueOf(17).equals(change.int32(1))
                            : Long.valueOf(17).equals(ids.get(0).int64(2));
                    int missingKeys=0,missingDates=0,nonfiniteDates=0;
                    for(var entry:object.children(3)) {
                        if(entry.string(1)==null)missingKeys++;
                        Double date=entry.doubleValue(2);
                        if(date==null)missingDates++;else if(!Double.isFinite(date))nonfiniteDates++;
                    }
                    log("HEALTH defaults shape: protected="+protectedValues
                            +" categoryMatches="+Long.valueOf(protectedValues ? 105 : 1).equals(object.int64(1))
                            +" domainPresent="+(object.string(2)!=null)+" pairs="+object.children(3).size()
                            +" missingKeys="+missingKeys+" missingDates="+missingDates+" nonfiniteDates="+nonfiniteDates
                            +" unknownFields="+object.unknownFieldsDeep()+" currentEpoch="+currentEpoch
                            +"; structural read only; values/identities logged=false.");
                }
            } catch(IllegalArgumentException unsupportedOrInvalid) { invalid++; }
            finally { wipe(data); }
        }
        log("HEALTH Objects read-only: schema="+schema+" decoded="+decoded+" invalid="+invalid
                +" categoryRecords="+categories+" quantityRecords="+quantities+" deletionRecords="+deleted
                +" completeHeaders="+complete+" opaqueChildren="+opaque+" unknownFields="+unknown
                +" currentEpoch="+currentEpoch+"; values/identities logged=false; database/anchors not committed.");
    }

    void disconnect() {
        disconnectHealthSession();
        healthRegistryObservation = null;
        healthPeerEpoch = null;
    }
    @Override public void close() { healthObservationQueue.close(); }
    private void log(String line) { host.log(line); }
    private static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
