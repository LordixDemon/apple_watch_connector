// Explicit profile entry point: synthetic image, no Watch transport or commands.
import 'dart:async';
import 'dart:convert';
import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';
import 'package:apple_watch_companion/services/native_photo_crop.dart';
import 'package:apple_watch_companion/widgets/native_photo_crop_view.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kProfileMode) throw StateError('Use profile mode');
  final recorder = ui.PictureRecorder();
  final canvas = Canvas(recorder);
  canvas.drawColor(const Color(0xff14344f), BlendMode.src);
  for (var y = 0; y < 1536; y += 64) {
    for (var x = 0; x < 1280; x += 64) {
      canvas.drawRect(
        Rect.fromLTWH(x.toDouble(), y.toDouble(), 48, 48),
        Paint()..color = Color.fromARGB(255, x % 256, y % 256, (x + y) % 256),
      );
    }
  }
  final picture = recorder.endRecording();
  final image = await picture.toImage(1280, 1536);
  picture.dispose();
  runApp(CupertinoApp(home: _Profile(source: image)));
}

class _Profile extends StatefulWidget {
  final ui.Image source;
  const _Profile({required this.source});
  @override
  State<_Profile> createState() => _ProfileState();
}

class _ProfileState extends State<_Profile>
    with SingleTickerProviderStateMixin {
  final _crop = ValueNotifier(const NativePhotoCrop(zoom: 2));
  final _frames = <ui.FrameTiming>[];
  late final Ticker _ticker;
  int _updates = 0, _parentBuilds = 0;
  bool _recording = false;
  String _status = 'Warming crop — synthetic image';

  @override
  void initState() {
    super.initState();
    SchedulerBinding.instance.addTimingsCallback(_timings);
    _ticker = createTicker((elapsed) {
      final t = elapsed.inMicroseconds / 1e6;
      _crop.value = _crop.value.transform(
        width: widget.source.width,
        height: widget.source.height,
        viewport: const Size(250, 250 / .82),
        previousFocalPoint: const Offset(125, 150),
        focalPoint: Offset(
          125 + math.sin(t * 2) * .6,
          150 + math.cos(t * 2) * .6,
        ),
        scale: 1 + math.sin(t * 2) * .003,
      );
      if (_recording) _updates++;
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => unawaited(_run()));
  }

  void _timings(List<ui.FrameTiming> frames) {
    if (_recording) _frames.addAll(frames);
  }

  Future<void> _run() async {
    _ticker.start();
    await Future<void>.delayed(const Duration(seconds: 2));
    if (!mounted) return;
    _recording = true;
    final buildsBefore = _parentBuilds;
    await Future<void>.delayed(const Duration(seconds: 12));
    if (!mounted) return;
    _ticker.stop();
    // Flush the engine's last timing batch before reporting.
    await Future<void>.delayed(const Duration(milliseconds: 400));
    if (!mounted) return;
    _recording = false;
    int p95(Iterable<int> values) {
      final sorted = values.toList()..sort();
      return sorted.isEmpty ? 0 : sorted[(sorted.length * .95).ceil() - 1];
    }

    final refresh = View.of(context).display.refreshRate;
    final budget = (1e6 / refresh).round();
    final report = jsonEncode({
      'scope':
          'physical crop painting and value listeners; synthetic image; excludes codec, JPEG, IPC and touch recognizer',
      'frames': _frames.length,
      'updates': _updates,
      'refreshHz': refresh,
      'buildP95Us': p95(_frames.map((f) => f.buildDuration.inMicroseconds)),
      'rasterP95Us': p95(_frames.map((f) => f.rasterDuration.inMicroseconds)),
      'buildOverBudget': _frames
          .where((f) => f.buildDuration.inMicroseconds > budget)
          .length,
      'rasterOverBudget': _frames
          .where((f) => f.rasterDuration.inMicroseconds > budget)
          .length,
      'editorRebuildsDuringCrop': _parentBuilds - buildsBefore,
      'sourceRgbaBytes': widget.source.width * widget.source.height * 4,
    });
    debugPrint('NATIVE_PHOTO_CROP_PROFILE:$report', wrapWidth: 2048);
    setState(() => _status = report);
  }

  @override
  void dispose() {
    SchedulerBinding.instance.removeTimingsCallback(_timings);
    _ticker.dispose();
    _crop.dispose();
    widget.source.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _parentBuilds++;
    return CupertinoPageScaffold(
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Center(
              child: SizedBox(
                width: 250,
                child: NativePhotoCropView(
                  source: widget.source,
                  controller: _crop,
                ),
              ),
            ),
            ValueListenableBuilder<NativePhotoCrop>(
              valueListenable: _crop,
              builder: (_, crop, _) => CupertinoSlider(
                value: crop.zoom,
                min: 1,
                max: 4,
                onChanged: (v) => _crop.value = crop.copyWith(zoom: v),
              ),
            ),
            Text(_status),
          ],
        ),
      ),
    );
  }
}
