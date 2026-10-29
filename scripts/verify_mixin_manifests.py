#!/usr/bin/env python3
"""Validate Mixin manifests against source declarations and optional built JARs."""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path


MIXIN_DECLARATION = re.compile(
    r"@Mixin\s*(?:\([^)]*\)\s*)?(?:public\s+|protected\s+|private\s+|abstract\s+|final\s+)*"
    r"(?:class|interface|record|enum)\s+([A-Za-z_$][\w$]*)",
    re.DOTALL,
)


def fail(message: str) -> None:
    raise SystemExit(f"Mixin manifest verification failed: {message}")


def load_manifest(root: Path, relative: str, section: str) -> tuple[str, str, list[str]]:
    path = root / relative
    if not path.is_file():
        fail(f"missing manifest: {relative}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"invalid manifest {relative}: {exc}")
    package = data.get("package")
    entries = data.get(section)
    if not isinstance(package, str) or not package:
        fail(f"{relative} has no non-empty package")
    if not isinstance(entries, list) or not entries or any(not isinstance(x, str) or not x for x in entries):
        fail(f"{relative} has invalid or empty {section} list")
    if len(entries) != len(set(entries)):
        fail(f"{relative} contains duplicate {section} entries")
    return relative, package, entries


def source_mixins(root: Path, package: str) -> dict[str, Path]:
    package_path = Path(*package.split("."))
    source_dir = root / "src" / "main" / "java" / package_path
    if not source_dir.is_dir():
        fail(f"source package directory is missing: {source_dir}")
    found: dict[str, Path] = {}
    for path in sorted(source_dir.rglob("*.java")):
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            fail(f"cannot read source {path}: {exc}")
        match = MIXIN_DECLARATION.search(text)
        if match:
            name = match.group(1)
            if name in found:
                fail(f"duplicate source Mixin {name}: {found[name]} and {path}")
            found[name] = path
    return found


def verify_manifest(root: Path, relative: str, section: str) -> set[str]:
    _, package, entries = load_manifest(root, relative, section)
    source = source_mixins(root, package)
    listed = set(entries)
    missing = sorted(set(source) - listed)
    stale = sorted(listed - set(source))
    if missing:
        details = ", ".join(f"{name} ({source[name]})" for name in missing)
        fail(f"{relative} is missing source Mixin(s): {details}")
    if stale:
        fail(f"{relative} lists non-Mixin or missing source(s): {', '.join(stale)}")
    print(f"source check passed: {relative} ({len(source)} Mixin(s) in {package})")
    return listed


def verify_jars(root: Path, jar_dir: Path, manifests: list[tuple[str, str, set[str]]]) -> None:
    jars = sorted(jar_dir.glob("*.jar"))
    if not jars:
        fail(f"no JAR files found in {jar_dir}")
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as archive:
                names = set(archive.namelist())
        except zipfile.BadZipFile as exc:
            fail(f"invalid JAR {jar}: {exc}")
        for relative, package, entries in manifests:
            manifest_name = f"{package.replace('.', '/')}/"
            for entry in entries:
                class_name = f"{manifest_name}{entry}.class"
                if class_name not in names:
                    fail(f"{jar} is missing {class_name} from {relative}")
        print(f"JAR check passed: {jar}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--jar-dir", type=Path, default=None)
    args = parser.parse_args()
    root = args.root.resolve()
    manifests = [
        ("src/main/resources/aeronauticscompatguard.mixins.json", "mixins"),
        ("src/main/resources/aeronauticscompatguard.client.mixins.json", "client"),
    ]
    checked = [(relative, load_manifest(root, relative, section)[1], verify_manifest(root, relative, section))
               for relative, section in manifests]
    if args.jar_dir is not None:
        verify_jars(root, args.jar_dir.resolve(), checked)
    print("Mixin manifest verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
