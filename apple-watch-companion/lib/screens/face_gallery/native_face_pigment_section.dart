import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart'
    show
        Material,
        Slider,
        SliderTheme,
        SliderThemeData,
        RoundedRectSliderTrackShape,
        RoundSliderThumbShape,
        RoundSliderOverlayShape,
        SliderTickMarkShape;
import '../../l10n/strings.dart';
import '../../services/native_face_pigment.dart';
import '../../services/native_pigment_favorites.dart';
import '../../services/native_pigment_check_artwork.dart';
import '../../controllers/native_pigment_sync_controller.dart';
import '../../theme/ios_colors.dart';
import '../../theme/companion_spacing.dart';
import '../../widgets/ios_list_section.dart';
import 'native_face_pigment_header.dart';

double _swatchDiameter(
  BuildContext context,
  NativeFacePigmentSection section,
) => section.layout.diameter(View.of(context).display.size.shortestSide);

/// Native colors, not full-face bitmaps or hand-written color approximations.
/// The shade control owns transient drag state, avoiding rebuilding the editor.
class NativeFacePigmentSectionView extends StatefulWidget {
  final NativeFacePigmentSection section;
  final String title, selected;
  final String Function(String) label;
  final ValueChanged<String>? onSelected;
  final ValueChanged<String>? onPreviewSelected;
  final NativePigmentFavorites? favorites;
  final bool Function()? canCommit;
  const NativeFacePigmentSectionView({
    super.key,
    required this.section,
    required this.title,
    required this.selected,
    required this.label,
    this.onSelected,
    this.onPreviewSelected,
    this.favorites,
    this.canCommit,
  });
  @override
  State<NativeFacePigmentSectionView> createState() =>
      _NativeFacePigmentSectionViewState();
}

