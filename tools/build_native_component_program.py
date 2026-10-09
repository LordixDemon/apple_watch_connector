#!/usr/bin/env python3
"""Pack native component bases and validate against independent native snapshots.

NFCP v1: fixed 24-byte header, zlib uint16 indices, zlib RGBA+role RGB
tables. All bytes are premultiplied. No raster assets are synthesized or expanded
into a PNG per shade. Promotion is permitted only after every reference passes.
"""
import argparse
import hashlib
import json
import struct
import zlib
from pathlib import Path
import numpy as np
from PIL import Image
from prepare_native_component_bindings import flatten, equal_color
from build_native_face_previews import key


def premultiplied(path):
    a = np.array(Image.open(path).convert('RGBA')).astype(np.float64)
    a[..., :3] = np.rint(a[..., :3] * a[..., 3:4] / 255)
    return a.astype(np.uint8)


def pack(bases, roles):
    black = bases['black'].astype(np.int16)
    if len({a.shape for a in bases.values()}) != 1:
        raise ValueError('Native basis geometry differs')
    height, width, _ = black.shape
    if not (0 < width <= 1024 and 0 < height <= 1024 and width * height <= 512 * 1024
            and 1 <= len(roles) <= 4):
        raise ValueError('Native component bounds exceeded')
    deltas = []
    for role in roles:
        if not np.array_equal(bases[role][..., 3], black[..., 3]):
            raise ValueError('Native role changed alpha')
        delta = bases[role][..., :3].astype(np.int16) - black[..., :3]
        if delta.min() < 0 or delta.max() > 255:
            raise ValueError('Native component has nonlinear occlusion')
        deltas.append(delta)
    if np.any(black[..., :3] + sum(deltas) > black[..., 3:4]):
        raise ValueError('Native component cannot preserve premultiplied alpha')
    rows = np.concatenate([black, *deltas], axis=2).astype(np.uint8)
    table, indices = np.unique(rows.reshape(-1, rows.shape[2]), axis=0, return_inverse=True)
    if not 1 <= len(table) <= 65536:
        raise ValueError('Native component table exceeded')
    plane = zlib.compress(indices.astype('<u2').tobytes(), 9)
    colors = zlib.compress(table.tobytes(), 9)
    header = struct.pack('<4sBBHHHIII', b'NFCP', 1, len(roles), 0, width, height,
                         len(table), len(plane), len(colors))
    encoded = header + plane + colors
    if len(encoded) > 1024 * 1024:
        raise ValueError('Native component encoded size exceeded')
    return encoded, rows, len(table)


def evaluate(rows, roles, colors):
    out = rows[..., :4].astype(np.float64)
    for index, role in enumerate(roles):
        out[..., :3] += rows[..., 4 + index * 3:7 + index * 3] * colors[role][:3]
    # Dart double.round compares the fraction itself. Adding .5 first can round
    # a value just below a half to an exact integer before floor sees it.
    lower = np.floor(out[..., :3])
    rounded = lower + (out[..., :3] - lower >= .5)
    out[..., :3] = np.minimum(rounded, out[..., 3:4])
    return out.astype(np.uint8)


