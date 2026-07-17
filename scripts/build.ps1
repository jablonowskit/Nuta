[CmdletBinding()]
param(
    [string]$ImageName = "nuta-gui:local",
    [switch]$NoCache
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Nie znaleziono polecenia Docker. Uruchom Docker Desktop i sprobuj ponownie."
}

$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
docker info 2>$null | Out-Null
$ErrorActionPreference = $previousErrorActionPreference
if ($LASTEXITCODE -ne 0) {
    throw "Docker nie dziala albo uzywa niewlasciwego trybu. Wymagane sa kontenery Linux."
}

$arguments = @("build", "--file", (Join-Path $projectRoot "Dockerfile"), "--tag", $ImageName)
if ($NoCache) {
    $arguments += "--no-cache"
}
$arguments += $projectRoot

Write-Host "Budowanie obrazu $ImageName..."
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
& docker $arguments
$ErrorActionPreference = $previousErrorActionPreference
if ($LASTEXITCODE -ne 0) {
    throw "Budowanie obrazu zakonczylo sie bledem ($LASTEXITCODE)."
}

Write-Host "Gotowe: $ImageName"
