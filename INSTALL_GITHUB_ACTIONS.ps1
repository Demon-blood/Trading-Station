param(
    [Parameter(Mandatory=$true)][string]$RepoPath,
    [switch]$Commit,
    [switch]$Push
)
$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = (Resolve-Path $RepoPath).Path
if (!(Test-Path (Join-Path $Repo 'app\build.gradle.kts'))) { throw "RepoPath is not the Trading-Station Android repository: $Repo" }
New-Item -ItemType Directory -Force -Path (Join-Path $Repo '.github\workflows') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Repo '.cts-v4-migration') | Out-Null
Copy-Item -Force (Join-Path $Here '.github\workflows\android-v4-build.yml') (Join-Path $Repo '.github\workflows\android-v4-build.yml')
Copy-Item -Recurse -Force (Join-Path $Here '.cts-v4-migration\*') (Join-Path $Repo '.cts-v4-migration')
Write-Host "Installed GitHub Actions v4 build workflow into $Repo" -ForegroundColor Green
if ($Commit -or $Push) {
    Push-Location $Repo
    try {
        git add .github/workflows/android-v4-build.yml .cts-v4-migration
        git commit -m "Add Crypto TradeStation v4 GitHub Actions build" | Write-Host
        if ($Push) { git push | Write-Host }
    } finally { Pop-Location }
}
Write-Host "GitHub will build the APK after push, or run it manually from Actions > Crypto TradeStation v4 Build." -ForegroundColor Cyan
