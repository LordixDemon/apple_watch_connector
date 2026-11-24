import 'dart:async';
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
  final Future<String> Function(bool enabled)? setOnlyWhenScreenOff;
  final Future<String> Function()? retryPending;
  final Stream<Map<String, dynamic>>? operationStream;
  const NotificationRelayPanel({
    super.key,
    required this.state,
    required this.requestAccess,
    required this.setPolicy,
    required this.sendTest,
    this.setOnlyWhenScreenOff,
    this.retryPending,
    this.operationStream,
  });

  @override
  State<NotificationRelayPanel> createState() => _NotificationRelayPanelState();
}

class _NotificationRelayPanelState extends State<NotificationRelayPanel> {
  final Set<String> _busyKeys = {};
  final TextEditingController _searchController = TextEditingController();
  String _searchQuery = '';
  bool _onlyObserved = false;
  String? _error;

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _save(bool value, {String? packageName}) async {
    final key = packageName ?? '_global';
    setState(() {
      _busyKeys.add(key);
      _error = null;
    });
    final status = await widget.setPolicy(value, packageName: packageName);
    if (!mounted) return;
    setState(() {
      _busyKeys.remove(key);
      if (status != 'STORED') {
        _error = Strings.current.notificationSettingsFailed;
      }
    });
  }

  Future<void> _saveScreenOff(bool value) async {
    final callback = widget.setOnlyWhenScreenOff;
    if (callback == null) return;
    setState(() {
      _busyKeys.add('_screen_off');
      _error = null;
    });
    final status = await callback(value);
    if (!mounted) return;
    setState(() {
      _busyKeys.remove('_screen_off');
      if (status != 'STORED') {
        _error = Strings.current.notificationSettingsFailed;
      }
    });
  }

  Future<void> _retry() async {
    final callback = widget.retryPending;
    if (callback == null) return;
    setState(() {
      _busyKeys.add('_retry');
      _error = null;
    });
    await callback();
    if (!mounted) return;
    setState(() => _busyKeys.remove('_retry'));
  }

  Future<void> _permission() async {
    setState(() {
      _busyKeys.add('_permission');
      _error = null;
    });
    final opened = await widget.requestAccess();
    if (!mounted) return;
    setState(() {
      _busyKeys.remove('_permission');
      if (!opened) _error = Strings.current.notificationAccessFailed;
    });
  }

