[CmdletBinding()]
param(
    [string]$ImageName = "nuta-android-build:local",
    [string]$OutputPath,
    [switch]$NoCache
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputPath) {
    $OutputPath = Join-Path $projectRoot "artifacts\android\Nuta-debug.apk"
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Nie znaleziono Dockera. Uruchom Docker Desktop."
}

$buildArguments = @("build", "--progress=plain", "--target", "android-builder", "--tag", $ImageName)
if ($NoCache) { $buildArguments += "--no-cache" }
$buildArguments += $projectRoot

Write-Host "Budowanie APK Android w Dockerze..."
& docker $buildArguments
if ($LASTEXITCODE -ne 0) { throw "Budowanie APK zakonczylo sie bledem ($LASTEXITCODE)." }

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$containerName = "nuta-android-apk-export"
$existing = docker container ls --all --quiet --filter "name=^/$containerName$"
if ($existing) { docker container rm --force $containerName | Out-Null }

try {
    docker create --name $containerName $ImageName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Nie udalo sie utworzyc kontenera eksportowego." }
    docker cp "${containerName}:/workspace/androidApp/build/outputs/apk/debug/androidApp-debug.apk" $OutputPath
    if ($LASTEXITCODE -ne 0) { throw "Nie udalo sie wyeksportowac APK." }
} finally {
    docker container rm --force $containerName 2>$null | Out-Null
}

Write-Host "Gotowe APK: $OutputPath"