def build(case, basis_cache=None):
    cache = basis_cache if basis_cache is not None else {}
    identity = (case['basisInput'], case['basisCaptures'])
    if identity not in cache:
        inputs = json.loads(Path(case['basisInput']).read_text())
        roles = sorted(r['whiteRole'] for r in inputs if r['whiteRole'] != 'black')
        bases = {r['whiteRole']: premultiplied(Path(case['basisCaptures']) / (r['key'] + '.png'))
                 for r in inputs}
        encoded, rows, classes = pack(bases, roles)
        cache[identity] = inputs, roles, encoded, rows, classes
    inputs, roles, encoded, rows, classes = cache[identity]
    groups = {}
    for binding in inputs[0]['componentBindings']:
        groups.setdefault(binding.get('group', binding['role']), []).append(binding)
    if set(groups) != set(roles) or any(len({b['role'] for b in g}) != 1 for g in groups.values()):
        raise ValueError('Ambiguous native component role grouping')
    getters = {role: groups[role][0]['role'] for role in roles}
    base = inputs[0]
    token = case.get('base', base['configuration']['customization']['color'].split(':')[0])
    if not isinstance(token, str) or not token or ':' in token:
        raise ValueError('Invalid native component base')
    samples = []
    for group in case['references']:
        directory = Path(group['captures'])
        for row in json.loads(Path(group['input']).read_text()):
            custom = row['configuration']['customization']
            if (custom['detail'] != case['detail'] or custom['color'].split(':')[0] != token):
                continue
            tree = json.loads((directory / (row['key'] + '-layers.json')).read_text())
            layers = dict(flatten(tree['layer']))
            palette = next(node['palette'] for node, _ in layers.values() if node.get('palette'))
            # Require the same actual source-property→palette-role mapping for
            # every independent native reference, including held-out shades.
            component_colors = {}
            for binding in inputs[0]['componentBindings']:
                node, hidden = layers[binding['path']]
                if hidden or node['class'] != binding['class']:
                    raise ValueError('Native role topology differs')
                if binding['property'] == 'attributedText':
                    text = node['attributedText']
                    run = next(r for r in text['runs'] if [r['start'], r['length']] == binding['range'])
                    value = run['colors'][binding['attribute']]
                else:
                    value = node[binding['property']]
                group = binding.get('group', binding['role'])
                if group in component_colors and not equal_color(value, component_colors[group]):
                    raise ValueError('Native component group diverged')
                component_colors[group] = value
            actual = premultiplied(directory / (row['key'] + '.png'))
            sample_token = row['configuration']['customization']['color']
            percent = 50 if ':' not in sample_token else round(float(sample_token.rsplit(':', 1)[1]) * 100)
            samples.append((percent, row, palette, actual, component_colors))
    if not {0, 25, 50, 75, 100} <= {s[0] for s in samples}:
        raise ValueError(f'Native reference shades incomplete: {token}/{case["detail"]}')
    stops = [next(s[2] for s in samples if s[0] == v) for v in (0, 50, 100)]
    report = []
    overrides = {}
    fixed = rows[..., 4:].max(2) == 0
    for percent, row, palette, actual, component_colors in samples:
        index = 0 if percent <= 50 else 1
        t = percent / 50 if index == 0 else (percent - 50) / 50
        # Match NativeFacePigment.colorAt's operation order, including half-byte
        # rounding boundaries; algebraically equivalent blends can differ in IEEE754.
        colors = {role: np.array(stops[index][getters[role]])
                        + (np.array(stops[index + 1][getters[role]])
                           - np.array(stops[index][getters[role]])) * t for role in roles}
        for role in roles:
            if not equal_color(list(colors[role]), palette[getters[role]]):
                raise ValueError('Native palette is not piecewise linear')
        if any(not equal_color(list(colors[role]), component_colors[role]) for role in roles):
            # NTKFaceColorScheme has a distinct pure-white foreground branch.
            # Capture its actual component colors, never blend this discontinuity.
            if not equal_color(palette['primaryColor'], [1, 1, 1, 1]):
                raise ValueError('Unproven native color scheme branch')
            overrides[str(percent)] = [component_colors[role] for role in roles]
            colors = component_colors
        reconstructed = evaluate(rows, roles, colors)
        if actual.shape != reconstructed.shape:
            raise ValueError('Native reference dimensions differ')
        delta = np.abs(actual.astype(np.int16) - reconstructed.astype(np.int16))
        maximum, mean = int(delta.max()), float(delta[..., :3].mean())
        fixed_maximum = int(delta[fixed].max()) if fixed.any() else 0
        if maximum > 2 or delta[..., 3].max() != 0 or fixed_maximum != 0:
            raise ValueError(f'Native reference differs: {percent}, max={maximum}, mean={mean}')
        report.append(dict(percent=percent, maximumPremultipliedDelta=maximum,
                           meanPremultipliedRgbDelta=mean,
                           fixedPixelDelta=fixed_maximum,
                           reconstructedRgbaSha256=hashlib.sha256(reconstructed.tobytes()).hexdigest(),
                           pixelsAboveOne=int(np.count_nonzero(delta[..., :3].max(2) > 1))))
    custom = base['configuration']['customization']
    digest = hashlib.sha256(encoded).hexdigest()
    entry = dict(field='color', base=token, asset=digest + '.nfc', roles=roles,
                 stops=[[stop[getters[role]] for stop in stops] for role in roles])
    if overrides:
        entry['percentColors'] = overrides
    return key(base['family'], dict(custom, color=token)), entry, encoded, dict(
        detail=case['detail'], base=token, bindings=len(inputs[0]['componentBindings']),
        classes=classes, bytes=len(encoded), paletteGetters=getters,
        percentColors=overrides, references=report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cases', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    cache = {}
    results = [build(case, cache) for case in json.loads(args.cases.read_text())]
    args.output.mkdir(parents=True, exist_ok=True)
    entries, reports = {}, []
    for identity, entry, encoded, report in results:
        if identity in entries:
            raise ValueError('Duplicate native component identity')
        (args.output / entry['asset']).write_bytes(encoded)
        entries[identity] = entry
        reports.append(report)
    (args.output / 'catalog.json').write_text(json.dumps(
        dict(version=1, entries=entries), separators=(',', ':')) + '\n')
    (args.output / 'validation.json').write_text(json.dumps(reports, indent=2) + '\n')
    programs = {r[1]['asset']: r[2] for r in results}
    print(json.dumps(dict(entries=len(entries), programs=len(programs), bytes=sum(len(r) for r in programs.values()),
                          references=sum(len(r[3]['references']) for r in results))))


if __name__ == '__main__':
    main()
