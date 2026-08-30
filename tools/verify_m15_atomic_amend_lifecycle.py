#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M15 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    atomic = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/AtomicOrderAmend.kt")
    client = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/CryptoExchangeClient.kt")
    kraken = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    lifecycle = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    policy_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/SmartOrderLifecyclePolicyTest.kt")
    calibration_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/ExecutionCalibrationMathTest.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    checks = {
        "no Room schema bump":
            "version = 12" in db,

        "atomic amend request distinguishes total quantity":
            "val newTotalQuantity: BigDecimal? = null" in atomic and
            "Kraken interprets order_qty as the NEW TOTAL order quantity" in atomic,
        "connector contract defaults unsupported":
            "suspend fun amendOrder(request: AtomicOrderAmendRequest)" in client and
            'reason = "Atomic amend is not supported by this exchange connector."' in client,

        "Kraken uses atomic AmendOrder endpoint":
            'privateJson("/0/private/AmendOrder", form)' in kraken,
        "Kraken prefers stable client order id":
            'form["cl_ord_id"] = KrakenClientOrderId.normalize(request.clientOrderId)' in kraken,
        "Kraken falls back to txid":
            'form["txid"] = request.exchangeOrderId' in kraken,
        "Kraken returns amend_id":
            'result.optString("amend_id", "")' in kraken,
        "Kraken atomic amend does not call AddOrder":
            "/0/private/AddOrder" not in kraken[
                kraken.find("override suspend fun amendOrder"):
                kraken.find("override suspend fun cancelOrder")
            ],
        "automatic repricer never changes quantity":
            "newTotalQuantity =" not in lifecycle,
        "automatic amend is post-only":
            "postOnly = true" in lifecycle,
        "automatic amend has latency deadline":
            "Instant.now().plusSeconds(5)" in lifecycle,

        "bounded lifecycle defines HOLD AMEND CANCEL":
            "enum class SmartOrderLifecycleAction { HOLD, AMEND, CANCEL }" in lifecycle,
        "automatic repricing is BUY LIMIT only":
            "side != OrderSide.BUY || orderType != OrderType.LIMIT" in lifecycle,
        "hard timeout exists":
            "HARD_CANCEL_MULTIPLIER = 4L" in lifecycle and
            "SmartOrderLifecycleAction.CANCEL" in lifecycle,
        "maximum automatic amendment count exists":
            "MAX_AUTOMATIC_AMENDS = 3" in lifecycle,
        "stale hard cancel submits no replacement":
            "No automatic replacement was submitted because the original signal is now stale." in lifecycle,
        "amend failure submits no replacement":
            "no replacement order was submitted." in lifecycle,

        "fill-time calibration persisted":
            "mean_fill_sec" in lifecycle and
            "actualFill=" in lifecycle,
        "slippage calibration persisted":
            "mean_slippage_bps" in lifecycle and
            "slippageBps(" in lifecycle,
        "modification telemetry persisted":
            "_amends" in lifecycle and
            "amendmentsPerCompletedFill" in lifecycle,
        "cancel telemetry persisted":
            "_cancels" in lifecycle,
        "calibration requires enough samples":
            "calibrationSamples < 3" in lifecycle,
        "learned stale timing strictly bounded":
            "configured / 2L" in lifecycle and
            "configured * 2L" in lifecycle,
        "calibration cannot bypass trading risk":
            "they never override risk, authority, DMS or net-EV gates" in lifecycle,

        "old cancel-only requote removed":
            "Stale order cancelled for requote:" not in controller,
        "controller invokes M15 lifecycle":
            "smartOrderLifecycle.manage(settings, exchange, orders)" in controller and
            "M15 order lifecycle:" in controller,

        "policy tests cover atomic amend":
            "staleBuyLimitAmendsInsteadOfCancelReplace" in policy_tests,
        "policy tests cover hard cancel":
            "hardDeadlineCancelsStaleSignalWithoutReplacement" in policy_tests,
        "policy tests protect SELL intent":
            "sellLimitIsNeverBlindlyRepriced" in policy_tests,
        "policy tests bound learning":
            "learnedStaleTimingIsBounded" in policy_tests,
        "calibration tests define adverse slippage":
            "buyPositiveSlippageMeansWorseExecution" in calibration_tests and
            "makerPriceImprovementIsNegativeSlippage" in calibration_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M15 atomic amend / lifecycle verification failed: " + ", ".join(failed))

    print("\nPASS | M15 atomic amend and self-calibrating order lifecycle contracts satisfied.")

if __name__ == "__main__":
    main()
