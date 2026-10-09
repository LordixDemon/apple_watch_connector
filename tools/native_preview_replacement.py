"""Explicit, source-pinned Memoji migration; ordinary preview merges stay strict."""
import json
from pathlib import Path
from build_native_face_previews import customization, key, merge_entries

FAMILY = 'bundle:com.apple.NTKCollieFaceBundle'
POLICY = 'Watch7,5-23S303-Collie-imageWithName'


def validate_replacement(manifest, existing, rows, source_root):
    if (not isinstance(manifest, dict) or set(manifest) !=
            {'version', 'family', 'maskPolicy', 'sourceRoot', 'entries'} or
            type(manifest['version']) is not int or manifest['version'] != 1 or manifest['family'] != FAMILY or
            manifest['maskPolicy'] != POLICY or manifest['sourceRoot'] != source_root):
        raise ValueError('Invalid or stale native mask replacement policy')
    expected = manifest['entries']
    if (not isinstance(expected, dict) or not expected or len(expected) > 10000 or
            set(expected) != {r['key'] for r in rows} or len(expected) != len(rows) or
            any(existing.get(k) != v for k, v in expected.items())):
        raise ValueError('Native mask replacement old references or coverage differ')
    for row in rows:
        config = row['configuration']
        if (row['family'] != FAMILY or config.get('face type') != 'bundle' or
                config.get('bundle id') != 'com.apple.NTKCollieFaceBundle' or
                config.get('version') != 4 or 'customData' in config or
                'resource directory' in config or 'complications' in config or
                row['key'] != key(FAMILY, customization(config))):
            raise ValueError('Replacement is not the verified resource-free Memoji appearance')
    return dict(expected)


def check_reports(directory):
    manifest = directory / 'requests-pages.json'
    names = ([p['file'] for p in json.loads(manifest.read_text())['pages']]
             if manifest.exists() else ['requests.json'])
    for name in names:
        report = json.loads((directory / 'png' /
            (Path(name).stem + '-report.json')).read_text())
        if report.get('maskPolicy') != POLICY or report.get('rejected') != 0:
            raise ValueError('Replacement requires complete device-mask native rendering')


def merge_replacement(existing, incoming, expected):
    if (set(incoming) != set(expected) or
            any(existing.get(k) != v for k, v in expected.items())):
        raise ValueError('Native mask replacement coverage or preconditions changed')
    # Remove only explicitly proven old references, then reuse the ordinary
    # merge validator. All unrelated keys and assets retain their identities.
    return merge_entries({k: v for k, v in existing.items() if k not in expected}, incoming)
