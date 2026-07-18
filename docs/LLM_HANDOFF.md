# Nuta — handoff dla kolejnego LLM

## Cel dokumentu

Ten dokument zawiera bieżący stan projektu, ustalenia z diagnozy Windows/Spotify, porównanie z pluginem Spotube oraz zalecane następne kroki. Przed zmianami należy przeczytać również `PROJECT.md` i `AGENTS.md`.

## Zasady repozytorium

- Nie uruchamiać Gradle, kompilatora, testów ani aplikacji bezpośrednio na hoście.
- Nie używać lokalnego `gradlew`, `gradlew.bat`, Javy ani SDK do budowania.
- Budowanie/testy wykonywać w Dockerze przez `scripts/build.ps1`.
- Aplikację Linux uruchamiać przez `scripts/run.ps1`.
- Wyjątek w `AGENTS.md`: podczas diagnozy aplikacji Windows można uruchamiać lokalną Javę i aplikację Windows.
- Host służy zasadniczo do edycji plików i sterowania Dockerem.

## Repozytorium i ostatni stan Git

Repozytorium: `https://github.com/jablonowskit/Nuta.git`

Gałąź: `main`

Ostatni znany commit:

```text
02ead0f Add hashed credential diagnostics
```

Wcześniejsze istotne commity:

```text
311185d Add detailed Spotify WebView diagnostics
1eae2e5 Allow third-party cookies in Spotify WebView
612429f Include HTTP client module in Windows runtime
1d5f8c7 Add Windows diagnostic console build
```

## Technologie

- Kotlin Multiplatform.
- Compose Multiplatform.
- Desktop JVM/Compose dla Linux/Windows/macOS.
- Android jako osobny moduł.
- Desktopowy WebView: `io.github.kevinnzou:compose-webview-multiplatform:2.0.3`.
- Desktopowa implementacja tej biblioteki korzysta z KCEF/JCEF, a nie z WebView2.
- Desktopowy player: `mpv` sterowany przez IPC.
- Spotify desktop: prywatny Web Player flow przez WebView.
- YouTube: własne wyszukiwanie HTML + prywatny Innertube/player endpoint.

## Struktura główna

```text
composeApp/src/commonMain
  modele, kontrakty, UI, fake repository/player, logging

composeApp/src/desktopMain
  Main.kt
  SpotifyLoginPrototype.kt
  SpotifyCookieSessionStore.kt
  SpotifyTestTokenStore.kt
  SpotifyWebSearchRepository.kt
  NutaYouTubeMediaService.kt
  MpvAudioPlayer.kt

androidApp
  obecnie demo z FakeSpotifyRepository i FakeAudioPlayer
```

## Obecny przepływ uruchamiania Windows

GitHub Actions używa natywnego runnera Windows:

```yaml
runs-on: windows-latest
```

Budowanie wykonywane jest przez:

```text
gradlew.bat --no-daemon :composeApp:packageExe
```

Compose Desktop jest skonfigurowany z:

```kotlin
targetFormats(TargetFormat.Exe)
packageName = "Nuta"
packageVersion = "0.1.1"
modules("java.net.http")
windows {
    console = true
}
```

`console = true` zostało włączone do diagnostyki launchera.

## Zdiagnozowany błąd JVM Windows

Pierwszy instalator uruchamiał się z komunikatem:

```text
Failed to launch JVM
```

Po uruchomieniu zainstalowanego pliku diagnostycznego z konsoli uzyskano dokładny błąd:

```text
java.lang.NoClassDefFoundError: java/net/http/HttpClient
Caused by: java.lang.ClassNotFoundException: java.net.http.HttpClient
```

Przyczyna: odchudzony runtime `jpackage` nie zawierał modułu `java.net.http`, mimo że kod używa `HttpClient` w Spotify i YouTube.

Naprawa została dodana w `composeApp/build.gradle.kts`:

```kotlin
modules("java.net.http")
```

