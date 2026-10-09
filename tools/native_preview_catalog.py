"""Content-addressed preview indexes; no family list or approximate style lookup.

The phone reads one small root and only requested SHA256-prefix shards. Host
tools may flatten all entries for offline verification and generation.
"""
import hashlib
import json
import re
from pathlib import Path

MAX_DOCUMENT_BYTES = 2 * 1024 * 1024
INDEX_PREFIX = 'assets/native_faces/configured_preview_indexes/'
IMAGE_PREFIX = 'assets/native_faces/configured_previews/'


def encoded(value):
    return (json.dumps(value, separators=(',', ':'), sort_keys=True) + '\n').encode()


def checked_entries(value, indexed=True):
    if not isinstance(value, dict) or len(value) > 10000:
        raise ValueError('Invalid or excessive preview entries')
    for key, asset in value.items():
        if (not re.fullmatch('[a-f0-9]{64}', key) or not isinstance(asset, str)
                or not re.fullmatch(re.escape(IMAGE_PREFIX) + r'[a-f0-9]{64}\.(webp|nfp)', asset)
                or (not indexed and asset.endswith('.nfp'))):
            raise ValueError('Invalid managed preview entry')
    return value


def read_catalog(assets):
    raw = (assets / 'configured_previews.json').read_bytes()
    if len(raw) > MAX_DOCUMENT_BYTES:
        raise ValueError('Preview root exceeds its reader budget')
    root = json.loads(raw)
    if (root.get('locale') != 'en_US' or root.get('blankComplications') is not True):
        raise ValueError('Incompatible native preview metadata')
    if root.get('version') in (1, 2):
        return root, checked_entries(root.get('entries'), root['version'] == 2)
    shards = root.get('shards')
    if (root.get('version') != 3 or len(raw) > 128 * 1024
            or not isinstance(shards, dict) or len(shards) > 256 or 'entries' in root):
        raise ValueError('Invalid native preview index')
    entries = {}
    for prefix, reference in shards.items():
        if (not re.fullmatch('[a-f0-9]{2}', prefix) or not isinstance(reference, dict)
                or not isinstance(reference.get('asset'), str)
                or not re.fullmatch(re.escape(INDEX_PREFIX) + r'[a-f0-9]{64}\.json', reference['asset'])
                or type(reference.get('count')) is not int or not 1 <= reference['count'] <= 10000
                or type(reference.get('bytes')) is not int or not 1 <= reference['bytes'] <= MAX_DOCUMENT_BYTES):
            raise ValueError('Invalid native preview shard reference')
        data = (assets / 'configured_preview_indexes' / Path(reference['asset']).name).read_bytes()
        if (len(data) != reference['bytes'] or
                hashlib.sha256(data).hexdigest() != Path(reference['asset']).stem):
            raise ValueError('Preview shard identity mismatch')
        shard = json.loads(data)
        values = checked_entries(shard.get('entries'))
        if (shard.get('version') != 1 or shard.get('prefix') != prefix
                or len(values) != reference['count']
                or any(not key.startswith(prefix) for key in values)):
            raise ValueError('Preview shard membership mismatch')
        entries.update(values)
    return root, entries


def write_catalog(assets, entries, *, sharded=True):
    """Publish root last, after collision checks and all shard writes/verification.

    Old indexes are retained until the new root is committed. Callers capture
    affected files before invoking this generated/bulk operation.
    """
    root = {'version': 3 if sharded else 2, 'renderer': 'NanoTimeKit watchOS 26.2',
            'locale': 'en_US', 'blankComplications': True}
    if not sharded:
        root['entries'] = checked_entries(entries)
    else:
        groups = {}
        for key, asset in entries.items():
            groups.setdefault(key[:2], {})[key] = asset
        if len(groups) > 256:
            raise ValueError('Preview shard count exceeded')
        root['shards'] = {}
        files = {}
        for prefix, values in sorted(groups.items()):
            checked_entries(values)
            data = encoded({'version': 1, 'prefix': prefix, 'entries': values})
            if len(data) > MAX_DOCUMENT_BYTES:
                raise ValueError('Preview shard byte budget exceeded')
            name = hashlib.sha256(data).hexdigest() + '.json'
            path = assets / 'configured_preview_indexes' / name
            if path.exists() and path.read_bytes() != data:
                raise ValueError('Preview shard content address collision')
            files[path] = data
            root['shards'][prefix] = {'asset': INDEX_PREFIX + name,
                'count': len(values), 'bytes': len(data)}
        for path, data in files.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            if path.read_bytes() != data:
                raise ValueError('Preview shard write verification failed')
    data = encoded(root)
    if len(data) > (128 * 1024 if sharded else MAX_DOCUMENT_BYTES):
        raise ValueError('Preview root byte budget exceeded')
    target = assets / 'configured_previews.json'
    temporary = assets / '.configured-previews.pending'
    temporary.write_bytes(data)
    temporary.replace(target)
    validated, actual = read_catalog(assets)
    if actual != entries or validated != root:
        raise ValueError('Preview catalog publication verification failed')
    if sharded:
        used = {Path(value['asset']).name for value in root['shards'].values()}
        for path in (assets / 'configured_preview_indexes').iterdir():
            if re.fullmatch(r'[a-f0-9]{64}\.json', path.name) and path.name not in used:
                path.unlink()
    return root
