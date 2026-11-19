#!/usr/bin/env sh
# Verify dynamically resolved classes and methods in a minified WCX APK.

set -eu

APK=
MAPPING=
CONTRACTS=
APKANALYZER=

while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk)
      APK=${2:-}
      shift 2
      ;;
    --mapping)
      MAPPING=${2:-}
      shift 2
      ;;
    --contracts)
      CONTRACTS=${2:-}
      shift 2
      ;;
    --apkanalyzer)
      APKANALYZER=${2:-}
      shift 2
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

[ -n "$APK" ] && [ -f "$APK" ] || {
  echo "APK not found: $APK" >&2
  exit 2
}
[ -n "$MAPPING" ] && [ -d "$MAPPING" ] || {
  echo "mapping directory not found: $MAPPING" >&2
  exit 2
}
[ -n "$CONTRACTS" ] && [ -f "$CONTRACTS" ] || {
  echo "contracts file not found: $CONTRACTS" >&2
  exit 2
}

if [ -z "$APKANALYZER" ]; then
  if command -v apkanalyzer >/dev/null 2>&1; then
    APKANALYZER=$(command -v apkanalyzer)
  else
    for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" /opt/android-sdk /usr/local/lib/android/sdk; do
      [ -n "$sdk" ] || continue
      if [ -x "$sdk/cmdline-tools/latest/bin/apkanalyzer" ]; then
        APKANALYZER="$sdk/cmdline-tools/latest/bin/apkanalyzer"
        break
      fi
    done
  fi
fi

[ -n "$APKANALYZER" ] && [ -x "$APKANALYZER" ] || {
  echo "apkanalyzer not found; set ANDROID_SDK_ROOT or pass --apkanalyzer" >&2
  exit 2
}

DUMP=$(mktemp)
trap 'rm -f "$DUMP"' EXIT HUP INT TERM

"$APKANALYZER" dex packages --defined-only --proguard-folder "$MAPPING" "$APK" > "$DUMP"

python3 - "$CONTRACTS" "$DUMP" "$MAPPING" <<'PY'
import re
import sys
from pathlib import Path

contracts_path = Path(sys.argv[1])
dump_path = Path(sys.argv[2])
mapping_dir = Path(sys.argv[3])
mapping_file = mapping_dir / "mapping.txt"

renamed_classes: set[str] = set()
renamed_methods: dict[str, set[str]] = {}

if mapping_file.is_file():
    mapped_class: str | None = None
    method_pattern = re.compile(
        r"^(?:\d+:\d+:)?(?P<ret>\S+)\s+(?P<sig>(?P<name>[^(\s]+)\([^)]*\))(?::\d+(?::\d+)?)?\s+->\s+(?P<dst>\S+)$"
    )
    with mapping_file.open(encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            line = raw_line.rstrip()
            if not line or line.lstrip().startswith("#"):
                continue
            if not line.startswith(" ") and " -> " in line and line.endswith(":"):
                src, dst = line[:-1].split(" -> ", 1)
                mapped_class = src.strip()
                if mapped_class != dst.strip():
                    renamed_classes.add(mapped_class)
                continue
            if mapped_class is None:
                continue
            match = method_pattern.match(line.strip())
            if not match:
                continue
            if match.group("dst") != match.group("name"):
                bucket = renamed_methods.setdefault(mapped_class, set())
                sig = match.group("sig")
                ret = match.group("ret")
                bucket.add(sig)
                bucket.add(f"{ret} {sig}")

classes: set[str] = set()
methods_by_class: dict[str, set[str]] = {}
current_class: str | None = None

with dump_path.open(encoding="utf-8", errors="replace") as handle:
    for raw_line in handle:
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("C "):
            current_class = line.split()[-1]
            classes.add(current_class)
            methods_by_class.setdefault(current_class, set())
        elif line.startswith("P "):
            current_class = None
        elif line.startswith("M ") and current_class is not None:
            parts = line.split(maxsplit=5)
            if len(parts) < 6:
                continue
            descriptor = parts[5]
            prefix = f"{current_class} "
            if not descriptor.startswith(prefix):
                continue
            member = descriptor[len(prefix) :].strip()
            bucket = methods_by_class.setdefault(current_class, set())
            bucket.add(member)
            if " " in member:
                _, without_return = member.split(" ", 1)
                bucket.add(without_return.strip())

contracts: list[str] = []
missing: list[str] = []

for raw_line in contracts_path.read_text(encoding="utf-8").splitlines():
    line = raw_line.strip()
    if not line or line.startswith("#"):
        continue
    contracts.append(line)
    fields = line.split("|")
    kind = fields[0]
    if kind == "class" and len(fields) == 2:
        class_name = fields[1]
        if class_name not in classes or class_name in renamed_classes:
            missing.append(line)
    elif kind == "method" and len(fields) == 3:
        class_name, signature = fields[1], fields[2]
        if (
            class_name not in classes
            or class_name in renamed_classes
            or signature not in methods_by_class.get(class_name, set())
            or signature in renamed_methods.get(class_name, set())
        ):
            missing.append(line)
    else:
        raise ValueError(f"invalid contract record: {line}")

for item in missing:
    print(f"missing: {item}")
print(f"contracts={len(contracts)} missing={len(missing)}")
raise SystemExit(1 if missing else 0)
PY
