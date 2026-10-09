import 'dart:async';
import 'package:camera/camera.dart';
import 'package:flutter/cupertino.dart';
import '../../l10n/strings.dart';
import '../../services/optical/optical_capture.dart';
import '../../services/optical/optical_frame.dart';
import '../../services/optical/optical_reader.dart';
import '../../theme/ios_colors.dart';

/// Diagnostic capture remains separate from the live recognition mode.
class OpticalPairingLabScreen extends StatelessWidget {
  const OpticalPairingLabScreen({super.key});
  @override
  Widget build(BuildContext context) => const OpticalPairingCameraScreen();
}

class OpticalPairingCameraScreen extends StatefulWidget {
  final bool recognize;
  const OpticalPairingCameraScreen({super.key, this.recognize = false});
  @override
  State<OpticalPairingCameraScreen> createState() =>
      _OpticalPairingLabScreenState();
}

class _OpticalPairingLabScreenState extends State<OpticalPairingCameraScreen>
    with WidgetsBindingObserver {
  CameraController? _camera;
  OpticalCapture? _capture;
  OpticalReader? _reader;
  OpticalCandidate? _candidate;
  Future<void> _operations = Future.value();
  Future<void>? _frameWrite;
  bool _foreground = true, _leaving = false, _recording = false;
  bool _busy = true, _error = false;
  bool _noMatch = false;
  int _frames = 0;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _queue(_open);
  }

  void _queue(Future<void> Function() operation) {
    _operations = _operations.then((_) => operation()).catchError((Object _) {
      if (mounted && !_leaving) {
        setState(() {
          _error = true;
          _busy = false;
          _recording = false;
        });
      }
    });
  }

  Future<void> _open() async {
    if (!_foreground || _leaving || _camera != null) {
      return;
    }
    if (mounted) {
      setState(() {
        _busy = true;
        _error = false;
        _noMatch = false;
      });
    }
    final cameras = await availableCameras();
    final description = cameras.firstWhere(
      (c) => c.lensDirection == CameraLensDirection.back,
    );
    final controller = CameraController(
      description,
      ResolutionPreset.high,
      enableAudio: false,
      imageFormatGroup: ImageFormatGroup.yuv420,
    );
    _camera = controller;
    try {
      await controller.initialize();
      if (!_foreground || _leaving) {
        await _close();
        return;
      }
      await controller.setFocusMode(FocusMode.auto);
      await controller.setExposureMode(ExposureMode.auto);
      if (mounted) {
        setState(() => _busy = false);
      }
    } catch (_) {
      await _close();
      rethrow;
    }
  }

  Future<void> _close() async {
    final controller = _camera;
    _camera = null;
    _recording = false;
    if (controller != null) {
      try {
        if (controller.value.isStreamingImages) {
          await controller.stopImageStream();
        }
      } finally {
        try {
          await _frameWrite;
          await _capture?.close(discard: _leaving);
          await _reader?.close();
          _reader = null;
        } finally {
          _capture = null;
          await controller.dispose();
        }
      }
    }
    if (mounted && !_leaving) {
      setState(() => _busy = false);
    }
  }

  Future<void> _record() async {
    final controller = _camera;
    if (controller == null ||
        !controller.value.isInitialized ||
        _recording ||
        !_foreground ||
        _leaving) {
      return;
    }
    if (widget.recognize) {
      _reader = OpticalReader();
      _candidate = null;
    } else {
      _capture = await OpticalCapture.start();
    }
    if (!_foreground || _leaving) {
      await _close();
      return;
    }
    setState(() {
      _frames = 0;
      _recording = true;
      _error = false;
      _noMatch = false;
    });
    await controller.startImageStream((image) {
      if (!_recording || _frameWrite != null || !_foreground || _leaving) {
        return;
      }
      _frameWrite = _writeFrame(
        image,
        controller.description.sensorOrientation,
      ).whenComplete(() => _frameWrite = null);
    });
  }

  Future<void> _writeFrame(CameraImage image, int rotation) async {
    try {
      if (image.format.group != ImageFormatGroup.yuv420 ||
          image.planes.length != 3) {
        throw const FormatException('Unsupported camera format');
      }
      final u = image.planes[1], v = image.planes[2];
      final frame = OpticalFrame.fromYuv420(
        image.width,
        image.height,
        OpticalPlane(u.bytes, u.bytesPerRow, u.bytesPerPixel ?? 1),
        OpticalPlane(v.bytes, v.bytesPerRow, v.bytesPerPixel ?? 1),
      );
      final capture = _capture;
      if (widget.recognize) {
        _candidate = await _reader?.add(frame);
      } else {
        await capture?.add(frame, rotation);
      }
      if (mounted && !_leaving) {
        setState(
          () => _frames = widget.recognize
              ? _reader?.frames ?? _frames
              : capture?.frames ?? _frames,
        );
      }
      if (_candidate != null ||
          (widget.recognize && _frames >= 90) ||
          capture?.full == true) {
        if (widget.recognize && _candidate == null) {
          _error = true;
          _noMatch = true;
        }
        _recording = false;
        _queue(_finish);
      }
    } catch (_) {
      _recording = false;
      if (mounted && !_leaving) {
        setState(() => _error = true);
      }
      _queue(_finish);
    }
  }

  Future<void> _finish() async {
    _recording = false;
    if (_camera?.value.isStreamingImages == true) {
      await _camera?.stopImageStream();
    }
    await _frameWrite;
    await _capture?.close(discard: _error);
    _capture = null;
    await _reader?.close();
    _reader = null;
    if (mounted && !_leaving) {
      setState(() {});
      if (_candidate != null) Navigator.pop(context, _candidate);
    }
  }

  Future<void> _restart() async {
    await _close();
    await OpticalCapture.clear();
    await _open();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    if (_foreground) {
      _queue(_open);
    } else {
      _queue(_close);
    }
  }

  @override
  void dispose() {
    _leaving = true;
    WidgetsBinding.instance.removeObserver(this);
    _queue(() async {
      try {
        await _close();
      } finally {
        await OpticalCapture.clear();
      }
    });
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.current, camera = _camera;
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        middle: Text(
          widget.recognize ? s.opticalScanTitle : s.opticalPairingLab,
        ),
      ),
      child: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: Stack(
                alignment: Alignment.center,
                children: [
                  if (camera?.value.isInitialized == true)
                    Center(child: CameraPreview(camera!))
                  else if (_busy)
                    const CupertinoActivityIndicator()
                  else
                    const Icon(
                      CupertinoIcons.camera,
                      size: 56,
                      color: IosColors.secondaryLabel,
                    ),
                  IgnorePointer(
                    child: Container(
                      width: 220,
                      height: 260,
                      decoration: BoxDecoration(
                        borderRadius: BorderRadius.circular(48),
                        border: Border.all(
                          color: CupertinoColors.white,
                          width: 2,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(20),
              child: Column(
                children: [
                  Text(
                    _error
                        ? _noMatch
                              ? s.opticalNoMatch
                              : s.opticalCameraError
                        : widget.recognize
                        ? s.opticalScanHint
                        : s.opticalCaptureHint,
                    textAlign: TextAlign.center,
                    style: const TextStyle(color: IosColors.label),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    widget.recognize
                        ? s.opticalAnalyzedFrames(_frames)
                        : s.opticalFrames(_frames),
                    style: const TextStyle(color: IosColors.secondaryLabel),
                  ),
                  const SizedBox(height: 12),
                  CupertinoButton.filled(
                    onPressed: _recording
                        ? () => _queue(_finish)
                        : _busy
                        ? null
                        : _error
                        ? () => _queue(_restart)
                        : () => _queue(_record),
                    child: Text(
                      _recording
                          ? s.opticalStopCapture
                          : _error
                          ? s.opticalRetry
                          : widget.recognize
                          ? s.opticalScan
                          : s.opticalCapture,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
