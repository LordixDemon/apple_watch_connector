#!/usr/bin/env python3
"""Compare extracted Watch Mach-O components without executing firmware.

Select the same architecture from thin/universal binaries. String-set equality
and address-encoding candidates are evidence, not proof of protocol equivalence.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct

ARCHITECTURES = {"arm64_32": (0x0200000C, 1), "arm64e": (0x0100000C, 2)}
STRING_SECTIONS = ("__cstring", "__objc_classname", "__objc_methname", "__oslogstring")


def unpack(data: bytes, fmt: str, offset: int) -> tuple:
    size = struct.calcsize(fmt)
    if offset < 0 or offset + size > len(data):
        raise ValueError("Truncated Mach-O structure")
    return struct.unpack_from(fmt, data, offset)


def architecture_slice(data: bytes, architecture: str) -> bytes:
    cpu, subtype = ARCHITECTURES[architecture]
    if data[:4] in (b"\xca\xfe\xba\xbe", b"\xca\xfe\xba\xbf"):
        wide = data[:4] == b"\xca\xfe\xba\xbf"
        count = unpack(data, ">I", 4)[0]
        fmt = ">IIQQII" if wide else ">IIIII"
        stride = struct.calcsize(fmt)
        for index in range(count):
            entry_cpu, entry_subtype, offset, size, *_ = unpack(data, fmt, 8 + index * stride)
            if offset < 8 + count * stride or offset + size > len(data):
                raise ValueError("Invalid universal Mach-O slice bounds")
            if entry_cpu == cpu and entry_subtype & 0xFFFFFF == subtype:
                return architecture_slice(data[offset:offset + size], architecture)
        raise ValueError(f"Architecture {architecture} absent")
    if data[:4] not in (b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe"):
        raise ValueError("Expected little-endian Mach-O")
    actual_cpu, actual_subtype = unpack(data, "<II", 4)
    if actual_cpu != cpu or actual_subtype & 0xFFFFFF != subtype:
        raise ValueError(f"Expected architecture {architecture}")
    return data


def sections(data: bytes) -> dict[str, bytes]:
    wide = data[:4] == b"\xcf\xfa\xed\xfe"
    header_size = 32 if wide else 28
    count, command_bytes = unpack(data, "<II", 16)
    end = header_size + command_bytes
    if end > len(data):
        raise ValueError("Truncated load commands")
    result = {}
    cursor = header_size
    for _ in range(count):
        command, size = unpack(data, "<II", cursor)
        if size < 8 or cursor + size > end:
            raise ValueError("Invalid load command size")
        if command in (1, 0x19):
            segment_wide = command == 0x19
            base_size, stride = (72, 80) if segment_wide else (56, 68)
            count_offset = 64 if segment_wide else 48
            section_count = unpack(data, "<I", cursor + count_offset)[0]
            if base_size + section_count * stride > size:
                raise ValueError("Truncated segment sections")
            for index in range(section_count):
                pos = cursor + base_size + index * stride
                name = data[pos:pos + 16].split(b"\0", 1)[0].decode("ascii")
                segment = data[pos + 16:pos + 32].split(b"\0", 1)[0].decode("ascii")
                section_size = unpack(data, "<Q" if segment_wide else "<I", pos + (40 if segment_wide else 36))[0]
                offset = unpack(data, "<I", pos + (48 if segment_wide else 40))[0]
                flags = unpack(data, "<I", pos + (64 if segment_wide else 56))[0]
                if flags & 0xFF in (1, 12, 18):  # zero-fill sections have no file bytes
                    continue
                if offset + section_size > len(data):
                    raise ValueError(f"Truncated section {segment}.{name}")
                result[f"{segment}.{name}"] = data[offset:offset + section_size]
        cursor += size
    if cursor != end:
        raise ValueError("Load command length mismatch")
    return result


def instruction_kind(word: int) -> str:
    if word & 0xFC000000 == 0x94000000:
        return "branch_link"
    if word & 0xFC000000 == 0x14000000:
        return "branch"
    if word & 0x9F000000 == 0x90000000:
        return "adrp"
    if word & 0x7F000000 == 0x11000000:
        return "add_sub_immediate"
    if word & 0x3B000000 == 0x39000000:
        return "load_store_unsigned_offset"
    return "other"


def instruction_differences(left: bytes, right: bytes) -> dict:
    if len(left) != len(right) or len(left) % 4:
        return {"same_length": False, "address_encoding_candidates_only": False}
    counts = Counter()
    candidates_only = True
    masks = {"branch": 0x03FFFFFF, "branch_link": 0x03FFFFFF,
             "adrp": 0x60FFFFE0, "add_sub_immediate": 0x003FFC00,
             "load_store_unsigned_offset": 0x003FFC00}
    for (a,), (b,) in zip(struct.iter_unpack("<I", left), struct.iter_unpack("<I", right)):
        if a == b:
            continue
        kind = instruction_kind(a)
        counts[kind] += 1
        mask = masks.get(kind)
        if instruction_kind(b) != kind or mask is None or (a ^ b) & ~mask:
            candidates_only = False
    return {"same_length": True, "changed_instructions": sum(counts.values()),
            "kinds": dict(sorted(counts.items())),
            "address_encoding_candidates_only": candidates_only}


def compare(left: bytes, right: bytes, architecture: str) -> dict:
    a, b = architecture_slice(left, architecture), architecture_slice(right, architecture)
    sa, sb = sections(a), sections(b)
    report = {"architecture": architecture, "slice_equal": a == b,
              "left_sha256": hashlib.sha256(a).hexdigest(),
              "right_sha256": hashlib.sha256(b).hexdigest(),
              "left_size": len(a), "right_size": len(b), "strings": {}}
    for name in STRING_SECTIONS:
        key = f"__TEXT.{name}"
        if key not in sa or key not in sb:
            report["strings"][name] = {"available": False}
            continue
        strings_a = set(sa[key].split(b"\0")) - {b""}
        strings_b = set(sb[key].split(b"\0")) - {b""}
        report["strings"][name] = {
            "available": True, "left_count": len(strings_a), "right_count": len(strings_b),
            "equal": strings_a == strings_b,
            "left_only": [x.decode("utf-8", errors="replace") for x in sorted(strings_a - strings_b)],
            "right_only": [x.decode("utf-8", errors="replace") for x in sorted(strings_b - strings_a)]}
    key = "__TEXT.__text"
    if key in sa and key in sb:
        report["text"] = instruction_differences(sa[key], sb[key])
        report["text"]["byte_equal"] = sa[key] == sb[key]
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--left", type=Path, required=True, help="Extracted firmware root")
    parser.add_argument("--right", type=Path, required=True, help="Comparison firmware root")
    parser.add_argument("--arch", choices=ARCHITECTURES, default="arm64_32")
    parser.add_argument("--component", type=Path, action="append", required=True,
                        help="Relative Mach-O path; repeat for each component")
    args = parser.parse_args()
    results = {}
    for relative in args.component:
        if relative.is_absolute() or ".." in relative.parts:
            parser.error("Component paths must remain relative to firmware roots")
        try:
            results[relative.as_posix()] = compare((args.left / relative).read_bytes(),
                                                    (args.right / relative).read_bytes(), args.arch)
        except (OSError, ValueError) as error:
            parser.error(f"{relative}: {error}")
    print(json.dumps({"schema": 1, "components": results,
                      "limit": "Static binary evidence; no hardware pairing acceptance."}, indent=2))


if __name__ == "__main__":
    main()
