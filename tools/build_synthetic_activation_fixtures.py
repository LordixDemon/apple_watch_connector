#!/usr/bin/env python3
"""Generate deterministic serializer fixtures without captured activation material."""
import base64
import json
from pathlib import Path
import plistlib

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "apple-watch-bridge/core/src/test/resources/activation"


def fixtures():
    token = json.dumps({
        "InternationalMobileEquipmentIdentity": "000000000000000",
        "SerialNumber": "SYNTHETIC-TEST",
        "UniqueDeviceID": "00000000-0000-4000-8000-000000000001",
        "ActivationRandomness": "SYNTHETIC-NONCE",
        "WildcardTicket": "NOT-A-VALID-TICKET",
    }, sort_keys=True, separators=(",", ":")).encode()
    # Deliberately invalid certificates/keys; sizes exercise plist offset and
    # object-reference widths, and XML base64 line wrapping.
    record = {
        "AccountToken": base64.b64encode(token),
        "AccountTokenCertificate": b"SYNTHETIC CERTIFICATE " * 64,
        "AccountTokenSignature": bytes(range(128)),
        "DeviceCertificate": b"SYNTHETIC DEVICE CERTIFICATE " * 40,
        "FairPlayKeyData": bytes(range(256)) * 7,
        "RegulatoryInfo": b"SYNTHETIC REGULATORY INFO",
        "UniqueDeviceCertificate": b"SYNTHETIC UNIQUE CERTIFICATE " * 80,
        "unbrick": True,
    }
    container = {"ActivationRecord": record}
    return {
        "activation-resolved-body-2202.bin": plistlib.dumps(container, fmt=plistlib.FMT_XML),
        "activation-record-2202-canonical.bplist": plistlib.dumps(container, fmt=plistlib.FMT_BINARY),
        "activation-record-inner-2202.bplist": plistlib.dumps(record, fmt=plistlib.FMT_BINARY),
    }


if __name__ == "__main__":
    for name, data in fixtures().items():
        path = DESTINATION / name
        old = path.read_bytes()
        path.write_bytes(data)
        print(f"{name}: binary {len(old)} -> {len(data)} bytes")
