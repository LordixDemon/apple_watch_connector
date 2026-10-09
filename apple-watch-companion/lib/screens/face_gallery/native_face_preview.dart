import 'dart:convert';
import 'package:flutter/cupertino.dart';
import '../../services/native_face_gallery.dart';
import '../../services/native_configured_previews.dart';
import '../../services/native_indexed_preview_image.dart';
import '../../services/native_component_previews.dart';
import '../../services/native_component_preview_image.dart';
import '../../services/native_preview_catalog.dart';
import '../../l10n/strings.dart';

/// A verified native style render. Demo time and blank complications are explicit.
class NativeFacePreview extends StatelessWidget {
  final NativeFaceTemplate template;
  final double width;
  final Map<String, dynamic>? configuration;
  final bool fallbackToDefault;
  final String? _matchedAsset;
  final bool _hideUnavailable;
  const NativeFacePreview({
    super.key,
    required this.template,
    this.width = 52,
    this.configuration,
    this.fallbackToDefault = true,
  }) : _matchedAsset = null,
       _hideUnavailable = false;

  const NativeFacePreview._matched({
    required this.template,
    required String asset,
  }) : _matchedAsset = asset,
       width = 36,
       configuration = null,
       fallbackToDefault = false,
       _hideUnavailable = false;

  const NativeFacePreview._candidate({
    required this.template,
    required this.configuration,
  }) : _matchedAsset = null,
       width = 36,
       fallbackToDefault = false,
       _hideUnavailable = true;

  static String caption(
    NativeFaceTemplate template,
    Map<String, dynamic>? configuration,
  ) => _caption(
    template,
    configuration,
    NativeConfiguredPreviews.asset(template.family, configuration),
  );

  // A default sample is evidence only for the default customization. In
  // particular, changing a pigment fraction must not replace SUBDIALS with RINGS.
  static bool _matchesDefault(
    NativeFaceTemplate template,
    Map<String, dynamic>? configuration,
  ) =>
      configuration == null ||
      NativeConfiguredPreviews.keyFor(template.family, configuration) ==
          NativeConfiguredPreviews.keyFor(
            template.family,
            jsonDecode(template.configurationJson) as Map<String, dynamic>,
          );

  static String _caption(
    NativeFaceTemplate template,
    Map<String, dynamic>? configuration,
    String? asset,
  ) =>
      asset != null ||
          NativeComponentPreviews.resolve(template.family, configuration) !=
              null
      ? Strings.current.nativeFaceStylePreview
      : _matchesDefault(template, configuration)
      ? Strings.current.nativeFaceDefaultPreview
      : Strings.current.nativeFacePreviewUnavailable;

  static Widget? option(
    NativeFaceTemplate template,
    Map<String, dynamic>? configuration,
    String field,
    String value,
  ) {
    if (configuration == null ||
        template.options[field]?.contains(value) != true) {
      return null;
    }
    // Only visual customization participates; no copy of opaque complication intents.
    final candidate = <String, dynamic>{
      'customization': jsonDecode(jsonEncode(configuration['customization'])),
    };
    template.selectOption(candidate, field, value);
    if (NativeComponentPreviews.resolve(template.family, candidate) != null) {
      return NativeFacePreview._candidate(
        template: template,
        configuration: candidate,
      );
    }
    final asset = NativeConfiguredPreviews.asset(template.family, candidate);
    if (asset == null) {
      final key = NativeConfiguredPreviews.keyFor(template.family, candidate)!;
      return NativeConfiguredPreviews.catalog.isResolved(key) &&
              NativeComponentPreviews.resolve(template.family, candidate) ==
                  null
          ? null
          : NativeFacePreview._candidate(
              template: template,
              configuration: candidate,
            );
    }
    return NativeFacePreview._matched(template: template, asset: asset);
  }

  @override
  Widget build(BuildContext context) {
    if (_matchedAsset != null) return _buildImage(context, _matchedAsset);
    // The template already contains a verified default render. Avoid starting
    // a metadata worker when the caller requested that sample without edits.
    if (configuration == null && fallbackToDefault) {
      return _buildImage(context, template.previewAsset);
    }
    final config =
        configuration ??
        (fallbackToDefault
            ? jsonDecode(template.configurationJson) as Map<String, dynamic>
            : null);
    if (NativeComponentPreviews.resolve(template.family, config) != null) {
      return _buildImage(context, null);
    }
    return NativePreviewAssetResolver(
      configurationKey: NativeConfiguredPreviews.keyFor(
        template.family,
        config,
      ),
      builder: (context, asset) => _buildImage(context, asset),
    );
  }

