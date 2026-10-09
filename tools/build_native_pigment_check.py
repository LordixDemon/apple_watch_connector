#!/usr/bin/env python3
"""Validate a native UIKit check palette basis; preserve its two original PNGs."""
import argparse
import io
import json
import math
from pathlib import Path
from PIL import Image
from build_native_pigments import color, native_image


def build(capture, directory):
    if (not isinstance(capture, dict) or type(capture.get('version')) is not int or capture['version'] != 1 or
            capture.get('appearance') != 'dark' or
            capture.get('cellClass') not in ('NTKPigmentCheckCell', '_NTKPigmentAddCell') or
            not isinstance(capture.get('rows'), list) or not 5 <= len(capture['rows']) <= 512):
        raise ValueError('Invalid native check capture')
    rows, payloads, seen = [], {}, set()
    geometry = None
    for row in capture['rows']:
        metrics = [row.get(k) for k in ('width', 'height', 'scale')]
        if (any(type(v) not in (int, float) or not math.isfinite(v) for v in metrics) or
                not 12 <= metrics[0] == metrics[1] <= 96 or not 1 <= metrics[2] <= 4 or
                type(row.get('viewAppearance')) is not int or row['viewAppearance'] != 2 or
                not (row.get('selectedVisible') is True or
                     type(row.get('selectedVisible')) is int and row['selectedVisible'] == 1) or
                color(row.get('tint')) != [0, 0, 0, 1]):
            raise ValueError('Native check geometry or appearance mismatch')
        if geometry is not None and metrics != geometry:
            raise ValueError('Native check geometry changed')
        geometry = metrics
        primary = row.get('primary')
        if primary is not None:
            color(primary)
            if primary[3] != 1:
                raise ValueError('Native check reference must have opaque primary')
        key = json.dumps(primary)
        if key in seen:
            raise ValueError('Duplicate native check reference')
        seen.add(key)
        leaf, data = native_image(directory, row.get('image'))
        with Image.open(io.BytesIO(data)) as image:
            if image.size != (round(metrics[0] * metrics[2]), round(metrics[1] * metrics[2])):
                raise ValueError('Native check PNG geometry mismatch')
            raw = image.convert('RGBA').tobytes()
            pixels = [tuple(raw[i:i + 4]) for i in range(0, len(raw), 4)]
        payloads[leaf] = data
        rows.append((primary, leaf, pixels))
    template = [r for r in rows if r[0] is None]
    basis = [r for r in rows if r[0] is not None and all(abs(c - 1) < 1e-6 for c in r[0])]
    if len(template) != 1 or not basis or any(r[2] != basis[0][2] for r in basis):
        raise ValueError('Native check template or white basis missing')
    white = basis[0][2]
    if (not any(p[0] == 255 and p[3] == 255 for p in white) or
            any(p[0] != p[1] or p[0] != p[2] for p in white) or
            any(any(p[:3]) for p in template[0][2])):
        raise ValueError('Native check palette is not a dark grayscale basis')
    # Source: [primary, BPSBackgroundColor] symbol palette. In the dark appearance
    # its fixed role is black; modulating the white basis preserves that role.
    for primary, _, pixels in rows:
        if primary is None:
            continue
        expected = [tuple(math.floor(p[i] * max(0, min(primary[i], 1)) + .5) for i in range(3)) + (p[3],)
                    for p in white]
        if any(a[3] != b[3] or max(abs(a[i] - b[i]) for i in range(3)) > 1
               for a, b in zip(pixels, expected)):
            raise ValueError('Native check palette reconstruction differs')
    selected = {r[1]: payloads[r[1]] for r in (template[0], basis[0])}
    return {'version': 1, 'appearance': 'dark', 'primaryTransform': 'clampSRGB',
            'width': geometry[0], 'height': geometry[1],
            'paletteImage': basis[0][1], 'templateImage': template[0][1]}, selected


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('images', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    metadata, images = build(json.loads(args.capture.read_text()), args.images)
    args.output.mkdir(exist_ok=False)
    (args.output / 'check.json').write_text(json.dumps(metadata, separators=(',', ':')) + '\n')
    for leaf, data in images.items():
        (args.output / leaf).write_bytes(data)
    print(json.dumps({'nativeImages': len(images), 'bytes': sum(map(len, images.values())), 'geometry': metadata}))
