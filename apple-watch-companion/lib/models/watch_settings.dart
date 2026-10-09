enum WristOrientation { left, right }

enum CrownOrientation { right, left }

enum HapticStrength { defaultStrength, prominent }

enum WakeDuration { seconds15, seconds70 }

enum ActionButtonTarget {
  workout,
  stopwatch,
  waypoint,
  backtrack,
  dive,
  flashlight,
  shortcut,
  none,
}

/// Comprehensive settings state mirroring iOS Watch.app preferences.
class WatchSettings {
  // General & Orientation
  final String watchName;
  final WristOrientation wristOrientation;
  final CrownOrientation crownOrientation;
  final bool airplaneMode;
  final bool bluetoothEnabled;
  final bool wifiEnabled;
  final bool backgroundAppRefresh;

  // Display & Brightness
  final double brightness; // 0.0 to 1.0
  final double textSize; // 0.0 to 1.0
  final bool boldText;
  final bool alwaysOnDisplay;
  final bool wakeOnWristRaise;
  final WakeDuration wakeDuration;

  // Sounds & Haptics
  final double alertVolume; // 0.0 to 1.0
  final bool silentMode;
  final bool hapticAlerts;
  final HapticStrength hapticStrength;
  final bool crownHaptics;
  final bool systemHaptics;
  final bool coverToMute;

  // Notifications
  final bool notificationIndicator;
  final bool notificationPrivacy;
  final bool showSummary;
  final Map<String, bool> appNotifications;

  // Passcode & Security
  final bool passcodeEnabled;
  final bool simplePasscode;
  final bool unlockWithPhone;
  final bool wristDetection;
  final bool eraseDataOnTenFails;

  // Health & Activity
  final bool standReminders;
  final bool dailyCoaching;
  final bool goalCompletions;
  final bool heartRateAlerts;
  final int highHeartRateThreshold;
  final int lowHeartRateThreshold;
  final bool bloodOxygenEnabled;
  final bool sleepTracking;

  // Ultra Exclusive: Action Button & Siren
  final ActionButtonTarget actionButtonTarget;
  final String actionButtonApp;
  final bool holdForSiren;

  // Emergency SOS & Safety
  final bool fallDetection;
  final bool crashDetection;

  const WatchSettings({
    this.watchName = 'Apple Watch',
    this.wristOrientation = WristOrientation.left,
    this.crownOrientation = CrownOrientation.right,
    this.airplaneMode = false,
    this.bluetoothEnabled = true,
    this.wifiEnabled = true,
    this.backgroundAppRefresh = true,
    this.brightness = 0.85,
    this.textSize = 0.5,
    this.boldText = false,
    this.alwaysOnDisplay = true,
    this.wakeOnWristRaise = true,
    this.wakeDuration = WakeDuration.seconds70,
    this.alertVolume = 0.8,
    this.silentMode = false,
    this.hapticAlerts = true,
    this.hapticStrength = HapticStrength.prominent,
    this.crownHaptics = true,
    this.systemHaptics = true,
    this.coverToMute = true,
    this.notificationIndicator = true,
    this.notificationPrivacy = false,
    this.showSummary = true,
    this.appNotifications = const {
      'messages': true,
      'phone': true,
      'mail': true,
      'calendar': true,
      'reminders': true,
      'fitness': true,
      'heart': true,
      'whatsapp': true,
      'telegram': true,
      'banking': true,
    },
    this.passcodeEnabled = true,
    this.simplePasscode = true,
    this.unlockWithPhone = true,
    this.wristDetection = true,
    this.eraseDataOnTenFails = false,
    this.standReminders = true,
    this.dailyCoaching = true,
    this.goalCompletions = true,
    this.heartRateAlerts = true,
    this.highHeartRateThreshold = 120,
    this.lowHeartRateThreshold = 40,
    this.bloodOxygenEnabled = true,
    this.sleepTracking = true,
    this.actionButtonTarget = ActionButtonTarget.workout,
    this.actionButtonApp = 'workout',
    this.holdForSiren = true,
    this.fallDetection = true,
    this.crashDetection = true,
  });

