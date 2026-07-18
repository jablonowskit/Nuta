# Plan Android-first — najmniejsza i najoszczędniejsza aplikacja

Status: **aktywny priorytet** (2026-07-18). Decyzja: cel numer jeden to mała,
oszczędna bateryjnie aplikacja Android. Desktop (WebView2/JCEF) odłożony —
`docs/WEBVIEW2_MIGRATION_PLAN.md`.

## Zasada naczelna (dlaczego taki stack)

Najmniejszy i najoszczędniejszy Android = **standardowy natywny stack Kotlin +
ART**. Na Androidzie Kotlin kompiluje się do natywnego kodu (ART, AOT przy
instalacji) — nie ma „narzutu JVM" jak na desktopie. Dlatego:

- **NIE Flutter** — dokłada własny silnik renderujący (Skia/Impeller) + runtime →
  większa apka. Sprzeczne z celem rozmiaru.
- **NIE Kotlin/Native** — to ścieżka iOS/desktop/embedded, nie Android; nie
  integruje się z frameworkiem Androida. Zero zysku, dużo strat.
- **TAK: Kotlin + Jetpack Compose + Media3 + systemowy WebView.**

## Stack dobrany pod rozmiar i baterię

| Warstwa | Wybór | Dlaczego |
|---|---|---|
| UI | Jetpack Compose (już wspólne w `commonMain`) | reużycie; Views tylko jeśli rozmiar krytyczny |
| Logowanie | **systemowy `android.webkit.WebView`** | 0 MB (Chromium preinstalowany), zaufany silnik → challenge Spotify najpewniej przechodzi |
| Audio | **Media3 / ExoPlayer** + `MediaSessionService` | dekodowanie sprzętowe, background, audio focus, sterowanie z powiadomienia/lockscreen — oszczędne bateryjnie |
| HTTP | **Ktor** (silnik OkHttp) | `java.net.http` NIE istnieje na Androidzie; Ktor jest multiplatform |

## Co reużywamy, co portujemy, co nowe

**Reużywamy (już w `commonMain`):** modele (`Models.kt`), kontrakty
(`SpotifyRepository`, `AudioPlayer` w `domain/Contracts.kt`), Compose UI
(`NutaApp`, `App.kt`), logging, `SecretValue`, kontrakty YouTube.

**Portujemy z `desktopMain` (java.net.http → Ktor, docelowo do `commonMain`):**
- `SpotifyWebSearchRepository` (prywatne GraphQL),
- `NutaYouTubeMediaService` (Innertube resolve + ranking — logika parsowania i
  rankingu jest czysta i przenośna),
- liczenie `/api/token` + TOTP (HMAC-SHA1; `javax.crypto.Mac` dostępny i na JVM,
  i na Androidzie).

**Nowe (`composeApp/src/androidMain`):**
- logowanie: `WebView` + przechwycenie `sp_dc`,
- `AudioPlayer` na Media3 (zamiast `MpvAudioPlayer`),
- foreground `MediaSessionService`.

