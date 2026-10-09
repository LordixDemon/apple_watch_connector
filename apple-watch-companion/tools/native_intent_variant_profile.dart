// Explicit profile entry point: synthetic data, no Bridge/Watch transport.
import 'dart:async';
import 'dart:convert';
import 'dart:ui' show FrameTiming;
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';
import 'package:apple_watch_companion/services/native_complication_choices.dart';
import 'package:apple_watch_companion/services/native_intent_variants.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_complication_settings_screen.dart';
import 'package:apple_watch_companion/widgets/ios_list_tile.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kProfileMode) throw StateError('Use profile mode');
  final rows =
      jsonDecode(const String.fromEnvironment('PROFILE_SYNTHETIC_PRESETS'))
          as List;
  final choices = <NativeComplicationChoice>[
    for (final row in rows)
      (
        title: row['title'] as String,
        value: Map<String, dynamic>.from(row['value'] as Map),
      ),
  ];
  final index = NativeIntentVariantIndex();
  final source = Object();
  var reads = 0;
  NativeIntentVariants? bind() => index.bind(
    source: source,
    slot: 'synthetic',
    current: choices.first.value,
    choices: () {
      reads++;
      return choices;
    },
  );
  final cold = Stopwatch()..start();
  final model = bind();
  cold.stop();
  if (model == null) throw StateError('Provide synthetic received presets');
  final warm = Stopwatch()..start();
  for (var i = 0; i < 250; i++) {
    bind();
  }
  warm.stop();
  if (reads != 1) throw StateError('Variant cache missed');
  debugPrint(
    'NATIVE_VARIANT_CACHE:${jsonEncode({'coldUs': cold.elapsedMicroseconds, 'warmMeanUs': warm.elapsedMicroseconds / 250, 'catalogReads': reads})}',
  );
  runApp(
    CupertinoApp(
      home: _Profile(model: model, value: choices.first.value),
    ),
  );
}

class _Profile extends StatefulWidget {
  final NativeIntentVariants model;
  final Map<String, dynamic> value;
  const _Profile({required this.model, required this.value});
  @override
  State<_Profile> createState() => _ProfileState();
}

class _ProfileState extends State<_Profile> {
  final _root = GlobalKey();
  final _frames = <FrameTiming>[];
  bool _recording = false;
  @override
  void initState() {
    super.initState();
    SchedulerBinding.instance.addTimingsCallback(_timings);
    WidgetsBinding.instance.addPostFrameCallback((_) => unawaited(_run()));
  }

  void _timings(List<FrameTiming> frames) {
    if (_recording) _frames.addAll(frames);
  }

  List<IosListTile> _tiles(Element root) {
    final result = <IosListTile>[];
    void visit(Element e) {
      if (e.widget is IosListTile) result.add(e.widget as IosListTile);
      e.visitChildElements(visit);
    }

    visit(root);
    return result;
  }

  Future<void> _cycle() async {
    for (final field in widget.model.fields) {
      final title = NativeIntentVariants.fieldTitle(field);
      final setting = _tiles(
        _root.currentContext! as Element,
      ).singleWhere((v) => v.title == title);
      final previous = setting.trailingText;
      setting.onTap!();
      await Future<void>.delayed(const Duration(milliseconds: 450));
      if (!mounted) return;
      final navigator = Navigator.of(context).context as Element;
      final option = _tiles(navigator)
          .where(
            (v) => v.onTap != null && v.title != previous && v.title != title,
          )
          .last;
      option.onTap!();
      await Future<void>.delayed(const Duration(milliseconds: 450));
      if (!mounted) return;
      final changed = _tiles(
        _root.currentContext! as Element,
      ).singleWhere((v) => v.title == title);
      if (changed.trailingText == previous) {
        throw StateError('Selection did not change');
      }
    }
  }

  Future<void> _run() async {
    await Future<void>.delayed(const Duration(milliseconds: 500));
    for (var i = 0; i < 2; i++) {
      await _cycle();
    }
    for (var round = 0; round < 3; round++) {
      if (!mounted) return;
      _frames.clear();
      _recording = true;
      for (var cycle = 0; cycle < 10; cycle++) {
        await _cycle();
      }
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
        'NATIVE_VARIANT_PROFILE:${jsonEncode({'scope': 'physical production preset settings and pickers; synthetic callbacks; excludes IME, IPC and Watch', 'round': round, 'cycles': 10, 'selections': 10 * widget.model.fields.length, 'frames': _frames.length, 'refreshHz': refresh, 'buildP95Us': p95(_frames.map((f) => f.buildDuration.inMicroseconds)), 'rasterP95Us': p95(_frames.map((f) => f.rasterDuration.inMicroseconds)), 'buildOverBudget': _frames.where((f) => f.buildDuration.inMicroseconds > budget).length, 'rasterOverBudget': _frames.where((f) => f.rasterDuration.inMicroseconds > budget).length})}',
        wrapWidth: 2048,
      );
    }
  }

  @override
  void dispose() {
    SchedulerBinding.instance.removeTimingsCallback(_timings);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => NativeComplicationSettingsScreen(
    key: _root,
    title: 'Synthetic preset settings — profile',
    value: widget.value,
    variants: widget.model,
  );
}
