#!/usr/bin/env python3
"""Generate portable scalar/property data from one exhaustive Foundation capture."""
import argparse
import base64
import gzip
import hashlib
import json
import struct
from pathlib import Path


def scalar(value):
    if type(value) is not int or not 0 <= value <= 0x10ffff or 0xd800 <= value <= 0xdfff:
        raise ValueError('Invalid Unicode scalar')
    return value


def validate_ranges(rows):
    if not isinstance(rows, list) or not rows or len(rows) > 4096:
        raise ValueError('Invalid range count')
    last = -2
    for row in rows:
        if not isinstance(row, list) or len(row) != 2:
            raise ValueError('Invalid range')
        start, end = map(scalar, row)
        if start <= last + 1 or end < start or start <= 0xdfff and end >= 0xd800:
            raise ValueError('Noncanonical ranges')
        last = end


def validate_rows(rows, kind):
    if not isinstance(rows, list) or not rows or len(rows) > 8192:
        raise ValueError('Invalid scalar row count')
    last = -1
    for row in rows:
        if not isinstance(row, list) or len(row) != 2 or scalar(row[0]) <= last:
            raise ValueError('Invalid or unordered scalar row')
        last, value = row
        if kind == 'uppercase':
            if not isinstance(value, str) or not value or len(value.encode('utf-16-be')) > 32 or value == chr(last):
                raise ValueError('Invalid uppercase mapping')
        elif kind == 'combiningClasses':
            if type(value) is not int or not 1 <= value <= 255:
                raise ValueError('Invalid combining class')
        else:
            if not isinstance(value, list) or not 2 <= len(value) <= 64:
                raise ValueError('Invalid decomposition')
            for item in value:
                scalar(item)


def generate(document):
    if (document.get('schema') != 2 or document.get('checked') != 1112064
            or document.get('uppercaseMatches') != 1112064
            or document.get('runtime') != 'Version 26.2 (Build 23S303)'):
        raise ValueError('Unverified Foundation runtime/capture')
    output = bytearray(b'FNTX\x00\x00\x00\x02')
    for name in ['uppercase', 'letterRanges', 'nonBaseRanges', 'combiningClasses', 'greekDecomposition',
                 'extendRanges', 'pictographicRanges', 'prependRanges', 'spacingRanges']:
        rows = document.get(name)
        if name.endswith('Ranges'):
            validate_ranges(rows)
        else:
            validate_rows(rows, name)
        output.extend(struct.pack('>I', len(rows)))
        for key, value in rows:
            output.extend(struct.pack('>I', key))
            if name.endswith('Ranges'):
                output.extend(struct.pack('>I', value))
            elif name == 'uppercase':
                text = value.encode('utf-16-be')
                output.append(len(text) // 2)
                output.extend(text)
            elif name == 'combiningClasses':
                output.append(value)
            else:
                output.append(len(value))
                output.extend(b''.join(struct.pack('>I', v) for v in value))
    encoded = base64.b64encode(gzip.compress(bytes(output), compresslevel=9, mtime=0)).decode()
    digest = hashlib.sha256(output).hexdigest()
    chunks = ['        "' + encoded[i:i+120] + '"' for i in range(0, len(encoded), 120)]
    source = '''package dev.applewatchandroid.companion.apple_watch_companion;

/** Generated exhaustive watchOS 26.2 / 23S303 CoreFoundation data. */
final class NativeFoundationTextPayload {
    static final String SHA256 = "%s";
    static final String BASE64_GZIP =
%s;
    private NativeFoundationTextPayload() { }
}
''' % (digest, ' +\n'.join(chunks))
    return bytes(output), source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture', type=Path)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--binary', required=True, type=Path)
    args = parser.parse_args()
    data, source = generate(json.loads(args.capture.read_text()))
    args.java.write_text(source)
    args.binary.write_bytes(data)


if __name__ == '__main__':
    main()
