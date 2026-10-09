"""Synthetic scalar TimeInterval archives; no real Watch/user intent data."""
import struct
import plistlib
from pathlib import Path


def archive(objects):
    return plistlib.dumps({'$archiver': 'NSKeyedArchiver', '$version': 100000,
                          '$objects': objects, '$top': {'root': plistlib.UID(1)}},
                         fmt=plistlib.FMT_BINARY, sort_keys=False)


def fixture(*, field=13, maximum=0, duplicate=False, nan=False, alias=False):
    uid = plistlib.UID
    schema = archive([
        '$null', {'$class': uid(2), 'attributes': uid(3), 'versioningHash': 2**63+17},
        {'$classname': 'INIntentCodableDescription', '$classes': ['INIntentCodableDescription', 'NSObject']},
        {'$class': uid(4), 'NS.keys': [uid(5)], 'NS.objects': [uid(6)]},
        {'$classname': 'NSDictionary', '$classes': ['NSDictionary', 'NSObject']}, field,
        {'$class': uid(7), '_typeString': 'TimeInterval', 'typeName': 'Double',
         'modifier': 1, 'fixedSizeArray': False, 'supportsDynamicEnumeration': False,
         'configurable': False, 'propertyName': 'researchInterval', 'displayName': 'Research interval',
         'metadata': uid(8)},
        {'$classname': 'INCodableObjectAttribute', '$classes': ['INCodableObjectAttribute', 'NSObject']},
        {'$class': uid(9), 'minimumValue': 0, 'maximumValue': maximum},
        {'$classname': 'INCodableTimeIntervalAttributeMetadata', '$classes': ['INCodableTimeIntervalAttributeMetadata', 'NSObject']},
    ])
    def varint(v):
        b = bytearray()
        while v >= 128: b.append((v & 127) | 128); v >>= 7
        b.append(v); return bytes(b)
    parameter = varint((field << 3) | 2) + b'\x0b\x0a\x09\x11' + struct.pack('<d', float('nan') if nan else 900)
    payload = parameter + (parameter if duplicate else b'') + varint((65535 << 3) | 2) + b'\x03\x12\x01X'
    objects = [
        '$null', {'$class': uid(2), 'identifier': uid(3), 'backingStore': uid(4),
                  'unknownFuture': b'untouched', 'unknownNumeric': 17001},
        {'$classname': 'INIntent', '$classes': ['INIntent', 'NSObject']},
        '00000000-0000-0000-0000-000000000046',
        {'$class': uid(5), 'bytes': payload, 'codableDescriptionBytes': schema},
        {'$classname': 'INCodable', '$classes': ['INCodable', 'NSObject']},
    ]
    if alias: objects[1]['unknownAlias'] = payload
    return archive(objects)


if __name__ == '__main__':
    destination = Path(__file__).resolve().parents[1] / 'apple-watch-companion/test/fixtures'
    for name, options in {'scalar': {}, 'alternate-field': {'field': 21},
                          'bounded': {'maximum': 1200}, 'duplicate': {'duplicate': True},
                          'nan': {'nan': True}, 'alias': {'alias': True}}.items():
        (destination / f'native-parameters-46-{name}.bplist').write_bytes(fixture(**options))
