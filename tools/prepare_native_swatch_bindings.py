#!/usr/bin/env python3
"""Infer native UIImage compositor roles from independent option captures.

No artwork is synthesized. Inputs contain original UIImage source bytes and
draw calls captured inside the original swatch provider. At least three actual
color contexts must prove that source geometry/order and option identity agree.
The output instructs the simulator probe to render native black/white bases.
"""
import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from PIL import Image
import io


def rgba(value):
    if (not isinstance(value, list) or len(value) != 4 or
            any(type(n) not in (int, float) or not math.isfinite(n) or
                not -2 <= n <= 2 for n in value) or not 0 <= value[3] <= 1):
        raise ValueError('Unsupported native compositor tint')
    return tuple(value)


def infer(directories, axis='color'):
    if not 3 <= len(directories) <= 32:
        raise ValueError('Three independent native contexts are required')
    captures = []
    for directory in map(Path, directories):
        data = (directory / 'capture.json').read_bytes()
        if len(data) > 2 * 1024 * 1024:
            raise ValueError('Native swatch capture exceeded')
        capture = json.loads(data)
        custom, rows = capture.get('customization'), capture.get('rows')
        if (not isinstance(capture.get('family'), str) or
                not isinstance(custom, dict) or not isinstance(custom.get(axis), str) or
                not isinstance(rows, list) or not 1 <= len(rows) <= 512):
            raise ValueError('Invalid native swatch capture')
        capture['_directory'] = directory
        captures.append(capture)
    first = captures[0]
    without_axis = lambda custom: {k: v for k, v in custom.items() if k != axis}
    if (len({c['customization'][axis] for c in captures}) < 3 or
            any(c['family'] != first['family'] or
                without_axis(c['customization']) != without_axis(first['customization']) or
                len(c['rows']) != len(first['rows']) for c in captures)):
        raise ValueError('Native contexts do not share a complete style identity')
    colors = {}
    geometry = {}
    option_identities = set()
    for index, original in enumerate(first['rows']):
        custom = original.get('customization')
        if not isinstance(custom, dict):
            raise ValueError('Missing complete native option identity')
        identity = (original.get('mode'), json.dumps(without_axis(custom), sort_keys=True))
        if identity in option_identities:
            raise ValueError('Duplicate native option identity')
        option_identities.add(identity)
        shape = {k: original.get(k) for k in ('mode', 'label', 'optionClass', 'size', 'scale')}
        calls = original.get('drawOperations')
        if not isinstance(calls, list) or not 1 <= len(calls) <= 128:
            raise ValueError('Missing original compositor calls')
        for context_index, capture in enumerate(captures):
            row = capture['rows'][index]
            if ({k: row.get(k) for k in shape} != shape or
                    not isinstance(row.get('customization'), dict) or
                    without_axis(row['customization']) != without_axis(custom) or
                    row['customization'].get(axis) != capture['customization'][axis] or
                    len(row.get('drawOperations', [])) != len(calls)):
                raise ValueError('Native option context or topology differs')
            for call_index, call in enumerate(row['drawOperations']):
                reference = calls[call_index]
                if ({k: v for k, v in call.items() if k != 'tint'} !=
                        {k: v for k, v in reference.items() if k != 'tint'} or
                        (call.get('tint') is None) != (reference.get('tint') is None)):
                    raise ValueError('Native draw order or geometry differs')
                leaf = call.get('image')
                if not isinstance(leaf, str) or not re.fullmatch(r'[a-f0-9]{64}\.png', leaf):
                    raise ValueError('Invalid native source identity')
                resource = (capture['_directory'] / leaf).read_bytes()
                if len(resource) > 4 * 1024 * 1024 or hashlib.sha256(resource).hexdigest() + '.png' != leaf:
                    raise ValueError('Original source bytes differ from their identity')
                size, scale = call.get('imageSize'), call.get('imageScale')
                if (not isinstance(size, list) or len(size) != 2 or
                        any(type(n) not in (int, float) or not math.isfinite(n) or not 0 < n <= 1024 for n in size) or
                        type(scale) not in (int, float) or not math.isfinite(scale) or not 0 < scale <= 4):
                    raise ValueError('Invalid native image size or scale')
                with Image.open(io.BytesIO(resource)) as image:
                    if (image.format != 'PNG' or any(n > 1024 for n in image.size) or
                            any(abs(points * scale - pixels) > 1e-5 for points, pixels in zip(size, image.size))):
                        raise ValueError('Native image pixels do not match source geometry')
                if (call.get('orientation') != 0 or call.get('operation') not in ('rect', 'point') or
                        not isinstance(call.get('geometry'), list) or
                        len(call['geometry']) != (4 if call['operation'] == 'rect' else 2) or
                        any(type(n) not in (int, float) or not math.isfinite(n)
                            for n in call['geometry'])):
                    raise ValueError('Unsupported native draw operation')
                if call.get('tint') is None:
                    continue
                color = rgba(call['tint'])
                values = colors.setdefault(leaf, [None] * len(captures))
                if values[context_index] is not None and values[context_index] != color:
                    raise ValueError('One source is tinted with different roles')
                values[context_index] = color
                source_geometry = (call.get('imageSize'), call.get('imageScale'))
                if leaf in geometry and geometry[leaf] != source_geometry:
                    raise ValueError('Native source scale or size differs')
                geometry[leaf] = source_geometry
    if not colors or any(None in values for values in colors.values()):
        raise ValueError('Incomplete native color contexts')
    groups = {}
    for leaf, values in colors.items():
        groups.setdefault(tuple(values), []).append(leaf)
    if not 1 <= len(groups) <= 4:
        raise ValueError('Unsupported native role count')
    sources = {}
    for leaves in groups.values():
        role = 'g_' + hashlib.sha256('\n'.join(sorted(leaves)).encode()).hexdigest()[:16]
        for leaf in leaves:
            sources[leaf] = dict(role=role, rgba=list(colors[leaf][0]))
    bases = [dict(family=first['family'], whiteRole=role, sources=sources)
             for role in ['black', *sorted({v['role'] for v in sources.values()})]]
    proof = dict(version=1, family=first['family'], axis=axis,
                 contexts=[c['customization'] for c in captures], sources=sources,
                 rows=first['rows'])
    return bases, proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('captures', nargs='+', type=Path)
    parser.add_argument('--axis', default='color')
    args = parser.parse_args()
    bases, proof = infer(args.captures, args.axis)
    args.output.mkdir(parents=True, exist_ok=False)
    for basis in bases:
        (args.output / (basis['whiteRole'] + '.json')).write_text(json.dumps(basis, indent=2) + '\n')
    (args.output / 'proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(dict(roles=len(bases) - 1, sources=len(proof['sources']), rows=len(proof['rows']))))


if __name__ == '__main__':
    main()
