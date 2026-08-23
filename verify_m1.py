#!/usr/bin/env python3
from __future__ import annotations

import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit("FAIL | " + message)


def main() -> None:
    if len(sys.argv) != 2:
        fail("Usage: python verify_m1.py <path-to-Trading-Station>")

    repo = Path(sys.argv[1]).resolve()
    workflow = repo / ".github" / "workflows" / "android-v4-build.yml"
    if not workflow.exists():
        fail(f"Missing workflow: {workflow}")

    text = workflow.read_text(encoding="utf-8")

    checks = {
        "workflow identity is v4.0.7": "CTS_VERSION_NAME: 4.0.7" in text,
        "workflow versionCode is 112": "CTS_VERSION_CODE: 112" in text,
        "identity step reads CTS_VERSION_NAME": 'os.environ["CTS_VERSION_NAME"]' in text,
        "identity step reads CTS_VERSION_CODE": 'os.environ["CTS_VERSION_CODE"]' in text,
        "APK check uses CTS_VERSION_NAME": "versionName='$CTS_VERSION_NAME'" in text,
        "APK check uses CTS_VERSION_CODE": "versionCode='$CTS_VERSION_CODE'" in text,
        "artifact name uses canonical version": "CryptoTradeStation-v${{ env.CTS_VERSION_NAME }}" in text,
        "old identity assignment removed": 'version_name = "4.0.6"' not in text,
        "old code assignment removed": "version_code = 111" not in text,
        "old APK version check removed": "versionName='4.0.6'" not in text,
        "old APK code check removed": "versionCode='111'" not in text,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        fail("Verification failures: " + ", ".join(failed))

    print("")
    print("PASS | Milestone 1 baseline identity is internally consistent.")


if __name__ == "__main__":
    main()
