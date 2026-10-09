import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:archive/archive.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/controllers/native_face_controller.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_import_screen.dart';
import 'package:apple_watch_companion/services/native_watch_face_import.dart';
import 'package:apple_watch_companion/services/native_watch_face_archive.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'native_face_management_test.dart' as facts;

Uint8List packageWith(Map<String, Uint8List> files) {
  final archive = Archive();
  for (final entry in files.entries) {
    archive.addFile(ArchiveFile(entry.key, entry.value.length, entry.value));
  }
  return Uint8List.fromList(ZipEncoder().encode(archive)!);
}

Uint8List pngHeader(int width, int height) {
  final bytes = Uint8List(33)..setAll(0, [137, 80, 78, 71, 13, 10, 26, 10]);
  final header = ByteData.sublistView(bytes);
  header.setUint32(8, 13);
  header.setUint32(12, 0x49484452);
  header.setUint32(16, width);
  header.setUint32(20, height);
  return bytes;
}

Future<void> waitForImport(
  WidgetTester tester,
  NativeFaceController controller,
) async {
  await tester.runAsync(() async {
    for (var i = 0; i < 100 && !controller.busy; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 10));
    }
  });
  await tester.pump();
  expect(controller.busy, true);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final configuration = Uint8List.fromList(utf8.encode(facts.config));
  const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test(
    'Genuine Apple sharing preview and original archive survive immutable review',
    () async {
      final bytes = await File(
        '../apple-watch-bridge/core/src/test/resources/clockface-402-greenfield.watchface',
      ).readAsBytes();
      final original = Uint8List.fromList(bytes);
      final review = await NativeWatchFaceImport.inspect(bytes);
      expect(review.preview!.length, 63213);
      expect(review.family, 'bundle:com.apple.NTKLeghornFaceBundle');
      expect(review.configuration['customization']['style'], 'analog');
      expect(review.archive, orderedEquals(original));
      final copy = review.archive;
      copy[0] ^= 1;
      final preview = review.preview!;
      preview[0] ^= 1;
      bytes[0] ^= 1;
      final config = review.configuration;
      config['customization']['style'] = 'digital';
      expect(review.archive, orderedEquals(original));
      expect(review.preview!.first, 137);
      expect(review.configuration['customization']['style'], 'analog');
      expect(
        NativeWatchFaceArchive.configuration(review.archive),
        review.configuration,
      );
    },
  );

  test(
    'Explicit import preparation adds native metrics while retaining the original file and opaque resources',
    () async {
      final bytes = packageWith({
        'face.json': configuration,
        'Resources/photo.jpg': Uint8List.fromList([1, 2, 3]),
        'Resources/opaque.bin': Uint8List.fromList([4, 5, 6]),
        'snapshot.png': pngHeader(100, 120),
      });
      final review = await NativeWatchFaceImport.inspect(bytes);
      final prepared = await review.prepareForAddition();
      final config = NativeWatchFaceArchive.configuration(prepared);
      expect(config['metrics']['origin'], 12);
      expect(config['metrics']['editedState'], 1);
      expect(config['metrics']['dateCreated'], isA<double>());
      expect(config['metrics'].containsKey('numberOfCompanionEdits'), false);
      final archive = ZipDecoder().decodeBytes(prepared);
      final original = ZipDecoder().decodeBytes(bytes);
      expect(
        archive.files.map((f) => f.name),
        original.files.map((f) => f.name),
      );
      for (final file in original.files.where((f) => f.name != 'face.json')) {
        expect(archive.findFile(file.name)!.content, file.content);
      }
      expect({...config}..remove('metrics'), jsonDecode(facts.config));
      expect(review.archive, bytes);
      expect(review.configuration, jsonDecode(facts.config));
      final genuine = await File(
        '../apple-watch-bridge/core/src/test/resources/clockface-402-greenfield.watchface',
      ).readAsBytes();
      final shared = await NativeWatchFaceImport.inspect(genuine);
      final imported = NativeWatchFaceArchive.configuration(
        await shared.prepareForAddition(),
      );
      expect(shared.configuration.containsKey('metrics'), false);
      expect(imported['metrics']['origin'], 12);
      expect(imported['customization'], shared.configuration['customization']);
      expect(shared.archive, genuine);
    },
  );

  test(
    'Only bounded root PNG presentation is used; resources remain opaque',
    () async {
      final fallback = pngHeader(422, 514);
      for (final invalid in [
        Uint8List(32),
        pngHeader(0, 514),
        pngHeader(2049, 514),
        Uint8List(NativeWatchFaceImport.maxPreviewBytes + 1),
      ]) {
        final review = await NativeWatchFaceImport.inspect(
          packageWith({
            'face.json': configuration,
            'no_borders_snapshot.png': invalid,
            'snapshot.png': fallback,
            'Resources/no_borders_snapshot.png': pngHeader(1, 1),
          }),
        );
        expect(review.preview, orderedEquals(fallback));
      }
      final nestedOnly = await NativeWatchFaceImport.inspect(
        packageWith({
          'face.json': configuration,
          'Resources/snapshot.png': fallback,
        }),
      );
      expect(nestedOnly.preview, isNull);
      expect(nestedOnly.archive, isNotEmpty);
      await expectLater(
        NativeWatchFaceImport.inspect(
          packageWith({'face.json': configuration, '../escape': fallback}),
        ),
        throwsFormatException,
      );
      await expectLater(
        NativeWatchFaceImport.inspect(
          packageWith({
            'face.json': Uint8List.fromList(
              utf8.encode('{"bundle_id":"local"}'),
            ),
          }),
        ),
        throwsFormatException,
      );
    },
  );

  testWidgets(
    'Compact large-text review sends nothing until ADD and requires native proof',
    (tester) async {
      tester.view.physicalSize = const Size(320, 640);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({
        'type': 'connection',
        'data': facts.report(now, order: [facts.first]),
      });
      await tester.pump();
      final bytes = packageWith({
        'face.json': configuration,
        'Resources/photo.jpg': Uint8List.fromList([1, 2, 3]),
      });
      final review = await tester.runAsync(
        () => NativeWatchFaceImport.inspect(bytes),
      );
      var additions = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        expect(call.method, 'addNativeFace');
        final prepared = call.arguments['archive'] as Uint8List;
        final config = NativeWatchFaceArchive.configuration(prepared);
        expect(config['metrics']['origin'], 12);
        expect(config['metrics']['editedState'], 1);
        expect(config['metrics']['dateCreated'], isA<double>());
        expect(
          ZipDecoder()
              .decodeBytes(prepared)
              .findFile('Resources/photo.jpg')!
              .content,
          [1, 2, 3],
        );
        expect(review!.archive, bytes);
        additions++;
        return {
          'status': 'QUEUED',
          'faceId': facts.second,
          'requestId': facts.second,
        };
      });
      await tester.pumpWidget(
        CupertinoApp(
          builder: (_, child) => MediaQuery(
            data: const MediaQueryData(
              size: Size(320, 640),
              textScaler: TextScaler.linear(2),
            ),
            child: child!,
          ),
          home: NativeFaceImportScreen(
            package: review!,
            controller: controller,
            pair: facts.pair,
            epoch: facts.epoch,
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(additions, 0);
      expect(tester.takeException(), isNull);
      await tester.scrollUntilVisible(find.text('Add to Watch'), 180);
      await tester.tap(find.text('Add to Watch'));
      await waitForImport(tester, controller);
      expect(additions, 1);
      events.add({
        'type': 'connection',
        'data': facts.report(now + 1, selected: facts.second),
      });
      events.add({
        'type': 'operation',
        'data': {
          'requestId': facts.second,
          'epoch': facts.epoch,
          'status': 'APP_ACK_RECEIVED',
        },
      });
      await tester.pump();
      expect(find.text('Done'), findsNothing);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': facts.second,
          'epoch': facts.epoch,
          'status': 'NATIVE_FACE_APPLIED',
        },
      });
      await tester.pump();
      expect(find.text('Done'), findsOneWidget);
      expect(additions, 1);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      bridge.dispose();
      unawaited(events.close());
    },
  );

  testWidgets(
    'A changed epoch during archive preparation never redirects an import',
    (tester) async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': facts.report(now)});
      await tester.pump();
      final review = await tester.runAsync(
        () => NativeWatchFaceImport.inspect(
          packageWith({'face.json': configuration}),
        ),
      );
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {'status': 'QUEUED', 'requestId': facts.second};
      });
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceImportScreen(
            package: review!,
            controller: controller,
            pair: facts.pair,
            epoch: facts.epoch,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Add to Watch'));
      events.add({
        'type': 'connection',
        'data': {...facts.report(now + 1), 'faceCollectionEpoch': facts.first},
      });
      await tester.pump();
      for (
        var i = 0;
        i < 100 &&
            find.byType(CupertinoActivityIndicator).evaluate().isNotEmpty;
        i++
      ) {
        await tester.runAsync(
          () => Future<void>.delayed(const Duration(milliseconds: 10)),
        );
        await tester.pump();
      }
      expect(find.byType(CupertinoActivityIndicator), findsNothing);
      expect(calls, isEmpty);
      expect(controller.busy, false);
      expect(
        tester
            .widget<CupertinoButton>(
              find.ancestor(
                of: find.text('Add to Watch'),
                matching: find.byType(CupertinoButton),
              ),
            )
            .onPressed,
        isNull,
      );
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      bridge.dispose();
      unawaited(events.close());
    },
  );

  testWidgets(
    'Unknown ADD stays locked after refresh; reconnection never redirects the file',
    (tester) async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      final now = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': facts.report(now)});
      await tester.pump();
      final review = await tester.runAsync(
        () => NativeWatchFaceImport.inspect(
          packageWith({'face.json': configuration}),
        ),
      );
      var additions = 0;
      messenger.setMockMethodCallHandler(channel, (call) async {
        if (call.method == 'addNativeFace') additions++;
        return {
          'status': 'QUEUED',
          'faceId': facts.second,
          'requestId': facts.second,
        };
      });
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceImportScreen(
            package: review!,
            controller: controller,
            pair: facts.pair,
            epoch: facts.epoch,
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Add to Watch'));
      await waitForImport(tester, controller);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': facts.second,
          'epoch': facts.epoch,
          'status': 'UNKNOWN',
        },
      });
      await tester.pump();
      expect(additions, 1);
      CupertinoButton addButton() => tester.widget<CupertinoButton>(
        find.ancestor(
          of: find.text('Add to Watch'),
          matching: find.byType(CupertinoButton),
        ),
      );
      expect(addButton().onPressed, isNull);
      final refreshed = controller.refresh();
      await tester.pump();
      events.add({'type': 'connection', 'data': facts.report(now + 1)});
      await tester.pump();
      expect(await refreshed, isTrue);
      expect(addButton().onPressed, isNull);
      events.add({
        'type': 'connection',
        'data': {...facts.report(now + 2), 'faceCollectionEpoch': facts.first},
      });
      await tester.pump();
      expect(addButton().onPressed, isNull);
      expect(additions, 1);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      bridge.dispose();
      unawaited(events.close());
    },
  );

  testWidgets('A failed preflight read is recoverable without submitting ADD', (
    tester,
  ) async {
    final events = StreamController<dynamic>();
    final bridge = WatchBridgeService(events: events.stream);
    final controller = NativeFaceController(bridge);
    events.add({
      'type': 'connection',
      'data': facts.report(DateTime.now().millisecondsSinceEpoch - 250000),
    });
    await tester.pump();
    final review = await tester.runAsync(
      () => NativeWatchFaceImport.inspect(
        packageWith({'face.json': configuration}),
      ),
    );
    var reads = 0;
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'refreshFaceCollection');
      reads++;
      return {'status': 'QUEUED', 'requestId': facts.second};
    });
    await tester.pumpWidget(
      CupertinoApp(
        home: NativeFaceImportScreen(
          package: review!,
          controller: controller,
          pair: facts.pair,
          epoch: facts.epoch,
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Add to Watch'));
    await waitForImport(tester, controller);
    events.add({
      'type': 'operation',
      'data': {
        'requestId': facts.second,
        'epoch': facts.epoch,
        'status': 'UNKNOWN',
      },
    });
    await tester.pump();
    final button = tester.widget<CupertinoButton>(
      find.ancestor(
        of: find.text('Add to Watch'),
        matching: find.byType(CupertinoButton),
      ),
    );
    expect(button.onPressed, isNotNull);
    expect(reads, 1);
    events.add({
      'type': 'connection',
      'data': {
        ...facts.report(DateTime.now().millisecondsSinceEpoch),
        'faceCollectionEpoch': facts.first,
      },
    });
    await tester.pump();
    expect(
      tester
          .widget<CupertinoButton>(
            find.ancestor(
              of: find.text('Add to Watch'),
              matching: find.byType(CupertinoButton),
            ),
          )
          .onPressed,
      isNull,
    );
    await tester.pumpWidget(const SizedBox());
    controller.dispose();
    bridge.dispose();
    unawaited(events.close());
  });

  testWidgets(
    'Native preview decodes with a bounded cache; corrupt PNG uses fallback',
    (tester) async {
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final controller = NativeFaceController(bridge);
      events.add({
        'type': 'connection',
        'data': facts.report(DateTime.now().millisecondsSinceEpoch),
      });
      await tester.pump();
      final genuine = await tester.runAsync(
        () async => NativeWatchFaceImport.inspect(
          await File(
            '../apple-watch-bridge/core/src/test/resources/clockface-402-greenfield.watchface',
          ).readAsBytes(),
        ),
      );
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceImportScreen(
            package: genuine!,
            controller: controller,
            pair: facts.pair,
            epoch: facts.epoch,
          ),
        ),
      );
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 60)),
      );
      await tester.pumpAndSettle();
      expect(find.byType(RawImage), findsOneWidget);
      final image = tester.widget<RawImage>(find.byType(RawImage)).image!;
      expect(image.width, lessThanOrEqualTo(720));
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      final corrupt = await tester.runAsync(
        () => NativeWatchFaceImport.inspect(
          packageWith({
            'face.json': configuration,
            'snapshot.png': pngHeader(422, 514),
          }),
        ),
      );
      await tester.pumpWidget(
        CupertinoApp(
          home: NativeFaceImportScreen(
            package: corrupt!,
            controller: controller,
            pair: facts.pair,
            epoch: facts.epoch,
          ),
        ),
      );
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 60)),
      );
      await tester.pumpAndSettle();
      expect(
        find.text('This file has no usable shared preview.'),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      controller.dispose();
      bridge.dispose();
      unawaited(events.close());
    },
  );
}