Runtime w instalacji zawierał `jvm.dll`, ale nie zawierał `runtime/bin/java.exe`. Dla obrazu runtime `jpackage` nie musi to oznaczać błędu, ponieważ launcher może uruchamiać JVM przez bibliotekę JVM/JNI. Kluczowym realnym błędem był brak `java.net.http`.

## Instalator Windows — kod 1638

Próba instalacji nowego instalatora kończyła się pozornie bez komunikatu. Log Windows Installer wykazał:

```text
Product: Nuta -- Configuration failed.
kod: 1638
```

Przyczyna: na komputerze była już zainstalowana wersja `0.1.0`, a nowy instalator także miał `0.1.0`.

Naprawa:

```kotlin
packageVersion = "0.1.1"
```

## Obecna implementacja Spotify w Nuta

Plik: `composeApp/src/desktopMain/kotlin/app/nuta/spotify/SpotifyLoginPrototype.kt`.

Nuta nie używa oficjalnego OAuth i nie używa `clientId`/`clientSecret`.

Przepływ:

```text
accounts.spotify.com
  ↓ wykrycie /status
open.spotify.com
  ↓ opóźnienie 3 sekundy
JavaScript w WebView
  ↓ /api/server-time
GitHub Gist nuances.json
  ↓ sekret i wersja TOTP
TOTP SHA-1, 6 cyfr, interwał 30 s
  ↓ fetch('/api/token?...', credentials: include)
accessToken Web Playera
```

Nuta:

1. Otwiera WebView na `https://open.spotify.com/`.
2. Ogranicza nawigację do domen Spotify/Spotify CDN.
3. Po wejściu na `accounts.spotify.com/.../status` przechodzi do `open.spotify.com`.
4. Po 3 sekundach wykonuje JavaScript.
5. Pobiera czas przez `/api/server-time`.
6. Pobiera `nuances.json` z GitHub Gista.
7. Generuje TOTP przez Web Crypto.
8. Wywołuje `/api/token` w kontekście WebView z `credentials: include`.
9. Odczytuje token i czas wygaśnięcia.
10. Eksportuje cookies przez `CefCookieManager`.
11. Zapisuje token/cookies lokalnie w obecnej wersji testowej.

## Diagnostyka Spotify w Nuta

Obecna diagnostyka zapisuje do loggera:

- każdą domenę i ścieżkę obserwowanej nawigacji,
- obecność query stringa,
- HTTP status `/api/server-time`,
- HTTP status GitHub Gista,
- HTTP status `/api/token`,
- klucze odpowiedzi token endpointu,
- długość tokenu,
- SHA-256 tokenu,
- obecność czasu wygaśnięcia,
- `isAnonymous`,
- nazwy cookies bez wartości,
- domeny cookies,
- długości cookies,
- SHA-256 cookies,
- User-Agent WebView.

Nie zapisuje pełnych wartości:

- tokenu,
- `sp_dc`,
- `sp_t`,
- TOTP,
- nagłówka `Cookie`,
- nagłówka `Authorization`.

Ostatni commit diagnostyki:

```text
02ead0f Add hashed credential diagnostics
```

Po zbudowaniu wersji Windows należy szukać zdarzenia:

```text
web_token_diagnostics
```

## Błąd reCAPTCHA

Otrzymane logi JCEF:

```text
Third-party cookie will be blocked.
CO invokeChallengeCommand [object Object]
Solve error ... challenge-orchestrator ... status=400
```

Wniosek ostrożny:

- `status=400` nie dowodzi sam w sobie wykrycia JCEF.
- Możliwe są: blokada/utrata cookies, niepełny stan challenge, zła kolejność nawigacji, wygasła sesja challenge, problem JavaScript, sieć albo fingerprint WebView.
- Ostrzeżenie o third-party cookies jest informacyjne, nie jest jednoznacznym dowodem przyczyny.
- Pełny Edge na tym samym hoście działa, więc problem jest ograniczony do przepływu osadzonego WebView albo jego kolejności/stanu.

