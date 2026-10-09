#!/usr/bin/env python3
"""Validate original option swatches, then pack reusable native component bases.

The original provider renders every reference and every basis. No full-image
PNG per shade is promoted. Complete customization identities select programs;
unproven contexts cannot resolve to another style's default image.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import numpy as np
from build_native_component_program import premultiplied, pack, evaluate
from build_native_face_previews import key
from prepare_native_swatch_bindings import rgba


def tint_colors(row, proof, reference):
    custom = row['customization']
    axis = proof['axis']
    without_axis = lambda value: {k: v for k, v in value.items() if k != axis}
    if (without_axis(custom) != without_axis(reference['customization']) or
            any(row[k] != reference[k] for k in ('mode', 'label', 'optionClass', 'size', 'scale')) or
            len(row['drawOperations']) != len(reference['drawOperations'])):
        raise ValueError('Native option identity or draw topology differs')
    colors = {}
    for call, expected in zip(row['drawOperations'], reference['drawOperations']):
        if ({k: v for k, v in call.items() if k != 'tint'} !=
                {k: v for k, v in expected.items() if k != 'tint'} or
                (call['tint'] is None) != (expected['tint'] is None)):
            raise ValueError('Native draw geometry or source differs')
        if call['tint'] is None:
            continue
        value = rgba(call['tint'])
        # Preserve the source extended-sRGB palette. Its channels are clamped
        # only when evaluating the original 8-bit UIImage compositor's colors.
        value = np.array(value)
        role = proof['sources'][call['image']]['role']
        if role in colors and not np.allclose(colors[role], value, rtol=0, atol=1e-5):
            raise ValueError('Native sources no longer share one color role')
        colors[role] = value
    return colors


def interpolate(stops, percent):
    index = 0 if percent <= 50 else 1
    t = percent / 50 if index == 0 else (percent - 50) / 50
    return stops[index] + (stops[index + 1] - stops[index]) * t


def build(proof_path, basis_root, references, geometry, field, basis_topologies=None):
    proof = json.loads(Path(proof_path).read_text())
    if proof.get('version') != 1 or proof.get('axis') != 'color':
        raise ValueError('Unsupported native swatch proof')
    roles = sorted({v['role'] for v in proof['sources'].values()})
    geometry = json.loads(Path(geometry).read_text())
    if (geometry.get('windowAttached') is not True or geometry['family'] != proof['family'] or
            not math.isfinite(geometry['rowHeight']) or not 44 <= geometry['rowHeight'] <= 512):
        raise ValueError('Missing attached native row geometry')
    root = Path(references)
    index = json.loads((root / 'index.json').read_text())
    samples = []
    native_cache = {}
    for item in index:
        leaf = item['directory']
        if not leaf.startswith('context-') or not leaf[8:].isdigit():
            raise ValueError('Invalid native reference path')
        directory = root / leaf
        capture = json.loads((directory / 'capture.json').read_text())
        if capture['family'] != proof['family'] or len(capture['rows']) != len(proof['rows']):
            raise ValueError('Native reference identity differs')
        primary = np.array(rgba(capture['palettePrimaryColor']))
        white = capture['paletteIsWhiteColor']
        slider = capture['supportsSlider']
        if type(slider) is not bool:
            raise ValueError('Missing native slider capability')
        if type(white) is not bool or white and not np.allclose(primary, [1, 1, 1, 1], rtol=0, atol=1e-5):
            raise ValueError('Invalid native white-color branch')
        token = item['color']
        if ':' in token:
            base, suffix = token.rsplit(':', 1)
            fraction = float(suffix)
            percent = round(fraction * 100)
            if token != base + ':' + format(percent / 100, '.2f') or not 0 <= percent <= 100:
                raise ValueError('Noncanonical native reference fraction')
        else:
            base, percent = token, 50
        actual_token = capture['customization']['color']
        if actual_token not in (token, base):
            raise ValueError('Native reference serialized a different identity')
        for ordinal, row in enumerate(capture['rows']):
            if row['customization']['color'] != actual_token:
                raise ValueError('Native row has a different color context')
            identity = key(proof['family'], row['customization'])
            image_digest = hashlib.sha256((directory / row['image']).read_bytes()).hexdigest()
            cached = not row['drawOperations']
            if cached:
                previous = native_cache.get(identity)
                shape = {k: row[k] for k in ('mode', 'label', 'optionClass', 'size', 'scale')}
                if previous is None or previous[0] != image_digest or previous[1] != shape:
                    raise ValueError('Uninspected native image has no exact captured cache identity')
                colors = previous[2]
                if not np.array_equal(previous[3], primary) or previous[4] != white:
                    raise ValueError('Cached native palette differs')
            else:
                colors = tint_colors(row, proof, proof['rows'][ordinal])
                native_cache[identity] = (image_digest,
                    {k: row[k] for k in ('mode', 'label', 'optionClass', 'size', 'scale')}, colors, primary, white)
            if set(colors) != set(roles):
                raise ValueError('Native role context is incomplete')
            samples.append((base, percent, ordinal, row, directory, colors, primary, white, actual_token, cached, slider))
    entries, programs, reports, basis_cache = {}, {}, [], {}
    for ordinal, reference in enumerate(proof['rows']):
        ordinary = [s for s in samples if s[2] == ordinal and not s[7]]
        white_samples = [s for s in samples if s[2] == ordinal and s[7]]
        if not ordinary or not white_samples:
            raise ValueError('Native scheme branch contexts are incomplete')
        # Source inspection proves the provider substitutes systemOrangeColor
        # only for a white palette's foreground. Independent actual calls must
        # identify that role across all ordinary and white color contexts.
        foreground_roles = {r for r in roles
                            if all(np.allclose(s[5][r][:3], s[6][:3], rtol=0, atol=1e-5) for s in ordinary)
                            and all(not np.allclose(s[5][r][:3], s[6][:3], rtol=0, atol=1e-5) for s in white_samples)}
        if len(foreground_roles) != 1:
            raise ValueError('Native foreground white branch is ambiguous')
        for base in sorted({s[0] for s in samples}):
            selected = [s for s in samples if s[0] == base and s[2] == ordinal]
            selected_basis = (basis_topologies or {}).get(base, basis_root)
            expected_alpha = {r: selected[0][5][r][3] for r in roles}
            basis_key = str(selected_basis), ordinal
            if basis_key not in basis_cache:
                bases, alphas = {}, []
                for role in ['black', *roles]:
                    folder = Path(str(selected_basis) + '-' + role)
                    basis = json.loads((folder / 'capture.json').read_text())
                    if basis['family'] != proof['family']:
                        raise ValueError('Native basis family differs')
                    basis_colors = tint_colors(basis['rows'][ordinal], proof, reference)
                    alphas.append({r: basis_colors[r][3] for r in roles})
                    bases[role] = premultiplied(folder / basis['rows'][ordinal]['image'])
                basis_cache[basis_key] = (*pack(bases, roles), alphas)
            encoded, pixels, classes, alphas = basis_cache[basis_key]
            if any(abs(alpha[r] - expected_alpha[r]) > 1e-5 for alpha in alphas for r in roles):
                raise ValueError('Native basis alpha topology differs')
            digest = hashlib.sha256(encoded).hexdigest()
            programs[digest + '.nfc'] = encoded
            fixed = pixels[..., 4:].max(2) == 0
            shades = selected[0][10]
            if any(s[10] != shades for s in selected):
                raise ValueError('Native slider capability differs across references')
            if not ({0, 25, 50, 75, 100} if shades else {50}) <= {s[1] for s in selected}:
                raise ValueError('Native color reference stops are incomplete')
            if not shades and any(s[1] != 50 for s in selected):
                raise ValueError('An unsupported native slider was sampled')
            stops = {role: np.array([next(s[5][role] for s in selected if s[1] == v)
                                     for v in ((0, 50, 100) if shades else (50, 50, 50))]) for role in roles}
            if not shades and any(not np.array_equal(s[5][r], selected[0][5][r])
                                  for s in selected for r in roles):
                raise ValueError('Nonserializable native shades changed their pixels')
            overrides, validations = {}, []
            if shades and any(not s[7] for s in selected):
                for endpoint, stop_index in ((0, 0), (100, 2)):
                    sample = next(s for s in selected if s[1] == endpoint)
                    if sample[7]:
                        for role in foreground_roles:
                            stops[role][stop_index] = sample[6]
                        overrides[endpoint] = [sample[5][r].tolist() for r in roles]
            for _, percent, _, row, directory, actual_colors, primary, white, actual_token, cached, slider in selected:
                if any(abs(actual_colors[r][3] - expected_alpha[r]) > 1e-5 for r in roles):
                    raise ValueError('Native reference changed alpha topology')
                colors = {role: interpolate(stops[role], percent) for role in roles}
                if percent in overrides:
                    colors = dict(zip(roles, map(np.array, overrides[percent])))
                if any(not np.allclose(colors[r], actual_colors[r], rtol=0, atol=1e-5) for r in roles):
                    raise ValueError(f'Native scheme is not piecewise linear: {base}/{percent}')
                reconstructed = evaluate(pixels, roles, {r: np.clip(v, 0, 1) for r, v in colors.items()})
                actual = premultiplied(directory / row['image'])
                if actual.shape != reconstructed.shape:
                    raise ValueError('Native reference size differs')
                delta = np.abs(actual.astype(np.int16) - reconstructed.astype(np.int16))
                fixed_maximum = int(delta[fixed].max()) if fixed.any() else 0
                maximum = int(delta.max())
                if maximum > 2 or delta[..., 3].max() != 0 or fixed_maximum != 0:
                    raise ValueError(f'Native reference differs: {base}/{percent}, max={maximum}')
                validations.append(dict(percent=percent, maximumPremultipliedDelta=maximum,
                                        fixedPixelDelta=fixed_maximum,
                                        maximumAlphaDelta=int(delta[..., 3].max()),
                                        meanPremultipliedRgbDelta=float(delta[..., :3].mean()),
                                        nativeCacheHit=cached))
            identity = key(proof['family'], dict(reference['customization'], color=base))
            if identity in entries or not isinstance(reference['customization'].get(field), str):
                raise ValueError('Ambiguous native option identity')
            entries[identity] = dict(field='color', base=base, asset=digest + '.nfc', roles=roles,
                                     stops=[stops[r].tolist() for r in roles], mode=reference['mode'],
                                     optionField=field, option=reference['customization'][field], supportsShades=shades)
            if overrides:
                entries[identity]['percentColors'] = overrides
            reports.append(dict(base=base, option=reference['customization'][field], bytes=len(encoded),
                                classes=classes, references=validations))
    return dict(version=1, entries=entries), programs, reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('proof', type=Path)
    parser.add_argument('basis_root', type=Path)
    parser.add_argument('references', type=Path)
    parser.add_argument('geometry', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--field', required=True)
    parser.add_argument('--basis-topologies', type=Path)
    args = parser.parse_args()
    topologies = json.loads(args.basis_topologies.read_text()) if args.basis_topologies else None
    manifest, programs, reports = build(args.proof, args.basis_root, args.references, args.geometry, args.field, topologies)
    args.output.mkdir(parents=True, exist_ok=False)
    for name, encoded in programs.items():
        (args.output / name).write_bytes(encoded)
    (args.output / 'catalog.json').write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
    (args.output / 'validation.json').write_text(json.dumps(reports, indent=2) + '\n')
    print(json.dumps(dict(entries=len(manifest['entries']), programs=len(programs),
                          bytes=sum(map(len, programs.values())),
                          references=sum(len(r['references']) for r in reports))))


if __name__ == '__main__':
    main()