class _NativeFacePigmentSectionViewState
    extends State<NativeFacePigmentSectionView> {
  final _scroll = ScrollController();
  String? _revealed;
  List<String> _visible = const [];
  bool _retrying = false;
  double? _itemExtent;
  double? _separatorExtent;
  int? _separatorIndex;

  Future<void> _retryColors() async {
    final favorites = widget.favorites;
    if (_retrying ||
        favorites == null ||
        !favorites.canRetry ||
        widget.onSelected == null ||
        widget.canCommit?.call() == false) {
      return;
    }
    setState(() => _retrying = true);
    try {
      await favorites.retrySync();
    } catch (_) {
      // The storage failure is retained by the scoped synchronization owner.
    } finally {
      if (mounted) setState(() => _retrying = false);
    }
  }

  Future<void> _openColors() async {
    final favorites = widget.favorites, section = widget.section;
    if (favorites == null || widget.onSelected == null) return;
    final pair = favorites.pair;
    bool active() =>
        mounted &&
        widget.favorites == favorites &&
        favorites.canEdit &&
        favorites.pair == pair &&
        widget.section == section &&
        widget.onSelected != null &&
        widget.canCommit?.call() != false;
    // Native presents above its parent controller, covering the tab bar.
    final changed = await Navigator.of(context, rootNavigator: true).push<bool>(
      CupertinoPageRoute(
        fullscreenDialog: true,
        builder: (_) => NativeFacePigmentPicker(
          section: section,
          favorites: favorites,
          label: widget.label,
          canCommit: active,
        ),
      ),
    );
    if (changed != true || !active()) return;
    final visible = favorites.visibleOptions(section);
    // Native selectLastColor falls back to defaultOption when the palette is empty.
    final token = visible.isNotEmpty ? visible.last : section.defaultToken;
    if (token != null) widget.onSelected!(token);
  }

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.favorites == null
      ? _build(context)
      : ListenableBuilder(
          listenable: widget.favorites!,
          builder: (context, _) => _build(context),
        );

  Widget _build(BuildContext context) {
    final diameter = _swatchDiameter(context, widget.section);
    final layout = widget.section.layout;
    final strip = layout.strip;
    final row = layout.row;
    final expanded =
        View.of(context).display.size.shortestSide >
        layout.expandedAbovePhysicalPixels;
    final cellWidth = strip?.cellWidth(diameter, expanded) ?? diameter + 22;
    final spacing = strip?.cellSpacing(expanded) ?? 0.0;
    final itemExtent = cellWidth + spacing;
    final contentInset = row == null
        ? 0.0
        : row.horizontalInset(expanded) - strip!.outlinePadding(expanded) / 2;
    final visible =
        widget.favorites?.visibleOptions(
          widget.section,
          selected: widget.selected,
        ) ??
        widget.section.options.keys.toList(growable: false);
    // The scoped palette already partitions once by native slider support.
    final options = widget.favorites != null
        ? visible
        : [
            ...visible.where(widget.section.editable.contains),
            ...visible.where((t) => !widget.section.editable.contains(t)),
          ];
    final addable =
        widget.favorites != null &&
        widget.section.choices.values.any((c) => c.addable);
    final editableCount = options
        .where(widget.section.editable.contains)
        .length;
    final separated =
        strip != null &&
        editableCount > 0 &&
        (editableCount < options.length || addable);
    final separatorIndex = separated ? editableCount : null;
    final separatorExtent = separated
        ? strip.dividerCellWidth(expanded) + spacing
        : 0.0;
    final count = options.length + (separated ? 1 : 0) + (addable ? 1 : 0);
    final selected = widget.section.optionFor(widget.selected);
    if (_revealed != selected ||
        !_sameOptions(options, _visible) ||
        _itemExtent != itemExtent ||
        _separatorExtent != separatorExtent ||
        _separatorIndex != separatorIndex) {
      final initialLayout = _revealed == null;
      _revealed = selected;
      _visible = options;
      _itemExtent = itemExtent;
      _separatorExtent = separatorExtent;
      _separatorIndex = separatorIndex;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted ||
            !_scroll.hasClients ||
            _revealed != selected ||
            _itemExtent != itemExtent ||
            _separatorExtent != separatorExtent ||
            _separatorIndex != separatorIndex ||
            !_sameOptions(options, _visible)) {
          return;
        }
        final index = options.indexOf(
          widget.section.optionFor(widget.selected) ?? '',
        );
        if (index < 0) return;
        final position = _scroll.position;
        final start =
            contentInset +
            index * itemExtent +
            (separated && index >= editableCount ? separatorExtent : 0);
        final end = start + cellWidth;
        // UICollectionView preserves visible items (including partial ones).
        // Initial layout or an offscreen selection uses centeredHorizontally.
        final offset =
            initialLayout ||
                end <= position.pixels ||
                start >= position.pixels + position.viewportDimension
            ? (start + end - position.viewportDimension) / 2
            : position.pixels;
        _scroll.jumpTo(
          offset.clamp(position.minScrollExtent, position.maxScrollExtent),
        );
      });
    }
    final pigment = widget.section.pigmentFor(widget.selected);
    final syncMessage = switch (widget.favorites?.syncState) {
      PigmentSyncState.pending => Strings.current.nativeFaceColorsPending,
      PigmentSyncState.awaitingDelivery =>
        Strings.current.nativeFaceColorsAwaiting,
      PigmentSyncState.delivered => Strings.current.nativeFaceColorsSent,
      PigmentSyncState.deliveryUncertain =>
        Strings.current.nativeFaceColorsDeliveryUncertain,
      PigmentSyncState.uncertain => Strings.current.nativeFaceColorsUncertain,
      PigmentSyncState.storageError =>
        Strings.current.nativeFaceColorsSaveError,
      _ => null,
    };
    return IosListSection(
      margin: row == null
          ? CompanionSpacing.sectionMargin
          : EdgeInsets.symmetric(
              horizontal: row.sectionHorizontalInset,
              vertical: CompanionSpacing.sectionGap / 2,
            ),
      header: row == null ? widget.title : null,
      headerWidget: row == null
          ? null
          : NativeFacePigmentHeader(
              layout: row,
              title: widget.title,
              subtitle: widget.label(widget.selected),
              expanded: expanded,
            ),
      footer: syncMessage,
      children: [
        if (widget.favorites?.canRetry == true &&
            (widget.favorites!.syncState == PigmentSyncState.uncertain ||
                widget.favorites!.syncState == PigmentSyncState.storageError ||
                widget.favorites!.syncState == PigmentSyncState.pending))
          CupertinoButton(
            onPressed:
                _retrying ||
                    widget.onSelected == null ||
                    widget.canCommit?.call() == false
                ? null
                : _retryColors,
            child: _retrying
                ? const CupertinoActivityIndicator()
                : Text(Strings.current.nativeFaceColorsRetry),
          ),
        Padding(
          padding: row == null
              ? const EdgeInsets.only(top: 8, bottom: 8)
              : EdgeInsets.zero,
          child: Column(
            children: [
              SizedBox(
                height: row?.height(diameter) ?? cellWidth,
                child: ListView.separated(
                  controller: _scroll,
                  scrollDirection: Axis.horizontal,
                  padding: EdgeInsets.symmetric(horizontal: contentInset),
                  itemCount: count,
                  separatorBuilder: (_, _) => SizedBox(width: spacing),
                  itemBuilder: (_, index) {
                    if (separated && index == editableCount) {
                      return ExcludeSemantics(
                        child: SizedBox(
                          width: strip.dividerCellWidth(expanded),
                          child: Center(
                            child: Image.asset(
                              strip.dividerImage,
                              width: strip.dividerWidth,
                              height: strip.dividerHeight,
                            ),
                          ),
                        ),
                      );
                    }
                    if (addable && index == count - 1) {
                      return SizedBox(
                        width: cellWidth,
                        child: Semantics(
                          label: Strings.current.nativeFaceAddColors,
                          button: true,
                          enabled:
                              widget.onSelected != null &&
                              widget.favorites!.canEdit &&
                              widget.canCommit?.call() != false,
                          excludeSemantics: true,
                          child: CupertinoButton(
                            padding: EdgeInsets.zero,
                            minimumSize: Size.zero,
                            onPressed:
                                widget.onSelected == null ||
                                    !widget.favorites!.canEdit ||
                                    widget.canCommit?.call() == false
                                ? null
                                : _openColors,
                            child: strip == null
                                ? const Icon(
                                    CupertinoIcons.add_circled,
                                    size: 42,
                                    color: IosColors.systemOrange,
                                  )
                                : Image.asset(
                                    strip.plusImage,
                                    width: strip.plusWidth,
                                    height: strip.plusHeight,
                                  ),
                          ),
                        ),
                      );
                    }
                    final token =
                        options[index -
                            (separated && index > editableCount ? 1 : 0)];
                    return SizedBox(
                      width: cellWidth,
                      child: Semantics(
                        label: '${widget.title}: ${widget.label(token)}',
                        selected: token == selected,
                        enabled: widget.onSelected != null,
                        button: true,
                        excludeSemantics: true,
                        child: CupertinoButton(
                          padding: EdgeInsets.zero,
                          minimumSize: Size.zero,
                          onPressed: widget.onSelected == null
                              ? null
                              : () => widget.onSelected!(token),
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              border: Border.all(
                                color: token == selected
                                    ? IosColors.systemOrange
                                    : CupertinoColors.transparent,
                                width: strip?.outlineWidth(expanded) ?? 2,
                              ),
                            ),
                            child: Padding(
                              padding: EdgeInsets.all(
                                strip == null
                                    ? 3
                                    : strip.outlinePadding(expanded) / 2,
                              ),
                              child: NativeFacePigmentSwatch(
                                pigment: widget.section.options[token]!,
                                percent: token == selected
                                    ? widget.section.options[token]!.percentFor(
                                        widget.selected,
                                      )
                                    : null,
                                diameter: diameter,
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
              if (row == null)
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 4,
                  ),
                  child: Text(
                    widget.label(widget.selected).toUpperCase(),
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 13,
                      color: IosColors.label,
                    ),
                  ),
                ),
            ],
          ),
        ),
        if (pigment != null && pigment.supportsShade)
          _NativePigmentShade(
            key: ValueKey(pigment.base),
            pigment: pigment,
            selected: widget.selected,
            onSelected: widget.onSelected,
            onPreviewSelected: widget.onPreviewSelected,
            diameter: diameter,
          ),
      ],
    );
  }

  static bool _sameOptions(List<String> a, List<String> b) =>
      a.length == b.length &&
      Iterable<int>.generate(a.length).every((i) => a[i] == b[i]);
}

/// Original Add Colors uses vertically grouped, independently scrolling rows,
/// not a grid of face previews. Choices are staged until Done.
class NativeFacePigmentPicker extends StatefulWidget {
  final NativeFacePigmentSection section;
  final NativePigmentFavorites favorites;
  final String Function(String) label;
  final bool Function() canCommit;
  const NativeFacePigmentPicker({
    super.key,
    required this.section,
    required this.favorites,
    required this.label,
    required this.canCommit,
  });
  @override
  State<NativeFacePigmentPicker> createState() =>
      _NativeFacePigmentPickerState();
}

class _NativeFacePigmentPickerState extends State<NativeFacePigmentPicker> {
  final Map<String, bool> _initial = {}, _selected = {};
  final List<List<String>> _groups = [];
  bool _saving = false;
  String? _error;
  String? _pair;
  bool get _canCommit =>
      widget.canCommit() &&
      widget.favorites.canEdit &&
      widget.favorites.pair == _pair;

  @override
  void initState() {
    super.initState();
    _pair = widget.favorites.pair;
    String? collection;
    for (final entry in widget.section.choices.entries) {
      final choice = entry.value;
      if (!choice.addable) continue;
      _initial[choice.name] = widget.favorites.isVisible(choice);
      if (collection != choice.collection) {
        collection = choice.collection;
        _groups.add([]);
      }
      _groups.last.add(entry.key);
    }
    _selected.addAll(_initial);
  }

  Future<void> _done() async {
    if (_saving || !_canCommit) return;
    final changes = {
      for (final entry in _selected.entries)
        if (_initial[entry.key] != entry.value) entry.key: entry.value,
    };
    if (changes.isEmpty) {
      Navigator.pop(context, false);
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final changed = await widget.favorites.update(
        changes,
        canCommit: () => _canCommit,
      );
      if (!mounted) return;
      if (_canCommit) {
        Navigator.pop(context, changed);
      } else {
        setState(() => _saving = false);
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error =
              widget.favorites.syncState == PigmentSyncState.awaitingDelivery
              ? Strings.current.nativeFaceColorsAwaiting
              : Strings.current.nativeFaceColorsSaveError;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final layout = widget.section.layout;
    final metrics = layout.picker;
    final diameter = metrics.diameter;
    final inset = layout.pickerHorizontalInset(
      View.of(context).display.size.shortestSide,
    );
    return PopScope(
      canPop: !_saving,
      child: CupertinoPageScaffold(
        backgroundColor: IosColors.systemBackground,
        navigationBar: CupertinoNavigationBar(
          middle: Text(Strings.current.nativeFaceAddColors),
          leading: CupertinoButton(
            padding: EdgeInsets.zero,
            onPressed: _saving ? null : () => Navigator.pop(context, false),
            child: Text(Strings.current.cancel),
          ),
          trailing: CupertinoButton(
            padding: EdgeInsets.zero,
            onPressed: _saving || !_canCommit ? null : _done,
            child: _saving
                ? const CupertinoActivityIndicator()
                : Text(Strings.current.nativeFaceColorsDone),
          ),
        ),
        child: SafeArea(
          child: CustomScrollView(
            slivers: [
              if (_error != null)
                SliverToBoxAdapter(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Text(
                      _error!,
                      style: const TextStyle(color: IosColors.systemRed),
                    ),
                  ),
                ),
              SliverList.builder(
                itemCount: _groups.length,
                itemBuilder: (context, index) {
                  final tokens = _groups[index];
                  final choice = widget.section.choices[tokens.first]!;
                  return Padding(
                    padding: EdgeInsets.only(
                      top: index == 0
                          ? metrics.topInset
                          : metrics.sectionSpacing,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Padding(
                          padding: EdgeInsets.symmetric(horizontal: inset),
                          child: Text(
                            choice.title(
                              Localizations.localeOf(context).languageCode,
                            ),
                            textScaler: MediaQuery.textScalerOf(context).clamp(
                              maxScaleFactor:
                                  metrics.headerMaxFontSize /
                                  metrics.headerFontSize,
                            ),
                            style: TextStyle(
                              fontSize: metrics.headerFontSize,
                              color: IosColors.label,
                            ),
                          ),
                        ),
                        SizedBox(height: metrics.headerGap),
                        SizedBox(
                          height: diameter,
                          child: ListView.separated(
                            key: PageStorageKey('pigment-group-$index'),
                            scrollDirection: Axis.horizontal,
                            padding: EdgeInsets.symmetric(horizontal: inset),
                            separatorBuilder: (_, _) =>
                                SizedBox(width: metrics.itemSpacing),
                            itemCount: tokens.length,
                            itemBuilder: (_, offset) {
                              final token = tokens[offset];
                              final color = widget.section.choices[token]!;
                              final selected = _selected[color.name]!;
                              final enabled = !_saving && _canCommit;
                              return Semantics(
                                label: widget.label(token),
                                checked: selected,
                                enabled: enabled,
                                button: true,
                                excludeSemantics: true,
                                child: CupertinoButton(
                                  padding: EdgeInsets.zero,
                                  minimumSize: Size.zero,
                                  onPressed: enabled
                                      ? () => setState(
                                          () =>
                                              _selected[color.name] = !selected,
                                        )
                                      : null,
                                  child: SizedBox.square(
                                    dimension: diameter,
                                    child: Stack(
                                      clipBehavior: Clip.none,
                                      children: [
                                        NativeFacePigmentSwatch(
                                          pigment:
                                              widget.section.options[token]!,
                                          diameter: diameter,
                                        ),
                                        if (selected &&
                                            widget.section.checkArtwork != null)
                                          Positioned(
                                            left:
                                                (diameter -
                                                    widget
                                                        .section
                                                        .checkArtwork!
                                                        .width) /
                                                2,
                                            top:
                                                (diameter -
                                                    widget
                                                        .section
                                                        .checkArtwork!
                                                        .height) /
                                                2,
                                            width: widget
                                                .section
                                                .checkArtwork!
                                                .width,
                                            height: widget
                                                .section
                                                .checkArtwork!
                                                .height,
                                            child: NativeFacePigmentCheck(
                                              artwork:
                                                  widget.section.checkArtwork!,
                                              primary: widget
                                                  .section
                                                  .options[token]!
                                                  .color,
                                            ),
                                          ),
                                      ],
                                    ),
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
              SliverToBoxAdapter(
                child: SizedBox(height: metrics.sectionSpacing),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// One shared native palette basis; selecting a color never decodes another glyph.
class NativeFacePigmentCheck extends StatelessWidget {
  final NativePigmentCheckArtwork artwork;
  final Color? primary;
  const NativeFacePigmentCheck({
    super.key,
    required this.artwork,
    this.primary,
  });

  @override
  Widget build(BuildContext context) => SizedBox(
    width: artwork.width,
    height: artwork.height,
    child: Image(
      image: ExactAssetImage(
        primary == null ? artwork.templateImage : artwork.paletteImage,
      ),
      color: artwork.primaryColor(primary),
      colorBlendMode: primary == null ? null : BlendMode.modulate,
      excludeFromSemantics: true,
    ),
  );
}

class NativeFacePigmentSwatch extends StatelessWidget {
  final NativeFacePigment pigment;
  final int? percent;
  final double diameter;
  const NativeFacePigmentSwatch({
    super.key,
    required this.pigment,
    this.percent,
    this.diameter = 42,
  });
  @override
  Widget build(BuildContext context) => SizedBox.square(
    dimension: diameter,
    child: pigment.image != null
        ? Image(
            image: ExactAssetImage(pigment.image!),
            fit: BoxFit.contain,
            color:
                percent != null &&
                    percent != pigment.defaultPercent &&
                    pigment.shadeImageMask
                ? pigment.colorAt(percent!)
                : null,
            colorBlendMode: pigment.shadeImageMask ? BlendMode.srcIn : null,
            errorBuilder: (_, _, _) =>
                const Icon(CupertinoIcons.exclamationmark_circle),
          )
        : DecoratedBox(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: percent != null && pigment.supportsShade
                  ? pigment.colorAt(percent!)
                  : pigment.color,
              border: Border.all(color: IosColors.separator, width: .5),
            ),
          ),
  );
}

class _NativePigmentShade extends StatefulWidget {
  final NativeFacePigment pigment;
  final String selected;
  final ValueChanged<String>? onSelected;
  final ValueChanged<String>? onPreviewSelected;
  final double diameter;
  const _NativePigmentShade({
    super.key,
    required this.pigment,
    required this.selected,
    this.onSelected,
    this.onPreviewSelected,
    required this.diameter,
  });
  @override
  State<_NativePigmentShade> createState() => _NativePigmentShadeState();
}

class _NativePigmentShadeState extends State<_NativePigmentShade> {
  late int _percent;
  @override
  void initState() {
    super.initState();
    _percent = widget.pigment.percentFor(widget.selected)!;
  }

  @override
  void didUpdateWidget(_NativePigmentShade oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selected != widget.selected || widget.onSelected == null) {
      _percent = widget.pigment.percentFor(widget.selected)!;
    }
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
    child: Row(
      children: [
        ExcludeSemantics(
          child: NativeFacePigmentSwatch(
            pigment: widget.pigment,
            percent: _percent,
            diameter: widget.diameter,
          ),
        ),
        Expanded(
          child: Material(
            color: CupertinoColors.transparent,
            child: SliderTheme(
              data: SliderThemeData(
                trackHeight: 8,
                trackShape: _NativePigmentTrack(widget.pigment.stops),
                thumbColor: CupertinoColors.white,
                disabledThumbColor: IosColors.secondaryLabel,
                overlayColor: CupertinoColors.transparent,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 12),
                overlayShape: const RoundSliderOverlayShape(overlayRadius: 18),
                tickMarkShape: const _NoTicks(),
              ),
              child: Semantics(
                label: Strings.current.nativeFaceShade,
                child: Slider(
                  value: _percent.toDouble(),
                  min: 0,
                  max: 100,
                  divisions: 100,
                  semanticFormatterCallback: (value) => '${value.round()}%',
                  onChanged: widget.onSelected == null
                      ? null
                      : (value) {
                          setState(() => _percent = value.round());
                          widget.onPreviewSelected?.call(
                            widget.pigment.tokenAt(_percent),
                          );
                        },
                  onChangeEnd: widget.onSelected == null
                      ? null
                      : (value) => widget.onSelected!(
                          widget.pigment.tokenAt(value.round()),
                        ),
                ),
              ),
            ),
          ),
        ),
      ],
    ),
  );
}

class _NativePigmentTrack extends RoundedRectSliderTrackShape {
  final List<Color> colors;
  const _NativePigmentTrack(this.colors);
  @override
  void paint(
    PaintingContext context,
    Offset offset, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required Offset thumbCenter,
    Offset? secondaryOffset,
    bool isEnabled = false,
    bool isDiscrete = false,
    required TextDirection textDirection,
    double additionalActiveTrackHeight = 2,
  }) {
    final rect = getPreferredRect(
      parentBox: parentBox,
      offset: offset,
      sliderTheme: sliderTheme,
      isEnabled: isEnabled,
      isDiscrete: isDiscrete,
    );
    final gradient = LinearGradient(
      colors: textDirection == TextDirection.rtl
          ? colors.reversed.toList()
          : colors,
      stops: const [0, .5, 1],
    );
    context.canvas.drawRRect(
      RRect.fromRectAndRadius(rect, const Radius.circular(4)),
      Paint()..shader = gradient.createShader(rect),
    );
  }
}

// Shade ticks are native serialization steps, not visible marks on the gradient.
class _NoTicks extends SliderTickMarkShape {
  const _NoTicks();
  @override
  Size getPreferredSize({
    required SliderThemeData sliderTheme,
    bool isEnabled = false,
  }) => Size.zero;
  @override
  void paint(
    PaintingContext context,
    Offset center, {
    required RenderBox parentBox,
    required SliderThemeData sliderTheme,
    required Animation<double> enableAnimation,
    required Offset thumbCenter,
    required bool isEnabled,
    required TextDirection textDirection,
  }) {}
}
