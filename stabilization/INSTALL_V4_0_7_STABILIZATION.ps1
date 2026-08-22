param(
    [string]$RepoPath = "."
)
$ErrorActionPreference = "Stop"
$installer = Join-Path $PSScriptRoot "INSTALL_V4_0_7_STABILIZATION.py"
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command py -ErrorAction SilentlyContinue }
if (-not $python) { throw "Python was not found in PATH." }
& $python.Source $installer $RepoPath
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "CTS v4.0.7 stabilization files installed into $RepoPath"
