import 'dart:async';
import 'package:flutter/painting.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_indexed_preview_image.dart';
import 'native_preview_assets.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test(
    'Provider creates the requested thumbnail and caches by immutable size',
    () async {
      final asset = (await allNativePreviewAssets()).values.firstWhere(
        (v) => v.endsWith('.nfp'),
      );
      final provider = NativeIndexedPreviewImage(asset, cacheWidth: 54);
      expect(provider, NativeIndexedPreviewImage(asset, cacheWidth: 54));
      expect(
        provider,
        isNot(NativeIndexedPreviewImage(asset, cacheWidth: 108)),
      );
      final stream = provider.resolve(ImageConfiguration.empty);
      final ready = Completer<ImageInfo>();
      final listener = ImageStreamListener((info, synchronous) {
        if (!ready.isCompleted) ready.complete(info.clone());
      }, onError: ready.completeError);
      stream.addListener(listener);
      try {
        final image = await ready.future.timeout(const Duration(seconds: 15));
        try {
          expect(image.image.width, 54);
          expect(image.image.height, (54 * 514 / 422).floor());
          expect(image.scale, 1);
        } finally {
          image.dispose();
        }
      } finally {
        stream.removeListener(listener);
        await provider.evict();
      }
    },
  );

  test(
    'Invalid keys fail asynchronously without poisoning the image cache',
    () async {
      for (final provider in [
        const NativeIndexedPreviewImage('../outside.nfp', cacheWidth: 54),
        NativeIndexedPreviewImage(
          'assets/native_faces/configured_previews/${'a' * 64}.nfp',
          cacheWidth: 0,
        ),
      ]) {
        final ready = Completer<void>();
        final stream = provider.resolve(ImageConfiguration.empty);
        final listener = ImageStreamListener((_, _) {
          ready.completeError(StateError('Invalid preview produced an image'));
        }, onError: (error, stack) => ready.completeError(error, stack));
        stream.addListener(listener);
        try {
          await expectLater(ready.future, throwsFormatException);
          await Future<void>.delayed(Duration.zero);
          expect(
            PaintingBinding.instance.imageCache.containsKey(provider),
            isFalse,
          );
        } finally {
          stream.removeListener(listener);
        }
      }
    },
  );
}
