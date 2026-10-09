#!/usr/bin/env python3
"""Infer component color roles from multiple native, read-only layer captures.

Outputs research inputs for --components, not a Watch library or runtime manifest.
Never infers a role from a single color sample or assumes a family's geometry.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path

ROLES = ('primaryColor', 'secondaryColor', 'primaryShiftedColor',
         'secondaryShiftedColor')
PROPERTIES = ('backgroundColor', 'contentsMultiplyColor')


def flatten(node, hidden=False):
    hidden = hidden or node.get('hidden', False) or node.get('opacity', 1) == 0
    yield node['path'], (node, hidden)
    for child in node.get('children', []):
        yield from flatten(child, hidden)


def equal_color(a, b):
    return (isinstance(a, list) and isinstance(b, list) and len(a) == len(b) == 4
            and all(abs(x - y) <= 1e-5 for x, y in zip(a, b)))


def infer_bindings(trees):
    if not 3 <= len(trees) <= 32:
        raise ValueError('Require 3..32 independent shade captures')
    nodes = [dict(flatten(tree['layer'])) for tree in trees]
    if any(tree.get('limitReached') for tree in trees):
        raise ValueError('Truncated native hierarchy')
    if any(set(rows) != set(nodes[0]) for rows in nodes):
        raise ValueError('Native component topology changed')
    palettes = []
    for rows in nodes:
        values = [row.get('palette') for row, _ in rows.values() if row.get('palette')]
        if len(values) != 1 or any(role not in values[0] for role in ROLES):
            raise ValueError('Require one complete native palette')
        palettes.append(values[0])

    def role_for(values):
        roles = [role for role in ROLES if all(equal_color(value, palette[role])
                 for value, palette in zip(values, palettes))]
        if len(roles) == 1 and any(not equal_color(values[0], v) for v in values[1:]):
            return roles[0]
        return None

    bindings = []
    geometry = ('class', 'bounds', 'position', 'anchorPoint', 'transform', 'opacity',
                'hidden', 'contentsScale', 'contentsGravity', 'masksToBounds',
                'contentsImage', 'shapePath')
    for path, (first, hidden) in nodes[0].items():
        samples = [rows[path][0] for rows in nodes]
        if any(rows[path][1] != hidden for rows in nodes):
            raise ValueError(f'Visibility changed: {path}')
        if any(any(sample.get(k) != first.get(k) for k in geometry) for sample in samples):
            raise ValueError(f'Native geometry/contents changed: {path}')
        if hidden:
            continue
        for prop in PROPERTIES:
            role = role_for([sample.get(prop) for sample in samples])
            if role:
                bindings.append(dict(path=path, **{'class': first['class']},
                                     property=prop, role=role))
        attributed = first.get('attributedText')
        if not attributed:
            continue
        for run in attributed['runs']:
            colors = []
            for sample in samples:
                text = sample.get('attributedText', {})
                match = [r for r in text.get('runs', [])
                         if (r['start'], r['length']) == (run['start'], run['length'])]
                if text.get('text') != attributed['text'] or len(match) != 1:
                    raise ValueError(f'Native text layout changed: {path}')
                colors.append(match[0]['colors'].get('NSColor'))
            role = role_for(colors)
            if role:
                bindings.append(dict(path=path, **{'class': first['class']},
                                     property='attributedText', role=role,
                                     range=[run['start'], run['length']],
                                     attribute='NSColor', text=attributed['text']))
    if not 1 <= len(bindings) <= 128:
        raise ValueError('No bounded component bindings')
    return bindings, palettes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('captures', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--split-components', action='store_true',
                        help='Separate native properties/delegates sharing a palette role')
    args = parser.parse_args()
    rows = json.loads(args.input.read_text())
    trees = [json.loads((args.captures / (row['key'] + '-layers.json')).read_text())
             for row in rows]
    bindings, palettes = infer_bindings(trees)
    if args.split_components:
        nodes = dict(flatten(trees[len(trees) // 2]['layer']))
        for binding in bindings:
            node, _ = nodes[binding['path']]
            signature = [binding['role'], binding['property'], node['class'],
                         node.get('view', {}).get('class')]
            binding['group'] = 'g_' + hashlib.sha256(json.dumps(signature,
                separators=(',', ':')).encode()).hexdigest()[:16]
    roles = sorted(set(b.get('group', b['role']) for b in bindings))
    if len(roles) > 4:
        raise ValueError('Native component role count exceeded')
    base = rows[len(rows) // 2]
    inputs = []
    for role in ['black', *roles]:
        row = copy.deepcopy(base)
        row['componentBindings'] = bindings
        row['whiteRole'] = role
        row['key'] = hashlib.sha256(json.dumps(row, sort_keys=True,
                                   separators=(',', ':')).encode()).hexdigest()
        inputs.append(row)
    args.output.write_text(json.dumps(inputs, separators=(',', ':')))
    print(json.dumps(dict(bindings=len(bindings), roles=roles,
                          samples=len(trees), basisKeys={r['whiteRole']: r['key']
                                                      for r in inputs})))


if __name__ == '__main__':
    main()
