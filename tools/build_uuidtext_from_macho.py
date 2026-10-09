#!/usr/bin/env python3
"""Build a derived UUIDText string table from an exact, UUID-verified Mach-O.

This is decoder metadata, not recovered Watch evidence. Only actual C string
sections are copied; format offsets remain relative to the Mach-O text base.
The output must live separately from the unmodified recovered logarchive.
"""
import argparse
from pathlib import Path
import os
import struct
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--expected-uuid", required=True)
    parser.add_argument("--image-path", required=True)
    args = parser.parse_args()
    data = args.image.read_bytes()
    if struct.unpack_from("<I", data)[0] != 0xfeedfacf:
        raise ValueError("Expected a thin little-endian 64-bit Mach-O")
    commands = struct.unpack_from("<I", data, 16)[0]
    offset = 32
    image_uuid = None
    base = None
    sections = []
    for _ in range(commands):
        command, size = struct.unpack_from("<II", data, offset)
        if size < 8 or offset + size > len(data):
            raise ValueError("Invalid load command bounds")
        if command == 0x1b:
            image_uuid = uuid.UUID(bytes=data[offset + 8:offset + 24])
        if command == 0x19:
            name = data[offset + 8:offset + 24].rstrip(b"\0")
            if name == b"__TEXT":
                base = struct.unpack_from("<Q", data, offset + 24)[0]
                count = struct.unpack_from("<I", data, offset + 64)[0]
                if 72 + count * 80 > size:
                    raise ValueError("Invalid section bounds")
                for index in range(count):
                    section = offset + 72 + index * 80
                    section_name = data[section:section + 16].rstrip(b"\0")
                    address, length, file_offset = struct.unpack_from("<QQI", data, section + 32)
                    flags = struct.unpack_from("<I", data, section + 64)[0]
                    if flags & 0xff == 2 or section_name == b"__oslogstring":
                        if file_offset + length > len(data):
                            raise ValueError("Truncated string section")
                        sections.append((address, data[file_offset:file_offset + length]))
        offset += size
    if image_uuid != uuid.UUID(args.expected_uuid):
        raise ValueError("Mach-O UUID does not match observed Watch image")
    if base is None or not sections:
        raise ValueError("No text string sections")
    descriptors = b"".join(struct.pack("<II", address - base, len(content))
                           for address, content in sections)
    result = (struct.pack("<IIII", 0x66778899, 2, 1, len(sections)) + descriptors
              + b"".join(content for _, content in sections)
              + args.image_path.encode("utf-8") + b"\0")
    os.umask(0o077)
    key = image_uuid.hex.upper()
    directory = args.archive / key[:2]
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (directory / key[2:]).open("xb") as output:
        output.write(result)
    print(f"Derived UUIDText: UUID={image_uuid}, sections={len(sections)}, bytes={len(result)}")


if __name__ == "__main__":
    main()
