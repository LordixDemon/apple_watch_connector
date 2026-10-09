"""Synthetic data-only intent archives. No Watch IDs, intents or user data."""
import plistlib
from pathlib import Path


def archive(objects):
    return plistlib.dumps({"$archiver": "NSKeyedArchiver", "$version": 100000,
                          "$objects": objects, "$top": {"root": plistlib.UID(1)}},
                         fmt=plistlib.FMT_BINARY, sort_keys=False)


def fixture(*, mutable=False, seconds=900, changed_schema=False, changed_future=False,
            invalid_uuid=False):
    uid = plistlib.UID
    schema = ["$null",
              {"$class": uid(2), "versioningHash": uid(3), "attributes": uid(4),
               "intentName": "ResearchTimer"},
              {"$classname": "INIntentCodableDescription", "$classes": ["INIntentCodableDescription", "NSObject"]},
              2**63 + (29 if mutable else 17),
              {"$class": uid(5), "NS.objects": [uid(6), uid(6)]},
              {"$classname": "NSMutableArray" if mutable else "NSArray",
               "$classes": ["NSMutableArray", "NSArray", "NSObject"] if mutable else ["NSArray", "NSObject"]},
              {"$class": uid(7), "_codableDescription": uid(0 if mutable else 1),
               "name": "duration", "valueType": "otherType" if changed_schema else "timeInterval"},
              {"$classname": "INCodableObjectAttribute", "$classes": ["INCodableObjectAttribute", "NSObject"]}]
    objects = ["$null",
               {"$class": uid(2), "identifier": uid(3), "backingStore": uid(4),
                "parameterCombinations": uid(6), "recordDeviceUID": uid(0),
                "future": "changed" if changed_future else "preserved"},
               {"$classname": "INIntent", "$classes": ["INIntent", "NSObject"]},
               "custom-key" if invalid_uuid else ("22222222-2222-2222-2222-222222222222" if mutable else "11111111-1111-1111-1111-111111111111"),
               {"$class": uid(5), "bytes": seconds.to_bytes(8, "big"),
                "codableDescriptionBytes": archive(schema)},
               {"$classname": "INCodable", "$classes": ["INCodable", "NSObject"]},
               {"$class": uid(7), "NS.keys": [uid(8), uid(9)], "NS.objects": [uid(0), uid(0)]},
               {"$classname": "NSMutableDictionary" if mutable else "NSDictionary",
                "$classes": ["NSMutableDictionary", "NSDictionary", "NSObject"] if mutable else ["NSDictionary", "NSObject"]},
               "duration", 13]
    if mutable:
        objects[6]["NS.keys"].reverse()
        # Reallocate every UID while retaining the root entry point.
        order = [0, 1] + list(range(len(objects) - 1, 1, -1))
        mapping = {old: new for new, old in enumerate(order)}
        def remap(value):
            if isinstance(value, uid): return uid(mapping[value.data])
            if isinstance(value, dict): return {k: remap(v) for k, v in value.items()}
            if isinstance(value, list): return [remap(v) for v in value]
            return value
        objects = [remap(objects[i]) for i in order]
    return archive(objects)


if __name__ == "__main__":
    destination = Path(__file__).resolve().parent.parent / "apple-watch-companion/test/fixtures"
    variants = {"original": {}, "rearchived": {"mutable": True},
                "duration": {"mutable": True, "seconds": 60},
                "schema": {"mutable": True, "changed_schema": True},
                "future": {"mutable": True, "changed_future": True},
                "identifier": {"mutable": True, "invalid_uuid": True}}
    for name, options in variants.items():
        (destination / f"native-intent-43-{name}.bplist").write_bytes(fixture(**options))
