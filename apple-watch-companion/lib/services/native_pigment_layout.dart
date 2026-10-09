/// Original small-circle metrics, supplied by versioned native metadata.
/// UIKit selects its larger size only above the captured physical screen threshold.
final class NativePigmentLayout {
  final double compactDiameter, expandedDiameter, expandedAbovePhysicalPixels;
  final NativePigmentPickerLayout picker;
  final NativePigmentStripLayout? strip;
  final NativePigmentRowLayout? row;
  const NativePigmentLayout._(
    this.compactDiameter,
    this.expandedDiameter,
    this.expandedAbovePhysicalPixels, [
    this.picker = NativePigmentPickerLayout.legacy,
    this.strip,
    this.row,
  ]);

  // Compatibility for pre-geometry catalogs and isolated synthetic sections.
  static const legacy = NativePigmentLayout._(42, 42, 0);

  factory NativePigmentLayout.fromJson(Object? value) {
    if (value == null) return legacy;
    const baseKeys = {
      'version',
      'compactDiameter',
      'expandedDiameter',
      'expandedAbovePhysicalPixels',
    };
    final version = value is Map ? value['version'] : null;
    final keys = {
      ...baseKeys,
      if (version == 2 || version == 3 || version == 4) 'picker',
      if (version == 3 || version == 4) 'strip',
      if (version == 4) 'row',
    };
    if (value is! Map ||
        value.length != keys.length ||
        !value.keys.toSet().containsAll(keys) ||
        value['version'] is! int ||
        (version != 1 && version != 2 && version != 3 && version != 4) ||
        baseKeys
            .where((k) => k != 'version')
            .any((k) => value[k] is! num || !(value[k] as num).isFinite)) {
      throw const FormatException('Invalid native pigment layout.');
    }
    final compact = (value['compactDiameter'] as num).toDouble();
    final expanded = (value['expandedDiameter'] as num).toDouble();
    final threshold = (value['expandedAbovePhysicalPixels'] as num).toDouble();
    if (compact < 12 ||
        expanded < compact ||
        expanded > 96 ||
        threshold < 100 ||
        threshold > 8192) {
      throw const FormatException('Invalid native pigment layout bounds.');
    }
    final strip = version == 3 || version == 4
        ? NativePigmentStripLayout.fromJson(value['strip'])
        : null;
    final row = version == 4
        ? NativePigmentRowLayout.fromJson(value['row'])
        : null;
    if (row != null &&
        (row.swatchVerticalInset < strip!.outlinePadding(true) / 2 ||
            row.compactHorizontalInset < strip.outlinePadding(true) / 2)) {
      throw const FormatException('Invalid native pigment row padding.');
    }
    return NativePigmentLayout._(
      compact,
      expanded,
      threshold,
      version == 2 || version == 3 || version == 4
          ? NativePigmentPickerLayout.fromJson(value['picker'])
          : NativePigmentPickerLayout.legacy,
      strip,
      row,
    );
  }

  double diameter(double physicalShortestSide) =>
      physicalShortestSide > expandedAbovePhysicalPixels
      ? expandedDiameter
      : compactDiameter;

  double pickerHorizontalInset(double physicalShortestSide) =>
      physicalShortestSide > expandedAbovePhysicalPixels
      ? picker.expandedHorizontalInset
      : picker.compactHorizontalInset;
}

/// The color collection has no description label; its subtitle lives in the header.
final class NativePigmentRowLayout {
  final double sectionHorizontalInset;
  final double swatchVerticalInset,
      compactHorizontalInset,
      expandedHorizontalInset;
  final double headerTopInset,
      headerLabelGap,
      headerFontSize,
      headerMaxFontSize;
  final double headerLineHeight;
  const NativePigmentRowLayout._(
    this.sectionHorizontalInset,
    this.swatchVerticalInset,
    this.compactHorizontalInset,
    this.expandedHorizontalInset,
    this.headerTopInset,
    this.headerLabelGap,
    this.headerFontSize,
    this.headerMaxFontSize,
    this.headerLineHeight,
  );
  factory NativePigmentRowLayout.fromJson(Object? value) {
    const keys = {
      'sectionHorizontalInset',
      'swatchVerticalInset',
      'compactHorizontalInset',
      'expandedHorizontalInset',
      'headerTopInset',
      'headerLabelGap',
      'headerFontSize',
      'headerMaxFontSize',
      'headerLineHeight',
    };
    if (value is! Map ||
        value.length != keys.length ||
        !value.keys.toSet().containsAll(keys) ||
        keys.any(
          (k) =>
              value[k] is! num ||
              !(value[k] as num).isFinite ||
              value[k] <= 0 ||
              value[k] > 96,
        ) ||
        value['expandedHorizontalInset'] < value['compactHorizontalInset'] ||
        value['headerFontSize'] < 12 ||
        value['headerMaxFontSize'] < value['headerFontSize'] ||
        value['headerLineHeight'] < value['headerFontSize']) {
      throw const FormatException('Invalid native pigment row layout.');
    }
    double n(String k) => (value[k] as num).toDouble();
    return NativePigmentRowLayout._(
      n('sectionHorizontalInset'),
      n('swatchVerticalInset'),
      n('compactHorizontalInset'),
      n('expandedHorizontalInset'),
      n('headerTopInset'),
      n('headerLabelGap'),
      n('headerFontSize'),
      n('headerMaxFontSize'),
      n('headerLineHeight'),
    );
  }
  double height(double diameter) => diameter + 2 * swatchVerticalInset;
  double horizontalInset(bool expanded) =>
      expanded ? expandedHorizontalInset : compactHorizontalInset;
}

