# Plan migracji logowania Spotify: JCEF → WebView2 (parytet Spotube)

Status: **ODŁOŻONY** (2026-07-18). Priorytet przeniesiony na **Android-first** —
`docs/ANDROID_PLAN.md`. Ten dokument dotyczy desktopu Windows i wraca do gry
dopiero, gdy desktop znów stanie się priorytetem. Decyzja projektu: OAuth
wykluczony, cel to parytet ze Spotube. Diagnoza: `docs/LLM_HANDOFF.md` sekcja
„ROZSTRZYGNIĘCIE — dowód wizualny z 2026-07-18". Mechanizm Spotube:
`docs/SPOTUBE_PLUGIN_ANALYSIS.md`.

DECYZJA (2026-07-18): rozważano pełny rewrite na native (Flutter / Rust-Tauri /
Kotlin-Native) „bez Javy". Odrzucone dla celu Android-first, bo prawdziwym celem
jest **mała, oszczędna apka Android**, a tam standardowy Kotlin+ART już jest
natywny (Flutter/Kotlin-Native tylko powiększają apkę). Na desktopie, gdy wróci,
plan pozostaje: PoC helpera WebView2 → decyzja helper vs native.

## 1. Cel

Zamienić silnik logowania Spotify z JCEF/KCEF (odrzucany przez backend Spotify —
reCAPTCHA zaliczana, a i tak `challenge-orchestrator 400`) na **WebView2 / Edge**
(zaufany silnik, którego używa działający plugin Spotube), zachowując resztę
architektury Nuta (prywatny `/api/token`, prywatny GraphQL, audio z YouTube).

Zakres dotyczy **wyłącznie desktopu Windows**. Linux/macOS i Android są poza tym
planem (Linux dziś działa na JCEF; osobna decyzja później).

## 2. Twardy haczyk techniczny, który wymusza architekturę

**Cookie `sp_dc` jest `httpOnly`.** JavaScript `document.cookie` **nie widzi**
cookies httpOnly. Obecna Nuta liczy token przez JS `fetch` wewnątrz WebView z
`credentials: include`, więc nie musi czytać `sp_dc` samodzielnie — przeglądarka
dokłada go automatycznie. Po przejściu na model Spotube (`/api/token` liczony
**poza** webview) trzeba `sp_dc` **odczytać jawnie**, a to wymaga natywnego API
cookies:

```text
WebView2: CoreWebView2.CookieManager.GetCookiesAsync(url)   ← widzi httpOnly
```

Wniosek: **minimalne biblioteki webview oparte tylko o JS (navigate/eval) nie
wystarczą** — potrzebny jest dostęp do WebView2 CookieManager (COM/WinRT).

## 3. Wybrana architektura: samodzielny helper logowania WebView2

Zamiast osadzać WebView2 w oknie Compose (trudne hostowanie HWND w Skia/Swing),
logowanie realizuje **osobny mały proces natywny Windows**. To jest zgodne z
modelem Spotube („webview tylko do logowania") i omija dwa najtrudniejsze
problemy naraz (hosting HWND + odczyt httpOnly).

```text
Nuta (JVM/Compose)
  │  ProcessBuilder — uruchamia helper
  ▼
nuta-spotify-login.exe  (WebView2, natywny Windows)
  │  1. otwiera accounts.spotify.com
  │  2. użytkownik loguje się ręcznie (challenge PRZECHODZI — to prawdziwy Edge)
  │  3. wykrywa nawigację na .../status
  │  4. CookieManager.GetCookiesAsync → sp_dc (+ sp_t)  [w tym httpOnly]
  │  5. wypisuje JSON {sp_dc, sp_t, ...} na stdout / plik tymczasowy, kończy się
  ▼
Nuta (Kotlin)
  │  6. odczytuje sp_dc
  │  7. /api/token liczony w Kotlinie (java.net.http):
  │       server-time + TOTP(HMAC-SHA1, Gist) + Cookie: sp_dc
  │  8. accessToken + expiry → istniejący SpotifyWebSearchRepository / GraphQL
  ▼
reszta bez zmian (wyszukiwanie, YouTube resolve, mpv)
```

### Dlaczego helper, a nie osadzenie w Compose

- **httpOnly**: helper ma natywny CookieManager; osadzony minimalny webview nie.
- **Hosting HWND**: brak potrzeby wciskania WebView2 w okno Skia/Swing.
- **Izolacja**: crash/leak przeglądarki nie zabija procesu Nuta (dziś mamy
  `SkiaLayer is disposed` przy zamykaniu JCEF — ten problem znika).
- **Zgodność ze Spotube**: dokładnie „webview tylko do logowania".

## 4. Wybór technologii helpera (DECYZJA DO POTWIERDZENIA)

| Opcja | Plusy | Minusy |
|---|---|---|
| **C# .NET (WinForms/WPF + WebView2)** — rekomendacja | najszybsza implementacja, oficjalny SDK `Microsoft.Web.WebView2`, proste CookieManager | większy artefakt (self-contained ~10–70 MB) lub zależność od .NET runtime |
| **C++ / Win32 + WebView2 SDK** | maleńki exe, brak zależności runtime poza WebView2 | wolniejsza implementacja, COM ręcznie |
| JxBrowser (osadzony w Compose) | osadzenie w oknie, API cookies | komercyjny/płatny — odrzucony |

Rekomendacja: **C# .NET self-contained single-file** (najszybciej do działającego
MVP; rozmiar akceptowalny). Do potwierdzenia przez użytkownika.

## 5. Wymóg runtime: WebView2 Evergreen

- WebView2 Runtime jest zwykle preinstalowany na Win10/11 (z Edge). Użytkownik jest
  na Windows 10 Pro — prawdopodobnie obecny.
