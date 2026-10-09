#!/usr/bin/env python3
"""Prepare firmware-derived combinations; promote only verified native PNGs.

The renderer is run separately with simctl in a watchOS research simulator.
No physical Watch identity or user configuration is embedded in the catalog.
"""
import argparse
import hashlib
import itertools
import json
import re
import subprocess
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from native_preview_catalog import read_catalog, write_catalog

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'apple-watch-companion/assets/native_faces'


def read_entries(assets):
    manifest, entries = read_catalog(assets)
    for digest, asset in entries.items():
        if (not re.fullmatch('[a-f0-9]{64}', digest)
                or not isinstance(asset, str)
                or not re.fullmatch(r'assets/native_faces/configured_previews/[a-f0-9]{64}\.(webp|nfp)', asset)
                or (asset.endswith('.nfp') and manifest['version'] == 1)
                or not (assets / 'configured_previews' / Path(asset).name).is_file()):
            raise ValueError('Invalid or missing existing native preview')
        if asset.endswith('.nfp'):
            frame = (assets / 'configured_previews' / Path(asset).name).read_bytes()
            if (len(frame) <= 80 or len(frame) > 1024 * 1024
                    or frame[:8] != b'NFPT\x01\x02\x00\x00'
                    or hashlib.sha256(frame).hexdigest() != Path(asset).stem
                    or not (assets / 'configured_previews' /
                            (frame[16:48].hex() + '.nfpm')).is_file()):
                raise ValueError('Invalid or missing indexed native preview')
            plane = (assets / 'configured_previews' /
                     (frame[16:48].hex() + '.nfpm')).read_bytes()
            if (len(plane) <= 16 or len(plane) > 1024 * 1024
                    or hashlib.sha256(plane).digest() != frame[16:48]):
                raise ValueError('Invalid indexed native preview plane')
    return entries


def merge_entries(existing, incoming):
    result = dict(existing)
    for digest, asset in incoming.items():
        if digest in result and result[digest] != asset:
            raise ValueError('A verified existing preview would be replaced')
        result[digest] = asset
    prefixes = {}
    for digest in result:
        if not re.fullmatch('[a-f0-9]{64}', digest):
            raise ValueError('Invalid native configuration key')
        prefixes[digest[:2]] = prefixes.get(digest[:2], 0) + 1
    if any(count > 10000 for count in prefixes.values()):
        raise ValueError('Merged preview shard count exceeds the application budget')
    return result


def key(family, customization):
    raw = json.dumps({'family': family, 'customization': customization},
                     sort_keys=True, ensure_ascii=False, separators=(',', ':'))
    return hashlib.sha256(raw.encode()).hexdigest()


def customization(configuration):
    value = configuration.get('customization', {})
    if not isinstance(value, dict):
        raise ValueError('Malformed native customization')
    return value


def write_requests(directory, rows, page_size=0):
    """Publish immutable bounded pages last, preserving the legacy renderer input."""
    directory.mkdir(parents=True, exist_ok=True)
    if not page_size:
        data = json.dumps(rows, separators=(',', ':')).encode()
        if len(rows) > 10000 or len(data) > 16 * 1024 * 1024:
            raise ValueError('Native request batch exceeds the renderer budget')
        (directory / 'requests.json').write_bytes(data)
        (directory / 'requests-pages.json').unlink(missing_ok=True)
        return
    if not 1 <= page_size <= 10000 or len(rows) > page_size * 256:
        raise ValueError('Native request page budget exceeded')
    pages = []
    for offset in range(0, len(rows), page_size):
        page = rows[offset:offset + page_size]
        data = json.dumps(page, separators=(',', ':')).encode()
        if len(data) > 16 * 1024 * 1024:
            raise ValueError('Native request page exceeds the renderer byte budget')
        sha = hashlib.sha256(data).hexdigest()
        name = f'requests-{sha}.json'
        path = directory / name
        if path.exists() and path.read_bytes() != data:
            raise ValueError('Native request page collision')
        path.write_bytes(data)
        pages.append({'file': name, 'count': len(page), 'bytes': len(data), 'sha256': sha})
    manifest = {'version': 1, 'count': len(rows), 'pages': pages}
    staged = directory / 'requests-pages.staged.json'
    staged.write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
    staged.replace(directory / 'requests-pages.json')


