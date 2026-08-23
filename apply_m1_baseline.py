#!/usr/bin/env python3
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(message)


def require_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, found {count}.")
    return text.replace(old, new, 1)


def section(text: str, start_marker: str, end_marker: str) -> tuple[int, int, str]:
    start = text.find(start_marker)
    if start < 0:
        fail(f"Could not locate workflow section: {start_marker}")
    end = text.find(end_marker, start)
    if end < 0:
        fail(f"Could not locate workflow section terminator: {end_marker}")
    return start, end, text[start:end]


def main() -> None:
    if len(sys.argv) != 2:
        fail("Usage: python apply_m1_baseline.py <path-to-Trading-Station>")

    repo = Path(sys.argv[1]).resolve()
    workflow = repo / ".github" / "workflows" / "android-v4-build.yml"
    if not workflow.exists():
        fail(f"Workflow not found: {workflow}")

    original = workflow.read_text(encoding="utf-8")
    text = original

    if "CTS_VERSION_NAME: 4.0.7" not in text or "CTS_VERSION_CODE: 112" not in text:
        fail(
            "Expected current canonical identity CTS_VERSION_NAME=4.0.7 and "
            "CTS_VERSION_CODE=112 was not found. Refusing to patch an unknown revision."
        )

    backup_dir = repo / ".cts-m1-backup"
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup = backup_dir / "android-v4-build.yml"
    if not backup.exists():
        shutil.copy2(workflow, backup)

    start, end, block = section(
        text,
        "      - name: Set update build identity\n",
        "      - name: Add JUnit 4 test dependency when missing\n",
    )

    block = require_once(
        block,
        '''          from pathlib import Path
          import re

          version_name = "4.0.6"
          version_code = 111
''',
        '''          from pathlib import Path
          import os
          import re

          version_name = os.environ["CTS_VERSION_NAME"]
          version_code = int(os.environ["CTS_VERSION_CODE"])
''',
        "Set update build identity environment wiring",
    )

    block = require_once(
        block,
        '''              text = main.read_text(encoding="utf-8").replace("v4.0.0 CTS", "v4.0.6 CTS")
              main.write_text(text, encoding="utf-8")
''',
        '''              text = main.read_text(encoding="utf-8")
              text = re.sub(r"v4\\.0\\.\\d+ CTS", f"v{version_name} CTS", text)
              main.write_text(text, encoding="utf-8")
''',
        "MainActivity version label wiring",
    )
    text = text[:start] + block + text[end:]

    start, end, block = section(
        text,
        "      - name: Validate migrated update source\n",
        "      - name: Prepare release signing\n",
    )

    block = require_once(
        block,
        '''          from pathlib import Path
          import re
          gradle = Path('app/build.gradle.kts').read_text(encoding='utf-8')
''',
        '''          from pathlib import Path
          import os
          import re

          expected_name = os.environ["CTS_VERSION_NAME"]
          expected_code = int(os.environ["CTS_VERSION_CODE"])

          gradle = Path('app/build.gradle.kts').read_text(encoding='utf-8')
''',
        "Validation environment wiring",
    )

    block = require_once(
        block,
        '''              'versionName 4.0.6': 'versionName = "4.0.6"' in gradle,
              'versionCode 111': 'versionCode = 111' in gradle,
              'V4ReleaseInfo 4.0.6': 'VERSION_NAME = "4.0.6"' in release and 'VERSION_CODE = 111' in release,
''',
        '''              f'versionName {expected_name}': f'versionName = "{expected_name}"' in gradle,
              f'versionCode {expected_code}': f'versionCode = {expected_code}' in gradle,
              'V4ReleaseInfo identity': (
                  f'VERSION_NAME = "{expected_name}"' in release
                  and f'VERSION_CODE = {expected_code}' in release
              ),
''',
        "Validation identity checks",
    )
    text = text[:start] + block + text[end:]

    text = require_once(
        text,
        '''          echo "$PACKAGE_LINE" | grep -q "versionCode='111'" || { echo "Wrong versionCode in built APK" >&2; exit 1; }
          echo "$PACKAGE_LINE" | grep -q "versionName='4.0.6'" || { echo "Wrong versionName in built APK" >&2; exit 1; }
''',
        '''          echo "$PACKAGE_LINE" | grep -q "versionCode='$CTS_VERSION_CODE'" || { echo "Wrong versionCode in built APK" >&2; exit 1; }
          echo "$PACKAGE_LINE" | grep -q "versionName='$CTS_VERSION_NAME'" || { echo "Wrong versionName in built APK" >&2; exit 1; }
''',
        "APK identity checks",
    )

    text = require_once(
        text,
        "          name: CryptoTradeStation-v4.0.6-${{ env.CTS_BUILD_TYPE }}-update-apk\n",
        "          name: CryptoTradeStation-v${{ env.CTS_VERSION_NAME }}-${{ env.CTS_BUILD_TYPE }}-update-apk\n",
        "APK artifact name",
    )

    critical_start = text.find("      - name: Set update build identity\n")
    if critical_start < 0:
        fail("Internal verification failed: identity step missing.")
    critical_tail = text[critical_start:]
    forbidden = [
        'version_name = "4.0.6"',
        "version_code = 111",
        "versionCode='111'",
        "versionName='4.0.6'",
    ]
    remaining = [item for item in forbidden if item in critical_tail]
    if remaining:
        fail("Internal verification failed. Old identity remains: " + ", ".join(remaining))

    workflow.write_text(text, encoding="utf-8")

    print("PASS | Crypto TradeStation Milestone 1 baseline fix applied.")
    print(f"Workflow: {workflow}")
    print(f"Backup:   {backup}")
    print("Identity: CTS_VERSION_NAME=4.0.7 / CTS_VERSION_CODE=112")
    print("Trading behavior: unchanged")


if __name__ == "__main__":
    main()
