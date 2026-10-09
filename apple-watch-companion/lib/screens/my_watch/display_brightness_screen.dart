import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../models/watch_settings.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/ios_slider_tile.dart';

class DisplayBrightnessScreen extends StatelessWidget {
  const DisplayBrightnessScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.displayBrightness),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              header: Strings.current.brightness,
              children: [
                IosSliderTile(
                  leftIcon: CupertinoIcons.sun_min,
                  rightIcon: CupertinoIcons.sun_max_fill,
                  value: settings.brightness,
                  onChanged: (val) {
                    provider.updateSettings(settings.copyWith(brightness: val));
                  },
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.textSize,
              children: [
                IosSliderTile(
                  leftIcon: CupertinoIcons.textformat_size,
                  rightIcon: CupertinoIcons.textformat,
                  value: settings.textSize,
                  onChanged: (val) {
                    provider.updateSettings(settings.copyWith(textSize: val));
                  },
                ),
                IosListTile(
                  title: Strings.current.boldText,
                  trailing: CupertinoSwitch(
                    value: settings.boldText,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(settings.copyWith(boldText: val));
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.alwaysOn2,
              footer:
                  Strings.current.keepTheWatchFaceAndAppInformationVisibleWhen,
              children: [
                IosListTile(
                  title: Strings.current.alwaysOn,
                  trailing: CupertinoSwitch(
                    value: settings.alwaysOnDisplay,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(alwaysOnDisplay: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.wristRaise,
              children: [
                IosListTile(
                  title: Strings.current.wakeOnWristRaise,
                  trailing: CupertinoSwitch(
                    value: settings.wakeOnWristRaise,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(wakeOnWristRaise: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.wakeDuration,
                  trailingText: settings.wakeDuration == WakeDuration.seconds70
                      ? Strings.current.message70Seconds
                      : Strings.current.message15Seconds,
                  onTap: () {
                    showCupertinoModalPopup(
                      context: context,
                      builder: (ctx) => CupertinoActionSheet(
                        title: Text(Strings.current.screenWakeDurationOnTap),
                        actions: [
                          CupertinoActionSheetAction(
                            onPressed: () {
                              provider.updateSettings(
                                settings.copyWith(
                                  wakeDuration: WakeDuration.seconds15,
                                ),
                              );
                              Navigator.pop(ctx);
                            },
                            child: Text(Strings.current.message15Seconds),
                          ),
                          CupertinoActionSheetAction(
                            onPressed: () {
                              provider.updateSettings(
                                settings.copyWith(
                                  wakeDuration: WakeDuration.seconds70,
                                ),
                              );
                              Navigator.pop(ctx);
                            },
                            child: Text(Strings.current.message70Seconds),
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
