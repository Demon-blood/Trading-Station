param(
    [Parameter(Mandatory=$true)]
    [string]$ApkPath,
    [switch]$Install
)

$ErrorActionPreference = 'Stop'
$PackageName = 'com.ksp.cryptobot'
$ExpectedStableDebugSigner = 'b690958cb434544e7f8963ecc86559562a82155ffbd915cb2088c9333e06aa28'

function Find-Tool([string]$Name, [string[]]$Candidates) {
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($candidate in $Candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    return $null
}

if (!(Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
$ApkPath = (Resolve-Path $ApkPath).Path

$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Find-Tool 'adb.exe' @((Join-Path $sdk 'platform-tools\adb.exe'))
if (!$adb) { throw "adb.exe not found. Install Android SDK Platform Tools or set ANDROID_SDK_ROOT." }

$buildTools = Join-Path $sdk 'build-tools'
$btDirs = if (Test-Path $buildTools) { Get-ChildItem $buildTools -Directory | Sort-Object Name -Descending } else { @() }
$apksigner = $null
$aapt = $null
foreach ($dir in $btDirs) {
    if (!$apksigner -and (Test-Path (Join-Path $dir.FullName 'apksigner.bat'))) { $apksigner = Join-Path $dir.FullName 'apksigner.bat' }
    if (!$aapt -and (Test-Path (Join-Path $dir.FullName 'aapt.exe'))) { $aapt = Join-Path $dir.FullName 'aapt.exe' }
}
if (!$apksigner) { throw "apksigner.bat not found under $buildTools" }
if (!$aapt) { throw "aapt.exe not found under $buildTools" }

function Get-CertSha256([string]$Path) {
    $lines = & $apksigner verify --print-certs $Path 2>&1
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed for $Path`n$($lines -join "`n")" }
    $line = $lines | Where-Object { $_ -match 'Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
    if (!$line) { throw "Signer SHA-256 not found for $Path" }
    return (($line -split 'digest:',2)[1] -replace '[:\s]','').ToLowerInvariant()
}

function Get-ApkIdentity([string]$Path) {
    $line = (& $aapt dump badging $Path 2>&1 | Where-Object { $_ -like 'package:*' } | Select-Object -First 1)
    if (!$line) { throw "Could not read APK package identity." }
    $name = if ($line -match "name='([^']+)'") { $Matches[1] } else { '' }
    $code = if ($line -match "versionCode='(\d+)'" ) { [long]$Matches[1] } else { -1 }
    $version = if ($line -match "versionName='([^']*)'" ) { $Matches[1] } else { '' }
    [pscustomobject]@{ Package=$name; VersionCode=$code; VersionName=$version; Raw=$line }
}

Write-Host "Checking new APK..." -ForegroundColor Cyan
$newIdentity = Get-ApkIdentity $ApkPath
$newCert = Get-CertSha256 $ApkPath
Write-Host "New APK: package=$($newIdentity.Package) version=$($newIdentity.VersionName) code=$($newIdentity.VersionCode)"
Write-Host "New signer SHA-256: $newCert"

if ($newIdentity.Package -ne $PackageName) { throw "Wrong package name. Expected $PackageName." }
if ($newCert -eq $ExpectedStableDebugSigner) {
    Write-Host "New APK uses the expected stable CTS debug update signer." -ForegroundColor Green
} else {
    Write-Warning "New APK is NOT signed with the stable CTS debug update signer. It may be a release APK or the wrong artifact."
}

& $adb start-server | Out-Null
$devices = & $adb devices
if (($devices | Select-String '\tdevice$').Count -lt 1) {
    throw "No Android device authorized over ADB. Connect the phone, enable USB debugging, and accept the authorization prompt."
}

$pathLine = (& $adb shell pm path $PackageName 2>$null | Select-Object -First 1)
if (!$pathLine -or $pathLine -notmatch '^package:(.+)$') {
    Write-Host "No existing $PackageName installation found. This will be a clean install." -ForegroundColor Yellow
    if ($Install) {
        & $adb install $ApkPath
        exit $LASTEXITCODE
    }
    exit 0
}

$remoteApk = $Matches[1].Trim()
$tempApk = Join-Path $env:TEMP 'cts-installed-base.apk'
& $adb pull $remoteApk $tempApk | Out-Null
$installedCert = Get-CertSha256 $tempApk
$dumpsys = & $adb shell dumpsys package $PackageName
$versionCodeLine = $dumpsys | Where-Object { $_ -match 'versionCode=' } | Select-Object -First 1
$installedCode = if ($versionCodeLine -match 'versionCode=(\d+)') { [long]$Matches[1] } else { -1 }
$versionNameLine = $dumpsys | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1
$installedVersion = if ($versionNameLine -match 'versionName=([^\s]+)') { $Matches[1] } else { '?' }

Write-Host "Installed app: version=$installedVersion code=$installedCode"
Write-Host "Installed signer SHA-256: $installedCert"

$certOk = $installedCert -eq $newCert
$versionOk = $newIdentity.VersionCode -ge $installedCode
Write-Host "Signer match: $certOk"
Write-Host "Version update allowed: $versionOk (new=$($newIdentity.VersionCode), installed=$installedCode)"

if (!$certOk) {
    throw "IN-PLACE UPDATE IMPOSSIBLE: installed APK and new APK have different signing certificates. Do NOT uninstall until you export/backup any app data you need."
}
if (!$versionOk) {
    throw "IN-PLACE UPDATE BLOCKED: new versionCode is lower than installed versionCode."
}

Write-Host "Update compatibility checks PASS." -ForegroundColor Green
if ($Install) {
    Write-Host "Installing with adb install -r..." -ForegroundColor Cyan
    & $adb install -r $ApkPath
    if ($LASTEXITCODE -ne 0) { throw "adb install -r failed. The exact Package Manager error is shown above." }
    Write-Host "Update installed successfully." -ForegroundColor Green
}
