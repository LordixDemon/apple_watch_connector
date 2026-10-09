"""Validate the transient original color row/header against source metrics."""
import math


def validate(capture, layout):
    row = layout['row']
    def near(actual, expected):
        return (type(actual) in (int, float) and math.isfinite(actual) and
                math.isclose(actual, expected, abs_tol=1e-8))
    if (not isinstance(capture, dict) or type(capture.get('collectionType')) is not int or
            capture['collectionType'] != 1 or type(capture.get('mode')) is not int or
            capture['mode'] != 10 or capture.get('cellLabels') != [] or
            capture.get('windowAttached') is not True or capture.get('tableStyle') != 2 or
            'pigmentClassOptionsDescription' not in capture or
            capture.get('pigmentClassOptionsDescription') is not None):
        raise ValueError('Invalid native color row capture')
    diameter = layout['expandedDiameter']
    padding = 2 * (layout['strip']['outlineOutset'] + layout['strip']['expandedOutlineWidth'])
    frame = capture.get('swatchFrame')
    if (not isinstance(frame, list) or len(frame) != 4 or
            not all(near(a, b) for a, b in zip(frame, [padding / 2, row['swatchVerticalInset'], diameter, diameter])) or
            not near(capture.get('rowHeight'), diameter + 2 * row['swatchVerticalInset'])):
        raise ValueError('Native color row geometry mismatch')
    labels = capture.get('headerLabels')
    if not isinstance(labels, list) or len(labels) != 2 or any(not isinstance(l, dict) for l in labels):
        raise ValueError('Invalid native header labels')
    by_text = {l.get('text'): l for l in labels}
    if set(by_text) != {'Color', 'Multicolor'}:
        raise ValueError('Native header text mismatch')
    for text, alignment in [('Color', 4), ('Multicolor', 2)]:
        label = by_text[text]
        f = label.get('frame')
        if (not near(label.get('pointSize'), row['headerFontSize']) or
                not near(label.get('lineHeight'), row['headerLineHeight']) or
                label.get('symbolicTraits') != 0 or label.get('alignment') != alignment or
                not isinstance(f, list) or len(f) != 4 or
                not near(f[1], row['headerTopInset'])):
            raise ValueError('Native header font or baseline mismatch')
        rgba = label.get('rgba')
        expected_color = [1, 1, 1, 1] if text == 'Color' else [235/255, 235/255, 245/255, .6]
        if (not isinstance(rgba, list) or len(rgba) != 4 or
                any(type(a) not in (int, float) or not math.isfinite(a) or
                    not math.isclose(a, b, abs_tol=1e-6) for a, b in zip(rgba, expected_color))):
            raise ValueError('Native header semantic color mismatch')
    inset = row['sectionHorizontalInset'] + row['expandedHorizontalInset']
    if (not near(by_text['Color']['frame'][0], inset) or
            not near(sum(by_text['Multicolor']['frame'][::2]), 393 - inset) or
            not near(capture.get('headerHeight'), row['headerTopInset'] + math.ceil(row['headerLineHeight'] * 3) / 3)):
        raise ValueError('Native header placement mismatch')
    cell = capture.get('tableCellInTable')
    content = capture.get('contentInset')
    if (not isinstance(cell, list) or len(cell) != 4 or
            not near(cell[0], row['sectionHorizontalInset']) or
            not near(cell[2], 393 - 2 * row['sectionHorizontalInset']) or
            not isinstance(content, list) or len(content) != 4 or
            not all(near(a, b) for a, b in zip(content, [0, row['expandedHorizontalInset'] - padding / 2, 0,
                                                        row['expandedHorizontalInset'] - padding / 2]))):
        raise ValueError('Native table row insets mismatch')
