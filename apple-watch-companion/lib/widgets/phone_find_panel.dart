import '../l10n/strings.dart';
import 'dart:async';
import 'package:flutter/cupertino.dart';
import '../models/phone_find_state.dart';
import '../theme/ios_colors.dart';
import '../theme/companion_spacing.dart';

class PhoneFindPanel extends StatefulWidget {
  final PhoneFindState state;
  final Future<bool> Function() stop;
  final Future<bool> Function() permission;
  final DateTime Function()? clock;
  const PhoneFindPanel({
    super.key,
    required this.state,
    required this.stop,
    required this.permission,
    this.clock,
  });
  @override
  State<PhoneFindPanel> createState() => _PhoneFindPanelState();
}

class _PhoneFindPanelState extends State<PhoneFindPanel> {
  Timer? _timer;
  bool _stopping = false;
  bool _opening = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted && widget.state.active) setState(() {});
    });
  }

  @override
  void didUpdateWidget(PhoneFindPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.observedAt != widget.state.observedAt ||
        oldWidget.state.available != widget.state.available) {
      _error = null;
    }
  }

  Future<void> _stop() async {
    setState(() {
      _stopping = true;
      _error = null;
    });
    bool stopped;
    try {
      stopped = await widget.stop();
    } catch (_) {
      stopped = false;
    }
    if (!mounted) return;
    setState(() {
      _stopping = false;
      if (!stopped) _error = Strings.current.stopNotConfirmed;
    });
    // The actual hardware state comes only from APK observation, not this receipt.
  }

  Future<void> _permission() async {
    setState(() {
      _opening = true;
      _error = null;
    });
    bool opened;
    try {
      opened = await widget.permission();
    } catch (_) {
      opened = false;
    }
    if (!mounted) return;
    setState(() {
      _opening = false;
      if (!opened) _error = Strings.current.couldNotOpenFlashlightPermission;
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final stale = state.activeObservationIsStale(
      (widget.clock ?? DateTime.now)(),
    );
    final description = !state.available
        ? Strings.current.phoneServiceUnavailable
        : stale
        ? Strings.current.alertStatusIsStale
        : state.active
        ? (state.behavior == 2
              ? Strings.current.flashlightIsOn
              : state.behavior == 1
              ? Strings.current.soundAndFlashlightAreOn
              : Strings.current.soundAlertStarted)
        : state.didPlay == false
        ? Strings.current.lastAlertDidNotStart
        : state.behavior == 4
        ? Strings.current.requestHadNoSoundOrFlashlight
        : state.didPlay == true
        ? Strings.current.alertStopped
        : Strings.current.noAlertIsRunning;
    return Container(
      margin: CompanionSpacing.sectionMargin,
      padding: const EdgeInsets.all(CompanionSpacing.cardInset),
      decoration: BoxDecoration(
        color: IosColors.secondaryGroupedBackground,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            Strings.current.findPhoneFromWatch,
            style: TextStyle(
              color: IosColors.label,
              fontSize: 16,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            description,
            style: const TextStyle(
              color: IosColors.secondaryLabel,
              fontSize: 14,
            ),
          ),
          if (state.localProbe && state.observedAt != null)
            Text(
              Strings.current.localApkTest,
              style: TextStyle(color: IosColors.secondaryLabel, fontSize: 12),
            ),
          if (state.active && state.available)
            CupertinoButton(
              padding: EdgeInsets.zero,
              onPressed: _stopping ? null : _stop,
              child: Text(
                _stopping
                    ? Strings.current.stopping
                    : Strings.current.stopAlert,
              ),
            ),
          if (!state.flashPermission && state.available) ...[
            const SizedBox(height: 8),
            Text(
              Strings.current.cameraPermissionIsRequiredToFindYourPhoneWith,
              style: TextStyle(color: IosColors.secondaryLabel, fontSize: 13),
            ),
            CupertinoButton(
              padding: EdgeInsets.zero,
              onPressed: _opening ? null : _permission,
              child: Text(Strings.current.allowFlashlight),
            ),
          ],
          if (_error != null)
            Text(_error!, style: const TextStyle(color: IosColors.systemRed)),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}
