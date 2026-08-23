param(
    [Parameter(Mandatory=$false)]
    [string]$RepoPath = (Get-Location).Path
)

$ErrorActionPreference = "Stop"
$Repo = (Resolve-Path $RepoPath).Path
$Backup = Join-Path $Repo ".cts-m1-backup\android-v4-build.yml"
$Target = Join-Path $Repo ".github\workflows\android-v4-build.yml"

if (-not (Test-Path $Backup)) {
    throw "Milestone 1 backup not found: $Backup"
}

Copy-Item -Force $Backup $Target
Write-Host "Restored original workflow from $Backup" -ForegroundColor Yellow
