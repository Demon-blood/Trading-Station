#!/usr/bin/env python3
from pathlib import Path
import os, sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/execution/MarketMicrostructureEngine.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/LiquidityAwareSizer.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/OrderTypeOptimizer.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionModels.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/MarketMicrostructureEngineTest.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/OrderTypeOptimizerM16Test.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/LiquidityAwareSizerM16Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M16 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\\n" + dirty)

    payload = Path(__file__).resolve().parent / "m16_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M16 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\\n", encoding="utf-8")
        if dst.read_text(encoding="utf-8").endswith("\\\\n"):
            fail(f"M16 payload copy produced literal backslash-n EOF: {rel}")
        print("WRITE |", rel)

    # M16 makes the optimizer's passive-maker decision authoritative when no
    # source research directive explicitly specifies an order type.
    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''        val finalPostOnly = directive?.postOnlyPreferred == true && finalOrderType == OrderType.LIMIT
''',
        '''        val finalPostOnly = finalOrderType == OrderType.LIMIT && when {
            directive?.preferredOrderType != null -> directive.postOnlyPreferred == true
            else -> optimizedOrder.postOnly
        }
''',
        "M16 passive maker post-only authority"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Controlled app diff only.
    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
    }
    if actual - allowed:
        fail("Unexpected M16 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M16 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M16 controlled app diff.")

if __name__ == "__main__":
    main()
