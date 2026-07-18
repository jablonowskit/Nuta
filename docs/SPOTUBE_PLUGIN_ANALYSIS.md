# Analiza pluginu Spotify dla Spotube

## Źródło analizy

Repozytorium:

`https://github.com/sonic-liberation/spotube-plugin-spotify`

Analizowana wersja manifestu: `0.2.2`.

Plugin jest napisany w języku Hetu i ładowany przez system pluginów Spotube. Nie jest oficjalnym pluginem Spotify. Manifest określa go jako plugin metadanych:

```json
{
  "version": "0.2.2",
  "name": "Spotify",
  "entryPoint": "SpotifyMetadataProviderPlugin",
  "apis": ["webview", "localstorage", "timezone"],
  "abilities": ["authentication", "metadata"],
  "pluginApiVersion": "2.0.0"
}
```

## Najważniejszy wniosek

Plugin nie używa standardowego OAuth Spotify i nie korzysta z `clientId` ani `clientSecret`.

Używa prywatnego mechanizmu Web Playera Spotify:

```text
WebView accounts.spotify.com
        ↓
cookie sp_dc
        ↓
sekret TOTP + czas serwera Spotify
        ↓
open.spotify.com/api/token
        ↓
krótkotrwały token Web Playera
        ↓
prywatne zapytania Spotify GraphQL
```

Jest to mechanizm podobny do obecnego prototypu Nuta, a nie bezpieczny/publiczny kontrakt OAuth.

## Szczegółowy przepływ logowania

Główny kod znajduje się w `src/segments/auth.ht`.

### 1. Utworzenie WebView

Plugin tworzy WebView wskazujący na:

```text
https://accounts.spotify.com/
```

Następnie nasłuchuje zmian adresu URL.

### 2. Wykrycie zakończenia logowania

Plugin szuka adresu pasującego do wzorca:

```text
https://accounts.spotify.com/.../status
```

Po wykryciu tego adresu pobiera cookies WebView przez `webview.getCookies(url)`.

### 3. Odczyt cookie `sp_dc`

Z pobranej listy cookies wybierane jest cookie o nazwie:

```text
sp_dc
```

Jego wartość jest używana jako dowód zalogowania do Web Playera.

Plugin przechowuje również pozostałe cookies, między innymi `sp_t`, ponieważ późniejszy klient GraphQL wykorzystuje je przy niektórych żądaniach.

### 4. Pobranie parametrów TOTP

Plugin pobiera dane z publicznego GitHub Gista:

```text
https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef
```

Z pliku `nuances.json` wybierany jest wpis o najwyższym numerze wersji. Wpis zawiera między innymi:

- wersję TOTP (`v`),
- sekret TOTP (`s`).

Oznacza to, że działanie pluginu zależy od zewnętrznego Gista, który może zmienić się niezależnie od pluginu.

### 5. Pobranie czasu serwera Spotify

Plugin wywołuje:

```text
https://open.spotify.com/api/server-time
```

Z odpowiedzi pobierane jest `serverTime`. Jest on używany zamiast lokalnego zegara, aby kod TOTP był zgodny z zegarem Spotify.

### 6. Wygenerowanie TOTP

Plugin generuje sześciocyfrowy kod TOTP:

- algorytm: SHA-1,
- długość: 6 cyfr,
- interwał: 30 sekund,
- czas: czas serwera Spotify.

### 7. Pobranie tokenu Web Playera

Plugin wywołuje prywatny endpoint:

```text
https://open.spotify.com/api/token?reason=transport&productType=web-player&totp=<TOTP>&totpServer=<TOTP>&totpVer=<wersja>
```

W nagłówkach wysyła między innymi:

```text
Cookie: sp_dc=<wartość>
User-Agent: <losowo zbudowany tekst>
```

Oczekiwana odpowiedź zawiera:

- `accessToken`,
- `accessTokenExpirationTimestampMs`.

