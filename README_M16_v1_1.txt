M16 v1.1 — payload newline writer hotfix

No trading/runtime logic changes.

Root cause:
The M16 v1 applier wrote:

    .rstrip() + "\\n"

At runtime that appended the literal characters backslash + n to every copied
payload file. Kotlin then parsed those two characters after the final closing
brace and reported:

    Expecting a top level declaration

at the final line of every replaced Kotlin source.

The v1 guard was also over-escaped and therefore did not detect the defect.

v1.1 fixes the writer to append an actual newline and adds byte-level checks:

- reject files ending in literal b"\\n"
- require files to end in real b"\n"

Replace exactly:
tools/apply_m16_market_microstructure.py

Commit to main and launch a NEW M16 workflow from main.

Expected first log line:
INFO | M16 applier revision v1.1

No M16 Kotlin runtime, tests, verifier, strategy, risk, order-book, execution,
or trading behavior changed.
