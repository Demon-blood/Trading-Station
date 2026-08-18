#!/usr/bin/env python3
"""Apply final cumulative Crypto TradeStation Android v4.0.0 Stage 6 to v3.2.5 or any prior v4 milestone."""
from __future__ import annotations
import re, shutil, subprocess, sys
from pathlib import Path
HERE=Path(__file__).resolve().parent

def fail(msg:str)->None: raise SystemExit(msg)
def replace_once(text:str, old:str, new:str, label:str)->str:
    count=text.count(old)
    if count!=1: fail(f"Cannot patch {label}: expected exactly one match, found {count}.")
    return text.replace(old,new,1)

def run_m5(repo:Path)->None:
    gradle=(repo/'app/build.gradle.kts').read_text(encoding='utf-8')
    controller=(repo/'app/src/main/java/com/ksp/cryptobot/core/BotController.kt').read_text(encoding='utf-8')
    database=(repo/'app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt').read_text(encoding='utf-8') if (repo/'app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt').exists() else ''
    if ('versionName = "4.0.0-m5"' in gradle or 'versionName = "4.0.0"' in gradle) and 'ResearchCoordinator' in controller and 'version = 11' in database:
        print('M5 baseline already present; skipping M5 reapply.')
        return
    script=HERE/'_baseline_m5/apply_milestone5.py'
    result=subprocess.run([sys.executable,str(script),str(repo)],text=True,capture_output=True)
    if result.returncode!=0: fail("Cumulative M5 baseline failed before final Stage 6 patching:\n"+result.stdout+result.stderr)
    print(result.stdout.strip())

def enforce_execution_truth_patches(repo:Path)->None:
    """Re-apply the idempotent M4 execution/lifecycle patches even when M5 is already present."""
    import importlib.util
    script=HERE/'_baseline_m5/_baseline_m4/apply_milestone4.py'
    spec=importlib.util.spec_from_file_location('cts_m4_truth_patch', script)
    if spec is None or spec.loader is None: fail('Cannot load M4 truth patch module.')
    module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    src=repo/'app/src/main/java/com/ksp/cryptobot'
    module.patch_controller(src/'core/BotController.kt')
    module.patch_lifecycle(src/'lifecycle/TradeLifecycleManager.kt')
    module.patch_paper_exchange(src/'exchange/PaperExchangeClient.kt')

def copy_overlay(repo:Path)->None:
    source=HERE/'app/src/main/java/com/ksp/cryptobot'; target=repo/'app/src/main/java/com/ksp/cryptobot'
    for folder in ('cloudshare','data','ui','intelligence','governance','execution','research','release','exchange'):
        s=source/folder
        if not s.exists(): continue
        d=target/folder; d.mkdir(parents=True,exist_ok=True)
        for f in s.glob('*.kt'): shutil.copy2(f,d/f.name)
    st=HERE/'app/src/test/java/com/ksp/cryptobot'
    if st.exists():
        dt=repo/'app/src/test/java/com/ksp/cryptobot';dt.mkdir(parents=True,exist_ok=True)
        for f in st.rglob('*.kt'):
            out=dt/f.relative_to(st);out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,out)
    assets=HERE/'app/src/main/assets'
    if assets.exists():
        shutil.copytree(assets, repo/'app/src/main/assets', dirs_exist_ok=True)

def backup(paths:list[Path],repo:Path)->Path:
    root=repo/'.v4_final_backup';root.mkdir(exist_ok=True)
    for p in paths:
        if p.exists():
            t=root/p.relative_to(repo);t.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,t)
    return root

