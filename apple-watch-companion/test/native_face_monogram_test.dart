import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_monogram.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('Native monogram toggles retain unrelated and future data', () async {
    final profiles = await NativeFaceGallery.load();
    final color = profiles.singleWhere((p) => p.family == 'type:color rich');
    final monogram = color.complicationLayout!.monogram!;
    expect(monogram.slot, 'monogram');
    final config = jsonDecode(color.configurationJson) as Map<String, dynamic>;
    config['complications'] = {
      'top left': {
        'future': {
          'opaque': [1, true, 'retained'],
        },
      },
    };
    final original = jsonEncode(config);
    expect(monogram.value(config), false);
    expect(monogram.select(config, true), true);
    expect(config['complications']['monogram'], {'app': 'monogram'});
    config['complications']['monogram']['future'] = 'native extension';
    expect(monogram.select(config, true), false);
    expect(config['complications']['monogram']['future'], 'native extension');
    expect(monogram.select(config, false), true);
    expect(jsonEncode(config), original);
    config['complications'] = {
      'monogram': {'app': 'monogram'},
    };
    expect(monogram.select(config, false), true);
    expect(config.containsKey('complications'), false);
    for (final malformed in [
      false,
      ['invalid'],
      {'monogram': 1},
      {'monogram': {}},
    ]) {
      config['complications'] = malformed;
      final before = jsonEncode(config);
      expect(monogram.value(config), isNull);
      expect(monogram.select(config, true), false);
      expect(monogram.select(config, false), false);
      expect(jsonEncode(config), before);
    }
    expect(
      profiles.where((p) => p.complicationLayout?.monogram != null),
      hasLength(1),
    );
  });

  test('Monogram metadata cannot target ordinary complication slots', () {
    for (final bad in [
      {
        'slot': 'top',
        'enabled': {'app': 'monogram'},
      },
      {
        'slot': 'monogram',
        'enabled': {'app': 'battery'},
      },
      {
        'slot': 'monogram',
        'enabled': {'app': 'monogram', 'text': 'invented'},
      },
    ]) {
      expect(
        () => NativeFaceMonogram.fromJson(bad, {'monogram'}),
        throwsFormatException,
      );
    }
  });
}
