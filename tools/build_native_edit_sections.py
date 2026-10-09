#!/usr/bin/env python3
"""Validate native section identities against shipped options; emit model metadata.

Input is probe_native_edit_sections.m JSON, never guessed family-specific layouts.
Slotted and multi-field sections remain research until their schemas are modeled.
"""
import argparse
import json
from pathlib import Path


def build(rows, profiles):
    by_family = {profile['family']: profile for profile in profiles}
    result = {}
    for row in rows:
        family = row['family']
        if family not in by_family:
            continue
        if family in result:
            raise ValueError('Duplicate native family')
        sections, order = [], []
        for section in row['sections']:
            if section['slot'] is not None:
                continue
            fields = {key for option in section['options']
                      for key, value in option['customization'].items()
                      if value != row['baseline'].get(key)}
            if len(fields) != 1:
                continue
            field = fields.pop()
            values = [option['customization'].get(field) for option in section['options']]
            if any(not isinstance(value, str) for value in values):
                continue
            known = [option['value'] for option in by_family[family]['options'].get(field, [])]
            if len(values) != len(set(values)) or not set(values).issubset(known):
                raise ValueError('Native section options do not match profile')
            mode, kind = section['mode'], section['collectionType']
            if type(mode) is not int or not 0 <= mode <= 255 or type(kind) is not int or not 0 <= kind <= 5:
                raise ValueError('Invalid native section identity')
            if field in order:
                raise ValueError('Duplicate native field section')
            order.append(field)
            # Pigment and dynamic controllers need their own native model, not
            # a generic inline selector. Preserve their existing UI meanwhile.
            if kind not in (0, 2, 3):
                continue
            if any(prior['field'] == field for prior in sections):
                raise ValueError('Duplicate native field section')
            sections.append({'field': field, 'mode': mode, 'collectionType': kind, 'values': values})
        result[family] = {'order': order, 'sections': sections}
    return {'version': 1, 'source': 'watchOS 26.2 / 23S303 native editOptionsForCustomEditModes',
            'families': result}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('probe', type=Path)
    parser.add_argument('profiles', type=Path)
    args = parser.parse_args()
    print(json.dumps(build(json.loads(args.probe.read_text()),
                           json.loads(args.profiles.read_text())['templates']),
                     ensure_ascii=False, indent=2))
