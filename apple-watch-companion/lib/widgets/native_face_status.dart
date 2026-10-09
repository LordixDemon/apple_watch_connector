import '../controllers/native_face_controller.dart';
import '../l10n/strings.dart';

String nativeFaceStatus(NativeFaceResult result, {bool refresh = false}) =>
    switch (result) {
      NativeFaceResult.idle => '',
      NativeFaceResult.waiting =>
        refresh
            ? Strings.current.waitingForTheWatchFaceCollection
            : Strings.current.nativeFaceWaiting,
      NativeFaceResult.applied =>
        refresh
            ? Strings.current.collectionReceivedFromTheWatch
            : Strings.current.nativeFaceChangesConfirmed,
      NativeFaceResult.rejected =>
        refresh
            ? Strings.current.requestNotSentCheckTheConnection
            : Strings.current.nativeFaceRejected,
      NativeFaceResult.unknown =>
        refresh
            ? Strings.current.newCollectionNotReceivedYetRefreshAgain
            : Strings.current.nativeFaceUnknown,
      NativeFaceResult.connectionChanged =>
        Strings.current.connectionChangedRefreshAgain,
      NativeFaceResult.conflict => Strings.current.nativeFaceConflict,
    };
