#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
errors = []

def require_file(rel):
    p = root / rel
    if not p.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return p.read_text(encoding="utf-8", errors="replace")

def require_contains(text, needle, label):
    if needle not in text:
        errors.append(f"{label}: missing {needle!r}")

db = require_file("app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")
if not re.search(r"@Database\([\s\S]*?version\s*=\s*12\b", db):
    errors.append("AppDatabase must be Room schema version 12.")
require_contains(db, "MIGRATION_11_12", "AppDatabase migration")

canonical = require_file(".github/workflows/android-canonical-build.yml")
require_contains(canonical, "roomSchema=12", "canonical install identity")
if "roomSchema=11" in canonical:
    errors.append("canonical install identity still contains stale roomSchema=11.")

readiness = require_file("app/src/main/java/com/ksp/cryptobot/release/M25ReleaseReadiness.kt")
for marker in (
    "MIN_PAPER_BURN_IN_HOURS = 24",
    "MIN_SHADOW_BURN_IN_HOURS = 24",
    "CONTROLLED_LIVE_ELIGIBLE",
    "RELEASE_READY",
    "cloudShareProductionVerified",
    "krakenPermissionsVerified",
    "partialFillLifecycle",
    "feePnlReconciled",
):
    require_contains(readiness, marker, "M25 readiness policy")

tests = require_file("app/src/test/java/com/ksp/cryptobot/release/M25ReleaseReadinessTest.kt")
for marker in (
    "burnInThresholdCannotBeBypassed",
    "productionCloudShareAndSignedArtifactAreRequiredBeforeControlledLive",
    "releaseReadyRequiresFullPostLiveLifecycleEvidence",
    "anyExplicitFailureBlocksTheCandidate",
):
    require_contains(tests, marker, "M25 tests")

runbook = require_file("app/src/main/assets/release/m25_release_candidate_runbook.md")
for marker in (
    "CI and GitHub Actions MUST NOT submit a Kraken order",
    "Unknown/stale Kraken execution truth blocks a new BUY",
    "production CloudShare `/v1/health`",
    "Profit is not an M25 pass condition",
):
    require_contains(runbook, marker, "M25 runbook")

require_file("app/src/main/assets/release/m25_evidence_template.json")

security = require_file("app/src/main/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurity.kt")
for marker in (
    '"withdraw-funds"',
    '"add-withdraw-address"',
    '"update-withdraw-address"',
    "MAX_ASSESSMENT_AGE_MS",
    "gateForNewBuy",
):
    require_contains(security, marker, "M22 Kraken API security prerequisite")

execution = require_file("app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt")
for marker in (
    '"partially_filled"',
    "cumulativeQuantity",
    "feeQuantity",
    "markRestReconciled",
    "markRecoveryUnknown",
    "markRecoveryReconciled",
    "KrakenDurableExecutionQuarantine",
    "onNetworkAvailable",
    "ambiguous",
):
    require_contains(execution, marker, "Kraken lifecycle/recovery prerequisite")

worker = require_file("app/src/main/assets/cloudshare_setup/cloudshare-worker.js")
for marker in (
    'const PROTOCOL_VERSION = "2026-07-26"',
    "ENGINE_LEASE_SCHEMA_VERSION = 2",
    'path === "/v1/health"',
    "engine_lease_schema_version",
    "SELECT fence_token, schema_version FROM engine_leases LIMIT 1",
):
    require_contains(worker, marker, "CloudShare production probe contract")

# Search all Kotlin for exact milestone safety components without assuming a file path.
all_kt = "\n".join(
    p.read_text(encoding="utf-8", errors="replace")
    for p in (root / "app/src/main/java").rglob("*.kt")
)
for marker in (
    "canSubmitNewLiveEntryAuthoritative",
    "M23DiagnosticBundleExporter",
):
    require_contains(all_kt, marker, "cross-milestone safety prerequisite")

if errors:
    print("M25 VERIFICATION FAILED")
    for e in errors:
        print(f" - {e}")
    raise SystemExit(1)

print("M25 verification PASS")
print(" - Room schema identity: 12")
print(" - M24.1 prerequisite present")
print(" - restart/network/partial-fill execution contracts present")
print(" - Kraken API-key security contract present")
print(" - M24 authoritative LIVE entry fence present")
print(" - M23 diagnostic bundle exporter present")
print(" - CloudShare read-only production health contract present")
print(" - M25 readiness policy separates CODE_RC / CONTROLLED_LIVE_ELIGIBLE / RELEASE_READY")
