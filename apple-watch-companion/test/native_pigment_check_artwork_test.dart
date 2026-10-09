import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/cupertino.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_pigment_check_artwork.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_section.dart';

void main() {
  test(
    'Native glyph metadata rejects altered geometry, appearance and paths',
    () {
      final valid = {
        'version': 1,
        'appearance': 'dark',
        'primaryTransform': 'clampSRGB',
        'width': 112 / 3,
        'height': 112 / 3,
        'paletteImage': '${'a' * 64}.png',
        'templateImage': '${'b' * 64}.png',
      };
      expect(NativePigmentCheckArtwork.fromJson(valid).width, 112 / 3);
      for (final invalid in [
        {...valid, 'version': 1.0},
        {...valid, 'appearance': 'light'},
        {...valid, 'primaryTransform': 'unproven'},
        {...valid, 'width': double.nan},
        {...valid, 'height': 97},
        {...valid, 'height': 12},
        {...valid, 'paletteImage': '../check.png'},
        {...valid, 'extra': true},
      ]) {
        expect(
          () => NativePigmentCheckArtwork.fromJson(invalid),
          throwsFormatException,
        );
      }
    },
  );

  for (final primary in [
    null,
    const Color(0xFFFF0000),
    const Color(0xFF0000FF),
  ]) {
    testWidgets(
      'Native check retains geometry and fixed black role for $primary',
      (tester) async {
        final profiles = await tester.runAsync(NativeFaceGallery.load);
        final art = profiles!
            .singleWhere((p) => p.family == 'type:california')
            .pigmentSection('color')!
            .checkArtwork!;
        final boundary = GlobalKey();
        await tester.pumpWidget(
          CupertinoApp(
            home: Center(
              child: RepaintBoundary(
                key: boundary,
                child: NativeFacePigmentCheck(artwork: art, primary: primary),
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        await tester.runAsync(
          () => precacheImage(
            ExactAssetImage(
              primary == null ? art.templateImage : art.paletteImage,
            ),
            boundary.currentContext!,
          ),
        );
        await tester.pump();
        final widget = tester.widget<Image>(find.byType(Image));
        expect(
          (widget.image as ExactAssetImage).assetName,
          primary == null ? art.templateImage : art.paletteImage,
        );
        expect(
          widget.colorBlendMode,
          primary == null ? null : BlendMode.modulate,
        );
        expect(
          tester.getSize(find.byType(NativeFacePigmentCheck)),
          Size(art.width, art.height),
        );
        final image = (await tester.runAsync(
          () =>
              (boundary.currentContext!.findRenderObject()!
                      as RenderRepaintBoundary)
                  .toImage(pixelRatio: 3),
        ))!;
        final bytes = await tester.runAsync(
          () => image.toByteData(format: ui.ImageByteFormat.rawStraightRgba),
        );
        expect([image.width, image.height], [112, 112]);
        final pixels = bytes!.buffer.asUint8List(
          bytes.offsetInBytes,
          bytes.lengthInBytes,
        );
        var opaqueBlack = 0, opaquePrimary = 0, transparent = 0;
        for (var i = 0; i < pixels.length; i += 4) {
          if (pixels[i + 3] == 0) transparent++;
          if (pixels[i + 3] == 255 &&
              pixels[i] == 0 &&
              pixels[i + 1] == 0 &&
              pixels[i + 2] == 0) {
            opaqueBlack++;
          }
          if (primary != null &&
              pixels[i + 3] == 255 &&
              pixels[i] == (primary.r * 255).round() &&
              pixels[i + 1] == (primary.g * 255).round() &&
              pixels[i + 2] == (primary.b * 255).round()) {
            opaquePrimary++;
          }
        }
        expect(transparent, greaterThan(100));
        expect(opaqueBlack, greaterThan(100));
        if (primary != null) expect(opaquePrimary, greaterThan(100));
        if (primary != null) {
          final fixture = Directory('../tools/fixtures/native_pigment_check');
          final native = await tester.runAsync(() async {
            final capture = jsonDecode(
              await File('${fixture.path}/capture.json').readAsString(),
            );
            final row = (capture['rows'] as List).singleWhere(
              (r) =>
                  r['primary'] != null &&
                  r['primary'][0] == primary.r &&
                  r['primary'][1] == primary.g &&
                  r['primary'][2] == primary.b,
            );
            final codec = await ui.instantiateImageCodec(
              await File('${fixture.path}/${row['image']}').readAsBytes(),
            );
            final frame = await codec.getNextFrame();
            final result = await frame.image.toByteData(
              format: ui.ImageByteFormat.rawStraightRgba,
            );
            frame.image.dispose();
            codec.dispose();
            return result!;
          });
          final reference = native!.buffer.asUint8List(
            native.offsetInBytes,
            native.lengthInBytes,
          );
          expect(reference.length, pixels.length);
          var maximumRgbDelta = 0, maximumAlphaDelta = 0;
          for (var i = 0; i < pixels.length; i++) {
            final delta = (reference[i] - pixels[i]).abs();
            if (i % 4 == 3) {
              if (delta > maximumAlphaDelta) maximumAlphaDelta = delta;
            } else if (delta > maximumRgbDelta) {
              maximumRgbDelta = delta;
            }
          }
          expect(maximumRgbDelta, lessThanOrEqualTo(1));
          expect(maximumAlphaDelta, 0);
        }
        image.dispose();
        expect(tester.takeException(), isNull);
      },
    );
  }

  testWidgets('All production pigment sections share original check artwork', (
    tester,
  ) async {
    final profiles = await tester.runAsync(NativeFaceGallery.load);
    final data = jsonDecode(
      (await tester.runAsync(
        () => rootBundle.loadString('assets/native_faces/pigments_26_2.json'),
      ))!,
    );
    final sections = profiles!.expand((p) => p.pigmentSections).toList();
    expect(sections, isNotEmpty);
    final art = sections.first.checkArtwork;
    expect(art, isNotNull);
    expect(sections.every((s) => identical(s.checkArtwork, art)), isTrue);
    expect(art!.width, data['checkArtwork']['width']);
  });

  test(
    'Only the check clamps extended coordinates; the face color stays intact',
    () {
      final art = NativePigmentCheckArtwork.fromJson({
        'version': 1,
        'appearance': 'dark',
        'primaryTransform': 'clampSRGB',
        'width': 112 / 3,
        'height': 112 / 3,
        'paletteImage': '${'a' * 64}.png',
        'templateImage': '${'b' * 64}.png',
      });
      const original = Color.from(
        alpha: 1,
        red: 1.085,
        green: .249,
        blue: -.178,
        colorSpace: ui.ColorSpace.extendedSRGB,
      );
      final check = art.primaryColor(original)!;
      expect([check.r, check.g, check.b], [1, .249, 0]);
      expect([original.r, original.g, original.b], [1.085, .249, -.178]);
      expect(check.colorSpace, ui.ColorSpace.sRGB);
      expect(art.primaryColor(null), isNull);
    },
  );
}
