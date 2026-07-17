# Faza 2 — SpotifyWebSessionAdapter

## 1. Cel

### Stan realizacji (2026-07-17)

- [x] zależność KCEF/WebView i wymagane biblioteki Linux,
- [x] uruchomienie WebView w obrazie Docker,
- [x] wyświetlenie prawdziwej strony logowania Spotify przez noVNC,
- [x] potwierdzenie, że logowanie Spotify i Web Player działają w KCEF,
- [x] udokumentowanie błędu KCEF: menedżer cookies nie udostępnia sesji zalogowanego WebView,
- [x] zastąpienie odczytu `sp_dc` pobraniem krótkotrwałego tokenu wewnątrz zalogowanej strony,
- [x] przekazanie tokenu wyłącznie w pamięci do repozytorium wyszukiwania,
- [x] ręczne potwierdzenie powrotu do Nuta i prawdziwych wyników wyszukiwania,
- [x] automatyczne odświeżanie tokenu w prototypie Linux,
- [ ] prywatny klient playlist.

### Odświeżanie sesji w prototypie Linux

Profil KCEF utrwala cookies sesyjne w prywatnym katalogu kontenera. Nuta zapamiętuje wyłącznie
czas wygaśnięcia krótkotrwałego tokenu i pięć minut wcześniej ponownie uruchamia przepływ
WebView. Jeżeli sesja Spotify nadal jest ważna, nowy token jest pobierany bez wpisywania danych
logowania, a aktywne repozytorium zostaje atomowo zastąpione. Zdarzenia odświeżenia nie zawierają
tokenu, cookies ani pełnego URL-a.

Profil jest trwały względem kolejnych kontenerów dzięki nazwanemu wolumenowi Dockera. Docelowe
przechowywanie na Androidzie będzie korzystać z magazynu chronionego przez platformę.

## Aktualna decyzja implementacyjna

Na Linuxie Compose WebView/KCEF poprawnie utrzymuje zalogowaną sesję, ale zarówno globalne,
jak i filtrowane API cookies zwraca pusty zbiór. Nie ponawiamy odczytu `sp_dc` przez ten adapter.
Po załadowaniu `open.spotify.com` kod wykonywany w kontekście strony pobiera token web-playera,
a do Kotlin trafiają wyłącznie token i czas wygaśnięcia. Wartości pozostają w pamięci i są
opakowane w `SecretValue`.

Pierwszy pionowy prototyp obsługuje token do chwili jego wygaśnięcia. Odświeżanie bez ponownego
logowania jest osobnym kryterium następnego pakietu i nie może polegać na niedziałającym API cookies.

Celem fazy jest zastąpienie `FakeSpotifyRepository` działającym adapterem, który loguje użytkownika przez stronę Spotify, uzyskuje sesję web-playera i pobiera playlisty, utwory oraz wyniki wyszukiwania.

Nie używamy Spotify Web API, OAuth dla aplikacji deweloperskich, `Client ID` ani `Client Secret`. Nie powstaje `SpotifyOfficialAdapter`.

Docelowy przepływ:

```text
SpotifyLoginWebView
    → SpotifySessionExtractor
    → SpotifySessionVault
    → SpotifyWebTokenProvider
    → SpotifyPrivateApiClient
    → SpotifyWebSessionAdapter
    → istniejące GUI
```

Faza kończy się, gdy użytkownik może w kontenerze Linux:

1. otworzyć ekran logowania,
2. zalogować się na stronie Spotify,
3. zobaczyć swoje playlisty,
4. otworzyć playlistę i zobaczyć jej utwory,
5. wyszukać utwór,
6. wylogować się i całkowicie usunąć lokalną sesję.

Odtwarzanie, wyszukiwanie YouTube i resolver strumieni pozostają poza zakresem tej fazy.

## 2. Założenia i ryzyka

Adapter korzysta z niepublicznego protokołu web-playera Spotify. Nie ma gwarancji stabilności ani zgodności wstecznej. Każdy endpoint, nagłówek, identyfikator operacji GraphQL i format odpowiedzi może zmienić się bez zapowiedzi.

Najważniejsze ryzyka:

- Spotify może zmienić lub zablokować sposób pozyskiwania tokenu web-playera,
- logowanie może odrzucić osadzoną przeglądarkę,
- KCEF może nie udostępnić ciasteczka `HttpOnly` w wymagany sposób,
- prywatne GraphQL może zmienić identyfikatory operacji i schemat,
- `sp_dc` umożliwia korzystanie z sesji użytkownika i musi być traktowane jak hasło,
- KCEF powiększy desktopową dystrybucję, ale będzie ładowany wyłącznie podczas logowania,
- publiczna dystrybucja wymaga osobnej oceny regulaminowej i prawnej.

Nie kopiujemy kodu Spotube ani jego pluginu. Implementujemy tylko minimalny, własny klient na podstawie obserwowanego zachowania protokołu i sprawdzamy licencje każdej użytej zależności.

## 3. Decyzje techniczne

### 3.1. Granice modułów

`SpotifyRepository` pozostaje interfejsem używanym przez GUI. Szczegóły sesji nie mogą przenikać do modeli UI.

Nowe interfejsy w `commonMain`:

```kotlin
interface SpotifySessionGateway {
    val state: StateFlow<SpotifySessionState>
    suspend fun login()
    suspend fun restore(): Boolean
    suspend fun logout()
}

interface SpotifySessionVault {
    suspend fun load(): SpotifyStoredSession?
    suspend fun save(session: SpotifyStoredSession)
    suspend fun clear()
}

interface SpotifyWebTokenProvider {
    suspend fun exchange(sessionCookie: SecretValue): SpotifyWebToken
}

interface SpotifyPrivateApiClient {
    suspend fun currentUser(): SpotifyRemoteUser
    suspend fun playlists(cursor: String?): SpotifyPage<SpotifyRemotePlaylist>
    suspend fun playlistTracks(id: String, cursor: String?): SpotifyPage<SpotifyRemoteTrack>
    suspend fun search(query: String, cursor: String?): SpotifyRemoteSearchResult
}
```

`SecretValue` nie może implementować `toString()` w sposób ujawniający wartość. Jego reprezentacja tekstowa zawsze zwraca `[REDACTED]`.

### 3.2. WebView

Kandydat dla Linux/desktop: `compose-webview-multiplatform` oparty o KCEF. Kandydat musi zostać zatwierdzony dopiero po pakiecie prototypowym.

Warstwa aplikacji otrzyma własny interfejs `SpotifyLoginBrowser`, aby późniejsza zmiana KCEF nie wpływała na sesję i API.

Wymagania dla WebView:

- dopuszcza nawigację wyłącznie do domen Spotify wymaganych podczas logowania,
- blokuje dowolne zewnętrzne schematy i pobieranie plików,
- nie wstrzykuje JavaScriptu pobierającego dane formularza,
- odczytuje wyłącznie ciasteczka domen Spotify potrzebne do sesji,
- przekazuje dalej wyłącznie `sp_dc`, a pozostałe ciasteczka odrzuca,
- po zakończeniu zamyka WebView i zwalnia KCEF,
- po wylogowaniu usuwa cookies, cache, local storage i dane profilu przeglądarki,
- nie udostępnia DevTools w buildzie produkcyjnym.

### 3.3. HTTP i serializacja

Wspólny klient HTTP będzie oparty na Ktor Client:

- desktop: silnik CIO,
- Android w późniejszej fazie: silnik OkHttp,
- `kotlinx.serialization` do jawnych modeli JSON,
- limity czasu, kontrolowane retry i centralna redakcja logów,
- brak automatycznego zapisywania cookies w ogólnym cookie jarze.

Każde żądanie otrzymuje `operationId`. Retry są dozwolone tylko dla bezpiecznych odczytów i błędów przejściowych. `401`/`403` uruchamiają pojedynczą próbę odświeżenia tokenu, bez pętli.

### 3.4. Przechowywanie sesji

Powstaną dwie implementacje:

- `EphemeralSpotifySessionVault` — pamięć procesu, używana w Dockerze i testach,
- `LinuxSecretServiceSessionVault` — systemowy keyring/Secret Service, przeznaczony dla normalnej instalacji Linux.