/// Horizontal geometry and original glyphs for the main color strip.
/// Vertical row/font layout is separate from these captured metrics.
final class NativePigmentStripLayout {
  final double compactOutlineWidth,
      expandedOutlineWidth,
      outlineOutset,
      swatchSpacing;
  final double plusWidth, plusHeight, dividerWidth, dividerHeight;
  final String plusImage, dividerImage;
  const NativePigmentStripLayout._(
    this.compactOutlineWidth,
    this.expandedOutlineWidth,
    this.outlineOutset,
    this.swatchSpacing,
    this.plusWidth,
    this.plusHeight,
    this.dividerWidth,
    this.dividerHeight,
    this.plusImage,
    this.dividerImage,
  );
  factory NativePigmentStripLayout.fromJson(Object? value) {
    const numbers = {
      'compactOutlineWidth',
      'expandedOutlineWidth',
      'outlineOutset',
      'swatchSpacing',
      'plusWidth',
      'plusHeight',
      'dividerWidth',
      'dividerHeight',
    };
    const images = {'plusImage', 'dividerImage'};
    final keys = {...numbers, ...images};
    if (value is! Map ||
        value.length != keys.length ||
        !value.keys.toSet().containsAll(keys) ||
        numbers.any(
          (k) =>
              value[k] is! num ||
              !(value[k] as num).isFinite ||
              value[k] <= 0 ||
              value[k] > 96,
        ) ||
        images.any(
          (k) =>
              value[k] is! String ||
              !RegExp(r'^[a-f0-9]{64}\.png$').hasMatch(value[k]),
        ) ||
        value['expandedOutlineWidth'] < value['compactOutlineWidth'] ||
        2 * (value['outlineOutset'] + value['expandedOutlineWidth']) >=
            value['swatchSpacing']) {
      throw const FormatException('Invalid native pigment strip layout.');
    }
    double n(String key) => (value[key] as num).toDouble();
    String image(String key) =>
        'assets/native_faces/pigment_swatches/${value[key]}';
    return NativePigmentStripLayout._(
      n('compactOutlineWidth'),
      n('expandedOutlineWidth'),
      n('outlineOutset'),
      n('swatchSpacing'),
      n('plusWidth'),
      n('plusHeight'),
      n('dividerWidth'),
      n('dividerHeight'),
      image('plusImage'),
      image('dividerImage'),
    );
  }
  double outlineWidth(bool expanded) =>
      expanded ? expandedOutlineWidth : compactOutlineWidth;
  double outlinePadding(bool expanded) =>
      2 * (outlineOutset + outlineWidth(expanded));
  double cellWidth(double diameter, bool expanded) =>
      diameter + outlinePadding(expanded);
  double cellSpacing(bool expanded) => swatchSpacing - outlinePadding(expanded);
  double dividerCellWidth(bool expanded) =>
      dividerWidth + outlinePadding(expanded);
}

/// Add Colors has its own fixed cell size and adaptive section insets.
final class NativePigmentPickerLayout {
  final double diameter, compactHorizontalInset, expandedHorizontalInset;
  final double topInset, headerGap, sectionSpacing, itemSpacing;
  final double headerFontSize, headerMaxFontSize;
  const NativePigmentPickerLayout._(
    this.diameter,
    this.compactHorizontalInset,
    this.expandedHorizontalInset,
    this.topInset,
    this.headerGap,
    this.sectionSpacing,
    this.itemSpacing,
    this.headerFontSize,
    this.headerMaxFontSize,
  );

  // Compatibility only; production metrics come from the native source capture.
  static const legacy = NativePigmentPickerLayout._(
    42,
    16,
    16,
    25,
    12,
    25,
    12,
    15,
    33,
  );
  factory NativePigmentPickerLayout.fromJson(Object? value) {
    const keys = {
      'diameter',
      'compactHorizontalInset',
      'expandedHorizontalInset',
      'topInset',
      'headerGap',
      'sectionSpacing',
      'itemSpacing',
      'headerFontSize',
      'headerMaxFontSize',
    };
    if (value is! Map ||
        value.length != keys.length ||
        !value.keys.toSet().containsAll(keys) ||
        keys.any(
          (key) =>
              value[key] is! num ||
              !(value[key] as num).isFinite ||
              (value[key] as num) < 0 ||
              (value[key] as num) > 96,
        ) ||
        (value['diameter'] as num) < 12 ||
        (value['headerFontSize'] as num) < 12 ||
        (value['headerMaxFontSize'] as num) <
            (value['headerFontSize'] as num) ||
        (value['expandedHorizontalInset'] as num) <
            (value['compactHorizontalInset'] as num)) {
      throw const FormatException('Invalid native pigment picker layout.');
    }
    double number(String key) => (value[key] as num).toDouble();
    return NativePigmentPickerLayout._(
      number('diameter'),
      number('compactHorizontalInset'),
      number('expandedHorizontalInset'),
      number('topInset'),
      number('headerGap'),
      number('sectionSpacing'),
      number('itemSpacing'),
      number('headerFontSize'),
      number('headerMaxFontSize'),
    );
  }
}
