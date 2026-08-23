# Nuta

Natywny, wieloplatformowy odtwarzacz muzyki. Metadane, playlisty, wyszukiwanie
i rekomendacje pochodzą z jednego z dwóch **jawnie wybieranych źródeł danych**
(`DataSource` w Ustawieniach) — **Spotify** albo **ListenBrainz** (wyszukiwanie
przez MusicBrainz) — bez mergowania między nimi: przełączenie na ListenBrainz
działa tak, jakby Spotify nie istniało, i odwrotnie. Samo audio jest zawsze
niezależne od tego wyboru i pochodzi z jednego z dwóch **źródeł audio**
(`AudioSource`) — **YouTube** albo **SoundCloud** (z opcjonalnym trybem AUTO:
YouTube, a przy błędzie automatyczny fallback na SoundCloud dla tego samego
utworu) — odtwarzane we własnym playerze i własnej kolejce. Nuta nie tworzy
playlist w YouTube i nie wymaga logowania do YouTube.

**Darmowe konto wystarcza dla obu źródeł danych.** Spotify nie wymaga Premium
(dźwięk nie pochodzi ze Spotify). ListenBrainz nie wymaga żadnego logowania
w apce — wystarczy publiczny nick i osobisty token API wygenerowany ręcznie
na listenbrainz.org/settings, wklejony w Ustawieniach (bez OAuth/WebView).

Stack: **Kotlin Multiplatform + Compose Multiplatform**, toolchain Java 25 LTS,
Gradle Wrapper 9.6.1.

## Status platform

Priorytetem jest **Android** (decyzja z 2026-07-18, szczegóły w
[docs/ANDROID_PLAN.md](docs/ANDROID_PLAN.md)).

| Platforma | Stan |
|---|---|
| **Android** | Realne logowanie Spotify (WebView), prawdziwe repozytorium Spotify, odtwarzanie przez Media3/ExoPlayer z `MediaSessionService` (tło + ekran blokady). minSdk 26, target/compileSdk 36. |
| **Windows** | Paczka EXE z CI. Logowanie Spotify przez natywny helper WebView2 (`native/spotify-login`), bo osadzony JCEF był odrzucany przez challenge Spotify. |
| **Linux** | Aplikacja Compose Desktop + `mpv` przez IPC; testy GUI w Dockerze przez noVNC. Job CI dla Linuksa jest tymczasowo wyłączony. |
| **macOS** | Planowany, jeszcze nierozpoczęty. |

iOS został świadomie usunięty z wymagań.

## Zasada środowiska budowania

Projekt kompilujemy i testujemy **wyłącznie w Dockerze** — na hoście nie
uruchamiamy `gradlew`, Javy, testów ani SDK (pełne zasady w
[AGENTS.md](AGENTS.md)). Host służy do edycji plików i sterowania Dockerem.
Jedyny wyjątek: diagnostyka aplikacji Windows.

## Budowanie

Desktop (kompilacja + testy w obrazie Docker):

```powershell
.\scripts\build.ps1
```

Android APK (debug, trafia do `artifacts\android\Nuta-debug.apk`):

```powershell
.\scripts\build-android.ps1
```

## Uruchamianie

### Linux GUI przez noVNC

```powershell
.\scripts\run.ps1 -OpenBrowser
```

Oba kroki naraz:

```powershell
.\scripts\run.ps1 -Build -OpenBrowser
```

Adresy lokalne:

- GUI: <http://localhost:6080/vnc.html?autoconnect=true&resize=scale>
- screenshot: <http://localhost:6081/screenshot>
- healthcheck: <http://localhost:6081/health>

Sesja Spotify jest domyślnie trwała w wolumenie `nuta-session`. Czysty
kontener bez zapisanej sesji: `.\scripts\run.ps1 -EphemeralSession`.
Szczegółową diagnostykę playera i IPC włącza `-LogLevel TRACE`.

Zatrzymanie:

```powershell
.\scripts\stop.ps1
```

### Android — na emulatorze lub telefonie

Wdrożenie zbudowanego APK przez `adb` (instalacja z zachowaniem danych, więc
sesja Spotify nie ginie):

```powershell
python scripts\deploy-android.py            # domyślny APK z artifacts\android
python scripts\deploy-android.py --build    # najpierw zbuduj
python scripts\deploy-android.py --device emulator-5554
```

Podpisany APK release z GitHub Actions (wymaga zalogowanego `gh`):

```powershell
python scripts\download-latest-apk.py --install
```

Zalecana konfiguracja emulatora (obraz **Google Play**, API 36 — ważne dla
aktualizowalnego WebView) jest opisana w
[docs/ANDROID_PLAN.md](docs/ANDROID_PLAN.md).

## Testy

Testy uruchamiają się automatycznie w ramach `scripts\build.ps1` podczas
budowania obrazu.

Skrypty przyjmują dodatkowe parametry — opis wyświetla PowerShell:

```powershell
Get-Help .\scripts\run.ps1 -Detailed
```

## CI

Workflow [`.github/workflows/linux-gui.yml`](.github/workflows/linux-gui.yml)
uruchamia się przy każdym pushu i buduje:

- **`build-windows-exe`** — helper logowania WebView2 (`dotnet publish`),
  wbudowanie go w zasoby aplikacji i `:composeApp:packageExe`; artefakty
  `nuta-windows-exe-diagnostic` i `nuta-spotify-login-helper-diagnostic`,
- **`build-android-apk`** — podpisany `:androidApp:assembleRelease`; artefakt
  `nuta-android-apk`. Wymaga sekretów keystore w GitHub Actions Secrets.

Job Linux (AppImage + test GUI przez noVNC) jest zakomentowany i można go
przywrócić w razie potrzeby.

## Architektura

Ścieżka odtwarzania jednego utworu:

```text
DataSourceSelectingRepository — wybiera Spotify albo ListenBrainz (ustawienie DataSource),
      ↓                         deleguje KAŻDĄ metodę SpotifyRepository bez mergowania
  SpotifyRepository            — logowanie i prywatne endpointy web-playera Spotify
  ListenBrainzRepository       — ListenBrainz (playlisty/ulubione/rekomendacje)
                                  + MusicBrainzRepository (wyszukiwanie)
      ↓
SourceSelectingMediaService   — wybiera YouTube albo SoundCloud (ustawienie AudioSource,
      ↓                         AUTO = YouTube z fallbackiem na SoundCloud po błędzie)
  YouTubeMediaService          — własne wyszukiwanie YouTube, ranking i strumień audio-only
  SoundCloudMediaService       — nieoficjalne publiczne API SoundCloud
      ↓
AudioPlayer                   — Media3 (Android) / mpv przez IPC (desktop) / Fake (testy GUI)
      ↓
Normalizacja głośności        — działa na samym sygnale audio, niezależnie od źródła:
                                 mpv filtr `loudnorm` (desktop) / `LoudnessEnhancer` (Android)
```

Wszystkie kontrakty (`SpotifyRepository`, `YouTubeMediaService`, `AudioPlayer`) są
zdefiniowane w `commonMain/domain/Contracts.kt` / `commonMain/youtube/YouTubeContracts.kt`
i wstrzykiwane przez `AppContainer`, więc warstwa UI nie zna szczegółów prywatnych
protokołów ani tego, które konkretne źródło jest aktywne. Osobnego `PlaybackCoordinator`
opisanego w PROJECT.md jeszcze nie ma — koordynacja znajduje się dziś w implementacjach
`AudioPlayer` i w `ui/App.kt`.

Podział źródeł:

```text
composeApp/src/commonMain   — modele, kontrakty, UI Compose, logging, dane demo,
                               DataSourceSelectingRepository, SourceSelectingMediaService,
                               listenbrainz/, musicbrainz/, net/ (expect/actual HTTP fetch)
composeApp/src/desktopMain  — Spotify/YouTube/SoundCloud dla desktopu, MpvAudioPlayer
androidApp                  — logowanie WebView, repozytorium Spotify, YouTube/SoundCloud
                               media services, Media3 + PlaybackService (LoudnessEnhancer)
native/spotify-login        — helper logowania WebView2 dla Windows (C#)
```

Logowanie do Spotify korzysta z prywatnego protokołu web-playera — **bez OAuth,
bez `clientId` i `clientSecret`**. Ten mechanizm może przestać działać bez
ostrzeżenia. Sekrety sesji (`sp_dc`, tokeny) nigdy nie trafiają do logów ani do
repozytorium. SoundCloud (audio) i MusicBrainz (wyszukiwanie w trybie
ListenBrainz) też opierają się na nieoficjalnych/publicznych API bez własnego
logowania. ListenBrainz (metadane/playlisty/rekomendacje) jest jedynym
źródłem z oficjalnym, udokumentowanym API — token wklejany ręcznie w
Ustawieniach, bez żadnego mechanizmu OAuth/WebView w apce.

## Dokumentacja

- [PROJECT.md](PROJECT.md) — ustalenia projektowe i wymagania,
- [docs/ANDROID_PLAN.md](docs/ANDROID_PLAN.md) — aktualny priorytet i plan Androida,
- [docs/PHASE_1_LINUX_GUI.md](docs/PHASE_1_LINUX_GUI.md),
  [docs/PHASE_2_SPOTIFY_WEB_SESSION.md](docs/PHASE_2_SPOTIFY_WEB_SESSION.md),
  [docs/PHASE_3_YOUTUBE_STREAM.md](docs/PHASE_3_YOUTUBE_STREAM.md) — plany faz,
- [docs/WEBVIEW2_MIGRATION_PLAN.md](docs/WEBVIEW2_MIGRATION_PLAN.md) — migracja
  logowania desktop na WebView2 (status: odłożony),
- [docs/SPOTUBE_PLUGIN_ANALYSIS.md](docs/SPOTUBE_PLUGIN_ANALYSIS.md) — analiza
  rozwiązań wtyczki Spotube.

## Ograniczenia

Spotify i YouTube mogą zmienić API bez zapowiedzi. Nuta opiera się na
niepublicznych mechanizmach obu serwisów, więc wymaga utrzymywania po ich
zmianach. Przed publiczną dystrybucją należy ponownie ocenić zgodność
z regulaminami Spotify i YouTube.

## Licencja

Licencja open source zostanie wybrana przed pierwszą publikacją repozytorium.
