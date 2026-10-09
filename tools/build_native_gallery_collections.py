#!/usr/bin/env python3
"""Export factory order/customization, without simulator-owned complication intents."""
import argparse
import hashlib
import json
from pathlib import Path


def family(recipe):
    kind = recipe.get('face type')
    if not isinstance(kind, str) or not kind or len(kind) > 128:
        raise ValueError('Invalid native family')
    if kind == 'bundle':
        bundle = recipe.get('bundle id')
        if not isinstance(bundle, str) or not bundle.startswith('com.apple.'):
            raise ValueError('Invalid native bundle')
        return 'bundle:' + bundle
    return 'type:' + kind


def customization(value, depth=0):
    if depth > 8:
        raise ValueError('Unbounded native customization')
    if isinstance(value, str) and len(value) <= 256:
        return value
    if isinstance(value, dict) and len(value) <= 64:
        if any(not isinstance(k, str) or not 0 < len(k) <= 128 for k in value):
            raise ValueError('Invalid native customization key')
        return {k: customization(v, depth + 1) for k, v in value.items()}
    raise ValueError('Unsupported native customization')


def build(capture, profiles):
    if capture.get('schema') != 1 or capture.get('runtime') != 'watchOS26.2/23S303':
        raise ValueError('Unverified gallery source')
    rows = capture.get('collections')
    if not isinstance(rows, list) or len(rows) != 53 or sum(len(r['faces']) for r in rows) != 223:
        raise ValueError('Incomplete gallery factory capture')
    available = {r['family'] for r in profiles['templates']}
    sections, missing = [], {}
    for row in rows:
        title = row['title']
        if not isinstance(title, str) or not title or len(title) > 128:
            raise ValueError('Invalid native gallery title')
        variants = []
        for recipe in row['faces']:
            key = family(recipe)
            if key not in available:
                missing[key] = missing.get(key, 0) + 1
                continue
            # Only appearance is portable here. Do not ship simulator intent UUIDs,
            # resource paths, histories, metrics, donor identity or compiled descriptors.
            values = customization(recipe.get('customization', {}))
            variants.append({'family': key, 'customization': values})
        if variants:
            sections.append({'titles': {'en': title}, 'variants': variants})
    return {'version': 1, 'source': 'watchOS26.2/23S303', 'factorySections': 53,
            'factoryVariants': 223, 'unresolvedFamilies': missing, 'sections': sections}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--profiles', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    raw = args.capture.read_bytes()
    result = build(json.loads(raw), json.loads(args.profiles.read_text()))
    result['sourceSha256'] = hashlib.sha256(raw).hexdigest()
    args.output.write_text(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n')
    print(json.dumps({'sections': len(result['sections']),
                      'variants': sum(len(r['variants']) for r in result['sections']),
                      'unresolvedFamilies': result['unresolvedFamilies']}))
