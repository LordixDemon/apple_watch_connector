#!/usr/bin/env python3
"""Verify packaged native preview assets against their final source generation.

Read-only build audit. No device, pairing identity or Watch commands are used.
"""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

PACKAGED = 'assets/flutter_assets/assets/native_faces/'
DIRECTORIES = ('configured_previews', 'configured_preview_indexes', 'pigment_swatches',
               'previews', 'component_previews')
ROOT_FILES = ('watchos_26_2.json', 'configured_previews.json', 'edit_sections_26_2.json', 'pigments_26_2.json',
              'pigment_collections_26_2.json', 'complication_layouts_26_2.json',
              'gallery_collections_26_2.json')


def digest(stream):
    result = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        result.update(chunk)
    return result.hexdigest()


def verify(apk, assets, *, allow_profile=False):
    expected = {name: assets / name for name in ROOT_FILES if (assets / name).is_file()}
    for directory in DIRECTORIES:
        if not (assets / directory).is_dir():
            continue
        for path in (assets / directory).iterdir():
            if not path.is_file():
                raise ValueError('Unexpected directory inside managed native assets')
            expected[directory + '/' + path.name] = path
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK ZIP entries')
        actual = {name[len(PACKAGED):] for name in names
                  if name in {PACKAGED + root for root in ROOT_FILES}
                  or any(name.startswith(PACKAGED + directory + '/') for directory in DIRECTORIES)}
        if actual != expected.keys():
            raise ValueError('Packaged native preview generation differs from source file set')
        for name, path in expected.items():
            info = archive.getinfo(PACKAGED + name)
            if info.file_size != path.stat().st_size:
                raise ValueError('Packaged native asset length differs from source')
            with archive.open(info) as packaged, path.open('rb') as source:
                if digest(packaged) != digest(source):
                    raise ValueError('Packaged native asset content differs from source')
        for name in names:
            if name.startswith('assets/flutter_assets/') and any(
                    part in {'test', 'fixtures', 'testfixtures'} for part in name.split('/')):
                raise ValueError('Test fixtures packaged in application')
        libraries = [name for name in names if name.endswith('/libapp.so')]
        if not libraries:
            raise ValueError('APK has no compiled Flutter application')
        if not allow_profile:
            for name in libraries:
                with archive.open(name) as library:
                    tail = b''
                    for chunk in iter(lambda: library.read(1024 * 1024), b''):
                        data = tail + chunk
                        if b'NATIVE_FACE_PROFILE' in data:
                            raise ValueError('Profiling entry point packaged in ordinary release')
                        tail = data[-64:]
    with apk.open('rb') as stream:
        sha = digest(stream)
    return {'managedAssetCount': len(expected), 'bytes': apk.stat().st_size, 'sha256': sha,
            'profileAllowed': allow_profile}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--assets', type=Path, default=Path(__file__).resolve().parents[1]
                        / 'apple-watch-companion/assets/native_faces')
    parser.add_argument('--allow-profile', action='store_true')
    args = parser.parse_args()
    print(json.dumps(verify(args.apk, args.assets, allow_profile=args.allow_profile)))


if __name__ == '__main__':
    main()
