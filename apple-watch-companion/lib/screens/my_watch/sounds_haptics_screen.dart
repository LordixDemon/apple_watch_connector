import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../models/watch_settings.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/ios_slider_tile.dart';

class SoundsHapticsScreen extends StatelessWidget {
  const SoundsHapticsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.soundsHaptics),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              header: Strings.current.alertVolume,
              children: [
                IosSliderTile(
                  leftIcon: CupertinoIcons.speaker_1,
                  rightIcon: CupertinoIcons.speaker_3_fill,
                  value: settings.alertVolume,
                  onChanged: (val) {
                    provider.updateSettings(
                      settings.copyWith(alertVolume: val),
                    );
                  },
                ),
                IosListTile(
                  title: Strings.current.silentMode,
                  trailing: CupertinoSwitch(
                    value: settings.silentMode,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(silentMode: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.hapticFeedback,
              children: [
                IosListTile(
                  title: Strings.current.hapticAlerts,
                  trailing: CupertinoSwitch(
                    value: settings.hapticAlerts,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(hapticAlerts: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.defaultHaptic,
                  trailing:
                      settings.hapticStrength == HapticStrength.defaultStrength
                      ? const Icon(
                          CupertinoIcons.checkmark,
                          color: IosColors.systemOrange,
                          size: 18,
                        )
                      : null,
                  showChevron: false,
                  onTap: () {
                    provider.updateSettings(
                      settings.copyWith(
                        hapticStrength: HapticStrength.defaultStrength,
                      ),
                    );
                  },
                ),
                IosListTile(
                  title: Strings.current.prominent,
                  subtitle:
                      Strings.current.playAnAdditionalHapticBeforeANotification,
                  trailing: settings.hapticStrength == HapticStrength.prominent
                      ? const Icon(
                          CupertinoIcons.checkmark,
                          color: IosColors.systemOrange,
                          size: 18,
                        )
                      : null,
                  showChevron: false,
                  onTap: () {
                    provider.updateSettings(
                      settings.copyWith(
                        hapticStrength: HapticStrength.prominent,
                      ),
                    );
                  },
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.systemFeedback,
              children: [
                IosListTile(
                  title: Strings.current.crownHaptics,
                  trailing: CupertinoSwitch(
                    value: settings.crownHaptics,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(crownHaptics: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.systemHaptics,
                  trailing: CupertinoSwitch(
                    value: settings.systemHaptics,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(systemHaptics: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.coverToMute,
                  subtitle:
                      Strings.current.coverTheDisplayWithYourPalmForAtLeast,
                  trailing: CupertinoSwitch(
                    value: settings.coverToMute,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(coverToMute: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
