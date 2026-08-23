CTS M2 v4 verifier hotfix

Replace:
tools/verify_canonical_v407.py

Then commit to main and start a NEW:
Actions -> Canonicalize Crypto TradeStation v4.0.7 -> Run workflow

Do not re-run the old failed run.

This only updates three stale CloudShare verification assertions.
It does not change app/, trading logic, CloudShare runtime behavior, or migration code.

The new checks validate:
- actual sequential CreateStep flow + Create My CloudShare + provisioner.provision()
- actual sequential JoinStep flow + Worker URL + Register & Verify
- temporary Cloudflare token is passed to provisioning and cleared after provision() returns
