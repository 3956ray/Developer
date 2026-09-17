#!/usr/bin/env python3
"""Verify a local evidence archive; optionally restore indexed files. No network or execution."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import tarfile


def safe_path(root, relative):
    path = pathlib.PurePosixPath(relative)
    if path.is_absolute() or not path.parts or ".." in path.parts:
        raise ValueError("Unsafe manifest path")
    target = root.joinpath(*path.parts)
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError("Destination escapes output directory")
    return target


def file_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.verify_only == bool(args.output):
        parser.error("Choose exactly --verify-only or --output")
    output = args.output.resolve() if args.output else None
    manifest = None
    expected = {}
    verified = set()
    with tarfile.open(args.archive, "r|gz") as archive:
        for entry in archive:
            if not entry.isfile():
                raise ValueError("Archive contains a non-regular entry")
            source = archive.extractfile(entry)
            if entry.name == "manifest.json":
                if manifest is not None or entry.size > 16 * 1024 * 1024:
                    raise ValueError("Invalid manifest")
                manifest = json.load(source)
                for item in manifest["files"]:
                    safe_path(output or pathlib.Path.cwd(), item["path"])
                    sha, size = item["sha256"], item["size"]
                    if not re.fullmatch(r"[0-9a-f]{64}", sha) or size < 0:
                        raise ValueError("Invalid object identity")
                    if sha in expected and expected[sha] != size:
                        raise ValueError("Conflicting object size")
                    expected[sha] = size
                continue
            if manifest is None or not re.fullmatch(r"objects/[0-9a-f]{64}", entry.name):
                raise ValueError("Unexpected archive entry")
            sha = entry.name.split("/")[1]
            if sha in verified or expected.get(sha) != entry.size:
                raise ValueError("Unexpected object size or duplicate")
            digest = hashlib.sha256()
            destination = safe_path(output, ".objects/" + sha) if output else None
            handle = None
            try:
                if destination:
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    # Do not overwrite an unrelated pre-existing file.
                    if destination.exists():
                        if file_hash(destination) != sha:
                            raise ValueError("Existing object differs")
                    else:
                        handle = destination.open("xb")
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
                    if handle:
                        handle.write(chunk)
            finally:
                if handle:
                    handle.close()
            if digest.hexdigest() != sha:
                raise ValueError("Object SHA256 mismatch")
            verified.add(sha)
    if manifest is None or verified != set(expected):
        raise ValueError("Missing manifest or objects")
    if output:
        for item in manifest["files"]:
            destination = safe_path(output, item["path"])
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if file_hash(destination) != item["sha256"]:
                    raise ValueError("Existing destination differs: " + item["path"])
            else:
                shutil.copyfile(output / ".objects" / item["sha256"], destination)
    print(json.dumps({"verified_objects": len(verified), "logical_files": len(manifest["files"]),
                      "restored": output is not None, "excluded": len(manifest["excluded"])}))


if __name__ == "__main__":
    main()
