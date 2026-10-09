#!/usr/bin/env python3
"""Validate native pigment data, deduplicate small swatches, emit bounded metadata.

No full-face rendering, device commands or family-specific color tables. Shade
support requires exact native serialization for all 101 integer percentages and
native palette stops matching the sampled swatch colors.
"""
import argparse
import copy
import hashlib
import io
import json
import math
import struct
from pathlib import Path
from PIL import Image


def color(value):
    if (not isinstance(value, list) or len(value) != 4 or
            any(type(v) not in (int, float) or not math.isfinite(v) for v in value) or
            any(not -2 <= v <= 2 for v in value[:3]) or not 0 <= value[3] <= 1):
        raise ValueError('Invalid native extended-sRGB color')
    return value


def native_image(directory, leaf, *, square=True):
    if not isinstance(leaf, str) or Path(leaf).name != leaf:
        raise ValueError('Invalid native swatch path')
    data = (directory / leaf).read_bytes()
    dimensions = struct.unpack('>II', data[16:24]) if len(data) >= 24 else (0, 0)
    if (len(data) > 256 * 1024 or data[:16] != b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' or
            (square and dimensions[0] != dimensions[1]) or
            any(not 1 <= n <= 144 for n in dimensions)):
        raise ValueError('Invalid native swatch image')
    # Decode before publication, including truncated/corrupt PNG payloads.
    try:
        with Image.open(io.BytesIO(data)) as image:
            image.verify()
    except (OSError, SyntaxError) as error:
        raise ValueError('Invalid native swatch image payload') from error
    return hashlib.sha256(data).hexdigest() + '.png', data


def native_layout(value):
    keys = {'version', 'compactDiameter', 'expandedDiameter', 'expandedAbovePhysicalPixels'}
    version = value.get('version') if isinstance(value, dict) else None
    if type(version) is int and version in (2, 3, 4):
        keys.add('picker')
    if type(version) is int and version in (3, 4):
        keys.add('strip')
    if type(version) is int and version == 4:
        keys.add('row')
    if (not isinstance(value, dict) or set(value) != keys or
            type(value['version']) is not int or value['version'] not in (1, 2, 3, 4) or
            any(type(value[k]) not in (int, float) or not math.isfinite(value[k])
                for k in keys - {'version', 'picker', 'strip', 'row'}) or
            not 12 <= value['compactDiameter'] <= value['expandedDiameter'] <= 96 or
            not 100 <= value['expandedAbovePhysicalPixels'] <= 8192):
        raise ValueError('Invalid native pigment layout')
    if version in (2, 3, 4):
        picker = value['picker']
        picker_keys = {'diameter', 'compactHorizontalInset', 'expandedHorizontalInset',
                       'topInset', 'headerGap', 'sectionSpacing', 'itemSpacing',
                       'headerFontSize', 'headerMaxFontSize'}
        if (not isinstance(picker, dict) or set(picker) != picker_keys or
                any(type(picker[k]) not in (int, float) or not math.isfinite(picker[k]) or
                    not 0 <= picker[k] <= 96 for k in picker_keys) or
                picker['diameter'] < 12 or picker['headerFontSize'] < 12 or
                picker['headerMaxFontSize'] < picker['headerFontSize'] or
                picker['expandedHorizontalInset'] < picker['compactHorizontalInset']):
            raise ValueError('Invalid native pigment picker layout')
    if version in (3, 4):
        strip = value['strip']
        numbers = {'compactOutlineWidth', 'expandedOutlineWidth', 'outlineOutset',
                   'swatchSpacing', 'plusWidth', 'plusHeight', 'dividerWidth', 'dividerHeight'}
        images = {'plusImage', 'dividerImage'}
        import re
        if (not isinstance(strip, dict) or set(strip) != numbers | images or
                any(type(strip[k]) not in (int, float) or not math.isfinite(strip[k]) or
                    not 0 < strip[k] <= 96 for k in numbers) or
                strip['expandedOutlineWidth'] < strip['compactOutlineWidth'] or
                2 * (strip['outlineOutset'] + strip['expandedOutlineWidth']) >= strip['swatchSpacing'] or
                any(not isinstance(strip[k], str) or not re.fullmatch(r'[a-f0-9]{64}\.png', strip[k]) for k in images)):
            raise ValueError('Invalid native pigment strip layout')
    if version == 4:
        row = value['row']
        row_keys = {'sectionHorizontalInset', 'swatchVerticalInset', 'compactHorizontalInset', 'expandedHorizontalInset',
                    'headerTopInset', 'headerLabelGap', 'headerFontSize', 'headerMaxFontSize',
                    'headerLineHeight'}
        if (not isinstance(row, dict) or set(row) != row_keys or
                any(type(row[k]) not in (int, float) or not math.isfinite(row[k]) or
                    not 0 < row[k] <= 96 for k in row_keys) or
                row['expandedHorizontalInset'] < row['compactHorizontalInset'] or
                row['headerFontSize'] < 12 or row['headerMaxFontSize'] < row['headerFontSize'] or
                row['headerLineHeight'] < row['headerFontSize'] or
                row['swatchVerticalInset'] < strip['outlineOutset'] + strip['expandedOutlineWidth'] or
                row['compactHorizontalInset'] < strip['outlineOutset'] + strip['expandedOutlineWidth']):
            raise ValueError('Invalid native pigment row layout')
    return dict(value)