def read_requests(directory):
    """Yield verified pages without loading the complete generation into memory."""
    manifest = directory / 'requests-pages.json'
    if not manifest.exists():
        data = (directory / 'requests.json').read_bytes()
        if len(data) > 16 * 1024 * 1024:
            raise ValueError('Native request batch exceeds the byte budget')
        pages = [(json.loads(data), None)]
    else:
        raw = manifest.read_bytes()
        if len(raw) > 128 * 1024:
            raise ValueError('Native request manifest exceeds the byte budget')
        root = json.loads(raw)
        if (not isinstance(root, dict) or root.get('version') != 1 or not isinstance(root.get('pages'), list)
                or len(root['pages']) > 256 or type(root.get('count')) is not int
                or not 0 <= root['count'] <= 256 * 10000):
            raise ValueError('Invalid native request manifest')
        def load_pages():
            total = 0
            for ref in root['pages']:
                if (not isinstance(ref, dict)
                        or not isinstance(ref.get('file'), str)
                        or not re.fullmatch(r'requests-[a-f0-9]{64}\.json', ref['file'])
                        or ref.get('sha256') != ref['file'][9:-5]
                        or type(ref.get('count')) is not int or not 1 <= ref['count'] <= 10000
                        or type(ref.get('bytes')) is not int or not 1 <= ref['bytes'] <= 16 * 1024 * 1024):
                    raise ValueError('Invalid native request page reference')
                data = (directory / ref['file']).read_bytes()
                if len(data) != ref['bytes'] or hashlib.sha256(data).hexdigest() != ref['sha256']:
                    raise ValueError('Native request page identity mismatch')
                total += ref['count']
                yield json.loads(data), ref['count']
            if total != root['count']:
                raise ValueError('Native request manifest count mismatch')
        pages = load_pages()
    seen = set()
    for rows, count in pages:
        if (not isinstance(rows, list) or len(rows) > 10000
                or count is not None and len(rows) != count):
            raise ValueError('Invalid native request page count')
        for row in rows:
            if (not isinstance(row, dict) or not isinstance(row.get('configuration'), dict)
                    or not isinstance(row.get('family'), str)
                    or row.get('key') != key(row['family'], customization(row['configuration']))
                    or row['key'] in seen):
                raise ValueError('Invalid or repeated native request identity')
            seen.add(row['key'])
            yield row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['prepare', 'promote'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--family', action='append', default=[])
    parser.add_argument('--all-families', action='store_true',
                        help='Enumerate every firmware profile, without a family allowlist.')
    parser.add_argument('--page-size', type=int, default=0,
                        help='Write immutable renderer pages (1–10,000 rows each, at most 256 pages).')
    parser.add_argument('--limit', type=int, default=5000)
    parser.add_argument('--merge', action='store_true',
                        help='Preserve the current catalog and render only missing keys.')
    parser.add_argument('--workers', type=int, default=4,
                        help='Independent native PNG verification/encoding jobs (1–8).')
    args = parser.parse_args()
    if not 1 <= args.workers <= 8:
        parser.error('Use between one and eight encoding workers.')
    if not 0 <= args.page_size <= 10000 or args.limit < 1:
        parser.error('Use a positive total budget and a page size between zero and 10,000.')
    directory = args.directory.resolve()
    if not str(directory).startswith('/private/tmp/') and not str(directory).startswith('/tmp/'):
        parser.error('Use an absolute temporary staging directory.')
    existing = read_entries(ASSETS) if args.merge else {}
    if args.phase == 'prepare':
        profiles = json.loads((ASSETS / 'watchos_26_2.json').read_text())['templates']
        names = {p['titles']['en'] for p in profiles}
        if set(args.family) - names:
            parser.error('Unknown native family title.')
        rows = {}
        for profile in profiles:
            base = profile['configuration']
            family = 'bundle:' + base['bundle id'] if 'bundle id' in base else 'type:' + base['face type']
            if family != profile['family']:
                raise ValueError('Native template family mismatch')
            customization(base)
            options = profile['options'] if args.all_families or profile['titles']['en'] in args.family else {}
            fields = list(options)
            values = [[v['value'] for v in options[f]] for f in fields]
            for combination in itertools.product(*values):
                config = json.loads(json.dumps(base))
                if fields:
                    config.setdefault('customization', {}).update(zip(fields, combination))
                digest = key(profile['family'], customization(config))
                if digest in existing:
                    continue
                rows[digest] = {'key': digest, 'family': profile['family'], 'configuration': config}
                if len(rows) > min(args.limit, args.page_size * 256 if args.page_size else 10000):
                    parser.error('Catalog exceeds the explicit generation budget.')
        merge_entries(existing, {digest: '' for digest in rows})
        write_requests(directory, list(rows.values()), args.page_size)
        print(f'Prepared {len(rows)} native configurations; no Watch data used.')
        return
    from PIL import Image
    requests = read_requests(directory)
    rendered = directory / 'png'
    destination = ASSETS / 'configured_previews'
    destination.mkdir(exist_ok=True)
    entries = {}
    sources = {}
    requested_count = 0
    for row in requests:
        requested_count += 1
        digest = row['key']
        if digest != key(row['family'], customization(row['configuration'])):
            raise ValueError('Mismatched configuration key')
        source = rendered / f'{digest}.png'
        if not source.exists():
            continue  # Native normalization or unavailable families are never substituted.
        image_key = hashlib.sha256(source.read_bytes()).hexdigest()
        sources.setdefault(image_key, source)
        entries[digest] = f'assets/native_faces/configured_previews/{image_key}.webp'
    def encode(item):
        image_key, source = item
        target = destination / f'{image_key}.webp'
        with Image.open(source) as original:
            original.load()
            if original.size != (422, 514):
                raise ValueError('Unexpected native preview dimensions')
            if not target.exists():
                subprocess.run(['cwebp', '-quiet', '-lossless', '-exact', '-m', '6',
                                str(source), '-o', str(target)], check=True)
            with Image.open(target) as converted:
                if converted.convert('RGBA').tobytes() != original.convert('RGBA').tobytes():
                    raise ValueError('Lossless preview verification failed')
        return image_key, target.stat().st_size
    # Never publish the catalog before every incoming native image is verified.
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        images = dict(pool.map(encode, sources.items()))
    promoted = len(entries)
    entries = merge_entries(existing, entries)
    write_catalog(ASSETS, entries)
    if not args.merge:
        for stale in destination.iterdir():
            if stale.suffix in ('.webp', '.nfp', '.nfpm') and stale.stem not in images:
                stale.unlink()
    print(f'Promoted {promoted}/{requested_count} exact native styles; catalog={len(entries)}; '
          f'{len(images)} incoming unique images, {sum(images.values())} incoming image bytes.')


if __name__ == '__main__':
    main()