  Future<void> _test() async {
    setState(() {
      _busyKeys.add('_test');
      _error = null;
    });
    final receipt = await widget.sendTest();
    if (!mounted) return;
    setState(() => _busyKeys.remove('_test'));
    final queued = receipt.status == 'QUEUED';
    if (!queued || receipt.requestId == null || widget.operationStream == null) {
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
      return;
    }
    await showCupertinoDialog<void>(
      context: context,
      builder: (context) => _LiveTestStatusDialog(
        requestId: receipt.requestId!,
        operations: widget.operationStream!,
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
    final hasObservedMix =
        state.apps.any((app) => app.observed) &&
        state.apps.any((app) => !app.observed);
    final query = _searchQuery.trim().toLowerCase();
    final visibleApps = state.apps.where((app) {
      if (_onlyObserved && !app.observed) return false;
      if (query.isEmpty) return true;
      return app.name.toLowerCase().contains(query) ||
          app.packageName.toLowerCase().contains(query);
    }).toList();
    final canSendTest = (state.connected || state.ready) &&
        !_busyKeys.contains('_test');

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
                onChanged: state.available && !_busyKeys.contains('_global')
                    ? (value) => _save(value)
                    : null,
              ),
              showChevron: false,
            ),
            if (state.available && widget.setOnlyWhenScreenOff != null)
              IosListTile(
                title: Strings.current.notificationOnlyWhenLocked,
                subtitle: Strings.current.notificationOnlyWhenLockedDescription,
                trailing: CupertinoSwitch(
                  value: state.onlyWhenScreenOff,
                  activeTrackColor: IosColors.systemGreen,
                  onChanged: !_busyKeys.contains('_screen_off')
                      ? _saveScreenOff
                      : null,
                ),
                showChevron: false,
              ),
            if (state.pending > 0 && state.ready && widget.retryPending != null)
              IosListTile(
                title: Strings.current.notificationRetryPending,
                trailing: _busyKeys.contains('_retry')
                    ? const CupertinoActivityIndicator()
                    : null,
                onTap: _busyKeys.contains('_retry') ? null : _retry,
              ),
            if (state.available)
              IosListTile(
                title: state.accessGranted
                    ? Strings.current.notificationManageAccess
                    : Strings.current.notificationAllowAccess,
                subtitle: Strings.current.notificationAccessDescription,
                onTap: _busyKeys.contains('_permission') ? null : _permission,
              ),
          ],
        ),
        if (state.available)
          IosListSection(
            headerWidget: Padding(
              padding: const EdgeInsets.only(left: 12, right: 12, bottom: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    Strings.current.mirrorPhoneAlerts.toUpperCase(),
                    style: const TextStyle(
                      color: IosColors.secondaryLabel,
                      fontSize: 13,
                      fontWeight: FontWeight.w500,
                      letterSpacing: -0.1,
                    ),
                  ),
                  if (state.apps.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    CupertinoSearchTextField(
                      controller: _searchController,
                      placeholder: Strings.current.notificationSearchApps,
                      onChanged: (value) => setState(() => _searchQuery = value),
                    ),
                  ],
                  if (hasObservedMix) ...[
                    const SizedBox(height: 8),
                    SizedBox(
                      width: double.infinity,
                      child: CupertinoSlidingSegmentedControl<bool>(
                        groupValue: _onlyObserved,
                        onValueChanged: (value) {
                          if (value != null) {
                            setState(() => _onlyObserved = value);
                          }
                        },
                        children: {
                          false: Text(Strings.current.notificationFilterAll),
                          true: Text(Strings.current.notificationFilterActive),
                        },
                      ),
                    ),
                  ],
                ],
              ),
            ),
            footer: Strings.current.notificationAppDiscovery,
            children: [
              if (visibleApps.isEmpty)
                IosListTile(
                  title: Strings.current.notificationNoApps,
                  showChevron: false,
                ),
              for (final app in visibleApps)
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
                    onChanged: !_busyKeys.contains(app.packageName)
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
              trailing: _busyKeys.contains('_test')
                  ? const CupertinoActivityIndicator()
                  : null,
              onTap: canSendTest ? _test : null,
              enabled: canSendTest,
              showChevron: canSendTest,
            ),
          ],
        ),
      ],
    );
  }
}

class _LiveTestStatusDialog extends StatefulWidget {
  final String requestId;
  final Stream<Map<String, dynamic>> operations;
  const _LiveTestStatusDialog({
    required this.requestId,
    required this.operations,
  });

  @override
  State<_LiveTestStatusDialog> createState() => _LiveTestStatusDialogState();
}

class _LiveTestStatusDialogState extends State<_LiveTestStatusDialog> {
  StreamSubscription<Map<String, dynamic>>? _sub;
  String _stage = 'QUEUED';

  @override
  void initState() {
    super.initState();
    _sub = widget.operations.listen((event) {
      if (event['requestId'] != widget.requestId) return;
      final status = event['status'];
      if (status is String && mounted) {
        setState(() => _stage = status);
      }
    });
  }

  @override
  void dispose() {
    unawaited(_sub?.cancel());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final (title, body) = switch (_stage) {
      'APP_ACK_RECEIVED' => (
        Strings.current.notificationQueued,
        Strings.current.notificationTestAcknowledged,
      ),
      'EXPIRED' || 'FAILED' || 'REJECTED' || 'UNKNOWN' => (
        Strings.current.notificationTestFailed,
        Strings.current.notificationTestDeliveryFailed,
      ),
      _ => (
        Strings.current.notificationQueued,
        Strings.current.notificationQueuedDetails,
      ),
    };
    return CupertinoAlertDialog(
      title: Text(title),
      content: Text(body),
      actions: [
        CupertinoDialogAction(
          onPressed: () => Navigator.pop(context),
          child: Text(Strings.current.ok),
        ),
      ],
    );
  }
}