- Helper na starcie sprawdza dostępność runtime; przy braku pokazuje instrukcję /
  link do Evergreen bootstrapper. Instalatora WebView2 **nie** dołączamy do Nuta.

## 6. Zmiany w kodzie Nuta (JVM)

### Nowe

- `composeApp/src/desktopMain/kotlin/app/nuta/spotify/SpotifyLoginHelperClient.kt`
  — uruchamia `nuta-spotify-login.exe`, czyta JSON z cookies, zwraca `sp_dc`/`sp_t`.
- `.../spotify/SpotifyWebTokenClient.kt` — Spotube-style liczenie tokenu w Kotlinie:
  `GET /api/server-time`, pobranie sekretu/wersji TOTP z Gista, TOTP HMAC-SHA1
  (javax.crypto.Mac), `GET /api/token?...` z nagłówkiem `Cookie: sp_dc=...` i
  User-Agentem. Zwraca `SpotifyWebToken`.
- `native/spotify-login/` — projekt helpera (C#/C++), budowany osobno w CI.

### Zmienione

- `SpotifyLoginPrototype.kt` — zamiast ekranu JCEF: prosty ekran „Logowanie…",
  który wywołuje helper i token client. Docelowo cały JCEF-owy WebView znika z
  desktopu.
- `Main.kt` — bez zmian w logice sesji; podmieniamy tylko implementację logowania.
- `SpotifyTestTokenStore.kt` — **naprawić ścieżkę** (dziś linuxowa
  `/home/nuta/...`, na Windows nie działa → sesja się nie utrwala). Użyć
  `%LOCALAPPDATA%\Nuta`. Rozważyć szyfrowanie (Windows DPAPI) dla `sp_dc`/tokenu.

### Do usunięcia (po weryfikacji WebView2)

- Zależność `libs.compose.webview` (KCEF/JCEF) w `desktopMain` — znika pobieranie
  runtime KCEF, znika błąd `SkiaLayer is disposed`.
- `SpotifyCookieSessionStore.kt` (oparty o `CefCookieManager`) — zbędny.

## 7. Zmiany w buildzie / CI

- `.github/workflows/linux-gui.yml`, job `build-windows-exe` (już `windows-latest`):
  dodać krok budowania helpera. `windows-latest` ma preinstalowany .NET SDK i MSVC.
  - C#: `dotnet publish -c Release -r win-x64 --self-contained -p:PublishSingleFile=true`
- **Bundlowanie helpera w instalatorze**: Compose Desktop wspiera dołączanie
  dodatkowych plików przez `nativeDistributions { appResourcesRootDir.set(...) }`.
  Skopiować `nuta-spotify-login.exe` do katalogu zasobów przed `packageExe`, żeby
  trafił do `app/resources`. Helper lokalizować w runtime przez
  `compose.application.resources.dir` / ścieżkę względem instalacji.
- Bump `packageVersion` przy każdym instalatorze (inaczej kod 1638).

## 8. Fazy wdrożenia (przyrostowo, każda weryfikowalna)

1. **PoC helpera (samodzielny).** Zbudować `nuta-spotify-login.exe`, ręcznie
   sprawdzić, że: otwiera Spotify, challenge **przechodzi**, po `/status` wypisuje
   `sp_dc`. To weryfikuje główną hipotezę (WebView2 ≠ JCEF). Bez integracji z Nuta.
   → jeśli tu challenge też pada, cała migracja nie ma sensu — to bramka krytyczna.
2. **Token w Kotlinie.** `SpotifyWebTokenClient` — dla ręcznie wklejonego `sp_dc`
   policzyć `/api/token` i dostać `accessToken` (`isAnonymous:false`).
3. **Integracja.** `SpotifyLoginHelperClient` + podmiana ekranu logowania; pełny
   flow login → token → wyszukiwanie → YouTube → mpv.
4. **Utrwalanie sesji.** Naprawa `SpotifyTestTokenStore` (ścieżka Windows + DPAPI),
   odświeżanie tokenu na timerze (jak Spotube).
5. **Sprzątanie.** Usunięcie JCEF/KCEF z desktopu, `SpotifyCookieSessionStore`,
   argów KCEF; aktualizacja dokumentacji.

## 9. Ryzyka i pytania otwarte

- **Bramka krytyczna (Faza 1):** czy WebView2 ze świeżym `user-data-folder` też
  dostanie trudny challenge? Dowód ze Spotube sugeruje, że przechodzi, ale to
  hipoteza do potwierdzenia. Mitygacja: trwały `user-data-folder` (rozgrzewanie
  profilu), realny User-Agent Edge.
- **Rozmiar instalatora** przy self-contained .NET (do zaakceptowania?).
- **Bezpieczeństwo:** NIE logować `sp_dc`, TOTP ani tokenu (błąd, który popełnia
  Spotube). Sesję trzymać zaszyfrowaną (DPAPI), nie plaintext.
- **Zależność od Gista TOTP i prywatnego `/api/token`** — pozostaje, jak w Spotube;
  może się zmienić bez zapowiedzi.
- **Nie da się zbudować/przetestować helpera lokalnie** (AGENTS.md: build w
  Dockerze/CI; wyjątek tylko na diagnozę aplikacji Windows). Helper buduje się na
  `windows-latest`, jak główny exe.

## 10. Co zostaje bez zmian

- Prywatny mechanizm token/GraphQL Spotify (ten sam protokół co dziś i co Spotube).
- Wyszukiwanie i resolve audio z YouTube (`NutaYouTubeMediaService`).
- Odtwarzanie przez `mpv` (`MpvAudioPlayer`).
- Darmowe konto Spotify wystarcza (audio i tak z YouTube).
