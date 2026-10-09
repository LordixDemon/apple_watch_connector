import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:image/image.dart' as image;
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_editor_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_faces_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_templates_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/face_gallery_tab.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_creation_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_option_section.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_monogram_section.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_value_picker.dart';
import 'package:apple_watch_companion/services/native_watch_face_archive.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_gallery_collections.dart';
import 'package:apple_watch_companion/services/native_face_photos.dart';
import 'package:apple_watch_companion/services/watch_face_files.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_photos_screen.dart';
import 'package:apple_watch_companion/services/native_intent_parameters.dart';
import 'package:apple_watch_companion/widgets/ios_list_tile.dart';
import 'native_intent_variants_test.dart' as presets;
import 'native_face_management_test.dart' as facts;

class _Catalog extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) async => [];
}

class _PhotoFiles extends WatchFaceFiles {
  final Uint8List bytes;
  _PhotoFiles(this.bytes);
  @override
  Future<Uint8List?> openPhoto() async => bytes;
}

class _HoldingStorage extends SettingsStorage {
  final gate = Completer<void>();
  _HoldingStorage(super.preferences);
  @override
  Future<void> saveFaceEditIntents(String value) async {
    await gate.future;
    await super.saveFaceEditIntents(value);
  }
}

void main() {
  for (final creation in [false, true]) {
    testWidgets(
      'Monogram ${creation ? "gallery draft" : "installed autosave"} uses native configuration and refuses stale delivery',
      (tester) async {
        final profiles = await tester.runAsync(NativeFaceGallery.load);
        final profile = profiles!.singleWhere(
          (p) => p.family == 'type:color rich',
        );
        SharedPreferences.setMockInitialValues({});
        final events = StreamController<dynamic>();
        final bridge = WatchBridgeService(events: events.stream);
        final provider = WatchProvider(
          storage: SettingsStorage(await SharedPreferences.getInstance()),
          bridge: bridge,
          apiService: _Catalog(),
          ownsApiService: true,
        );
        const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        final calls = <MethodCall>[];
        messenger.setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return {
            'status': 'QUEUED',
            'requestId': facts.second,
            'faceId': facts.second,
          };
        });
        final config =
            jsonDecode(profile.configurationJson) as Map<String, dynamic>;
        config['complications'] = {
          'top left': {
            'future': {
              'opaque': [1, true],
            },
          },
        };
        final report = facts.report(
          DateTime.now().millisecondsSinceEpoch,
          json: jsonEncode(config),
        );
        for (final row in report['faceCollectionFaces'] as List) {
          row['bundle'] = '';
        }
        events.add({'type': 'connection', 'data': report});
        await tester.pump();
        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: CupertinoApp(
              home: creation
                  ? NativeFaceCreationScreen(
                      template: profile,
                      pair: facts.pair,
                      epoch: facts.epoch,
                    )
                  : NativeFaceEditorScreen(
                      face: provider.nativeFaces.collection.faces.first,
                      pair: facts.pair,
                      epoch: facts.epoch,
                    ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final section = find.byType(NativeFaceMonogramSection);
        await tester.scrollUntilVisible(
          section,
          180,
          scrollable: find
              .byWidgetPredicate(
                (w) => w is Scrollable && w.axisDirection == AxisDirection.down,
              )
              .first,
        );
        await tester.pumpAndSettle();
        final toggle = find.descendant(
          of: section,
          matching: find.byType(CupertinoSwitch),
        );
        expect(toggle, findsOneWidget);
        expect(tester.widget<CupertinoSwitch>(toggle).value, false);
        await tester.tap(toggle);
        await tester.pumpAndSettle();
        expect(tester.widget<CupertinoSwitch>(toggle).value, true);
        if (creation) {
          expect(calls, isEmpty);
          await tester.tap(find.text('Add to Watch'));
          await tester.pump();
          expect(calls.single.method, 'addNativeFace');
          final sent = NativeWatchFaceArchive.configuration(
            calls.single.arguments['archive'] as Uint8List,
          );
          expect(sent['complications']['monogram'], {'app': 'monogram'});
          expect(
            jsonDecode(profile.configurationJson)['complications'],
            isNot(contains('monogram')),
          );
        } else {
          expect(calls.single.method, 'updateNativeFace');
          final sent = jsonDecode(
            utf8.decode(calls.single.arguments['configuration'] as Uint8List),
          );
          expect(sent['complications']['monogram'], {'app': 'monogram'});
          expect(
            sent['complications']['top left'],
            config['complications']['top left'],
          );
          expect(sent.containsKey('customMonogram'), false);
          expect(sent['complications']['monogram'].containsKey('text'), false);
        }
        events.add({
          'type': 'connection',
          'data': {...report, 'faceCollectionEpoch': facts.second},
        });
        await tester.pumpAndSettle();
        if (creation) {
          // Gallery changes remain local when disconnected. A submitted add
          // cannot be replayed against the new connection, even after editing.
          await tester.tap(toggle);
          await tester.pumpAndSettle();
          final add = tester.widget<CupertinoButton>(
            find.ancestor(
              of: find.text('Add to Watch'),
              matching: find.byType(CupertinoButton),
            ),
          );
          expect(add.onPressed, isNull);
        } else {
          expect(tester.widget<CupertinoSwitch>(toggle).onChanged, isNull);
        }
        expect(calls, hasLength(1));
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        provider.dispose();
        bridge.dispose();
        unawaited(events.close());
        messenger.setMockMethodCallHandler(channel, null);
        await tester.pump();
      },
    );
  }
  for (final creation in [false, true]) {
    testWidgets(
      'California ${creation ? "gallery" : "installed"} style updates slot availability and preserves opaque values',
      (tester) async {
        final profiles = await tester.runAsync(NativeFaceGallery.load);
        final profile = profiles!.singleWhere(
          (p) => p.family == 'type:california',
        );
        SharedPreferences.setMockInitialValues({});
        final events = StreamController<dynamic>();
        final bridge = WatchBridgeService(events: events.stream);
        final provider = WatchProvider(
          storage: SettingsStorage(await SharedPreferences.getInstance()),
          bridge: bridge,
          apiService: _Catalog(),
          ownsApiService: true,
        );
        const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        final calls = <MethodCall>[];
        messenger.setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return null;
        });
        final config =
            jsonDecode(profile.configurationJson) as Map<String, dynamic>;
        config['customization']['style'] = 'fullscreen';
        config['complications'] = {
          'subdial top': {
            'type': 99,
            'future': {'opaque': true},
          },
        };
        final json = jsonEncode(config);
        final report = facts.report(
          DateTime.now().millisecondsSinceEpoch,
          json: json,
        );
        for (final row in report['faceCollectionFaces'] as List) {
          row['bundle'] = '';
        }
        events.add({'type': 'connection', 'data': report});
        await tester.pump();
        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: CupertinoApp(
              home: creation
                  ? NativeFaceCreationScreen(
                      template: profile,
                      pair: facts.pair,
                      epoch: facts.epoch,
                    )
                  : NativeFaceEditorScreen(
                      face: provider.nativeFaces.collection.faces.first,
                      pair: facts.pair,
                      epoch: facts.epoch,
                    ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        Finder row(String title) => find.widgetWithText(IosListTile, title);
        Future<IosListTile> reveal(String title) async {
          await tester.scrollUntilVisible(
            row(title),
            180,
            scrollable: find
                .byWidgetPredicate(
                  (w) =>
                      w is Scrollable && w.axisDirection == AxisDirection.down,
                )
                .first,
          );
          await tester.pumpAndSettle();
          return tester.widget<IosListTile>(row(title));
        }

        // The gallery starts with the native fullscreen baseline, just as the
        // installed fixture does. Stored subdial data stays readable.
        expect((await reveal('Top Left')).enabled, false);
        expect((await reveal('Sub-dial Top')).enabled, true);
        expect((await reveal('Top Middle')).enabled, false);
        final style = find.byWidgetPredicate(
          (w) => w is NativeFaceOptionSection && w.section.field == 'style',
        );
        await tester.scrollUntilVisible(
          style,
          -180,
          scrollable: find
              .byWidgetPredicate(
                (w) => w is Scrollable && w.axisDirection == AxisDirection.down,
              )
              .first,
        );
        await tester.pumpAndSettle();
        final circular = find.descendant(
          of: style,
          matching: find.text('CIRCULAR'),
        );
        await tester.ensureVisible(circular);
        await tester.tap(circular);
        await tester.pump();
        expect((await reveal('Top Left')).enabled, true);
        expect((await reveal('Sub-dial Top')).enabled, false);
        expect((await reveal('Top Middle')).enabled, true);
        if (!creation) {
          // Provider-owned autosave retains the opaque complication when only
          // the style changes; unavailable does not mean removed from JSON.
          final edit = provider.nativeFaceAutosave.edit(
            facts.pair,
            facts.first,
          )!;
          expect(
            jsonDecode(edit.desired)['complications'],
            config['complications'],
          );
          expect(find.text('Create Copy'), findsNothing);
          expect(find.text('Move Earlier'), findsNothing);
          await reveal('Advanced');
          await tester.tap(find.text('Advanced'));
          await tester.pump();
          expect(await reveal('Create Copy'), isA<IosListTile>());
        }
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        provider.dispose();
        bridge.dispose();
        unawaited(events.close());
        messenger.setMockMethodCallHandler(channel, null);
        await tester.pump();
      },
    );
  }
  for (final creation in [false, true]) {
    for (final reconnect in [false, true]) {
      testWidgets(
        'AppIntent ${creation ? "creation" : "editor"} ${reconnect
            ? "rejects stale settings"
            : creation
            ? "retains a local preset draft"
            : "automatically saves the chosen preset"}',
        (tester) async {
          final profiles = await tester.runAsync(NativeFaceGallery.load);
          final profile = profiles!.singleWhere(
            (p) => p.family == 'type:whistler-digital',
          );
          SharedPreferences.setMockInitialValues({});
          final events = StreamController<dynamic>();
          final bridge = WatchBridgeService(events: events.stream);
          final provider = WatchProvider(
            storage: SettingsStorage(await SharedPreferences.getInstance()),
            bridge: bridge,
            apiService: _Catalog(),
            ownsApiService: true,
          );
          const channel = MethodChannel(
            'dev.applewatchandroid.companion/bridge',
          );
          final calls = <String>[];
          final messenger =
              TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
          messenger.setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            return null;
          });
          final current = {
            ...presets.value('week-walk'),
            'extension': 'fixture.provider',
          };
          final config =
              (jsonDecode(profile.configurationJson) as Map<String, dynamic>)
                ..['complications'] = {'center': current};
          final json = jsonEncode(config);
          final base = facts.report(
            DateTime.now().millisecondsSinceEpoch,
            json: json,
          );
          final report = {
            ...base,
            'faceCollectionFaces': [
              for (final id in [facts.first, facts.second])
                {
                  'id': id,
                  'bundle': '',
                  'configurationBytes': utf8.encode(json).length,
                  'configuration': Uint8List.fromList(utf8.encode(json)),
                  'archiveAvailable': true,
                },
            ],
            'faceComplicationCatalogComplete': true,
            'faceComplicationCatalog': Uint8List.fromList(
              utf8.encode(
                jsonEncode({
                  'WidgetComplications:fixture.provider': {
                    for (final row in presets.grid())
                      row.title: {
                        'name': row.title,
                        'families': [11],
                        'descriptor': row.value['descriptor'],
                      },
                  },
                }),
              ),
            ),
          };
          events.add({'type': 'connection', 'data': report});
          await tester.pump();
          await tester.pumpWidget(
            ChangeNotifierProvider.value(
              value: provider,
              child: CupertinoApp(
                home: creation
                    ? NativeFaceCreationScreen(
                        template: profile,
                        pair: facts.pair,
                        epoch: facts.epoch,
                      )
                    : NativeFaceEditorScreen(
                        face: provider.nativeFaces.collection.faces.first,
                        pair: facts.pair,
                        epoch: facts.epoch,
                      ),
              ),
            ),
          );
          await tester.pumpAndSettle();
          if (creation) {
            await tester.scrollUntilVisible(
              find.text('Middle'),
              160,
              scrollable: find.byType(Scrollable).first,
            );
            await tester.pumpAndSettle();
            await tester.tap(find.text('Middle'));
            await tester.pumpAndSettle();
            await tester.tap(find.text('Weekly Walking Distance').first);
            await tester.pumpAndSettle();
          }
          await tester.scrollUntilVisible(
            find.text('Settings for Middle'),
            250,
            scrollable: find.byWidgetPredicate(
              (widget) =>
                  widget is Scrollable &&
                  widget.axisDirection == AxisDirection.down,
            ),
          );
          await tester.pumpAndSettle();
          await tester.tap(find.text('Settings for Middle'));
          await tester.pumpAndSettle();
          await tester.tap(find.text('Period'));
          await tester.pumpAndSettle();
          await tester.tap(find.text('Monthly'));
          await tester.pumpAndSettle();
          if (reconnect) {
            events.add({
              'type': 'connection',
              'data': {...report, 'faceCollectionEpoch': facts.second},
            });
            await tester.pump();
          }
          await tester.tap(find.text('SAVE'));
          await tester.pumpAndSettle();
          expect(
            find.text(
              reconnect
                  ? 'Weekly Walking Distance'
                  : 'Monthly Walking Distance',
            ),
            findsWidgets,
          );
          expect(calls, creation || reconnect ? isEmpty : ['updateNativeFace']);
          expect(
            provider
                .nativeFaces
                .collection
                .faces
                .first
                .configuration!['complications']['center'],
            current,
          );
          expect(tester.takeException(), isNull);
          await tester.pumpWidget(const SizedBox());
          provider.dispose();
          bridge.dispose();
          unawaited(events.close());
          messenger.setMockMethodCallHandler(channel, null);
          await tester.pump();
        },
      );
    }
  }
  for (final reconnect in [false, true]) {
    testWidgets(
      'Native interval settings ${reconnect ? "refuse a reconnect" : "save automatically"}',
      (tester) async {
        await tester.runAsync(NativeFaceGallery.load);
        SharedPreferences.setMockInitialValues({});
        final events = StreamController<dynamic>();
        final bridge = WatchBridgeService(events: events.stream);
        final provider = WatchProvider(
          storage: SettingsStorage(await SharedPreferences.getInstance()),
          bridge: bridge,
          apiService: _Catalog(),
          ownsApiService: true,
        );
        const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
        final calls = <String>[];
        final messenger =
            TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
        messenger.setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          return null;
        });
        final intent = base64Encode(
          File(
            'test/fixtures/native-parameters-46-scalar.bplist',
          ).readAsBytesSync(),
        );
        final config = jsonEncode({
          'bundle id': 'com.apple.NativeFace',
          'customization': {'color': 'opaque-token'},
          'complications': {
            'bottom': {
              'type': 56,
              'descriptor': {
                'containerBundleIdentifier': 'research.app',
                'extensionBundleIdentifier': 'research.widget',
                'kind': 'ResearchIntervalWidget',
                'intent': intent,
              },
            },
          },
        });
        final report = facts.report(
          DateTime.now().millisecondsSinceEpoch,
          json: config,
        );
        events.add({'type': 'connection', 'data': report});
        await tester.pump();
        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: CupertinoApp(
              home: NativeFaceEditorScreen(
                face: provider.nativeFaces.collection.faces.first,
                pair: facts.pair,
                epoch: facts.epoch,
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        final settings = find.text('Settings for Bottom');
        await tester.ensureVisible(settings);
        await tester.tap(settings);
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(CupertinoTextField), '70');
        if (reconnect) {
          events.add({
            'type': 'connection',
            'data': {...report, 'faceCollectionEpoch': facts.second},
          });
          await tester.pump();
        }
        await tester.tap(find.text('SAVE'));
        await tester.pumpAndSettle();
        expect(
          find.text(
            reconnect ? 'Research interval: 900 s' : 'Research interval: 70 s',
          ),
          findsWidgets,
        );
        expect(calls, reconnect ? isEmpty : ['updateNativeFace']);
        final stored = provider
            .nativeFaces
            .collection
            .faces
            .first
            .configuration!['complications']['bottom']['descriptor']['intent'];
        expect(
          NativeIntentParameters.parse(stored)!.durations.single.seconds,
          900,
        );
        expect(tester.takeException(), isNull);
        await tester.pumpWidget(const SizedBox());
        provider.dispose();
        bridge.dispose();
        unawaited(events.close());
        messenger.setMockMethodCallHandler(channel, null);
        await tester.pump();
      },
    );
  }
  testWidgets(
    'Native face screens ignore unrelated provider updates and still react to connection changes',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      await tester.runAsync(NativeGalleryCollections.load);
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (_) async => null);
      final report = facts.report(DateTime.now().millisecondsSinceEpoch);
      events.add({'type': 'connection', 'data': report});
      await tester.pump();
      final screens = <Widget Function()>[
        () => const NativeFacesScreen(),
        () => NativeFaceEditorScreen(
          face: provider.nativeFaces.collection.faces.first,
          pair: facts.pair,
          epoch: facts.epoch,
        ),
        () => const NativeFaceTemplatesScreen(),
        () => NativeFaceCreationScreen(
          template: profiles!.firstWhere((v) => !v.requiresPhotos),
          pair: facts.pair,
          epoch: facts.epoch,
        ),
        () => NativeFacePhotosScreen(
          template: profiles!.singleWhere((v) => v.requiresPhotos),
          pair: facts.pair,
          epoch: facts.epoch,
        ),
      ];
      try {
        for (final screen in screens) {
          events.add({'type': 'connection', 'data': report});
          await tester.pump();
          await tester.pumpWidget(
            ChangeNotifierProvider.value(
              value: provider,
              child: CupertinoApp(home: screen()),
            ),
          );
          await tester.runAsync(() async {
            await Future<void>.delayed(const Duration(milliseconds: 50));
          });
          await tester.pumpAndSettle();
          final before = tester.widget<CupertinoPageScaffold>(
            find.byType(CupertinoPageScaffold).first,
          );
          for (var i = 0; i < 8; i++) {
            await provider.updateSettings(provider.settings);
            await tester.pump();
            expect(
              identical(
                before,
                tester.widget(find.byType(CupertinoPageScaffold).first),
              ),
              isTrue,
            );
          }
          events.add({
            'type': 'connection',
            'data': {...report, 'faceCollectionEpoch': facts.second},
          });
          await tester.pump();
          await tester.pump();
          expect(provider.nativeFaces.collection.epoch, facts.second);
          expect(
            identical(
              before,
              tester.widget(find.byType(CupertinoPageScaffold).first),
            ),
            // Gallery connection receipts rebuild its banner and Photos rows,
            // while the immutable appearance catalog keeps its scaffold.
            screen() is NativeFaceTemplatesScreen ? isTrue : isFalse,
          );
          await tester.pumpWidget(const SizedBox());
          expect(tester.takeException(), isNull);
        }
      } finally {
        await tester.pumpWidget(const SizedBox());
        provider.dispose();
        bridge.dispose();
        unawaited(events.close());
        messenger.setMockMethodCallHandler(channel, null);
      }
    },
  );
  testWidgets(
    'Native creation edits locally, submits one configured package and never repeats an unknown add',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final profile = profiles!.singleWhere((p) => p.title('en') == 'Flux');
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      final calls = <MethodCall>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {
          'status': 'QUEUED',
          'requestId': facts.second,
          'faceId': facts.second,
        };
      });
      events.add({
        'type': 'connection',
        'data': facts.report(DateTime.now().millisecondsSinceEpoch),
      });
      await tester.pump();
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: CupertinoApp(
            home: NativeFaceCreationScreen(
              template: profile,
              pair: facts.pair,
              epoch: facts.epoch,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      final fieldTitle = profile.fieldTitle('style', 'en')!;
      final defaults = jsonDecode(profile.configurationJson) as Map;
      final next = profile.options['style']!.firstWhere(
        (token) => token != defaults['customization']['style'],
      );
      await tester.ensureVisible(find.text(fieldTitle));
      await tester.tap(find.text(fieldTitle));
      await tester.pumpAndSettle();
      expect(find.byType(NativeFaceOptionSection), findsWidgets);
      expect(find.byType(NativeFaceValuePicker), findsNothing);
      await tester.tap(find.text(profile.valueTitle('style', next, 'en')!));
      await tester.pumpAndSettle();
      expect(calls, isEmpty);
      expect(provider.nativeFaces.collection.ordered, [
        facts.first,
        facts.second,
      ]);
      await tester.tap(find.text('Add to Watch'));
      await tester.pump();
      expect(calls, hasLength(1));
      expect(calls.single.method, 'addNativeFace');
      final config = NativeWatchFaceArchive.configuration(
        (calls.single.arguments as Map)['archive'] as Uint8List,
      );
      expect(config['customization']['style'], next);
      expect(jsonDecode(profile.configurationJson), defaults);
      expect(provider.nativeFaces.busy, true);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': facts.second,
          'epoch': facts.epoch,
          'status': 'UNKNOWN',
        },
      });
      await tester.pumpAndSettle();
      expect(provider.nativeFaces.busy, false);
      final button = tester.widget<CupertinoButton>(
        find.ancestor(
          of: find.text('Add to Watch'),
          matching: find.byType(CupertinoButton),
        ),
      );
      expect(button.onPressed, isNull);
      expect(calls, hasLength(1));
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      messenger.setMockMethodCallHandler(channel, null);
      await tester.pump();
    },
  );
  testWidgets(
    'Failed preflight read sends no ADD and leaves the native draft recoverable',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final profile = profiles!.singleWhere((p) => p.title('en') == 'Flux');
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      final calls = <String>[];
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return {'status': 'QUEUED', 'requestId': facts.second};
      });
      events.add({
        'type': 'connection',
        'data': facts.report(DateTime.now().millisecondsSinceEpoch - 300000),
      });
      await tester.pump();
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: CupertinoApp(
            home: NativeFaceCreationScreen(
              template: profile,
              pair: facts.pair,
              epoch: facts.epoch,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Add to Watch'));
      await tester.pump();
      expect(calls, ['refreshFaceCollection']);
      events.add({
        'type': 'operation',
        'data': {
          'requestId': facts.second,
          'epoch': facts.epoch,
          'status': 'UNKNOWN',
        },
      });
      await tester.pumpAndSettle();
      final button = tester.widget<CupertinoButton>(
        find.ancestor(
          of: find.text('Add to Watch'),
          matching: find.byType(CupertinoButton),
        ),
      );
      expect(button.onPressed, isNotNull);
      expect(calls, ['refreshFaceCollection']);
      expect(
        find.text(
          'The result is not confirmed. Refresh before making another change.',
        ),
        findsNothing,
      );
      events.add({
        'type': 'connection',
        'data': facts.report(DateTime.now().millisecondsSinceEpoch),
      });
      await tester.pumpAndSettle();
      expect(find.text('Collection received from the watch.'), findsOneWidget);
      expect(calls, ['refreshFaceCollection']);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      messenger.setMockMethodCallHandler(channel, null);
      await tester.pump();
    },
  );

  testWidgets(
    'Legacy picker saves a clear, keeps further edits after rejection and rejects stale modal choices',
    (tester) async {
      await tester.runAsync(NativeFaceGallery.load);
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      final report = facts.report(DateTime.now().millisecondsSinceEpoch);
      final rows = report['faceCollectionFaces'] as List;
      for (var i = 0; i < rows.length; i++) {
        final json = jsonEncode({
          'face type': 'legacy-observed',
          'complications': {
            'top': {'app': i == 0 ? 'date' : 'battery'},
          },
        });
        rows[i]['bundle'] = '';
        rows[i]['configuration'] = Uint8List.fromList(utf8.encode(json));
        rows[i]['configurationBytes'] = utf8.encode(json).length;
        rows[i]['archiveAvailable'] = false;
      }
      events.add({'type': 'connection', 'data': report});
      await tester.pump();
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            return null;
          });
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: CupertinoApp(
            home: NativeFaceEditorScreen(
              face: provider.nativeFaces.collection.faces.first,
              pair: facts.pair,
              epoch: facts.epoch,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('Top'));
      await tester.tap(find.text('Top'));
      await tester.pumpAndSettle();
      expect(
        find.widgetWithText(CupertinoActionSheetAction, 'date'),
        findsOneWidget,
      );
      expect(
        find.widgetWithText(CupertinoActionSheetAction, 'battery'),
        findsOneWidget,
      );
      await tester.tap(find.widgetWithText(CupertinoActionSheetAction, 'None'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Top'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(CupertinoActionSheetAction, 'date'));
      await tester.pumpAndSettle();
      expect(find.text('date'), findsOneWidget);
      await tester.tap(find.text('Top'));
      await tester.pumpAndSettle();
      // A reconnect while the picker is open invalidates its selection.
      events.add({
        'type': 'connection',
        'data': {...report, 'faceCollectionEpoch': facts.second},
      });
      await tester.pump();
      await tester.tap(
        find.widgetWithText(CupertinoActionSheetAction, 'battery'),
      );
      await tester.pumpAndSettle();
      expect(find.text('date'), findsOneWidget);
      expect(find.text('battery'), findsNothing);
      expect(calls, ['updateNativeFace']);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
      await tester.pump();
    },
  );

  testWidgets(
    'Installed Photos loads the real album on a narrow screen and reorders without creating a face',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(320, 568);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.reset);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final profile = profiles!.singleWhere((v) => v.requiresPhotos);
      final archive = (await tester.runAsync(
        () async => NativeFacePhotos.package(profile, [
          NativeFacePhoto(
            image.encodeJpg(image.Image(width: 320, height: 400)),
            320,
            400,
          ),
          NativeFacePhoto(
            image.encodeJpg(
              image.Image(width: 320, height: 400)
                ..clear(image.ColorRgb8(120, 50, 20)),
            ),
            320,
            400,
          ),
        ]),
      ))!;
      final hash = sha256.convert(archive).toString();
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      final report = facts.report(
        DateTime.now().millisecondsSinceEpoch,
        json: jsonEncode(NativeWatchFaceArchive.configuration(archive)),
      );
      for (final row in report['faceCollectionFaces'] as List) {
        row['bundle'] = 'com.apple.NTKParmesanFaceBundle';
      }
      events.add({'type': 'connection', 'data': report});
      await tester.pump();
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      final calls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            if (call.method != 'exportNativeFace') return null;
            return {
              'status': 'EXPORTED',
              'archive': Uint8List.fromList(archive),
              'offset': 0,
              'total': archive.length,
              'sha256': hash,
            };
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null),
      );
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: CupertinoApp(
            home: NativeFacePhotosScreen(
              template: profile,
              face: provider.nativeFaces.collection.faces.first,
              pair: facts.pair,
              epoch: facts.epoch,
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.runAsync(() async {
        await Future<void>.delayed(const Duration(milliseconds: 100));
      });
      await tester.pumpAndSettle();
      expect(find.byType(Image), findsNWidgets(2));
      expect(find.text('2 photos selected'), findsOneWidget);
      final orderBefore = tester
          .widgetList<Image>(find.byType(Image))
          .map(
            (v) =>
                ((v.image as ResizeImage).imageProvider as MemoryImage).bytes,
          )
          .toList();
      await tester.tap(find.byIcon(CupertinoIcons.chevron_right).first);
      await tester.pumpAndSettle();
      final orderAfter = tester
          .widgetList<Image>(find.byType(Image))
          .map(
            (v) =>
                ((v.image as ResizeImage).imageProvider as MemoryImage).bytes,
          )
          .toList();
      expect(orderAfter.first, orderBefore.last);
      expect(orderAfter.last, orderBefore.first);
      expect(calls.where((m) => m != 'exportNativeFace'), isEmpty);
      expect(tester.takeException(), isNull);
      events.add({
        'type': 'connection',
        'data': {...report, 'faceCollectionEpoch': facts.second},
      });
      await tester.pumpAndSettle();
      final choose = tester.widget<CupertinoButton>(
        find.widgetWithText(CupertinoButton, 'Choose Photo'),
      );
      expect(choose.onPressed, isNull);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      await tester.pump();
    },
  );
  for (final installed in [false, true]) {
    for (final stale in [false, true]) {
      testWidgets(
        'Photos ${installed ? "resource edit" : "creation"} ${stale ? "keeps a failed preflight recoverable" : "never repeats an uncertain mutation"}',
        (tester) async {
          final profiles = await tester.runAsync(NativeFaceGallery.load);
          final profile = profiles!.singleWhere((p) => p.requiresPhotos);
          final jpeg = image.encodeJpg(
            image.Image(width: 320, height: 400)
              ..clear(image.ColorRgb8(120, 50, 20)),
          );
          final archive = (await tester.runAsync(
            () => NativeFacePhotos.package(profile, [
              NativeFacePhoto(jpeg, 320, 400),
            ]),
          ))!;
          SharedPreferences.setMockInitialValues({});
          final events = StreamController<dynamic>();
          final bridge = WatchBridgeService(events: events.stream);
          final provider = WatchProvider(
            storage: SettingsStorage(await SharedPreferences.getInstance()),
            bridge: bridge,
            apiService: _Catalog(),
            ownsApiService: true,
          );
          const channel = MethodChannel(
            'dev.applewatchandroid.companion/bridge',
          );
          final messenger =
              TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
          final calls = <String>[];
          messenger.setMockMethodCallHandler(channel, (call) async {
            calls.add(call.method);
            if (call.method == 'exportNativeFace') {
              return {
                'status': 'EXPORTED',
                'archive': archive,
                'offset': 0,
                'total': archive.length,
                'sha256': sha256.convert(archive).toString(),
              };
            }
            if (call.method == 'beginNativeFaceImport') {
              expect((call.arguments as Map)['faceId'], facts.first);
              expect(
                (call.arguments as Map)['baselineHash'],
                sha256.convert(archive).toString(),
              );
              return {'status': 'UPLOADING', 'uploadId': facts.second};
            }
            if (call.method == 'appendNativeFaceImport') {
              final args = call.arguments as Map;
              return {
                'status': 'UPLOADING',
                'offset':
                    (args['offset'] as int) +
                    (args['chunk'] as Uint8List).length,
              };
            }
            return {
              'status': 'QUEUED',
              'requestId': facts.second,
              'faceId': facts.second,
            };
          });
          final report = facts.report(
            DateTime.now().millisecondsSinceEpoch - (stale ? 300000 : 0),
            json: jsonEncode(NativeWatchFaceArchive.configuration(archive)),
          );
          for (final row in report['faceCollectionFaces'] as List) {
            row['bundle'] = 'com.apple.NTKParmesanFaceBundle';
          }
          try {
            events.add({'type': 'connection', 'data': report});
            await tester.pump();
            await tester.pumpWidget(
              ChangeNotifierProvider.value(
                value: provider,
                child: CupertinoApp(
                  home: NativeFacePhotosScreen(
                    template: profile,
                    face: installed
                        ? provider.nativeFaces.collection.faces.first
                        : null,
                    files: _PhotoFiles(jpeg),
                    pair: facts.pair,
                    epoch: facts.epoch,
                  ),
                ),
              ),
            );
            await tester.runAsync(() async {
              await Future<void>.delayed(const Duration(milliseconds: 100));
            });
            await tester.pumpAndSettle();
            if (installed) {
              await tester.tap(find.text('Trailing'));
              await tester.pumpAndSettle();
            } else {
              await tester.tap(find.text('Choose Photo'));
              await tester.runAsync(() async {
                await Future<void>.delayed(const Duration(milliseconds: 100));
              });
              await tester.pumpAndSettle();
              await tester.ensureVisible(find.text('Keep Crop'));
              await tester.pumpAndSettle();
              await tester.tap(find.text('Keep Crop'));
              await tester.runAsync(() async {
                await Future<void>.delayed(const Duration(milliseconds: 100));
              });
              await tester.pumpAndSettle();
            }
            final action = find.widgetWithText(
              CupertinoButton,
              installed ? 'Apply to Watch' : 'Add to Watch',
            );
            await tester.ensureVisible(action);
            await tester.pumpAndSettle();
            await tester.tap(action);
            await tester.runAsync(() async {
              await Future<void>.delayed(const Duration(milliseconds: 100));
            });
            await tester.pump();
            final expected = stale
                ? ['refreshFaceCollection']
                : installed
                ? [
                    'beginNativeFaceImport',
                    'appendNativeFaceImport',
                    'finishNativeFaceImport',
                  ]
                : ['addNativeFace'];
            expect(calls.where((m) => m != 'exportNativeFace'), expected);
            events.add({
              'type': 'operation',
              'data': {
                'requestId': facts.second,
                'epoch': facts.epoch,
                'status': 'UNKNOWN',
              },
            });
            await tester.pumpAndSettle();
            expect(
              tester.widget<CupertinoButton>(action).onPressed,
              stale ? isNotNull : isNull,
            );
            // Unrelated native state publications do not imply photo success
            // or unlock a second copy/resource write after an uncertain result.
            events.add({'type': 'connection', 'data': report});
            await tester.pumpAndSettle();
            expect(
              tester.widget<CupertinoButton>(action).onPressed,
              stale ? isNotNull : isNull,
            );
            expect(calls.where((m) => m != 'exportNativeFace'), expected);
            expect(tester.takeException(), isNull);
          } finally {
            await tester.pumpWidget(const SizedBox());
            provider.dispose();
            bridge.dispose();
            unawaited(events.close());
            messenger.setMockMethodCallHandler(channel, null);
            await tester.pump();
          }
        },
      );
    }
  }

  testWidgets(
    'Photos exposes native nested palettes and ignores a choice after reconnect',
    (tester) async {
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      final profile = profiles!.singleWhere((v) => v.requiresPhotos);
      final config =
          jsonDecode(profile.configurationJson) as Map<String, dynamic>;
      profile.selectOption(config, 'style', 'duotone');
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      final data = facts.report(
        DateTime.now().millisecondsSinceEpoch,
        json: jsonEncode(config),
      );
      for (final row in data['faceCollectionFaces'] as List) {
        row['bundle'] = 'com.apple.NTKParmesanFaceBundle';
      }
      events.add({'type': 'connection', 'data': data});
      await tester.pump();
      final collection = provider.nativeFaces.collection;
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: CupertinoApp(
            home: NativeFaceEditorScreen(
              face: collection.faces.first,
              pair: collection.pair!,
              epoch: collection.epoch!,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('Photo Color'), 150);
      await tester.tap(find.text('Photo Color'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(CupertinoSearchTextField), 'orange');
      await tester.pumpAndSettle();
      expect(find.text('ORANGE'), findsOneWidget);
      events.add({
        'type': 'connection',
        'data': {...data, 'faceCollectionEpoch': facts.second},
      });
      await tester.pump();
      await tester.tap(find.text('ORANGE'));
      await tester.pumpAndSettle();
      expect(find.text('ORANGE'), findsNothing);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      await tester.pump();
    },
  );
  testWidgets(
    'Photos fits a narrow screen at 200% and disables a changed connection',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(320, 568);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.reset);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final profiles = await tester.runAsync(NativeFaceGallery.load);
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      events.add({
        'type': 'connection',
        'data': facts.report(DateTime.now().millisecondsSinceEpoch),
      });
      await tester.pump();
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: CupertinoApp(
            home: NativeFacePhotosScreen(
              template: profiles!.singleWhere((v) => v.requiresPhotos),
              pair: provider.nativeFaces.collection.pair!,
              epoch: provider.nativeFaces.collection.epoch!,
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
      events.add({
        'type': 'connection',
        'data': {
          ...facts.report(DateTime.now().millisecondsSinceEpoch + 1),
          'faceCollectionPair': '33333333-3333-3333-3333-333333333333',
        },
      });
      await tester.pumpAndSettle();
      expect(
        find.text(
          'This face changed or the watch reconnected. Open the face again before applying edits.',
        ),
        findsOneWidget,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      await tester.pump();
    },
  );
  testWidgets(
    'Creation gallery fits 320dp at 200% and refuses a changed pair',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(320, 568);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.reset);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await tester.runAsync(NativeGalleryCollections.load);
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      var calls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (_) async {
            calls++;
            return {'status': 'QUEUED', 'requestId': facts.second};
          });
      events.add({
        'type': 'connection',
        'data': facts.report(DateTime.now().millisecondsSinceEpoch),
      });
      await tester.pump();
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: const CupertinoApp(home: NativeFaceTemplatesScreen()),
        ),
      );
      await tester.runAsync(() async {
        await Future<void>.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(CupertinoSearchTextField), 'Flux');
      await tester.pumpAndSettle();
      final card = find
          .descendant(
            of: find.byType(NativeGallerySectionRow),
            matching: find.byType(CupertinoButton),
          )
          .first;
      await tester.ensureVisible(card);
      await tester.tap(card);
      await tester.pumpAndSettle();
      expect(find.text('Add to Watch'), findsOneWidget);
      expect(tester.takeException(), isNull);
      final baseline = calls;
      events.add({
        'type': 'connection',
        'data': {
          ...facts.report(DateTime.now().millisecondsSinceEpoch + 1),
          'faceCollectionPair': '33333333-3333-3333-3333-333333333333',
        },
      });
      await tester.pump();
      await tester.tap(find.text('Add to Watch'));
      await tester.pumpAndSettle();
      expect(calls, baseline);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
      await tester.pump();
    },
  );
  testWidgets(
    'Primary Gallery opens the exact factory appearance and Add packages that draft once',
    (tester) async {
      final rows = await tester.runAsync(NativeGalleryCollections.load);
      final row = rows!.singleWhere((s) => s.title('en') == 'California');
      final chosen = row.variants[1];
      final defaultJson = chosen.template.configurationJson;
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {
          'status': 'QUEUED',
          'requestId': facts.second,
          'faceId': facts.second,
        };
      });
      try {
        events.add({
          'type': 'connection',
          'data': facts.report(DateTime.now().millisecondsSinceEpoch),
        });
        await tester.pump();
        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: const CupertinoApp(home: FaceGalleryTab()),
          ),
        );
        await tester.runAsync(() async {
          await Future<void>.delayed(const Duration(milliseconds: 50));
        });
        await tester.pumpAndSettle();
        expect(find.byType(NativeFaceTemplatesScreen), findsOneWidget);
        expect(find.byType(NativeFacesScreen), findsNothing);
        await tester.enterText(
          find.byType(CupertinoSearchTextField),
          'California',
        );
        await tester.pumpAndSettle();
        expect(find.byType(NativeGallerySectionRow), findsOneWidget);
        final card = find
            .descendant(
              of: find.byType(NativeGallerySectionRow),
              matching: find.byType(CupertinoButton),
            )
            .at(1);
        await tester.ensureVisible(card);
        await tester.tap(card);
        await tester.pumpAndSettle();
        final editor = tester.widget<NativeFaceCreationScreen>(
          find.byType(NativeFaceCreationScreen),
        );
        expect(
          jsonDecode(editor.template.configurationJson),
          chosen.configuration,
        );
        expect(calls, isEmpty);
        expect(chosen.template.configurationJson, defaultJson);
        await tester.tap(find.text('Add to Watch'));
        await tester.pump();
        expect(calls, hasLength(1));
        expect(calls.single.method, 'addNativeFace');
        final submitted = NativeWatchFaceArchive.configuration(
          (calls.single.arguments as Map)['archive'] as Uint8List,
        );
        expect(
          submitted['customization'],
          chosen.configuration['customization'],
        );
        expect(
          submitted['complications'],
          chosen.configuration['complications'],
        );
        expect(submitted.containsKey('resource directory'), false);
        expect(chosen.template.configurationJson, defaultJson);
        events.add({
          'type': 'operation',
          'data': {
            'requestId': facts.second,
            'epoch': facts.epoch,
            'status': 'UNKNOWN',
          },
        });
        await tester.pumpAndSettle();
        await tester.tap(find.text('Add to Watch'), warnIfMissed: false);
        await tester.pump();
        expect(calls, hasLength(1));
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox());
        provider.dispose();
        bridge.dispose();
        unawaited(events.close());
        messenger.setMockMethodCallHandler(channel, null);
        await tester.pump();
      }
    },
  );
  testWidgets(
    'Gallery opened before connection refreshes once when the Watch arrives',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final provider = WatchProvider(
        storage: SettingsStorage(await SharedPreferences.getInstance()),
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      var reads = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            if (call.method == 'refreshFaceCollection') reads++;
            return {'status': 'QUEUED', 'requestId': facts.second};
          });
      await tester.pumpWidget(
        ChangeNotifierProvider.value(
          value: provider,
          child: const CupertinoApp(home: NativeFacesScreen(gallery: true)),
        ),
      );
      await tester.pumpAndSettle();
      expect(reads, 0);
      final stamp = DateTime.now().millisecondsSinceEpoch;
      final stale = facts.report(stamp - 310000);
      events.add({'type': 'connection', 'data': stale});
      await tester.pumpAndSettle();
      expect(reads, 1);
      events.add({'type': 'connection', 'data': stale});
      await tester.pumpAndSettle();
      expect(reads, 1);
      events.add({'type': 'connection', 'data': facts.report(stamp)});
      await tester.pumpAndSettle();
      expect(find.text('Collection received from the watch.'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
      provider.dispose();
      bridge.dispose();
      unawaited(events.close());
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
      await tester.pump();
    },
  );

  testWidgets(
    'Closing a durable installed editor keeps its pending save and completes only after native proof',
    (tester) async {
      SharedPreferences.setMockInitialValues({});
      final events = StreamController<dynamic>();
      final bridge = WatchBridgeService(events: events.stream);
      final storage = _HoldingStorage(await SharedPreferences.getInstance());
      final provider = WatchProvider(
        storage: storage,
        bridge: bridge,
        apiService: _Catalog(),
        ownsApiService: true,
      );
      const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
      final messenger =
          TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
      final calls = <MethodCall>[];
      messenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        return {
          'status': 'QUEUED',
          'requestId': facts.second,
          'faceId': facts.first,
        };
      });
      final stamp = DateTime.now().millisecondsSinceEpoch;
      events.add({'type': 'connection', 'data': facts.report(stamp)});
      await tester.pump();
      try {
        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: CupertinoApp(
              home: Builder(
                builder: (ctx) => CupertinoPageScaffold(
                  child: CupertinoButton(
                    child: const Text('Open'),
                    onPressed: () => Navigator.of(ctx).push(
                      CupertinoPageRoute(
                        builder: (_) => NativeFaceEditorScreen(
                          face: provider.nativeFaces.collection.faces.first,
                          pair: facts.pair,
                          epoch: facts.epoch,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('Open'));
        await tester.pumpAndSettle();
        await tester.scrollUntilVisible(find.text('Bottom'), 180);
        await tester.tap(find.text('Bottom'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('None'));
        await tester.pump();
        expect(calls, isEmpty);
        expect(find.text('Saving changes…'), findsOneWidget);
        expect(
          provider.nativeFaceAutosave.canLeave(facts.pair, facts.first),
          false,
        );
        storage.gate.complete();
        // Bound transition pumping: pumpAndSettle would advance the live save
        // spinner all the way to its protocol timeout.
        for (var i = 0; i < 20; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        for (var i = 0; i < 4; i++) {
          await tester.pump();
        }
        expect(calls.single.method, 'updateNativeFace');
        expect(provider.nativeFaces.busy, true);
        expect(find.byType(CupertinoActionSheet), findsNothing);
        final scope = tester.widget<PopScope<dynamic>>(
          find.descendant(
            of: find.byType(NativeFaceEditorScreen),
            matching: find.byWidgetPredicate((w) => w is PopScope),
          ),
        );
        expect(scope.canPop, true);
        expect(
          provider.nativeFaceAutosave.canLeave(facts.pair, facts.first),
          true,
        );
        final navigator = Navigator.of(
          tester.element(find.byType(NativeFaceEditorScreen)),
        );
        final popped = navigator.maybePop();
        await tester.pump();
        expect(await popped, true);
        for (var i = 0; i < 20; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        await tester.pump();
        expect(find.byType(NativeFaceEditorScreen), findsNothing);
        expect(find.text('Discard unsaved changes?'), findsNothing);
        expect(provider.nativeFaces.busy, true);
        expect(
          provider.nativeFaceAutosave.edit(facts.pair, facts.first),
          isNotNull,
        );
        final sent = calls.single.arguments['configuration'] as Uint8List;
        events.add({
          'type': 'connection',
          'data': facts.report(stamp + 1, json: utf8.decode(sent)),
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
        expect(
          provider.nativeFaceAutosave.edit(facts.pair, facts.first),
          isNotNull,
        );
        events.add({
          'type': 'operation',
          'data': {
            'requestId': facts.second,
            'epoch': facts.epoch,
            'status': 'NATIVE_FACE_APPLIED',
          },
        });
        for (var i = 0; i < 6; i++) {
          await tester.pump();
        }
        expect(
          provider.nativeFaceAutosave.edit(facts.pair, facts.first),
          isNull,
        );
        expect(calls.length, 1);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox());
        provider.dispose();
        bridge.dispose();
        unawaited(events.close());
        messenger.setMockMethodCallHandler(channel, null);
        await tester.pump();
      }
    },
  );

  for (final scale in [1.0, 2.0]) {
    testWidgets(
      'Native editor at 320dp and text scale $scale has no overflow and retains cleared slots',
      (tester) async {
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = const Size(320, 568);
        tester.platformDispatcher.textScaleFactorTestValue = scale;
        addTearDown(tester.view.reset);
        addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
        SharedPreferences.setMockInitialValues({});
        final events = StreamController<dynamic>();
        final bridge = WatchBridgeService(events: events.stream);
        const channel = MethodChannel('dev.applewatchandroid.companion/bridge');
        var updates = 0;
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, (call) async {
              expect(call.method, 'updateNativeFace');
              updates++;
              return {'status': 'REJECTED'};
            });
        events.add({
          'type': 'connection',
          'data': facts.report(
            DateTime.now().millisecondsSinceEpoch,
            json: facts.config.replaceFirst(
              '"descriptor":{"intent":"opaque"}',
              '"type":56,"descriptor":{"kind":"native-kind","intent":"opaque"}',
            ),
          ),
        });
        await tester.pump();
        final provider = WatchProvider(
          storage: SettingsStorage(await SharedPreferences.getInstance()),
          bridge: bridge,
          apiService: _Catalog(),
          ownsApiService: true,
        );
        await tester.pumpWidget(
          ChangeNotifierProvider.value(
            value: provider,
            child: CupertinoApp(
              home: NativeFaceEditorScreen(
                face: bridge.faceCollection.face(facts.first)!,
                pair: facts.pair,
                epoch: facts.epoch,
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        await tester.scrollUntilVisible(
          find.text('Bottom'),
          180,
          scrollable: find.byType(Scrollable).first,
        );
        await tester.ensureVisible(find.text('Bottom'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Bottom'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('None'));
        await tester.pumpAndSettle();
        expect(find.text('Bottom'), findsOneWidget);
        expect(find.text('None'), findsOneWidget);
        expect(tester.takeException(), isNull);
        await tester.ensureVisible(find.text('Bottom'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('Bottom'));
        await tester.pumpAndSettle();
        expect(find.text('native-kind'), findsOneWidget);
        await tester.tap(find.text('Cancel'));
        await tester.pumpAndSettle();
        unawaited(
          Navigator.of(
            tester.element(find.byType(NativeFaceEditorScreen)),
          ).maybePop(),
        );
        await tester.pumpAndSettle();
        expect(find.text('Discard unsaved changes?'), findsNothing);
        expect(
          provider.nativeFaceAutosave.canLeave(facts.pair, facts.first),
          true,
        );
        expect(find.text('None'), findsOneWidget);
        expect(updates, 1);
        expect(tester.takeException(), isNull);
        await tester.tap(find.text('Retry').first);
        await tester.pumpAndSettle();
        expect(updates, 2);
        expect(tester.takeException(), isNull);
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, null);
        await tester.pumpWidget(const SizedBox());
        provider.dispose();
        bridge.dispose();
        unawaited(events.close());
        await tester.pump();
      },
    );
  }
}
