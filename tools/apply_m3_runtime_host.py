#!/usr/bin/env python3
from __future__ import annotations
import os
import sys
from pathlib import Path

ALLOWED = {
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
    "app/src/main/java/com/ksp/cryptobot/service/BootReceiver.kt",
    "app/src/main/java/com/ksp/cryptobot/service/RuntimeHostStateStore.kt",
    "app/src/main/java/com/ksp/cryptobot/service/RuntimeConnectivityMonitor.kt",
    "app/src/main/java/com/ksp/cryptobot/service/RuntimeHostHealthInspector.kt",
}

FILES = {}

def load_payload():
    base = Path(__file__).resolve().parent / "m3_payload"
    for rel in ALLOWED:
        payload = base / rel
        if not payload.exists():
            raise SystemExit(f"Missing M3 payload: {rel}")
        FILES[rel] = payload.read_text(encoding="utf-8")

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists() or not (repo / "app").exists():
        raise SystemExit("Not a Trading-Station checkout")

    status = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if status:
        raise SystemExit("Refusing to patch dirty app/ tree:\n" + status)

    load_payload()
    for rel, content in FILES.items():
        path = repo / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content.rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    changed = os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines()
    unexpected = sorted(set(changed) - ALLOWED)
    if unexpected:
        raise SystemExit("Unexpected M3 file changes: " + ", ".join(unexpected))

    missing = sorted(ALLOWED - set(changed))
    # New files appear in git status but not git diff --name-only until staged.
    untracked = os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines()
    changed_all = set(changed) | set(untracked)
    missing = sorted(ALLOWED - changed_all)
    if missing:
        raise SystemExit("M3 expected files did not change/materialize: " + ", ".join(missing))

    print("PASS | M3 runtime host patch applied only to allowed Android host files.")

if __name__ == "__main__":
    main()
