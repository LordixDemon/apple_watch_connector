import 'package:flutter/cupertino.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_configured_previews.dart';
import 'package:apple_watch_companion/services/native_component_preview_image.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_preview.dart';

void main() {
  testWidgets(
    'Component image and caption never resolve the static snapshot catalog',
    (tester) async {
      final templates = await tester.runAsync(NativeFaceGallery.load);
      final template = templates!.singleWhere(
        (t) => t.family == 'type:activity analog rich',
      );
      final config = <String, dynamic>{
        'customization': <String, dynamic>{
          'detail': 'detailed',
          'color': 'standard.red:0.41',
        },
      };
      final key = NativeConfiguredPreviews.keyFor(template.family, config)!;
      expect(NativeConfiguredPreviews.catalog.isResolved(key), isFalse);
      final before = NativeConfiguredPreviews.catalog.memoEntryCount;
      await tester.pumpWidget(
        CupertinoApp(
          home: Column(
            children: [
              NativeFacePreview(template: template, configuration: config),
              NativeFacePreviewCaption(
                template: template,
                configuration: config,
              ),
            ],
          ),
        ),
      );
      expect(find.byType(NativePreviewAssetResolver), findsNothing);
      expect(
        tester.widget<Image>(find.byType(Image)).image,
        isA<NativeComponentPreviewImage>(),
      );
      expect(find.text('Style preview'), findsOneWidget);
      expect(NativeConfiguredPreviews.catalog.memoEntryCount, before);
      expect(NativeConfiguredPreviews.catalog.isResolved(key), isFalse);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
