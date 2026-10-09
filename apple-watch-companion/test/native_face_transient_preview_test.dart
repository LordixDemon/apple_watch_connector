import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_face_transient_preview.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';

void main() {
  testWidgets('Visual shades follow current native customization rules', (
    tester,
  ) async {
    final profiles = await tester.runAsync(NativeFaceGallery.load);
    final preview = NativeFaceTransientPreview();
    var checked = 0;
    for (final profile in profiles!) {
      for (final field in profile.editableFields) {
        final section = profile.pigmentSection(field);
        if (section == null) continue;
        for (final pigment in section.options.values.where(
          (p) => p.supportsShade,
        )) {
          final source =
              jsonDecode(profile.configurationJson) as Map<String, dynamic>;
          final before = jsonEncode(source);
          final expected = jsonDecode(before) as Map<String, dynamic>;
          final token = pigment.tokenAt(25);
          profile.selectPigment(expected, field, token);
          preview.bind(source, profile.family, enabled: true);
          preview.show(field, token);
          expect(
            preview.configuration,
            expected,
            reason: '${profile.family}/$field/$token',
          );
          expect(jsonEncode(source), before);
          checked++;
        }
      }
    }
    expect(checked, greaterThanOrEqualTo(19));
    preview.dispose();
  });
  test(
    'Transient shades share opaque intents and leave source/metrics untouched',
    () {
      final opaque = {'intent': Object()};
      final source = <String, dynamic>{
        'customization': {'color': 'standard.orange', 'style': 'simple'},
        'complications': opaque,
        'metrics': {'numberOfCompanionEdits': 4},
      };
      final preview = NativeFaceTransientPreview();
      var notifications = 0;
      preview.addListener(() => notifications++);
      preview.bind(source, ('pair', 'epoch'), enabled: true);
      preview.show('color', 'standard.orange:0.25');
      expect(
        preview.configuration!['customization']['color'],
        'standard.orange:0.25',
      );
      expect(source['customization']['color'], 'standard.orange');
      expect(
        identical(preview.configuration!['complications'], opaque),
        isTrue,
      );
      expect(
        identical(preview.configuration!['metrics'], source['metrics']),
        isTrue,
      );
      expect(
        () => preview.configuration!['customization']['color'] = 'changed',
        throwsUnsupportedError,
      );
      preview.show('color', 'standard.orange:0.25');
      expect(notifications, 1);
      preview.bind(source, ('pair', 'epoch'), enabled: true);
      expect(
        preview.configuration!['customization']['color'],
        'standard.orange:0.25',
      );
      preview.clear();
      expect(identical(preview.configuration, source), isTrue);
      expect(notifications, 2);
      preview.dispose();
    },
  );

  test(
    'Rebinding, disable and external style edits discard transient shades',
    () {
      final source = <String, dynamic>{
        'customization': {'color': 'standard.blue', 'style': 'simple'},
      };
      final preview = NativeFaceTransientPreview();
      void drag() => preview.show('color', 'standard.blue:0.41');
      preview.bind(source, ('pair', 'epoch'), enabled: true);
      drag();
      preview.bind(source, ('pair', 'epoch'), enabled: false);
      drag();
      expect(identical(preview.configuration, source), isTrue);
      preview.bind(source, ('pair', 'epoch'), enabled: true);
      expect(identical(preview.configuration, source), isTrue);
      drag();
      source['customization']['style'] = 'detailed';
      preview.bind(source, ('pair', 'epoch'), enabled: true);
      expect(identical(preview.configuration, source), isTrue);
      drag();
      preview.bind(source, ('different-pair', 'epoch'), enabled: true);
      expect(identical(preview.configuration, source), isTrue);
      drag();
      final replacement = <String, dynamic>{...source};
      preview.bind(replacement, ('different-pair', 'epoch'), enabled: true);
      expect(identical(preview.configuration, replacement), isTrue);
      preview.dispose();
    },
  );
}
