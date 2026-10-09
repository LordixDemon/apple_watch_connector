import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class PasscodeScreen extends StatelessWidget {
  const PasscodeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.passcode),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.passcode,
                  trailingText: settings.passcodeEnabled
                      ? Strings.current.on
                      : Strings.current.off,
                  onTap: () {
                    provider.updateSettings(
                      settings.copyWith(
                        passcodeEnabled: !settings.passcodeEnabled,
                      ),
                    );
                  },
                ),
                IosListTile(
                  title: Strings.current.changePasscode,
                  onTap: () {},
                ),
              ],
            ),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.simplePasscode4Digits,
                  trailing: CupertinoSwitch(
                    value: settings.simplePasscode,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(simplePasscode: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.unlockWithPhone,
                  subtitle: Strings
                      .current
                      .unlockingYourPhoneAutomaticallyUnlocksYourTrustedAppleWatch,
                  trailing: CupertinoSwitch(
                    value: settings.unlockWithPhone,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(unlockWithPhone: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.wristDetection,
              footer: Strings.current.lockAppleWatchWhenYouTakeItOffTo,
              children: [
                IosListTile(
                  title: Strings.current.wristDetection2,
                  trailing: CupertinoSwitch(
                    value: settings.wristDetection,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(wristDetection: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.dataSecurity,
              footer:
                  Strings.current.eraseAllAppleWatchDataAfter10FailedPasscode,
              children: [
                IosListTile(
                  title: Strings.current.eraseData10Attempts,
                  trailing: CupertinoSwitch(
                    value: settings.eraseDataOnTenFails,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(eraseDataOnTenFails: val),
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
