#!/usr/bin/env python3
"""One-click power-log uploader for the Biocore-logs pipeline.

The uploader runs on the driver-station laptop. It pulls new WPILib
``.wpilog`` files from the roboRIO, compresses them, and pushes them to the
``logs`` branch of the logs repository. It never parses logs and never deletes
the roboRIO copy.

Python 3.9+ only; no third-party packages.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import lzma
import os
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

DEFAULT_CONFIG = Path("config/uploader.json")
STATE_VERSION = 1


def log(message: str) -> None:
    print("[upload] " + message, flush=True)


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run(command: List[str], cwd: Optional[Path] = None, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode != 0:
        raise RuntimeError(
            "Command failed: {}\nstdout: {}\nstderr: {}".format(
                " ".join(command), result.stdout.strip(), result.stderr.strip()
            )
        )
    return result


def load_config(path: Path) -> Dict:
    if not path.exists():
        raise SystemExit(
            "Missing config: {}. Copy config/uploader.example.json to config/uploader.json "
            "and fill in the team number.".format(path)
        )
    with path.open("r", encoding="utf-8") as handle:
        config = json.load(handle)

    required = ["team_number", "roborio_host", "roborio_user", "remote_log_dir", "repo_url"]
    missing = [
        key for key in required if key not in config or config[key] in (None, "")
    ]
    if missing:
        raise SystemExit("Config is missing required keys: " + ", ".join(missing))

    config.setdefault("repo_path", ".")
    config.setdefault("logs_branch", "logs")
    config.setdefault("logs_clone_dir", ".logs-branch")
    config.setdefault("queue_dir", ".upload-queue")
    config.setdefault("archive_dir", ".upload-archive")
    config.setdefault("state_file", ".upload-state.json")
    config.setdefault("max_file_mb", 50)
    config.setdefault("git_user_name", "Biocore Log Bot")
    config.setdefault("git_user_email", "logs@biocore.local")
    return config


def state_path(config: Dict) -> Path:
    return Path(config["repo_path"]) / config["state_file"]


def load_state(config: Dict) -> Dict:
    path = state_path(config)
    if not path.exists():
        return {"version": STATE_VERSION, "uploaded": {}}
    with path.open("r", encoding="utf-8") as handle:
        state = json.load(handle)
    state.setdefault("version", STATE_VERSION)
    state.setdefault("uploaded", {})
    return state


def save_state(config: Dict, state: Dict) -> None:
    path = state_path(config)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(state, handle, indent=2, sort_keys=True)
        handle.write("\n")


def pull_logs(config: Dict, destination: Path, input_dir: Optional[Path]) -> List[Path]:
    if input_dir is not None:
        if not input_dir.exists():
            raise SystemExit("Input directory does not exist: {}".format(input_dir))
        files = sorted(input_dir.glob("*.wpilog"))
        log("Using local input directory: {} ({} file(s))".format(input_dir, len(files)))
        return files

    remote = "{}@{}:{}/*.wpilog".format(
        config["roborio_user"], config["roborio_host"], config["remote_log_dir"].rstrip("/")
    )
    log("Pulling logs from {}".format(remote))
    result = run(
        [
            "scp",
            "-q",
            "-o",
            "BatchMode=yes",
            "-o",
            "StrictHostKeyChecking=accept-new",
            remote,
            str(destination),
        ],
        check=False,
    )
    if result.returncode != 0:
        log("scp failed; keeping any queued logs and exiting.")
        log(result.stderr.strip())
        return []

    files = sorted(destination.glob("*.wpilog"))
    log("Pulled {} file(s).".format(len(files)))
    return files


def compress_to_queue(source: Path, config: Dict, state: Dict) -> Tuple[bool, str]:
    digest = sha256_file(source)
    if digest in state["uploaded"]:
        return False, "already uploaded"

    max_bytes = int(config["max_file_mb"]) * 1024 * 1024
    size = source.stat().st_size
    if size > max_bytes:
        return False, "too large ({:.1f} MB > {} MB)".format(size / 1048576.0, config["max_file_mb"])

    queue_dir = Path(config["repo_path"]) / config["queue_dir"]
    queue_dir.mkdir(parents=True, exist_ok=True)
    target = queue_dir / (digest[:16] + ".wpilog.xz")
    meta_path = queue_dir / (digest[:16] + ".meta.json")

    if target.exists() and meta_path.exists():
        return True, "already queued"

    with source.open("rb") as reader, lzma.open(target, "wb", preset=6) as writer:
        shutil.copyfileobj(reader, writer)

    metadata = {
        "sha256": digest,
        "original_name": source.name,
        "source_bytes": size,
        "queued_at": utc_now(),
        "source": config.get("roborio_host", "local"),
    }
    with meta_path.open("w", encoding="utf-8") as handle:
        json.dump(metadata, handle, indent=2, sort_keys=True)
        handle.write("\n")

    return True, "queued ({:.1f} KB -> {:.1f} KB)".format(
        size / 1024.0, target.stat().st_size / 1024.0
    )


def ensure_logs_clone(config: Dict) -> Path:
    repo_path = Path(config["repo_path"]).resolve()
    clone = repo_path / config["logs_clone_dir"]
    branch = config["logs_branch"]

    if not clone.exists():
        log("Creating logs-branch clone at {}".format(clone))
        result = run(
            [
                "git",
                "clone",
                "--single-branch",
                "--branch",
                branch,
                config["repo_url"],
                str(clone),
            ],
            check=False,
        )
        if result.returncode != 0:
            log("Branch '{}' not found; creating it.".format(branch))
            run(["git", "clone", config["repo_url"], str(clone)])
            run(["git", "checkout", "--orphan", branch], cwd=clone)
            run(["git", "rm", "-rf", "--ignore-unmatch", "."], cwd=clone)
            (clone / "logs").mkdir(parents=True, exist_ok=True)
            (clone / "logs" / "README.md").write_text(
                "# Raw power logs\n\nCompressed `.wpilog.xz` files uploaded by the driver station.\n",
                encoding="utf-8",
            )
            run(["git", "add", "-A"], cwd=clone)
            run(
                [
                    "git",
                    "-c",
                    "user.name=" + config["git_user_name"],
                    "-c",
                    "user.email=" + config["git_user_email"],
                    "commit",
                    "-m",
                    "Initialize logs branch",
                ],
                cwd=clone,
            )
            run(["git", "push", "-u", "origin", branch], cwd=clone)
            return clone

    run(["git", "fetch", "origin", branch], cwd=clone)
    run(["git", "checkout", branch], cwd=clone)
    run(["git", "pull", "--ff-only", "origin", branch], cwd=clone)
    return clone


def push_queue(config: Dict, state: Dict, dry_run: bool) -> bool:
    repo_path = Path(config["repo_path"])
    queue_dir = repo_path / config["queue_dir"]
    archive_dir = repo_path / config["archive_dir"]

    queued = sorted(queue_dir.glob("*.wpilog.xz"))
    if not queued:
        log("No queued logs to upload.")
        return True

    if dry_run:
        log("Dry run: would upload {} queued file(s).".format(len(queued)))
        for path in queued:
            log("  " + path.name)
        return True

    clone = ensure_logs_clone(config)
    branch = config["logs_branch"]
    added: List[Path] = []
    processed: List[Tuple[Path, Path, Dict]] = []

    for compressed in queued:
        meta_path = compressed.with_name(compressed.stem.replace(".wpilog", "") + ".meta.json")
        if not meta_path.exists():
            log("Skipping {}: missing metadata sidecar.".format(compressed.name))
            continue
        with meta_path.open("r", encoding="utf-8") as handle:
            metadata = json.load(handle)
        processed.append((compressed, meta_path, metadata))

        day = str(metadata.get("queued_at", utc_now()))[:10]
        original = metadata.get("original_name", "log.wpilog")
        digest = str(metadata.get("sha256", ""))
        destination_dir = clone / "logs" / day / (digest[:8] or "unknown")
        destination_dir.mkdir(parents=True, exist_ok=True)
        destination = destination_dir / (original + ".xz")
        if destination.exists():
            log("Skipping {}: already on logs branch.".format(original))
            continue

        shutil.copy2(compressed, destination)
        added.append(compressed)

    if added:
        run(["git", "add", "-A"], cwd=clone)
        commit = run(["git", "diff", "--cached", "--quiet"], cwd=clone, check=False)
        if commit.returncode != 0:
            run(
                [
                    "git",
                    "-c",
                    "user.name=" + config["git_user_name"],
                    "-c",
                    "user.email=" + config["git_user_email"],
                    "commit",
                    "-m",
                    "Add power logs ({})".format(utc_now()),
                ],
                cwd=clone,
            )

            push = run(["git", "push", "origin", branch], cwd=clone, check=False)
            if push.returncode != 0:
                log("Push failed. Queued files remain and will retry next run.")
                log(push.stderr.strip())
                return False
        else:
            log("No staged changes to commit.")
    else:
        log("No new files to commit.")

    archive_dir.mkdir(parents=True, exist_ok=True)
    archived = archive_dir / utc_now()[:10]
    archived.mkdir(parents=True, exist_ok=True)
    for compressed, meta_path, metadata in processed:
        state["uploaded"][metadata["sha256"]] = {
            "uploaded_at": utc_now(),
            "original_name": metadata.get("original_name"),
            "queue_file": compressed.name,
        }
        shutil.move(str(compressed), str(archived / compressed.name))
        shutil.move(str(meta_path), str(archived / meta_path.name))

    save_state(config, state)
    log(
        "Uploaded {} new file(s); archived {} queued file(s).".format(
            len(added), len(processed)
        )
    )
    return True


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Pull and upload FRC power logs.")
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List what would be uploaded; do not compress or push.",
    )
    parser.add_argument(
        "--input-dir",
        type=Path,
        default=None,
        help="Use local .wpilog files instead of pulling from the roboRIO.",
    )
    parser.add_argument(
        "--push-only",
        action="store_true",
        help="Skip pulling and only retry the local queue.",
    )
    args = parser.parse_args(list(argv) if argv is not None else None)

    config = load_config(args.config)
    state = load_state(config)
    repo_path = Path(config["repo_path"])
    queue_dir = repo_path / config["queue_dir"]
    queue_dir.mkdir(parents=True, exist_ok=True)

    if args.dry_run:
        if not args.push_only:
            with tempfile.TemporaryDirectory(prefix="biocore-logs-") as temp:
                pulled = pull_logs(config, Path(temp), args.input_dir)
                for source in pulled:
                    digest = sha256_file(source)
                    status = (
                        "already uploaded" if digest in state["uploaded"] else "would queue"
                    )
                    log("{}: {}".format(source.name, status))
        success = push_queue(config, state, dry_run=True)
        return 0 if success else 1

    if not args.push_only:
        with tempfile.TemporaryDirectory(prefix="biocore-logs-") as temp:
            pulled = pull_logs(config, Path(temp), args.input_dir)
            queued_count = 0
            for source in pulled:
                queued, reason = compress_to_queue(source, config, state)
                if queued:
                    queued_count += 1
                log("{}: {}".format(source.name, reason))
            log("Newly queued: {}".format(queued_count))

    success = push_queue(config, state, args.dry_run)
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