def native_slider_support(metadata, original_rows, capture):
    """Classify from supportsSlider independently of faceView edit permission.

    Exact native customizations must match the original palette capture. This
    changes section grouping only, never pigment colors, serialization or images.
    """
    if not isinstance(capture, list) or not 1 <= len(capture) <= 256:
        raise ValueError('Invalid native slider support capture')
    original = {r['family']: r for r in original_rows}
    result, seen = copy.deepcopy(metadata), set()
    for row in capture:
        family = row.get('family') if isinstance(row, dict) else None
        if family not in result['families'] or family in seen or family not in original:
            raise ValueError('Unknown or duplicate native slider family')
        seen.add(family)
        sections = result['families'][family]
        raw_sections = row.get('sections')
        if not isinstance(raw_sections, list) or any(
                not isinstance(s, dict) or type(s.get('mode')) is not int for s in raw_sections):
            raise ValueError('Invalid native slider sections')
        source = [s for s in raw_sections if s.get('mode') == 10]
        previous = [s for s in original[family]['sections'] if s['mode'] == 10]
        if (len(sections) != 1 or len(source) != 1 or len(previous) != 1 or
                row.get('baseline') != original[family]['baseline']):
            raise ValueError('Ambiguous native slider section')
        section = sections[0]; field = section['field']
        expected = {json.dumps(o['customization'], sort_keys=True): o for o in previous[0]['options']}
        options = source[0].get('options')
        if not isinstance(options, list) or len(options) != len(expected) or not 1 <= len(options) <= 512:
            raise ValueError('Incomplete native slider options')
        observed, editable = set(), set()
        for option in options:
            if (not isinstance(option, dict) or option.get('supportsSliderDeclared') is not True or
                    type(option.get('supportsSlider')) is not bool or type(option.get('viewAllowsSlider')) is not bool or
                    option.get('pigmentClass') != 'NTKPigmentEditOption' or
                    not isinstance(option.get('optionClass'), str)):
                raise ValueError('Native slider declaration unavailable')
            key = json.dumps(option.get('customization'), sort_keys=True)
            if key not in expected or key in observed:
                raise ValueError('Native slider customization mismatch')
            observed.add(key)
            if expected[key]['slider'] != (option['supportsSlider'] and option['viewAllowsSlider']):
                raise ValueError('Native slider permission context changed')
            if option['supportsSlider']:
                editable.add(option['customization'][field])
        section['editable'] = [token for token, _ in section['values'] if token in editable]
    if seen != result['families'].keys():
        raise ValueError('Native slider families incomplete')
    return result


