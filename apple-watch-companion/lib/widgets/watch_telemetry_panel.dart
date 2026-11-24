import '../l10n/strings.dart';
import 'dart:async';
import 'package:flutter/cupertino.dart';
import '../models/watch_telemetry.dart';
import '../theme/ios_colors.dart';

class WatchTelemetryPanel extends StatefulWidget {
  final WatchTelemetry telemetry;
  final bool connected;
  final Future<String> Function() refresh;
  final DateTime Function()? clock;
  const WatchTelemetryPanel({
    super.key,
    required this.telemetry,
    required this.connected,
    required this.refresh,
    this.clock,
  });
  @override
  State<WatchTelemetryPanel> createState() => _WatchTelemetryPanelState();
}

class _WatchTelemetryPanelState extends State<WatchTelemetryPanel> {
  Timer? _timer;
  int? _pendingSince;
  String? _result;
  bool _sending = false;
  DateTime get _now => widget.clock?.call() ?? DateTime.now();

  void _syncTimer() {
    final needsTicker =
        widget.connected &&
        (_pendingSince != null || widget.telemetry.isFreshAt(_now));
    if (needsTicker) {
      _timer ??= Timer.periodic(const Duration(seconds: 15), (_) {
        if (!mounted) return;
        setState(() {
          if (_pendingSince != null &&
              _now.millisecondsSinceEpoch - _pendingSince! >= 60000) {
            _pendingSince = null;
            _result = Strings.of(
              context,
            ).theWatchHasNotRepliedYetRefreshAgain;
          }
        });
        _syncTimer();
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
  void didUpdateWidget(WatchTelemetryPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.connected) {
      _pendingSince = null;
      _result = null;
    } else if (_pendingSince != null &&
        (widget.telemetry.observedAt ?? 0) >= _pendingSince! &&
        (widget.telemetry.observedAt ?? 0) >
            (oldWidget.telemetry.observedAt ?? 0)) {
      _pendingSince = null;
      _result = Strings.of(context).dataUpdated;
    }
    _syncTimer();
  }

  Future<void> _refresh() async {
    if (_sending || _pendingSince != null || !widget.connected) return;
    setState(() {
      _sending = true;
      _pendingSince = _now.millisecondsSinceEpoch;
      _result = null;
    });
    _syncTimer();
    String status;
    try {
      status = await widget.refresh();
    } catch (_) {
      status = 'UNAVAILABLE';
    }
    if (!mounted) return;
    setState(() {
      _sending = false;
      if (status != 'QUEUED') {
        _pendingSince = null;
        _result = Strings.of(context).couldNotSendRequest;
      }
    });
    _syncTimer();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final telemetry = widget.telemetry;
    final fresh = telemetry.isFreshAt(_now);
    final stamp = telemetry.observedAt;
    final local = stamp == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(stamp).toLocal();
    String two(int number) => number.toString().padLeft(2, '0');
    final updated = local == null
        ? s.noWatchDataYet
        : s.telemetryStatusTime(
            (fresh ? s.updated : s.dataIsStale).toString(),
            (two(local.hour)).toString(),
            (two(local.minute)).toString(),
            (two(local.second)).toString(),
          );
    final charging = telemetry.isCharging == null
        ? s.chargingUnknown
        : telemetry.isCharging!
        ? s.charging
        : s.notCharging;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          telemetry.batteryLevel == null
              ? s.battery2
              : s.batteryReading(
                  (fresh ? s.battery3 : s.lastBattery).toString(),
                  (telemetry.batteryLevel).toString(),
                  (charging).toString(),
                ),
          style: TextStyle(
            color: fresh ? IosColors.label : IosColors.secondaryLabel,
            fontSize: 13,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          widget.connected ? updated : s.noConnectionToWatch,
          style: const TextStyle(color: IosColors.secondaryLabel, fontSize: 12),
        ),
        if (_result != null)
          Text(
            _result!,
            style: const TextStyle(
              color: IosColors.secondaryLabel,
              fontSize: 12,
            ),
          ),
        CupertinoButton(
          padding: const EdgeInsets.symmetric(vertical: 8),
          minimumSize: const Size(44, 44),
          onPressed: widget.connected && !_sending && _pendingSince == null
              ? _refresh
              : null,
          child: Text(
            _pendingSince != null ? s.waitingForWatchReply : s.refreshData,
            style: const TextStyle(fontSize: 14),
          ),
        ),
      ],
    );
  }
}