### 8. Zapis sesji

Plugin zapisuje w `LocalStorage` obiekt zawierający:

```text
cookies
accessToken
expiration
```

Po ponownym uruchomieniu plugin próbuje odtworzyć ten obiekt. Jeżeli token nie wygasł, emituje zdarzenie `recovered`. Jeżeli wygasł, wykonuje odświeżenie na podstawie zapisanych cookies.

### 9. Odświeżanie tokenu

Po zdarzeniu `recovered` lub `login` uruchamiany jest timer. Timer po czasie wygaśnięcia ponownie:

1. odczytuje zapisane cookies,
2. pobiera aktualny sekret TOTP,
3. pobiera czas serwera Spotify,
4. generuje nowy TOTP,
5. wywołuje `/api/token`,
6. zapisuje nowy token i czas wygaśnięcia.

## Użycie tokenu

Plik `src/plugin.ht` tworzy:

```text
SpotifyAuthEndpoint
SpotifyGqlApi
AlbumEndpoint
ArtistEndpoint
BrowseEndpoint
PlaylistEndpoint
SearchEndpoint
TrackEndpoint
UserEndpoint
```

Po zdarzeniu `recovered`, `login` lub `refreshed` token jest przekazywany do klienta GraphQL przez:

```text
api.setAccessToken(auth.credentials["accessToken"])
```

Poszczególne endpointy pluginu używają prywatnego klienta GraphQL Spotify do:

- wyszukiwania utworów,
- pobierania playlist,
- pobierania utworów playlist,
- pobierania albumów,
- pobierania artystów,
- pobierania danych użytkownika,
- pobierania rekomendacji i danych przeglądania.

Klient GraphQL jest dostarczany przez submoduł:

```text
dependencies/hetu_spotify_gql_client
```

Podczas lokalnej analizy submoduł nie został pobrany, ponieważ jego wpis Git używa adresu SSH `git@github.com`. Główna logika uwierzytelniania była jednak dostępna i została przeanalizowana.

## Architektura pluginu

Plugin Spotube ma dwie warstwy:

```text
Spotube Plugin Runtime
        ↓
plugin.ht / Hetu
        ├── WebView API
        ├── LocalStorage API
        ├── HTTP Client
        └── Timezone API
```

Manifest deklaruje uprawnienia `webview`, `localstorage` i `timezone`. Dzięki temu plugin sam nie implementuje natywnego WebView dla każdego systemu — korzysta z API dostarczonego przez Spotube.

## Istotne różnice względem obecnej Nuta

Obecna Nuta:

- używa JCEF przez `compose-webview-multiplatform`,
- pobiera token Web Playera z poziomu Kotlin/JVM,
- posiada własny `SpotifyCookieSessionStore`,
- posiada własny `SpotifyTestTokenStore`,
- wykonuje prywatne zapytania Spotify GraphQL.

Spotube:

- używa abstrakcji WebView plugin runtime'u,
- odczytuje cookies po zakończeniu logowania,
- generuje TOTP na podstawie aktualnego czasu serwera Spotify,
- zapisuje cookies i token w LocalStorage pluginu,
- ma wydzielone segmenty endpointów GraphQL,
- automatycznie sprawdza aktualizacje pluginu przez GitHub Releases.

Sam mechanizm Spotify jest jednak zasadniczo taki sam: `sp_dc` + TOTP + prywatny `/api/token`.

## Problemy i ryzyka

### 1. Brak stabilnego publicznego kontraktu

Endpoint `/api/token`, format TOTP, wersja TOTP i prywatne GraphQL mogą zmienić się bez zapowiedzi.

### 2. Zależność od zewnętrznego Gista

Sekret i wersja TOTP są pobierane dynamicznie z GitHub Gista. Awaria, zmiana lub przejęcie Gista może zatrzymać logowanie albo zmienić zachowanie pluginu.

### 3. Cookies i token są bardzo wrażliwe

