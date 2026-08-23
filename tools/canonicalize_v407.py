#!/usr/bin/env python3
"""
Crypto TradeStation v4.0.7 canonical source materializer — v2.

Fixes the v1 GitHub Actions failure caused by Python bytecode/cache files
being generated inside .cts-v4-migration and then tripping the clean-archive
validation.

Designed for the one-time GitHub Actions canonicalization workflow.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


MUTATION_CHAIN = [
    ".cts-v4-migration/apply_diagnostics_integration_fix.py",
    ".cts-v4-migration/apply_milestone6.py",
    ".cts-v4-migration/apply_full_integration_cleanup.py",
    ".cts-v4-migration/apply_exchange_minimum_order_fix.py",
    ".cts-v4-migration/apply_exact_preview_ui.py",
    ".cts-v4-migration/apply_system_diagnostics_ui.py",
    ".cts-v4-migration/apply_gdelt_rate_limit_fix.py",
    ".cts-v4-migration/apply_cloudshare_setup_wizard.py",
    ".cts-v4-migration/apply_cloudshare_guided_assistant.py",
]

EXPECTED_VERSION_NAME = "4.0.7"
EXPECTED_VERSION_CODE = 112


def fail(message: str) -> "None":
    raise SystemExit("ERROR | " + message)


def run(
    cmd: list[str],
    cwd: Path,
    label: str,
    *,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    print(f"\n=== {label} ===")
    print("$ " + " ".join(cmd))
    merged_env = os.environ.copy()
    if env:
        merged_env.update(env)
    result = subprocess.run(
        cmd,
        cwd=str(cwd),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        env=merged_env,
    )
    print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    if result.returncode != 0:
        fail(f"{label} failed with exit code {result.returncode}")
    return result


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_identity() -> tuple[str, int]:
    version_name = os.environ.get("CTS_VERSION_NAME", EXPECTED_VERSION_NAME).strip()
    raw_code = os.environ.get("CTS_VERSION_CODE", str(EXPECTED_VERSION_CODE)).strip()
    try:
        version_code = int(raw_code)
    except ValueError:
        fail(f"CTS_VERSION_CODE is not an integer: {raw_code!r}")
    if version_name != EXPECTED_VERSION_NAME or version_code != EXPECTED_VERSION_CODE:
        fail(
            f"This canonicalizer is pinned to {EXPECTED_VERSION_NAME}/{EXPECTED_VERSION_CODE}; "
            f"received {version_name}/{version_code}."
        )
    return version_name, version_code


def require_repo(root: Path) -> None:
    required = [
        root / "app/build.gradle.kts",
        root / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt",
        root / ".cts-v4-migration",
        root / ".git",
    ]
    missing = [str(path.relative_to(root)) for path in required if not path.exists()]
    if missing:
        fail("Repository prerequisites missing: " + ", ".join(missing))


def ensure_clean_inputs(root: Path) -> None:
    result = subprocess.run(
        ["git", "status", "--porcelain", "--", "app", ".cts-v4-migration"],
        cwd=str(root),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        fail("Could not inspect git working tree: " + result.stderr.strip())
    if result.stdout.strip():
        fail(
            "Canonicalization requires clean app/ and .cts-v4-migration/ inputs. "
            "Dirty paths:\n" + result.stdout
        )


def syntax_check_script(script: Path) -> None:
    # In-memory compile: validates syntax without creating __pycache__/ or .pyc.
    source = script.read_text(encoding="utf-8")
    try:
        compile(source, str(script), "exec")
    except SyntaxError as exc:
        fail(
            f"Syntax check failed for {script}: "
            f"{exc.msg} at line {exc.lineno}, column {exc.offset}"
        )
    print(f"PASS | syntax | {script}")


def execute_chain(root: Path) -> list[dict[str, str]]:
    records: list[dict[str, str]] = []
    no_bytecode = {"PYTHONDONTWRITEBYTECODE": "1"}

    for rel in MUTATION_CHAIN:
        script = root / rel
        if not script.exists():
            fail(f"Current v4 transformation is missing: {rel}")

        syntax_check_script(script)
        before = sha256(script)

        run(
            [sys.executable, "-B", str(script), str(root)],
            root,
            f"apply {rel}",
            env=no_bytecode,
        )
        records.append(
            {
                "script": rel,
                "script_sha256_before_apply": before,
            }
        )
    return records


def patch_gradle(root: Path, version_name: str, version_code: int) -> None:
    path = root / "app/build.gradle.kts"
    text = path.read_text(encoding="utf-8")
    text, n_code = re.subn(
        r"versionCode\s*=\s*\d+",
        f"versionCode = {version_code}",
        text,
        count=1,
    )
    text, n_name = re.subn(
        r'versionName\s*=\s*"[^"]+"',
        f'versionName = "{version_name}"',
        text,
        count=1,
    )
    if n_code != 1 or n_name != 1:
        fail(f"Could not freeze Gradle identity: versionCode={n_code}, versionName={n_name}")

    junit = '    testImplementation("junit:junit:4.13.2")\n'
    if 'testImplementation("junit:junit:4.13.2")' not in text:
        marker = "dependencies {\n"
        if marker not in text:
            fail("app/build.gradle.kts has no dependencies block")
        text = text.replace(marker, marker + junit, 1)

    path.write_text(text, encoding="utf-8")


def patch_release_info(root: Path, version_name: str, version_code: int) -> None:
    path = root / "app/src/main/java/com/ksp/cryptobot/release/V4ReleaseInfo.kt"
    if not path.exists():
        fail("V4ReleaseInfo.kt was not produced by the current migration chain")
    text = path.read_text(encoding="utf-8")
    text, n_name = re.subn(
        r'const val VERSION_NAME\s*=\s*"[^"]+"',
        f'const val VERSION_NAME = "{version_name}"',
        text,
        count=1,
    )
    text, n_code = re.subn(
        r"const val VERSION_CODE\s*=\s*\d+",
        f"const val VERSION_CODE = {version_code}",
        text,
        count=1,
    )
    if n_name != 1 or n_code != 1:
        fail("Could not freeze V4ReleaseInfo identity")
    path.write_text(text, encoding="utf-8")


def patch_ui_label(root: Path, version_name: str) -> None:
    path = root / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt"
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"v4\.0\.\d+ CTS", f"v{version_name} CTS", text)
    path.write_text(text, encoding="utf-8")


def restore_migration_archive(root: Path) -> None:
    # Restore every tracked migration file exactly to HEAD.
    run(
        ["git", "restore", "--source=HEAD", "--staged", "--worktree", "--", ".cts-v4-migration"],
        root,
        "restore tracked migration payload",
    )

    # Remove ONLY untracked files created after checkout under this directory.
    # ensure_clean_inputs() proved the directory was clean before we started.
    run(
        ["git", "clean", "-fd", "--", ".cts-v4-migration"],
        root,
        "remove generated migration cache/temp files",
    )


def write_marker(
    root: Path,
    version_name: str,
    version_code: int,
    chain_records: list[dict[str, str]],
) -> None:
    marker = root / "app/.cts-canonical-v407.json"
    payload = {
        "canonical_source": True,
        "version_name": version_name,
        "version_code": version_code,
        "materialized_utc": datetime.now(timezone.utc).isoformat(),
        "source_commit": os.environ.get("GITHUB_SHA", "unknown"),
        "workflow_run_id": os.environ.get("GITHUB_RUN_ID", "unknown"),
        "workflow_run_number": os.environ.get("GITHUB_RUN_NUMBER", "unknown"),
        "mutation_chain": chain_records,
        "migration_payload_role": "historical_only_after_materialization",
        "canonicalizer_revision": "v2-no-bytecode",
    }
    marker.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def validate_materialized_shape(root: Path, version_name: str, version_code: int) -> None:
    app_root = root / "app/src/main/java/com/ksp/cryptobot"
    required = [
        app_root / "PreviewReplicaUi.kt",
        app_root / "release/V4ReleaseInfo.kt",
        app_root / "execution/ExchangeMinimumOrderPolicy.kt",
        app_root / "execution/ProtectiveStopManager.kt",
        app_root / "research/ResearchHandoffEngine.kt",
        app_root / "news/NewsProviderHealth.kt",
        app_root / "ui/CloudShareScreen.kt",
        app_root / "cloudshare/CloudShareProvisioner.kt",
    ]
    missing = [str(path.relative_to(root)) for path in required if not path.exists()]
    if missing:
        fail("Materialized source is incomplete: " + ", ".join(missing))

    gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
    if f'versionName = "{version_name}"' not in gradle:
        fail("Frozen Gradle versionName is incorrect")
    if f"versionCode = {version_code}" not in gradle:
        fail("Frozen Gradle versionCode is incorrect")
    if 'testImplementation("junit:junit:4.13.2")' not in gradle:
        fail("JUnit dependency was not frozen into Gradle")

    migration_status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all", "--", ".cts-v4-migration"],
        cwd=str(root),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if migration_status.returncode != 0:
        fail("Could not verify migration archive status: " + migration_status.stderr.strip())
    if migration_status.stdout.strip():
        fail(
            ".cts-v4-migration is still modified after restoration:\n"
            + migration_status.stdout
        )


def emit_diff_summary(root: Path) -> None:
    run(["git", "diff", "--stat", "--", "app"], root, "materialized app diff stat")
    result = subprocess.run(
        ["git", "diff", "--name-only", "--", "app"],
        cwd=str(root),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        fail("Could not list materialized files")
    paths = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    print(f"\nMaterialized changed app files: {len(paths)}")
    for path in paths[:120]:
        print(" - " + path)
    if len(paths) > 120:
        print(f" ... {len(paths) - 120} more")


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    version_name, version_code = read_identity()
    require_repo(root)
    ensure_clean_inputs(root)

    print(f"Canonicalizing Crypto TradeStation {version_name} ({version_code})")
    print("Source commit: " + os.environ.get("GITHUB_SHA", "unknown"))
    print("Canonicalizer: v2-no-bytecode")

    chain_records = execute_chain(root)
    patch_gradle(root, version_name, version_code)
    patch_release_info(root, version_name, version_code)
    patch_ui_label(root, version_name)
    restore_migration_archive(root)
    write_marker(root, version_name, version_code, chain_records)
    validate_materialized_shape(root, version_name, version_code)
    emit_diff_summary(root)

    print("\nPASS | v4.0.7 effective CI source is materialized into app/.")
    print("PASS | migration archive restored with no generated bytecode/temp files.")
    print("PASS | version identity frozen at 4.0.7 / 112.")


if __name__ == "__main__":
    main()