def build(rows, profiles, image_directory):
    known = {p['family']: p for p in profiles}
    families, pigments, lookup, images = {}, [], {}, {}
    for row in rows:
        family = row['family']
        if family not in known or not row['pigmentUI']:
            continue
        if family in families:
            raise ValueError('Duplicate native family')
        if (type(row.get('pigmentEditOption')) is not bool or
                (row['pigmentEditOption'] and not row.get('paletteClass'))):
            raise ValueError('Native pigment controller palette unavailable')
        sections = []
        for section in row['sections']:
            if section['mode'] != 10:
                continue
            options = section['options']
            fields = {k for o in options for k, v in o['customization'].items()
                      if v != row['baseline'].get(k)}
            if len(fields) != 1 or not 1 <= len(options) <= 512:
                raise ValueError('Ambiguous native pigment field')
            field = fields.pop()
            available = {o['value'] for o in known[family]['options'].get(field, [])}
            entries, seen = [], set()
            for option in options:
                token = option['customization'].get(field)
                if token not in available or token in seen:
                    raise ValueError('Native pigment does not match profile')
                seen.add(token)
                pigment = {'rgba': color(option['rgba']) if option['rgba'] is not None else None}
                if option.get('image'):
                    leaf, data = native_image(image_directory, option['image'])
                    images[leaf] = data
                    pigment['image'] = leaf
                if pigment['rgba'] is None and 'image' not in pigment:
                    raise ValueError('Native pigment has no usable swatch')
                if option['slider']:
                    base = option['fullname']
                    samples = option['samples']
                    expected = [base if p == 50 else f'{base}:{p / 100:.2f}' for p in range(101)]
                    if (not isinstance(base, str) or not base or ':' in base or
                            len(option['percentTokens']) != 101 or
                            [o.get(field) for o in option['percentTokens']] != expected or
                            any({k: v for k, v in o.items() if k != field} !=
                                {k: v for k, v in option['customization'].items() if k != field}
                                for o in option['percentTokens'])):
                        raise ValueError('Native fraction serialization mismatch')
                    stops = option['stops']
                    # Some legacy views expose no primary palette. Preserve their
                    # native color selection, without inventing a shade gradient.
                    if len(stops) == 3 and all(s is not None for s in stops):
                        stops = [color(s) for s in stops]
                        if [s['fraction'] for s in samples] != [0, .25, .5, .75, 1]:
                            raise ValueError('Native shade samples missing')
                        for sample in samples:
                            rgba = color(sample['rgba'])
                            fraction = sample['fraction']
                            a, b = (stops[0], stops[1]) if fraction <= .5 else (stops[1], stops[2])
                            t = fraction * 2 if fraction <= .5 else fraction * 2 - 1
                            if max(abs(a[i] * (1 - t) + b[i] * t - rgba[i]) for i in range(4)) > 1e-5:
                                raise ValueError('Native shade gradient mismatch')
                        fraction = option['fraction']
                        percent = round(fraction * 100)
                        if abs(percent / 100 - fraction) > 1e-8 or token != expected[percent]:
                            raise ValueError('Invalid native default fraction')
                        pigment.update(base=base, percent=percent, stops=stops)
                        if 'image' in pigment:
                            # Native slider swatches must be monochrome alpha
                            # masks before the client can recolor their shape.
                            # Allow only the PNG's two-step edge quantization.
                            with Image.open(io.BytesIO(data)) as image:
                                rgba_bytes = image.convert('RGBA').tobytes()
                            pixels = [tuple(rgba_bytes[i:i + 4]) for i in range(0, len(rgba_bytes), 4)]
                            opaque = {p[:3] for p in pixels if p[3] == 255}
                            if len(opaque) != 1:
                                raise ValueError('Native shade swatch is not monochrome')
                            reference = next(iter(opaque))
                            if any(max(abs(p[i] - reference[i]) for i in range(3)) > 2
                                   for p in pixels if p[3] >= 128):
                                raise ValueError('Native shade swatch is not monochrome')
                            pigment['shadeImageMask'] = True
                serialized = json.dumps(pigment, sort_keys=True, separators=(',', ':'))
                index = lookup.get(serialized)
                if index is None:
                    index = len(pigments)
                    lookup[serialized] = index
                    pigments.append(pigment)
                entries.append([token, index])
            # Original configuration separates by supportsSlider, independently
            # of isAddable/isVisible. Preserve that native flag even when a
            # legacy palette exposes no usable shade gradient.
            sections.append({'field': field, 'mode': 10, 'values': entries,
                'editable': [o['customization'][field] for o in options if o['slider']]})
        if sections:
            families[family] = sections
    if len(pigments) > 2048 or len(images) > 2048 or len(families) > 256:
        raise ValueError('Native pigment bounds exceeded')
    metadata = {'version': 1, 'source': 'watchOS 26.2 / 23S303 controller-backed native faceView swatches; '
                'iOS 23G71 pigment controller', 'colorSpace': 'extendedSRGB',
                'pigments': pigments, 'families': families}
    if len(json.dumps(metadata)) > 1024 * 1024:
        raise ValueError('Native pigment metadata too large')
    return metadata, images