`sp_dc` jest cookie sesyjnym Spotify. Wykradzenie go może umożliwić przejęcie sesji Web Playera.

Plugin zapisuje cookies i token w LocalStorage bez dodatkowej warstwy szyfrowania widocznej w kodzie pluginu.

### 4. Wyciek sekretów do loga

W `getToken` znajduje się logowanie wartości TOTP i `sp_dc`:

```text
print("Timestamp: ... TOTP: ... spDc: ...")
```

To jest krytyczny błąd bezpieczeństwa. Takich danych nie wolno logować.

### 5. Słabe losowanie User-Agent

Plugin próbuje tworzyć losowy fragment User-Agent przy użyciu ogólnego `Random`, a funkcja `randomBytesFromMath` nie zwraca poprawnie zbudowanego stringa. Nie należy traktować tego jako zabezpieczenia kryptograficznego.

### 6. WebView i reCAPTCHA

Plugin nadal loguje przez WebView. Dlatego może mieć te same problemy z reCAPTCHA, cookies third-party i fingerprintem osadzonej przeglądarki co Nuta. Różnica w działaniu może wynikać z innego silnika WebView i innego profilu przeglądarki, a nie z innego protokołu Spotify.

### 7. Aktualizacje pluginu z GitHub

`CorePlugin.checkUpdate` sprawdza GitHub Releases i zwraca URL do nowego `plugin.smplug`. W praktyce kod pluginu może zostać podmieniony niezależnie od głównej aplikacji.

## Wnioski dla Nuta

DECYZJA PROJEKTU (2026-07-18): **OAuth jest wykluczony.** Celem jest parytet ze
Spotube. Poniżej pozostawiono OAuth wyłącznie jako odrzucony kontekst.

### Kierunek A — oficjalny OAuth PKCE (ODRZUCONY decyzją projektu)

Oficjalny OAuth Authorization Code + PKCE w przeglądarce systemowej byłby
publicznym, wspieranym mechanizmem, ale **nie jest brany pod uwagę** — wymagałby
przepisania klienta na publiczne Web API i rezygnacji z prywatnego GraphQL.
Zostawione tu tylko po to, żeby kolejny LLM nie proponował tego ponownie.

### Kierunek B — zgodność z pluginem Spotube (WYBRANY)

To jest docelowy kierunek. Trzeba zaimplementować:

- **WebView2 / Edge jako silnik logowania na Windows** (a NIE JCEF) — to jest
  element, który realnie odblokowuje challenge Spotify; wymaga natywnego mostu
  Windows (JNI/COM). Sam protokół Spotify jest identyczny jak w obecnej Nuta,
  więc różnicę robi silnik/profil przeglądarki, nie protokół,
- webview TYLKO do logowania,
- wykrywanie zakończenia logowania (ścieżka `/status`),
- odczyt `sp_dc` (i `sp_t`) z cookies webview,
- `/api/token` wywoływany **poza** webview kotlinowym `HttpClient`
  (sp_dc + User-Agent + TOTP z Gista + czas serwera),
- prywatne GraphQL (jak teraz),
- odświeżanie tokenu na timerze przed wygaśnięciem.

Uwaga bezpieczeństwa: w Nuta NIE wolno logować cookies, TOTP ani tokenów (błąd,
który plugin Spotube popełnia w `getToken`), a dane sesyjne powinny trafiać do
magazynu platformowego lub być szyfrowane.

## Rekomendacja

Realizować **Kierunek B (parytet Spotube)** zgodnie z decyzją projektu. Sedno to
zamiana silnika logowania JCEF → WebView2/Edge plus odwrócenie flow (token liczony
poza webview). OAuth pozostaje wykluczony. Szczegóły diagnozy, dlaczego JCEF jest
odrzucany po stronie serwera, są w `docs/LLM_HANDOFF.md`
(sekcja „ROZSTRZYGNIĘCIE — dowód wizualny z 2026-07-18").