Uwaga o strukturze: `composeApp` jest biblioteką KMP z targetami `androidLibrary`
+ `desktop`. Implementacje androidowe trafiają do `composeApp/src/androidMain`,
a moduł `androidApp` tylko je wstrzykuje w `MainActivity` (dziś wstrzykuje Fake'i).

## Logowanie na Androidzie (prostsze niż desktop)

Systemowy WebView to zaufany Chromium — inaczej niż JCEF na Windows, więc problem
`challenge-orchestrator 400` najpewniej nie wystąpi. Flow (model Spotube):

```text
WebView → accounts.spotify.com
  → użytkownik loguje się ręcznie
  → po zalogowaniu: CookieManager.getCookie("https://open.spotify.com") → sp_dc
  → /api/token liczony POZA WebView (Ktor): server-time + TOTP(Gist) + Cookie: sp_dc
  → accessToken → prywatne GraphQL
```

**httpOnly:** Android `CookieManager.getCookie(url)` zwraca cookies łącznie z
`httpOnly` (inaczej niż JS `document.cookie`), więc `sp_dc` da się odczytać bez
natywnych sztuczek. To eliminuje główny problem, który komplikował wariant desktop.

## Audio i bateria

- Media3 `ExoPlayer` + `MediaSessionService` (Media3) → background playback,
  sterowanie z powiadomienia i lockscreena, poprawny audio focus.
- Foreground service **tylko** podczas odtwarzania; brak wakelocków na siłę.
- Strumień audio z YouTube (jak na desktopie) odtwarzany przez ExoPlayer
  (progressive/HLS, dekodowanie sprzętowe).

## Rozmiar — konkretne dźwignie

- **R8 full mode** + `shrinkResources true` w release.
- **Android App Bundle** (splity per ABI/gęstość) → Play dostarcza minimalny APK.
- Nie dołączać Chromium/mpv/Flutter.
- Media3: tylko potrzebne moduły (`media3-exoplayer`, `media3-session`; bez
  `media3-ui` jeśli własne UI).
- Ograniczyć zależności; rozważyć Views zamiast Compose tylko jeśli rozmiar
  okaże się krytyczny (Compose dodaje kilka MB, ale daje reużycie UI).

## Fazy (przyrostowo, każda weryfikowalna)

1. **Fundament:** dodać do `libs.versions.toml` Ktor (core + okhttp + content
   negotiation + serialization) i Media3 (`exoplayer`, `session`); utworzyć
   `composeApp/src/androidMain` z zależnościami; włączyć R8/App Bundle w release.
2. **Port HTTP:** przenieść Spotify repo + YouTube resolver z `java.net.http` na
   Ktor do `commonMain` (lub `androidMain` na start). Testy parsowania/rankingu.
3. **Logowanie:** `WebView` (Composable/Activity) → `sp_dc` → token; podmiana
   `FakeSpotifyRepository` na prawdziwe w `MainActivity`.
4. **Audio:** `Media3AudioPlayer` + `MediaSessionService` + foreground; podmiana
   `FakeAudioPlayer`.
5. **Optymalizacja:** R8/App Bundle, pomiar rozmiaru APK i profil baterii
   (Battery Historian / Android Studio Energy Profiler).

## Pytania otwarte / decyzje

- **Compose vs Views:** rekomendacja Compose (reużycie UI z desktopem). Views
  tylko jeśli rozmiar APK okaże się krytyczny — do decyzji po pomiarze Fazy 5.
- **Ktor w `commonMain` (unifikacja z desktopem) czy tylko `androidMain`?**
  Rekomendacja: docelowo `commonMain` (jeden klient HTTP dla obu platform), ale
  można zacząć od `androidMain`, żeby nie ruszać działającego desktopu.
- **minSdk 26** (Android 8.0) — zostaje, wystarczające dla Media3 i WebView.

## Emulator do testów (Windows 11 host)

Ustalenia z 2026-07-18:

- **System image: API 36, target „Google Play" (nie samo „Google APIs")** —
  tylko wariant Google Play ma Android System WebView aktualizowany przez Play
  Store. WebView musi wspierać `crypto.subtle` (HMAC-SHA1 do liczenia TOTP w
  `SpotifyAndroidLogin.kt`) i obsłużyć ewentualny reCAPTCHA/challenge Spotify —
  starszy, niedoaktualizowalny WebView z obrazu „Google APIs" tego nie gwarantuje.
- **ABI:** x86_64 na hoście Intel/AMD (arm64-v8a na hoście ARM).
- **Profil urządzenia:** Pixel 8/9, RAM 2048–4096 MB, GLES automatyczne.
- **Akceleracja:** Android Emulator hypervisor driver (WHPX/Hyper-V) — wbudowany
  w AVD, zastępuje HAXM, działa na Win11 bez dodatkowej konfiguracji.
- Po utworzeniu AVD: ręcznie zaktualizować „Android System WebView" przez Play
  Store w emulatorze przed pierwszym testem logowania.
- Opcjonalnie: drugi AVD na **API 26** (= minSdk) do weryfikacji zgodności
  wstecznej WebView/logowania.
- Powód wyboru Google Play potwierdzony też przez ten plan: logowanie zakłada
  „zaufany silnik Chromium" jako fundament unikania błędu, który dotknął
  desktopowy WebView2/JCEF (`challenge-orchestrator 400`) — patrz sekcja
  „Logowanie na Androidzie" wyżej.

## Nie zmienia się

- Prywatny mechanizm token/GraphQL Spotify (ten sam protokół co dziś i co Spotube).
- Wyszukiwanie i pobieranie audio z YouTube.
- Darmowe konto Spotify wystarcza (audio z YouTube, nie ze Spotify).
