# Nuta

Natywny, wieloplatformowy odtwarzacz muzyki. Pierwsza faza rozwijana jest jako aplikacja Linux uruchamiana w Dockerze i oglądana przez noVNC.

## Uruchomienie GUI Linux

Wymagany jest wyłącznie Docker z włączonymi kontenerami Linux.

Zbuduj obraz (kompilacja i testy odbywają się wewnątrz Dockera):

```powershell
.\scripts\build.ps1
```

Uruchom aplikację:

```powershell
.\scripts\run.ps1 -OpenBrowser
```

Można też wykonać oba kroki jednym poleceniem:

```powershell
.\scripts\run.ps1 -Build -OpenBrowser
```

Następnie otwórz:

- GUI: <http://localhost:6080/vnc.html?autoconnect=true&resize=scale>
- screenshot: <http://localhost:6081/screenshot>
- healthcheck: <http://localhost:6081/health>

Zatrzymanie:

```powershell
.\scripts\stop.ps1
```

## Testy

Testy są wykonywane automatycznie przez `build.ps1` podczas budowania obrazu.

Skrypty przyjmują opcjonalne parametry. Ich opis wyświetla PowerShell, np.:

```powershell
Get-Help .\scripts\run.ps1 -Detailed
```

Ustalenia projektu znajdują się w [PROJECT.md](PROJECT.md), a szczegółowy plan fazy Linux GUI w [docs/PHASE_1_LINUX_GUI.md](docs/PHASE_1_LINUX_GUI.md).

## Licencja

Licencja open source zostanie wybrana przed pierwszą publikacją repozytorium.
