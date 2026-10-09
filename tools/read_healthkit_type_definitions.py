#!/usr/bin/env python3
"""Extract verified 23S303 HKDataTypeDefinitions, not personal Health records.

Layout proved by HealthKit _definition/_lock_dataTypeWithCode/identifier/
canonicalUnit ARM64 instructions retained in health-372-*.asm. Other flags and
parent pointers are retained as raw evidence, never interpreted as permission.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

TABLE = 0x1ED60A4D8
COUNT = 342
STRIDE = 48


def extract(path):
    binary = path.read_bytes()
    if binary[:4] != b"\xcf\xfa\xed\xfe":
        raise ValueError("Expected little-endian 64-bit Mach-O")
    segments = []
    offset = 32
    for _ in range(struct.unpack_from("<I", binary, 16)[0]):
        command, size = struct.unpack_from("<II", binary, offset)
        if size < 8 or offset + size > len(binary):
            raise ValueError("Invalid Mach-O command")
        if command == 0x19:
            address, _, file_offset, file_size = struct.unpack_from("<QQQQ", binary, offset + 24)
            if file_offset + file_size > len(binary):
                raise ValueError("Invalid Mach-O segment")
            segments.append((address, file_size, file_offset))
        offset += size

    def read(address, size):
        for base, length, start in segments:
            if base <= address and address + size <= base + length:
                return binary[start + address - base:start + address - base + size]
        raise ValueError("Address outside extracted image")

    def string(address):
        if not address:
            return None
        _, flags, pointer, length = struct.unpack("<QQQQ", read(address, 32))
        # This image's table uses ASCII CFString records only. Refuse other ABI.
        if flags != 0x7C8 or length > 4096:
            raise ValueError("Unverified CFString representation")
        value = read(pointer, length).decode("ascii", errors="strict")
        if any(c in value for c in "\x00\t\r\n"):
            raise ValueError("Invalid table string")
        return value

    result = []
    for index in range(COUNT):
        entry = read(TABLE + index * STRIDE, STRIDE)
        pointers = struct.unpack_from("<5Q", entry)
        code, flags = struct.unpack_from("<hH", entry, 40)
        if code != index:
            raise ValueError("Native table code/index mismatch")
        values = [string(p) for p in pointers[:4]]
        identifier, type_class, sample_class, unit = values
        if (identifier is None) != (type_class is None):
            raise ValueError("Partial type definition")
        if identifier is None and any(pointers):
            raise ValueError("Unexpected reserved entry")
        # Nil and the empty dimensionless unit remain distinct; no fallback.
        result.append((code, identifier, type_class, sample_class, unit, pointers[4], flags))
    return hashlib.sha256(binary).hexdigest(), result


def java(sha, entries):
    literal = lambda s: "null" if s is None else json.dumps(s, ensure_ascii=True)
    lines = ["package dev.applewatchandroid.bridge;", "",
             "/** Generated from Watch7,5 23S303 HealthKit; type/unit schema, not grants or support. */",
             "final class NativeHealthTypeCatalog {",
             "    static final String BUILD=\"23S303\";",
             f"    static final String IMAGE_SHA256=\"{sha}\";",
             "    record Definition(int code,String identifier,String typeClass,String sampleClass,String canonicalUnit) {",
             "        boolean quantity() { return \"HKQuantityType\".equals(typeClass); }",
             "        boolean category() { return \"HKCategoryType\".equals(typeClass); }",
             "    }", "    private NativeHealthTypeCatalog() { }",
             "    /** Preserve full-width code; holes/foreign builds are unknown, never narrowed or defaulted. */",
             "    static Definition lookup(String build,Long code) {",
             "        if(!BUILD.equals(build) || code==null || code<0 || code>=TYPES.length)return null;",
             "        return TYPES[code.intValue()];", "    }",
             f"    private static final Definition[] TYPES=new Definition[{COUNT}];", "    static {"]
    for code, identifier, type_class, sample_class, unit, _, _ in entries:
        if identifier is not None:
            values = ",".join(literal(v) for v in (identifier, type_class, sample_class, unit))
            lines.append(f"        TYPES[{code}]=new Definition({code},{values});")
    lines.extend(["    }", "}", ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--table", type=Path, required=True)
    parser.add_argument("--java", type=Path)
    args = parser.parse_args()
    sha, entries = extract(args.image)
    if sha != args.expected_sha256:
        raise ValueError("Image hash does not match verified build")
    header = "code\tidentifier\ttype_class\tsample_class\tcanonical_unit\tparent_pointer_raw\tflags_raw\n"
    table = header + "".join("\t".join("<nil>" if v is None else str(v) for v in row[:5])
                           + f"\t0x{row[5]:x}\t0x{row[6]:04x}\n" for row in entries)
    args.table.write_text(table, encoding="utf-8")
    if args.java:
        args.java.write_text(java(sha, entries), encoding="utf-8")
    print(f"entries={len(entries)} defined={sum(e[1] is not None for e in entries)} sha256={sha}")


if __name__ == "__main__":
    main()
