import 'dart:ui' as ui;
import 'package:flutter/cupertino.dart';
import '../services/native_photo_crop.dart';

/// Repaint the crop, not the album/editor, on every drag or pinch update.
class NativePhotoCropView extends StatefulWidget {
  final ui.Image source;
  final ValueNotifier<NativePhotoCrop> controller;
  final bool enabled;
  const NativePhotoCropView({
    super.key,
    required this.source,
    required this.controller,
    this.enabled = true,
  });

  @override
  State<NativePhotoCropView> createState() => _NativePhotoCropViewState();
}

class _NativePhotoCropViewState extends State<NativePhotoCropView> {
  Offset? _focalPoint;
  double _scale = 1;
  int _pointers = 0;

  @override
  void didUpdateWidget(NativePhotoCropView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.source != widget.source ||
        oldWidget.controller != widget.controller ||
        oldWidget.enabled != widget.enabled) {
      _focalPoint = null;
    }
  }

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<NativePhotoCrop>(
    valueListenable: widget.controller,
    builder: (_, crop, _) => AspectRatio(
      aspectRatio: crop.aspect,
      child: LayoutBuilder(
        builder: (_, constraints) => ClipRRect(
          borderRadius: BorderRadius.circular(30),
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onScaleStart: widget.enabled
                ? (details) {
                    _focalPoint = details.localFocalPoint;
                    _scale = 1;
                    _pointers = details.pointerCount;
                  }
                : null,
            onScaleUpdate: widget.enabled
                ? (details) {
                    final previous = _focalPoint;
                    // Joining/lifting a finger moves the recognizer's centroid.
                    // Rebase without moving the image at that transition.
                    if (previous != null && _pointers == details.pointerCount) {
                      widget.controller.value = widget.controller.value
                          .transform(
                            width: widget.source.width,
                            height: widget.source.height,
                            viewport: constraints.biggest,
                            previousFocalPoint: previous,
                            focalPoint: details.localFocalPoint,
                            scale: details.scale / _scale,
                          );
                    }
                    _focalPoint = details.localFocalPoint;
                    _pointers = details.pointerCount;
                    if (details.scale.isFinite && details.scale > 0) {
                      _scale = details.scale;
                    }
                  }
                : null,
            onScaleEnd: widget.enabled ? (_) => _focalPoint = null : null,
            child: RepaintBoundary(
              child: CustomPaint(
                painter: _PhotoCropPainter(
                  widget.source,
                  crop.rect(widget.source.width, widget.source.height),
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}

class _PhotoCropPainter extends CustomPainter {
  final ui.Image source;
  final ui.Rect crop;
  _PhotoCropPainter(this.source, this.crop);
  @override
  void paint(Canvas canvas, Size size) => canvas.drawImageRect(
    source,
    crop,
    Offset.zero & size,
    Paint()..filterQuality = FilterQuality.medium,
  );
  @override
  bool shouldRepaint(_PhotoCropPainter old) =>
      source != old.source || crop != old.crop;
}
