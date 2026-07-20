[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$Device,
    [switch]$Build,
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $ApkPath) {
    $ApkPath = Join-Path $projectRoot "artifacts\android\Nuta-debug.apk"
}

if ($Build) {
    & (Join-Path $PSScriptRoot "build-android.ps1") -OutputPath $ApkPath
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "Brak APK: $ApkPath. Uruchom scripts/build-android.ps1 albo dodaj -Build."
}

$adb = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1
if (-not $adb) {
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe")
    )
    $adb = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if (-not $adb) { throw "Nie znaleziono adb. Sprawdz instalacje Android SDK lub platform-tools (winget install Google.PlatformTools)." }

$deviceArguments = @()
if ($Device) { $deviceArguments = @("-s", $Device) }
$devices = & $adb devices
$onlineDevices = @($devices | Select-String "\sdevice$")
if ($onlineDevices.Count -eq 0) { throw "Brak uruchomionego emulatora lub telefonu widocznego przez adb." }
if (-not $Device -and $onlineDevices.Count -gt 1) {
    throw "Wykryto kilka urzadzen. Podaj -Device, np. emulator-5554."
}

Write-Host "Instalowanie APK z zachowaniem danych aplikacji..."
& $adb @deviceArguments install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "Instalacja nie powiodla sie. Skrypt celowo nie odinstalowuje aplikacji, aby nie utracic sesji Spotify."
}

if (-not $NoLaunch) {
    & $adb @deviceArguments shell am force-stop app.nuta
    & $adb @deviceArguments shell am start -n app.nuta/app.nuta.android.MainActivity | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "APK zainstalowano, ale nie udalo sie uruchomic aplikacji." }
}

Write-Host "Nuta zostala wdrozona na urzadzenie."

