import 'package:flutter/services.dart';
import 'native_monogram_text_rules.dart';

/// Original editing order: native composed range, host locale uppercase, validation.
/// An unavailable host never falls back to Dart's locale-independent uppercase.
/// macOS uses Foundation directly; Android uses a versioned Foundation module.
/// This local channel has no Watch transport or preference-write access.
final class NativeMonogramTextNormalizer {
  static const _channel = MethodChannel('dev.applewatchandroid.companion/text');

  const NativeMonogramTextNormalizer();

  Future<({String text, bool valid})> normalize(String input) async {
    if (input.length > 4096 || !_wellFormed(input)) {
      throw const FormatException('Invalid monogram input.');
    }
    final result = await _channel.invokeMethod<Object>('normalizeMonogram', {
      'text': input,
    });
    if (result is! String || result.length > 12288 || !_wellFormed(result)) {
      throw const FormatException('Invalid native text normalization.');
    }
    return (text: result, valid: NativeMonogramTextRules.valid(result));
  }

  static bool _wellFormed(String text) {
    for (var i = 0; i < text.length; i++) {
      final c = text.codeUnitAt(i);
      if (c >= 0xd800 && c <= 0xdbff) {
        if (++i >= text.length) return false;
        final next = text.codeUnitAt(i);
        if (next < 0xdc00 || next > 0xdfff) return false;
      } else if (c >= 0xdc00 && c <= 0xdfff) {
        return false;
      }
    }
    return true;
  }
}
