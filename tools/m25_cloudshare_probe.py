#!/usr/bin/env python3
import argparse
import json
import ssl
import sys
import urllib.parse
import urllib.request
from pathlib import Path

EXPECTED_PROTOCOL = "2026-07-26"
EXPECTED_LEASE_SCHEMA = 2

parser = argparse.ArgumentParser(description="Read-only M25 CloudShare production health probe")
parser.add_argument("base_url")
parser.add_argument("--output", default="m25-cloudshare-production.json")
args = parser.parse_args()

base = args.base_url.strip().rstrip("/")
parsed = urllib.parse.urlparse(base)
if parsed.scheme != "https":
    raise SystemExit("ERROR: M25 production CloudShare URL must use HTTPS.")
if not parsed.netloc:
    raise SystemExit("ERROR: invalid CloudShare base URL.")

url = base + "/v1/health"
request = urllib.request.Request(
    url,
    method="GET",
    headers={"User-Agent": "CryptoTradeStation-M25-ReadOnly-Probe/1"},
)

result = {
    "url": url,
    "read_only": True,
    "ok": False,
    "protocol_match": False,
    "lease_schema_match": False,
    "d1_query_confirmed": False,
    "r2_advertised": False,
    "note": "No lease acquisition, D1 mutation, event upload, or Kraken action is performed."
}

try:
    with urllib.request.urlopen(request, timeout=15, context=ssl.create_default_context()) as response:
        body = json.loads(response.read().decode("utf-8"))
        result["http_status"] = response.status
        result["service"] = body.get("service", "")
        result["protocol_version"] = body.get("protocol_version")
        result["engine_lease_schema_version"] = body.get("engine_lease_schema_version")
        result["d1"] = body.get("d1")
        result["r2"] = body.get("r2")
        result["protocol_match"] = body.get("protocol_version") == EXPECTED_PROTOCOL
        result["lease_schema_match"] = body.get("engine_lease_schema_version") == EXPECTED_LEASE_SCHEMA
        result["d1_query_confirmed"] = body.get("d1") is True
        result["r2_advertised"] = body.get("r2") is True
        result["ok"] = (
            body.get("ok") is True
            and result["protocol_match"]
            and result["lease_schema_match"]
            and result["d1_query_confirmed"]
        )
except Exception as exc:
    result["error"] = f"{type(exc).__name__}: {exc}"

Path(args.output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
if not result["ok"]:
    raise SystemExit(1)