  WatchSettings copyWith({
    String? watchName,
    WristOrientation? wristOrientation,
    CrownOrientation? crownOrientation,
    bool? airplaneMode,
    bool? bluetoothEnabled,
    bool? wifiEnabled,
    bool? backgroundAppRefresh,
    double? brightness,
    double? textSize,
    bool? boldText,
    bool? alwaysOnDisplay,
    bool? wakeOnWristRaise,
    WakeDuration? wakeDuration,
    double? alertVolume,
    bool? silentMode,
    bool? hapticAlerts,
    HapticStrength? hapticStrength,
    bool? crownHaptics,
    bool? systemHaptics,
    bool? coverToMute,
    bool? notificationIndicator,
    bool? notificationPrivacy,
    bool? showSummary,
    Map<String, bool>? appNotifications,
    bool? passcodeEnabled,
    bool? simplePasscode,
    bool? unlockWithPhone,
    bool? wristDetection,
    bool? eraseDataOnTenFails,
    bool? standReminders,
    bool? dailyCoaching,
    bool? goalCompletions,
    bool? heartRateAlerts,
    int? highHeartRateThreshold,
    int? lowHeartRateThreshold,
    bool? bloodOxygenEnabled,
    bool? sleepTracking,
    ActionButtonTarget? actionButtonTarget,
    String? actionButtonApp,
    bool? holdForSiren,
    bool? fallDetection,
    bool? crashDetection,
  }) {
    return WatchSettings(
      watchName: watchName ?? this.watchName,
      wristOrientation: wristOrientation ?? this.wristOrientation,
      crownOrientation: crownOrientation ?? this.crownOrientation,
      airplaneMode: airplaneMode ?? this.airplaneMode,
      bluetoothEnabled: bluetoothEnabled ?? this.bluetoothEnabled,
      wifiEnabled: wifiEnabled ?? this.wifiEnabled,
      backgroundAppRefresh: backgroundAppRefresh ?? this.backgroundAppRefresh,
      brightness: brightness ?? this.brightness,
      textSize: textSize ?? this.textSize,
      boldText: boldText ?? this.boldText,
      alwaysOnDisplay: alwaysOnDisplay ?? this.alwaysOnDisplay,
      wakeOnWristRaise: wakeOnWristRaise ?? this.wakeOnWristRaise,
      wakeDuration: wakeDuration ?? this.wakeDuration,
      alertVolume: alertVolume ?? this.alertVolume,
      silentMode: silentMode ?? this.silentMode,
      hapticAlerts: hapticAlerts ?? this.hapticAlerts,
      hapticStrength: hapticStrength ?? this.hapticStrength,
      crownHaptics: crownHaptics ?? this.crownHaptics,
      systemHaptics: systemHaptics ?? this.systemHaptics,
      coverToMute: coverToMute ?? this.coverToMute,
      notificationIndicator:
          notificationIndicator ?? this.notificationIndicator,
      notificationPrivacy: notificationPrivacy ?? this.notificationPrivacy,
      showSummary: showSummary ?? this.showSummary,
      appNotifications: appNotifications ?? this.appNotifications,
      passcodeEnabled: passcodeEnabled ?? this.passcodeEnabled,
      simplePasscode: simplePasscode ?? this.simplePasscode,
      unlockWithPhone: unlockWithPhone ?? this.unlockWithPhone,
      wristDetection: wristDetection ?? this.wristDetection,
      eraseDataOnTenFails: eraseDataOnTenFails ?? this.eraseDataOnTenFails,
      standReminders: standReminders ?? this.standReminders,
      dailyCoaching: dailyCoaching ?? this.dailyCoaching,
      goalCompletions: goalCompletions ?? this.goalCompletions,
      heartRateAlerts: heartRateAlerts ?? this.heartRateAlerts,
      highHeartRateThreshold:
          highHeartRateThreshold ?? this.highHeartRateThreshold,
      lowHeartRateThreshold:
          lowHeartRateThreshold ?? this.lowHeartRateThreshold,
      bloodOxygenEnabled: bloodOxygenEnabled ?? this.bloodOxygenEnabled,
      sleepTracking: sleepTracking ?? this.sleepTracking,
      actionButtonTarget: actionButtonTarget ?? this.actionButtonTarget,
      actionButtonApp: actionButtonApp ?? this.actionButtonApp,
      holdForSiren: holdForSiren ?? this.holdForSiren,
      fallDetection: fallDetection ?? this.fallDetection,
      crashDetection: crashDetection ?? this.crashDetection,
    );
  }
}
