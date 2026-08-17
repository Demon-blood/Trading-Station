param(
    [Parameter(Mandatory=$true)][string]$RepoPath,
    [switch]$Release
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path $RepoPath).Path
Write-Host "Applying Crypto TradeStation Android v4.0.0 final migration to: $repo" -ForegroundColor Cyan
python (Join-Path $here 'apply_milestone6.py') $repo
Push-Location $repo
try {
    if ($Release) {
        Write-Host 'Building signed release APK...' -ForegroundColor Cyan
        .\gradlew clean :app:assembleRelease
        $apk = Get-ChildItem -Path .\app\build\outputs\apk\release -Filter *.apk -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    } else {
        Write-Host 'Building debug APK...' -ForegroundColor Cyan
        .\gradlew clean :app:assembleDebug
        $apk = Get-ChildItem -Path .\app\build\outputs\apk\debug -Filter *.apk -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if (-not $apk) { throw 'Gradle completed but no APK was found in the expected output directory.' }
    $hash = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash
    Write-Host "APK: $($apk.FullName)" -ForegroundColor Green
    Write-Host "SHA256: $hash" -ForegroundColor Green
} finally {
    Pop-Location
}
