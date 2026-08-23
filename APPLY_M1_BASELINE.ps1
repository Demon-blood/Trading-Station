param(
    [Parameter(Mandatory=$false)]
    [string]$RepoPath = (Get-Location).Path
)

$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Crypto TradeStation - Milestone 1 Baseline Fix" -ForegroundColor Cyan
Write-Host "Repository: $RepoPath"

python "$ScriptRoot\apply_m1_baseline.py" "$RepoPath"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

python "$ScriptRoot\verify_m1.py" "$RepoPath"
exit $LASTEXITCODE
