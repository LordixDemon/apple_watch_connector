// Explicit profile entry point; never included by lib/main.dart.
// Uses production gallery widgets/assets and refuses every Watch command.
import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'dart:ui' show FramePhase, FrameTiming;
import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/scheduler.dart';
import 'package:provider/provider.dart';
import 'package:apple_watch_companion/models/watch_face.dart';
import 'package:apple_watch_companion/models/native_face_collection.dart';
import 'package:apple_watch_companion/providers/watch_provider.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_templates_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_grid_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_library_screen.dart';
import 'package:apple_watch_companion/widgets/native_face_collection_panel.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_creation_screen.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_value_picker.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_preview.dart';
import 'package:apple_watch_companion/screens/face_gallery/native_face_pigment_section.dart';
import 'package:apple_watch_companion/services/bridge_transport.dart';
import 'package:apple_watch_companion/services/native_face_gallery.dart';
import 'package:apple_watch_companion/services/native_face_options.dart';
import 'package:apple_watch_companion/services/native_gallery_collections.dart';
import 'package:apple_watch_companion/services/native_configured_previews.dart';
import 'package:apple_watch_companion/services/native_indexed_preview_image.dart';
import 'package:apple_watch_companion/services/native_component_preview_image.dart';
import 'package:apple_watch_companion/services/settings_storage.dart';
import 'package:apple_watch_companion/services/watch_bridge_service.dart';
import 'package:apple_watch_companion/services/watch_face_api_service.dart';
import 'package:apple_watch_companion/theme/ios_theme.dart';

class _NoWatch implements BridgeTransport {
  final observations = StreamController<dynamic>();
  @override
  Stream<dynamic> get events => observations.stream;
  @override
  Future<dynamic> invoke(String method, [Map<String, dynamic>? arguments]) =>
      Future.error(StateError('Profiling never sends Watch commands: $method'));
}

class _NoCatalog extends WatchFaceApiService {
  @override
  Future<List<WatchFace>> loadCachedFaces() async => [];
  @override
  Future<List<WatchFace>> fetchRemoteFaces({String endpoint = ''}) async => [];
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  if (!kProfileMode) throw StateError('Run this entry point in profile mode');
  final loading = Stopwatch()..start();
  final sections = await NativeGalleryCollections.load();
  debugPrint('NATIVE_FACE_PROFILE_LOAD_US ${loading.elapsedMicroseconds}');
  debugPrint(
    'NATIVE_FACE_PROFILE_FACTORY ${jsonEncode({'sections': sections.length, 'variants': sections.fold<int>(0, (sum, s) => sum + s.variants.length)})}',
  );
  final transport = _NoWatch();
  final bridge = WatchBridgeService(transport: transport);
  final provider = WatchProvider(
    storage: await SettingsStorage.init(),
    bridge: bridge,
    apiService: _NoCatalog(),
    ownsApiService: true,
  );
  const workflow = String.fromEnvironment(
    'NATIVE_FACE_PROFILE_WORKFLOW',
    defaultValue: 'gallery',
  );
  if (workflow == 'myfaces' || workflow == 'library') {
    // In-memory measurement fixture only. No phone state or native archives
    // are read or written; real transport invocation is still always rejected.
    final variants = <NativeGalleryVariant>[];
    final keys = <String>{};
    for (final variant in sections.expand((section) => section.variants)) {
      if (variant.template.requiresPhotos) continue;
      final key = NativeConfiguredPreviews.keyFor(
        variant.template.family,
        variant.configuration,
      );
      if (key == null || keys.contains(key)) continue;
      final asset = await NativeConfiguredPreviews.resolve(
        variant.template.family,
        variant.configuration,
      );
      if (asset == null) continue;
      keys.add(key);
      variants.add(variant);
      if (variants.length == 32) break;
    }
    if (variants.length < 3) {
      debugPrint('NATIVE_FACE_PROFILE_FAILED Too few exact native previews');
      throw StateError('Too few exact native previews for scrolling');
    }
    final ids = List.generate(
      variants.length,
      (i) => '00000000-0000-4000-8000-${(i + 1).toString().padLeft(12, '0')}',
    );
    transport.observations.add({
      'type': 'connection',
      'data': {
        'connected': true,
        'faceCollectionKnown': true,
        'faceCollectionPair': '00000000-0000-4000-8000-000000000001',
        'faceCollectionEpoch': '00000000-0000-4000-8000-000000000002',
        'faceCollectionObservedAt': DateTime.now().millisecondsSinceEpoch,
        'faceCollectionComplete': true,
        'faceCollectionOrderKnown': true,
        'faceCollectionSelectionKnown': true,
        'activeFaceId': ids.first,
        'faceCollectionOrder': ids,
        'faceCollectionFaces': [
          for (final (i, variant) in variants.indexed)
            {
              'id': ids[i],
              'bundle': variant.configuration['bundle id'] ?? '',
              'configurationBytes': utf8
                  .encode(variant.configurationJson)
                  .length,
              'configuration': Uint8List.fromList(
                utf8.encode(variant.configurationJson),
              ),
              'archiveAvailable': false,
            },
        ],
      },
    });
    debugPrint(
      'NATIVE_FACE_PROFILE_FIXTURE ${ids.length} exact native factory previews; metadata preloaded; no Watch',
    );
  }
  runApp(
    ChangeNotifierProvider.value(
      value: provider,
      child: CupertinoApp(
        theme: IosTheme.cupertinoDarkTheme,
        home: const _Profile(),
      ),
    ),
  );
}