  Widget _buildImage(BuildContext context, String? asset) {
    // Keep one native demo timeline across a supported shade range, including
    // its catalogued midpoint; mixing dates from two captures would flicker.
    final component = NativeComponentPreviews.resolve(
      template.family,
      configuration,
    );
    if (_hideUnavailable && asset == null && component == null) {
      return const SizedBox.shrink();
    }
    final verifiedAsset =
        asset ??
        (fallbackToDefault && _matchesDefault(template, configuration)
            ? template.previewAsset
            : null);
    return ExcludeSemantics(
      child: SizedBox(
        width: width,
        height: width * 1.22,
        child: template.requiresPhotos
            ? const Icon(CupertinoIcons.photo)
            : verifiedAsset == null && component == null
            ? const Icon(CupertinoIcons.clock)
            : ClipRRect(
                borderRadius: BorderRadius.circular(width * .2),
                child: Image(
                  image: component != null
                      ? NativeComponentPreviewImage(
                          component,
                          cacheWidth:
                              (width *
                                      MediaQuery.devicePixelRatioOf(
                                        context,
                                      ).clamp(1, 3))
                                  .ceil(),
                        )
                      : _image(
                          verifiedAsset!,
                          (width *
                                  MediaQuery.devicePixelRatioOf(
                                    context,
                                  ).clamp(1, 3))
                              .ceil(),
                        ),
                  fit: BoxFit.contain,
                  errorBuilder: (_, _, _) => const Icon(CupertinoIcons.clock),
                ),
              ),
      ),
    );
  }

  static ImageProvider _image(String asset, int cacheWidth) =>
      asset.endsWith('.nfp')
      ? NativeIndexedPreviewImage(asset, cacheWidth: cacheWidth)
      // Managed native images have one exact file, without resolution variants.
      // Avoid loading the application's entire asset manifest just to find it.
      : ResizeImage(ExactAssetImage(asset), width: cacheWidth);
}

/// Caption shares the same coalesced lookup as the image and never calls an
/// unrelated default sample the selected style while metadata is still loading.
class NativeFacePreviewCaption extends StatelessWidget {
  final NativeFaceTemplate template;
  final Map<String, dynamic>? configuration;
  final TextStyle? style;
  const NativeFacePreviewCaption({
    super.key,
    required this.template,
    required this.configuration,
    this.style,
  });
  @override
  Widget build(BuildContext context) {
    final config =
        configuration ??
        jsonDecode(template.configurationJson) as Map<String, dynamic>;
    if (NativeComponentPreviews.resolve(template.family, config) != null) {
      return Text(Strings.current.nativeFaceStylePreview, style: style);
    }
    return NativePreviewAssetResolver(
      configurationKey: NativeConfiguredPreviews.keyFor(
        template.family,
        configuration ??
            jsonDecode(template.configurationJson) as Map<String, dynamic>,
      ),
      builder: (_, asset) => Text(
        NativeFacePreview._caption(template, configuration, asset),
        style: style,
      ),
    );
  }
}

class NativePreviewAssetResolver extends StatefulWidget {
  final String? configurationKey;
  final NativePreviewCatalog? catalog;
  final Widget Function(BuildContext, String?) builder;
  const NativePreviewAssetResolver({
    super.key,
    required this.configurationKey,
    required this.builder,
    this.catalog,
  });
  @override
  State<NativePreviewAssetResolver> createState() =>
      _NativePreviewResolutionState();
}

class _NativePreviewResolutionState extends State<NativePreviewAssetResolver> {
  String? _key, _asset;
  int _generation = 0;
  @override
  void initState() {
    super.initState();
    _resolve();
  }

  @override
  void didUpdateWidget(covariant NativePreviewAssetResolver oldWidget) {
    super.didUpdateWidget(oldWidget);
    _resolve(force: oldWidget.catalog != widget.catalog);
  }

  void _resolve({bool force = false}) {
    final key = widget.configurationKey;
    final catalog = widget.catalog ?? NativeConfiguredPreviews.catalog;
    if (!force && _generation != 0 && _key == key) return;
    _key = key;
    final generation = ++_generation;
    _asset = key == null ? null : catalog.peek(key);
    if (key == null || catalog.isResolved(key)) return;
    catalog
        .resolve(key)
        .then(
          (asset) {
            if (mounted && generation == _generation) {
              setState(() => _asset = asset);
            }
          },
          onError: (Object error, StackTrace stack) {
            // A missing/corrupt preview cannot block editing or publish a false style.
            if (mounted && generation == _generation) {
              setState(() => _asset = null);
            }
          },
        );
  }

  @override
  Widget build(BuildContext context) => widget.builder(context, _asset);
}
