#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
payload = root / ".m25" / "payload"
if not payload.is_dir():
    raise SystemExit("ERROR: .m25/payload is missing; bootstrap files are not present on main.")

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

print("M25 controlled app-only application complete.")
