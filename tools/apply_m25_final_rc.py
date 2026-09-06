#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
payload = root / ".m25" / "payload"
if not payload.is_dir():
    raise SystemExit("ERROR: .m25/payload is missing; extract the M25 package at repository root.")

# M24.1 prerequisite marker. Never apply M25 to an older branch silently.
m241 = root / "app/src/main/assets/cloudshare_setup/m24_1_warmup_evidence_semantics.md"
if not m241.is_file():
    raise SystemExit("ERROR: merged M24.1 prerequisite is missing.")

for src in payload.rglob("*"):
    if src.is_dir():
        continue
    rel = src.relative_to(payload)
    dst = root / rel
    if dst.exists():
        raise SystemExit(f"ERROR: M25 refuses to overwrite existing payload target: {rel}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    print(f"ADD {rel}")

canonical = root / ".github/workflows/android-canonical-build.yml"
text = canonical.read_text(encoding="utf-8")
old = "roomSchema=11"
new = "roomSchema=12"
count = text.count(old)
if count != 1:
    raise SystemExit(f"ERROR: expected exactly one {old!r} in canonical workflow, found {count}.")
if new in text:
    raise SystemExit("ERROR: canonical workflow already contains roomSchema=12 before controlled replacement.")
canonical.write_text(text.replace(old, new), encoding="utf-8", newline="\n")
print("PATCH .github/workflows/android-canonical-build.yml roomSchema=11 -> roomSchema=12")
print("M25 controlled application complete.")