def companion_complex_swatches(metadata, images, watch_rows, companion_rows, directory):
    """Prefer captured iPhone artwork only for proven matching complex options.

    A shared Watch thumbnail may belong to unrelated families. Append/deduplicate
    the new pigment record and change only a matched family's option reference.
    Never overwrite a shared record, infer a token or tint companion artwork.
    """
    if not isinstance(companion_rows, list) or not 1 <= len(companion_rows) <= 256:
        raise ValueError('Invalid companion pigment capture')
    originals = {}
    for row in watch_rows:
        if row['family'] not in metadata['families']:
            continue
        for section in row['sections']:
            if section['mode'] != 10:
                continue
            for option in section['options']:
                key = (row['family'], section['mode'],
                       json.dumps(option['customization'], sort_keys=True))
                if key in originals:
                    raise ValueError('Duplicate native option capture')
                originals[key] = option
    result, payloads = copy.deepcopy(metadata), dict(images)
    lookup = {json.dumps(p, sort_keys=True): i for i, p in enumerate(result['pigments'])}
    seen, seen_options, applied = set(), set(), 0
    for row in companion_rows:
        family = row['family']
        if family in seen:
            raise ValueError('Duplicate companion family')
        seen.add(family)
        if family not in result['families']:
            continue
        if (row.get('pigmentUI') is not True or
                type(row.get('pigmentEditOption')) is not bool or
                row['pigmentEditOption'] and not row.get('paletteClass')):
            raise ValueError('Companion controller palette unavailable')
        if not isinstance(row['sections'], list) or len(row['sections']) > 64:
            raise ValueError('Invalid companion pigment sections')
        for section in row['sections']:
            if not isinstance(section['options'], list) or len(section['options']) > 512:
                raise ValueError('Invalid companion pigment options')
            if section['mode'] != 10:
                continue
            for option in section['options']:
                key = (family, section['mode'],
                       json.dumps(option['customization'], sort_keys=True))
                original = originals.get(key)
                source_complex = (original is not None and original.get('rgba') is None
                                  and original.get('slider') is False)
                if not source_complex:
                    if option.get('rgba') is None and option.get('slider') is False:
                        raise ValueError('Companion complex option does not match native source')
                    continue
                if key in seen_options:
                    raise ValueError('Duplicate companion complex option')
                seen_options.add(key)
                if (type(original.get('multi')) is not bool or
                        type(option.get('multi')) is not bool or
                        option['multi'] != original['multi'] or
                        option.get('slider') is not False or option.get('rgba') is not None):
                    raise ValueError('Companion complex option does not match native source')
                targets = [(entry, index) for s in result['families'][family]
                           for entry, index in s['values']
                           if option['customization'].get(s['field']) == entry]
                if len(targets) != 1:
                    raise ValueError('Ambiguous companion complex option')
                token, index = targets[0]
                pigment = result['pigments'][index]
                if pigment.get('rgba') is not None or 'base' in pigment or 'image' not in pigment:
                    raise ValueError('Companion swatch cannot replace a shade palette')
                leaf, data = native_image(directory, option.get('image'))
                replacement = {**pigment, 'image': leaf}
                serialized = json.dumps(replacement, sort_keys=True)
                replacement_index = lookup.get(serialized)
                if replacement_index is None:
                    replacement_index = len(result['pigments'])
                    result['pigments'].append(replacement)
                    lookup[serialized] = replacement_index
                for s in result['families'][family]:
                    for entry in s['values']:
                        if entry == [token, index]:
                            entry[1] = replacement_index
                payloads[leaf] = data
                applied += 1
    if not applied or len(result['pigments']) > 2048 or len(payloads) > 2048:
        raise ValueError('Companion complex swatch coverage/bounds invalid')
    result['source'] += '; captured native iPhone companion complex swatches'
    if len(json.dumps(result)) > 1024 * 1024:
        raise ValueError('Native pigment metadata too large')
    return result, payloads


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('probe', type=Path)
    parser.add_argument('profiles', type=Path)
    parser.add_argument('swatches', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--companion-probe', type=Path)
    parser.add_argument('--companion-swatches', type=Path)
    parser.add_argument('--companion-layout', type=Path)
    parser.add_argument('--check-capture', type=Path)
    parser.add_argument('--check-images', type=Path)
    parser.add_argument('--strip-capture', type=Path)
    parser.add_argument('--strip-images', type=Path)
    parser.add_argument('--slider-support', type=Path)
    parser.add_argument('--row-capture', type=Path)
    args = parser.parse_args()
    if bool(args.companion_probe) != bool(args.companion_swatches):
        parser.error('Companion probe and swatch directory must be supplied together')
    if args.companion_layout and not args.companion_probe:
        parser.error('Companion layout requires a companion capture')
    if bool(args.check_capture) != bool(args.check_images):
        parser.error('Check capture and image directory must be supplied together')
    if bool(args.strip_capture) != bool(args.strip_images) or args.strip_capture and not args.companion_layout:
        parser.error('Strip capture requires its image directory and native layout')
    rows = json.loads(args.probe.read_text())
    metadata, images = build(rows,
                             json.loads(args.profiles.read_text())['templates'], args.swatches)
    if args.companion_probe:
        metadata, images = companion_complex_swatches(metadata, images, rows,
            json.loads(args.companion_probe.read_text()), args.companion_swatches)
    if args.companion_layout:
        metadata['swatchLayout'] = native_layout(json.loads(args.companion_layout.read_text()))
        if metadata['swatchLayout']['version'] >= 3 and not args.slider_support:
            raise ValueError('Separate native slider support capture required')
    if args.slider_support:
        metadata = native_slider_support(metadata, rows, json.loads(args.slider_support.read_text()))
    if metadata.get('swatchLayout', {}).get('version') == 4:
        if not args.row_capture:
            raise ValueError('Native color row capture required')
        from build_native_pigment_row import validate
        validate(json.loads(args.row_capture.read_text()), metadata['swatchLayout'])
    elif args.row_capture:
        raise ValueError('Native color row capture requires version 4 layout')
    if args.check_capture:
        from build_native_pigment_check import build as build_check
        metadata['checkArtwork'], check_images = build_check(
            json.loads(args.check_capture.read_text()), args.check_images)
        images.update(check_images)
    if args.strip_capture:
        from build_native_pigment_strip import build as build_strip
        artwork, payloads = build_strip(json.loads(args.strip_capture.read_text()), args.strip_images)
        strip = metadata['swatchLayout'].get('strip', {})
        if any(strip.get(k) != v for k, v in artwork.items()):
            raise ValueError('Native strip capture does not match source layout')
        images.update(payloads)
    elif metadata.get('swatchLayout', {}).get('version', 0) >= 3:
        raise ValueError('Native strip capture required')
    # Fresh staging only. Promotion into the application is an explicit step.
    args.output.mkdir(exist_ok=False)
    (args.output / 'pigments_26_2.json').write_text(json.dumps(metadata, ensure_ascii=False, separators=(',', ':')) + '\n')
    (args.output / 'pigment_swatches').mkdir()
    for leaf, data in images.items():
        (args.output / 'pigment_swatches' / leaf).write_bytes(data)
    print(json.dumps({'families': len(metadata['families']), 'pigments': len(metadata['pigments']),
                      'images': len(images), 'bytes': (args.output / 'pigments_26_2.json').stat().st_size}))