class _Profile extends StatefulWidget {
  const _Profile();
  @override
  State<_Profile> createState() => _ProfileState();
}

class _ProfileState extends State<_Profile> {
  static const _family = String.fromEnvironment(
    'NATIVE_FACE_PROFILE_FAMILY',
    defaultValue: 'bundle:com.apple.NTKLeghornFaceBundle',
  );
  static const _workflow = String.fromEnvironment(
    'NATIVE_FACE_PROFILE_WORKFLOW',
    defaultValue: 'gallery',
  );
  final _gallery = GlobalKey();
  final _frames = <FrameTiming>[];
  bool _recording = false;
  bool _scrollAuditReported = false;
  String _status = 'Profiling native faces — no Watch commands';

  @override
  void initState() {
    super.initState();
    SchedulerBinding.instance.addTimingsCallback(_timings);
    WidgetsBinding.instance.addPostFrameCallback((_) => unawaited(_run()));
  }

  void _timings(List<FrameTiming> frames) {
    if (_recording) _frames.addAll(frames);
  }

  ScrollableState? _scrollable() {
    ScrollableState? result;
    void visit(Element element) {
      if (element is StatefulElement && element.state is ScrollableState) {
        result = element.state as ScrollableState;
      } else {
        element.visitChildren(visit);
      }
    }

    (_gallery.currentContext as Element?)?.visitChildren(visit);
    return result;
  }

