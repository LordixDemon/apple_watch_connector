package dev.applewatchandroid.bridge;

import java.util.UUID;

/** Manager preflight before engine/data acceptance. Observation storage remains independent. */
final class NativeHealthIncomingPolicy {
    enum Outcome { ELIGIBLE, EMPTY, HELD }
    enum Reason { MATCHED, EMPTY_MESSAGE, FOREIGN_BUILD, PROFILE_UNAVAILABLE, FOREIGN_PAIR,
        STALE_EPOCH, UNSUPPORTED_DIRECTION, PROTOCOL_VERSION, RESTORE_INCOMPLETE, IDENTITY_MISMATCH }
    record Context(UUID pair,UUID local,UUID peer,UUID epoch,String build) {
        @Override public String toString() { return "Health manager context (identities withheld)"; }
    }
    record Message(UUID pair,UUID epoch,int messageId,boolean response,
                   NativeHealthSyncCodec.Identity identity,boolean hasChanges,boolean hasStatus) {
        @Override public String toString() { return "Incoming Health message (identities withheld)"; }
    }
    record Result(Outcome outcome,Reason reason,boolean restoreComplete,boolean protocolMatches,boolean identityMatches) {
        boolean eligible() { return outcome==Outcome.ELIGIBLE; }
    }
    private NativeHealthIncomingPolicy() { }

    static Result inspect(Context context,HealthSyncStateStore.State profile,Message message) {
        if(context==null || message==null || context.pair==null || context.local==null || context.peer==null
                || message.pair==null || message.epoch==null || message.identity==null)
            throw new IllegalArgumentException("Missing Health manager preflight context");
        boolean restored=profile!=null && profile.phase()==HealthSyncStateStore.Phase.REMOTE_FINISHED;
        boolean protocol=profile!=null && profile.version()==message.identity.version();
        boolean identity=profile!=null && profile.identity().equals(message.identity);
        Reason held=null;
        if(!NativeHealthTypeCatalog.BUILD.equals(context.build))held=Reason.FOREIGN_BUILD;
        else if(profile==null)held=Reason.PROFILE_UNAVAILABLE;
        else if(!context.pair.equals(message.pair) || !profile.pair().equals(context.pair)
                || !profile.local().equals(context.local) || !profile.peer().equals(context.peer))held=Reason.FOREIGN_PAIR;
        else if(context.epoch==null || !context.epoch.equals(message.epoch))held=Reason.STALE_EPOCH;
        else if(message.response || message.messageId!=2 && message.messageId!=7)held=Reason.UNSUPPORTED_DIRECTION;
        // Native can negotiate older stores. This APK implements exactly17, never invents a legacy store.
        else if(!protocol)held=Reason.PROTOCOL_VERSION;
        else if(!restored)held=Reason.RESTORE_INCOMPLETE;
        if(held!=null)return new Result(Outcome.HELD,held,restored,protocol,identity);
        // Native skips pairing validation and data transaction for an empty request after Restore.
        if(!message.hasChanges && !message.hasStatus)
            return new Result(Outcome.EMPTY,Reason.EMPTY_MESSAGE,restored,protocol,identity);
        if(!identity)return new Result(Outcome.HELD,Reason.IDENTITY_MISMATCH,restored,protocol,false);
        return new Result(Outcome.ELIGIBLE,Reason.MATCHED,restored,protocol,true);
    }
}