Domyślne uruchomienie Docker używa nazwanego wolumenu `nuta-session` dla prywatnego profilu KCEF.
Dzięki temu sesja przetrwa usunięcie i ponowne utworzenie kontenera. Wolumen zawiera wrażliwe dane
sesyjne i nie może trafić do repozytorium, backupu ani artefaktów CI. Tryb czystego testu uruchamia
się przez `scripts/run.ps1 -EphemeralSession`. Świadome wylogowanie testowe wymaga zatrzymania
kontenera i ręcznego usunięcia wolumenu: `docker volume rm nuta-session`.

## 4. Modele i stan

Rozszerzamy model `Track` o pola opcjonalne:

```text
spotifyId
title
artists
album
durationMs
isrc
coverUrl
spotifyUrl
explicit
```

`Playlist` otrzymuje `coverUrl`, `ownerName`, `totalTracks` i informację o dalszych stronach. Lista utworów nie może być wymagana wewnątrz każdego obiektu playlisty.

Stan sesji:

```text
LoggedOut
OpeningBrowser
WaitingForLogin
ExchangingCookie
Authenticated
Refreshing
SessionExpired
Error(recoverable, publicMessage, diagnosticCode)
```

Błędy domenowe:

- `LoginCancelled`,
- `LoginDomainRejected`,
- `SessionCookieMissing`,
- `TokenExchangeRejected`,
- `TokenExpired`,
- `AuthenticationRequired`,
- `RateLimited(retryAfter)`,
- `RemoteSchemaChanged(operation)`,
- `RemoteServiceUnavailable`,
- `NetworkUnavailable`.

## 5. Pakiety wykonawcze

### Pakiet 0 — prototyp blokujący

Cel: potwierdzić najtrudniejsze założenie przed rozbudową kodu.

1. Dodać KCEF/WebView tylko do `desktopMain`.
2. Rozszerzyć obraz Docker o biblioteki wymagane przez Chromium.
3. Otworzyć `https://accounts.spotify.com/` w osobnym ekranie Compose.
4. Zalogować testowe konto ręcznie przez noVNC.
5. Wykryć zakończenie logowania bez analizowania pól formularza.
6. Potwierdzić programowo obecność `sp_dc`, zapisując w logu wyłącznie `cookiePresent=true` i długość zakresową, nigdy wartość.
7. Zamknąć WebView, usunąć profil KCEF i potwierdzić zwolnienie procesów Chromium.

Kryterium przejścia: odczyt `sp_dc` działa powtarzalnie w Dockerze Linux. Jeśli nie, badamy alternatywny KCEF API lub osobny tymczasowy profil Chromium. Nie budujemy klienta GraphQL przed zamknięciem tego punktu.

### Pakiet 1 — kontrakty i bezpieczeństwo sekretów

1. Dodać modele stanu sesji i błędów.
2. Dodać `SecretValue` z bezpiecznym `toString`, `equals` i kontrolowanym dostępem do wartości.
3. Rozszerzyć `LogRedactor` o `sp_dc`, `Cookie:`, wartości JSON `accessToken` i parametry `totp`.
4. Zablokować logowanie pełnych URL-i token endpointu, ponieważ query string może zawierać dane sesyjne.
5. Dodać testy redakcji wiadomości, pól, wyjątków i stack trace.
6. Dodać `EphemeralSpotifySessionVault` i test czyszczenia pamięci.

Kryterium przejścia: żadna wartość wzorcowego ciasteczka ani tokenu nie występuje w przechwyconych logach i raportach testowych.

### Pakiet 2 — pozyskanie tokenu web-playera

Komponenty:

```text
SpotifyServerClock
SpotifyNuanceProvider
TotpGenerator
SpotifyWebTokenEndpoint
DefaultSpotifyWebTokenProvider
```

Zadania:

1. Pobrać czas serwera Spotify i obliczyć kontrolowane przesunięcie zegara.
2. Pobrać aktualną wersję i sekret TOTP z konfigurowalnego źródła, walidując schemat, host i zakres wartości.
3. Zaimplementować TOTP SHA-1, 6 cyfr, interwał 30 sekund oraz testy na oficjalnych wektorach RFC.
4. Wywołać endpoint tokenu web-playera z ciasteczkiem przekazanym wyłącznie w nagłówku `Cookie`.
5. Sparsować token i czas wygaśnięcia bez zapisywania surowej odpowiedzi.
6. Odświeżać token z wyprzedzeniem i scalać równoległe żądania odświeżenia przez `Mutex`.
7. Odrzucać tokeny puste, już wygasłe lub o niepoprawnym typie.

