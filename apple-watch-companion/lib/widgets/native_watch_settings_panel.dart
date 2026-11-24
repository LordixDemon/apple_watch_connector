import '../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import '../models/native_watch_settings.dart';
import '../theme/ios_colors.dart';
import 'ios_list_section.dart';
import 'ios_list_tile.dart';

class NativeWatchSettingsPanel extends StatefulWidget {
  final NativeWatchSettings settings;
  final Future<String> Function(NativeWatchSetting, bool) onChange;
  const NativeWatchSettingsPanel({
    super.key,
    required this.settings,
    required this.onChange,
  });
  @override
  State<NativeWatchSettingsPanel> createState() =>
      _NativeWatchSettingsPanelState();
}

class _NativeWatchSettingsPanelState extends State<NativeWatchSettingsPanel> {
  bool _busy = false;
  String? _receipt;
  NativeWatchSetting? _pendingSetting;
  DateTime? _pendingBaselineObservedAt;

  @override
  void didUpdateWidget(NativeWatchSettingsPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.settings.connected) {
      _receipt = null;
      _pendingSetting = null;
      _pendingBaselineObservedAt = null;
      return;
    }
    final pending = _pendingSetting;
    if (pending != null) {
      final currentObs = widget.settings.values[pending];
      final confirmed =
          currentObs != null &&
          (_pendingBaselineObservedAt == null ||
              currentObs.observedAt.isAfter(_pendingBaselineObservedAt!));
      if (confirmed) {
        _receipt = null;
        _pendingSetting = null;
        _pendingBaselineObservedAt = null;
      }
    }
  }

  Future<void> _send(NativeWatchSetting setting, bool value) async {
    if (!mounted || _busy || !widget.settings.connected) return;
    final baseline = widget.settings.values[setting]?.observedAt;
    setState(() {
      _busy = true;
      _receipt = null;
      _pendingSetting = setting;
      _pendingBaselineObservedAt = baseline;
    });
    String status;
    try {
      status = await widget.onChange(setting, value);
    } catch (_) {
      status = 'UNAVAILABLE';
    }
    if (!mounted) return;
    final s = Strings.of(context);
    setState(() {
      _busy = false;
      if (status == 'QUEUED') {
        _receipt = s.commandSentWaitingForTheWatchResult;
      } else {
        _pendingSetting = null;
        _pendingBaselineObservedAt = null;
        _receipt = s.commandNotSentCheckTheConnection;
      }
    });
  }

  Future<void> _choose(NativeWatchSetting setting) async {
    final s = Strings.of(context);
    final value = await showCupertinoModalPopup<bool>(
      context: context,
      builder: (context) => CupertinoActionSheet(
        title: Text(setting.localizedTitle(s)),
        actions: [
          for (final enabled in [true, false])
            CupertinoActionSheetAction(
              onPressed: () => Navigator.pop(context, enabled),
              child: Text(enabled ? s.enable : s.disable),
            ),
        ],
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(context),
          child: Text(s.cancel),
        ),
      ),
    );
    if (!mounted || value == null || !widget.settings.connected) return;
    await _send(setting, value);
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    return IosListSection(
      header: s.watchSettings,
      footer:
          _receipt ??
          (widget.settings.connected
              ? s.valuesComeFromWatchMessagesNotReceivedMeansThe
              : s.connectYourWatchToChangeSettings),
      children: [
        for (final setting in NativeWatchSetting.values)
          Builder(
            builder: (context) {
              final obs = widget.settings.values[setting];
              if (obs != null) {
                return IosListTile(
                  title: setting.localizedTitle(s),
                  trailing: CupertinoSwitch(
                    value: obs.value,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: widget.settings.connected && !_busy
                        ? (next) => _send(setting, next)
                        : null,
                  ),
                  showChevron: false,
                );
              }
              return IosListTile(
                title: setting.localizedTitle(s),
                trailingText: s.notReceived,
                onTap: widget.settings.connected && !_busy
                    ? () => _choose(setting)
                    : null,
              );
            },
          ),
        if (_busy)
          const Padding(
            padding: EdgeInsets.all(12),
            child: CupertinoActivityIndicator(color: IosColors.systemOrange),
          ),
      ],
    );
  }
}
