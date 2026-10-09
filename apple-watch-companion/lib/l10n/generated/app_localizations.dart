import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'generated/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[Locale('en')];

  /// No description provided for @bluetoothAdapter.
  ///
  /// In en, this message translates to:
  /// **'Bluetooth Adapter'**
  String get bluetoothAdapter;

  /// No description provided for @bluetoothController.
  ///
  /// In en, this message translates to:
  /// **'Bluetooth controller'**
  String get bluetoothController;

  /// No description provided for @refreshBluetoothAdapters.
  ///
  /// In en, this message translates to:
  /// **'Refresh Adapters'**
  String get refreshBluetoothAdapters;

  /// No description provided for @bluetoothAdaptersLoading.
  ///
  /// In en, this message translates to:
  /// **'Looking for Bluetooth adapters…'**
  String get bluetoothAdaptersLoading;

  /// No description provided for @bluetoothAdaptersEmpty.
  ///
  /// In en, this message translates to:
  /// **'No Bluetooth adapters found. Connect an adapter and refresh the list.'**
  String get bluetoothAdaptersEmpty;

  /// No description provided for @bluetoothAdapterChoiceHint.
  ///
  /// In en, this message translates to:
  /// **'Choose an adapter for your Watch. Your choice is saved for future connections.'**
  String get bluetoothAdapterChoiceHint;

  /// No description provided for @bluetoothAdapterDisconnectHint.
  ///
  /// In en, this message translates to:
  /// **'Disconnect your Watch before changing the Bluetooth adapter.'**
  String get bluetoothAdapterDisconnectHint;

  /// No description provided for @bluetoothAdapterMissing.
  ///
  /// In en, this message translates to:
  /// **'Saved adapter unavailable'**
  String get bluetoothAdapterMissing;

  /// No description provided for @bluetoothAdapterMissingHint.
  ///
  /// In en, this message translates to:
  /// **'Reconnect your saved adapter or choose another one. Your Watch pairing is preserved.'**
  String get bluetoothAdapterMissingHint;

  /// No description provided for @bluetoothAdapterDiscoveryFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not read Bluetooth adapters. Check that BlueZ is running, then refresh the list.'**
  String get bluetoothAdapterDiscoveryFailed;

  /// No description provided for @bluetoothAdapterSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Could not read or save the adapter preference. Check access to the application state folder, then select an adapter again.'**
  String get bluetoothAdapterSaveFailed;

  /// No description provided for @bluetoothAdapterSaved.
  ///
  /// In en, this message translates to:
  /// **'Bluetooth adapter saved.'**
  String get bluetoothAdapterSaved;

  /// No description provided for @opticalScanTitle.
  ///
  /// In en, this message translates to:
  /// **'Pair Apple Watch'**
  String get opticalScanTitle;

  /// No description provided for @opticalPairWithCamera.
  ///
  /// In en, this message translates to:
  /// **'Pair with Camera'**
  String get opticalPairWithCamera;

  /// No description provided for @opticalScanHint.
  ///
  /// In en, this message translates to:
  /// **'Keep the pairing animation inside the frame, then tap Scan. Hold your phone steady while it reads the code.'**
  String get opticalScanHint;

  /// No description provided for @opticalScan.
  ///
  /// In en, this message translates to:
  /// **'Scan'**
  String get opticalScan;

  /// No description provided for @opticalNoMatch.
  ///
  /// In en, this message translates to:
  /// **'No pairing animation found. Point the camera at your Watch and try again.'**
  String get opticalNoMatch;

  /// No description provided for @opticalRecognizedTitle.
  ///
  /// In en, this message translates to:
  /// **'Apple Watch Found'**
  String get opticalRecognizedTitle;

  /// No description provided for @opticalRecognizedDetail.
  ///
  /// In en, this message translates to:
  /// **'Pairing code recognized for {name}. Bluetooth authentication is the next step.'**
  String opticalRecognizedDetail(String name);

  /// No description provided for @opticalReplaceDetail.
  ///
  /// In en, this message translates to:
  /// **'Code recognized for {name}. Your Watch must be waiting for a new pair. The previous encrypted pair will be backed up before replacement.'**
  String opticalReplaceDetail(String name);

  /// No description provided for @opticalReplacePair.
  ///
  /// In en, this message translates to:
  /// **'Replace Saved Pair'**
  String get opticalReplacePair;

  /// No description provided for @opticalStartPairing.
  ///
  /// In en, this message translates to:
  /// **'Pair Watch'**
  String get opticalStartPairing;

  /// No description provided for @opticalAnalyzedFrames.
  ///
  /// In en, this message translates to:
  /// **'{count} frames analyzed'**
  String opticalAnalyzedFrames(int count);

  /// No description provided for @opticalPairingLab.
  ///
  /// In en, this message translates to:
  /// **'Optical Pairing Lab'**
  String get opticalPairingLab;

  /// No description provided for @opticalCaptureHint.
  ///
  /// In en, this message translates to:
  /// **'Keep the pairing animation inside the frame. Capture a short sample to test the optical decoder. Recognition is not yet enabled. The sample is deleted when you leave this screen.'**
  String get opticalCaptureHint;

  /// No description provided for @opticalCameraError.
  ///
  /// In en, this message translates to:
  /// **'Camera capture failed. Check camera permission and try again.'**
  String get opticalCameraError;

  /// No description provided for @opticalFrames.
  ///
  /// In en, this message translates to:
  /// **'{count} frames captured'**
  String opticalFrames(int count);

  /// No description provided for @opticalCapture.
  ///
  /// In en, this message translates to:
  /// **'Capture Sample'**
  String get opticalCapture;

  /// No description provided for @opticalRetry.
  ///
  /// In en, this message translates to:
  /// **'Try Again'**
  String get opticalRetry;

  /// No description provided for @opticalStopCapture.
  ///
  /// In en, this message translates to:
  /// **'Stop Capture'**
  String get opticalStopCapture;

  /// No description provided for @backendFeatures.
  ///
  /// In en, this message translates to:
  /// **'Available Features'**
  String get backendFeatures;

  /// No description provided for @backendFeaturesHint.
  ///
  /// In en, this message translates to:
  /// **'Availability describes this backend\'s implemented services. A connection does not guarantee every watchOS feature, device or firmware version is supported.'**
  String get backendFeaturesHint;

  /// No description provided for @backendFeaturesUnknown.
  ///
  /// In en, this message translates to:
  /// **'Feature capabilities have not been received from the connection service yet.'**
  String get backendFeaturesUnknown;

  /// No description provided for @featureNotSupported.
  ///
  /// In en, this message translates to:
  /// **'Not supported yet'**
  String get featureNotSupported;

  /// No description provided for @phoneFindFeature.
  ///
  /// In en, this message translates to:
  /// **'Find Phone'**
  String get phoneFindFeature;

  /// Dismiss an informational dialog.
  ///
  /// In en, this message translates to:
  /// **'OK'**
  String get ok;

  /// Caption above the face preview time slider.
  ///
  /// In en, this message translates to:
  /// **'DIGITAL CROWN / TIME TRAVEL'**
  String get digitalCrownTimeTravel;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Right wrist'**
  String get rightWrist;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Rotate screen 180°'**
  String get rotateScreen180;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'24-hour time'**
  String get message24Hourtime;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Ready to connect'**
  String get readyToConnect;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Workout'**
  String get workout;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Track activity, heart rate, power zones, and GPS routes.'**
  String get trackActivityHeartRatePowerZonesAndGpsRoutes;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Activity'**
  String get activity;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Move, Exercise, and Stand rings.'**
  String get moveExerciseAndStandRings;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Heart Rate'**
  String get heartRate;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Heart rate measurements and alerts.'**
  String get heartRateMeasurementsAndAlerts;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Compass & Waypoints'**
  String get compassWaypoints;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Elevation, incline, coordinates, and Backtrack routes.'**
  String get elevationInclineCoordinatesAndBacktrackRoutes;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Music'**
  String get music;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Stream audio and control playback.'**
  String get streamAudioAndControlPlayback;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Messages'**
  String get messages;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Send and receive messages, use dictation and quick replies.'**
  String get sendAndReceiveMessagesUseDictationAndQuickReplies;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Phone'**
  String get phone;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Calls over Bluetooth or cellular.'**
  String get callsOverBluetoothOrCellular;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Weather'**
  String get weather;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Forecasts, UV index, precipitation, and wind.'**
  String get forecastsUvIndexPrecipitationAndWind;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Maps'**
  String get maps;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Turn-by-turn directions with haptic feedback.'**
  String get turnByTurnDirectionsWithHapticFeedback;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Noise'**
  String get noise;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Monitor ambient noise levels in decibels.'**
  String get monitorAmbientNoiseLevelsInDecibels;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Natural Titanium'**
  String get naturalTitanium;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'49 mm'**
  String get message49Mm;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Battery'**
  String get battery;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'72 bpm'**
  String get message72Bpm;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'+21° Sunny'**
  String get message21Sunny;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Compass'**
  String get compass;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'124° SE • 182 m'**
  String get message124Se182M;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Outdoor Run'**
  String get outdoorRun;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Now Playing'**
  String get nowPlaying;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Calendar'**
  String get calendar;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Meeting at 19:30'**
  String get meetingAt1930;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Custom'**
  String get custom;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Imported'**
  String get imported;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} GB'**
  String gb(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} MB'**
  String mb(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} KB'**
  String kb(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} B'**
  String b(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Point the camera at your Apple Watch screen'**
  String get pointTheCameraAtYourAppleWatchScreen;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Enter the 6-digit code shown on your watch'**
  String get enterThe6DigitCodeShownOnYourWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Waiting for Bridge confirmation…'**
  String get waitingForBridgeConfirmation;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Setup confirmed by the watch'**
  String get setupConfirmedByTheWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Setup is not confirmed yet. Apple Watch Bridge is still setting up the watch.'**
  String get setupIsNotConfirmedYetAppleWatchBridgeIs;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'No setup confirmation from Bridge'**
  String get noSetupConfirmationFromBridge;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Discover'**
  String get discover;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'WATCH COMPANION'**
  String get newInWatchos11;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Apple Watch Companion'**
  String get appleWatchUltra2OnAndroid;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Connect your Apple Watch using your phone or desktop. See Available Features in My Watch for the services implemented by your connection backend.'**
  String get syncNotificationsCallsWorkoutsAndWatchFacesWithYour;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'GUIDES & TIPS'**
  String get guidesTips;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Gestures & Controls'**
  String get gesturesControls;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Double Tap, Digital Crown, and Action Button'**
  String get doubleTapDigitalCrownAndActionButton;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Health & Workouts'**
  String get healthWorkouts;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Set up heart rate zones, GPS routes, and Activity rings'**
  String get setUpHeartRateZonesGpsRoutesAndActivity;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Compass & Backtrack'**
  String get compassBacktrack;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Navigate without an internet connection'**
  String get navigateWithoutAnInternetConnection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Notification Mirroring'**
  String get notificationMirroring;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Set up SMS, calls, and messaging apps'**
  String get setUpSmsCallsAndMessagingApps;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'RESOURCES'**
  String get resources;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Apple Watch User Guide'**
  String get appleWatchUserGuide;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'HAL Support & Diagnostics'**
  String get halSupportDiagnostics;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Apple Watch Ultra 2 Controls'**
  String get appleWatchUltra2Controls;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Use Double Tap, the Digital Crown, and the orange Action Button.'**
  String get useDoubleTapTheDigitalCrownAndTheOrange;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'ESSENTIAL GESTURES'**
  String get essentialGestures;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Double Tap'**
  String get doubleTap;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Tap your index finger and thumb together twice to answer a call, stop a timer, or scroll the Smart Stack.'**
  String get tapYourIndexFingerAndThumbTogetherTwiceTo;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Turn to scroll through lists and zoom, or press to return to the watch face.'**
  String get turnToScrollThroughListsAndZoomOrPress;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Orange Action Button'**
  String get orangeActionButton;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Start a workout, mark a waypoint, or hold to activate the 86 dB siren.'**
  String get startAWorkoutMarkAWaypointOrHoldTo;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Water Lock'**
  String get waterLock;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Prevents accidental touches in water. Use the Digital Crown to unlock the screen and eject water.'**
  String get preventsAccidentalTouchesInWaterUseTheDigitalCrown;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Face Gallery'**
  String get faceGallery;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'SAVE'**
  String get save;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Reset'**
  String get reset;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'COLOR PALETTE'**
  String get colorPalette;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Night Mode'**
  String get nightMode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'BEZEL STYLE'**
  String get bezelStyle;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Elevation / Incline'**
  String get elevationIncline;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Seconds'**
  String get seconds;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'No Bezel'**
  String get noBezel;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'COMPLICATIONS (TAP TO CHANGE)'**
  String get complicationsTapToChange;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Save Layout on Phone'**
  String get saveLayoutOnPhone;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Export .watchface Package'**
  String get exportWatchfacePackage;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Choose a complication for {value0}'**
  String chooseAComplicationFor(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'.watchface generated successfully'**
  String get watchfaceGeneratedSuccessfully;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Created an Apple Watch package ({value0} bytes). '**
  String createdAnAppleWatchPackageBytes(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Includes Face.plist, metadata.plist, and slot configuration.'**
  String get includesFacePlistMetadataPlistAndSlotConfiguration;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Top Subdial'**
  String get topSubdial;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Top Left'**
  String get topLeft;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Top Right'**
  String get topRight;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Center'**
  String get center;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Bottom Left'**
  String get bottomLeft;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Bottom Right'**
  String get bottomRight;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Bottom Subdial'**
  String get bottomSubdial;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Bezel'**
  String get bezel;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Import'**
  String get import;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Online Gallery'**
  String get onlineGallery;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Downloaded from the Apple Watch Face catalog server.'**
  String get downloadedFromTheAppleWatchFaceCatalogServer;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Imported Wayfinder'**
  String get importedWayfinder;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Import Apple Watch Face'**
  String get importAppleWatchFace;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Load a .watchface package (ZIP with Face.plist)'**
  String get loadAWatchfacePackageZipWithFacePlist;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Watch face imported!'**
  String get watchFaceImported;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Layout “{value0}” saved on your phone.'**
  String layoutSavedOnYourPhone(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Import Sample .watchface'**
  String get importSampleWatchface;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'My Watch'**
  String get myWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'About'**
  String get about;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'General'**
  String get general;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Name'**
  String get name;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Model'**
  String get model;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Model Number'**
  String get modelNumber;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Case'**
  String get watchCase;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'watchOS Version'**
  String get watchosVersion;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Serial Number'**
  String get serialNumber;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Network Addresses'**
  String get networkAddresses;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Wi-Fi Address'**
  String get wiFiAddress;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Storage'**
  String get storage;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Available'**
  String get available;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Songs'**
  String get songs;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Photos'**
  String get photos;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'User Apps'**
  String get userApps;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Action Button'**
  String get actionButton;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Quick access to workouts, waypoints, the stopwatch, and the safety siren.'**
  String get quickAccessToWorkoutsWaypointsTheStopwatchAndThe;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'ACTION'**
  String get action;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Stopwatch'**
  String get stopwatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Compass Waypoint'**
  String get compassWaypoint;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Backtrack'**
  String get backtrack;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Dive & Depth'**
  String get diveDepth;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Flashlight'**
  String get flashlight;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'SAFETY SIREN'**
  String get safetySiren;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Hold the Action Button on Apple Watch Ultra to play an 86-decibel alarm audible from up to 180 meters away.'**
  String get holdTheActionButtonOnAppleWatchUltraTo;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Hold for Siren'**
  String get holdForSiren;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} • Version {value1}'**
  String version(String value0, String value1);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Show App on Apple Watch'**
  String get showAppOnAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Display & Brightness'**
  String get displayBrightness;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'BRIGHTNESS'**
  String get brightness;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'TEXT SIZE'**
  String get textSize;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Bold Text'**
  String get boldText;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Keep the watch face and app information visible when your wrist is down.'**
  String get keepTheWatchFaceAndAppInformationVisibleWhen;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Always On'**
  String get alwaysOn;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'WRIST RAISE'**
  String get wristRaise;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Wake on Wrist Raise'**
  String get wakeOnWristRaise;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Wake Duration'**
  String get wakeDuration;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'70 seconds'**
  String get message70Seconds;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'15 seconds'**
  String get message15Seconds;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Screen Wake Duration on Tap'**
  String get screenWakeDurationOnTap;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Emergency SOS'**
  String get emergencySos;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'FALL DETECTION'**
  String get fallDetection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'If Apple Watch detects a hard fall and you remain immobile for about a minute, it gives a haptic alert, sounds an alarm, and contacts emergency services.'**
  String get ifAppleWatchDetectsAHardFallAndYou;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Fall Detection'**
  String get fallDetection2;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'CRASH DETECTION'**
  String get crashDetection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'After a severe car crash, Apple Watch shows the emergency call slider and automatically calls emergency services after 20 seconds.'**
  String get afterASevereCarCrashAppleWatchShowsThe;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Crash Detection'**
  String get crashDetection2;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'MEDICAL INFORMATION'**
  String get medicalInformation;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Set Up Medical ID'**
  String get setUpMedicalId;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Software Update'**
  String get softwareUpdate;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Orientation & Time'**
  String get orientationTime;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Left wrist'**
  String get leftWrist;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Not received'**
  String get notReceived;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Background App Refresh'**
  String get backgroundAppRefresh;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'On'**
  String get on;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Off'**
  String get off;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'CONNECTIVITY'**
  String get connectivity;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Airplane Mode'**
  String get airplaneMode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Reset & Unpair'**
  String get resetUnpair;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Unpair Apple Watch'**
  String get unpairAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'All data, watch faces, and settings will be erased from the device.'**
  String get allDataWatchFacesAndSettingsWillBeErased;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Unpair'**
  String get unpair;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Activity & Health'**
  String get activityHealth;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'No health data received from the watch yet.'**
  String get noHealthDataReceivedFromTheWatchYet;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'WATCH MEASUREMENTS'**
  String get watchMeasurements;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'bpm'**
  String get bpm;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Steps'**
  String get steps;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} km'**
  String km(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Move'**
  String get move;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} / {value1} kcal'**
  String kcal(String value0, String value1);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Exercise'**
  String get exercise;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} / {value1} min'**
  String min(String value0, String value1);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Stand'**
  String get stand;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} / {value1} hr'**
  String hr(String value0, String value1);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'ACTIVITY RINGS'**
  String get activityRings;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Stand Reminders'**
  String get standReminders;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Reminds you to stand and move 10 minutes before the end of each hour.'**
  String get remindsYouToStandAndMove10MinutesBefore;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Daily Coaching'**
  String get dailyCoaching;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Goal Completions'**
  String get goalCompletions;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'HEART & BREATHING'**
  String get heartBreathing;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'High Heart Rate Notifications'**
  String get highHeartRateNotifications;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'> {value0} bpm'**
  String bpm2(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Low Heart Rate Notifications'**
  String get lowHeartRateNotifications;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'< {value0} bpm'**
  String bpm3(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Blood Oxygen'**
  String get bloodOxygen;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Sleep Tracking'**
  String get sleepTracking;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'All Watches'**
  String get allWatches;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Connected'**
  String get connected;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Disconnected'**
  String get disconnected;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Sounds & Haptics'**
  String get soundsHaptics;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Notifications'**
  String get notifications;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Passcode'**
  String get passcode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'INSTALLED ON APPLE WATCH'**
  String get installedOnAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Installed'**
  String get installed;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Hidden'**
  String get hidden;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Manage connected watches'**
  String get manageConnectedWatches;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Add Apple Watch'**
  String get addAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Notification Indicator'**
  String get notificationIndicator;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Shows a red dot at the top of the watch face when you have unread notifications.'**
  String get showsARedDotAtTheTopOfThe;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Notification Privacy'**
  String get notificationPrivacy;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Hide notification details until you tap the screen.'**
  String get hideNotificationDetailsUntilYouTapTheScreen;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Notification Summary'**
  String get notificationSummary;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'MIRROR PHONE ALERTS'**
  String get mirrorPhoneAlerts;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Forward alerts from these apps to Apple Watch with haptic feedback.'**
  String get forwardAlertsFromTheseAppsToAppleWatchWith;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Messages (SMS & RCS)'**
  String get messagesSmsRcs;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Phone & Calls'**
  String get phoneCalls;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Mail'**
  String get mail;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Calendar & Reminders'**
  String get calendarReminders;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Banking Apps'**
  String get bankingApps;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'BULLETIN RELAY TEST'**
  String get bulletinRelayTest;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Send a test notification to Apple Watch over the secure com.apple.private.alloy.bulletindistributor IDS channel.'**
  String get sendATestNotificationToAppleWatchOverThe;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Send Test Notification'**
  String get sendTestNotification;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Simulated Telegram / SMS with Taptic feedback'**
  String get simulatedTelegramSmsWithTapticFeedback;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Test Message'**
  String get testMessage;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Android ↔ Apple Watch connection is working!'**
  String get androidAppleWatchConnectionIsWorking;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Notification sent'**
  String get notificationSent;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'BulletinRequest was handed to the watch protocol stack.'**
  String get bulletinrequestWasHandedToTheWatchProtocolStack;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Change Passcode'**
  String get changePasscode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Simple Passcode (4 digits)'**
  String get simplePasscode4Digits;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Unlock with Phone'**
  String get unlockWithPhone;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Unlocking your phone automatically unlocks your trusted Apple Watch when worn.'**
  String get unlockingYourPhoneAutomaticallyUnlocksYourTrustedAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'WRIST DETECTION'**
  String get wristDetection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Lock Apple Watch when you take it off to protect your data and Apple Pay cards.'**
  String get lockAppleWatchWhenYouTakeItOffTo;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Wrist Detection'**
  String get wristDetection2;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'DATA SECURITY'**
  String get dataSecurity;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Erase all Apple Watch data after 10 failed passcode attempts.'**
  String get eraseAllAppleWatchDataAfter10FailedPasscode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Erase Data (10 attempts)'**
  String get eraseData10Attempts;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Automatic Updates'**
  String get automaticUpdates;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Beta Updates'**
  String get betaUpdates;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Checking for updates…'**
  String get checkingForUpdates;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Your Apple Watch software is up to date.'**
  String get yourAppleWatchSoftwareIsUpToDate;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Check Again'**
  String get checkAgain;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'ALERT VOLUME'**
  String get alertVolume;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Silent Mode'**
  String get silentMode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'HAPTIC FEEDBACK'**
  String get hapticFeedback;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Haptic Alerts'**
  String get hapticAlerts;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Default'**
  String get defaultHaptic;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Prominent'**
  String get prominent;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Play an additional haptic before a notification.'**
  String get playAnAdditionalHapticBeforeANotification;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'SYSTEM FEEDBACK'**
  String get systemFeedback;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Crown Haptics'**
  String get crownHaptics;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'System Haptics'**
  String get systemHaptics;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Cover to Mute'**
  String get coverToMute;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Cover the display with your palm for at least 3 seconds to mute an incoming call or alert.'**
  String get coverTheDisplayWithYourPalmForAtLeast;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'WATCH STORAGE'**
  String get watchStorage;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Reclaimable'**
  String get reclaimable;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Purgeable Data'**
  String get purgeableData;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'WATCH CONTENT'**
  String get watchContent;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Point the Camera'**
  String get pointTheCamera;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Keep Apple Watch in the viewfinder'**
  String get keepAppleWatchInTheViewfinder;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Place the Apple Watch screen in the center of the frame to recognize the pairing animation automatically.'**
  String get placeTheAppleWatchScreenInTheCenterOf;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Pair Apple Watch Manually'**
  String get pairAppleWatchManually;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Pair Manually'**
  String get pairManually;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Enter the 6-digit Code'**
  String get enterThe6DigitCode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Tap the “i” icon on your Apple Watch to see the pairing code.'**
  String get tapTheIIconOnYourAppleWatchTo;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Sync complete!'**
  String get syncComplete;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Syncing Apple Watch'**
  String get syncingAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Set Up Apple Watch'**
  String get setUpAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Keep Bluetooth on and your watch close to your phone.'**
  String get keepBluetoothOnAndYourWatchCloseToYour;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Connect Apple Watch to your Android phone to sync notifications, calls, and workouts.'**
  String get connectAppleWatchToYourAndroidPhoneToSync;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Pair Apple Watch'**
  String get pairAppleWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get continueAction;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Finish Setup'**
  String get finishSetup;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Which wrist do you wear your watch on?'**
  String get whichWristDoYouWearYourWatchOn;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Apple Watch adjusts wrist raise and gesture recognition for your selected wrist.'**
  String get appleWatchAdjustsWristRaiseAndGestureRecognitionFor;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Digital Crown Position'**
  String get digitalCrownPosition;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Choose which side the Digital Crown is on.'**
  String get chooseWhichSideTheDigitalCrownIsOn;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Digital Crown on Right'**
  String get digitalCrownOnRight;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Digital Crown on Left'**
  String get digitalCrownOnLeft;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Always-On Display'**
  String get alwaysOnDisplay;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'The Always-On Retina display shows the time and important information without raising your wrist.'**
  String get theAlwaysOnRetinaDisplayShowsTheTimeAnd;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Enable Always-On Display'**
  String get enableAlwaysOnDisplay;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Create a Passcode'**
  String get createAPasscode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'A passcode protects your data, Apple Pay cards, and medical information.'**
  String get aPasscodeProtectsYourDataApplePayCardsAnd;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Add a 4-digit Passcode'**
  String get addA4DigitPasscode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Titanium'**
  String get titanium;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Sunny'**
  String get sunny;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Exclusive Apple Watch Ultra faces with a customizable bezel, Night Mode, and 8 complications.'**
  String get exclusiveAppleWatchUltraFacesWithACustomizableBezel;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Modular'**
  String get modular;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Large information blocks and graphs for data at a glance.'**
  String get largeInformationBlocksAndGraphsForDataAtA;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Classic'**
  String get classic;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Traditional analog faces with Roman and Arabic numerals.'**
  String get traditionalAnalogFacesWithRomanAndArabicNumerals;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Infograph & Astronomy'**
  String get infographAstronomy;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Detailed astronomical and weather displays.'**
  String get detailedAstronomicalAndWeatherDisplays;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Activity & Nike'**
  String get activityNike;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Activity rings, workouts, and sports metrics.'**
  String get activityRingsWorkoutsAndSportsMetrics;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Wayfinder Alpine'**
  String get wayfinderAlpine;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Wayfinder Night Mode'**
  String get wayfinderNightMode;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Modular Duo'**
  String get modularDuo;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Modular Multicolor'**
  String get modularMulticolor;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'California'**
  String get california;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'California Ocean'**
  String get californiaOcean;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Chronograph Pro'**
  String get chronographPro;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Infograph'**
  String get infograph;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Solar Dial'**
  String get solarDial;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Activity Analog'**
  String get activityAnalog;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Modular Ultra'**
  String get modularUltra;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Activity Digital'**
  String get activityDigital;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Selection confirmed by the watch.'**
  String get selectionConfirmedByTheWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Collection received from the watch.'**
  String get collectionReceivedFromTheWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Incomplete collection received. Refresh again.'**
  String get incompleteCollectionReceivedRefreshAgain;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Connection changed. Refresh again.'**
  String get connectionChangedRefreshAgain;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Waiting for the watch face collection…'**
  String get waitingForTheWatchFaceCollection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Request not sent. Check the connection.'**
  String get requestNotSentCheckTheConnection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'New collection not received yet. Refresh again.'**
  String get newCollectionNotReceivedYetRefreshAgain;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Waiting for the new watch face…'**
  String get waitingForTheNewWatchFace;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Waiting for the selected watch face…'**
  String get waitingForTheSelectedWatchFace;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Command not sent. Refresh the watch face collection.'**
  String get commandNotSentRefreshTheWatchFaceCollection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Result not confirmed. Refresh the watch face collection.'**
  String get resultNotConfirmedRefreshTheWatchFaceCollection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Last sync: {value0}.'**
  String lastSync(String value0);

  /// Heading when the watch face collection size is unknown.
  ///
  /// In en, this message translates to:
  /// **'My Faces'**
  String get myFaces;

  /// Heading with the size of the complete watch face collection.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =0{My Faces · 0} =1{My Faces · 1} other{My Faces · {count}}}'**
  String myFacesCount(int count);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Collection not received yet'**
  String get collectionNotReceivedYet;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Partial collection received'**
  String get partialCollectionReceived;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Collection is empty'**
  String get collectionIsEmpty;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Face {value0}'**
  String face(String value0);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Selected on watch'**
  String get selectedOnWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Create Copy'**
  String get createCopy;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Refresh from Watch'**
  String get refreshFromWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Enable'**
  String get enable;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Disable'**
  String get disable;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Command sent. Waiting for the watch result.'**
  String get commandSentWaitingForTheWatchResult;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Command not sent. Check the connection.'**
  String get commandNotSentCheckTheConnection;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'WATCH SETTINGS'**
  String get watchSettings;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Values come from watch messages. “Not received” means the watch has not reported this setting yet.'**
  String get valuesComeFromWatchMessagesNotReceivedMeansThe;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Connect your watch to change settings.'**
  String get connectYourWatchToChangeSettings;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Stop not confirmed'**
  String get stopNotConfirmed;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Could not open flashlight permission'**
  String get couldNotOpenFlashlightPermission;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Phone service unavailable'**
  String get phoneServiceUnavailable;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Alert status is stale'**
  String get alertStatusIsStale;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Flashlight is on'**
  String get flashlightIsOn;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Sound and flashlight are on'**
  String get soundAndFlashlightAreOn;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Sound alert started'**
  String get soundAlertStarted;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Last alert did not start'**
  String get lastAlertDidNotStart;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Request had no sound or flashlight'**
  String get requestHadNoSoundOrFlashlight;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Alert stopped'**
  String get alertStopped;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'No alert is running'**
  String get noAlertIsRunning;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Find Phone from Watch'**
  String get findPhoneFromWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Local APK Test'**
  String get localApkTest;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Stopping…'**
  String get stopping;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Stop Alert'**
  String get stopAlert;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Camera permission is required to find your phone with the flashlight.'**
  String get cameraPermissionIsRequiredToFindYourPhoneWith;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Allow Flashlight'**
  String get allowFlashlight;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'INCLINE 42°'**
  String get incline42;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'NORTH 124°'**
  String get north124;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'182 M'**
  String get message182M;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'ACTIVITY'**
  String get activity2;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'620 / 800 KCAL'**
  String get message620800Kcal;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'TODAY'**
  String get today;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'19:30 MEETING'**
  String get message1930Meeting;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Watch Sync'**
  String get watchSync;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'72 BPM • 5 min ago'**
  String get message72Bpm5Minago;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'The watch has not replied yet. Refresh again.'**
  String get theWatchHasNotRepliedYetRefreshAgain;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Data updated'**
  String get dataUpdated;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Could not send request'**
  String get couldNotSendRequest;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'No watch data yet'**
  String get noWatchDataYet;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0} · {value1}:{value2}:{value3}'**
  String telemetryStatusTime(
    String value0,
    String value1,
    String value2,
    String value3,
  );

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'charging unknown'**
  String get chargingUnknown;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'charging'**
  String get charging;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'not charging'**
  String get notCharging;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Battery: —'**
  String get battery2;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'{value0}: {value1}% · {value2}'**
  String batteryReading(String value0, String value1, String value2);

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'No connection to watch'**
  String get noConnectionToWatch;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Waiting for watch reply…'**
  String get waitingForWatchReply;

  /// Application UI text.
  ///
  /// In en, this message translates to:
  /// **'Refresh Data'**
  String get refreshData;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Updated'**
  String get updated;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Data is stale'**
  String get dataIsStale;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Battery'**
  String get battery3;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Last battery'**
  String get lastBattery;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'LIVE'**
  String get live;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'ALWAYS ON'**
  String get alwaysOn2;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Bluetooth'**
  String get bluetooth;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Wi-Fi'**
  String get wiFi;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Digital Crown'**
  String get digitalCrown;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Apple Watch Ultra 2 Action Button'**
  String get appleWatchUltra2ActionButton;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Apple Watch'**
  String get appleWatch;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Watch'**
  String get watch;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Ultra'**
  String get ultra;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Wayfinder'**
  String get wayfinder;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Ultra Modular'**
  String get ultraModular;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Ultra Modular Trail'**
  String get ultraModularTrail;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Nike Bounce Volt'**
  String get nikeBounceVolt;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Apple Music'**
  String get appleMusic;

  /// Application UI label.
  ///
  /// In en, this message translates to:
  /// **'Apple Account'**
  String get appleAccount;

  /// No description provided for @watchConnection.
  ///
  /// In en, this message translates to:
  /// **'Watch connection'**
  String get watchConnection;

  /// No description provided for @savedWatch.
  ///
  /// In en, this message translates to:
  /// **'Saved watch'**
  String get savedWatch;

  /// No description provided for @noPairedWatch.
  ///
  /// In en, this message translates to:
  /// **'No paired watch'**
  String get noPairedWatch;

  /// No description provided for @noPairedWatchHint.
  ///
  /// In en, this message translates to:
  /// **'Keep your watch nearby and put it in pairing mode to begin.'**
  String get noPairedWatchHint;

  /// No description provided for @bridgeUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Bridge is unavailable'**
  String get bridgeUnavailable;

  /// No description provided for @bridgeUnavailableHint.
  ///
  /// In en, this message translates to:
  /// **'Install the matching Bridge backend to connect your watch.'**
  String get bridgeUnavailableHint;

  /// No description provided for @identityLoading.
  ///
  /// In en, this message translates to:
  /// **'Reading saved pairing…'**
  String get identityLoading;

  /// No description provided for @identityReadError.
  ///
  /// In en, this message translates to:
  /// **'The saved pairing is unavailable. Check diagnostics before starting a new pair.'**
  String get identityReadError;

  /// No description provided for @connectWatch.
  ///
  /// In en, this message translates to:
  /// **'Connect'**
  String get connectWatch;

  /// No description provided for @disconnectWatch.
  ///
  /// In en, this message translates to:
  /// **'Disconnect'**
  String get disconnectWatch;

  /// No description provided for @connectionConnecting.
  ///
  /// In en, this message translates to:
  /// **'Connecting…'**
  String get connectionConnecting;

  /// No description provided for @connectionStopping.
  ///
  /// In en, this message translates to:
  /// **'Disconnecting…'**
  String get connectionStopping;

  /// No description provided for @connectionPermissions.
  ///
  /// In en, this message translates to:
  /// **'Allow Bluetooth access'**
  String get connectionPermissions;

  /// No description provided for @pairingRunning.
  ///
  /// In en, this message translates to:
  /// **'Setup in progress'**
  String get pairingRunning;

  /// No description provided for @resumeWatchSetup.
  ///
  /// In en, this message translates to:
  /// **'Continue setup'**
  String get resumeWatchSetup;

  /// No description provided for @finishWatchSetup.
  ///
  /// In en, this message translates to:
  /// **'Finish setup'**
  String get finishWatchSetup;

  /// No description provided for @watchStillSettingUp.
  ///
  /// In en, this message translates to:
  /// **'My watch still shows setup'**
  String get watchStillSettingUp;

  /// No description provided for @resumeWatchSync.
  ///
  /// In en, this message translates to:
  /// **'Resume synchronization'**
  String get resumeWatchSync;

  /// No description provided for @resumeWatchSyncHint.
  ///
  /// In en, this message translates to:
  /// **'Use this only if your watch still displays setup. If its watch face is visible, finish setup instead.'**
  String get resumeWatchSyncHint;

  /// No description provided for @confirmWatchFace.
  ///
  /// In en, this message translates to:
  /// **'The watch face is visible'**
  String get confirmWatchFace;

  /// No description provided for @confirmWatchFaceHint.
  ///
  /// In en, this message translates to:
  /// **'Confirm only after your watch shows its watch face. The current setup connection will be stopped safely.'**
  String get confirmWatchFaceHint;

  /// No description provided for @confirmWatchFaceQuestion.
  ///
  /// In en, this message translates to:
  /// **'Does your watch show its watch face?'**
  String get confirmWatchFaceQuestion;

  /// No description provided for @confirmWatchFaceDetail.
  ///
  /// In en, this message translates to:
  /// **'This saves normal connection mode for the current activated pair.'**
  String get confirmWatchFaceDetail;

  /// No description provided for @connectionRequestAccepted.
  ///
  /// In en, this message translates to:
  /// **'Request accepted. Waiting for the watch…'**
  String get connectionRequestAccepted;

  /// No description provided for @connectionRequestRejected.
  ///
  /// In en, this message translates to:
  /// **'Request not accepted. Check the current connection and diagnostics.'**
  String get connectionRequestRejected;

  /// No description provided for @connectionPermissionRequired.
  ///
  /// In en, this message translates to:
  /// **'Allow Bluetooth access, then try again.'**
  String get connectionPermissionRequired;

  /// No description provided for @connectionBusy.
  ///
  /// In en, this message translates to:
  /// **'A connection or setup operation is already running.'**
  String get connectionBusy;

  /// No description provided for @syncCurrentWifi.
  ///
  /// In en, this message translates to:
  /// **'Send phone Wi-Fi network'**
  String get syncCurrentWifi;

  /// No description provided for @wifiReceiptHint.
  ///
  /// In en, this message translates to:
  /// **'The network is queued for delivery. Verify it in Settings → Wi-Fi on your watch.'**
  String get wifiReceiptHint;

  /// No description provided for @watchDiagnostics.
  ///
  /// In en, this message translates to:
  /// **'Connection diagnostics'**
  String get watchDiagnostics;

  /// No description provided for @bridgeJournal.
  ///
  /// In en, this message translates to:
  /// **'Bridge journal'**
  String get bridgeJournal;

  /// No description provided for @copyBridgeJournal.
  ///
  /// In en, this message translates to:
  /// **'Copy journal'**
  String get copyBridgeJournal;

  /// No description provided for @journalCopied.
  ///
  /// In en, this message translates to:
  /// **'Journal copied'**
  String get journalCopied;

  /// No description provided for @advancedConnectionTools.
  ///
  /// In en, this message translates to:
  /// **'Advanced connection tools'**
  String get advancedConnectionTools;

  /// No description provided for @stockBondAudit.
  ///
  /// In en, this message translates to:
  /// **'Audit system Bluetooth bond'**
  String get stockBondAudit;

  /// No description provided for @stockBondImport.
  ///
  /// In en, this message translates to:
  /// **'Import system Bluetooth bond'**
  String get stockBondImport;

  /// No description provided for @stockIdentityAlignment.
  ///
  /// In en, this message translates to:
  /// **'Align system Bluetooth identity'**
  String get stockIdentityAlignment;

  /// No description provided for @stockReconnectProbe.
  ///
  /// In en, this message translates to:
  /// **'Test system reconnect'**
  String get stockReconnectProbe;

  /// No description provided for @advancedToolHint.
  ///
  /// In en, this message translates to:
  /// **'These tools require the connection to be stopped and system Bluetooth to be off.'**
  String get advancedToolHint;

  /// No description provided for @advancedToolQuestion.
  ///
  /// In en, this message translates to:
  /// **'Run this connection tool?'**
  String get advancedToolQuestion;

  /// No description provided for @advancedToolDetail.
  ///
  /// In en, this message translates to:
  /// **'The backend validates the saved pair and Bluetooth state before running.'**
  String get advancedToolDetail;

  /// No description provided for @pairingDiscovering.
  ///
  /// In en, this message translates to:
  /// **'Looking for a watch…'**
  String get pairingDiscovering;

  /// No description provided for @pairingConnecting.
  ///
  /// In en, this message translates to:
  /// **'Connecting to the discovered watch…'**
  String get pairingConnecting;

  /// No description provided for @pairingSecurity.
  ///
  /// In en, this message translates to:
  /// **'Establishing a secure pair…'**
  String get pairingSecurity;

  /// No description provided for @pairingActivation.
  ///
  /// In en, this message translates to:
  /// **'Activating with Apple…'**
  String get pairingActivation;

  /// No description provided for @pairingIds.
  ///
  /// In en, this message translates to:
  /// **'Connecting watch services…'**
  String get pairingIds;

  /// No description provided for @pairingRegistry.
  ///
  /// In en, this message translates to:
  /// **'Reading watch configuration…'**
  String get pairingRegistry;

  /// No description provided for @pairingConfiguring.
  ///
  /// In en, this message translates to:
  /// **'Applying watch settings…'**
  String get pairingConfiguring;

  /// No description provided for @pairingActivated.
  ///
  /// In en, this message translates to:
  /// **'Watch activated'**
  String get pairingActivated;

  /// No description provided for @pairingCheckWatch.
  ///
  /// In en, this message translates to:
  /// **'Initial synchronization sent. Check your watch.'**
  String get pairingCheckWatch;

  /// No description provided for @pairingVerifyReconnect.
  ///
  /// In en, this message translates to:
  /// **'Watch setup observed. Connection verification pending.'**
  String get pairingVerifyReconnect;

  /// No description provided for @pairingPaused.
  ///
  /// In en, this message translates to:
  /// **'Setup connection stopped'**
  String get pairingPaused;

  /// No description provided for @watchActivatedHint.
  ///
  /// In en, this message translates to:
  /// **'Activation is complete. Confirm below when your watch shows its watch face.'**
  String get watchActivatedHint;

  /// No description provided for @confirmWatchFaceActiveDetail.
  ///
  /// In en, this message translates to:
  /// **'This will disconnect the setup session safely and save normal connection mode for this activated pair.'**
  String get confirmWatchFaceActiveDetail;

  /// No description provided for @pairingSync.
  ///
  /// In en, this message translates to:
  /// **'Synchronizing watch setup…'**
  String get pairingSync;

  /// No description provided for @pairingVerified.
  ///
  /// In en, this message translates to:
  /// **'Watch setup complete'**
  String get pairingVerified;

  /// No description provided for @pairingFailed.
  ///
  /// In en, this message translates to:
  /// **'The setup session ended with an error. Check the journal.'**
  String get pairingFailed;

  /// No description provided for @pairingIdle.
  ///
  /// In en, this message translates to:
  /// **'No setup session is running.'**
  String get pairingIdle;

  /// No description provided for @pairingStarting.
  ///
  /// In en, this message translates to:
  /// **'Starting the watch session…'**
  String get pairingStarting;

  /// No description provided for @pairingPinHint.
  ///
  /// In en, this message translates to:
  /// **'Enter the six-digit code displayed on your watch.'**
  String get pairingPinHint;

  /// No description provided for @pairingCheckingCode.
  ///
  /// In en, this message translates to:
  /// **'Checking the watch code…'**
  String get pairingCheckingCode;

  /// No description provided for @pairingCodeVerifiedBondPending.
  ///
  /// In en, this message translates to:
  /// **'Watch code verified. Bluetooth bonding is not available in this build yet.'**
  String get pairingCodeVerifiedBondPending;

  /// No description provided for @macosPairingBootstrapRestricted.
  ///
  /// In en, this message translates to:
  /// **'macOS restricts the Bluetooth channel required for watch pairing. See Diagnostics for details.'**
  String get macosPairingBootstrapRestricted;

  /// No description provided for @windowsPairingBootstrapRestricted.
  ///
  /// In en, this message translates to:
  /// **'Bluetooth discovery and connection are available. Windows does not expose the channel required for Apple Watch pairing in this build.'**
  String get windowsPairingBootstrapRestricted;

  /// No description provided for @windowsRawHciRequired.
  ///
  /// In en, this message translates to:
  /// **'Full Watch pairing requires the bundled protocol runtime and signed UsbDk 1.0.21. Version 1.0.22 is blocked because it can crash Windows. Select a USB Bluetooth adapter; it is reserved for the Watch during the session.'**
  String get windowsRawHciRequired;

  /// No description provided for @sendWatchPin.
  ///
  /// In en, this message translates to:
  /// **'Send code'**
  String get sendWatchPin;

  /// No description provided for @pairingCodeOnlyHint.
  ///
  /// In en, this message translates to:
  /// **'On this computer, pair using the six-digit code shown on your watch.'**
  String get pairingCodeOnlyHint;

  /// No description provided for @findNearbyWatch.
  ///
  /// In en, this message translates to:
  /// **'Find a Watch'**
  String get findNearbyWatch;

  /// No description provided for @bluetoothLinkConnected.
  ///
  /// In en, this message translates to:
  /// **'Bluetooth link connected'**
  String get bluetoothLinkConnected;

  /// No description provided for @watchPairingUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Watch pairing services are not available on this platform yet.'**
  String get watchPairingUnavailable;

  /// No description provided for @activationOwnerInput.
  ///
  /// In en, this message translates to:
  /// **'Apple requires owner confirmation'**
  String get activationOwnerInput;

  /// No description provided for @activationCredentialsHint.
  ///
  /// In en, this message translates to:
  /// **'Credentials are sent to Apple\'s activation service and are not saved by Companion.'**
  String get activationCredentialsHint;

  /// No description provided for @submitActivation.
  ///
  /// In en, this message translates to:
  /// **'Continue activation'**
  String get submitActivation;

  /// No description provided for @retryActivation.
  ///
  /// In en, this message translates to:
  /// **'Retry activation'**
  String get retryActivation;

  /// No description provided for @stopSetup.
  ///
  /// In en, this message translates to:
  /// **'Stop setup'**
  String get stopSetup;

  /// No description provided for @pairedIdentity.
  ///
  /// In en, this message translates to:
  /// **'Pairing ID'**
  String get pairedIdentity;

  /// No description provided for @hardwareIdentity.
  ///
  /// In en, this message translates to:
  /// **'Hardware identifier'**
  String get hardwareIdentity;

  /// No description provided for @systemBuild.
  ///
  /// In en, this message translates to:
  /// **'System build'**
  String get systemBuild;

  /// No description provided for @pendingWatchSetup.
  ///
  /// In en, this message translates to:
  /// **'This saved pair has not finished setup.'**
  String get pendingWatchSetup;

  /// No description provided for @oneWatchBackend.
  ///
  /// In en, this message translates to:
  /// **'The backend currently stores one pairing. A saved pair is retained when disconnected.'**
  String get oneWatchBackend;

  /// No description provided for @finishInitialSync.
  ///
  /// In en, this message translates to:
  /// **'Finish initial synchronization'**
  String get finishInitialSync;

  /// No description provided for @chooseDiscoveredWatch.
  ///
  /// In en, this message translates to:
  /// **'Choose a watch found nearby. Only supported setup advertisements are listed.'**
  String get chooseDiscoveredWatch;

  /// No description provided for @desktopCoreUnavailable.
  ///
  /// In en, this message translates to:
  /// **'The connection service could not start. See Diagnostics for details.'**
  String get desktopCoreUnavailable;

  /// No description provided for @manageWatchFaces.
  ///
  /// In en, this message translates to:
  /// **'Manage Watch Faces'**
  String get manageWatchFaces;

  /// No description provided for @nativeFaceChangesConfirmed.
  ///
  /// In en, this message translates to:
  /// **'Changes confirmed by the watch.'**
  String get nativeFaceChangesConfirmed;

  /// No description provided for @nativeFaceWaiting.
  ///
  /// In en, this message translates to:
  /// **'Waiting for the watch to confirm changes…'**
  String get nativeFaceWaiting;

  /// No description provided for @nativeFaceSaving.
  ///
  /// In en, this message translates to:
  /// **'Saving changes…'**
  String get nativeFaceSaving;

  /// No description provided for @nativeFaceRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry'**
  String get nativeFaceRetry;

  /// No description provided for @nativeFaceEditPending.
  ///
  /// In en, this message translates to:
  /// **'Changes saved on this device. Waiting for the watch…'**
  String get nativeFaceEditPending;

  /// No description provided for @nativeFaceSaveFailed.
  ///
  /// In en, this message translates to:
  /// **'Changes could not be saved on this device. Keep this editor open and retry.'**
  String get nativeFaceSaveFailed;

  /// No description provided for @nativeFaceEditUncertain.
  ///
  /// In en, this message translates to:
  /// **'Changes are saved on this device, but the watch has not confirmed them. Retry to send them again.'**
  String get nativeFaceEditUncertain;

  /// No description provided for @nativeFaceRejected.
  ///
  /// In en, this message translates to:
  /// **'Changes were not sent. Refresh the collection and try again.'**
  String get nativeFaceRejected;

  /// No description provided for @nativeFaceUnknown.
  ///
  /// In en, this message translates to:
  /// **'The result is not confirmed. Refresh before making another change.'**
  String get nativeFaceUnknown;

  /// No description provided for @nativeFaceImportHint.
  ///
  /// In en, this message translates to:
  /// **'Choose a native .watchface package, including bundled photos and resources (up to 16 MiB). Compatibility is confirmed by the connected watch.'**
  String get nativeFaceImportHint;

  /// No description provided for @nativeFaceCustomization.
  ///
  /// In en, this message translates to:
  /// **'Customization'**
  String get nativeFaceCustomization;

  /// No description provided for @nativeFaceComplications.
  ///
  /// In en, this message translates to:
  /// **'Complications'**
  String get nativeFaceComplications;

  /// No description provided for @nativeFaceSetActive.
  ///
  /// In en, this message translates to:
  /// **'Set as Current Face'**
  String get nativeFaceSetActive;

  /// No description provided for @nativeFaceDelete.
  ///
  /// In en, this message translates to:
  /// **'Delete Watch Face'**
  String get nativeFaceDelete;

  /// No description provided for @nativeFaceDeleteQuestion.
  ///
  /// In en, this message translates to:
  /// **'Delete this watch face?'**
  String get nativeFaceDeleteQuestion;

  /// No description provided for @nativeFaceDeleteDetail.
  ///
  /// In en, this message translates to:
  /// **'The remaining faces will stay on the watch. The last face cannot be deleted.'**
  String get nativeFaceDeleteDetail;

  /// No description provided for @nativeFaceMoveEarlier.
  ///
  /// In en, this message translates to:
  /// **'Move Earlier'**
  String get nativeFaceMoveEarlier;

  /// No description provided for @nativeFaceMoveLater.
  ///
  /// In en, this message translates to:
  /// **'Move Later'**
  String get nativeFaceMoveLater;

  /// No description provided for @nativeFaceConfiguration.
  ///
  /// In en, this message translates to:
  /// **'Native Configuration'**
  String get nativeFaceConfiguration;

  /// No description provided for @nativeFaceConfigurationHint.
  ///
  /// In en, this message translates to:
  /// **'These values come from the watch. Keep unknown fields intact. Available values and complication support depend on the face and watchOS.'**
  String get nativeFaceConfigurationHint;

  /// No description provided for @nativeFaceApply.
  ///
  /// In en, this message translates to:
  /// **'Apply to Watch'**
  String get nativeFaceApply;

  /// No description provided for @nativeFaceEditValue.
  ///
  /// In en, this message translates to:
  /// **'Edit Value'**
  String get nativeFaceEditValue;

  /// No description provided for @nativeFaceValueHint.
  ///
  /// In en, this message translates to:
  /// **'Enter a JSON value. Strings must be quoted; numbers and booleans use JSON notation.'**
  String get nativeFaceValueHint;

  /// No description provided for @nativeFaceInvalidValue.
  ///
  /// In en, this message translates to:
  /// **'Invalid configuration value'**
  String get nativeFaceInvalidValue;

  /// No description provided for @nativeFaceConfigurationUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Update Bridge and refresh the collection to load this face’s configuration.'**
  String get nativeFaceConfigurationUnavailable;

  /// No description provided for @nativeFaceFileSaved.
  ///
  /// In en, this message translates to:
  /// **'Watch face file saved'**
  String get nativeFaceFileSaved;

  /// No description provided for @nativeFaceFileError.
  ///
  /// In en, this message translates to:
  /// **'Could not open or save the watch face file'**
  String get nativeFaceFileError;

  /// No description provided for @nativeFaceResourceExportUnavailable.
  ///
  /// In en, this message translates to:
  /// **'This face may need photos or other resources. Export is unavailable until the complete native package can be read.'**
  String get nativeFaceResourceExportUnavailable;

  /// No description provided for @nativeFaceLocalLayoutHint.
  ///
  /// In en, this message translates to:
  /// **'This gallery contains local preview layouts. Native watch faces are managed in My Watch → Manage Watch Faces.'**
  String get nativeFaceLocalLayoutHint;

  /// No description provided for @nativeFaceComplicationHint.
  ///
  /// In en, this message translates to:
  /// **'Choose a compatible complication from the watch\'s catalog or an existing face. Saved settings and native intents are preserved.'**
  String get nativeFaceComplicationHint;

  /// No description provided for @nativeFaceComplicationCatalogPartial.
  ///
  /// In en, this message translates to:
  /// **'Some descriptors are unavailable. Refresh the collection to update compatible choices.'**
  String get nativeFaceComplicationCatalogPartial;

  /// No description provided for @nativeFaceComplicationVariantsMissing.
  ///
  /// In en, this message translates to:
  /// **'Some complications on these faces have not supplied their available variants. You can still use their current settings.'**
  String get nativeFaceComplicationVariantsMissing;

  /// No description provided for @nativeFaceNoComplication.
  ///
  /// In en, this message translates to:
  /// **'None'**
  String get nativeFaceNoComplication;

  /// No description provided for @nativeFaceUnknownComplication.
  ///
  /// In en, this message translates to:
  /// **'Unknown complication'**
  String get nativeFaceUnknownComplication;

  /// No description provided for @nativeFaceConflict.
  ///
  /// In en, this message translates to:
  /// **'This face changed or the watch reconnected. Open the face again before applying edits.'**
  String get nativeFaceConflict;

  /// No description provided for @exportLocalFaceLayout.
  ///
  /// In en, this message translates to:
  /// **'Export Local Layout'**
  String get exportLocalFaceLayout;

  /// No description provided for @localFaceLayoutSaved.
  ///
  /// In en, this message translates to:
  /// **'Local layout saved'**
  String get localFaceLayoutSaved;

  /// No description provided for @localFaceLayoutSavedHint.
  ///
  /// In en, this message translates to:
  /// **'This .watchlayout file contains a Companion preview layout. It is not an installable native watch face.'**
  String get localFaceLayoutSavedHint;

  /// No description provided for @nativeFaceTimeStyle.
  ///
  /// In en, this message translates to:
  /// **'Time Style'**
  String get nativeFaceTimeStyle;

  /// No description provided for @nativeFaceDialStyle.
  ///
  /// In en, this message translates to:
  /// **'Dial Style'**
  String get nativeFaceDialStyle;

  /// No description provided for @nativeFaceWaypoint.
  ///
  /// In en, this message translates to:
  /// **'Waypoint'**
  String get nativeFaceWaypoint;

  /// No description provided for @nativeFacePink.
  ///
  /// In en, this message translates to:
  /// **'Pink'**
  String get nativeFacePink;

  /// No description provided for @nativeFaceNeonGreen.
  ///
  /// In en, this message translates to:
  /// **'Neon Green'**
  String get nativeFaceNeonGreen;

  /// No description provided for @nativeFaceOrange.
  ///
  /// In en, this message translates to:
  /// **'Orange'**
  String get nativeFaceOrange;

  /// No description provided for @nativeFacePinkSand.
  ///
  /// In en, this message translates to:
  /// **'Pink / Sand'**
  String get nativeFacePinkSand;

  /// No description provided for @nativeFaceNeonGreenCloud.
  ///
  /// In en, this message translates to:
  /// **'Neon Green / Cloud'**
  String get nativeFaceNeonGreenCloud;

  /// No description provided for @nativeFaceOrangeLightSage.
  ///
  /// In en, this message translates to:
  /// **'Orange / Light Sage'**
  String get nativeFaceOrangeLightSage;

  /// No description provided for @nativeFaceLightBlue.
  ///
  /// In en, this message translates to:
  /// **'Light Blue'**
  String get nativeFaceLightBlue;

  /// No description provided for @nativeFaceBrightBlue.
  ///
  /// In en, this message translates to:
  /// **'Bright Blue'**
  String get nativeFaceBrightBlue;

  /// No description provided for @nativeFaceTerraCotta.
  ///
  /// In en, this message translates to:
  /// **'Terra Cotta'**
  String get nativeFaceTerraCotta;

  /// No description provided for @nativeFaceTopLeft.
  ///
  /// In en, this message translates to:
  /// **'Top Left'**
  String get nativeFaceTopLeft;

  /// No description provided for @nativeFaceTop.
  ///
  /// In en, this message translates to:
  /// **'Top'**
  String get nativeFaceTop;

  /// No description provided for @nativeFaceBottom.
  ///
  /// In en, this message translates to:
  /// **'Bottom'**
  String get nativeFaceBottom;

  /// No description provided for @nativeFaceTopRight.
  ///
  /// In en, this message translates to:
  /// **'Top Right'**
  String get nativeFaceTopRight;

  /// No description provided for @nativeFaceBottomLeft.
  ///
  /// In en, this message translates to:
  /// **'Bottom Left'**
  String get nativeFaceBottomLeft;

  /// No description provided for @nativeFaceBottomRight.
  ///
  /// In en, this message translates to:
  /// **'Bottom Right'**
  String get nativeFaceBottomRight;

  /// No description provided for @nativeFaceCenter.
  ///
  /// In en, this message translates to:
  /// **'Center'**
  String get nativeFaceCenter;

  /// No description provided for @nativeFaceNightMode.
  ///
  /// In en, this message translates to:
  /// **'Night Mode'**
  String get nativeFaceNightMode;

  /// No description provided for @nativeFaceColor.
  ///
  /// In en, this message translates to:
  /// **'Color'**
  String get nativeFaceColor;

  /// No description provided for @nativeFaceAutomatic.
  ///
  /// In en, this message translates to:
  /// **'Automatic'**
  String get nativeFaceAutomatic;

  /// No description provided for @nativeFaceLeft.
  ///
  /// In en, this message translates to:
  /// **'Left'**
  String get nativeFaceLeft;

  /// No description provided for @nativeFaceRight.
  ///
  /// In en, this message translates to:
  /// **'Right'**
  String get nativeFaceRight;

  /// No description provided for @nativeFacePalette.
  ///
  /// In en, this message translates to:
  /// **'Palette {number}'**
  String nativeFacePalette(String number);

  /// No description provided for @nativeFaceSeasonalPalette.
  ///
  /// In en, this message translates to:
  /// **'Seasonal {number}'**
  String nativeFaceSeasonalPalette(String number);

  /// No description provided for @nativeFaceAdvanced.
  ///
  /// In en, this message translates to:
  /// **'Advanced'**
  String get nativeFaceAdvanced;

  /// No description provided for @nativeFaceEditHint.
  ///
  /// In en, this message translates to:
  /// **'Choose your settings, then apply them to your watch.'**
  String get nativeFaceEditHint;

  /// No description provided for @nativeFaceCopyHint.
  ///
  /// In en, this message translates to:
  /// **'Create a new face from one on your watch, or import a shared .watchface file.'**
  String get nativeFaceCopyHint;

  /// No description provided for @nativeFaceLocalLibrary.
  ///
  /// In en, this message translates to:
  /// **'Local Design Library'**
  String get nativeFaceLocalLibrary;

  /// No description provided for @nativeFaceUnsaved.
  ///
  /// In en, this message translates to:
  /// **'Discard unsaved changes?'**
  String get nativeFaceUnsaved;

  /// No description provided for @nativeFaceDiscard.
  ///
  /// In en, this message translates to:
  /// **'Discard'**
  String get nativeFaceDiscard;

  /// No description provided for @nativeFaceImport.
  ///
  /// In en, this message translates to:
  /// **'Import Watch Face'**
  String get nativeFaceImport;

  /// No description provided for @nativeFaceSharedPreview.
  ///
  /// In en, this message translates to:
  /// **'Shared preview'**
  String get nativeFaceSharedPreview;

  /// No description provided for @nativeFaceImportDone.
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get nativeFaceImportDone;

  /// No description provided for @nativeFaceSharedPreviewUnavailable.
  ///
  /// In en, this message translates to:
  /// **'This file has no usable shared preview.'**
  String get nativeFaceSharedPreviewUnavailable;

  /// No description provided for @nativeFaceSharedPreviewHint.
  ///
  /// In en, this message translates to:
  /// **'Preview supplied with the file. Compatibility and successful installation are confirmed by your watch.'**
  String get nativeFaceSharedPreviewHint;

  /// No description provided for @nativeFaceDraft.
  ///
  /// In en, this message translates to:
  /// **'Unsaved changes'**
  String get nativeFaceDraft;

  /// No description provided for @nativeFaceDigital.
  ///
  /// In en, this message translates to:
  /// **'Digital'**
  String get nativeFaceDigital;

  /// No description provided for @nativeFaceAnalog.
  ///
  /// In en, this message translates to:
  /// **'Analog'**
  String get nativeFaceAnalog;

  /// No description provided for @nativeFaceAdd.
  ///
  /// In en, this message translates to:
  /// **'Add Watch Face'**
  String get nativeFaceAdd;

  /// No description provided for @nativeFaceAvailable.
  ///
  /// In en, this message translates to:
  /// **'Available Faces'**
  String get nativeFaceAvailable;

  /// No description provided for @nativeFaceAddToWatch.
  ///
  /// In en, this message translates to:
  /// **'Add to Watch'**
  String get nativeFaceAddToWatch;

  /// No description provided for @nativeFaceTemplateHint.
  ///
  /// In en, this message translates to:
  /// **'Choose colors and complications before adding this face to your watch.'**
  String get nativeFaceTemplateHint;

  /// No description provided for @nativeFaceDefaultPreview.
  ///
  /// In en, this message translates to:
  /// **'Default preview'**
  String get nativeFaceDefaultPreview;

  /// No description provided for @nativeFacePreviewUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Preview unavailable for these settings'**
  String get nativeFacePreviewUnavailable;

  /// No description provided for @nativeFaceStylePreview.
  ///
  /// In en, this message translates to:
  /// **'Style preview'**
  String get nativeFaceStylePreview;

  /// No description provided for @nativeFaceStylePreviewHint.
  ///
  /// In en, this message translates to:
  /// **'Sample time. Live data and complications appear on your watch.'**
  String get nativeFaceStylePreviewHint;

  /// No description provided for @nativeFaceSearch.
  ///
  /// In en, this message translates to:
  /// **'Search'**
  String get nativeFaceSearch;

  /// No description provided for @nativePhotoChoose.
  ///
  /// In en, this message translates to:
  /// **'Choose Photo'**
  String get nativePhotoChoose;

  /// No description provided for @nativePhotoCropHint.
  ///
  /// In en, this message translates to:
  /// **'Drag to frame your photo. Pinch or use the slider to zoom.'**
  String get nativePhotoCropHint;

  /// No description provided for @nativeIntentSettings.
  ///
  /// In en, this message translates to:
  /// **'Settings for {slot}'**
  String nativeIntentSettings(String slot);

  /// No description provided for @nativeIntentParameterSeconds.
  ///
  /// In en, this message translates to:
  /// **'{name} (seconds)'**
  String nativeIntentParameterSeconds(String name);

  /// No description provided for @nativeIntentDurationValue.
  ///
  /// In en, this message translates to:
  /// **'{name}: {seconds} s'**
  String nativeIntentDurationValue(String name, String seconds);

  /// No description provided for @nativeIntentParameterInvalid.
  ///
  /// In en, this message translates to:
  /// **'Enter a finite duration within its supported range.'**
  String get nativeIntentParameterInvalid;

  /// No description provided for @nativeIntentLocalDraft.
  ///
  /// In en, this message translates to:
  /// **'These settings are saved in your draft. Apply the face to send them to your watch.'**
  String get nativeIntentLocalDraft;

  /// No description provided for @nativePhotoZoom.
  ///
  /// In en, this message translates to:
  /// **'Zoom'**
  String get nativePhotoZoom;

  /// No description provided for @nativePhotoAspect.
  ///
  /// In en, this message translates to:
  /// **'Frame Shape'**
  String get nativePhotoAspect;

  /// No description provided for @nativePhotoKeepCrop.
  ///
  /// In en, this message translates to:
  /// **'Keep Crop'**
  String get nativePhotoKeepCrop;

  /// No description provided for @nativePhotoSelected.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 photo selected} other{{count} photos selected}}'**
  String nativePhotoSelected(int count);

  /// No description provided for @nativePhotoTimeAlignment.
  ///
  /// In en, this message translates to:
  /// **'Time Alignment'**
  String get nativePhotoTimeAlignment;

  /// No description provided for @nativePhotoLeading.
  ///
  /// In en, this message translates to:
  /// **'Leading'**
  String get nativePhotoLeading;

  /// No description provided for @nativePhotoTrailing.
  ///
  /// In en, this message translates to:
  /// **'Trailing'**
  String get nativePhotoTrailing;

  /// No description provided for @nativePhotoTimeSize.
  ///
  /// In en, this message translates to:
  /// **'Time Size'**
  String get nativePhotoTimeSize;

  /// No description provided for @nativePhotoSmall.
  ///
  /// In en, this message translates to:
  /// **'Small'**
  String get nativePhotoSmall;

  /// No description provided for @nativePhotoMedium.
  ///
  /// In en, this message translates to:
  /// **'Medium'**
  String get nativePhotoMedium;

  /// No description provided for @nativePhotoLarge.
  ///
  /// In en, this message translates to:
  /// **'Large'**
  String get nativePhotoLarge;

  /// No description provided for @nativePhotoXLarge.
  ///
  /// In en, this message translates to:
  /// **'XL'**
  String get nativePhotoXLarge;

  /// No description provided for @nativePhotoRemove.
  ///
  /// In en, this message translates to:
  /// **'Remove Photo'**
  String get nativePhotoRemove;

  /// No description provided for @nativeFaceShade.
  ///
  /// In en, this message translates to:
  /// **'Shade'**
  String get nativeFaceShade;

  /// No description provided for @nativeFaceAddColors.
  ///
  /// In en, this message translates to:
  /// **'Add Colors'**
  String get nativeFaceAddColors;

  /// No description provided for @nativeFaceColorsDone.
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get nativeFaceColorsDone;

  /// No description provided for @nativeFaceColorsSaveError.
  ///
  /// In en, this message translates to:
  /// **'Could not save colors. Try again.'**
  String get nativeFaceColorsSaveError;

  /// No description provided for @nativeFaceColorsPending.
  ///
  /// In en, this message translates to:
  /// **'Waiting for color settings from your Apple Watch.'**
  String get nativeFaceColorsPending;

  /// No description provided for @nativeFaceColorsAwaiting.
  ///
  /// In en, this message translates to:
  /// **'Sending colors to your Apple Watch.'**
  String get nativeFaceColorsAwaiting;

  /// No description provided for @nativeFaceColorsUncertain.
  ///
  /// In en, this message translates to:
  /// **'Your Apple Watch reported different colors.'**
  String get nativeFaceColorsUncertain;

  /// No description provided for @nativeFaceColorsSent.
  ///
  /// In en, this message translates to:
  /// **'Colors sent to your Apple Watch.'**
  String get nativeFaceColorsSent;

  /// No description provided for @nativeFaceColorsDeliveryUncertain.
  ///
  /// In en, this message translates to:
  /// **'Color delivery could not be confirmed.'**
  String get nativeFaceColorsDeliveryUncertain;

  /// No description provided for @nativeFaceColorsRetry.
  ///
  /// In en, this message translates to:
  /// **'Retry Color Sync'**
  String get nativeFaceColorsRetry;

  /// No description provided for @nativeFaceMonogram.
  ///
  /// In en, this message translates to:
  /// **'Monogram'**
  String get nativeFaceMonogram;

  /// No description provided for @nativeMonogramDone.
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get nativeMonogramDone;

  /// No description provided for @nativeMonogramInvalid.
  ///
  /// In en, this message translates to:
  /// **'This text cannot be used as a monogram.'**
  String get nativeMonogramInvalid;

  /// No description provided for @nativeMonogramUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Connect your Apple Watch to edit its monogram.'**
  String get nativeMonogramUnavailable;

  /// No description provided for @nativeMonogramSaveError.
  ///
  /// In en, this message translates to:
  /// **'Could not save the monogram. Try again.'**
  String get nativeMonogramSaveError;

  /// No description provided for @nativeMonogramAwaiting.
  ///
  /// In en, this message translates to:
  /// **'Sending the monogram to your Apple Watch.'**
  String get nativeMonogramAwaiting;

  /// No description provided for @nativeMonogramDelivered.
  ///
  /// In en, this message translates to:
  /// **'Monogram delivered. Waiting for confirmation from your Apple Watch.'**
  String get nativeMonogramDelivered;

  /// No description provided for @nativeMonogramUncertain.
  ///
  /// In en, this message translates to:
  /// **'Monogram sync could not be confirmed.'**
  String get nativeMonogramUncertain;

  /// No description provided for @nativeMonogramConfirmed.
  ///
  /// In en, this message translates to:
  /// **'Monogram confirmed by your Apple Watch.'**
  String get nativeMonogramConfirmed;

  /// No description provided for @nativeFaceSeeAllWatchFaces.
  ///
  /// In en, this message translates to:
  /// **'See All Watch Faces'**
  String get nativeFaceSeeAllWatchFaces;

  /// No description provided for @nativeFaceAllWatchFaces.
  ///
  /// In en, this message translates to:
  /// **'All Watch Faces'**
  String get nativeFaceAllWatchFaces;

  /// No description provided for @nativeFaceLibraryEdit.
  ///
  /// In en, this message translates to:
  /// **'Edit'**
  String get nativeFaceLibraryEdit;

  /// No description provided for @nativeFaceLibraryDone.
  ///
  /// In en, this message translates to:
  /// **'Done'**
  String get nativeFaceLibraryDone;

  /// No description provided for @nativeFaceLibraryReorder.
  ///
  /// In en, this message translates to:
  /// **'Reorder {face}'**
  String nativeFaceLibraryReorder(String face);
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