Kryterium przejścia: ręczny test uzyskuje token, a testy jednostkowe pokrywają zegar, TOTP, timeout, zły schemat, `401`, `429` i wygasanie.

### Pakiet 3 — minimalny klient prywatnego API

Implementujemy tylko operacje wymagane przez MVP:

1. profil bieżącego użytkownika,
2. własne/zapisane playlisty,
3. szczegóły playlisty,
4. stronicowane utwory playlisty,
5. wyszukiwanie utworów i playlist.

Identyfikatory operacji, hashe i wymagane zmienne trafiają do `SpotifyOperationRegistry`, a nie do ekranów ani mapperów. Registry ma wersję i test kompletności.

Klient musi:

- stosować ścisłą allowlistę hostów,
- ograniczać maksymalny rozmiar odpowiedzi,
- obsługiwać częściowo brakujące pola,
- odróżniać zmianę schematu od pustego wyniku,
- realizować paginację iteracyjnie z limitem stron,
- respektować `Retry-After`,
- nie ponawiać bez końca `401`, `403` ani błędów parsera,
- nie logować treści odpowiedzi ani pełnych zapytań użytkownika.

Kryterium przejścia: wszystkie operacje działają na zapisanych, zanonimizowanych fixture JSON oraz w ręcznym teście z kontem.

### Pakiet 4 — mapowanie i SpotifyWebSessionAdapter

1. Zaimplementować mappery modeli zdalnych do modeli domenowych.
2. Zachować stabilny identyfikator Spotify i ISRC do przyszłego dopasowania YouTube.
3. Filtrować lokalne lub niekompletne pozycje, których nie da się później dopasować.
4. Dodać kontrolowane limity równoległości.
5. Dodać cache tylko dla niewrażliwych metadanych; cookies i tokeny nie mogą trafić do cache metadanych.
6. Zaimplementować `SpotifyRepository` przez `SpotifyWebSessionAdapter`.
7. Pozostawić `FakeSpotifyRepository` wybierane flagą uruchomieniową na potrzeby testów GUI.

Kryterium przejścia: istniejące ekrany działają bez wiedzy, czy źródłem jest fake, czy prawdziwy adapter.

### Pakiet 5 — GUI logowania i obsługa błędów

1. Dodać ekran startowy sesji z przyciskiem „Zaloguj przez Spotify”.
2. Pokazać jednoznaczną informację, że logowanie odbywa się na stronie Spotify, a Nuta lokalnie używa sesji web-playera.
3. Dodać ekran WebView z anulowaniem i stanem ładowania.
4. Po sukcesie automatycznie przejść do playlist.
5. Dodać stany: offline, wygasła sesja, rate limit, zmiana protokołu i ponowne logowanie.
6. Dodać wylogowanie z potwierdzeniem i czyszczeniem danych.
7. Ekran diagnostyczny pokazuje wyłącznie stan logiczny, czas operacji i kody błędów — nigdy token ani cookie.

### Pakiet 6 — Docker i diagnostyka

1. Zaktualizować Dockerfile o biblioteki KCEF/Chromium.
2. Zapewnić wystarczające `/dev/shm` i uruchamianie jako użytkownik bez uprawnień root.
3. Dodać katalog tymczasowego profilu WebView poza artefaktami diagnostycznymi.
4. Czyścić profil przy starcie, wylogowaniu i zakończeniu kontenera.
5. Rozszerzyć `scripts/run.ps1` o opcjonalną flagę trybu Spotify, bez przyjmowania sekretów w parametrach.
6. Dodać healthcheck rozróżniający gotowość GUI od stanu zalogowania.
7. Nigdy nie dołączać profilu WebView do artefaktów GitHub Actions.

GitHub Actions używa wyłącznie fake repository i fixture. Prawdziwe logowanie pozostaje testem ręcznym; nie zapisujemy danych konta w GitHub Secrets.

### Pakiet 7 — testy odporności

Testy jednostkowe:

- redakcja wszystkich formatów sekretów,
- TOTP i przesunięcie zegara,
- wygaśnięcie oraz współbieżne odświeżenie tokenu,
- paginacja, `429`, `Retry-After`, timeout i anulowanie,
- brakujące lub przemianowane pola JSON,
- mappery playlist i utworów,
- logout czyści vault, cookies, cache i stan pamięci.

