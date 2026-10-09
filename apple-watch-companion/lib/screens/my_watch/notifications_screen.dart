import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.notifications),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.notificationIndicator,
                  subtitle: Strings.current.showsARedDotAtTheTopOfThe,
                  trailing: CupertinoSwitch(
                    value: settings.notificationIndicator,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(notificationIndicator: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.notificationPrivacy,
                  subtitle: Strings
                      .current
                      .hideNotificationDetailsUntilYouTapTheScreen,
                  trailing: CupertinoSwitch(
                    value: settings.notificationPrivacy,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(notificationPrivacy: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.notificationSummary,
                  trailing: CupertinoSwitch(
                    value: settings.showSummary,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(showSummary: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.mirrorPhoneAlerts,
              footer:
                  Strings.current.forwardAlertsFromTheseAppsToAppleWatchWith,
              children: [
                _buildAppToggle(
                  context,
                  title: Strings.current.messagesSmsRcs,
                  icon: CupertinoIcons.chat_bubble_2_fill,
                  iconBg: IosColors.systemGreen,
                  key: 'messages',
                  enabled: settings.appNotifications['messages'] ?? true,
                ),
                _buildAppToggle(
                  context,
                  title: Strings.current.phoneCalls,
                  icon: CupertinoIcons.phone_fill,
                  iconBg: IosColors.systemGreen,
                  key: 'phone',
                  enabled: settings.appNotifications['phone'] ?? true,
                ),
                _buildAppToggle(
                  context,
                  title: Strings.current.mail,
                  icon: CupertinoIcons.mail_solid,
                  iconBg: IosColors.systemBlue,
                  key: 'mail',
                  enabled: settings.appNotifications['mail'] ?? true,
                ),
                _buildAppToggle(
                  context,
                  title: Strings.current.calendarReminders,
                  icon: CupertinoIcons.calendar,
                  iconBg: IosColors.systemRed,
                  key: 'calendar',
                  enabled: settings.appNotifications['calendar'] ?? true,
                ),
                _buildAppToggle(
                  context,
                  title: 'Telegram & WhatsApp',
                  icon: CupertinoIcons.paperplane_fill,
                  iconBg: IosColors.systemTeal,
                  key: 'telegram',
                  enabled: settings.appNotifications['telegram'] ?? true,
                ),
                _buildAppToggle(
                  context,
                  title: Strings.current.bankingApps,
                  icon: CupertinoIcons.creditcard_fill,
                  iconBg: IosColors.systemOrange,
                  key: 'banking',
                  enabled: settings.appNotifications['banking'] ?? true,
                ),
              ],
            ),
            const SizedBox(height: 12),
            IosListSection(
              header: Strings.current.bulletinRelayTest,
              footer: Strings.current.sendATestNotificationToAppleWatchOverThe,
              children: [
                IosListTile(
                  icon: CupertinoIcons.bell_fill,
                  iconBackgroundColor: IosColors.systemIndigo,
                  title: Strings.current.sendTestNotification,
                  subtitle:
                      Strings.current.simulatedTelegramSmsWithTapticFeedback,
                  onTap: () async {
                    await provider.sendTestNotification(
                      Strings.current.testMessage,
                      Strings.current.androidAppleWatchConnectionIsWorking,
                      appName: 'Apple Watch Android Bridge',
                    );
                    if (context.mounted) {
                      showCupertinoDialog(
                        context: context,
                        builder: (ctx) => CupertinoAlertDialog(
                          title: Text(Strings.current.notificationSent),
                          content: Text(
                            Strings
                                .current
                                .bulletinrequestWasHandedToTheWatchProtocolStack,
                          ),
                          actions: [
                            CupertinoDialogAction(
                              child: Text(Strings.current.ok),
                              onPressed: () => Navigator.pop(ctx),
                            ),
                          ],
                        ),
                      );
                    }
                  },
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildAppToggle(
    BuildContext context, {
    required String title,
    required IconData icon,
    required Color iconBg,
    required String key,
    required bool enabled,
  }) {
    final provider = context.read<WatchProvider>();
    final settings = provider.settings;

    return IosListTile(
      icon: icon,
      iconBackgroundColor: iconBg,
      title: title,
      trailing: CupertinoSwitch(
        value: enabled,
        activeTrackColor: IosColors.systemGreen,
        onChanged: (val) {
          final newMap = Map<String, bool>.from(settings.appNotifications);
          newMap[key] = val;
          provider.updateSettings(settings.copyWith(appNotifications: newMap));
        },
      ),
      showChevron: false,
    );
  }
}
