[CmdletBinding()]
param(
    [string]$ContainerName = "nuta-gui"
)

$ErrorActionPreference = "Stop"
$container = docker container ls --all --quiet --filter "name=^/$ContainerName$"

if (-not $container) {
    Write-Host "Kontener $ContainerName nie istnieje."
    exit 0
}

docker container rm --force $ContainerName | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Nie udalo sie zatrzymac kontenera $ContainerName."
}

Write-Host "Kontener $ContainerName zostal zatrzymany i usuniety."
