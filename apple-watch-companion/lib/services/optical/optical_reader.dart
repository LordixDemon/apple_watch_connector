import 'package:flutter/services.dart';
import 'optical_frame.dart';

/// Public recognition result. The native process retains the actual key until
/// a separate explicit pairing command consumes this short-lived handle.
final class OpticalCandidate {
  final String token;
  final String advertisedName;
  const OpticalCandidate(this.token, this.advertisedName);
}

final class OpticalReader {
  static const _channel = MethodChannel(
    'dev.applewatchandroid.companion/bridge',
  );
  bool _initialized = false;
  int frames = 0;

  static Future<void> discard(String token) async {
    await _channel.invokeMethod<Object?>('discardOpticalCandidate', {
      'opticalToken': token,
    });
  }

  Future<OpticalCandidate?> add(OpticalFrame frame) async {
    if (!_initialized) {
      await _channel.invokeMethod<Object?>('startOpticalDecoder', {
        'width': frame.width,
        'height': frame.height,
      });
      _initialized = true;
    }
    final value = await _channel.invokeMapMethod<String, dynamic>(
      'processOpticalFrame',
      {'uv': frame.uv},
    );
    if (value == null) {
      throw const FormatException('Missing optical reader response');
    }
    frames = value['frames'] as int;
    if (value['recognized'] != true) return null;
    final token = value['token'], name = value['advertisedName'];
    if (token is! String ||
        name is! String ||
        !RegExp(r'^[0-9a-f-]{36}$').hasMatch(token) ||
        !RegExp(r'^[0-9]{5}[A-Za-z0-9]{3}$').hasMatch(name)) {
      throw const FormatException('Invalid optical identity');
    }
    return OpticalCandidate(token, name);
  }

  Future<void> close() async {
    if (!_initialized) return;
    _initialized = false;
    await _channel.invokeMethod<Object?>('stopOpticalDecoder');
  }
}
