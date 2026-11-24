import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/notification_relay_panel.dart';

class NotificationsScreen extends StatelessWidget {
  const NotificationsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final provider = context.watch<WatchProvider>();
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xCC121212),
        middle: Text(s.notifications),
        previousPageTitle: s.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            NotificationRelayPanel(
              state: provider.notifications,
              requestAccess: provider.requestNotificationAccess,
              setPolicy: provider.setNotificationRelay,
              setOnlyWhenScreenOff: (enabled) => provider.setNotificationRelay(
                enabled,
                mode: 'onlyWhenScreenOff',
              ),
              retryPending: provider.retryPendingNotifications,
              operationStream: provider.operationStatusStream,
              sendTest: () => provider.queueTestNotification(
                s.testMessage,
                s.notificationTestBody,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
