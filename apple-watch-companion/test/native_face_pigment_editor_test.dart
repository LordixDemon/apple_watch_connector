import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show Slider;
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/controllers/native_face_controller.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_watch_face_archive.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_creation_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_editor_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_section.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_preview.dart';
import 'native_face_management_test.dart' as facts;

class _Catalog extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) async => [];
}

void main() {
  for (final creation in [false, true]) {
    testWidgets(
      '${creation ? "Creation waits for Add" : "Installed editor automatically saves and serializes"} exact native shades',
      (tester) async {
        await tester.binding.setSurfaceSize(const Size(800, 1800));
        addTearDown(() => tester.binding.setSurfaceSize(null));
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
          return {
            'status': 'QUEUED',
            'requestId': facts.second,
            'faceId': facts.first,
          };
        });
        final config =
            jsonDecode(profile.configurationJson) as Map<String, dynamic>;
        config['complications'] = {
          'center': {
            'descriptor': 'opaque',
            'intent': {'keep': 'native'},
          },
        };
        final json = jsonEncode(config);
        final report = {
          ...facts.report(
            DateTime.now().millisecondsSinceEpoch,
            order: [facts.first],
            json: json,
          ),
          'faceCollectionFaces': [
            {
              'id': facts.first,
              'bundle': '',
              'configurationBytes': utf8.encode(json).length,
              'configuration': Uint8List.fromList(utf8.encode(json)),
              'archiveAvailable': true,
            },
          ],
        };
        events.add({'type': 'connection', 'data': report});
        await tester.pump();
        if (!creation) {
          final previous = provider.nativeFaces.select(facts.first);
          await tester.pump();
          events.add({
            'type': 'connection',
            'data': {
              ...report,
              'faceCollectionObservedAt':
                  (report['faceCollectionObservedAt'] as int) + 1,
            },
          });
          await tester.pump();
          expect(await previous, isTrue);
          expect(provider.nativeFaces.result, NativeFaceResult.applied);
          calls.clear();
        }
        final semantics = tester.ensureSemantics();
        try {
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
          await tester.scrollUntilVisible(
            find.byType(NativeFacePigmentSectionView),
            140,
            scrollable: find
                .byWidgetPredicate(
                  (w) =>
                      w is Scrollable && w.axisDirection == AxisDirection.down,
                )
                .first,
          );
          final options = profile
              .pigmentSection('color')!
              .options
              .keys
              .toList();
          final scroll = tester.state<ScrollableState>(
            find.descendant(
              of: find.byType(NativeFacePigmentSectionView),
              matching: find.byType(Scrollable),
            ),
          );
          scroll.position.jumpTo(
            (options.indexOf('standard.orange') * 64.0).clamp(
              0,
              scroll.position.maxScrollExtent,
            ),
          );
          await tester.pumpAndSettle();
          await tester.tap(find.bySemanticsLabel('COLOR: ORANGE'));
          if (creation) {
            await tester.pumpAndSettle();
          } else {
            await tester.pump(const Duration(milliseconds: 300));
            await tester.pump();
          }
          await tester.ensureVisible(find.byType(Slider));
          final slider = tester.widget<Slider>(find.byType(Slider));
          final beforeDragCalls = calls.length;
          final desiredBeforeDrag = provider.nativeFaceAutosave
              .edit(facts.pair, facts.first)
              ?.desired;
          final initialPreview = tester
              .widget<NativeFacePreview>(find.byType(NativeFacePreview).first)
              .configuration!;
          slider.onChanged!(25);
          await tester.pump();
          final draggingPreview = tester
              .widget<NativeFacePreview>(find.byType(NativeFacePreview).first)
              .configuration!;
          expect(
            draggingPreview['customization']['color'],
            'standard.orange:0.25',
          );
          expect(initialPreview['customization']['color'], 'standard.orange');
          expect(
            identical(
              draggingPreview['complications'],
              initialPreview['complications'],
            ),
            isTrue,
          );
          expect(calls, hasLength(beforeDragCalls));
          expect(
            provider.nativeFaceAutosave.edit(facts.pair, facts.first)?.desired,
            desiredBeforeDrag,
          );
          tester.widget<Slider>(find.byType(Slider)).onChangeEnd!(25);
          if (creation) {
            await tester.pumpAndSettle();
          } else {
            await tester.pump(const Duration(milliseconds: 300));
            await tester.pump();
            expect(provider.nativeFaces.busy, true);
            expect(
              jsonDecode(
                provider.nativeFaceAutosave
                    .edit(facts.pair, facts.first)!
                    .desired,
              )['customization']['color'],
              'standard.orange:0.25',
            );
          }
          expect(calls, creation ? isEmpty : hasLength(1));
          expect(
            provider.nativeFaces.collection.faces.first.configuration,
            config,
          );
          if (creation) {
            final submit = find.text('Add to Watch');
            await tester.scrollUntilVisible(
              submit,
              220,
              scrollable: find
                  .byWidgetPredicate(
                    (w) =>
                        w is Scrollable &&
                        w.axisDirection == AxisDirection.down,
                  )
                  .first,
            );
            await tester.tap(submit);
            await tester.pump();
          } else {
            expect(find.text('Changes confirmed by the watch.'), findsNothing);
            expect(find.text('Apply to Watch'), findsNothing);
            final initial = calls.single.arguments as Map;
            final sent = initial['configuration'] as Uint8List;
            expect(
              jsonDecode(utf8.decode(sent))['customization']['color'],
              'standard.orange',
            );
            events.add({
              'type': 'operation',
              'data': {
                'requestId': facts.second,
                'epoch': facts.epoch,
                'status': 'APP_ACK_RECEIVED',
              },
            });
            await tester.pump();
            expect(calls, hasLength(1));
            events.add({
              'type': 'connection',
              'data': {
                ...report,
                'faceCollectionObservedAt':
                    (report['faceCollectionObservedAt'] as int) + 2,
                'faceCollectionFaces': [
                  {
                    'id': facts.first,
                    'bundle': '',
                    'configurationBytes': sent.length,
                    'configuration': sent,
                    'archiveAvailable': true,
                  },
                ],
              },
            });
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
          }
          expect(calls, hasLength(creation ? 1 : 2));
          expect(
            calls.last.method,
            creation ? 'addNativeFace' : 'updateNativeFace',
          );
          final args = calls.last.arguments as Map;
          final submitted = creation
              ? NativeWatchFaceArchive.configuration(
                  args['archive'] as Uint8List,
                )
              : jsonDecode(utf8.decode(args['configuration'] as Uint8List))
                    as Map<String, dynamic>;
          expect(submitted['customization']['color'], 'standard.orange:0.25');
          final metrics = submitted['metrics'] as Map;
          expect(metrics['numberOfCompanionEdits'], 1);
          expect(metrics['editedState'], 2);
          expect(metrics['dateLastEdited'], isA<double>());
          if (creation) {
            expect(metrics['origin'], 6);
            expect(metrics['dateCreated'], metrics['dateLastEdited']);
            expect(config.containsKey('metrics'), false);
          } else {
            final initial =
                jsonDecode(
                      utf8.decode(
                        calls.first.arguments['configuration'] as Uint8List,
                      ),
                    )
                    as Map<String, dynamic>;
            expect(metrics, initial['metrics']);
          }
          if (!creation) {
            expect(submitted['complications'], config['complications']);
          }
          expect(provider.nativeFaces.busy, true);
        } finally {
          await tester.pumpWidget(const SizedBox());
          semantics.dispose();
          provider.dispose();
          bridge.dispose();
          unawaited(events.close());
          messenger.setMockMethodCallHandler(channel, null);
        }
      },
    );
  }
}
