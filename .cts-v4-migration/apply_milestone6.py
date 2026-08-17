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

def copy_overlay(repo:Path)->None:
    source=HERE/'app/src/main/java/com/ksp/cryptobot'; target=repo/'app/src/main/java/com/ksp/cryptobot'
    for folder in ('cloudshare','data','ui','intelligence','governance','execution','research','release'):
        s=source/folder
        if not s.exists(): continue
        d=target/folder; d.mkdir(parents=True,exist_ok=True)
        for f in s.glob('*.kt'): shutil.copy2(f,d/f.name)
    st=HERE/'app/src/test/java/com/ksp/cryptobot'
    if st.exists():
        dt=repo/'app/src/test/java/com/ksp/cryptobot';dt.mkdir(parents=True,exist_ok=True)
        for f in st.rglob('*.kt'):
            out=dt/f.relative_to(st);out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,out)

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
    if not all(p.exists() for p in (controller,main_activity,gradle,database)): fail('Target does not look like the current Demon-blood/Trading-Station Android source tree.')
    run_m5(repo)
    backup_root=backup([controller,main_activity,gradle,database,src/'data/GovernanceDao.kt',src/'data/ResearchDao.kt',src/'research/ResearchSettingsStore.kt'],repo)
    copy_overlay(repo)
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
