#!/usr/bin/env python3
"""Inspect an incomplete diagnostic gzip/tar without claiming a complete archive.

Optional recovery handles the observed 326 storage mistakes: 4000 raw first
bytes, then BE64 offsets with raw or separately gzip-compressed 4000-byte chunks.
Every offset and compressed member is validated before recovery. Only fully received
regular members are extracted into a private output directory; symlinks and
archive paths are never used as output paths.
"""
import argparse
import io
import json
import os
from pathlib import Path
import tarfile
import zlib


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--recover-326", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    data = args.input.read_bytes()
    if args.recover_326:
        corrected = bytearray(data[:4000])
        pos = 4000
        while pos + 8 <= len(data):
            offset = int.from_bytes(data[pos:pos + 8], "big")
            if offset != len(corrected):
                print(f"Recovery stops before unverified block at stored byte {pos}")
                break
            pos += 8
            if data[pos:pos + 3] == b"\x1f\x8b\x08":
                chunk_inflater = zlib.decompressobj(31)
                chunk = chunk_inflater.decompress(data[pos:], 4001)
                if not chunk_inflater.eof or len(chunk) != 4000:
                    break
                pos += len(data[pos:]) - len(chunk_inflater.unused_data)
            else:
                if pos + 4000 > len(data):
                    break
                chunk = data[pos:pos + 4000]
                pos += 4000
            corrected.extend(chunk)
        data = bytes(corrected)
    inflater = zlib.decompressobj(31)
    plain = inflater.decompress(data)
    members = []
    truncated = False
    try:
        with tarfile.open(fileobj=io.BytesIO(plain), mode="r|") as archive:
            for member in archive:
                entry = {"name": member.name, "bytes": member.size, "regular": member.isfile()}
                members.append(entry)
                if not member.isfile():
                    continue
                stream = archive.extractfile(member)
                try:
                    content = stream.read()
                except tarfile.ReadError:
                    entry["complete"] = False
                    truncated = True
                    break
                entry["complete"] = len(content) == member.size
                if not entry["complete"]:
                    truncated = True
                    break
                # Numeric names prevent traversal and basename collisions.
                target = args.output / f"member-{len(members):05d}.bin"
                target.write_bytes(content)
                entry["saved"] = target.name
    except (tarfile.ReadError, EOFError):
        truncated = True
    manifest = {"input_bytes": args.input.stat().st_size, "gzip_bytes": len(data),
                "inflated_bytes": len(plain), "gzip_complete": inflater.eof,
                "tar_truncated": truncated, "members": members}
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps({k: v for k, v in manifest.items() if k != "members"}))
    print(f"members={len(members)}; only complete regular entries extracted")


if __name__ == "__main__":
    main()
