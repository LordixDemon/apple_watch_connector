#!/usr/bin/env python3
"""Factor exact native RGBA pixels; stage and verify before asset promotion.

NFPM/NFPT v1 use little endian dimensions/classes, uint16 indices and zlib.
NFPT includes SHA256(map file) and SHA256(original straight-alpha RGBA pixels).
Groups come from native customization metadata, excluding only the chosen axis.
There is no interpolation, native renderer call, Watch command or family list.
"""
import argparse
import gc
import hashlib
import itertools
import json
import shutil
import struct
import zlib
from pathlib import Path
from native_preview_catalog import read_catalog, write_catalog

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'apple-watch-companion/assets/native_faces'
PREFIX = 'assets/native_faces/configured_previews/'


def header(magic, width, height, classes):
    if not (0 < width <= 1024 and 0 < height <= 1024
            and width * height <= 512 * 1024 and 0 < classes <= 65536):
        raise ValueError('Indexed native preview bounds exceeded')
    return struct.pack('<4sBBHHHI', magic, 1, 2, 0, width, height, classes)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def decode(frame, plane):
    if len(frame) <= 80 or len(plane) <= 16 or max(len(frame), len(plane)) > 1024 * 1024:
        raise ValueError('Native preview encoded size exceeded')
    f = struct.unpack('<4sBBHHHI', frame[:16])
    m = struct.unpack('<4sBBHHHI', plane[:16])
    if (f[0] != b'NFPT' or m[0] != b'NFPM' or f[1:] != m[1:]
            or frame[16:48] != hashlib.sha256(plane).digest()):
        raise ValueError('Native preview header/identity mismatch')
    _, version, index_bytes, reserved, width, height, classes = f
    if (version != 1 or index_bytes != 2 or reserved != 0
            or header(b'NFPT', width, height, classes) != frame[:16]):
        raise ValueError('Native preview format mismatch')
    import numpy as np
    index = inflate(plane[16:], width * height * 2)
    palette = inflate(frame[80:], classes * 4)
    if len(index) != width * height * 2 or len(palette) != classes * 4:
        raise ValueError('Native preview expanded size mismatch')
    ids = np.frombuffer(index, dtype='<u2')
    if int(ids.max()) >= classes:
        raise ValueError('Native preview invalid index')
    rgba = np.frombuffer(palette, dtype=np.uint8).reshape(classes, 4)[ids].tobytes()
    if hashlib.sha256(rgba).digest() != frame[48:80]:
        raise ValueError('Native preview pixel mismatch')
    return width, height, rgba


def inflate(encoded, length):
    try:
        decoder = zlib.decompressobj()
        raw = decoder.decompress(encoded, length + 1)
    except zlib.error as error:
        raise ValueError('Invalid native preview compression') from error
    if (len(raw) != length or not decoder.eof or decoder.unused_data
            or decoder.unconsumed_tail):
        raise ValueError('Native preview expanded size mismatch')
    return raw


def groups(manifest, profiles, axis):
    from build_native_face_previews import key
    result = {}
    for template in profiles:
        options = template['options']
        if axis not in options:
            continue
        fields = list(options)
        count = 1
        for values in options.values():
            count *= len(values)
        if count > 100000:
            continue  # Bounded build-time enumeration; unknown styles stay WebP.
        base = template['configuration']['customization']
        for combo in itertools.product(*(options[f] for f in fields)):
            custom = dict(base)
            custom.update({f: v['value'] for f, v in zip(fields, combo)})
            asset = manifest['entries'].get(key(template['family'], custom))
            if asset is None or not asset.endswith('.webp'):
                continue
            group = json.dumps({'family': template['family'], 'customization':
                {k: v for k, v in custom.items() if k != axis}}, sort_keys=True)
            result.setdefault(group, set()).add(asset)
    return result


