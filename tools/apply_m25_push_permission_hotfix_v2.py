#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
canonical = root / ".github/workflows/android-canonical-build.yml"
if not canonical.is_file():
    raise SystemExit("ERROR: run from Trading-Station repository root.")

text = canonical.read_text(encoding="utf-8")
if "roomSchema=12" in text and "roomSchema=11" not in text:
    print("Canonical Room identity already fixed: roomSchema=12")
    raise SystemExit(0)

count = text.count("roomSchema=11")
if count != 1:
    raise SystemExit(f"ERROR: expected exactly one roomSchema=11 marker, found {count}.")

canonical.write_text(
    text.replace("roomSchema=11", "roomSchema=12"),
    encoding="utf-8",
    newline="\n"
)

verify = canonical.read_text(encoding="utf-8")
if "roomSchema=12" not in verify or "roomSchema=11" in verify:
    raise SystemExit("ERROR: canonical Room identity patch did not verify.")

print("PATCHED .github/workflows/android-canonical-build.yml: roomSchema=11 -> roomSchema=12")
print("Now commit this hotfix on main, then rerun M25 Final RC Burn-In Controlled LIVE.")
