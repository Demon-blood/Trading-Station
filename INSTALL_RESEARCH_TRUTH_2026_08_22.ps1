param(
    [Parameter(Position=0)]
    [string]$RepoPath = "."
)
$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
python (Join-Path $ScriptDir "INSTALL_RESEARCH_TRUTH_2026_08_22.py") $RepoPath
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "CTS v4.0.7 research-truth implementation installed." -ForegroundColor Green