  Future<void> _cycle() async {
    if (!mounted) return;
    if (_workflow == 'components') {
      await _componentCycle();
      return;
    }
    final gridSections = _workflow == 'grid'
        ? await NativeGalleryCollections.load()
        : null;
    if (!mounted) return;
    final route = CupertinoPageRoute<void>(
      builder: (context) => _workflow == 'colors'
          ? _colors(context)
          : _workflow == 'palette'
          ? _palette()
          : _workflow == 'creation'
          ? NativeFaceCreationScreen(
              key: _gallery,
              template: NativeFaceGallery.profile(_family)!,
            )
          : _workflow == 'myfaces'
          ? CupertinoPageScaffold(
              child: SafeArea(
                child: SingleChildScrollView(
                  child: NativeFaceCollectionPanel(
                    key: _gallery,
                    controller: context.read<WatchProvider>().nativeFaces,
                    faceTitle: _title,
                    openFace: (_) {},
                    manage: () {},
                  ),
                ),
              ),
            )
          : _workflow == 'library'
          ? NativeFaceLibraryScreen(
              key: _gallery,
              controller: context.read<WatchProvider>().nativeFaces,
              faceTitle: _title,
              openFace: (_) {},
            )
          : _workflow == 'grid'
          ? NativeFaceGridScreen(
              key: _gallery,
              sections: gridSections!,
              controller: context.read<WatchProvider>().nativeFaces,
              onSelect: (_) {},
            )
          : NativeFaceTemplatesScreen(key: _gallery, gallery: true),
    );
    unawaited(Navigator.of(context).push(route));
    try {
      await Future<void>.delayed(const Duration(milliseconds: 450));
      final scrollable = _scrollable();
      if (scrollable == null) {
        throw StateError('Gallery scrollable is unavailable');
      }
      final position = scrollable.position;
      final extent = position.maxScrollExtent;
      if ((_workflow == 'myfaces' || _workflow == 'library') && extent <= 0) {
        throw StateError('Fixture has no scroll extent');
      }
      await position.animateTo(
        position.maxScrollExtent,
        duration: const Duration(milliseconds: 700),
        curve: Curves.linear,
      );
      // Lazy slivers can revise their estimated extent while scrolling. Settle
      // layout, then follow the actual end rather than measuring a partial pass.
      await SchedulerBinding.instance.endOfFrame;
      for (
        var retry = 0;
        retry < 8 && (position.pixels - position.maxScrollExtent).abs() > 1;
        retry++
      ) {
        await position.animateTo(
          position.maxScrollExtent,
          duration: const Duration(milliseconds: 100),
          curve: Curves.linear,
        );
        await SchedulerBinding.instance.endOfFrame;
      }
      if (_workflow == 'myfaces') {
        if (!mounted) return;
        final collection = context.read<WatchProvider>().faceCollection;
        var lastCardVisible = false;
        void visit(Element element) {
          if (element.widget.key == ValueKey(collection.ordered.last)) {
            lastCardVisible = true;
          }
          element.visitChildren(visit);
        }

        (_gallery.currentContext as Element?)?.visitChildren(visit);
        if (!lastCardVisible ||
            (position.pixels - position.maxScrollExtent).abs() > 1) {
          debugPrint(
            'NATIVE_FACE_PROFILE_SCROLL_FAILED ${jsonEncode({'faces': collection.ordered.length, 'initialMaxExtent': extent, 'actualMaxExtent': position.maxScrollExtent, 'reachedPixels': position.pixels, 'lastCardVisible': lastCardVisible, 'axis': position.axis.name})}',
          );
          throw StateError('Carousel did not reach the final fixture card');
        }
        if (!_scrollAuditReported) {
          _scrollAuditReported = true;
          debugPrint(
            'NATIVE_FACE_PROFILE_SCROLL ${jsonEncode({'faces': collection.ordered.length, 'initialMaxExtent': extent, 'reachedPixels': position.pixels, 'lastCardVisible': lastCardVisible})}',
          );
        }
      }
      if (_workflow == 'grid') {
        final count = gridSections!.fold<int>(
          0,
          (sum, section) => sum + section.variants.length,
        );
        var lastCardVisible = false;
        void visit(Element element) {
          if (element.widget.key == ValueKey('native-all-face-${count - 1}')) {
            lastCardVisible = true;
          }
          element.visitChildren(visit);
        }

        (_gallery.currentContext as Element?)?.visitChildren(visit);
        if (!lastCardVisible ||
            (position.pixels - position.maxScrollExtent).abs() > 1) {
          throw StateError('Grid did not reach its final preset');
        }
        if (!_scrollAuditReported) {
          _scrollAuditReported = true;
          debugPrint(
            'NATIVE_FACE_PROFILE_SCROLL ${jsonEncode({'presets': count, 'initialMaxExtent': extent, 'reachedPixels': position.pixels, 'lastCardVisible': lastCardVisible})}',
          );
        }
      }
      await position.animateTo(
        0,
        duration: const Duration(milliseconds: 700),
        curve: Curves.linear,
      );
    } finally {
      if (mounted && route.isCurrent) Navigator.of(context).pop();
      await Future<void>.delayed(const Duration(milliseconds: 450));
    }
  }

  String _title(NativeWatchFace face) =>
      NativeFaceOptions.profile(face)?.title('en') ?? face.bundle;

