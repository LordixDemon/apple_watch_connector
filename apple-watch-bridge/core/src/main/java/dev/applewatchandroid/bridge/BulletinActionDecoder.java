package dev.applewatchandroid.bridge;

import java.util.Arrays;

/** Read-only native action decoding. A decoded action alone authorizes no Android effect. */
final class BulletinActionDecoder {
    enum Kind { DISMISS, REPLY, LIGHTS_OBSERVED }
    record Action(Kind kind, String publisherId, String recordId, String sectionId,
                  String identifier, String replyText, Boolean played, String replyToken) { }

    static Action decode(int type, boolean response, byte[] payload) {
        if (response || (type != BulletinDistributorCodec.TYPE_DISMISS_ACTION
                && type != BulletinDistributorCodec.TYPE_SUPPLEMENTARY_ACTION
                && type != BulletinDistributorCodec.TYPE_DID_PLAY_LIGHTS_AND_SIRENS)) return null;
        if (payload == null || payload.length > BridgeApplicationEventCodec.MAX_PAYLOAD) {
            throw new IllegalArgumentException("Invalid bulletin action size");
        }
        BulletinDistributorCodec.MessageAndTrailer split = BulletinDistributorCodec.splitTrailer(payload);
        try {
            if (split.trailer == null) throw new IllegalArgumentException("Missing valid bulletin trailer");
            if (type == BulletinDistributorCodec.TYPE_DISMISS_ACTION) {
                BulletinDistributorCodec.DismissActionRequest action = BulletinDistributorCodec.DismissActionRequest.decode(split.messagePayload);
                requireIdentity(action.publisherBulletinId, action.sectionId);
                return new Action(Kind.DISMISS, action.publisherBulletinId, action.recordId, action.sectionId, null, null, null, null);
            }
            if (type == BulletinDistributorCodec.TYPE_SUPPLEMENTARY_ACTION) {
                BulletinDistributorCodec.SupplementaryActionRequest action = BulletinDistributorCodec.SupplementaryActionRequest.decode(split.messagePayload);
                requireIdentity(action.publisherBulletinId, action.sectionId);
                if (action.identifier == null || action.identifier.isBlank() || action.replyText == null) return null;
                return new Action(Kind.REPLY, action.publisherBulletinId, action.recordId, action.sectionId,
                        action.identifier, action.replyText, null, null);
            }
            BulletinDistributorCodec.DidPlayLightsAndSirens action = BulletinDistributorCodec.DidPlayLightsAndSirens.decode(split.messagePayload);
            return new Action(Kind.LIGHTS_OBSERVED, action.publisherMatchId, null, action.sectionId, null, null, action.didPlay, action.replyToken);
        } finally {
            // splitTrailer may return the caller's array when the suffix is invalid.
            if (split.messagePayload != payload) Arrays.fill(split.messagePayload, (byte) 0);
        }
    }

    private static void requireIdentity(String publisher, String section) {
        if (publisher == null || publisher.isBlank() || section == null || section.isBlank()) {
            throw new IllegalArgumentException("Missing bulletin action identity");
        }
    }
}
