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
  Future<void> _choose(NativeWatchSetting setting) async {
    final value = await showCupertinoModalPopup<bool>(
      context: context,
      builder: (context) => CupertinoActionSheet(
        title: Text(setting.title),
        actions: [
          for (final enabled in [true, false])
            CupertinoActionSheetAction(
              onPressed: () => Navigator.pop(context, enabled),
              child: Text(
                enabled ? Strings.current.enable : Strings.current.disable,
              ),
            ),
        ],
        cancelButton: CupertinoActionSheetAction(
          onPressed: () => Navigator.pop(context),
          child: Text(Strings.current.cancel),
        ),
      ),
    );
    if (!mounted || value == null || !widget.settings.connected) return;
    setState(() {
      _busy = true;
      _receipt = null;
    });
    String status;
    try {
      status = await widget.onChange(setting, value);
    } catch (_) {
      status = 'UNAVAILABLE';
    }
    if (!mounted) return;
    setState(() {
      _busy = false;
      _receipt = status == 'QUEUED'
          ? Strings.current.commandSentWaitingForTheWatchResult
          : Strings.current.commandNotSentCheckTheConnection;
    });
  }

  @override
  Widget build(BuildContext context) => IosListSection(
    header: Strings.current.watchSettings,
    footer:
        _receipt ??
        (widget.settings.connected
            ? Strings.current.valuesComeFromWatchMessagesNotReceivedMeansThe
            : Strings.current.connectYourWatchToChangeSettings),
    children: [
      for (final setting in NativeWatchSetting.values)
        IosListTile(
          title: setting.title,
          trailingText: switch (widget.settings.values[setting]?.value) {
            true => Strings.current.on,
            false => Strings.current.off,
            null => Strings.current.notReceived,
          },
          onTap: widget.settings.connected && !_busy
              ? () => _choose(setting)
              : null,
        ),
      if (_busy)
        const Padding(
          padding: EdgeInsets.all(12),
          child: CupertinoActivityIndicator(color: IosColors.systemOrange),
        ),
    ],
  );
}
