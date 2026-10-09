import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_complication_layout.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_options.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Native California layout uses JSON slot keys, order and style rules',
    () async {
      final profile = (await NativeFaceGallery.load()).singleWhere(
        (p) => p.family == 'type:california',
      );
      final draft =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      final layout = profile.complicationLayout!;
      expect(layout.order, [
        'top left',
        'top right',
        'bottom left',
        'bottom right',
        'bezel',
        'subdial top',
        'subdial bottom',
      ]);
      expect(layout.title('bezel', 'en_US'), 'Top Middle');
      expect(layout.title('subdial top', 'en'), 'Sub-dial Top');
      draft['complications'] = {
        'subdial top': {
          'type': 99,
          'future': {'opaque': true},
        },
      };
      final stored = jsonEncode(draft['complications']);
      draft['customization']['style'] = 'fullscreen';
      expect(layout.unavailable(draft['customization']), {
        'top left',
        'top right',
        'bottom left',
        'bottom right',
        'bezel',
      });
      draft['customization']['style'] = 'circular';
      expect(layout.unavailable(draft['customization']), {
        'subdial top',
        'subdial bottom',
      });
      expect(jsonEncode(draft['complications']), stored);
      draft['customization']['style'] = 'future-native-style';
      expect(layout.unavailable(draft['customization']), isNull);
      expect(
        NativeFaceOptions.slotAvailable(
          'subdial top',
          draft['customization'],
          template: profile,
        ),
        isTrue,
      );
      expect(() => layout.order.clear(), throwsUnsupportedError);
    },
  );

  test(
    'Every shipped layout has valid domains; native monogram is separate',
    () async {
      final profiles = await NativeFaceGallery.load();
      expect(
        profiles.where((p) => p.complicationLayout != null),
        hasLength(40),
      );
      for (final profile in profiles) {
        final layout = profile.complicationLayout;
        if (layout == null) continue;
        final config = jsonDecode(profile.configurationJson) as Map;
        expect(layout.order.toSet(), profile.slotFamilies.keys.toSet());
        final hidden = layout.unavailable(config['customization'] ?? const {});
        expect(hidden, isNotNull, reason: profile.family);
        expect(layout.order, containsAll(hidden!));
        expect(() => hidden.clear(), throwsUnsupportedError);
      }
      final color = profiles.singleWhere((p) => p.family == 'type:color rich');
      expect(
        NativeFaceOptions.templateSlots(color),
        isNot(contains('monogram')),
      );
      expect(color.slotFamilies, contains('monogram'));
      expect(
        NativeFaceOptions.slotTitle(
          'bezel',
          template: profiles.singleWhere((p) => p.family == 'type:california'),
        ),
        'Top Middle',
      );
    },
  );

  test(
    'Malformed native slot identities and incomplete rules are rejected',
    () {
      Map<String, dynamic> fixture() => {
        'order': ['top', 'bottom'],
        'excluded': [],
        'constant': [],
        'labels': {
          'top': {'en': 'Top', 'de': 'Oben'},
        },
        'domains': {
          'style': ['a', 'b'],
        },
        'rules': {
          'style': {
            'a': ['bottom'],
            'b': [],
          },
        },
      };
      const families = {
            'top': [8],
            'bottom': [8],
          },
          options = {
            'style': ['a', 'b'],
          };
      final layout = NativeComplicationLayout.fromJson(
        fixture(),
        families,
        options,
      );
      expect(layout.title('top', 'de_DE'), 'Oben');
      expect(layout.title('top', 'fr'), 'Top');
      expect(layout.unavailable({'style': 'a'}), {'bottom'});
      expect(layout.unavailable({'style': 'b'}), isEmpty);
      expect(layout.unavailable({'style': false}), isNull);
      expect(layout.unavailable(null), isNull);
      for (final update in <String, Object?>{
        'order': ['top', 'top'],
        'excluded': ['future'],
        'constant': ['future'],
        'labels': {
          'top': {'en': 1},
        },
        'domains': {
          'style': ['a'],
        },
        'rules': {
          'style': {
            'a': ['bottom'],
          },
        },
      }.entries) {
        expect(
          () => NativeComplicationLayout.fromJson(
            {...fixture(), update.key: update.value},
            families,
            options,
          ),
          throwsFormatException,
        );
      }
    },
  );
}
