#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

def b(value):
    return str(value).strip().lower() in {"1", "true", "yes", "on"}

parser = argparse.ArgumentParser()
parser.add_argument("--stage", choices=["prelive", "postlive"], required=True)
parser.add_argument("--paper-hours", type=int, required=True)
parser.add_argument("--shadow-hours", type=int, required=True)
parser.add_argument("--install-upgrade", required=True)
parser.add_argument("--kraken-permissions", required=True)
parser.add_argument("--distributed-authority", required=True)
parser.add_argument("--tiny-live", default="false")
parser.add_argument("--protective-exit", default="false")
parser.add_argument("--network-failure", default="false")
parser.add_argument("--fee-pnl", default="false")
parser.add_argument("--final-diagnostics", default="false")
parser.add_argument("--cloudshare-json", required=True)
parser.add_argument("--release-apk", required=True)
parser.add_argument("--output", default="m25-release-evidence.json")
args = parser.parse_args()

cloud = json.loads(Path(args.cloudshare_json).read_text(encoding="utf-8"))
apk = Path(args.release_apk)
blockers = []

if not apk.is_file() or apk.stat().st_size <= 0:
    blockers.append("signed release APK missing")
if not cloud.get("ok"):
    blockers.append("production CloudShare health probe failed")
if not b(args.install_upgrade):
    blockers.append("install/upgrade operator verification missing")
if not b(args.kraken_permissions):
    blockers.append("Kraken permission operator verification missing")
if not b(args.distributed_authority):
    blockers.append("distributed authority operator verification missing")
if args.paper_hours < 24:
    blockers.append(f"PAPER burn-in {args.paper_hours}/24h")
if args.shadow_hours < 24:
    blockers.append(f"shadow burn-in {args.shadow_hours}/24h")

stage = "BLOCKED" if blockers else "CONTROLLED_LIVE_ELIGIBLE"

post_blockers = []
if not blockers and args.stage == "postlive":
    checks = {
        "tiny LIVE lifecycle": b(args.tiny_live),
        "protective exit": b(args.protective_exit),
        "network-failure lifecycle": b(args.network_failure),
        "fee/PnL reconciliation": b(args.fee_pnl),
        "final diagnostics export": b(args.final_diagnostics),
    }
    post_blockers = [name for name, ok in checks.items() if not ok]
    stage = "RELEASE_READY" if not post_blockers else "CONTROLLED_LIVE_ELIGIBLE"

evidence = {
    "validation_stage": args.stage,
    "result": stage,
    "machine": {
        "signed_release_apk_present": apk.is_file() and apk.stat().st_size > 0,
        "cloudshare_production_probe": cloud,
    },
    "operator_attestations": {
        "install_upgrade_verified": b(args.install_upgrade),
        "kraken_permissions_verified": b(args.kraken_permissions),
        "distributed_authority_verified": b(args.distributed_authority),
        "paper_burnin_hours": args.paper_hours,
        "shadow_burnin_hours": args.shadow_hours,
        "tiny_live_completed": b(args.tiny_live),
        "protective_exit_verified": b(args.protective_exit),
        "network_failure_lifecycle_verified": b(args.network_failure),
        "fee_pnl_reconciled": b(args.fee_pnl),
        "final_diagnostics_exported": b(args.final_diagnostics),
    },
    "blockers": blockers + post_blockers,
    "warning": "Operator attestations are recorded evidence, not machine-observed Kraken actions."
}

Path(args.output).write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
print(json.dumps(evidence, indent=2))
if stage == "BLOCKED":
    raise SystemExit(1)
if args.stage == "postlive" and stage != "RELEASE_READY":
    raise SystemExit(1)
