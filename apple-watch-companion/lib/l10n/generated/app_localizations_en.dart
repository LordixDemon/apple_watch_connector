// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get bluetoothAdapter => 'Bluetooth Adapter';

  @override
  String get bluetoothController => 'Bluetooth controller';

  @override
  String get refreshBluetoothAdapters => 'Refresh Adapters';

  @override
  String get bluetoothAdaptersLoading => 'Looking for Bluetooth adapters…';

  @override
  String get bluetoothAdaptersEmpty =>
      'No Bluetooth adapters found. Connect an adapter and refresh the list.';

  @override
  String get bluetoothAdapterChoiceHint =>
      'Choose an adapter for your Watch. Your choice is saved for future connections.';

  @override
  String get bluetoothAdapterDisconnectHint =>
      'Disconnect your Watch before changing the Bluetooth adapter.';

  @override
  String get bluetoothAdapterMissing => 'Saved adapter unavailable';

  @override
  String get bluetoothAdapterMissingHint =>
      'Reconnect your saved adapter or choose another one. Your Watch pairing is preserved.';

  @override
  String get bluetoothAdapterDiscoveryFailed =>
      'Could not read Bluetooth adapters. Check that BlueZ is running, then refresh the list.';

  @override
  String get bluetoothAdapterSaveFailed =>
      'Could not read or save the adapter preference. Check access to the application state folder, then select an adapter again.';

  @override
  String get bluetoothAdapterSaved => 'Bluetooth adapter saved.';

  @override
  String get opticalScanTitle => 'Pair Apple Watch';

  @override
  String get opticalPairWithCamera => 'Pair with Camera';

  @override
  String get opticalScanHint =>
      'Keep the pairing animation inside the frame, then tap Scan. Hold your phone steady while it reads the code.';

  @override
  String get opticalScan => 'Scan';

  @override
  String get opticalNoMatch =>
      'No pairing animation found. Point the camera at your Watch and try again.';

  @override
  String get opticalRecognizedTitle => 'Apple Watch Found';

  @override
  String opticalRecognizedDetail(String name) {
    return 'Pairing code recognized for $name. Bluetooth authentication is the next step.';
  }

  @override
  String opticalReplaceDetail(String name) {
    return 'Code recognized for $name. Your Watch must be waiting for a new pair. The previous encrypted pair will be backed up before replacement.';
  }

  @override
  String get opticalReplacePair => 'Replace Saved Pair';

  @override
  String get opticalStartPairing => 'Pair Watch';

  @override
  String opticalAnalyzedFrames(int count) {
    return '$count frames analyzed';
  }

  @override
  String get opticalPairingLab => 'Optical Pairing Lab';

  @override
  String get opticalCaptureHint =>
      'Keep the pairing animation inside the frame. Capture a short sample to test the optical decoder. Recognition is not yet enabled. The sample is deleted when you leave this screen.';

  @override
  String get opticalCameraError =>
      'Camera capture failed. Check camera permission and try again.';

  @override
  String opticalFrames(int count) {
    return '$count frames captured';
  }

  @override
  String get opticalCapture => 'Capture Sample';

  @override
  String get opticalRetry => 'Try Again';

  @override
  String get opticalStopCapture => 'Stop Capture';

  @override
  String get backendFeatures => 'Available Features';

  @override
  String get backendFeaturesHint =>
      'Availability describes this backend\'s implemented services. A connection does not guarantee every watchOS feature, device or firmware version is supported.';

  @override
  String get backendFeaturesUnknown =>
      'Feature capabilities have not been received from the connection service yet.';

  @override
  String get featureNotSupported => 'Not supported yet';

  @override
  String get phoneFindFeature => 'Find Phone';

  @override
  String get ok => 'OK';

  @override
  String get digitalCrownTimeTravel => 'DIGITAL CROWN / TIME TRAVEL';

  @override
  String get rightWrist => 'Right wrist';

  @override
  String get rotateScreen180 => 'Rotate screen 180°';

  @override
  String get message24Hourtime => '24-hour time';

  @override
  String get readyToConnect => 'Ready to connect';

  @override
  String get workout => 'Workout';

  @override
  String get trackActivityHeartRatePowerZonesAndGpsRoutes =>
      'Track activity, heart rate, power zones, and GPS routes.';

  @override
  String get activity => 'Activity';

  @override
  String get moveExerciseAndStandRings => 'Move, Exercise, and Stand rings.';

  @override
  String get heartRate => 'Heart Rate';

  @override
  String get heartRateMeasurementsAndAlerts =>
      'Heart rate measurements and alerts.';

  @override
  String get compassWaypoints => 'Compass & Waypoints';

  @override
  String get elevationInclineCoordinatesAndBacktrackRoutes =>
      'Elevation, incline, coordinates, and Backtrack routes.';

  @override
  String get music => 'Music';

  @override
  String get streamAudioAndControlPlayback =>
      'Stream audio and control playback.';

  @override
  String get messages => 'Messages';

  @override
  String get sendAndReceiveMessagesUseDictationAndQuickReplies =>
      'Send and receive messages, use dictation and quick replies.';

  @override
  String get phone => 'Phone';

  @override
  String get callsOverBluetoothOrCellular =>
      'Calls over Bluetooth or cellular.';

  @override
  String get weather => 'Weather';

  @override
  String get forecastsUvIndexPrecipitationAndWind =>
      'Forecasts, UV index, precipitation, and wind.';

  @override
  String get maps => 'Maps';

  @override
  String get turnByTurnDirectionsWithHapticFeedback =>
      'Turn-by-turn directions with haptic feedback.';

  @override
  String get noise => 'Noise';

  @override
  String get monitorAmbientNoiseLevelsInDecibels =>
      'Monitor ambient noise levels in decibels.';

  @override
  String get naturalTitanium => 'Natural Titanium';

  @override
  String get message49Mm => '49 mm';

  @override
  String get battery => 'Battery';

  @override
  String get message72Bpm => '72 bpm';

  @override
  String get message21Sunny => '+21° Sunny';

  @override
  String get compass => 'Compass';

  @override
  String get message124Se182M => '124° SE • 182 m';

  @override
  String get outdoorRun => 'Outdoor Run';

  @override
  String get nowPlaying => 'Now Playing';

  @override
  String get calendar => 'Calendar';

  @override
  String get meetingAt1930 => 'Meeting at 19:30';

  @override
  String get custom => 'Custom';

  @override
  String get imported => 'Imported';

  @override
  String gb(String value0) {
    return '$value0 GB';
  }

  @override
  String mb(String value0) {
    return '$value0 MB';
  }

  @override
  String kb(String value0) {
    return '$value0 KB';
  }

  @override
  String b(String value0) {
    return '$value0 B';
  }

  @override
  String get pointTheCameraAtYourAppleWatchScreen =>
      'Point the camera at your Apple Watch screen';

  @override
  String get enterThe6DigitCodeShownOnYourWatch =>
      'Enter the 6-digit code shown on your watch';

  @override
  String get waitingForBridgeConfirmation => 'Waiting for Bridge confirmation…';

  @override
  String get setupConfirmedByTheWatch => 'Setup confirmed by the watch';

  @override
  String get setupIsNotConfirmedYetAppleWatchBridgeIs =>
      'Setup is not confirmed yet. Apple Watch Bridge is still setting up the watch.';

  @override
  String get noSetupConfirmationFromBridge =>
      'No setup confirmation from Bridge';

  @override
  String get discover => 'Discover';

  @override
  String get newInWatchos11 => 'WATCH COMPANION';

  @override
  String get appleWatchUltra2OnAndroid => 'Apple Watch Companion';

  @override
  String get syncNotificationsCallsWorkoutsAndWatchFacesWithYour =>
      'Connect your Apple Watch using your phone or desktop. See Available Features in My Watch for the services implemented by your connection backend.';

  @override
  String get guidesTips => 'GUIDES & TIPS';

  @override
  String get gesturesControls => 'Gestures & Controls';

  @override
  String get doubleTapDigitalCrownAndActionButton =>
      'Double Tap, Digital Crown, and Action Button';

  @override
  String get healthWorkouts => 'Health & Workouts';

  @override
  String get setUpHeartRateZonesGpsRoutesAndActivity =>
      'Set up heart rate zones, GPS routes, and Activity rings';

  @override
  String get compassBacktrack => 'Compass & Backtrack';

  @override
  String get navigateWithoutAnInternetConnection =>
      'Navigate without an internet connection';

  @override
  String get notificationMirroring => 'Notification Mirroring';

  @override
  String get setUpSmsCallsAndMessagingApps =>
      'Set up SMS, calls, and messaging apps';

  @override
  String get resources => 'RESOURCES';

  @override
  String get appleWatchUserGuide => 'Apple Watch User Guide';

  @override
  String get halSupportDiagnostics => 'HAL Support & Diagnostics';

  @override
  String get appleWatchUltra2Controls => 'Apple Watch Ultra 2 Controls';

  @override
  String get useDoubleTapTheDigitalCrownAndTheOrange =>
      'Use Double Tap, the Digital Crown, and the orange Action Button.';

  @override
  String get essentialGestures => 'ESSENTIAL GESTURES';

  @override
  String get doubleTap => 'Double Tap';

  @override
  String get tapYourIndexFingerAndThumbTogetherTwiceTo =>
      'Tap your index finger and thumb together twice to answer a call, stop a timer, or scroll the Smart Stack.';

  @override
  String get turnToScrollThroughListsAndZoomOrPress =>
      'Turn to scroll through lists and zoom, or press to return to the watch face.';

  @override
  String get orangeActionButton => 'Orange Action Button';

  @override
  String get startAWorkoutMarkAWaypointOrHoldTo =>
      'Start a workout, mark a waypoint, or hold to activate the 86 dB siren.';

  @override
  String get waterLock => 'Water Lock';

  @override
  String get preventsAccidentalTouchesInWaterUseTheDigitalCrown =>
      'Prevents accidental touches in water. Use the Digital Crown to unlock the screen and eject water.';

  @override
  String get faceGallery => 'Face Gallery';

  @override
  String get save => 'SAVE';

  @override
  String get reset => 'Reset';

  @override
  String get colorPalette => 'COLOR PALETTE';

  @override
  String get nightMode => 'Night Mode';

  @override
  String get bezelStyle => 'BEZEL STYLE';

  @override
  String get elevationIncline => 'Elevation / Incline';

  @override
  String get seconds => 'Seconds';

  @override
  String get noBezel => 'No Bezel';

  @override
  String get complicationsTapToChange => 'COMPLICATIONS (TAP TO CHANGE)';

  @override
  String get saveLayoutOnPhone => 'Save Layout on Phone';

  @override
  String get exportWatchfacePackage => 'Export .watchface Package';

  @override
  String chooseAComplicationFor(String value0) {
    return 'Choose a complication for $value0';
  }

  @override
  String get cancel => 'Cancel';

  @override
  String get watchfaceGeneratedSuccessfully =>
      '.watchface generated successfully';

  @override
  String createdAnAppleWatchPackageBytes(String value0) {
    return 'Created an Apple Watch package ($value0 bytes). ';
  }

  @override
  String get includesFacePlistMetadataPlistAndSlotConfiguration =>
      'Includes Face.plist, metadata.plist, and slot configuration.';

  @override
  String get topSubdial => 'Top Subdial';

  @override
  String get topLeft => 'Top Left';

  @override
  String get topRight => 'Top Right';

  @override
  String get center => 'Center';

  @override
  String get bottomLeft => 'Bottom Left';

  @override
  String get bottomRight => 'Bottom Right';

  @override
  String get bottomSubdial => 'Bottom Subdial';

  @override
  String get bezel => 'Bezel';

  @override
  String get import => 'Import';

  @override
  String get onlineGallery => 'Online Gallery';

  @override
  String get downloadedFromTheAppleWatchFaceCatalogServer =>
      'Downloaded from the Apple Watch Face catalog server.';

  @override
  String get importedWayfinder => 'Imported Wayfinder';

  @override
  String get importAppleWatchFace => 'Import Apple Watch Face';

  @override
  String get loadAWatchfacePackageZipWithFacePlist =>
      'Load a .watchface package (ZIP with Face.plist)';

  @override
  String get watchFaceImported => 'Watch face imported!';

  @override
  String layoutSavedOnYourPhone(String value0) {
    return 'Layout “$value0” saved on your phone.';
  }

  @override
  String get importSampleWatchface => 'Import Sample .watchface';

  @override
  String get myWatch => 'My Watch';

  @override
  String get about => 'About';

  @override
  String get general => 'General';

  @override
  String get name => 'Name';

  @override
  String get model => 'Model';

  @override
  String get modelNumber => 'Model Number';

  @override
  String get watchCase => 'Case';

  @override
  String get watchosVersion => 'watchOS Version';

  @override
  String get serialNumber => 'Serial Number';

  @override
  String get networkAddresses => 'Network Addresses';

  @override
  String get wiFiAddress => 'Wi-Fi Address';

  @override
  String get storage => 'Storage';

  @override
  String get available => 'Available';

  @override
  String get songs => 'Songs';

  @override
  String get photos => 'Photos';

  @override
  String get userApps => 'User Apps';

  @override
  String get actionButton => 'Action Button';

  @override
  String get quickAccessToWorkoutsWaypointsTheStopwatchAndThe =>
      'Quick access to workouts, waypoints, the stopwatch, and the safety siren.';

  @override
  String get action => 'ACTION';

  @override
  String get stopwatch => 'Stopwatch';

  @override
  String get compassWaypoint => 'Compass Waypoint';

  @override
  String get backtrack => 'Backtrack';

  @override
  String get diveDepth => 'Dive & Depth';

  @override
  String get flashlight => 'Flashlight';

  @override
  String get safetySiren => 'SAFETY SIREN';

  @override
  String get holdTheActionButtonOnAppleWatchUltraTo =>
      'Hold the Action Button on Apple Watch Ultra to play an 86-decibel alarm audible from up to 180 meters away.';

  @override
  String get holdForSiren => 'Hold for Siren';

  @override
  String version(String value0, String value1) {
    return '$value0 • Version $value1';
  }

  @override
  String get showAppOnAppleWatch => 'Show App on Apple Watch';

  @override
  String get displayBrightness => 'Display & Brightness';

  @override
  String get brightness => 'BRIGHTNESS';

  @override
  String get textSize => 'TEXT SIZE';

  @override
  String get boldText => 'Bold Text';

  @override
  String get keepTheWatchFaceAndAppInformationVisibleWhen =>
      'Keep the watch face and app information visible when your wrist is down.';

  @override
  String get alwaysOn => 'Always On';

  @override
  String get wristRaise => 'WRIST RAISE';

  @override
  String get wakeOnWristRaise => 'Wake on Wrist Raise';

  @override
  String get wakeDuration => 'Wake Duration';

  @override
  String get message70Seconds => '70 seconds';

  @override
  String get message15Seconds => '15 seconds';

  @override
  String get screenWakeDurationOnTap => 'Screen Wake Duration on Tap';

  @override
  String get emergencySos => 'Emergency SOS';

  @override
  String get fallDetection => 'FALL DETECTION';

  @override
  String get ifAppleWatchDetectsAHardFallAndYou =>
      'If Apple Watch detects a hard fall and you remain immobile for about a minute, it gives a haptic alert, sounds an alarm, and contacts emergency services.';

  @override
  String get fallDetection2 => 'Fall Detection';

  @override
  String get crashDetection => 'CRASH DETECTION';

  @override
  String get afterASevereCarCrashAppleWatchShowsThe =>
      'After a severe car crash, Apple Watch shows the emergency call slider and automatically calls emergency services after 20 seconds.';

  @override
  String get crashDetection2 => 'Crash Detection';

  @override
  String get medicalInformation => 'MEDICAL INFORMATION';

  @override
  String get setUpMedicalId => 'Set Up Medical ID';

  @override
  String get softwareUpdate => 'Software Update';

  @override
  String get orientationTime => 'Orientation & Time';

  @override
  String get leftWrist => 'Left wrist';

  @override
  String get notReceived => 'Not received';

  @override
  String get backgroundAppRefresh => 'Background App Refresh';

  @override
  String get on => 'On';

  @override
  String get off => 'Off';

  @override
  String get connectivity => 'CONNECTIVITY';

  @override
  String get airplaneMode => 'Airplane Mode';

  @override
  String get resetUnpair => 'Reset & Unpair';

  @override
  String get unpairAppleWatch => 'Unpair Apple Watch';

  @override
  String get allDataWatchFacesAndSettingsWillBeErased =>
      'All data, watch faces, and settings will be erased from the device.';

  @override
  String get unpair => 'Unpair';

  @override
  String get activityHealth => 'Activity & Health';

  @override
  String get noHealthDataReceivedFromTheWatchYet =>
      'No health data received from the watch yet.';

  @override
  String get watchMeasurements => 'WATCH MEASUREMENTS';

  @override
  String get bpm => 'bpm';

  @override
  String get steps => 'Steps';

  @override
  String km(String value0) {
    return '$value0 km';
  }

  @override
  String get move => 'Move';

  @override
  String kcal(String value0, String value1) {
    return '$value0 / $value1 kcal';
  }

  @override
  String get exercise => 'Exercise';

  @override
  String min(String value0, String value1) {
    return '$value0 / $value1 min';
  }

  @override
  String get stand => 'Stand';

  @override
  String hr(String value0, String value1) {
    return '$value0 / $value1 hr';
  }

  @override
  String get activityRings => 'ACTIVITY RINGS';

  @override
  String get standReminders => 'Stand Reminders';

  @override
  String get remindsYouToStandAndMove10MinutesBefore =>
      'Reminds you to stand and move 10 minutes before the end of each hour.';

  @override
  String get dailyCoaching => 'Daily Coaching';

  @override
  String get goalCompletions => 'Goal Completions';

  @override
  String get heartBreathing => 'HEART & BREATHING';

  @override
  String get highHeartRateNotifications => 'High Heart Rate Notifications';

  @override
  String bpm2(String value0) {
    return '> $value0 bpm';
  }

  @override
  String get lowHeartRateNotifications => 'Low Heart Rate Notifications';

  @override
  String bpm3(String value0) {
    return '< $value0 bpm';
  }

  @override
  String get bloodOxygen => 'Blood Oxygen';

  @override
  String get sleepTracking => 'Sleep Tracking';

  @override
  String get allWatches => 'All Watches';

  @override
  String get connected => 'Connected';

  @override
  String get disconnected => 'Disconnected';

  @override
  String get soundsHaptics => 'Sounds & Haptics';

  @override
  String get notifications => 'Notifications';

  @override
  String get passcode => 'Passcode';

  @override
  String get installedOnAppleWatch => 'INSTALLED ON APPLE WATCH';

  @override
  String get installed => 'Installed';

  @override
  String get hidden => 'Hidden';

  @override
  String get manageConnectedWatches => 'Manage connected watches';

  @override
  String get addAppleWatch => 'Add Apple Watch';

  @override
  String get notificationIndicator => 'Notification Indicator';

  @override
  String get showsARedDotAtTheTopOfThe =>
      'Shows a red dot at the top of the watch face when you have unread notifications.';

  @override
  String get notificationPrivacy => 'Notification Privacy';

  @override
  String get hideNotificationDetailsUntilYouTapTheScreen =>
      'Hide notification details until you tap the screen.';

  @override
  String get notificationSummary => 'Notification Summary';

  @override
  String get mirrorPhoneAlerts => 'MIRROR PHONE ALERTS';

  @override
  String get forwardAlertsFromTheseAppsToAppleWatchWith =>
      'Forward alerts from these apps to Apple Watch with haptic feedback.';

  @override
  String get messagesSmsRcs => 'Messages (SMS & RCS)';

  @override
  String get phoneCalls => 'Phone & Calls';

  @override
  String get mail => 'Mail';

  @override
  String get calendarReminders => 'Calendar & Reminders';

  @override
  String get bankingApps => 'Banking Apps';

  @override
  String get bulletinRelayTest => 'BULLETIN RELAY TEST';

  @override
  String get sendATestNotificationToAppleWatchOverThe =>
      'Send a test notification to Apple Watch over the secure com.apple.private.alloy.bulletindistributor IDS channel.';

  @override
  String get sendTestNotification => 'Send Test Notification';

  @override
  String get simulatedTelegramSmsWithTapticFeedback =>
      'Simulated Telegram / SMS with Taptic feedback';

  @override
  String get testMessage => 'Test Message';

  @override
  String get androidAppleWatchConnectionIsWorking =>
      'Android ↔ Apple Watch connection is working!';

  @override
  String get notificationSent => 'Notification sent';

  @override
  String get bulletinrequestWasHandedToTheWatchProtocolStack =>
      'BulletinRequest was handed to the watch protocol stack.';

  @override
  String get changePasscode => 'Change Passcode';

  @override
  String get simplePasscode4Digits => 'Simple Passcode (4 digits)';

  @override
  String get unlockWithPhone => 'Unlock with Phone';

  @override
  String get unlockingYourPhoneAutomaticallyUnlocksYourTrustedAppleWatch =>
      'Unlocking your phone automatically unlocks your trusted Apple Watch when worn.';

  @override
  String get wristDetection => 'WRIST DETECTION';

  @override
  String get lockAppleWatchWhenYouTakeItOffTo =>
      'Lock Apple Watch when you take it off to protect your data and Apple Pay cards.';

  @override
  String get wristDetection2 => 'Wrist Detection';

  @override
  String get dataSecurity => 'DATA SECURITY';

  @override
  String get eraseAllAppleWatchDataAfter10FailedPasscode =>
      'Erase all Apple Watch data after 10 failed passcode attempts.';

  @override
  String get eraseData10Attempts => 'Erase Data (10 attempts)';

  @override
  String get automaticUpdates => 'Automatic Updates';

  @override
  String get betaUpdates => 'Beta Updates';

  @override
  String get checkingForUpdates => 'Checking for updates…';

  @override
  String get yourAppleWatchSoftwareIsUpToDate =>
      'Your Apple Watch software is up to date.';

  @override
  String get checkAgain => 'Check Again';

  @override
  String get alertVolume => 'ALERT VOLUME';

  @override
  String get silentMode => 'Silent Mode';

  @override
  String get hapticFeedback => 'HAPTIC FEEDBACK';

  @override
  String get hapticAlerts => 'Haptic Alerts';

  @override
  String get defaultHaptic => 'Default';

  @override
  String get prominent => 'Prominent';

  @override
  String get playAnAdditionalHapticBeforeANotification =>
      'Play an additional haptic before a notification.';

  @override
  String get systemFeedback => 'SYSTEM FEEDBACK';

  @override
  String get crownHaptics => 'Crown Haptics';

  @override
  String get systemHaptics => 'System Haptics';

  @override
  String get coverToMute => 'Cover to Mute';

  @override
  String get coverTheDisplayWithYourPalmForAtLeast =>
      'Cover the display with your palm for at least 3 seconds to mute an incoming call or alert.';

  @override
  String get watchStorage => 'WATCH STORAGE';

  @override
  String get reclaimable => 'Reclaimable';

  @override
  String get purgeableData => 'Purgeable Data';

  @override
  String get watchContent => 'WATCH CONTENT';

  @override
  String get pointTheCamera => 'Point the Camera';

  @override
  String get keepAppleWatchInTheViewfinder =>
      'Keep Apple Watch in the viewfinder';

  @override
  String get placeTheAppleWatchScreenInTheCenterOf =>
      'Place the Apple Watch screen in the center of the frame to recognize the pairing animation automatically.';

  @override
  String get pairAppleWatchManually => 'Pair Apple Watch Manually';

  @override
  String get pairManually => 'Pair Manually';

  @override
  String get enterThe6DigitCode => 'Enter the 6-digit Code';

  @override
  String get tapTheIIconOnYourAppleWatchTo =>
      'Tap the “i” icon on your Apple Watch to see the pairing code.';

  @override
  String get syncComplete => 'Sync complete!';

  @override
  String get syncingAppleWatch => 'Syncing Apple Watch';

  @override
  String get setUpAppleWatch => 'Set Up Apple Watch';

  @override
  String get keepBluetoothOnAndYourWatchCloseToYour =>
      'Keep Bluetooth on and your watch close to your phone.';

  @override
  String get connectAppleWatchToYourAndroidPhoneToSync =>
      'Connect Apple Watch to your Android phone to sync notifications, calls, and workouts.';

  @override
  String get pairAppleWatch => 'Pair Apple Watch';

  @override
  String get continueAction => 'Continue';

  @override
  String get finishSetup => 'Finish Setup';

  @override
  String get whichWristDoYouWearYourWatchOn =>
      'Which wrist do you wear your watch on?';

  @override
  String get appleWatchAdjustsWristRaiseAndGestureRecognitionFor =>
      'Apple Watch adjusts wrist raise and gesture recognition for your selected wrist.';

  @override
  String get digitalCrownPosition => 'Digital Crown Position';

  @override
  String get chooseWhichSideTheDigitalCrownIsOn =>
      'Choose which side the Digital Crown is on.';

  @override
  String get digitalCrownOnRight => 'Digital Crown on Right';

  @override
  String get digitalCrownOnLeft => 'Digital Crown on Left';

  @override
  String get alwaysOnDisplay => 'Always-On Display';

  @override
  String get theAlwaysOnRetinaDisplayShowsTheTimeAnd =>
      'The Always-On Retina display shows the time and important information without raising your wrist.';

  @override
  String get enableAlwaysOnDisplay => 'Enable Always-On Display';

  @override
  String get createAPasscode => 'Create a Passcode';

  @override
  String get aPasscodeProtectsYourDataApplePayCardsAnd =>
      'A passcode protects your data, Apple Pay cards, and medical information.';

  @override
  String get addA4DigitPasscode => 'Add a 4-digit Passcode';

  @override
  String get titanium => 'Titanium';

  @override
  String get sunny => 'Sunny';

  @override
  String get exclusiveAppleWatchUltraFacesWithACustomizableBezel =>
      'Exclusive Apple Watch Ultra faces with a customizable bezel, Night Mode, and 8 complications.';

  @override
  String get modular => 'Modular';

  @override
  String get largeInformationBlocksAndGraphsForDataAtA =>
      'Large information blocks and graphs for data at a glance.';

  @override
  String get classic => 'Classic';

  @override
  String get traditionalAnalogFacesWithRomanAndArabicNumerals =>
      'Traditional analog faces with Roman and Arabic numerals.';

  @override
  String get infographAstronomy => 'Infograph & Astronomy';

  @override
  String get detailedAstronomicalAndWeatherDisplays =>
      'Detailed astronomical and weather displays.';

  @override
  String get activityNike => 'Activity & Nike';

  @override
  String get activityRingsWorkoutsAndSportsMetrics =>
      'Activity rings, workouts, and sports metrics.';

  @override
  String get wayfinderAlpine => 'Wayfinder Alpine';

  @override
  String get wayfinderNightMode => 'Wayfinder Night Mode';

  @override
  String get modularDuo => 'Modular Duo';

  @override
  String get modularMulticolor => 'Modular Multicolor';

  @override
  String get california => 'California';

  @override
  String get californiaOcean => 'California Ocean';

  @override
  String get chronographPro => 'Chronograph Pro';

  @override
  String get infograph => 'Infograph';

  @override
  String get solarDial => 'Solar Dial';

  @override
  String get activityAnalog => 'Activity Analog';

  @override
  String get modularUltra => 'Modular Ultra';

  @override
  String get activityDigital => 'Activity Digital';

  @override
  String get selectionConfirmedByTheWatch =>
      'Selection confirmed by the watch.';

  @override
  String get collectionReceivedFromTheWatch =>
      'Collection received from the watch.';

  @override
  String get incompleteCollectionReceivedRefreshAgain =>
      'Incomplete collection received. Refresh again.';

  @override
  String get connectionChangedRefreshAgain =>
      'Connection changed. Refresh again.';

  @override
  String get waitingForTheWatchFaceCollection =>
      'Waiting for the watch face collection…';

  @override
  String get requestNotSentCheckTheConnection =>
      'Request not sent. Check the connection.';

  @override
  String get newCollectionNotReceivedYetRefreshAgain =>
      'New collection not received yet. Refresh again.';

  @override
  String get waitingForTheNewWatchFace => 'Waiting for the new watch face…';

  @override
  String get waitingForTheSelectedWatchFace =>
      'Waiting for the selected watch face…';

  @override
  String get commandNotSentRefreshTheWatchFaceCollection =>
      'Command not sent. Refresh the watch face collection.';

  @override
  String get resultNotConfirmedRefreshTheWatchFaceCollection =>
      'Result not confirmed. Refresh the watch face collection.';

  @override
  String lastSync(String value0) {
    return 'Last sync: $value0.';
  }

  @override
  String get myFaces => 'My Faces';

  @override
  String myFacesCount(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'My Faces · $count',
      one: 'My Faces · 1',
      zero: 'My Faces · 0',
    );
    return '$_temp0';
  }

  @override
  String get collectionNotReceivedYet => 'Collection not received yet';

  @override
  String get partialCollectionReceived => 'Partial collection received';

  @override
  String get collectionIsEmpty => 'Collection is empty';

  @override
  String face(String value0) {
    return 'Face $value0';
  }

  @override
  String get selectedOnWatch => 'Selected on watch';

  @override
  String get createCopy => 'Create Copy';

  @override
  String get refreshFromWatch => 'Refresh from Watch';

  @override
  String get enable => 'Enable';

  @override
  String get disable => 'Disable';

  @override
  String get commandSentWaitingForTheWatchResult =>
      'Command sent. Waiting for the watch result.';

  @override
  String get commandNotSentCheckTheConnection =>
      'Command not sent. Check the connection.';

  @override
  String get watchSettings => 'WATCH SETTINGS';

  @override
  String get valuesComeFromWatchMessagesNotReceivedMeansThe =>
      'Values come from watch messages. “Not received” means the watch has not reported this setting yet.';

  @override
  String get connectYourWatchToChangeSettings =>
      'Connect your watch to change settings.';

  @override
  String get stopNotConfirmed => 'Stop not confirmed';

  @override
  String get couldNotOpenFlashlightPermission =>
      'Could not open flashlight permission';

  @override
  String get phoneServiceUnavailable => 'Phone service unavailable';

  @override
  String get alertStatusIsStale => 'Alert status is stale';

  @override
  String get flashlightIsOn => 'Flashlight is on';

  @override
  String get soundAndFlashlightAreOn => 'Sound and flashlight are on';

  @override
  String get soundAlertStarted => 'Sound alert started';

  @override
  String get lastAlertDidNotStart => 'Last alert did not start';

  @override
  String get requestHadNoSoundOrFlashlight =>
      'Request had no sound or flashlight';

  @override
  String get alertStopped => 'Alert stopped';

  @override
  String get noAlertIsRunning => 'No alert is running';

  @override
  String get findPhoneFromWatch => 'Find Phone from Watch';

  @override
  String get localApkTest => 'Local APK Test';

  @override
  String get stopping => 'Stopping…';

  @override
  String get stopAlert => 'Stop Alert';

  @override
  String get cameraPermissionIsRequiredToFindYourPhoneWith =>
      'Camera permission is required to find your phone with the flashlight.';

  @override
  String get allowFlashlight => 'Allow Flashlight';

  @override
  String get incline42 => 'INCLINE 42°';

  @override
  String get north124 => 'NORTH 124°';

  @override
  String get message182M => '182 M';

  @override
  String get activity2 => 'ACTIVITY';

  @override
  String get message620800Kcal => '620 / 800 KCAL';

  @override
  String get today => 'TODAY';

  @override
  String get message1930Meeting => '19:30 MEETING';

  @override
  String get watchSync => 'Watch Sync';

  @override
  String get message72Bpm5Minago => '72 BPM • 5 min ago';

  @override
  String get theWatchHasNotRepliedYetRefreshAgain =>
      'The watch has not replied yet. Refresh again.';

  @override
  String get dataUpdated => 'Data updated';

  @override
  String get couldNotSendRequest => 'Could not send request';

  @override
  String get noWatchDataYet => 'No watch data yet';

  @override
  String telemetryStatusTime(
    String value0,
    String value1,
    String value2,
    String value3,
  ) {
    return '$value0 · $value1:$value2:$value3';
  }

  @override
  String get chargingUnknown => 'charging unknown';

  @override
  String get charging => 'charging';

  @override
  String get notCharging => 'not charging';

  @override
  String get battery2 => 'Battery: —';

  @override
  String batteryReading(String value0, String value1, String value2) {
    return '$value0: $value1% · $value2';
  }

  @override
  String get noConnectionToWatch => 'No connection to watch';

  @override
  String get waitingForWatchReply => 'Waiting for watch reply…';

  @override
  String get refreshData => 'Refresh Data';

  @override
  String get updated => 'Updated';

  @override
  String get dataIsStale => 'Data is stale';

  @override
  String get battery3 => 'Battery';

  @override
  String get lastBattery => 'Last battery';

  @override
  String get live => 'LIVE';

  @override
  String get alwaysOn2 => 'ALWAYS ON';

  @override
  String get bluetooth => 'Bluetooth';

  @override
  String get wiFi => 'Wi-Fi';

  @override
  String get digitalCrown => 'Digital Crown';

  @override
  String get appleWatchUltra2ActionButton =>
      'Apple Watch Ultra 2 Action Button';

  @override
  String get appleWatch => 'Apple Watch';

  @override
  String get watch => 'Watch';

  @override
  String get ultra => 'Ultra';

  @override
  String get wayfinder => 'Wayfinder';

  @override
  String get ultraModular => 'Ultra Modular';

  @override
  String get ultraModularTrail => 'Ultra Modular Trail';

  @override
  String get nikeBounceVolt => 'Nike Bounce Volt';

  @override
  String get appleMusic => 'Apple Music';

  @override
  String get appleAccount => 'Apple Account';

  @override
  String get watchConnection => 'Watch connection';

  @override
  String get savedWatch => 'Saved watch';

  @override
  String get noPairedWatch => 'No paired watch';

  @override
  String get noPairedWatchHint =>
      'Keep your watch nearby and put it in pairing mode to begin.';

  @override
  String get bridgeUnavailable => 'Bridge is unavailable';

  @override
  String get bridgeUnavailableHint =>
      'Install the matching Bridge backend to connect your watch.';

  @override
  String get identityLoading => 'Reading saved pairing…';

  @override
  String get identityReadError =>
      'The saved pairing is unavailable. Check diagnostics before starting a new pair.';

  @override
  String get connectWatch => 'Connect';

  @override
  String get disconnectWatch => 'Disconnect';

  @override
  String get connectionConnecting => 'Connecting…';

  @override
  String get connectionStopping => 'Disconnecting…';

  @override
  String get connectionPermissions => 'Allow Bluetooth access';

  @override
  String get pairingRunning => 'Setup in progress';

  @override
  String get resumeWatchSetup => 'Continue setup';

  @override
  String get finishWatchSetup => 'Finish setup';

  @override
  String get watchStillSettingUp => 'My watch still shows setup';

  @override
  String get resumeWatchSync => 'Resume synchronization';

  @override
  String get resumeWatchSyncHint =>
      'Use this only if your watch still displays setup. If its watch face is visible, finish setup instead.';

  @override
  String get confirmWatchFace => 'The watch face is visible';

  @override
  String get confirmWatchFaceHint =>
      'Confirm only after your watch shows its watch face. The current setup connection will be stopped safely.';

  @override
  String get confirmWatchFaceQuestion => 'Does your watch show its watch face?';

  @override
  String get confirmWatchFaceDetail =>
      'This saves normal connection mode for the current activated pair.';

  @override
  String get connectionRequestAccepted =>
      'Request accepted. Waiting for the watch…';

  @override
  String get connectionRequestRejected =>
      'Request not accepted. Check the current connection and diagnostics.';

  @override
  String get connectionPermissionRequired =>
      'Allow Bluetooth access, then try again.';

  @override
  String get connectionBusy =>
      'A connection or setup operation is already running.';

  @override
  String get syncCurrentWifi => 'Send phone Wi-Fi network';

  @override
  String get wifiReceiptHint =>
      'The network is queued for delivery. Verify it in Settings → Wi-Fi on your watch.';

  @override
  String get watchDiagnostics => 'Connection diagnostics';

  @override
  String get bridgeJournal => 'Bridge journal';

  @override
  String get copyBridgeJournal => 'Copy journal';

  @override
  String get journalCopied => 'Journal copied';

  @override
  String get advancedConnectionTools => 'Advanced connection tools';

  @override
  String get stockBondAudit => 'Audit system Bluetooth bond';

  @override
  String get stockBondImport => 'Import system Bluetooth bond';

  @override
  String get stockIdentityAlignment => 'Align system Bluetooth identity';

  @override
  String get stockReconnectProbe => 'Test system reconnect';

  @override
  String get advancedToolHint =>
      'These tools require the connection to be stopped and system Bluetooth to be off.';

  @override
  String get advancedToolQuestion => 'Run this connection tool?';

  @override
  String get advancedToolDetail =>
      'The backend validates the saved pair and Bluetooth state before running.';

  @override
  String get pairingDiscovering => 'Looking for a watch…';

  @override
  String get pairingConnecting => 'Connecting to the discovered watch…';

  @override
  String get pairingSecurity => 'Establishing a secure pair…';

  @override
  String get pairingActivation => 'Activating with Apple…';

  @override
  String get pairingIds => 'Connecting watch services…';

  @override
  String get pairingRegistry => 'Reading watch configuration…';

  @override
  String get pairingConfiguring => 'Applying watch settings…';

  @override
  String get pairingActivated => 'Watch activated';

  @override
  String get pairingCheckWatch =>
      'Initial synchronization sent. Check your watch.';

  @override
  String get pairingVerifyReconnect =>
      'Watch setup observed. Connection verification pending.';

  @override
  String get pairingPaused => 'Setup connection stopped';

  @override
  String get watchActivatedHint =>
      'Activation is complete. Confirm below when your watch shows its watch face.';

  @override
  String get confirmWatchFaceActiveDetail =>
      'This will disconnect the setup session safely and save normal connection mode for this activated pair.';

  @override
  String get pairingSync => 'Synchronizing watch setup…';

  @override
  String get pairingVerified => 'Watch setup complete';

  @override
  String get pairingFailed =>
      'The setup session ended with an error. Check the journal.';

  @override
  String get pairingIdle => 'No setup session is running.';

  @override
  String get pairingStarting => 'Starting the watch session…';

  @override
  String get pairingPinHint =>
      'Enter the six-digit code displayed on your watch.';

  @override
  String get pairingCheckingCode => 'Checking the watch code…';

  @override
  String get pairingCodeVerifiedBondPending =>
      'Watch code verified. Bluetooth bonding is not available in this build yet.';

  @override
  String get macosPairingBootstrapRestricted =>
      'macOS restricts the Bluetooth channel required for watch pairing. See Diagnostics for details.';

  @override
  String get windowsPairingBootstrapRestricted =>
      'Bluetooth discovery and connection are available. Windows does not expose the channel required for Apple Watch pairing in this build.';

  @override
  String get windowsRawHciRequired =>
      'Full Watch pairing requires the bundled protocol runtime and signed UsbDk 1.0.21. Version 1.0.22 is blocked because it can crash Windows. Select a USB Bluetooth adapter; it is reserved for the Watch during the session.';

  @override
  String get sendWatchPin => 'Send code';

  @override
  String get pairingCodeOnlyHint =>
      'On this computer, pair using the six-digit code shown on your watch.';

  @override
  String get findNearbyWatch => 'Find a Watch';

  @override
  String get bluetoothLinkConnected => 'Bluetooth link connected';

  @override
  String get watchPairingUnavailable =>
      'Watch pairing services are not available on this platform yet.';

  @override
  String get activationOwnerInput => 'Apple requires owner confirmation';

  @override
  String get activationCredentialsHint =>
      'Credentials are sent to Apple\'s activation service and are not saved by Companion.';

  @override
  String get submitActivation => 'Continue activation';

  @override
  String get retryActivation => 'Retry activation';

  @override
  String get stopSetup => 'Stop setup';

  @override
  String get pairedIdentity => 'Pairing ID';

  @override
  String get hardwareIdentity => 'Hardware identifier';

  @override
  String get systemBuild => 'System build';

  @override
  String get pendingWatchSetup => 'This saved pair has not finished setup.';

  @override
  String get oneWatchBackend =>
      'The backend currently stores one pairing. A saved pair is retained when disconnected.';

  @override
  String get finishInitialSync => 'Finish initial synchronization';

  @override
  String get chooseDiscoveredWatch =>
      'Choose a watch found nearby. Only supported setup advertisements are listed.';

  @override
  String get desktopCoreUnavailable =>
      'The connection service could not start. See Diagnostics for details.';

  @override
  String get manageWatchFaces => 'Manage Watch Faces';

  @override
  String get nativeFaceChangesConfirmed => 'Changes confirmed by the watch.';

  @override
  String get nativeFaceWaiting => 'Waiting for the watch to confirm changes…';

  @override
  String get nativeFaceSaving => 'Saving changes…';

  @override
  String get nativeFaceRetry => 'Retry';

  @override
  String get nativeFaceEditPending =>
      'Changes saved on this device. Waiting for the watch…';

  @override
  String get nativeFaceSaveFailed =>
      'Changes could not be saved on this device. Keep this editor open and retry.';

  @override
  String get nativeFaceEditUncertain =>
      'Changes are saved on this device, but the watch has not confirmed them. Retry to send them again.';

  @override
  String get nativeFaceRejected =>
      'Changes were not sent. Refresh the collection and try again.';

  @override
  String get nativeFaceUnknown =>
      'The result is not confirmed. Refresh before making another change.';

  @override
  String get nativeFaceImportHint =>
      'Choose a native .watchface package, including bundled photos and resources (up to 16 MiB). Compatibility is confirmed by the connected watch.';

  @override
  String get nativeFaceCustomization => 'Customization';

  @override
  String get nativeFaceComplications => 'Complications';

  @override
  String get nativeFaceSetActive => 'Set as Current Face';

  @override
  String get nativeFaceDelete => 'Delete Watch Face';

  @override
  String get nativeFaceDeleteQuestion => 'Delete this watch face?';

  @override
  String get nativeFaceDeleteDetail =>
      'The remaining faces will stay on the watch. The last face cannot be deleted.';

  @override
  String get nativeFaceMoveEarlier => 'Move Earlier';

  @override
  String get nativeFaceMoveLater => 'Move Later';

  @override
  String get nativeFaceConfiguration => 'Native Configuration';

  @override
  String get nativeFaceConfigurationHint =>
      'These values come from the watch. Keep unknown fields intact. Available values and complication support depend on the face and watchOS.';

  @override
  String get nativeFaceApply => 'Apply to Watch';

  @override
  String get nativeFaceEditValue => 'Edit Value';

  @override
  String get nativeFaceValueHint =>
      'Enter a JSON value. Strings must be quoted; numbers and booleans use JSON notation.';

  @override
  String get nativeFaceInvalidValue => 'Invalid configuration value';

  @override
  String get nativeFaceConfigurationUnavailable =>
      'Update Bridge and refresh the collection to load this face’s configuration.';

  @override
  String get nativeFaceFileSaved => 'Watch face file saved';

  @override
  String get nativeFaceFileError =>
      'Could not open or save the watch face file';

  @override
  String get nativeFaceResourceExportUnavailable =>
      'This face may need photos or other resources. Export is unavailable until the complete native package can be read.';

  @override
  String get nativeFaceLocalLayoutHint =>
      'This gallery contains local preview layouts. Native watch faces are managed in My Watch → Manage Watch Faces.';

  @override
  String get nativeFaceComplicationHint =>
      'Choose a compatible complication from the watch\'s catalog or an existing face. Saved settings and native intents are preserved.';

  @override
  String get nativeFaceComplicationCatalogPartial =>
      'Some descriptors are unavailable. Refresh the collection to update compatible choices.';

  @override
  String get nativeFaceComplicationVariantsMissing =>
      'Some complications on these faces have not supplied their available variants. You can still use their current settings.';

  @override
  String get nativeFaceNoComplication => 'None';

  @override
  String get nativeFaceUnknownComplication => 'Unknown complication';

  @override
  String get nativeFaceConflict =>
      'This face changed or the watch reconnected. Open the face again before applying edits.';

  @override
  String get exportLocalFaceLayout => 'Export Local Layout';

  @override
  String get localFaceLayoutSaved => 'Local layout saved';

  @override
  String get localFaceLayoutSavedHint =>
      'This .watchlayout file contains a Companion preview layout. It is not an installable native watch face.';

  @override
  String get nativeFaceTimeStyle => 'Time Style';

  @override
  String get nativeFaceDialStyle => 'Dial Style';

  @override
  String get nativeFaceWaypoint => 'Waypoint';

  @override
  String get nativeFacePink => 'Pink';

  @override
  String get nativeFaceNeonGreen => 'Neon Green';

  @override
  String get nativeFaceOrange => 'Orange';

  @override
  String get nativeFacePinkSand => 'Pink / Sand';

  @override
  String get nativeFaceNeonGreenCloud => 'Neon Green / Cloud';

  @override
  String get nativeFaceOrangeLightSage => 'Orange / Light Sage';

  @override
  String get nativeFaceLightBlue => 'Light Blue';

  @override
  String get nativeFaceBrightBlue => 'Bright Blue';

  @override
  String get nativeFaceTerraCotta => 'Terra Cotta';

  @override
  String get nativeFaceTopLeft => 'Top Left';

  @override
  String get nativeFaceTop => 'Top';

  @override
  String get nativeFaceBottom => 'Bottom';

  @override
  String get nativeFaceTopRight => 'Top Right';

  @override
  String get nativeFaceBottomLeft => 'Bottom Left';

  @override
  String get nativeFaceBottomRight => 'Bottom Right';

  @override
  String get nativeFaceCenter => 'Center';

  @override
  String get nativeFaceNightMode => 'Night Mode';

  @override
  String get nativeFaceColor => 'Color';

  @override
  String get nativeFaceAutomatic => 'Automatic';

  @override
  String get nativeFaceLeft => 'Left';

  @override
  String get nativeFaceRight => 'Right';

  @override
  String nativeFacePalette(String number) {
    return 'Palette $number';
  }

  @override
  String nativeFaceSeasonalPalette(String number) {
    return 'Seasonal $number';
  }

  @override
  String get nativeFaceAdvanced => 'Advanced';

  @override
  String get nativeFaceEditHint =>
      'Choose your settings, then apply them to your watch.';

  @override
  String get nativeFaceCopyHint =>
      'Create a new face from one on your watch, or import a shared .watchface file.';

  @override
  String get nativeFaceLocalLibrary => 'Local Design Library';

  @override
  String get nativeFaceUnsaved => 'Discard unsaved changes?';

  @override
  String get nativeFaceDiscard => 'Discard';

  @override
  String get nativeFaceImport => 'Import Watch Face';

  @override
  String get nativeFaceSharedPreview => 'Shared preview';

  @override
  String get nativeFaceImportDone => 'Done';

  @override
  String get nativeFaceSharedPreviewUnavailable =>
      'This file has no usable shared preview.';

  @override
  String get nativeFaceSharedPreviewHint =>
      'Preview supplied with the file. Compatibility and successful installation are confirmed by your watch.';

  @override
  String get nativeFaceDraft => 'Unsaved changes';

  @override
  String get nativeFaceDigital => 'Digital';

  @override
  String get nativeFaceAnalog => 'Analog';

  @override
  String get nativeFaceAdd => 'Add Watch Face';

  @override
  String get nativeFaceAvailable => 'Available Faces';

  @override
  String get nativeFaceAddToWatch => 'Add to Watch';

  @override
  String get nativeFaceTemplateHint =>
      'Choose colors and complications before adding this face to your watch.';

  @override
  String get nativeFaceDefaultPreview => 'Default preview';

  @override
  String get nativeFacePreviewUnavailable =>
      'Preview unavailable for these settings';

  @override
  String get nativeFaceStylePreview => 'Style preview';

  @override
  String get nativeFaceStylePreviewHint =>
      'Sample time. Live data and complications appear on your watch.';

  @override
  String get nativeFaceSearch => 'Search';

  @override
  String get nativePhotoChoose => 'Choose Photo';

  @override
  String get nativePhotoCropHint =>
      'Drag to frame your photo. Pinch or use the slider to zoom.';

  @override
  String nativeIntentSettings(String slot) {
    return 'Settings for $slot';
  }

  @override
  String nativeIntentParameterSeconds(String name) {
    return '$name (seconds)';
  }

  @override
  String nativeIntentDurationValue(String name, String seconds) {
    return '$name: $seconds s';
  }

  @override
  String get nativeIntentParameterInvalid =>
      'Enter a finite duration within its supported range.';

  @override
  String get nativeIntentLocalDraft =>
      'These settings are saved in your draft. Apply the face to send them to your watch.';

  @override
  String get nativePhotoZoom => 'Zoom';

  @override
  String get nativePhotoAspect => 'Frame Shape';

  @override
  String get nativePhotoKeepCrop => 'Keep Crop';

  @override
  String nativePhotoSelected(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count photos selected',
      one: '1 photo selected',
    );
    return '$_temp0';
  }

  @override
  String get nativePhotoTimeAlignment => 'Time Alignment';

  @override
  String get nativePhotoLeading => 'Leading';

  @override
  String get nativePhotoTrailing => 'Trailing';

  @override
  String get nativePhotoTimeSize => 'Time Size';

  @override
  String get nativePhotoSmall => 'Small';

  @override
  String get nativePhotoMedium => 'Medium';

  @override
  String get nativePhotoLarge => 'Large';

  @override
  String get nativePhotoXLarge => 'XL';

  @override
  String get nativePhotoRemove => 'Remove Photo';

  @override
  String get nativeFaceShade => 'Shade';

  @override
  String get nativeFaceAddColors => 'Add Colors';

  @override
  String get nativeFaceColorsDone => 'Done';

  @override
  String get nativeFaceColorsSaveError => 'Could not save colors. Try again.';

  @override
  String get nativeFaceColorsPending =>
      'Waiting for color settings from your Apple Watch.';

  @override
  String get nativeFaceColorsAwaiting => 'Sending colors to your Apple Watch.';

  @override
  String get nativeFaceColorsUncertain =>
      'Your Apple Watch reported different colors.';

  @override
  String get nativeFaceColorsSent => 'Colors sent to your Apple Watch.';

  @override
  String get nativeFaceColorsDeliveryUncertain =>
      'Color delivery could not be confirmed.';

  @override
  String get nativeFaceColorsRetry => 'Retry Color Sync';

  @override
  String get nativeFaceMonogram => 'Monogram';

  @override
  String get nativeMonogramDone => 'Done';

  @override
  String get nativeMonogramInvalid => 'This text cannot be used as a monogram.';

  @override
  String get nativeMonogramUnavailable =>
      'Connect your Apple Watch to edit its monogram.';

  @override
  String get nativeMonogramSaveError =>
      'Could not save the monogram. Try again.';

  @override
  String get nativeMonogramAwaiting =>
      'Sending the monogram to your Apple Watch.';

  @override
  String get nativeMonogramDelivered =>
      'Monogram delivered. Waiting for confirmation from your Apple Watch.';

  @override
  String get nativeMonogramUncertain => 'Monogram sync could not be confirmed.';

  @override
  String get nativeMonogramConfirmed =>
      'Monogram confirmed by your Apple Watch.';

  @override
  String get nativeFaceSeeAllWatchFaces => 'See All Watch Faces';

  @override
  String get nativeFaceAllWatchFaces => 'All Watch Faces';

  @override
  String get nativeFaceLibraryEdit => 'Edit';

  @override
  String get nativeFaceLibraryDone => 'Done';

  @override
  String nativeFaceLibraryReorder(String face) {
    return 'Reorder $face';
  }
}