W Nuta wcześniej dodano argument KCEF:

```text
--disable-features=BlockThirdPartyCookies
```

Nie rozwiązało to błędu `challenge-orchestrator 400`.

## Analiza logu logowania z 2026-07-18 (Windows, wersja z konsolą)

Pełny log z jednej próby logowania na darmowym koncie potwierdził konkretny
przebieg. Kluczowe zdarzenia w kolejności:

```text
1. WebView otwiera open.spotify.com (wylogowany)
2. web_token_diagnostics: serverTimeStatus=200, gistStatus=200,
   token OK, isAnonymous=true            ← prywatny flow zadziałał w całości
3. redirect na accounts.spotify.com/en/login
4. dziesiątki "Third-party cookie will be blocked"
5. challenge.spotify.com/.../recaptcha
6. dalej "Third-party cookie will be blocked" x~30
7. CO invokeChallengeCommand → Solve error ... status=400
```

### Wniosek 1 — to NIE jest detekcja JCEF

Prywatny mechanizm tokenu (`/api/server-time`, Gist, TOTP SHA-1, `/api/token`)
przeszedł **bezbłędnie** i zwrócił poprawny token. Gdyby Spotify wykrywał i
blokował silnik JCEF, poległoby właśnie tutaj. Poległo dopiero na challenge
reCAPTCHA przy logowaniu. To osłabia, a nie wzmacnia, tezę o „wykrywaniu JCEF".

### Wniosek 2 — `isAnonymous=true` to nie błąd

To oczekiwany efekt zimnego startu bez sesji: kod pobiera token z wylogowanej
strony, wykrywa `isAnonymous` (SpotifyLoginPrototype.kt, gałąź `if (isAnonymous)`)
i słusznie przekierowuje na logowanie. Nie diagnozować tego jako usterki.

### Wniosek 3 — realny blocker to third-party cookies na challenge

Błąd `challenge-orchestrator 400` występuje bezpośrednio po dziesiątkach
komunikatów „Third-party cookie will be blocked" na `challenge.spotify.com`.
reCAPTCHA/challenge Spotify wymaga cookies third-party (Google). Zablokowane →
challenge nie może się rozwiązać → 400. To najmocniejsza korelacja w logu.

### Wniosek 4 — poprzednia flaga była nieskuteczna (no-op)

`--disable-features=BlockThirdPartyCookies` **nie działa**, bo
`BlockThirdPartyCookies` to nazwa polityki/prefa Chromium, a **nie** nazwa
`base::Feature`. `--disable-features=` ignoruje nieznane nazwy. Dlatego blokada
third-party cookies dalej obowiązywała.

W Chromium 122 ostrzeżenie „Third-party cookie will be blocked" pochodzi z
mechanizmu 3PCD. Zastosowana poprawka (do przetestowania na nowej wersji):

```text
--disable-features=TrackingProtection3pcd
```

Alternatywa pewniejsza od flagi CLI: ustawienie prefa CEF
`profile.cookie_controls_mode = 0` (allow all).

### Test rozstrzygający (jeśli 400 wróci mimo poprawki)

- Zaloguj się **ręcznie** w tym samym WebView (login/hasło z klawiatury,
  bez automatu). Jeśli challenge też daje 400 → przyczyną jest środowisko
  (cookies/reputacja profilu), nie kolejność przepływu Nuta.
- Sprawdź w polu `cookieNames` diagnostyki, czy w ogóle pojawiają się cookies
  Google/reCAPTCHA. Ich brak potwierdza hipotezę o third-party cookies.

### Uwaga o typie konta

Powyższe jest niezależne od typu konta. Użytkownik testuje na **darmowym koncie**
(Web Player). Przepływ `/api/token` i challenge są identyczne dla free i Premium,
więc typ konta nie wpływa na błąd 400. Premium nie jest potrzebne — audio w Nuta
i tak pochodzi z YouTube (`NutaYouTubeMediaService` + `MpvAudioPlayer`), nie ze
Spotify.