def partition_pixels(arrays, max_classes=65536):
    """Exact incremental equivalence, including all four native RGBA bytes.

    Each uint64 pair joins the previous class ID and this frame's uint32 pixel.
    It is injective, not a probabilistic hash. No wide pixel×all-frames matrix
    or interpolated color model is needed. Representatives define exact palettes.
    """
    import numpy as np
    if not arrays or len({a.shape for a in arrays}) != 1:
        raise ValueError('Native preview partition dimensions differ')
    width = arrays[0].shape[-1]
    if width != 4 or not 1 <= max_classes <= 65536:
        raise ValueError('Invalid native preview partition bounds')
    ids = np.zeros(arrays[0].size // 4, dtype=np.uint32)
    for array in arrays:
        if array.dtype != np.uint8:
            raise ValueError('Native RGBA byte arrays required')
        rgba = np.ascontiguousarray(array).reshape(-1, 4).view('<u4').reshape(-1)
        pairs = (ids.astype(np.uint64) << 32) | rgba
        values, inverse = np.unique(pairs, return_inverse=True)
        if len(values) > max_classes:
            return None
        ids = inverse.astype(np.uint32)
    _, representatives = np.unique(ids, return_index=True)
    return ids, representatives


def pack_pixels(arrays, max_classes=65536):
    """Reusable exact encoder for native PNGs and already verified WebPs."""
    import numpy as np
    partition = partition_pixels(arrays, max_classes)
    if partition is None:
        return None
    height, width, channels = arrays[0].shape
    if channels != 4:
        raise ValueError('Native preview RGBA required')
    inverse, representatives = partition
    classes = len(representatives)
    plane = header(b'NFPM', width, height, classes) + zlib.compress(
        inverse.astype('<u2').tobytes(), 9)
    map_hash = hashlib.sha256(plane).digest()
    frames = []
    for pixels in arrays:
        raw = pixels.tobytes()
        palette = pixels.reshape(-1, 4)[representatives].tobytes()
        frame = (header(b'NFPT', width, height, classes) + map_hash
                 + hashlib.sha256(raw).digest() + zlib.compress(palette, 9))
        if decode(frame, plane) != (width, height, raw):
            raise ValueError('Native pixel equality failed')
        frames.append(frame)
    return plane, frames, classes


def stage(directory, axis, preserve_catalog=None):
    import numpy as np
    from PIL import Image
    metadata, source_entries = read_catalog(ASSETS)
    source = {'version': 2, 'renderer': metadata.get('renderer'), 'locale': metadata['locale'],
              'blankComplications': metadata['blankComplications'], 'entries': source_entries}
    from build_native_face_previews import read_entries
    read_entries(ASSETS)
    profiles = json.loads((ASSETS / 'watchos_26_2.json').read_text())['templates']
    candidate = groups(source, profiles, axis)
    protected = set()
    if preserve_catalog is not None:
        _, preserved = read_catalog(preserve_catalog)
        if any(source_entries.get(key) != asset for key, asset in preserved.items()):
            raise ValueError('Preserved native catalog differs from current entries')
        protected = set(preserved.values())
    target = directory / 'files'
    target.mkdir(parents=True, exist_ok=True)
    replacements, reports = {}, []
    seen = set()
    for description, paths in sorted(candidate.items()):
        paths = sorted(paths - protected)
        identity = tuple(paths)
        if len(paths) < 8 or identity in seen:
            continue
        seen.add(identity)
        arrays = []
        for asset in paths:
            with Image.open(ROOT / 'apple-watch-companion' / asset) as image:
                arrays.append(np.array(image.convert('RGBA')))
        shapes = {a.shape for a in arrays}
        if len(shapes) != 1:
            raise ValueError('Native preview group dimensions differ')
        height, width, channels = arrays[0].shape
        if channels != 4:
            raise ValueError('Native preview RGBA required')
        partition = partition_pixels(arrays)
        if partition is None:
            del arrays
            gc.collect()
            continue
        inverse, representatives = partition
        class_count = len(representatives)
        plane = header(b'NFPM', width, height, class_count) + zlib.compress(
            inverse.astype('<u2').tobytes(), 9)
        map_hash = hashlib.sha256(plane).digest()
        frames = []
        for pixels in arrays:
            raw = pixels.tobytes()
            palette = pixels.reshape(-1, 4)[representatives].tobytes()
            frame = (header(b'NFPT', width, height, class_count) + map_hash
                     + hashlib.sha256(raw).digest()
                     + zlib.compress(palette, 9))
            if decode(frame, plane) != (width, height, raw):
                raise ValueError('Native pixel equality failed')
            frames.append(frame)
        old_bytes = sum((ROOT / 'apple-watch-companion' / p).stat().st_size for p in paths)
        new_bytes = len(plane) + sum(len(f) for f in frames)
        accepted = new_bytes < old_bytes * .8
        reports.append({'description': json.loads(description), 'frames': len(paths),
                        'classes': class_count, 'webpBytes': old_bytes,
                        'indexedBytes': new_bytes, 'accepted': accepted})
        if accepted:
            (target / (map_hash.hex() + '.nfpm')).write_bytes(plane)
            for asset, frame in zip(paths, frames):
                name = digest(frame) + '.nfp'
                (target / name).write_bytes(frame)
                replacements.setdefault(asset, PREFIX + name)
        print(f'{len(paths)} frames / {class_count} classes: '
              f'{old_bytes} -> {new_bytes} bytes; accepted={accepted}', flush=True)
        del arrays, inverse, representatives, partition, frames
        gc.collect()
    entries = {k: replacements.get(v, v) for k, v in source['entries'].items()}
    used = {Path(v).name for v in entries.values() if v.endswith('.nfp')}
    # Re-running after a version-two catalog or a later --merge preserves existing
    # indexed assets, rather than assuming every frame was generated in this run.
    for name in list(used):
        if not (target / name).exists():
            shutil.copyfile(ASSETS / 'configured_previews' / name, target / name)
        plane = decode_map_name((target / name).read_bytes())
        if not (target / plane).exists():
            shutil.copyfile(ASSETS / 'configured_previews' / plane, target / plane)
        used.add(plane)
    for stale in target.iterdir():
        if stale.name not in used:
            stale.unlink()
    manifest = {**source, 'version': 2, 'entries': entries}
    (directory / 'manifest.json').write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
    (directory / 'report.json').write_text(json.dumps(reports, indent=2))
    (directory / 'source-manifest-sha256.txt').write_text(digest((ASSETS / 'configured_previews.json').read_bytes()))
    verify(directory)


def decode_map_name(frame):
    return frame[16:48].hex() + '.nfpm'


def verify(directory):
    from PIL import Image
    source_bytes = (ASSETS / 'configured_previews.json').read_bytes()
    if digest(source_bytes) != (directory / 'source-manifest-sha256.txt').read_text():
        raise ValueError('Source catalog changed during staging')
    _, source = read_catalog(ASSETS)
    staged = json.loads((directory / 'manifest.json').read_text())['entries']
    if source.keys() != staged.keys():
        raise ValueError('Native configuration coverage changed')
    checked = set()
    for key, asset in staged.items():
        if asset == source[key] or (asset, source[key]) in checked:
            continue
        path = directory / 'files' / Path(asset).name
        frame = path.read_bytes()
        plane = (directory / 'files' / decode_map_name(frame)).read_bytes()
        if digest(frame) != path.stem or digest(plane) != frame[16:48].hex():
            raise ValueError('Encoded native asset hash mismatch')
        width, height, raw = decode(frame, plane)
        with Image.open(ROOT / 'apple-watch-companion' / source[key]) as original:
            if original.size != (width, height) or original.convert('RGBA').tobytes() != raw:
                raise ValueError('Native WebP pixels changed')
        checked.add((asset, source[key]))
    print(f'Verified {len(staged)} exact configurations; {len(checked)} factored images.', flush=True)


def promote(directory):
    verify(directory)
    destination = ASSETS / 'configured_previews'
    for path in (directory / 'files').iterdir():
        target = destination / path.name
        if target.exists() and target.read_bytes() != path.read_bytes():
            raise ValueError('Managed native asset collision')
        shutil.copyfile(path, target)
    entries = json.loads((directory / 'manifest.json').read_text())['entries']
    write_catalog(ASSETS, entries)
    used = {Path(v).name for v in entries.values()}
    used.update(decode_map_name((destination / n).read_bytes())
                for n in list(used) if n.endswith('.nfp'))
    for path in destination.iterdir():
        if path.name not in used:
            path.unlink()
    print(f'Promoted {len(entries)} exact configurations; {len(used)} managed assets; '
          f'{sum((destination / n).stat().st_size for n in used)} bytes.', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['stage', 'verify', 'promote'])
    parser.add_argument('directory', type=Path)
    parser.add_argument('--axis', default='color')
    parser.add_argument('--preserve-catalog', type=Path,
                        help='Keep every asset reference from a previously verified catalog.')
    args = parser.parse_args()
    directory = args.directory.resolve()
    if not (str(directory).startswith('/private/tmp/') or str(directory).startswith('/tmp/')):
        parser.error('Use a temporary staging directory')
    directory.mkdir(parents=True, exist_ok=True)
    if args.phase == 'stage':
        stage(directory, args.axis, args.preserve_catalog)
    elif args.phase == 'verify':
        verify(directory)
    else:
        promote(directory)


if __name__ == '__main__':
    main()
