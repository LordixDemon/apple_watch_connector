import '../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import '../theme/ios_colors.dart';

/// Representation of an app installed on watchOS.
class WatchAppItem {
  final String id;
  final String name;
  final String version;
  final String developer;
  final IconData icon;
  final Color iconBackgroundColor;
  final bool isInstalledOnWatch;
  final bool showOnWatch;
  final String description;

  const WatchAppItem({
    required this.id,
    required this.name,
    required this.version,
    required this.developer,
    required this.icon,
    required this.iconBackgroundColor,
    this.isInstalledOnWatch = true,
    this.showOnWatch = true,
    this.description = '',
  });

  static List<WatchAppItem> defaultApps() {
    return [
      WatchAppItem(
        id: 'workout',
        name: Strings.current.workout,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.play_circle_fill,
        iconBackgroundColor: IosColors.systemGreen,
        description:
            Strings.current.trackActivityHeartRatePowerZonesAndGpsRoutes,
      ),
      WatchAppItem(
        id: 'activity',
        name: Strings.current.activity,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.flame_fill,
        iconBackgroundColor: IosColors.activityRed,
        description: Strings.current.moveExerciseAndStandRings,
      ),
      WatchAppItem(
        id: 'heart_rate',
        name: Strings.current.heartRate,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.heart_fill,
        iconBackgroundColor: IosColors.systemRed,
        description: Strings.current.heartRateMeasurementsAndAlerts,
      ),
      WatchAppItem(
        id: 'compass',
        name: Strings.current.compassWaypoints,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.compass_fill,
        iconBackgroundColor: IosColors.systemOrange,
        description:
            Strings.current.elevationInclineCoordinatesAndBacktrackRoutes,
      ),
      WatchAppItem(
        id: 'music',
        name: Strings.current.music,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.music_note_2,
        iconBackgroundColor: IosColors.systemPink,
        description: Strings.current.streamAudioAndControlPlayback,
      ),
      WatchAppItem(
        id: 'messages',
        name: Strings.current.messages,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.chat_bubble_2_fill,
        iconBackgroundColor: IosColors.systemGreen,
        description:
            Strings.current.sendAndReceiveMessagesUseDictationAndQuickReplies,
      ),
      WatchAppItem(
        id: 'phone',
        name: Strings.current.phone,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.phone_fill,
        iconBackgroundColor: IosColors.systemGreen,
        description: Strings.current.callsOverBluetoothOrCellular,
      ),
      WatchAppItem(
        id: 'weather',
        name: Strings.current.weather,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.sun_max_fill,
        iconBackgroundColor: IosColors.systemBlue,
        description: Strings.current.forecastsUvIndexPrecipitationAndWind,
      ),
      WatchAppItem(
        id: 'maps',
        name: Strings.current.maps,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.location_solid,
        iconBackgroundColor: IosColors.systemTeal,
        description: Strings.current.turnByTurnDirectionsWithHapticFeedback,
      ),
      WatchAppItem(
        id: 'noise',
        name: Strings.current.noise,
        version: '11.2',
        developer: 'Apple Inc.',
        icon: CupertinoIcons.speaker_3_fill,
        iconBackgroundColor: IosColors.systemYellow,
        description: Strings.current.monitorAmbientNoiseLevelsInDecibels,
      ),
    ];
  }
}
