param(
    [Parameter(Mandatory=$false)]
    [string]$RepoRoot = "."
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path $RepoRoot).Path
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

$Required = @(
    (Join-Path $RepoRoot "app"),
    (Join-Path $RepoRoot ".github\workflows\android-v4-build.yml"),
    (Join-Path $Here "apply_cts_readiness_balance_orderintent.py"),
    (Join-Path $Here "apply_cts_full_completion_2026_08_22.py"),
    (Join-Path $Here "apply_dashboard_info_generator_fix.py"),
    (Join-Path $Here "payload")
)
foreach ($p in $Required) {
    if (!(Test-Path $p)) { throw "Required path missing: $p" }
}

$MigrationDir = Join-Path $RepoRoot ".cts-v4-migration"
New-Item -ItemType Directory -Force -Path $MigrationDir | Out-Null

# Preserve a timestamped workflow backup before installing the hooks.
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$workflow = Join-Path $RepoRoot ".github\workflows\android-v4-build.yml"
$backup = "$workflow.pre_cts_v407_all_in_one_$stamp.bak"
Copy-Item -Force $workflow $backup

Copy-Item -Force (Join-Path $Here "apply_cts_readiness_balance_orderintent.py") `
    (Join-Path $MigrationDir "apply_cts_readiness_balance_orderintent.py")
Copy-Item -Force (Join-Path $Here "apply_cts_full_completion_2026_08_22.py") `
    (Join-Path $MigrationDir "apply_cts_full_completion_2026_08_22.py")
Copy-Item -Force (Join-Path $Here "apply_dashboard_info_generator_fix.py") `
    (Join-Path $MigrationDir "apply_dashboard_info_generator_fix.py")

$payloadDest = Join-Path $MigrationDir "payload"
if (Test-Path $payloadDest) { Remove-Item -Recurse -Force $payloadDest }
Copy-Item -Recurse -Force (Join-Path $Here "payload") $payloadDest

$Python = Get-Command python -ErrorAction SilentlyContinue
$UsePyLauncher = $false
if (-not $Python) {
    $Python = Get-Command py -ErrorAction SilentlyContinue
    $UsePyLauncher = $true
}
if (-not $Python) { throw "Python 3 is required." }

$Readiness = Join-Path $MigrationDir "apply_cts_readiness_balance_orderintent.py"
$Full = Join-Path $MigrationDir "apply_cts_full_completion_2026_08_22.py"
$DashboardInfoFix = Join-Path $MigrationDir "apply_dashboard_info_generator_fix.py"

if ($UsePyLauncher) {
    & $Python.Source -3 -m py_compile $Readiness $Full $DashboardInfoFix
    if ($LASTEXITCODE -ne 0) { throw "Python syntax validation failed." }

    & $Python.Source -3 $DashboardInfoFix $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "Dashboard Info generator fix failed." }

    & $Python.Source -3 $Readiness --workflow-only $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "Readiness/balance/OrderIntent workflow install failed." }

    & $Python.Source -3 $Full --workflow-only $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "Full-completion workflow install failed." }
} else {
    & $Python.Source -m py_compile $Readiness $Full $DashboardInfoFix
    if ($LASTEXITCODE -ne 0) { throw "Python syntax validation failed." }

    & $Python.Source $DashboardInfoFix $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "Dashboard Info generator fix failed." }

    & $Python.Source $Readiness --workflow-only $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "Readiness/balance/OrderIntent workflow install failed." }

    & $Python.Source $Full --workflow-only $RepoRoot
    if ($LASTEXITCODE -ne 0) { throw "Full-completion workflow install failed." }
}

Write-Host ""
Write-Host "Crypto TradeStation v4.0.7 ALL-IN-ONE installed into canonical workflow."
Write-Host "Repo: $RepoRoot"
Write-Host "Workflow backup: $backup"
Write-Host ""
Write-Host "Installed:"
Write-Host "  .cts-v4-migration\apply_cts_readiness_balance_orderintent.py"
Write-Host "  .cts-v4-migration\apply_cts_full_completion_2026_08_22.py"
Write-Host "  .cts-v4-migration\payload\..."
Write-Host ""
Write-Host "The next canonical Android v4 workflow run will apply the migrations after the existing v4 source-generation layers."


Write-Host ""
Write-Host "IMPORTANT FOR GITHUB APK BUILDS:"
Write-Host ".cts-v4-migration\apply_exact_preview_ui.py MUST be committed and pushed."
Write-Host "The Info-button fix now lives inside that canonical generator."