### Rekomendacja docelowa

DECYZJA PROJEKTU (2026-07-18): **OAuth jest wykluczony.** Cel = parytet ze
Spotube (WebView2/Edge + webview tylko do logowania + `/api/token` poza webview).
Szczegóły w sekcji „DECYZJA PROJEKTU (2026-07-18): OAuth WYKLUCZONY". Poprawka
flagi third-party cookies była tylko testem hipotezy (nieudanym), nie docelową
architekturą.

## ROZSTRZYGNIĘCIE — dowód wizualny z 2026-07-18 (build z fixem)

WAŻNE: build z flagą `TrackingProtection3pcd` został przetestowany i wynik jest
**negatywny** — flaga nie zmieniła zachowania (komunikaty „Third-party cookie
will be blocked" i błąd 400 nadal występują). Zrzuty ekranu z ręcznego logowania
pokazały pełny, jednoznaczny ciąg:

```text
1. Ekran "We need to make sure that you're a human"
2. reCAPTCHA v2 checkbox "I'm not a robot" -> ZIELONA FAJKA (zaliczona) + Continue
3. Po kliknieciu Continue -> "Something went wrong. Try again later."
```

To odpowiada logowi `challenge-orchestrator ... status=400`.

### Ostateczny wniosek

- reCAPTCHA **przechodzi** (Google akceptuje rozwiązanie — zielona fajka). Problem
  NIE leży w rozwiązywaniu captchy ani po stronie Google.
- Błąd pojawia się **po** poprawnym rozwiązaniu, gdy wynik trafia do backendu
  challenge Spotify — to **serwer Spotify odrzuca** klienta (400 →
  „Something went wrong").
- Sam fakt, że Google pokazał **trudny challenge obrazkowy** przed checkboxem,
  oznacza, że osadzony JCEF jest oceniany jako **wysokie ryzyko / bot-podobny**.
- To jest **wielowarstwowe odrzucenie osadzonej przeglądarki JCEF**, a nie
  pojedynczy przełącznik do przestawienia.

### Cookies — zdegradowane jako trop

Komunikaty „Third-party cookie will be blocked" pojawiają się także w normalnym
Chrome (ostrzeżenie deprecjacyjne) i **nie są dowodem** przyczyny. KCEF nie
wystawia czystej kontroli third-party cookies (`KCEFCookieManager` służy tylko do
odczytu/zapisu, brak `cookie_controls_mode`). Dalsze dłubanie przy cookies/flagach
w JCEF jest małoobiecujące — nie warto na tym tracić kolejnych rebuildów.

### DECYZJA PROJEKTU (2026-07-18): OAuth WYKLUCZONY — cel to parytet ze Spotube

OAuth PKCE / publiczne Web API są **wykluczone decyzją projektu**. NIE proponować
OAuth. Celem jest odtworzenie działającego rozwiązania wtyczki Spotube
(`spotube-plugin-spotify`).

Kluczowa obserwacja diagnostyczna: Spotube na Windows loguje się przez
**WebView2/Edge**, a nie JCEF — i dlatego przechodzi challenge (zaufany
silnik/profil). Problem Nuta to **silnik osadzonej przeglądarki** (JCEF jest
odrzucany po stronie serwera Spotify), a NIE architektura logowania przez webview
jako taka.

Dwa elementy do odtworzenia jak w Spotube:

1. **Silnik: WebView2 (Edge) zamiast JCEF/KCEF na Windows.** To jest sedno — to
   on pozwala przejść challenge. Wymaga natywnego mostu Windows (WebView2 przez
   JNI/COM albo inna biblioteka webview dla Compose/JVM). Nie da się tego załatwić
   zmianą importu w `compose-webview-multiplatform 2.0.3` (desktop = KCEF/JCEF).
2. **Flow Spotube: webview TYLKO do logowania.** Po wykryciu ścieżki `/status`
   eksportować cookies (`sp_dc`) z webview, a `/api/token` wywoływać **poza**
   webview kotlinowym `HttpClient` (sp_dc + User-Agent + TOTP z Gista +
   server-time). To odwraca obecny model Nuta, który robi `/api/token` przez JS
   `fetch` wewnątrz JCEF.

Ryzyko/uczciwie: przejście na WebView2 to realna praca natywna (JNI/COM), nie
trywialna podmiana. Ale to jedyna droga zgodna z decyzją projektu (bez OAuth) i
zgodna z tym, co faktycznie działa w Spotube. Dłubanie przy flagach/cookies w
JCEF zostało wyczerpane i nie działa (patrz wyżej).

## Dlaczego challenge pojawił się na Windows, a nie na Linux

Obserwacja użytkownika: ta sama aplikacja logowała się na **Linuksie bez
challenge reCAPTCHA**, a na **Windows challenge się pojawił** (400). Oba desktopy
używają tej samej biblioteki i tego samego silnika KCEF/JCEF.

### Kluczowy wniosek

Challenge Spotify jest **oparty na ocenie ryzyka, nie deterministyczny**. Nie
odpala się „zawsze dla JCEF" ani „nigdy dla Linuksa". Skoro identyczny flow JCEF
przeszedł na Linuksie bez challenge, Spotify **na pewno nie blokuje kategorycznie
JCEF** — gdyby wykrywał silnik, blokowałby na obu OS. Różnica leży w środowisku i
reputacji, nie w engine. To kolejny dowód przeciw tezie o detekcji JCEF.

### Najbardziej prawdopodobne przyczyny różnicy (malejąco)

1. **Ciepły vs zimny profil.** Profil WebView jest trwały (`persistSessionCookies
   = true`, `cachePath` w `nuta-spotify-webview/cache`). Na Linuksie po wielu
   testach profil miał cookies/historię/wcześniejsze logowanie. Windows to była
   świeża instalacja z pustym profilem → nowe, nierozpoznane urządzenie → wysokie
   ryzyko → challenge.
2. **Zaufane urządzenie / wcześniejsze logowanie** w profilu Linux obniża ryzyko.
3. **Reputacja IP/sieci.** reCAPTCHA mocno waży IP. Linux u użytkownika działa w
   Dockerze (`scripts/run.ps1`) — wyjście sieciowe może różnić się od natywnego
   Windows; inny albo „ogrzany" IP zmienia score.
4. **Różnice buildu Chromium/JBR per OS** (KCEF pobiera osobne buildy) — realne,
   ale drugorzędne wobec profilu i reputacji.

### Konsekwencja dla hipotezy third-party cookies

Na Linuksie `challenge.spotify.com` nigdy się nie załadował, więc blokada
third-party cookies **nie została tam wywołana** — a nie „nie istnieje". Ta sama
blokada prawdopodobnie siedzi też w wersji Linux, tylko nie zaszkodziła, bo
logowanie nie wymagało challenge.

### Zastrzeżenie / co zweryfikować

Nie ma logu z Linuksa. Możliwe też, że challenge tam wystąpił, ale **przeszedł**
(cookies/reputacja OK), a nie że go nie było. Rozstrzygnięcie: porównać log Linux
pod kątem `challenge.spotify.com` oraz `Third-party cookie will be blocked`.

## Różnica JCEF vs WebView2

JCEF na Windowsie nie jest WebView2/Edge. Jest to:

```text
Java → JNI → CEF → Chromium Embedded Framework
```

Spotube na Windowsie używa WebView dostarczonego przez Flutter/hosta; w zgłoszeniach projektu wskazywany jest Microsoft Edge WebView.

Oba silniki są oparte na Chromium, ale mogą różnić się:

- wersją Chromium,
- fingerprintem,
- profilem i magazynem cookies,
- politykami cookies,
- obsługą reCAPTCHA,
- integracją z systemem.

Biblioteka Compose WebView używana przez Nuta w wersji `2.0.3` ma desktop oparty o KCEF/JCEF. Nie ma prostej konfiguracji przełączającej ją na WebView2.

Przejście na WebView2 wymagałoby natywnego mostu Windows/JNI/COM albo innej biblioteki. Nie należy udawać, że wystarczy zmiana importu.

## Analiza pluginu Spotube

Repozytorium:

```text
https://github.com/sonic-liberation/spotube-plugin-spotify
```

Manifest pluginu `0.2.2`:

```json
"apis": ["webview", "localstorage", "timezone"],
"abilities": ["authentication", "metadata"]
```

Główny kod:

```text
src/plugin.ht
src/segments/auth.ht
src/segments/album.ht
src/segments/artist.ht
src/segments/browse.ht
src/segments/playlist.ht
src/segments/search.ht
src/segments/track.ht
src/segments/user.ht
```

Plugin działa podobnie do Nuta, ale ma inną architekturę:

```text
Spotube WebView abstraction
  ↓
getCookies()
  ↓
sp_dc
  ↓
osobny HttpClient
  ↓
api/token
  ↓
SpotifyGqlApi
```

Najważniejsza różnica względem Nuta:

- Nuta wykonuje `/api/token` przez JavaScript `fetch` wewnątrz JCEF po przejściu do `open.spotify.com`.
- Spotube po `/status` pobiera cookies i wykonuje `/api/token` poza WebView przez własny `HttpClient`.

Spotube nie używa oficjalnego OAuth PKCE.

Plugin:

1. Otwiera `Webview(uri: "https://accounts.spotify.com/")`.
2. Nasłuchuje URL-i.
3. Szuka ścieżki kończącej się na `/status`.
4. Pobiera cookies przez `webview.getCookies(url)`.
5. Odczytuje cookie `sp_dc`.
6. Pobiera parametry TOTP z GitHub Gista.
7. Pobiera czas serwera Spotify.
8. Generuje TOTP SHA-1, 6 cyfr, 30 sekund.
9. Wysyła `sp_dc`, User-Agent i TOTP do `/api/token` przez HTTP client.
10. Zapisuje cookies, access token i expiry w LocalStorage.
11. Ustawia token w `SpotifyGqlApi`.
12. Odświeża token cyklicznie.

Plugin ma również automatyczne sprawdzanie aktualizacji przez GitHub Releases i może pobierać nowy `plugin.smplug`.

## Ryzyka pluginu Spotube

- prywatny i zmienny endpoint `/api/token`,
- prywatny GraphQL Spotify,
- zależność od zewnętrznego Gista z parametrami TOTP,
- zapis cookies sesyjnych i tokenu w LocalStorage,
- w kodzie znajduje się logowanie TOTP i `sp_dc`,
- słabe/niepoprawne losowanie User-Agent,
- zewnętrzny plugin może zmienić kod niezależnie od aplikacji,
- WebView nadal może być blokowany przez reCAPTCHA.

Nie kopiować pluginu 1:1 bez usunięcia logowania sekretów i bez świadomej decyzji dotyczącej prywatnego API.

## Potencjalny błąd zamykania aplikacji

W logach pojawiło się:

```text
IllegalStateException: SkiaLayer is disposed
```

Stack trace wskazuje na wyścig między usuwaniem komponentu WebView/JCEF a niszczeniem okna Compose. Błąd pojawia się po:

```text
Application app_stopped
```

Nie jest przyczyną błędu reCAPTCHA, ale wymaga później poprawy kolejności zwalniania WebView/KCEF.

## Inne znane braki Windows

Po uruchomieniu aplikacji i naprawie JVM mogą pojawić się kolejne problemy:

### mpv

`MpvAudioPlayer` uruchamia:

```text
ProcessBuilder("mpv", ...)
```

Instalator Windows nie dołącza `mpv.exe`.

### Unix socket

Player używa:

```kotlin
Path.of("/tmp", ...)
StandardProtocolFamily.UNIX
UnixDomainSocketAddress
```

To jest implementacja linuxowa i nie jest poprawnym rozwiązaniem dla Windows.

### Android

Android nadal używa:

```text
FakeSpotifyRepository
FakeAudioPlayer
```

Nie ma jeszcze prawdziwego Spotify, Media3 ani odtwarzania w tle.

## Rekomendowane następne kroki

### Krótkoterminowa diagnostyka JCEF

1. Zbudować nowy instalator z GitHub Actions.
2. Zainstalować wersję `0.1.1` lub nowszą.
3. Wykonać jedną próbę logowania.
4. Zebrać zdarzenie `web_token_diagnostics`.
5. Porównać:
   - czy `/api/server-time` ma status 2xx,
   - czy Gist ma status 2xx,
   - czy `/api/token` ma status 2xx/400,
   - czy token ma długość większą od zera,
   - czy `isAnonymous` jest `true`,
   - jakie domeny cookies są obecne.

### Test wariantu Spotube-like

Jeżeli chcemy sprawdzić hipotezę o kolejności, można przeprojektować Nuta tak, aby:

1. WebView służył tylko do logowania.
2. Po `/status` eksportował cookies.
3. Kotlinowy `HttpClient` wykonywał `/api/token` poza WebView.
4. Do żądania przekazywany był wyłącznie `sp_dc` i wymagany User-Agent.

To nadal będzie prywatny mechanizm Spotify i nie gwarantuje przejścia reCAPTCHA.

### Docelowa architektura — parytet ze Spotube (OAuth WYKLUCZONY)

DECYZJA PROJEKTU (2026-07-18): OAuth NIE jest brany pod uwagę. Nie proponować
przeglądarki systemowej ani publicznego Web API. Cel to odtworzyć mechanizm
wtyczki Spotube:

```text
WebView2 / Edge (zaufany silnik, przechodzi challenge)
  ↓  webview TYLKO do logowania
wykrycie ścieżki /status
  ↓
eksport cookies z webview (sp_dc)
  ↓
kotlinowy HttpClient POZA webview
  ↓  sp_dc + User-Agent + TOTP (Gist) + server-time
/api/token
  ↓
prywatny Spotify GraphQL (jak teraz)
```

Sedno różnicy wobec działającego Spotube: **silnik logowania**. Spotube używa
WebView2/Edge (challenge przechodzi), Nuta używa JCEF (challenge odrzucany po
stronie serwera). Przejście na WebView2 wymaga natywnego mostu Windows (JNI/COM)
— to realna praca, nie podmiana importu. Drugi element to odwrócenie flow: token
liczony poza webview, nie przez JS `fetch` w JCEF.

## Ważne ostrzeżenia dla kolejnego LLM

- Nie logować pełnych sekretów tylko dlatego, że użytkownik deklaruje testowe konto.
- Nie commitować `clientSecret`, tokenów, cookies, plików sesji ani `.env`.
- Nie traktować `status=400` jako dowodu detekcji JCEF bez danych diagnostycznych. Log z 2026-07-18 pokazuje, że prywatny token flow działa, a 400 dotyczy wyłącznie challenge reCAPTCHA przy logowaniu (patrz „Analiza logu logowania z 2026-07-18").
- Nie przekazywać do `--disable-features=` nazw polityk/prefów (np. `BlockThirdPartyCookies`) — to nie są `base::Feature` i są ignorowane. Third-party cookies w Chromium 122 reguluje `TrackingProtection3pcd` albo pref `profile.cookie_controls_mode`.
- Nie twierdzić, że obecna biblioteka Compose WebView potrafi przełączyć się na WebView2 bez dodatkowej implementacji.
- Nie uruchamiać lokalnego Gradle/testów/aplikacji poza zasadami `AGENTS.md`.
