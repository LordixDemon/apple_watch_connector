import 'package:flutter/cupertino.dart';
import '../l10n/strings.dart';
import '../models/watch_connection_state.dart';
import '../theme/ios_colors.dart';
import 'ios_list_section.dart';
import 'ios_list_tile.dart';

/// Availability is backend support, never inferred from an activated Watch.
class WatchFeaturesPanel extends StatelessWidget {
  final WatchConnectionState state;
  const WatchFeaturesPanel({super.key, required this.state});

  @override
  Widget build(BuildContext context) {
    final s = Strings.current;
    final features = state.features;
    return IosListSection(
      header: s.backendFeatures,
      footer: features.known ? s.backendFeaturesHint : s.backendFeaturesUnknown,
      children: [
        for (final row in <String, bool>{
          s.faceGallery: features.nativeFaces,
          s.watchSettings: features.watchSettings,
          s.battery: features.telemetry,
          s.phoneFindFeature: features.phoneFind,
          s.notifications: features.notifications,
          s.activityHealth: features.health,
          s.installedOnAppleWatch: features.appManagement,
          s.wiFi: state.wifiAvailable && features.known,
        }.entries)
          IosListTile(
            title: row.key,
            trailingText: !features.known
                ? s.notReceived
                : row.value
                ? s.available
                : s.featureNotSupported,
            trailing: Icon(
              row.value && features.known
                  ? CupertinoIcons.check_mark
                  : CupertinoIcons.minus,
              size: 16,
              color: row.value && features.known
                  ? IosColors.systemGreen
                  : IosColors.secondaryLabel,
            ),
          ),
      ],
    );
  }
}
