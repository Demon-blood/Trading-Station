param(
    [Parameter(Mandatory=$false)]
    [string]$RepoPath = (Get-Location).Path
)
$ErrorActionPreference = "Stop"
$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Write-Host "Crypto TradeStation - Milestone 2 Canonical Source Freeze" -ForegroundColor Cyan
Write-Host "Repository: $RepoPath"
python "$ScriptRoot\canonicalize_v407.py" "$RepoPath"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host ""
python "$ScriptRoot\verify_m2_canonical.py" "$RepoPath"
exit $LASTEXITCODE
