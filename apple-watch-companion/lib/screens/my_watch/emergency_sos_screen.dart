import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class EmergencySosScreen extends StatelessWidget {
  const EmergencySosScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.emergencySos),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              header: Strings.current.fallDetection,
              footer: Strings.current.ifAppleWatchDetectsAHardFallAndYou,
              children: [
                IosListTile(
                  icon: CupertinoIcons.bandage_fill,
                  iconBackgroundColor: IosColors.systemRed,
                  title: Strings.current.fallDetection2,
                  trailing: CupertinoSwitch(
                    value: settings.fallDetection,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(fallDetection: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.crashDetection,
              footer: Strings.current.afterASevereCarCrashAppleWatchShowsThe,
              children: [
                IosListTile(
                  icon: CupertinoIcons.car_detailed,
                  iconBackgroundColor: IosColors.systemOrange,
                  title: Strings.current.crashDetection2,
                  trailing: CupertinoSwitch(
                    value: settings.crashDetection,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(crashDetection: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.medicalInformation,
              children: [
                IosListTile(
                  icon: CupertinoIcons.heart_fill,
                  iconBackgroundColor: IosColors.systemRed,
                  title: Strings.current.setUpMedicalId,
                  onTap: () {},
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
