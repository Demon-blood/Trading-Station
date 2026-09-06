#!/usr/bin/env python3
from pathlib import Path
import re
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
errors = []

def read(rel):
    p = root / rel
    if not p.is_file():
        errors.append(f"missing file: {rel}")
        return ""
    return p.read_text(encoding="utf-8", errors="replace")

def need(text, marker, label):
    if marker not in text:
        errors.append(f"{label}: missing {marker!r}")

db = read("app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")
if not re.search(r"@Database\([\s\S]*?version\s*=\s*12\b", db):
    errors.append("AppDatabase must declare Room schema version 12.")
need(db, "MIGRATION_11_12", "AppDatabase migration")

policy = read("app/src/main/java/com/ksp/cryptobot/release/M25ReleaseReadiness.kt")
for marker in (
    "roomSchema12Verified",
    "MIN_PAPER_BURN_IN_HOURS = 24",
    "MIN_SHADOW_BURN_IN_HOURS = 24",
    "CONTROLLED_LIVE_ELIGIBLE",
    "RELEASE_READY",
    "cloudShareProductionVerified",
    "krakenPermissionsVerified",
    "partialFillLifecycle",
    "feePnlReconciled",
):
    need(policy, marker, "M25 readiness policy")

tests = read("app/src/test/java/com/ksp/cryptobot/release/M25ReleaseReadinessTest.kt")
for marker in (
    "roomSchemaSourceTruthIsRequiredForCodeRc",
    "burnInThresholdCannotBeBypassed",
    "productionCloudShareAndSignedArtifactAreRequiredBeforeControlledLive",
    "releaseReadyRequiresFullPostLiveLifecycleEvidence",
    "anyExplicitFailureBlocksTheCandidate",
):
    need(tests, marker, "M25 tests")

runbook = read("app/src/main/assets/release/m25_release_candidate_runbook.md")
for marker in (
    "CI and GitHub Actions MUST NOT submit a Kraken order",
    "M25 verifies the real Room source schema",
    "Unknown/stale Kraken execution truth blocks a new BUY",
    "production CloudShare `/v1/health`",
    "Profit is not an M25 pass condition",
):
    need(runbook, marker, "M25 runbook")

read("app/src/main/assets/release/m25_evidence_template.json")

security = read("app/src/main/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurity.kt")
for marker in (
    '"withdraw-funds"',
    '"add-withdraw-address"',
    '"update-withdraw-address"',
    "MAX_ASSESSMENT_AGE_MS",
    "gateForNewBuy",
):
    need(security, marker, "M22 Kraken API security prerequisite")

execution = read("app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt")
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
    need(execution, marker, "Kraken lifecycle/recovery prerequisite")

worker = read("app/src/main/assets/cloudshare_setup/cloudshare-worker.js")
for marker in (
    'const PROTOCOL_VERSION = "2026-07-26"',
    "ENGINE_LEASE_SCHEMA_VERSION = 2",
    'path === "/v1/health"',
    "engine_lease_schema_version",
    "SELECT fence_token, schema_version FROM engine_leases LIMIT 1",
):
    need(worker, marker, "CloudShare production probe contract")

all_kt = "\n".join(
    p.read_text(encoding="utf-8", errors="replace")
    for p in (root / "app/src/main/java").rglob("*.kt")
)
for marker in ("canSubmitNewLiveEntryAuthoritative", "M23DiagnosticBundleExporter"):
    need(all_kt, marker, "cross-milestone safety prerequisite")

if errors:
    print("M25 VERIFICATION FAILED")
    for e in errors:
        print(f" - {e}")
    raise SystemExit(1)

print("M25 verification PASS")
print(" - actual Room source schema: 12")
print(" - canonical INSTALL_IDENTITY metadata is not a CODE_RC dependency")
print(" - restart/network/partial-fill execution contracts present")
print(" - Kraken API-key security contract present")
print(" - M24 authoritative LIVE entry fence present")
print(" - M23 diagnostic bundle exporter present")
print(" - CloudShare read-only production health contract present")
print(" - readiness stages: CODE_RC / CONTROLLED_LIVE_ELIGIBLE / RELEASE_READY")
