#!/usr/bin/env python3
"""Synthetic inert NSKeyedArchiver fixtures; no Watch/provider/user payloads."""
import plistlib
from pathlib import Path

DESTINATION = Path(__file__).resolve().parents[1] / 'apple-watch-companion/test/fixtures/native_app_intent'


def archive(parameters, *, route='fixture.launch', unknown='preserve', mutable=False):
    objects = ['$null']
    def add(value):
        objects.append(value)
        return plistlib.UID(len(objects) - 1)
    intent_class = add({'$classname': 'INAppIntent', '$classes': ['INAppIntent', 'INIntent', 'NSObject']})
    dictionary_class = add({'$classname': 'NSMutableDictionary' if mutable else 'NSDictionary',
                            '$classes': ['NSMutableDictionary', 'NSDictionary', 'NSObject'] if mutable else ['NSDictionary', 'NSObject']})
    keys = [add(k) for k in parameters]
    values = [add(v) for v in parameters.values()]
    dictionary = add({'NS.keys': keys, 'NS.objects': values, '$class': dictionary_class})
    root = add({'$class': intent_class, 'launchId': add(route),
                'appIntentIdentifier': add('FixtureConfigurationIntent'),
                'extensionBundleId': plistlib.UID(0), 'linkAction': plistlib.UID(0),
                'serializedParameters': dictionary, 'future': add(unknown)})
    return plistlib.dumps({'$archiver': 'NSKeyedArchiver', '$version': 100000,
                          '$top': {'root': root}, '$objects': objects}, fmt=plistlib.FMT_BINARY, sort_keys=False)


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    rows = {}
    for period in ['week', 'month']:
        for category in ['walk', 'run']:
            rows[f'{period}-{category}'] = archive({'period': period, 'category': category})
    rows['reordered-incumbent'] = archive({'category': 'walk', 'period': 'week'}, mutable=True)
    rows['different-routing'] = archive({'period': 'month', 'category': 'walk'}, route='other.launch')
    rows['unknown-metadata'] = archive({'period': 'month', 'category': 'walk'}, unknown='different')
    rows['non-string'] = archive({'period': 'week', 'category': 7})
    rows['duplicate-key'] = archive({'period': 'week', 'category': 'walk'})
    # Malformed dictionary using the same key reference twice.
    duplicate = plistlib.loads(rows['duplicate-key'])
    table = duplicate['$objects']
    root = table[duplicate['$top']['root'].data]
    dictionary = table[root['serializedParameters'].data]
    dictionary['NS.keys'][1] = dictionary['NS.keys'][0]
    rows['duplicate-key'] = plistlib.dumps(duplicate, fmt=plistlib.FMT_BINARY, sort_keys=False)
    for name, data in rows.items():
        (DESTINATION / (name + '.bplist')).write_bytes(data)
    print(f'Generated {len(rows)} synthetic AppIntent archives / {sum(len(v) for v in rows.values())} bytes')


if __name__ == '__main__':
    main()