def patch_main_activity(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    if 'import com.ksp.cryptobot.ui.V4ControlCenterScreen' not in text:
        text=replace_once(text,'package com.ksp.cryptobot\n','package com.ksp.cryptobot\n\nimport com.ksp.cryptobot.ui.V4ControlCenterScreen\n','MainActivity V4 import')
    if 'V4_SYSTEMS("V4 Systems")' not in text:
        text=replace_once(text,'    SELF_LEARNING("Self Learning"),\n','    SELF_LEARNING("Self Learning"),\n    V4_SYSTEMS("V4 Systems"),\n','MainActivity V4 enum')
    if 'AppTab.V4_SYSTEMS,' not in text:
        text=replace_once(text,'            AppTab.SELF_LEARNING,\n            AppTab.CHART,\n','            AppTab.SELF_LEARNING,\n            AppTab.V4_SYSTEMS,\n            AppTab.CHART,\n','MainActivity live tab')
    if 'AppTab.V4_SYSTEMS -> V4ControlCenterScreen()' not in text:
        text=replace_once(text,'                AppTab.NOTIFICATIONS -> NotificationsHubScreen(\n','                AppTab.V4_SYSTEMS -> V4ControlCenterScreen()\n                AppTab.NOTIFICATIONS -> NotificationsHubScreen(\n','MainActivity V4 route')
    text=text.replace('Text("v3.2.5 CTS", color = Mint, fontWeight = FontWeight.Bold)','Text("v4.0.0 CTS", color = Mint, fontWeight = FontWeight.Bold)')
    path.write_text(text,encoding='utf-8')

def patch_controller(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    if 'import com.ksp.cryptobot.release.V4SystemVerifier' not in text:
        text=replace_once(text,'import com.ksp.cryptobot.research.ResearchCoordinator\n','import com.ksp.cryptobot.research.ResearchCoordinator\nimport com.ksp.cryptobot.release.V4SystemVerifier\n','controller V4 verifier import')
    if 'V4 migrated systems' not in text:
        anchor='''        add("PASS", "Secure Exchange Key Store", "Encrypted key store is reachable. Keys are not exposed in diagnostics.")\n'''
        block='''        add("PASS", "Secure Exchange Key Store", "Encrypted key store is reachable. Keys are not exposed in diagnostics.")\n\n        try {\n            V4SystemVerifier(appContext).verify(settings).forEach { check ->\n                add(check.status, "V4 ${check.name}", check.detail)\n            }\n            add("PASS", "V4 migrated systems", "Final Stage 6 verifier completed for CloudShare, governance, execution, research, recovery and signing/integrity.")\n        } catch (error: Exception) {\n            add("WARN", "V4 migrated systems", "V4 verifier failed to complete: ${error.message}")\n        }\n'''
        text=replace_once(text,anchor,block,'controller V4 verification')
    path.write_text(text,encoding='utf-8')

def patch_core_models(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    if 'val postOnly: Boolean = false' not in text:
        text=replace_once(text,
'''    val reduceOnly: Boolean = false,
    val purpose: String = "ENTRY"
)''',
'''    val reduceOnly: Boolean = false,
    val purpose: String = "ENTRY",
    /** Maker-only flag. Kraken REST maps this to oflags=post for LIMIT orders. */
    val postOnly: Boolean = false,
    /** Optional technical stop attached to a BUY as Kraken conditional close when supported. */
    val protectiveStopPrice: BigDecimal? = null
)''','OrderRequest post-only semantics')
    # OrderRequest protective stop idempotent hardening.
    if 'val protectiveStopPrice: BigDecimal? = null' not in text and 'val postOnly: Boolean = false' in text:
        text=text.replace('    val postOnly: Boolean = false\n)', '    val postOnly: Boolean = false,\n    /** Optional technical stop attached to a BUY as Kraken conditional close when supported. */\n    val protectiveStopPrice: BigDecimal? = null\n)',1)
    if 'val realizedPnlQuote: BigDecimal = BigDecimal.ZERO' not in text:
        text=replace_once(text,
'''    val fee: BigDecimal,
    val paper: Boolean,
    val timestamp: Instant = Instant.now()
)''',
'''    val fee: BigDecimal,
    val paper: Boolean,
    /** Realized P&L in the order quote currency when the exchange/simulator can know it. */
    val realizedPnlQuote: BigDecimal = BigDecimal.ZERO,
    val timestamp: Instant = Instant.now()
)''','OrderResult realized PnL')
    path.write_text(text,encoding='utf-8')

def patch_exchange_symbol_metadata(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    if 'val minOrderCost: BigDecimal = BigDecimal.ZERO' not in text:
        old = 'data class ExchangeSymbolInfo(\n    val requestedSymbol: String,\n    val normalizedSymbol: String,\n    val exchangePair: String,\n    val altName: String,\n    val baseAsset: String,\n    val quoteAsset: String,\n    val minOrderSize: BigDecimal,\n    val priceDecimals: Int,\n    val quantityDecimals: Int,\n    val tradable: Boolean,\n    val reason: String = ""\n)'
        new = 'data class ExchangeSymbolInfo(\n    val requestedSymbol: String,\n    val normalizedSymbol: String,\n    val exchangePair: String,\n    val altName: String,\n    val baseAsset: String,\n    val quoteAsset: String,\n    val minOrderSize: BigDecimal,\n    val priceDecimals: Int,\n    val quantityDecimals: Int,\n    val tradable: Boolean,\n    val reason: String = "",\n    /** Exchange-reported minimum order cost/notional in quote currency. */\n    val minOrderCost: BigDecimal = BigDecimal.ZERO,\n    /** Exchange-reported valid price tick. Zero means unknown/fallback precision only. */\n    val tickSize: BigDecimal = BigDecimal.ZERO\n)'
        text=replace_once(text,old,new,'ExchangeSymbolInfo costmin/tick')
    candidate = text.split('data class SymbolDiscoveryCandidate',1)[1] if 'data class SymbolDiscoveryCandidate' in text else ''
    if 'val minOrderCost: BigDecimal = BigDecimal.ZERO' not in candidate:
        old = '    val enabledForRotation: Boolean = false,\n    val reason: String = ""\n)'
        new = '    val enabledForRotation: Boolean = false,\n    val reason: String = "",\n    val minOrderCost: BigDecimal = BigDecimal.ZERO,\n    val tickSize: BigDecimal = BigDecimal.ZERO\n)'
        text=replace_once(text,old,new,'SymbolDiscoveryCandidate costmin/tick')
    path.write_text(text,encoding='utf-8')

def patch_exchange_interface(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    if 'data class TradingFeeSchedule' not in text:
        text=text.replace('interface CryptoExchangeClient {', '''data class TradingFeeSchedule(
    /** Decimal rate, e.g. 0.0040 = 0.40%. */
    val makerRate: java.math.BigDecimal,
    /** Decimal rate, e.g. 0.0080 = 0.80%. */
    val takerRate: java.math.BigDecimal,
    val rollingVolumeUsd: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val source: String = "EXCHANGE"
)

interface CryptoExchangeClient {''',1)
    if 'getTradingFeeSchedule(symbol: String)' not in text:
        text=text.replace('''    suspend fun getClosedOrders(limit: Int = 50): List<ClosedOrderInfo> = emptyList()

    /**
     * Full portfolio balances''','''    suspend fun getClosedOrders(limit: Int = 50): List<ClosedOrderInfo> = emptyList()

    /**
     * Account/pair-specific maker/taker fees when the connector can retrieve them.
     * Null means the caller must use a conservative fallback; it never means zero fees.
     */
    suspend fun getTradingFeeSchedule(symbol: String): TradingFeeSchedule? = null

    /**
     * Full portfolio balances''',1)
    path.write_text(text,encoding='utf-8')

def patch_kraken_exchange(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    pair_header = text.split('private data class KrakenPairRule',1)[1].split(')',1)[0] if 'private data class KrakenPairRule' in text else ''
    if 'val minOrderCost: BigDecimal = BigDecimal.ZERO' not in pair_header:
        text=replace_once(text,
            '        val quantityDecimals: Int,\n        val tradable: Boolean,\n        val status: String\n    )',
            '        val quantityDecimals: Int,\n        val tradable: Boolean,\n        val status: String,\n        val minOrderCost: BigDecimal = BigDecimal.ZERO,\n        val tickSize: BigDecimal = BigDecimal.ZERO\n    )',
            'Kraken pair costmin/tick fields')
    if 'minOrderCost = rule.minOrderCost' not in text:
        text=replace_once(text,
            '            tradable = rule.tradable,\n            reason = if (rule.tradable) "Tradable on Kraken. status=${rule.status}" else "Not tradable on Kraken. status=${rule.status}"\n        )',
            '            tradable = rule.tradable,\n            reason = if (rule.tradable) "Tradable on Kraken. status=${rule.status}; ordermin=${rule.minOrderSize}; costmin=${rule.minOrderCost}; tick=${rule.tickSize}" else "Not tradable on Kraken. status=${rule.status}",\n            minOrderCost = rule.minOrderCost,\n            tickSize = rule.tickSize\n        )',
            'Kraken validate costmin/tick')
    discovery_tail = text.split('SymbolDiscoveryCandidate(',1)[1] if 'SymbolDiscoveryCandidate(' in text else ''
    if 'minOrderCost = rule.minOrderCost' not in discovery_tail:
        text=replace_once(text,
            '                    minOrderSize = rule.minOrderSize,\n                    reason = "Discovered from Kraken AssetPairs. quote=${rule.quoteAsset}, status=${rule.status}, min=${rule.minOrderSize}, pair=${rule.exchangePair}"',
            '                    minOrderSize = rule.minOrderSize,\n                    minOrderCost = rule.minOrderCost,\n                    tickSize = rule.tickSize,\n                    reason = "Discovered from Kraken AssetPairs. quote=${rule.quoteAsset}, status=${rule.status}, min=${rule.minOrderSize}, costmin=${rule.minOrderCost}, tick=${rule.tickSize}, pair=${rule.exchangePair}"',
            'Kraken discovery costmin/tick')
    if 'minOrderCost = item.optString("costmin"' not in text:
        text=replace_once(text,
            '                    tradable = status.equals("online", ignoreCase = true) || status.isBlank(),\n                    status = status\n                )',
            '                    tradable = status.equals("online", ignoreCase = true) || status.isBlank(),\n                    status = status,\n                    minOrderCost = item.optString("costmin", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,\n                    tickSize = item.optString("tick_size", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO\n                )',
            'Kraken AssetPairs costmin/tick parse')
    if 'override suspend fun getTradingFeeSchedule(symbol: String)' not in text:
        anchor='''    override suspend fun getAvailableBalances(): Map<String, BigDecimal> = withContext(Dispatchers.IO) {
'''
        block='''    override suspend fun getTradingFeeSchedule(symbol: String): TradingFeeSchedule? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) return@withContext null
        val rule = resolvePairRule(symbol)
        // Kraken Get Trade Volume returns the current account/pair fee tier. It requires Query Funds permission.
        val root = privateJson("/0/private/TradeVolume", mapOf("pair" to rule.exchangePair))
        val result = root.optJSONObject("result") ?: return@withContext null
        fun feeRate(section: String): BigDecimal? {
            val group = result.optJSONObject(section) ?: return null
            val direct = group.optJSONObject(rule.exchangePair)
                ?: group.optJSONObject(rule.altName)
                ?: group.keys().asSequence().firstOrNull()?.let { group.optJSONObject(it) }
                ?: return null
            // Kraken returns fee as percentage units (e.g. 0.40), convert to decimal rate (0.0040).
            return direct.optString("fee", "").toBigDecimalOrNull()?.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
        }
        val taker = feeRate("fees") ?: return@withContext null
        val maker = feeRate("fees_maker") ?: taker
        TradingFeeSchedule(
            makerRate = maker.max(BigDecimal.ZERO),
            takerRate = taker.max(BigDecimal.ZERO),
            rollingVolumeUsd = result.optString("volume", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
            source = "KRAKEN_TRADE_VOLUME"
        )
    }

'''
        # ExchangeClientsV08.kt contains multiple exchange implementations with
        # the same getAvailableBalances() signature. Scope this insertion to
        # KrakenSpotClient instead of requiring a globally unique anchor.
        class_marker = 'class KrakenSpotClient('
        class_start = text.find(class_marker)
        if class_start < 0:
            fail('Cannot patch Kraken current fee tier: KrakenSpotClient not found.')
        next_class = text.find('\nclass ', class_start + len(class_marker))
        class_end = next_class if next_class >= 0 else len(text)
        anchor_index = text.find(anchor, class_start, class_end)
        if anchor_index < 0:
            fail('Cannot patch Kraken current fee tier: getAvailableBalances() not found inside KrakenSpotClient.')
        text = text[:anchor_index] + block + text[anchor_index:]
    # Install minimum-cost/tick-price validation before anything refers to the tick helper.
    if 'private fun roundKrakenPriceToTick' not in text:
        old = '''        if (request.orderType != OrderType.MARKET) {
            val price = request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
            form["price"] = price.setScale(rule.priceDecimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }'''
        new = '''        val orderPriceForMinimum = if (request.orderType == OrderType.MARKET) {
            val liveTicker = getTicker(rule.canonicalSymbol)
            if (request.side == OrderSide.BUY) liveTicker.ask else liveTicker.bid
        } else request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
        val estimatedOrderCost = cleanQuantity.multiply(orderPriceForMinimum)
        if (rule.minOrderCost > BigDecimal.ZERO && estimatedOrderCost < rule.minOrderCost) {
            error("Kraken order cost too small for ${rule.canonicalSymbol}. cost=$estimatedOrderCost minCost=${rule.minOrderCost}; the bot will not increase size above its risk ceiling to satisfy the exchange minimum.")
        }
        if (request.orderType != OrderType.MARKET) {
            val rawPrice = request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
            val price = roundKrakenPriceToTick(rawPrice, rule.tickSize, rule.priceDecimals, request.side, request.orderType)
            form["price"] = price.stripTrailingZeros().toPlainString()
        }'''
        text=replace_once(text,old,new,'Kraken tick/cost minimum order validation')
        helper = '''
    private fun roundKrakenPriceToTick(value: BigDecimal, tick: BigDecimal, decimals: Int, side: OrderSide, type: OrderType): BigDecimal {
        if (tick <= BigDecimal.ZERO) return value.setScale(decimals, RoundingMode.HALF_UP)
        val rounding = when (type) {
            OrderType.LIMIT -> if (side == OrderSide.BUY) RoundingMode.DOWN else RoundingMode.UP
            OrderType.STOP_LOSS -> if (side == OrderSide.BUY) RoundingMode.UP else RoundingMode.DOWN
            OrderType.TAKE_PROFIT -> if (side == OrderSide.BUY) RoundingMode.DOWN else RoundingMode.UP
            OrderType.MARKET -> RoundingMode.HALF_UP
        }
        return value.divide(tick, 0, rounding).multiply(tick).setScale(decimals, RoundingMode.HALF_UP)
    }

'''
        text=replace_once(text, '    private fun queryOrderFill(txid: String, rule: KrakenPairRule, request: OrderRequest): OrderResult {\n', helper+'    private fun queryOrderFill(txid: String, rule: KrakenPairRule, request: OrderRequest): OrderResult {\n', 'Kraken tick helper')
    if 'form["oflags"] = "post"' not in text or 'form["close[ordertype]"] = "stop-loss"' not in text:
        old='''        if (request.orderType != OrderType.MARKET) {
            val rawPrice = request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
            val price = roundKrakenPriceToTick(rawPrice, rule.tickSize, rule.priceDecimals, request.side, request.orderType)
            form["price"] = price.stripTrailingZeros().toPlainString()
        }
        val encoded = encodeForm(form)
'''
        new='''        if (request.orderType != OrderType.MARKET) {
            val rawPrice = request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
            val price = roundKrakenPriceToTick(rawPrice, rule.tickSize, rule.priceDecimals, request.side, request.orderType)
            form["price"] = price.stripTrailingZeros().toPlainString()
        }
        if (request.postOnly) {
            if (request.orderType != OrderType.LIMIT) error("Kraken post-only is valid only for ordinary LIMIT orders; conditional stop/take-profit orders cannot silently use maker-only semantics.")
            form["oflags"] = "post"
        }
        request.protectiveStopPrice?.takeIf { request.side == OrderSide.BUY && it > BigDecimal.ZERO }?.let { rawStop ->
            val stop = roundKrakenPriceToTick(rawStop, rule.tickSize, rule.priceDecimals, OrderSide.SELL, OrderType.STOP_LOSS)
            if (stop >= orderPriceForMinimum) error("Protective stop must be below the BUY entry reference. stop=$stop entryRef=$orderPriceForMinimum")
            form["close[ordertype]"] = "stop-loss"
            form["close[price]"] = stop.stripTrailingZeros().toPlainString()
        }
        val encoded = encodeForm(form)
'''
        text=replace_once(text,old,new,'Kraken post-only and conditional protective close')
    path.write_text(text,encoding='utf-8')

def patch_paper_post_only(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    if 'Paper post-only rejected' not in text:
        anchor='''        val ticker = getTicker(clean)
'''
        block='''        val ticker = getTicker(clean)
        if (request.postOnly) {
            if (request.orderType != OrderType.LIMIT) error("Paper post-only is valid only for LIMIT orders.")
            val limit = request.limitPrice ?: error("Paper post-only LIMIT requires a price.")
            val wouldCross = when (request.side) {
                OrderSide.BUY -> limit >= ticker.ask
                OrderSide.SELL -> limit <= ticker.bid
            }
            if (wouldCross) error("Paper post-only rejected: the limit would immediately cross the book and become taker.")
        }
'''
        text=replace_once(text,anchor,block,'paper post-only rejection')
    path.write_text(text,encoding='utf-8')

def patch_gradle(path:Path)->None:
    text=path.read_text(encoding='utf-8')
    text,c1=re.subn(r'versionCode\s*=\s*(?:97|100|101|102|103|104|105)\b','versionCode = 105',text,count=1)
    text,c2=re.subn(r'versionName\s*=\s*"(?:3\.2\.5|4\.0\.0-m1|4\.0\.0-m2|4\.0\.0-m3|4\.0\.0-m4|4\.0\.0-m5|4\.0\.0)"','versionName = "4.0.0"',text,count=1)
    if c1!=1 or c2!=1: fail(f"Cannot patch final Gradle version: code={c1}, name={c2}")
    guard='''\n// v4 final hardening: never silently produce an unsigned release package.\ntasks.configureEach {\n    if (name in setOf("assembleRelease", "bundleRelease", "packageRelease")) {\n        doFirst {\n            if (!signingPropertiesFile.exists()) {\n                throw GradleException("Release signing.properties is required for v4 release packaging. Debug builds continue to use the stable CTS debug update key.")\n            }\n        }\n    }\n}\n'''
    if 'v4 final hardening' not in text: text=text.rstrip()+"\n"+guard
    path.write_text(text,encoding='utf-8')

def main()->None:
    if len(sys.argv)!=2: fail('Usage: python apply_milestone6.py <path-to-Trading-Station-repo>')
    repo=Path(sys.argv[1]).resolve(); src=repo/'app/src/main/java/com/ksp/cryptobot'
    controller=src/'core/BotController.kt'; main_activity=src/'MainActivity.kt'; gradle=repo/'app/build.gradle.kts'; database=src/'data/AppDatabase.kt'
    core_models=src/'core/Models.kt'; exchange_iface=src/'exchange/CryptoExchangeClient.kt'; exchange_clients=src/'exchange/ExchangeClientsV08.kt'; paper_exchange=src/'exchange/PaperExchangeClient.kt'
    if not all(p.exists() for p in (controller,main_activity,gradle,database,core_models,exchange_iface,exchange_clients,paper_exchange)): fail('Target does not look like the current Demon-blood/Trading-Station Android source tree.')
    validator=HERE/'validate_handoff_truth.py'
    if validator.exists():
        check=subprocess.run([sys.executable,str(validator)],text=True,capture_output=True)
        if check.returncode!=0: fail('Research-handoff truth payload validation failed before migration:\n'+check.stdout+check.stderr)
        print(check.stdout.strip())
    run_m5(repo)
    enforce_execution_truth_patches(repo)
    backup_root=backup([controller,main_activity,gradle,database,core_models,exchange_iface,exchange_clients,paper_exchange,src/'data/GovernanceDao.kt',src/'data/ResearchDao.kt',src/'research/ResearchSettingsStore.kt'],repo)
    copy_overlay(repo)
    patch_core_models(core_models); patch_exchange_symbol_metadata(core_models); patch_exchange_interface(exchange_iface); patch_kraken_exchange(exchange_clients); patch_paper_post_only(paper_exchange)
    patch_main_activity(main_activity); patch_controller(controller); patch_gradle(gradle)
    print('Applied FINAL Crypto TradeStation Android v4 Stage 6.')
    print(f'Backup: {backup_root}')
    print('Version: 4.0.0 / code 105 / Room schema 11.')
    print('UI: V4 Systems tab with Overview, CloudShare, Research and Recovery panels.')
    print('Recovery: core backup remains available; v4 supplemental backup restores governance/execution/research history without exporting CloudShare/admin/API secrets.')
    print('Verification: existing System Test now includes final v4 integrity/module checks.')
    print('Release hardening: release packaging requires signing.properties; signer lineage is checked at runtime.')
    print('Build debug: ./gradlew clean :app:assembleDebug')
    print('Build release: ./gradlew clean :app:assembleRelease   (requires signing.properties)')
if __name__=='__main__': main()
