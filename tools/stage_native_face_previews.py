#!/usr/bin/env python3
"""Compress and verify native PNGs in staging; publish the catalog only last.

Each group compares exact indexed RGBA against fast lossless WebP. Existing
verified references/files are preserved. This tool never opens a Watch transport.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from build_native_face_previews import ASSETS, customization, merge_entries, read_entries, read_requests
from factor_native_face_previews import decode, decode_map_name, digest, pack_pixels
from native_preview_catalog import write_catalog
from native_preview_replacement import validate_replacement, check_reports, merge_replacement

PREFIX = 'assets/native_faces/configured_previews/'


def inputs(directory):
    rows = list(read_requests(directory))
    page_manifest = directory / 'requests-pages.json'
    names = ([p['file'] for p in json.loads(page_manifest.read_text())['pages']]
             if page_manifest.exists() else ['requests.json'])
    rejected, requested, rendered = set(), 0, 0
    for name in names:
        path = directory / 'png' / (Path(name).stem + '-report.json')
        raw = path.read_bytes()
        if len(raw) > 16 * 1024 * 1024:
            raise ValueError('Native renderer report exceeds the byte budget')
        report = json.loads(raw)
        if (not isinstance(report, dict) or not isinstance(report.get('rejections'), list)
                or any(type(report.get(k)) is not int for k in ['requested', 'rendered', 'rejected'])
                or report['requested'] != report['rendered'] + report['rejected']
                or report['rejected'] != len(report['rejections'])):
            raise ValueError('Invalid native renderer report')
        page_rows = json.loads((directory / name).read_text())
        page_keys = {r['key'] for r in page_rows}
        keys = {r['key'] for r in report['rejections']}
        if report['requested'] != len(page_rows) or len(keys) != report['rejected'] or not keys <= page_keys:
            raise ValueError('Native renderer report identity mismatch')
        rejected.update(keys)
        requested += report['requested']
        rendered += report['rendered']
    if requested != len(rows):
        raise ValueError('Native renderer generation count mismatch')
    accepted = [r for r in rows if r['key'] not in rejected]
    if len(accepted) != rendered or any(not (directory / 'png' / (r['key'] + '.png')).is_file() for r in accepted):
        raise ValueError('Native renderer accepted images are incomplete')
    return accepted, len(rejected)


def grouped(rows, axis):
    result = {}
    for row in rows:
        custom = customization(row['configuration'])
        group = json.dumps({'family': row['family'], 'customization':
                            {k: v for k, v in custom.items() if k != axis}},
                           sort_keys=True, separators=(',', ':'))
        result.setdefault(group, []).append(row)
    return result


def copy_exact(source, target):
    if target.exists():
        if source.read_bytes() != target.read_bytes():
            raise ValueError('Managed native asset collision')
    else:
        shutil.copyfile(source, target)


def encode_group(item, requests, staging):
    import numpy as np
    from PIL import Image
    description, rows = item
    work = staging / 'work' / digest(description.encode())
    work.mkdir(parents=True, exist_ok=True)
    unique, row_images = {}, {}
    for row in rows:
        source = requests / 'png' / (row['key'] + '.png')
        sha = digest(source.read_bytes())
        unique.setdefault(sha, source)
        row_images[row['key']] = sha
    arrays, webps, names = [], {}, sorted(unique)
    for sha in names:
        with Image.open(unique[sha]) as image:
            if image.size != (422, 514):
                raise ValueError('Unexpected native preview dimensions')
            pixels = np.array(image.convert('RGBA'))
        arrays.append(pixels)
        existing = ASSETS / 'configured_previews' / (sha + '.webp')
        path = existing if existing.exists() else work / (sha + '.webp')
        if not path.exists():
            subprocess.run(['cwebp', '-quiet', '-lossless', '-exact', '-m', '0',
                            str(unique[sha]), '-o', str(path)], check=True)
        with Image.open(path) as converted:
            if converted.size != (422, 514) or converted.convert('RGBA').tobytes() != pixels.tobytes():
                raise ValueError('Native WebP pixels differ from the renderer')
        webps[sha] = path
    webp_bytes = sum(p.stat().st_size for p in webps.values())
    packed = pack_pixels(arrays) if len(names) >= 8 else None
    indexed_bytes = (len(packed[0]) + sum(len(f) for f in packed[1])) if packed else None
    indexed = packed is not None and indexed_bytes < webp_bytes * .8
    files, assets = [], {}
    if indexed:
        plane, frames, classes = packed
        plane_path = work / (digest(plane) + '.nfpm')
        plane_path.write_bytes(plane)
        files.append(plane_path)
        for sha, frame in zip(names, frames):
            path = work / (digest(frame) + '.nfp')
            path.write_bytes(frame)
            files.append(path)
            assets[sha] = PREFIX + path.name
    else:
        files.extend(webps.values())
        assets.update({sha: PREFIX + path.name for sha, path in webps.items()})
    return ({k: assets[sha] for k, sha in row_images.items()}, files,
            {'description': json.loads(description), 'configurations': len(rows),
             'images': len(names), 'webpBytes': webp_bytes,
             'indexedBytes': indexed_bytes, 'indexed': indexed})


def stage(requests, staging, workers, axis, replacement=None):
    source_root = digest((ASSETS / 'configured_previews.json').read_bytes())
    existing = read_entries(ASSETS)
    rows, rejected = inputs(requests)
    if replacement is not None:
        raw = replacement.read_bytes()
        if len(raw) > 2 * 1024 * 1024:
            raise ValueError('Native replacement manifest exceeds the byte budget')
        replacement = json.loads(raw)
        validate_replacement(replacement, existing, rows, source_root)
        check_reports(requests)
    elif any(r['key'] in existing for r in rows):
        raise ValueError('Staging must contain only previously missing configuration keys')
    groups = grouped(rows, axis)
    target = staging / 'files'
    target.mkdir(parents=True, exist_ok=True)
    entries, reports = {}, []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(encode_group, item, requests, staging) for item in groups.items()]
        for future in as_completed(futures):
            added, files, report = future.result()
            entries = merge_entries(entries, added)
            for path in files:
                copy_exact(path, target / path.name)
            reports.append(report)
            print(f'Compressed groups={len(reports)}/{len(groups)} configurations={len(entries)}', flush=True)
    entries = (merge_replacement(existing, entries, replacement['entries'])
               if replacement is not None else merge_entries(existing, entries))
    # Independent production Dart validation also visits retained indexed frames.
    for asset in set(existing.values()):
        if not asset.endswith('.nfp'):
            continue
        path = ASSETS / 'configured_previews' / Path(asset).name
        copy_exact(path, target / path.name)
        plane = ASSETS / 'configured_previews' / decode_map_name(path.read_bytes())
        copy_exact(plane, target / plane.name)
    manifest = {'version': 2, 'locale': 'en_US', 'blankComplications': True, 'entries': entries}
    (staging / 'manifest.json').write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
    proof = {'sourceRoot': source_root, 'requests': str(requests), 'rejected': rejected,
             'incoming': {r['key']: digest((requests / 'png' / (r['key'] + '.png')).read_bytes()) for r in rows}}
    if replacement is not None:
        proof['replacement'] = replacement
    (staging / 'proof.json').write_text(json.dumps(proof, separators=(',', ':')) + '\n')
    (staging / 'report.json').write_text(json.dumps(reports, indent=2))
    verify(staging)


def verify(staging):
    from PIL import Image
    proof = json.loads((staging / 'proof.json').read_text())
    if proof['sourceRoot'] != digest((ASSETS / 'configured_previews.json').read_bytes()):
        raise ValueError('Source catalog changed during native staging')
    existing = read_entries(ASSETS)
    manifest = json.loads((staging / 'manifest.json').read_text())
    entries = manifest['entries']
    rows, rejected = inputs(Path(proof['requests']))
    incoming = {k: entries[k] for k in proof['incoming']}
    replacement = proof.get('replacement')
    if replacement is not None:
        expected = validate_replacement(replacement, existing, rows, proof['sourceRoot'])
        check_reports(Path(proof['requests']))
        merged = merge_replacement(existing, incoming, expected)
    else:
        merged = merge_entries(existing, incoming)
    if merged != entries:
        raise ValueError('Staging changed retained catalog entries or incoming coverage')
    if {r['key'] for r in rows} != proof['incoming'].keys() or rejected != proof['rejected']:
        raise ValueError('Native render proof changed during staging')
    checked = set()
    for position, (key, sha) in enumerate(proof['incoming'].items(), 1):
        if position % 8192 == 0:
            print(f'Verifying native pixels {position}/{len(proof["incoming"])}', flush=True)
        source = Path(proof['requests']) / 'png' / (key + '.png')
        if digest(source.read_bytes()) != sha:
            raise ValueError('Native source PNG changed during staging')
        asset = entries[key]
        if (asset, sha) in checked:
            continue
        path = staging / 'files' / Path(asset).name
        if asset.endswith('.nfp'):
            frame = path.read_bytes()
            if digest(frame) != path.stem:
                raise ValueError('Native frame encoded identity mismatch')
            width, height, raw = decode(frame, (staging / 'files' / decode_map_name(frame)).read_bytes())
        else:
            with Image.open(path) as image:
                width, height = image.size
                raw = image.convert('RGBA').tobytes()
        with Image.open(source) as original:
            if original.size != (width, height) or original.convert('RGBA').tobytes() != raw:
                raise ValueError('Staged native pixels differ from the renderer')
        checked.add((asset, sha))
    print(f'Verified native PNG pixels for {len(proof["incoming"])} incoming configurations; total={len(entries)}', flush=True)


def promote(staging):
    verify(staging)
    entries = json.loads((staging / 'manifest.json').read_text())['entries']
    destination = ASSETS / 'configured_previews'
    for path in (staging / 'files').iterdir():
        copy_exact(path, destination / path.name)
    write_catalog(ASSETS, entries)
    print(f'Published {len(entries)} exact configurations; retained assets preserved', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['stage', 'verify', 'promote'])
    parser.add_argument('staging', type=Path)
    parser.add_argument('--requests', type=Path)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--axis', default='color')
    parser.add_argument('--replacement', type=Path,
                        help='Explicit source-pinned Memoji old-reference manifest; stage only.')
    args = parser.parse_args()
    if args.replacement is not None and args.phase != 'stage':
        parser.error('--replacement applies only to staging; verification reads its pinned proof')
    staging = args.staging.resolve()
    if not str(staging).startswith(('/tmp/', '/private/tmp/')) or not 1 <= args.workers <= 8:
        parser.error('Use a temporary staging directory and one to eight workers')
    if args.phase == 'stage':
        if args.requests is None:
            parser.error('Pass the native requests directory')
        requests = args.requests.resolve()
        if not str(requests).startswith(('/tmp/', '/private/tmp/')):
            parser.error('Use a temporary native requests directory')
        stage(requests, staging, args.workers, args.axis, args.replacement)
    elif args.phase == 'verify':
        verify(staging)
    else:
        promote(staging)


if __name__ == '__main__':
    main()