  int _componentPass = 0;
  Future<void> _componentCycle() async {
    final template = NativeFaceGallery.profile('type:activity analog rich')!;
    final colors = template
        .pigmentSection('color')!
        .options
        .values
        .where((pigment) => pigment.supportsShade)
        .toList();
    final pigment = colors[_componentPass++ % colors.length];
    debugPrint(
      'NATIVE_FACE_PROFILE_CASE ${jsonEncode({'base': pigment.base, 'styles': template.options['detail'], 'steps': 21})}',
    );
    final shade = ValueNotifier<int>(0);
    final route = CupertinoPageRoute<void>(
      builder: (_) => CupertinoPageScaffold(
        navigationBar: const CupertinoNavigationBar(
          middle: Text('Native component profile'),
        ),
        child: SafeArea(
          child: Center(
            child: ValueListenableBuilder<int>(
              valueListenable: shade,
              builder: (_, percent, _) => Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  for (final detail in ['simple', 'detailed'])
                    NativeFacePreview(
                      template: template,
                      width: 112,
                      configuration: {
                        'customization': <String, dynamic>{
                          'detail': detail,
                          'color': pigment.tokenAt(percent),
                        },
                      },
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
    unawaited(Navigator.of(context).push(route));
    try {
      await Future<void>.delayed(const Duration(milliseconds: 450));
      for (var percent = 0; percent <= 100 && mounted; percent += 5) {
        shade.value = percent;
        await Future<void>.delayed(const Duration(milliseconds: 65));
      }
    } finally {
      if (route.isActive && mounted) Navigator.of(context).pop();
      await Future<void>.delayed(const Duration(milliseconds: 400));
      shade.dispose();
    }
  }

  Widget _palette() {
    final template = NativeFaceGallery.profile(_family)!;
    final configuration =
        jsonDecode(template.configurationJson) as Map<String, dynamic>;
    return NativeFaceValuePicker(
      key: _gallery,
      title: 'Native color previews',
      selected: (configuration['customization'] as Map)['color'] as String,
      values: template.options['color']!,
      label: (value) => template.valueTitle('color', value, 'en') ?? value,
      preview: (value) =>
          NativeFacePreview.option(template, configuration, 'color', value),
    );
  }

  Widget _colors(BuildContext context) {
    final template = NativeFaceGallery.profile(_family)!;
    return NativeFacePigmentPicker(
      key: _gallery,
      section: template.pigmentSection('color')!,
      favorites: context.read<WatchProvider>().pigmentFavorites,
      label: (token) => template.valueTitle('color', token, 'en') ?? token,
      canCommit: () =>
          false, // Scroll-only measurements never persist favorites.
    );
  }

  double _percentile(List<int> values, double percentile) {
    values.sort();
    return values[((values.length - 1) * percentile).round()] / 1000;
  }

  void _report(int round) {
    if (_frames.isEmpty) throw StateError('No engine FrameTiming measurements');
    final builds = _frames.map((v) => v.buildDuration.inMicroseconds).toList();
    final rasters = _frames
        .map((v) => v.rasterDuration.inMicroseconds)
        .toList();
    final starts =
        _frames
            .map((v) => v.timestampInMicroseconds(FramePhase.vsyncStart))
            .toList()
          ..sort();
    final cadence = [
      for (var i = 1; i < starts.length; i++)
        if (starts[i] > starts[i - 1] && starts[i] - starts[i - 1] < 100000)
          starts[i] - starts[i - 1],
    ];
    final overheads = _frames
        .map((v) => v.vsyncOverhead.inMicroseconds)
        .toList();
    final spans = _frames.map((v) => v.totalSpan.inMicroseconds).toList();
    final budget = 1000000 / View.of(context).display.refreshRate;
    debugPrint(
      'NATIVE_FACE_PROFILE ${jsonEncode({'workflow': _workflow, 'family': _workflow == 'components' ? 'type:activity analog rich' : _family, 'round': round, 'cycles': 10, 'frames': _frames.length, 'displayHz': View.of(context).display.refreshRate, 'buildP95Ms': _percentile(builds, .95), 'buildP99Ms': _percentile(builds, .99), 'rasterP95Ms': _percentile(rasters, .95), 'rasterP99Ms': _percentile(rasters, .99), 'buildOverBudget': builds.where((v) => v > budget).length, 'rasterOverBudget': rasters.where((v) => v > budget).length, 'imageCacheBytes': PaintingBinding.instance.imageCache.currentSizeBytes, 'imageCacheEntries': PaintingBinding.instance.imageCache.currentSize})}',
    );
    debugPrint(
      'NATIVE_FACE_PROFILE_ENGINE ${jsonEncode({'round': round, 'frameCadenceP50Ms': cadence.isEmpty ? null : _percentile(cadence, .5), 'frameCadenceP95Ms': cadence.isEmpty ? null : _percentile(cadence, .95), 'cadenceIntervals': cadence.length, 'idleGapThresholdMs': 100, 'vsyncOverheadP95Ms': _percentile(overheads, .95), 'totalSpanP95Ms': _percentile(spans, .95)})}',
    );
    debugPrint(
      'NATIVE_FACE_PROFILE_PIXELS ${jsonEncode(NativeIndexedPreviewImage.profileMetrics)}',
    );
    debugPrint(
      'NATIVE_FACE_PROFILE_COMPONENTS ${jsonEncode(NativeComponentPreviewImage.profileMetrics)}',
    );
    final catalog = NativeConfiguredPreviews.catalog;
    debugPrint(
      'NATIVE_FACE_PROFILE_CATALOG ${jsonEncode({'cachedShards': catalog.cachedShardCount, 'cachedEncodedBytes': catalog.cachedEncodedBytes, 'memoEntries': catalog.memoEntryCount, 'activeLoads': catalog.activeLoads, 'pendingLoads': catalog.pendingLoads})}',
    );
  }

  Future<void> _run() async {
    try {
      if (!const {
        'gallery',
        'grid',
        'creation',
        'palette',
        'colors',
        'myfaces',
        'library',
        'components',
      }.contains(_workflow)) {
        throw StateError('Unknown profile workflow');
      }
      await Future<void>.delayed(const Duration(seconds: 1));
      if (!mounted) return;
      if (_workflow == 'palette') {
        final template = NativeFaceGallery.profile(_family)!;
        final configuration =
            jsonDecode(template.configurationJson) as Map<String, dynamic>;
        final asset = await NativeConfiguredPreviews.resolve(
          _family,
          configuration,
        );
        if (!mounted) return;
        if (asset?.endsWith('.nfp') == true) {
          Object? imageError;
          final loading = Stopwatch()..start();
          await precacheImage(
            NativeIndexedPreviewImage(
              asset!,
              cacheWidth:
                  (36 * MediaQuery.devicePixelRatioOf(context).clamp(1, 3))
                      .ceil(),
            ),
            context,
            onError: (error, stack) => imageError = error,
          );
          if (imageError != null) throw StateError('First image: $imageError');
          debugPrint(
            'NATIVE_FACE_PROFILE_FIRST_IMAGE_US ${loading.elapsedMicroseconds}',
          );
        }
      }
      for (var i = 0; i < 2; i++) {
        await _cycle();
      }
      for (var round = 1; round <= 3; round++) {
        _frames.clear();
        _recording = true;
        for (var i = 0; i < 10; i++) {
          await _cycle();
        }
        // Engine reports timings in batches; flush before reading this round.
        await Future<void>.delayed(const Duration(milliseconds: 1100));
        _recording = false;
        _report(round);
      }
      if (mounted) setState(() => _status = 'Native gallery profile complete');
      debugPrint('NATIVE_FACE_PROFILE_DONE');
    } catch (error) {
      _recording = false;
      if (mounted) setState(() => _status = 'Profile failed: $error');
      debugPrint('NATIVE_FACE_PROFILE_FAILED $error');
    }
  }

  @override
  void dispose() {
    SchedulerBinding.instance.removeTimingsCallback(_timings);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CupertinoPageScaffold(
    child: SafeArea(child: Center(child: Text(_status))),
  );
}
