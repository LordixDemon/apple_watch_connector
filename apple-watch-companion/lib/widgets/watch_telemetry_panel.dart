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
  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 15), (_) {
      if (!mounted) return;
      setState(() {
        if (_pendingSince != null &&
            _now.millisecondsSinceEpoch - _pendingSince! >= 60000) {
          _pendingSince = null;
          _result = Strings.current.theWatchHasNotRepliedYetRefreshAgain;
        }
      });
    });
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
      _result = Strings.current.dataUpdated;
    }
  }

  Future<void> _refresh() async {
    if (_sending || _pendingSince != null || !widget.connected) return;
    setState(() {
      _sending = true;
      _pendingSince = _now.millisecondsSinceEpoch;
      _result = null;
    });
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
        _result = Strings.current.couldNotSendRequest;
      }
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final telemetry = widget.telemetry;
    final fresh = telemetry.isFreshAt(_now);
    final stamp = telemetry.observedAt;
    final local = stamp == null
        ? null
        : DateTime.fromMillisecondsSinceEpoch(stamp).toLocal();
    String two(int number) => number.toString().padLeft(2, '0');
    final updated = local == null
        ? Strings.current.noWatchDataYet
        : Strings.current.telemetryStatusTime(
            (fresh ? Strings.current.updated : Strings.current.dataIsStale)
                .toString(),
            (two(local.hour)).toString(),
            (two(local.minute)).toString(),
            (two(local.second)).toString(),
          );
    final charging = telemetry.isCharging == null
        ? Strings.current.chargingUnknown
        : telemetry.isCharging!
        ? Strings.current.charging
        : Strings.current.notCharging;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          telemetry.batteryLevel == null
              ? Strings.current.battery2
              : Strings.current.batteryReading(
                  (fresh
                          ? Strings.current.battery3
                          : Strings.current.lastBattery)
                      .toString(),
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
          widget.connected ? updated : Strings.current.noConnectionToWatch,
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
            _pendingSince != null
                ? Strings.current.waitingForWatchReply
                : Strings.current.refreshData,
            style: const TextStyle(fontSize: 14),
          ),
        ),
      ],
    );
  }
}
