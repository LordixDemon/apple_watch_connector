import 'package:flutter/cupertino.dart';
import '../l10n/strings.dart';
import '../models/notification_relay_state.dart';
import '../theme/ios_colors.dart';
import 'ios_list_section.dart';
import 'ios_list_tile.dart';

class NotificationRelayPanel extends StatefulWidget {
  final NotificationRelayState state;
  final Future<bool> Function() requestAccess;
  final Future<String> Function(bool enabled, {String? packageName}) setPolicy;
  final Future<({String status, String? requestId})> Function() sendTest;
  const NotificationRelayPanel({
    super.key,
    required this.state,
    required this.requestAccess,
    required this.setPolicy,
    required this.sendTest,
  });

  @override
  State<NotificationRelayPanel> createState() => _NotificationRelayPanelState();
}

class _NotificationRelayPanelState extends State<NotificationRelayPanel> {
  bool _busy = false;
  String? _error;

  Future<void> _save(bool value, {String? packageName}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final status = await widget.setPolicy(value, packageName: packageName);
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (status != 'STORED') {
        _error = Strings.current.notificationSettingsFailed;
      }
    });
  }

  Future<void> _permission() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final opened = await widget.requestAccess();
    if (!mounted) return;
    setState(() {
      _busy = false;
      if (!opened) _error = Strings.current.notificationAccessFailed;
    });
  }

  Future<void> _test() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final receipt = await widget.sendTest();
    if (!mounted) return;
    setState(() => _busy = false);
    final queued = receipt.status == 'QUEUED';
    await showCupertinoDialog<void>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: Text(
          queued
              ? Strings.current.notificationQueued
              : Strings.current.notificationTestFailed,
        ),
        content: Text(
          queued
              ? Strings.current.notificationQueuedDetails
              : Strings.current.notificationTestFailedDetails,
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context),
            child: Text(Strings.current.ok),
          ),
        ],
      ),
    );
  }

  String get _status {
    final state = widget.state;
    if (!state.available) return Strings.current.notificationBridgeUnavailable;
    if (!state.accessGranted) return Strings.current.notificationAccessRequired;
    if (!state.enabled) return Strings.current.notificationForwardingPaused;
    if (!state.listenerConnected) {
      return Strings.current.notificationListenerWaiting;
    }
    if (!state.ready) return Strings.current.notificationWatchWaiting;
    return Strings.current.notificationForwardingReady;
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    return Column(
      children: [
        IosListSection(
          footer:
              _error ??
              (state.pending > 0
                  ? Strings.current.notificationPending(state.pending)
                  : Strings.current.notificationForwardingDescription),
          children: [
            IosListTile(
              title: Strings.current.notificationForwarding,
              subtitle: _status,
              trailing: CupertinoSwitch(
                value: state.enabled,
                activeTrackColor: IosColors.systemGreen,
                onChanged: state.available && !_busy
                    ? (value) => _save(value)
                    : null,
              ),
              showChevron: false,
            ),
            if (state.available)
              IosListTile(
                title: state.accessGranted
                    ? Strings.current.notificationManageAccess
                    : Strings.current.notificationAllowAccess,
                subtitle: Strings.current.notificationAccessDescription,
                onTap: _busy ? null : _permission,
              ),
          ],
        ),
        if (state.available)
          IosListSection(
            header: Strings.current.mirrorPhoneAlerts,
            footer: Strings.current.notificationAppDiscovery,
            children: [
              if (state.apps.isEmpty)
                IosListTile(
                  title: Strings.current.notificationNoApps,
                  showChevron: false,
                ),
              for (final app in state.apps)
                IosListTile(
                  title: app.name,
                  subtitle: app.name == app.packageName
                      ? null
                      : app.packageName,
                  icon: CupertinoIcons.bell_fill,
                  iconBackgroundColor: IosColors.systemIndigo,
                  trailing: CupertinoSwitch(
                    value: app.enabled,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: !_busy
                        ? (value) => _save(value, packageName: app.packageName)
                        : null,
                  ),
                  showChevron: false,
                ),
            ],
          ),
        IosListSection(
          footer: Strings.current.notificationTestDescription,
          children: [
            IosListTile(
              title: Strings.current.sendTestNotification,
              icon: CupertinoIcons.bell_fill,
              iconBackgroundColor: IosColors.systemOrange,
              trailing: _busy ? const CupertinoActivityIndicator() : null,
              onTap: state.ready && !_busy ? _test : null,
              enabled: state.ready && !_busy,
              showChevron: state.ready && !_busy,
            ),
          ],
        ),
      ],
    );
  }
}
