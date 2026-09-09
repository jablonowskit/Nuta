<#
.SYNOPSIS
Szybkie, lokalne sprawdzenie błędów kompilacji kodu wspólnego i desktopowego.

.DESCRIPTION
Wstępna weryfikacja PRZED wypchnięciem na CI — łapie literówki, brakujące importy i
błędy typów w commonMain/desktopMain w ~20 s zamiast ~7 minut pełnego cyklu CI.

To NIE zastępuje CI ani Dockera:
- Nie kompiluje :androidApp (brak lokalnego Android SDK) — pliki androidowe nadal
  weryfikuje wyłącznie CI.
- Nie buduje APK ani nie tworzy artefaktów do wydania.
- Nie uruchamia aplikacji (do tego jest scripts/run.ps1 w Dockerze).

Zakres pokrycia jest jednak większy, niż sugeruje nazwa: commonMain to większość kodu
(UI, parsery JSON, scrobbler, DSP normalizacji, wyszukiwanie), więc błąd w kodzie
wspólnym wyjdzie tutaj, mimo że dotyczy też Androida.

.PARAMETER SkipTests
Tylko kompilacja, bez testów jednostkowych (nieco szybciej).

.PARAMETER Online
Pozwól Gradle sięgnąć do sieci. Domyślnie skrypt działa w trybie --offline i korzysta
z lokalnego cache'u zależności. Użyj po dodaniu nowej zależności do build.gradle.kts.

.EXAMPLE
.\scripts\check-desktop.ps1
Kompilacja + 56 testów jednostkowych, ~25 s.

.EXAMPLE
.\scripts\check-desktop.ps1 -SkipTests
Tylko kompilacja.
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Online
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

# Desktop celuje w JVM 25 (composeApp/build.gradle.kts), więc host musi mieć JDK 25+.
# Bez tej kontroli Gradle wywala się komunikatem o toolchainie, z którego nie wynika,
# że problem jest w wersji Javy na hoście.
$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCommand) {
    throw "Nie znaleziono polecenia java. Zainstaluj JDK 25+ albo buduj w Dockerze: .\scripts\build.ps1"
}

# `java -version` pisze na stderr, a w Windows PowerShell 5.1 przekierowanie 2>&1 na
# natywnym exe zamienia kazda linie stderr w ErrorRecord (NativeCommandError) i wywala
# skrypt przy ErrorActionPreference=Stop. Dlatego czytamy wersje z pliku release JDK,
# a gdy go nie ma — z stderr przy tymczasowo zluzowanej obsludze bledow.
$versionOutput = ""
$javaHome = Split-Path -Parent (Split-Path -Parent $javaCommand.Source)
$releaseFile = Join-Path $javaHome "release"
if (Test-Path $releaseFile) {
    $releaseLine = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION=' -ErrorAction SilentlyContinue
    if ($releaseLine) { $versionOutput = $releaseLine.Line }
}
if ([string]::IsNullOrWhiteSpace($versionOutput)) {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $versionOutput = (& java -version 2>&1 | Out-String)
    $ErrorActionPreference = $previousErrorActionPreference
}

# Pasuje i do JAVA_VERSION="25.0.3" z pliku release, i do: openjdk version "25.0.3".
# (?i) jest konieczne: [regex]::Match jest case-sensitive (w odróżnieniu od operatora
# -match w PowerShellu), a w pliku release klucz jest kapitalikami.
$versionMatch = [regex]::Match($versionOutput, '(?i)version[^"]*"(\d+)')
if ($versionMatch.Success) {
    $major = [int]$versionMatch.Groups[1].Value
    if ($major -lt 25) {
        throw "Wykryto Jave $major, a desktop wymaga JDK 25+ (jvmTarget JVM_25). Zainstaluj nowsza Jave albo buduj w Dockerze."
    }
} else {
    Write-Warning "Nie udalo sie odczytac wersji Javy z: $($versionOutput.Trim())"
}

$gradleArguments = @()
if (-not $Online) {
    # Offline domyślnie: bez tego Gradle za każdym razem odpytuje repozytoria, co przy
    # niedostępnej sieci wydłuża sprawdzenie z sekund do minut albo wysypuje build.
    $gradleArguments += "--offline"
}

$tasks = if ($SkipTests) { @(":composeApp:compileKotlinDesktop") } else { @(":composeApp:desktopTest") }
$gradleArguments += $tasks

$gradlew = Join-Path $projectRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    throw "Nie znaleziono $gradlew"
}

Write-Host "Sprawdzanie lokalnie (kod wspolny + desktop): $($tasks -join ', ')" -ForegroundColor Cyan
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Push-Location $projectRoot
try {
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $gradlew @gradleArguments
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorActionPreference
} finally {
    Pop-Location
}

$stopwatch.Stop()
$elapsed = [math]::Round($stopwatch.Elapsed.TotalSeconds, 1)

if ($exitCode -ne 0) {
    Write-Host ""
    Write-Host "BLAD kompilacji/testow po ${elapsed}s. Popraw i uruchom ponownie przed pushem." -ForegroundColor Red
    exit $exitCode
}

Write-Host ""
Write-Host "OK po ${elapsed}s." -ForegroundColor Green
if ($SkipTests) {
    Write-Host "Uruchomiono tylko kompilacje. Testy: .\scripts\check-desktop.ps1" -ForegroundColor DarkGray
}
Write-Host "Pamietaj: :androidApp weryfikuje dopiero CI (brak lokalnego Android SDK)." -ForegroundColor DarkGray
