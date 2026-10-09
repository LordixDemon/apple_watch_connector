// Profile-only synthetic archive. No Watch transport, intent execution or donation.
import 'dart:async';
import 'dart:convert';
import 'dart:ui';
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';
import 'package:apple_watch_companion/services/native_intent_parameters.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_complication_settings_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kProfileMode) throw StateError('Use profile mode');
  const intent = String.fromEnvironment('PROFILE_SYNTHETIC_INTENT');
  final parameters = NativeIntentParameters.parse(intent);
  if (parameters == null) {
    throw StateError('Provide the synthetic test fixture');
  }
  runApp(
    CupertinoApp(
      home: _Profile(intent: intent, parameters: parameters),
    ),
  );
}

class _Profile extends StatefulWidget {
  final String intent;
  final NativeIntentParameters parameters;
  const _Profile({required this.intent, required this.parameters});
  @override
  State<_Profile> createState() => _ProfileState();
}

class _ProfileState extends State<_Profile>
    with SingleTickerProviderStateMixin {
  final _frames = <FrameTiming>[];
  late final Ticker _ticker;
  CupertinoTextField? _field;
  bool _recording = false;
  int _updates = 0;
  @override
  void initState() {
    super.initState();
    SchedulerBinding.instance.addTimingsCallback(_timings);
    _ticker = createTicker((_) {
      final value = '${70 + _updates % 100}';
      _field!.controller!.text = value;
      _field!.onChanged!(value);
      if (_recording) _updates++;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => unawaited(_run()));
  }

  void _timings(List<FrameTiming> frames) {
    if (_recording) _frames.addAll(frames);
  }

  Future<void> _run() async {
    void visit(Element element) {
      if (element.widget is CupertinoTextField) {
        _field = element.widget as CupertinoTextField;
      } else {
        element.visitChildElements(visit);
      }
    }

    // Sliver children can be materialized after the first route/layout frame.
    for (var attempt = 0; attempt < 6 && _field == null; attempt++) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
      if (!mounted) return;
      (context as Element).visitChildElements(visit);
    }
    if (_field == null) throw StateError('No production parameter field');
    _ticker.start();
    await Future<void>.delayed(const Duration(seconds: 2));
    if (!mounted) return;
    _recording = true;
    await Future<void>.delayed(const Duration(seconds: 12));
    if (!mounted) return;
    _ticker.stop();
    await Future<void>.delayed(const Duration(milliseconds: 400));
    if (!mounted) return;
    _recording = false;
    int p95(Iterable<int> values) {
      final sorted = values.toList()..sort();
      return sorted.isEmpty ? 0 : sorted[(sorted.length * .95).ceil() - 1];
    }

    final refresh = View.of(context).display.refreshRate,
        budget = 1e6 / refresh;
    debugPrint(
      'NATIVE_INTENT_PROFILE:${jsonEncode({'scope': 'physical production interval settings; synthetic controller edits; excludes IME, IPC and Watch', 'frames': _frames.length, 'updates': _updates, 'refreshHz': refresh, 'buildP95Us': p95(_frames.map((f) => f.buildDuration.inMicroseconds)), 'rasterP95Us': p95(_frames.map((f) => f.rasterDuration.inMicroseconds)), 'buildOverBudget': _frames.where((f) => f.buildDuration.inMicroseconds > budget).length, 'rasterOverBudget': _frames.where((f) => f.rasterDuration.inMicroseconds > budget).length})}',
      wrapWidth: 2048,
    );
  }

  @override
  void dispose() {
    _ticker.dispose();
    SchedulerBinding.instance.removeTimingsCallback(_timings);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => NativeComplicationSettingsScreen(
    title: 'Interval settings — profile',
    value: {
      'type': 56,
      'descriptor': {'intent': widget.intent},
    },
    parameters: widget.parameters,
  );
}
