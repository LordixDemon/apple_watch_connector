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

  void _syncTimer() {
    if (widget.state.active) {
      _timer ??= Timer.periodic(const Duration(seconds: 1), (_) {
        if (mounted && widget.state.active) setState(() {});
      });
    } else {
      _timer?.cancel();
      _timer = null;
    }
  }

  @override
  void initState() {
    super.initState();
    _syncTimer();
  }

  @override
  void didUpdateWidget(PhoneFindPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state.observedAt != widget.state.observedAt ||
        oldWidget.state.available != widget.state.available) {
      _error = null;
    }
    _syncTimer();
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
      if (!stopped) _error = Strings.of(context).stopNotConfirmed;
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
      if (!opened) {
        _error = Strings.of(context).couldNotOpenFlashlightPermission;
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final state = widget.state;
    final stale = state.activeObservationIsStale(
      (widget.clock ?? DateTime.now)(),
    );
    final description = !state.available
        ? s.phoneServiceUnavailable
        : stale
        ? s.alertStatusIsStale
        : state.active
        ? (state.behavior == 2
              ? s.flashlightIsOn
              : state.behavior == 1
              ? s.soundAndFlashlightAreOn
              : s.soundAlertStarted)
        : state.didPlay == false
        ? s.lastAlertDidNotStart
        : state.behavior == 4
        ? s.requestHadNoSoundOrFlashlight
        : state.didPlay == true
        ? s.alertStopped
        : s.noAlertIsRunning;
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
            s.findPhoneFromWatch,
            style: const TextStyle(
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
              s.localApkTest,
              style: const TextStyle(
                color: IosColors.secondaryLabel,
                fontSize: 12,
              ),
            ),
          if (state.active && state.available)
            CupertinoButton(
              padding: EdgeInsets.zero,
              onPressed: _stopping ? null : _stop,
              child: Text(_stopping ? s.stopping : s.stopAlert),
            ),
          if (!state.flashPermission && state.available) ...[
            const SizedBox(height: 8),
            Text(
              s.cameraPermissionIsRequiredToFindYourPhoneWith,
              style: const TextStyle(
                color: IosColors.secondaryLabel,
                fontSize: 13,
              ),
            ),
            CupertinoButton(
              padding: EdgeInsets.zero,
              onPressed: _opening ? null : _permission,
              child: Text(s.allowFlashlight),
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
