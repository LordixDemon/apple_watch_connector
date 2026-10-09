"""Deterministic tar creation and verification without extracting executable content."""
import gzip
import hashlib
import json
import os
import tarfile

PREFIX = "watch-companion/"


def normalized_mode(path):
    return 0o755 if path.is_dir() or path.stat().st_mode & 0o111 else 0o644


def archive_epoch():
    epoch = int(os.environ.get("SOURCE_DATE_EPOCH", "1577836800"))
    if not 0 <= epoch <= 0xFFFFFFFF:
        raise ValueError("SOURCE_DATE_EPOCH must fit an unsigned gzip timestamp")
    return epoch


def write_archive(archive, bundle, manifest, extras, epoch):
    with archive.open("wb") as output, gzip.GzipFile(filename="", fileobj=output, mode="wb", mtime=epoch) as compressed:
        with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as tar:
            sources = [(bundle, PREFIX + "bundle")]
            sources += [(path, PREFIX + "bundle/" + path.relative_to(bundle).as_posix())
                        for path in sorted(bundle.rglob("*"))]
            sources += [(manifest, PREFIX + "release-manifest.json")]
            sources += [(path, PREFIX + name) for name, path in sorted(extras.items())]
            for path, name in sources:
                member = tar.gettarinfo(str(path), arcname=name)
                member.uid = member.gid = 0
                member.uname = member.gname = ""
                member.mtime = epoch
                member.mode = 0o777 if path.is_symlink() else normalized_mode(path)
                member.pax_headers = {}
                if not path.is_symlink() and path.is_file():
                    # Hard-linked input files are still independent package files.
                    member.type = tarfile.REGTYPE
                    member.linkname = ""
                    member.size = path.stat().st_size
                if member.isfile():
                    with path.open("rb") as stream:
                        tar.addfile(member, stream)
                else:
                    tar.addfile(member)


def verify_archive(archive, inventory, epoch):
    expected = {PREFIX + "bundle": {"type": "directory", "mode": 0o755}}
    expected.update({PREFIX + "bundle/" + name: entry for name, entry in inventory["entries"].items()})
    expected.update({PREFIX + name: {"type": "file", "mode": mode, "sha256": digest}
                     for name, (digest, mode) in inventory["packageFiles"].items()})
    expected[PREFIX + "release-manifest.json"] = {"type": "manifest", "mode": 0o644}
    seen = set()
    with tarfile.open(archive, "r:gz") as tar:
        for member in tar:
            entry = expected.get(member.name)
            if entry is None or member.name in seen:
                raise ValueError("Unexpected or duplicate archive entry: " + member.name)
            seen.add(member.name)
            if (member.uid, member.gid, member.uname, member.gname, member.mtime, member.mode) != (0, 0, "", "", epoch, entry["mode"]):
                raise ValueError("Archive metadata mismatch: " + member.name)
            if entry["type"] == "directory":
                valid = member.isdir()
            elif entry["type"] == "symlink":
                valid = member.issym() and member.linkname == entry["target"]
            else:
                valid = member.isfile()
                if valid:
                    with tar.extractfile(member) as stream:
                        if entry["type"] == "manifest":
                            valid = member.size <= 32 * 1024 * 1024 and json.load(stream) == inventory
                        else:
                            digest = hashlib.sha256()
                            for block in iter(lambda: stream.read(1024 * 1024), b""):
                                digest.update(block)
                            valid = digest.hexdigest() == entry["sha256"]
            if not valid:
                raise ValueError("Archive content mismatch: " + member.name)
    if seen != set(expected):
        raise ValueError("Archive inventory is incomplete")
