#!/usr/bin/env python3
"""Generate a deterministic WCX APK size report and enforce byte budgets."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


COMMON_ANDROID_SDK_ROOTS = (Path("/opt/android-sdk"),)


@dataclass(frozen=True)
class Budget:
    apk_bytes: int
    dex_bytes: int
    native_bytes: int

    @classmethod
    def from_json(cls, path: Path) -> "Budget":
        values = json.loads(path.read_text(encoding="utf-8"))
        return cls(
            apk_bytes=int(values["apk_bytes"]),
            dex_bytes=int(values["dex_bytes"]),
            native_bytes=int(values["native_bytes"]),
        )


def _group(name: str) -> str:
    if re.fullmatch(r"classes(?:\d+)?\.dex", name):
        return "dex"
    if name.startswith("lib/") and name.endswith(".so"):
        return "native"
    if name.startswith("assets/"):
        return "assets"
    if name == "resources.arsc" or name.startswith("res/"):
        return "resources"
    if name.startswith("META-INF/"):
        return "meta_inf"
    return "other"


def collect_zip_groups(apk: Path) -> dict:
    groups: dict[str, dict[str, int]] = {}
    entries: list[dict[str, int | str | bool]] = []
    with zipfile.ZipFile(apk) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            group = _group(info.filename)
            totals = groups.setdefault(
                group,
                {"raw": 0, "compressed": 0, "count": 0},
            )
            totals["raw"] += info.file_size
            totals["compressed"] += info.compress_size
            totals["count"] += 1
            entries.append(
                {
                    "name": info.filename,
                    "raw": info.file_size,
                    "compressed": info.compress_size,
                    "stored": info.compress_type == zipfile.ZIP_STORED,
                }
            )
    entries.sort(key=lambda item: (-int(item["raw"]), str(item["name"])))
    return {
        "apk_bytes": apk.stat().st_size,
        "groups": dict(sorted(groups.items())),
        "largest_entries": entries[:100],
    }


def check_budget(report: dict, budget: Budget) -> list[str]:
    actual = {
        "apk_bytes": int(report["apk_bytes"]),
        "dex_bytes": int(report["groups"].get("dex", {}).get("raw", 0)),
        "native_bytes": int(report["groups"].get("native", {}).get("raw", 0)),
    }
    limits = asdict(budget)
    return [
        f"{name}: actual={actual[name]} limit={limit}"
        for name, limit in limits.items()
        if actual[name] > limit
    ]


def parse_apkanalyzer_packages(output: str) -> list[dict[str, object]]:
    packages: list[dict[str, object]] = []
    for line in output.splitlines():
        parts = line.split()
        if len(parts) < 3 or parts[0] != "P" or parts[-1] == "<TOTAL>":
            continue
        metrics = [
            int(token.replace(",", ""))
            for token in parts[1:-1]
            if re.fullmatch(r"[0-9][0-9,]*", token)
        ]
        packages.append(
            {
                "name": parts[-1],
                "bytecode_bytes": metrics[-1] if metrics else 0,
                "metrics": metrics,
            }
        )
    packages.sort(
        key=lambda item: (-int(item["bytecode_bytes"]), str(item["name"]))
    )
    return packages


def parse_llvm_size_sections(output: str) -> dict[str, int]:
    sections: dict[str, int] = {}
    for line in output.splitlines():
        parts = line.split()
        if (
            len(parts) < 2
            or parts[0] in {"section", "Total"}
            or not parts[1].isdigit()
        ):
            continue
        sections[parts[0]] = int(parts[1])
    return dict(sorted(sections.items()))


def _run(command: list[str]) -> str:
    completed = subprocess.run(
        command,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return completed.stdout


def _collect_dex_packages(
    apk: Path,
    mapping: Path,
    apkanalyzer: Path,
) -> list[dict[str, object]]:
    output = _run(
        [
            str(apkanalyzer),
            "dex",
            "packages",
            "--defined-only",
            "--proguard-folder",
            str(mapping),
            str(apk),
        ]
    )
    return parse_apkanalyzer_packages(output)


def _collect_native_sections(
    apk: Path,
    llvm_size: Path,
) -> dict[str, dict[str, int]]:
    sections: dict[str, dict[str, int]] = {}
    with tempfile.TemporaryDirectory(prefix="wcx-apk-report-") as temp_dir:
        root = Path(temp_dir)
        with zipfile.ZipFile(apk) as archive:
            native_entries = sorted(
                entry.filename
                for entry in archive.infolist()
                if entry.filename.startswith("lib/")
                and entry.filename.endswith(".so")
            )
            for name in native_entries:
                extracted = root / name
                extracted.parent.mkdir(parents=True, exist_ok=True)
                extracted.write_bytes(archive.read(name))
                sections[name] = parse_llvm_size_sections(
                    _run([str(llvm_size), "-A", str(extracted)])
                )
    return sections


def build_report(
    apk: Path,
    mapping: Path | None = None,
    apkanalyzer: Path | None = None,
    llvm_size: Path | None = None,
) -> dict:
    report = collect_zip_groups(apk)
    if mapping is not None and apkanalyzer is not None:
        report["dex_packages"] = _collect_dex_packages(
            apk,
            mapping,
            apkanalyzer,
        )
    if llvm_size is not None:
        report["native_sections"] = _collect_native_sections(apk, llvm_size)
    return report


def _resolve_tool(explicit: str | None, name: str) -> Path | None:
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise FileNotFoundError(f"tool not found: {path}")
        return path
    found = shutil.which(name)
    if found:
        return Path(found)
    if name == "apkanalyzer":
        sdk_roots = [
            Path(sdk)
            for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME")
            if (sdk := os.environ.get(variable))
        ]
        sdk_roots.extend(COMMON_ANDROID_SDK_ROOTS)
        for sdk_root in sdk_roots:
            candidate = sdk_root / "cmdline-tools/latest/bin/apkanalyzer"
            if candidate.is_file():
                return candidate
    return None


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--budget", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--mapping", type=Path)
    parser.add_argument("--apkanalyzer")
    parser.add_argument("--llvm-size")
    return parser


def main(argv: Iterable[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    apk = args.apk.resolve()
    if not apk.is_file():
        raise FileNotFoundError(f"APK not found: {apk}")
    mapping = args.mapping.resolve() if args.mapping else None
    if mapping is not None and not mapping.is_dir():
        raise FileNotFoundError(f"mapping directory not found: {mapping}")

    report = build_report(
        apk,
        mapping,
        _resolve_tool(args.apkanalyzer, "apkanalyzer"),
        _resolve_tool(args.llvm_size, "llvm-size"),
    )
    failures: list[str] = []
    if args.budget:
        budget = Budget.from_json(args.budget)
        report["budget"] = asdict(budget)
        failures = check_budget(report, budget)
        report["budget_failures"] = failures

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    for failure in failures:
        print(f"budget exceeded: {failure}")
    return 2 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
