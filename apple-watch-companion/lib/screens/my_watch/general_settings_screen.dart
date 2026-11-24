import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../models/native_watch_settings.dart';
import '../../providers/watch_provider.dart';
import '../../providers/watch_connection_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import 'about_device_screen.dart';
import 'software_update_screen.dart';
import 'storage_screen.dart';
import 'orientation_screen.dart';

class GeneralSettingsScreen extends StatelessWidget {
  const GeneralSettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final provider = context.watch<WatchProvider>();
    final softwareUpdatesAvailable = context
        .select<WatchConnectionProvider, bool>(
          (c) => c.state.features.softwareUpdates,
        );

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xCC121212),
        middle: Text(s.general),
        previousPageTitle: s.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              children: [
                IosListTile(
                  title: s.about,
                  onTap: () {
                    Navigator.of(context).push(
                      CupertinoPageRoute(
                        builder: (_) => const AboutDeviceScreen(),
                      ),
                    );
                  },
                ),
                if (softwareUpdatesAvailable)
                  IosListTile(
                    title: s.softwareUpdate,
                    onTap: () {
                      Navigator.of(context).push(
                        CupertinoPageRoute(
                          builder: (_) => const SoftwareUpdateScreen(),
                        ),
                      );
                    },
                  ),
                IosListTile(
                  title: s.storage,
                  onTap: () {
                    Navigator.of(context).push(
                      CupertinoPageRoute(builder: (_) => const StorageScreen()),
                    );
                  },
                ),
              ],
            ),
            IosListSection(
              children: [
                IosListTile(
                  title: s.orientationTime,
                  trailingText: switch (provider
                      .nativeSettings
                      .values[NativeWatchSetting.rightWrist]
                      ?.value) {
                    true => s.rightWrist,
                    false => s.leftWrist,
                    null => s.notReceived,
                  },
                  onTap: () {
                    Navigator.of(context).push(
                      CupertinoPageRoute(
                        builder: (_) => const OrientationScreen(),
                      ),
                    );
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
