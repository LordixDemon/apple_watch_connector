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
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;
    final features = context.watch<WatchConnectionProvider>().state.features;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.general),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.about,
                  onTap: () {
                    Navigator.of(context).push(
                      CupertinoPageRoute(
                        builder: (_) => const AboutDeviceScreen(),
                      ),
                    );
                  },
                ),
                if (features.supports('softwareUpdate'))
                  IosListTile(
                    title: Strings.current.softwareUpdate,
                    onTap: () {
                      Navigator.of(context).push(
                        CupertinoPageRoute(
                          builder: (_) => const SoftwareUpdateScreen(),
                        ),
                      );
                    },
                  ),
                IosListTile(
                  title: Strings.current.storage,
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
                  title: Strings.current.orientationTime,
                  trailingText: switch (provider
                      .nativeSettings
                      .values[NativeWatchSetting.rightWrist]
                      ?.value) {
                    true => Strings.current.rightWrist,
                    false => Strings.current.leftWrist,
                    null => Strings.current.notReceived,
                  },
                  onTap: () {
                    Navigator.of(context).push(
                      CupertinoPageRoute(
                        builder: (_) => const OrientationScreen(),
                      ),
                    );
                  },
                ),
                if (features.supports('backgroundAppRefresh'))
                  IosListTile(
                    title: Strings.current.backgroundAppRefresh,
                    trailingText: settings.backgroundAppRefresh
                        ? Strings.current.on
                        : Strings.current.off,
                    onTap: () {
                      provider.updateSettings(
                        settings.copyWith(
                          backgroundAppRefresh: !settings.backgroundAppRefresh,
                        ),
                      );
                    },
                  ),
              ],
            ),
            if (features.supports('connectivitySettings'))
              IosListSection(
                header: Strings.current.connectivity,
                children: [
                  IosListTile(
                    title: Strings.current.airplaneMode,
                    trailing: CupertinoSwitch(
                      value: settings.airplaneMode,
                      activeTrackColor: IosColors.systemGreen,
                      onChanged: (val) {
                        provider.updateSettings(
                          settings.copyWith(airplaneMode: val),
                        );
                      },
                    ),
                    showChevron: false,
                  ),
                  IosListTile(
                    title: Strings.current.bluetooth,
                    trailing: CupertinoSwitch(
                      value: settings.bluetoothEnabled,
                      activeTrackColor: IosColors.systemGreen,
                      onChanged: (val) {
                        provider.updateSettings(
                          settings.copyWith(bluetoothEnabled: val),
                        );
                      },
                    ),
                    showChevron: false,
                  ),
                  IosListTile(
                    title: Strings.current.wiFi,
                    trailing: CupertinoSwitch(
                      value: settings.wifiEnabled,
                      activeTrackColor: IosColors.systemGreen,
                      onChanged: (val) {
                        provider.updateSettings(
                          settings.copyWith(wifiEnabled: val),
                        );
                      },
                    ),
                    showChevron: false,
                  ),
                ],
              ),
            if (features.supports('unpair'))
              IosListSection(
                children: [
                  IosListTile(
                    title: Strings.current.resetUnpair,
                    isDestructive: true,
                    onTap: () {
                      showCupertinoModalPopup(
                        context: context,
                        builder: (ctx) => CupertinoActionSheet(
                          title: Text(Strings.current.unpairAppleWatch),
                          message: Text(
                            Strings
                                .current
                                .allDataWatchFacesAndSettingsWillBeErased,
                          ),
                          actions: [
                            CupertinoActionSheetAction(
                              isDestructiveAction: true,
                              onPressed: () {
                                Navigator.pop(ctx);
                                provider.unpairWatch();
                                Navigator.of(context).pop();
                              },
                              child: Text(Strings.current.unpair),
                            ),
                          ],
                          cancelButton: CupertinoActionSheetAction(
                            onPressed: () => Navigator.pop(ctx),
                            child: Text(Strings.current.cancel),
                          ),
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
