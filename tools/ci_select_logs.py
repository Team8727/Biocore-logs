#!/usr/bin/env python3
"""Select new raw logs for the ingest workflow.

Reads the processed-hash store, scans the checked-out ``logs`` branch, and
writes a TSV of ``input_path<TAB>raw_url`` for logs that still need parsing.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import lzma
import shutil
from pathlib import Path

PARSER_VERSION = 1


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_processed(path: Path, parser_version: int) -> set:
    if not path.exists():
        return set()
    with path.open("r", encoding="utf-8") as handle:
        store = json.load(handle)
    if int(store.get("parser_version", -1)) != parser_version:
        return set()
    return set(store.get("logs", {}).keys())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--logs-worktree", required=True, type=Path)
    parser.add_argument("--processed", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--raw-base-url", required=True)
    parser.add_argument("--parser-version", type=int, default=PARSER_VERSION)
    args = parser.parse_args()

    processed = load_processed(args.processed, args.parser_version)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    args.manifest.parent.mkdir(parents=True, exist_ok=True)

    candidates = sorted(args.logs_worktree.rglob("*.wpilog")) + sorted(
        args.logs_worktree.rglob("*.wpilog.xz")
    )
    rows = []
    seen = set()
    for source in candidates:
        if source.suffix == ".xz":
            data = lzma.decompress(source.read_bytes())
            name = source.name[:-3]
        else:
            data = source.read_bytes()
            name = source.name

        digest = sha256_bytes(data)
        if digest in processed or digest in seen:
            continue
        seen.add(digest)

        destination = args.out_dir / name
        if destination.exists():
            destination = args.out_dir / f"{digest[:8]}-{name}"
        destination.write_bytes(data)
        relative = source.relative_to(args.logs_worktree).as_posix()
        raw_url = args.raw_base_url.rstrip("/") + "/" + relative
        rows.append((str(destination), raw_url))

    with args.manifest.open("w", encoding="utf-8") as handle:
        for input_path, raw_url in rows:
            handle.write(f"{input_path}\t{raw_url}\n")

    print(f"Scanned {len(candidates)} log file(s); selected {len(rows)} new log(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
