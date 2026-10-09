"""Request only missing native gallery appearances, never raw simulator recipes."""
import argparse
import json
from pathlib import Path
from build_native_gallery_collections import customization, family
from build_native_face_previews import key, write_requests
from native_preview_catalog import read_catalog


def prepare(metadata, profiles, existing):
    available = {p['family']: p for p in profiles['templates']}
    requests = {}
    for section in metadata['sections']:
        for variant in section['variants']:
            if set(variant) != {'family', 'customization'}:
                raise ValueError('Non-appearance gallery recipe')
            profile = available[variant['family']]
            base = profile['configuration']
            if family(base) != variant['family'] or profile.get('requiresResources'):
                raise ValueError('Unsafe native prototype')
            values = customization(variant['customization'])
            digest = key(variant['family'], values)
            if digest in existing:
                continue
            # Snapshot decoder receives the verified native family and appearance
            # only. It rejects normalization and checks cleared native slots.
            config = {k: base[k] for k in ('face type', 'version', 'bundle id') if k in base}
            config['customization'] = values
            requests[digest] = {'key': digest, 'family': variant['family'], 'configuration': config}
    return list(requests.values())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--assets', type=Path, required=True)
    args = parser.parse_args()
    target = args.directory.resolve()
    if not str(target).startswith(('/tmp/', '/private/tmp/')):
        parser.error('Use a temporary native renderer directory')
    assets = args.assets
    _, existing = read_catalog(assets)
    rows = prepare(json.loads((assets / 'gallery_collections_26_2.json').read_text()),
                   json.loads((assets / 'watchos_26_2.json').read_text()), existing)
    write_requests(target, rows)
    print(json.dumps({'missingUniqueAppearances': len(rows)}))