Testy integracyjne z lokalnym mock serverem:

- sukces logowania od momentu dostarczenia sztucznego cookie,
- odświeżenie po `401`,
- maksymalnie jedna próba odświeżenia,
- awaria źródła TOTP,
- zmiana operation hash,
- częściowa strona playlisty,
- przerwanie operacji po wylogowaniu.

Test ręczny Linux/noVNC:

1. czysty start bez sesji,
2. logowanie darmowym kontem,
3. pobranie wszystkich stron playlist,
4. otwarcie krótkiej i długiej playlisty,
5. wyszukiwanie z polskimi znakami,
6. restart kontenera i oczekiwany brak sesji w trybie ephemeral,
7. ponowne logowanie,
8. wylogowanie i potwierdzenie braku `sp_dc` w profilu oraz logach.

## 6. Struktura plików

Planowana struktura:

```text
composeApp/src/commonMain/kotlin/app/nuta/
├── core/security/
│   └── SecretValue.kt
├── data/spotify/
│   ├── SpotifyWebSessionAdapter.kt
│   ├── api/SpotifyPrivateApiClient.kt
│   ├── api/SpotifyOperationRegistry.kt
│   ├── auth/SpotifySessionGateway.kt
│   ├── auth/SpotifyWebTokenProvider.kt
│   ├── auth/TotpGenerator.kt
│   ├── mapping/SpotifyMappers.kt
│   └── models/SpotifyRemoteModels.kt
└── domain/
    └── SpotifyContracts.kt

composeApp/src/desktopMain/kotlin/app/nuta/
└── data/spotify/
    ├── browser/KcefSpotifyLoginBrowser.kt
    └── storage/EphemeralSpotifySessionVault.kt

composeApp/src/commonTest/kotlin/app/nuta/data/spotify/
├── SpotifyWebTokenProviderTest.kt
├── SpotifyPrivateApiClientTest.kt
├── SpotifyMappersTest.kt
└── fixtures/
```

Android otrzyma później `AndroidSpotifyLoginBrowser` oparty na systemowym Android WebView oraz magazyn wykorzystujący Android Keystore. Kod sesji, tokenu, API i mapperów pozostanie wspólny.

## 7. Zdarzenia diagnostyczne

Dozwolone zdarzenia:

```text
login_browser_opened
login_completed
login_cancelled
session_cookie_detected
token_exchange_started
token_exchange_completed
token_refresh_started
token_refresh_completed
api_request_completed
api_rate_limited
remote_schema_changed
session_cleared
logout_completed
```

Dozwolone pola obejmują `operationId`, nazwę operacji, status HTTP, czas, numer strony, liczbę elementów i kod błędu.

Zakazane są wartości cookies, tokenów, TOTP, pełne URL-e z query string, surowe odpowiedzi, dane formularza, e-mail, nazwa konta oraz pełne zapytania wyszukiwania.

## 8. Warunki ukończenia

Faza jest ukończona, gdy:

- działa logowanie darmowym kontem w Dockerze Linux,
- `SpotifyWebSessionAdapter` obsługuje playlisty, utwory i wyszukiwanie,
- GUI nie zależy od prywatnych modeli Spotify,
- wylogowanie usuwa całą sesję,
- testy automatyczne przechodzą bez dostępu do Spotify,
- ręczny test noVNC przechodzi na prawdziwym koncie,
- żaden sekret wzorcowy nie występuje w logach, artefaktach ani raportach,
- awaria prywatnego protokołu daje rozpoznawalny błąd diagnostyczny zamiast crasha,
- dokumentacja opisuje sposób uruchomienia, ograniczenia i procedurę naprawy po zmianie protokołu.

## 9. Kolejność realizacji

```text
0. Prototyp KCEF i sp_dc
1. Kontrakty oraz redakcja
2. Token web-playera i TOTP
3. Minimalny klient prywatnego API
4. SpotifyWebSessionAdapter i mappery
5. GUI logowania
6. Docker i diagnostyka
7. Testy odporności i odbiór ręczny
```

Nie przechodzimy do wyszukiwarki YouTube, dopóki powyższe warunki ukończenia nie są spełnione.
