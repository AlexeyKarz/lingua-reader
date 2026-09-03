#!/usr/bin/env python3
import argparse
import json
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any, Optional


SCRIPTS_DIR = Path(__file__).resolve().parent
ADD_BOOK = SCRIPTS_DIR / "add_book.py"


VALUE_FLAGS = {
    "work": "--work",
    "year": "--year",
    "author": "--author",
    "description": "--description",
    "original_lang": "--original-lang",
    "api_base_url": "--api-base-url",
    "admin_token": "--admin-token",
    "poll_interval_sec": "--poll-interval-sec",
    "ingest_timeout_sec": "--ingest-timeout-sec",
    "method": "--method",
    "align_src": "--align-src",
    "align_tgt": "--align-tgt",
    "aligner": "--aligner",
    "threshold": "--threshold",
    "window": "--window",
    "dictionary_dir": "--dictionary-dir",
    "dictionary_max_items": "--dictionary-max-items",
    "dictionary_limit": "--dictionary-limit",
}

BOOL_FLAGS = {
    "reset_existing": ("--reset-existing", None),
    "dictionary_auto": ("--dictionary-auto", "--no-dictionary-auto"),
    "dictionary_body_only": ("--dictionary-body-only", "--no-dictionary-body-only"),
    "dictionary_include_existing": ("--dictionary-include-existing", None),
    "dictionary_skip_missing": ("--dictionary-skip-missing", "--no-dictionary-skip-missing"),
}


def load_manifest(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        raise ValueError("Manifest root must be a JSON object")
    books = data.get("books")
    if not isinstance(books, list) or not books:
        raise ValueError("Manifest must contain a non-empty books array")
    return data


def get_value(book: dict[str, Any], defaults: dict[str, Any], key: str) -> Optional[Any]:
    if key in book:
        return book[key]
    return defaults.get(key)


def kv_spec(value: Any, field_name: str) -> str:
    if isinstance(value, str):
        return value
    if not isinstance(value, dict):
        raise ValueError(f"{field_name} entries must be objects or strings")

    parts: list[str] = []
    for raw_key, raw_value in value.items():
        if raw_value is None or raw_value == "":
            continue
        key = str(raw_key).replace("_", "-")
        text = str(raw_value)
        if "," in text:
            if '"' not in text:
                text = f'"{text}"'
            elif "'" not in text:
                text = f"'{text}'"
            else:
                raise ValueError(f"{field_name}.{raw_key} contains commas and both quote types")
        parts.append(f"{key}={text}")

    if not parts:
        raise ValueError(f"{field_name} entry cannot be empty")
    return ",".join(parts)


def append_value_flag(cmd: list[str], book: dict[str, Any], defaults: dict[str, Any], key: str) -> None:
    value = get_value(book, defaults, key)
    if value is None or value == "":
        return
    cmd.extend([VALUE_FLAGS[key], str(value)])


def append_bool_flag(cmd: list[str], book: dict[str, Any], defaults: dict[str, Any], key: str) -> None:
    value = get_value(book, defaults, key)
    if value is None:
        return
    true_flag, false_flag = BOOL_FLAGS[key]
    if isinstance(value, str):
        token = value.strip().lower()
        if token in {"1", "true", "t", "yes", "y", "on"}:
            enabled = True
        elif token in {"0", "false", "f", "no", "n", "off"}:
            enabled = False
        else:
            raise ValueError(f"{key} must be a boolean")
    else:
        enabled = bool(value)

    if enabled:
        cmd.append(true_flag)
    elif false_flag:
        cmd.append(false_flag)


def build_command(book: dict[str, Any], defaults: dict[str, Any]) -> list[str]:
    if not isinstance(book, dict):
        raise ValueError("Each book entry must be a JSON object")
    if not get_value(book, defaults, "work"):
        raise ValueError("Each book needs work")

    cmd = [sys.executable, str(ADD_BOOK)]
    for key in VALUE_FLAGS:
        append_value_flag(cmd, book, defaults, key)
    for key in BOOL_FLAGS:
        append_bool_flag(cmd, book, defaults, key)

    editions = get_value(book, defaults, "editions")
    if not isinstance(editions, list) or not editions:
        raise ValueError(f"Book '{get_value(book, defaults, 'work')}' needs a non-empty editions array")
    for edition in editions:
        cmd.extend(["--edition", kv_spec(edition, "editions")])

    body_starts = get_value(book, defaults, "body_start") or []
    if not isinstance(body_starts, list):
        raise ValueError(f"Book '{get_value(book, defaults, 'work')}' body_start must be an array")
    for body_start in body_starts:
        cmd.extend(["--body-start", kv_spec(body_start, "body_start")])

    dictionaries = get_value(book, defaults, "dictionary") or []
    if not isinstance(dictionaries, list):
        raise ValueError(f"Book '{get_value(book, defaults, 'work')}' dictionary must be an array")
    for dictionary in dictionaries:
        cmd.extend(["--dictionary", kv_spec(dictionary, "dictionary")])

    return cmd


def main() -> int:
    parser = argparse.ArgumentParser(description="Run add_book.py for every book in a JSON manifest.")
    parser.add_argument("manifest", help="Path to a JSON ingestion manifest")
    parser.add_argument("--dry-run", action="store_true", help="Print commands without running them")
    args = parser.parse_args()

    manifest_path = Path(args.manifest)
    if not manifest_path.exists():
        print(f"Manifest not found: {manifest_path}", file=sys.stderr)
        return 2

    try:
        manifest = load_manifest(manifest_path)
        defaults = manifest.get("defaults") or {}
        if not isinstance(defaults, dict):
            raise ValueError("defaults must be a JSON object when provided")

        for index, book in enumerate(manifest["books"], start=1):
            cmd = build_command(book, defaults)
            print(f"[{index}/{len(manifest['books'])}] {shlex.join(cmd)}")
            if args.dry_run:
                continue
            result = subprocess.run(cmd, cwd=SCRIPTS_DIR)
            if result.returncode != 0:
                return result.returncode
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
