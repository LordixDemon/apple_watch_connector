import 'dart:async';
import 'package:flutter/painting.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_component_previews.dart';
import 'package:apple_watch_companion/services/native_component_preview_image.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Component worker decodes a scaled native image and caches by palette and size',
    () async {
      final entries = NativeComponentPreviews.decodeManifest(
        await rootBundle.loadString(
          'assets/native_faces/component_previews/catalog.json',
        ),
      );
      NativeComponentPreviewRequest request(int percent) =>
          NativeComponentPreviews.resolve('type:activity analog rich', {
            'customization': <String, dynamic>{
              'detail': 'detailed',
              'color': 'standard.plum:${(percent / 100).toStringAsFixed(2)}',
            },
          }, entries: entries)!;
      final provider = NativeComponentPreviewImage(
        request(37),
        cacheWidth: 108,
      );
      expect(
        provider,
        NativeComponentPreviewImage(request(37), cacheWidth: 108),
      );
      expect(
        provider,
        isNot(NativeComponentPreviewImage(request(64), cacheWidth: 108)),
      );
      expect(
        provider,
        isNot(NativeComponentPreviewImage(request(37), cacheWidth: 216)),
      );
      final ready = Completer<ImageInfo>();
      final stream = provider.resolve(ImageConfiguration.empty);
      final listener = ImageStreamListener((info, _) {
        if (!ready.isCompleted) ready.complete(info.clone());
      }, onError: ready.completeError);
      stream.addListener(listener);
      try {
        final image = await ready.future.timeout(const Duration(seconds: 15));
        try {
          expect(image.image.width, 108);
          expect(image.image.height, (108 * 514 / 422).floor());
        } finally {
          image.dispose();
        }
      } finally {
        stream.removeListener(listener);
        await provider.evict();
      }
    },
  );
}
