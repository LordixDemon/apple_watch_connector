#!/usr/bin/env python3
"""Publish only exhaustively verified native layouts with proven JSON slot keys."""
import argparse
import json
import math
from pathlib import Path


def build(document, profiles, monograms=None):
    by_family = {row['family']: row for row in profiles}
    monogram_rows = {}
    for row in (monograms or {}).get('faces', []):
        family = row['family']
        if family in monogram_rows:
            raise ValueError('Duplicate native monogram evidence')
        enabled = row['enabled']
        on = row['enabledConfiguration'].get('complications', {})
        off = row['disabledConfiguration'].get('complications', {})
        changed = set(on) - set(off)
        if (enabled != {'app': 'monogram'} or row['disabled'] is not None
                or len(changed) != 1):
            raise ValueError('Unverified native monogram serialization')
        slot = changed.pop()
        if on[slot] != enabled or {k: v for k, v in on.items() if k != slot} != off:
            raise ValueError('Monogram changes unrelated native complications')
        roots = [{k: v for k, v in row[name].items() if k != 'complications'}
                 for name in ('configuration', 'enabledConfiguration', 'disabledConfiguration')]
        if roots[0] != roots[1] or roots[1] != roots[2]:
            raise ValueError('Monogram changes unrelated native configuration')
        monogram_rows[family] = {'nativeSlot': row['slot'], 'slot': slot,
                                 'enabled': enabled}
    families = {}
    for row in document['rows']:
        family = row['family']
        if family not in by_family:
            continue
        if family in families or row['normalized'] or row['checked'] < 1:
            raise ValueError('Unverified or duplicate native layout')
        profile = by_family[family]
        order, keys = row['order'], row['wireKeys']
        if (len(order) != len(set(order)) or set(keys) != set(order)
                or len(set(keys.values())) != len(keys)
                or set(keys.values()) != set(profile['slotFamilies'])):
            raise ValueError('Native slot identities do not match profile')
        def slots(values):
            if len(values) != len(set(values)) or not set(values).issubset(keys):
                raise ValueError('Invalid native slot reference')
            return [keys[value] for value in values]
        domains, rules = row['domains'], row['rules']
        if row['checked'] != math.prod(len(values) for values in domains.values()):
            raise ValueError('Native layout cross product is incomplete')
        if set(domains) != set(profile['options']) or set(rules) != set(domains):
            raise ValueError('Native customization fields do not match profile')
        compressed = {}
        for field, values in domains.items():
            expected = [value['value'] for value in profile['options'][field]]
            baseline = profile['configuration'].get('customization', {}).get(field)
            if isinstance(baseline, str) and baseline not in expected:
                expected.append(baseline)
            if (len(values) != len(set(values)) or set(values) != set(expected)
                    or set(rules[field]) != set(values)):
                raise ValueError('Native customization values do not match profile')
            converted = {value: slots(rules[field][value]) for value in values}
            if any(converted.values()):
                compressed[field] = converted
        labels = {}
        for slot, label in row['labels'].items():
            if slot not in keys or not isinstance(label, str) or len(label) > 256:
                raise ValueError('Invalid native label')
            # An untranslated native resource key is not a user-visible title.
            if label and label != slot and '_' not in label:
                labels[keys[slot]] = {'en': label}
        fixed = slots(row['fixed'])
        monogram = row['monogram']
        if monogram is not None:
            fixed += slots([monogram])
        families[family] = {'order': slots(order), 'excluded': list(dict.fromkeys(fixed)),
                            'labels': labels, 'domains': domains, 'rules': compressed,
                            'constant': slots(row['constant'])}
        if monogram is not None and monograms is not None:
            evidence = monogram_rows.get(family)
            if (evidence is None or evidence['nativeSlot'] != monogram
                    or evidence['slot'] != keys[monogram]):
                raise ValueError('Native monogram slot has no serialization proof')
            families[family]['monogram'] = {'slot': evidence['slot'],
                                             'enabled': evidence['enabled']}
    return {'version': 1, 'source': 'watchOS 26.2 / 23S303 native model; exhaustive customization cross product',
            'families': families}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('probe', type=Path)
    parser.add_argument('profiles', type=Path)
    parser.add_argument('--monograms', type=Path)
    args = parser.parse_args()
    print(json.dumps(build(json.loads(args.probe.read_text()),
                           json.loads(args.profiles.read_text())['templates'],
                           json.loads(args.monograms.read_text()) if args.monograms else None),
                     ensure_ascii=False, separators=(',', ':')))
