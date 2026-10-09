#!/usr/bin/env python3
"""Cross-check native Add Colors metadata against the shipped native palette."""
import argparse
import json
from pathlib import Path


def text(value):
    if not isinstance(value, str) or not value or len(value) > 256:
        raise ValueError('Invalid native collection string')
    return value


def build(probe, palette):
    defaults = probe['defaults']
    if (not isinstance(defaults, list) or len(defaults) > 4096 or
            len(set(map(text, defaults))) != len(defaults)):
        raise ValueError('Invalid native automatic selections')
    colors, indexes, families, seen = [], {}, {}, set()
    for row in probe['families']:
        family = text(row['family'])
        if family in seen:
            raise ValueError('Duplicate native collection family')
        seen.add(family)
        sections = palette['families'].get(family)
        if not sections:
            continue
        if len(sections) != 1:
            raise ValueError('Ambiguous global color section')
        section = sections[0]
        field = section['field']
        known = {token for token, _ in section['values']}
        values, observed = [], set()
        for value in row['values']:
            token = text(value['customization'].get(field))
            if (token not in known or token in observed or
                    {k: v for k, v in value['customization'].items() if k != field} !=
                    {k: v for k, v in row['baseline'].items() if k != field}):
                raise ValueError('Native collection does not match palette')
            observed.add(token)
            name = text(value['fullname'])
            if ':' in name or type(value['addable']) is not bool:
                raise ValueError('Invalid native pigment identity')
            color = {'name': name, 'collection': text(value['collection']),
                     'titles': {'en': text(value['title'])},
                     'addable': value['addable'], 'automatic': name in defaults}
            key = json.dumps(color, sort_keys=True)
            index = indexes.get(key)
            if index is None:
                index = len(colors)
                indexes[key] = index
                colors.append(color)
            values.append([token, index])
        if observed != known:
            raise ValueError('Native collection is incomplete')
        default = text(row['defaultCustomization'].get(field))
        shaded = {p['base'] if percent == 50 else f"{p['base']}:{percent / 100:.2f}"
                  for _, index in section['values'] for p in [palette['pigments'][index]]
                  if 'base' in p for percent in range(101)}
        if default not in known and default not in shaded:
            raise ValueError('Unknown native default pigment')
        families[family] = {'field': field, 'default': default, 'values': values}
    if families.keys() != palette['families'].keys():
        raise ValueError('Native collection families are incomplete')
    if len(colors) > 2048 or len(families) > 256:
        raise ValueError('Native collection bounds exceeded')
    return {'version': 1, 'source': 'watchOS 26.2 / 23S303 native availableColorsForSlot; '
            'shared collections automatic selections; iOS 23G71 Add Colors controller',
            'colors': colors, 'families': families}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('probe', type=Path)
    parser.add_argument('palette', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = build(json.loads(args.probe.read_text()), json.loads(args.palette.read_text()))
    data = json.dumps(result, ensure_ascii=False, separators=(',', ':')) + '\n'
    if len(data.encode()) > 1024 * 1024:
        raise ValueError('Native collection metadata too large')
    with args.output.open('x') as output:
        output.write(data)
    print(json.dumps({'families': len(result['families']), 'colors': len(result['colors']),
                      'bytes': len(data.encode())}))
