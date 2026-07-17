[CmdletBinding()]
param(
    [string]$ImageName = "nuta-gui:local",
    [string]$ContainerName = "nuta-gui",
    [int]$VncPort = 6080,
    [int]$ApiPort = 6081,
    [ValidateSet("TRACE", "DEBUG", "INFO", "WARN", "ERROR")]
    [string]$LogLevel = "DEBUG",
    [string]$SessionVolume = "nuta-session",
    [switch]$EphemeralSession,
    [switch]$Build,
    [switch]$OpenBrowser
)

$ErrorActionPreference = "Stop"

if ($Build) {
    & (Join-Path $PSScriptRoot "build.ps1") -ImageName $ImageName
    if ($LASTEXITCODE -ne 0) {
        throw "Nie udalo sie zbudowac obrazu."
    }
}

docker image inspect $ImageName *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Brak obrazu '$ImageName'. Uruchom scripts/build.ps1 albo dodaj parametr -Build."
}

if (-not $EphemeralSession) {
    if ($SessionVolume -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.-]+$') {
        throw "Nieprawidlowa nazwa wolumenu sesji '$SessionVolume'."
    }
    $existingSessionVolume = docker volume ls --quiet --filter "name=^${SessionVolume}$"
    if (-not $existingSessionVolume) {
        Write-Host "Tworzenie wolumenu sesji $SessionVolume..."
        docker volume create $SessionVolume | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Nie udalo sie utworzyc wolumenu sesji."
        }
    }
}

$existingContainer = docker container ls --all --quiet --filter "name=^/$ContainerName$"
if ($existingContainer) {
    Write-Host "Usuwanie poprzedniego kontenera $ContainerName..."
    docker container rm --force $ContainerName | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Nie udalo sie usunac poprzedniego kontenera."
    }
}

Write-Host "Uruchamianie $ContainerName..."
$dockerArguments = @(
    "run", "--detach",
    "--name", $ContainerName,
    "--publish", "127.0.0.1:${VncPort}:6080",
    "--publish", "127.0.0.1:${ApiPort}:6081",
    "--env", "NUTA_LOG_LEVEL=$LogLevel",
    "--shm-size", "512m"
)
if (-not $EphemeralSession) {
    $dockerArguments += @("--volume", "${SessionVolume}:/home/nuta/.local/share/nuta")
}
$dockerArguments += $ImageName

docker @dockerArguments | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Nie udalo sie uruchomic kontenera."
}

$healthUrl = "http://127.0.0.1:$ApiPort/health"
$guiUrl = "http://localhost:$VncPort/vnc.html?autoconnect=true&resize=scale"
$ready = $false

Write-Host "Oczekiwanie na GUI..."
for ($attempt = 1; $attempt -le 30; $attempt++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing $healthUrl -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 1
    }
}

if (-not $ready) {
    docker logs $ContainerName
    throw "Aplikacja nie zglosila gotowosci. Powyzej znajduja sie logi kontenera."
}

Write-Host "Aplikacja dziala."
Write-Host "GUI:        $guiUrl"
Write-Host "Screenshot: http://localhost:$ApiPort/screenshot"
Write-Host "Health:     $healthUrl"
Write-Host "Log level:  $LogLevel"
if (-not $EphemeralSession) {
    Write-Host "Sesja:      wolumen $SessionVolume"
} else {
    Write-Host "Sesja:      ulotna"
}

if ($OpenBrowser) {
    Start-Process $guiUrl
}
