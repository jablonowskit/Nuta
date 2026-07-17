# Nuta — ustalenia projektowe

Ten dokument jest źródłem kontekstu dla kolejnych modeli AI i deweloperów pracujących nad projektem. Przed wprowadzaniem zmian należy go przeczytać i zachować opisany podział odpowiedzialności.

Szczegółowy plan pierwszej fazy znajduje się w [docs/PHASE_1_LINUX_GUI.md](docs/PHASE_1_LINUX_GUI.md).

## Cel projektu

Nuta ma być wieloplatformowym odtwarzaczem muzyki, który:

1. loguje użytkownika do Spotify,
2. pobiera playlisty i umożliwia wyszukiwanie utworów przez Spotify,
3. znajduje odpowiadające nagranie w YouTube,
4. uzyskuje strumień audio nagrania,
5. odtwarza go we własnym playerze i własnej kolejce.

Aplikacja nie tworzy playlist w YouTube i nie wymaga logowania użytkownika do YouTube.

## Platformy docelowe

- Android,
- Windows,
- Linux,
- macOS.

iOS został świadomie usunięty z wymagań.

Priorytetem na Androidzie jest możliwie niskie zużycie energii podczas odtwarzania w tle i przy zablokowanym ekranie.

### Strategia Linux-first

Pierwsza działająca wersja powstaje wyłącznie dla Linuxa. Ma uruchamiać się w obrazie Docker `gui-test` i być obsługiwana przez zdalny pulpit noVNC. Dopiero po zweryfikowaniu pełnego przepływu na Linuksie projekt zostanie rozszerzony na Androida, Windows i macOS.

Pierwsza wersja Linux ma potwierdzić działanie całego łańcucha:

```text
Spotify
  ↓
playlisty i wyszukiwanie
  ↓
własne wyszukiwanie YouTube
  ↓
własny resolver strumienia audio
  ↓
player Linux
  ↓
własna kolejka odtwarzania
```

Zakres pierwszej wersji Linux:

- podstawowa nawigacja i ekrany,
- logowanie Spotify,
- lista playlist i utworów,
- wyszukiwanie Spotify,
- wyszukiwanie odpowiadających nagrań YouTube,
- wybór najlepszego dopasowania i możliwość ręcznej korekty,
- uzyskanie strumienia `audio-only`,
- podstawowy player: play, pauza, następny, poprzedni i przewijanie,
- kolejka odtwarzania,
- SQLite i cache `Spotify track ID → YouTube video ID`,
- obsługa wygasłego URL-a strumienia,
- testy GUI przez noVNC i automatyczne zrzuty ekranu.

W pierwszej kolejności GUI będzie rozwijane z `FakeAudioPlayer`. Rzeczywisty `DesktopAudioPlayer` zostanie podłączony po ustabilizowaniu nawigacji i modeli danych, ale nadal w ramach pierwszej wersji Linux.

Po zatwierdzeniu wersji Linux:

1. wydzielenie i weryfikacja kodu w `commonMain`,
2. implementacja Androida z Media3 i usługą działającą w tle,
3. pomiary energii na fizycznym telefonie,
4. paczka i testy Windows,
5. paczka i testy macOS.

## Repozytorium i budowanie

Repozytorium Nuta będzie **publiczne** i projekt będzie rozwijany jako open source.

- GitHub Actions będzie budować i testować aplikację na natywnych runnerach Windows, Linux i macOS.
- Standardowe runnery GitHub Actions dla publicznego repozytorium mają być podstawowym sposobem przygotowywania paczek desktopowych.
- Lokalne kontenery Docker będą służyć do testów, kompilacji Androida i zadań niewymagających natywnego systemu desktopowego.
- Paczki Windows mają zawierać własny runtime i działać bez instalowania JDK na komputerze użytkownika.
- Sekrety, tokeny, klucze API, certyfikaty i pliki `.env` nie mogą trafić do repozytorium ani historii Git.
- Dane wrażliwe dla CI należy przechowywać wyłącznie w GitHub Actions Secrets.
- Przed pierwszym publicznym pushem należy przygotować `.gitignore`, plik licencji i sprawdzić całą historię zmian pod kątem sekretów.

## Testowanie GUI w Dockerze

Do lokalnych testów interfejsu powstanie osobny obraz `gui-test`. Będzie on uruchamiał natywną linuksową wersję Nuta wewnątrz kontenera. GUI nie będzie aplikacją webową — przeglądarka posłuży wyłącznie jako klient zdalnego pulpitu.

Planowany przepływ:

```text
Nuta dla Linux
      ↓
wirtualny ekran Xvfb
      ↓
lekki menedżer okien
      ↓
serwer VNC
      ↓
noVNC przez HTTP/WebSocket
      ↓
przeglądarka na hoście
```

Planowana zawartość obrazu:

- minimalny bazowy system Linux,
- runtime wymagany przez Compose Multiplatform,
- biblioteki graficzne wymagane przez aplikację,
- Xvfb jako wirtualny ekran,
- lekki menedżer okien, np. Openbox,
- serwer VNC,
- noVNC,
- skompilowana linuksowa wersja Nuta,
- opcjonalna usługa wykonująca zrzuty ekranu.

Planowane uruchomienie:

```powershell
docker compose up gui-test
```

Planowane adresy lokalne:

```text
http://localhost:6080             — interaktywny zdalny pulpit noVNC
http://localhost:6081/screenshot  — opcjonalny zrzut aktualnego ekranu
```

Zdalny pulpit ma umożliwiać:

- obserwowanie natywnego okna Linux,
- klikanie i obsługę klawiatury,
- przewijanie i zmianę rozmiaru okna,
- ręczne testy nawigacji,
- wykonywanie zrzutów ekranu,
- automatyczne testy GUI w środowisku Xvfb.

W testach GUI należy użyć `FakeAudioPlayer`, aby testy widoków nie zależały od urządzeń audio i konfiguracji dźwięku kontenera. Architektura playera powinna przewidywać:

```text
AudioPlayer
├── AndroidAudioPlayer  — Media3
├── DesktopAudioPlayer  — rzeczywisty backend desktopowy
└── FakeAudioPlayer     — Docker i automatyczne testy GUI
```

Środowisko `gui-test` nie zastępuje testów na docelowych systemach. Nie służy do wiarygodnego sprawdzania:

- natywnego wyglądu i integracji Windows,
- systemowego panelu multimedialnego,
- rzeczywistych urządzeń audio, Bluetooth i słuchawek,
- sprzętowej akceleracji grafiki,
- wydajności i zużycia energii,
- odtwarzania Android w tle,
- zachowania aplikacji po zablokowaniu telefonu.

Finalne testy Windows wykonujemy na przenośnej paczce z GitHub Actions, a testy Androida, audio w tle i zużycia baterii — na fizycznym telefonie.

## Framework

Wybrany framework: **Kotlin Multiplatform + Compose Multiplatform**.

Toolchain JVM: **Java 25 LTS**. Do uruchamiania Gradle wymagamy wersji Gradle 9.1 lub nowszej; projekt przypina Gradle Wrapper 9.6.1. Java 26 nie jest LTS i nie jest używana.

Jest to świadoma zmiana względem wcześniejszego wyboru Fluttera. Najważniejszym kryterium stało się możliwie niskie zużycie energii na Androidzie, dlatego wersja mobilna ma korzystać bezpośrednio z natywnego stosu Androida.

Powody:

- Android używa bezpośrednio Jetpack Compose, bez WebView i bez osobnego wieloplatformowego silnika renderującego,
- bezpośrednia integracja z Media3, MediaSession i systemową usługą odtwarzania,
- brak mostu Dart–Kotlin w często wykonywanej ścieżce odtwarzania,
- możliwość współdzielenia modeli, logiki biznesowej, sieci, bazy i koordynatora przez Kotlin Multiplatform,
- Compose Multiplatform zapewnia wspólny interfejs dla Androida, Windowsa, Linuksa i macOS,
- w razie potrzeby kod krytyczny dla energii może mieć natywną implementację Androida.

Flutter pozostaje rozsądną alternatywą, ale nie jest już preferowany. Nie należy zmieniać frameworka z powrotem bez nowej decyzji projektowej.

Planowany podział źródeł Kotlin Multiplatform:

```text
commonMain     — modele, Spotify, własny silnik YouTube, cache i PlaybackCoordinator
androidMain    — Media3, MediaSession oraz usługa w tle
desktopMain    — backend playera desktopowego
```

## Architektura

System jest podzielony na cztery główne moduły i koordynator:

```text
SpotifyWebSessionAdapter
      ↓
YouTubeSearchService
      ↓
YouTubeStreamResolver
      ↓
AudioPlayer

Całością steruje PlaybackCoordinator.
```

### 1. SpotifyWebSessionAdapter

Odpowiedzialność:

- logowanie w osadzonym WebView na oficjalnej stronie kont Spotify,
- pozyskanie krótkotrwałego tokenu w kontekście zalogowanego Web Playera, bez OAuth, `Client ID` ani `Client Secret`; na Linuxie nie polegamy na niedziałającym odczycie cookies KCEF,
- uzyskanie i okresowe odświeżanie krótkotrwałego tokenu web-playera,
- komunikacja z prywatnymi endpointami używanymi przez webowy odtwarzacz Spotify,
- pobieranie playlist użytkownika,
- pobieranie utworów playlisty,
- wyszukiwanie utworów,
- dostarczanie metadanych potrzebnych do dopasowania.

To jest jedyny planowany adapter Spotify. Nie implementujemy `SpotifyOfficialAdapter`. Szczegóły prywatnego protokołu muszą pozostać zamknięte za interfejsem `SpotifyRepository`, ponieważ mogą zmieniać się bez zapowiedzi.

Adapter musi:

- przechowywać ciasteczka i token sesji wyłącznie lokalnie, w szyfrowanym magazynie właściwym dla platformy,
- nigdy nie przekazywać sesji do serwera Nuta ani usług telemetrycznych,
- usuwać dane WebView i magazynu po wylogowaniu,
- redagować `sp_dc`, tokeny, nagłówki `Cookie` i `Authorization` przed wejściem do loggera,
- rozpoznawać wygaśnięcie lub unieważnienie sesji i wymagać ponownego logowania,
- izolować generowanie TOTP, pobieranie tokenu oraz klienta prywatnego API w osobnych komponentach,
- posiadać testy parserów odpowiedzi, redakcji sekretów i obsługi zmian schematu.

Wspólny model utworu powinien zawierać co najmniej:

```text
spotifyTrackId
title
artists
album
durationMs
isrc
coverUrl
spotifyUrl
```

### 2. YouTubeSearchService

Odpowiedzialność:

- samodzielne wysyłanie zapytań do YouTube i parsowanie odpowiedzi,
- wyszukiwanie kandydatów na podstawie tytułu, wykonawcy i ISRC,
- ocenianie jakości dopasowania,
- zwracanie najlepszego filmu i alternatywnych wyników,
- umożliwienie użytkownikowi ręcznej zmiany dopasowania.

Ranking powinien premiować:

- zgodny tytuł,
- zgodnego wykonawcę,
- długość zbliżoną do wersji ze Spotify,
- oficjalny kanał lub kanał `Artist - Topic`,
- oznaczenia `official audio`.

Ranking powinien obniżać wynik dla oznaczeń takich jak:

- `live`,
- `cover`,
- `remix`,
- `sped up`,
- `nightcore`,
- `slowed`.

Wyszukiwanie i ranking są naszą implementacją. W MVP nie korzystamy z NewPipeExtractor, `yt-dlp` ani YouTubeExplode jako silnika wyszukiwania działającego wewnątrz aplikacji.

### 3. YouTubeStreamResolver

Odpowiedzialność:

- przyjęcie `youtubeVideoId`,
- samodzielne pobranie i interpretacja danych odtwarzania YouTube,
- uzyskanie aktualnej listy strumieni `audio-only`,
- zwrócenie URL-a, kontenera, kodeka i bitrate,
- wybór strumienia zgodnego z ustawieniami jakości,
- ponowienie operacji po wygaśnięciu URL-a.

Podstawowym i docelowym silnikiem jest nasz własny `NutaYouTubeStreamResolver`. Powinien w kodzie wspólnym realizować między innymi:

- wybór profilu klienta YouTube,
- pobranie odpowiedzi playera,
- odczyt formatów `audio-only`,
- obsługę podpisów i parametrów wymagających transformacji,
- filtrowanie kodeków, bitrate i kontenerów,
- rozpoznawanie wygaśnięcia URL-a,
- ponowne uzyskanie strumienia po błędzie.

Ten sam resolver ma działać na Androidzie, Windowsie, Linuksie i macOS. Kod platformowy może dostarczać transport HTTP lub wykonanie wymaganej transformacji, ale nie powinien implementować osobnego silnika, jeśli nie jest to konieczne.

NewPipeExtractor, `yt-dlp` i YouTubeExplode mogą służyć wyłącznie jako materiały porównawcze oraz narzędzia testowe podczas rozwoju. Nie są zależnościami runtime MVP. Nie wolno kopiować z nich kodu bez sprawdzenia i zachowania warunków licencji.

Moduł nadal musi być ukryty za wspólnym interfejsem, aby jego zmiany nie wpływały na player i Spotify.

Bezpośrednie URL-e strumieni są tymczasowe i nie wolno traktować ich jako trwałych danych.

### 4. AudioPlayer

Odpowiedzialność:

- play, pauza, następny i poprzedni utwór,
- przewijanie i głośność,
- kolejka, shuffle i repeat,
- buforowanie,
- obsługa błędów i ponowne pobranie wygasłego strumienia,
- działanie w tle,
- integracja z systemowym panelem multimedialnym,
- reagowanie na słuchawki, Bluetooth i przerwania audio.

Preferowane backendy:

- Android: Media3/ExoPlayer, MediaSession oraz natywna foreground service,
- desktop Linux: `MpvAudioPlayer` sterujący procesem `mpv` przez lokalne JSON IPC; URL strumienia nie trafia do argumentów procesu. Docker używa `--ao=null`, a normalna instalacja Linux `--ao=auto`.
- pozycja paska, pauza i koniec utworu są odczytywane z MPV przez JSON IPC (`time-pos`, `pause`, `eof-reached`, `idle-active`), a nie wyliczane przez zegar GUI;
- po zakończeniu utworu MPV player automatycznie przechodzi do następnej pozycji kolejki; stan `ENDED` pojawia się dopiero po ostatnim utworze;
- szczegółową diagnostykę playera i IPC włącza `scripts/run.ps1 -LogLevel TRACE`; adres strumienia jest usuwany z logów.

Na telefonie nie należy dekodować obrazu. Odtwarzany jest wyłącznie strumień audio.

### PlaybackCoordinator

Koordynuje pełny proces:

```text
wybór utworu
    ↓
odczyt zapisanego dopasowania Spotify ID → YouTube ID
    ↓
wyszukiwanie, jeśli dopasowania nie ma
    ↓
pobranie aktualnego strumienia audio
    ↓
przekazanie źródła do playera
    ↓
przygotowanie następnego utworu
```

Koordynator nie powinien zawierać szczegółów implementacyjnych Spotify, wyszukiwania, ekstrakcji ani odtwarzacza.

## Cache i baza danych

Trwale zapisujemy:

```text
Spotify track ID → YouTube video ID
```

Możemy również zapisywać:

- alternatywne dopasowania,
- wynik rankingu,
- ręczny wybór użytkownika,
- moment ostatniej weryfikacji dopasowania.

Nie zapisujemy jako trwałego mapowania bezpośredniego URL-a strumienia audio, ponieważ URL wygasa. Może on istnieć wyłącznie w krótkotrwałym cache sesji.

Preferowana lokalna baza danych: SQLite.

## Logowanie, diagnostyka i śledzenie

Szczegółowa diagnostyka jest wymaganiem podstawowym, a nie dodatkiem wdrażanym po MVP. Każdy z czterech modułów oraz `PlaybackCoordinator` musi emitować logi strukturalne pozwalające odtworzyć przebieg operacji i znaleźć miejsce awarii.

### Poziomy logowania

```text
TRACE — bardzo szczegółowy przebieg operacji, decyzje rankingu i zmiany stanu
DEBUG — dane potrzebne podczas developmentu i diagnozowania problemu
INFO  — ważne zdarzenia cyklu życia i operacje zakończone powodzeniem
WARN  — sytuacje nietypowe, retry, fallback lub częściowa degradacja
ERROR — operacja zakończona błędem, wyjątek i kontekst awarii
```

Zasady używania poziomów:

- `TRACE` jest domyślnie wyłączony i uruchamiany tylko na czas diagnozy,
- `DEBUG` jest domyślny w buildach developerskich,
- `INFO` jest domyślny w buildach produkcyjnych,
- `WARN` i `ERROR` są zawsze zapisywane,
- zmiana poziomu nie może wymagać ponownej kompilacji aplikacji,
- na Androidzie `TRACE` i `DEBUG` należy automatycznie wyłączyć po określonym czasie, aby nie zużywały niepotrzebnie energii i miejsca.

### Format zdarzenia

Logi mają być strukturalne. Preferowany format zapisu i eksportu to JSON Lines, po jednym zdarzeniu w każdym wierszu:

```json
{
  "timestamp": "2026-07-17T12:00:00.000Z",
  "level": "DEBUG",
  "module": "YouTubeSearchService",
  "event": "candidate_ranked",
  "operationId": "op-...",
  "trackId": "spotify-track-id",
  "videoId": "youtube-video-id",
  "durationMs": 42,
  "attempt": 1,
  "message": "Candidate ranking completed"
}
```

Każdy wpis powinien, jeśli ma to zastosowanie, zawierać:

- czas UTC z milisekundami,
- poziom,
- nazwę modułu,
- stabilną nazwę zdarzenia,
- `operationId` łączący cały przepływ jednego żądania,
- bezpieczne identyfikatory utworu i filmu,
- numer próby i przyczynę retry,
- czas wykonania operacji,
- rodzaj błędu i stack trace dla `ERROR`,
- wersję aplikacji, platformę i wersję systemu w nagłówku sesji diagnostycznej.

`operationId` ma przechodzić przez cały łańcuch:

```text
SpotifyWebSessionAdapter
  → YouTubeSearchService
  → YouTubeStreamResolver
  → PlaybackCoordinator
  → AudioPlayer
```

### Wymagane zdarzenia modułów

`SpotifyWebSessionAdapter`:

- rozpoczęcie i wynik logowania bez danych uwierzytelniających,
- odświeżenie sesji,
- pobranie strony playlist lub utworów,
- status odpowiedzi, retry, rate limit i czas żądania.

`YouTubeSearchService`:

- zanonimizowane lub bezpiecznie skrócone zapytanie,
- liczba kandydatów,
- składowe punktacji każdego kandydata na poziomie `TRACE`,
- wybrane dopasowanie i powód odrzucenia pozostałych,
- ręczna zmiana dopasowania.

`YouTubeStreamResolver`:

- wybrany profil klienta,
- etap pobierania i parsowania odpowiedzi,
- liczba znalezionych formatów,
- odrzucone formaty wraz z bezpiecznym powodem,
- wybrany kodek, kontener i bitrate,
- wygaśnięcie URL-a, retry i wynik odświeżenia,
- etap transformacji podpisu bez zapisywania jego wartości.

`AudioPlayer` i `PlaybackCoordinator`:

- zmiany stanu playera,
- zmiany kolejki i indeksu,
- rozpoczęcie buforowania i czas do pierwszego dźwięku,
- play, pause, seek, next i previous,
- zakończenie utworu,
- błąd źródła, dekodera lub urządzenia audio,
- przejście do ponownego pobrania strumienia.

### Bezpieczeństwo i prywatność logów

Nigdy nie wolno logować:

- tokenów sesji Spotify i tokenów web-playera,
- ciasteczek (szczególnie `sp_dc`) oraz nagłówków `Cookie` i `Authorization`,
- pełnych podpisanych URL-i strumieni,
- parametrów podpisu i danych używanych do jego transformacji,
- haseł, kluczy API i zawartości `.env`,
- pełnych odpowiedzi HTTP mogących zawierać dane użytkownika,
- danych osobowych profilu, jeśli nie są konieczne do diagnozy.

Warstwa HTTP musi mieć centralny mechanizm redakcji. Pola wrażliwe należy usuwać albo zastępować wartością `[REDACTED]` przed przekazaniem zdarzenia do loggera. Redakcja musi być objęta testami automatycznymi.

### Miejsca zapisu

- development i Docker: czytelne logi tekstowe na `stdout` oraz opcjonalnie JSON Lines,
- aplikacja desktopowa: rotowane pliki lokalne,
- Android: Logcat w developmencie oraz ograniczony rotowany bufor aplikacji w produkcji,
- testy automatyczne: log jako artefakt testu w przypadku niepowodzenia,
- GitHub Actions: log konsoli oraz paczka diagnostyczna dla nieudanego zadania.

Pliki logów muszą mieć limit rozmiaru, rotację i krótki okres przechowywania. Awaria zapisu logu nie może zatrzymać odtwarzania.

### Tryb diagnostyczny i eksport

Aplikacja ma udostępniać ekran diagnostyczny pozwalający:

- zobaczyć aktualny poziom logowania,
- tymczasowo włączyć `TRACE`,
- wyświetlić ostatnie zdarzenia,
- skopiować identyfikator operacji,
- wyeksportować paczkę diagnostyczną,
- usunąć wszystkie lokalne logi.

Paczka diagnostyczna powinna zawierać wyłącznie zredagowane dane:

```text
diagnostics.zip
├── app-info.json
├── preferences-sanitized.json
├── logs.jsonl
└── last-error.txt
```

Wysyłanie telemetrii poza urządzenie jest domyślnie wyłączone. Eksport lub wysłanie paczki wymaga świadomej akcji użytkownika.

### Implementacja

Kod wspólny powinien korzystać z własnej abstrakcji `NutaLogger`, niezależnej od konkretnej biblioteki:

```kotlin
interface NutaLogger {
    fun trace(event: LogEvent)
    fun debug(event: LogEvent)
    fun info(event: LogEvent)
    fun warn(event: LogEvent)
    fun error(event: LogEvent, throwable: Throwable? = null)
}
```

Konkretne backendy logowania oraz format JSON zostaną wybrane podczas tworzenia szkieletu projektu. Logowanie nie może bezpośrednio zależeć od warstwy UI.

## Energooszczędność na Androidzie

- używać natywnego mechanizmu odtwarzania i usługi działającej w tle,
- nie renderować ani nie dekodować obrazu,
- wyszukiwać tylko utwór bieżący i następny,
- korzystać z cache dopasowań,
- nie odświeżać interfejsu z wysoką częstotliwością przy wygaszonym ekranie,
- ograniczyć animacje i timery w tle,
- nie uruchamiać zewnętrznych procesów takich jak `yt-dlp` na Androidzie,
- pozwolić systemowi zarządzać buforem, Bluetooth i audio focus,
- nie pobierać całej playlisty audio z wyprzedzeniem.

## Istotne ograniczenia

- Spotify i YouTube mogą zmieniać API oraz zasady dostępu.
- `SpotifyWebSessionAdapter` korzysta z niepublicznego protokołu web-playera, może przestać działać bez ostrzeżenia i może podlegać ograniczeniom regulaminu Spotify.
- Nuta nie może kopiować błędu Spotube polegającego na wypisywaniu `sp_dc` w logach; wyciek tego ciasteczka należy traktować jak przejęcie sesji użytkownika.
- Pobieranie bezpośrednich strumieni YouTube opiera się na nieoficjalnych mechanizmach i może okresowo przestać działać.
- Własny silnik YouTube będzie wymagał regularnego utrzymywania po zmianach protokołu YouTube.
- Publiczna dystrybucja może wiązać się z ograniczeniami regulaminów Spotify i YouTube; należy to ponownie ocenić przed publikacją.
- Nie należy kopiować całego Spotube. Można analizować jego architekturę i rozwiązania techniczne, przestrzegając licencji zależności oraz kodu źródłowego.

## Kolejność prac nad MVP

Szczegółowy plan integracji sesji web-playera znajduje się w [`docs/PHASE_2_SPOTIFY_WEB_SESSION.md`](docs/PHASE_2_SPOTIFY_WEB_SESSION.md).

Szczegółowy plan wyszukiwania YouTube i pozyskania strumienia audio znajduje się w [`docs/PHASE_3_YOUTUBE_STREAM.md`](docs/PHASE_3_YOUTUBE_STREAM.md).

Docker domyślnie przechowuje profil logowania Spotify w nazwanym wolumenie `nuta-session`.
`scripts/run.ps1 -EphemeralSession` uruchamia czysty kontener bez trwałej sesji.

1. Utworzenie szkieletu Kotlin Multiplatform + Compose z aplikacją Linux.
2. Utworzenie obrazu `gui-test` z Xvfb i noVNC.
3. Implementacja `NutaLogger`, redakcji sekretów, rotacji i identyfikatorów operacji.
4. Zdefiniowanie wspólnych modeli i interfejsów czterech modułów.
5. Zbudowanie ekranów Linux z `FakeAudioPlayer`.
6. Implementacja `SpotifyWebSessionAdapter`: WebView, lokalna sesja, token web-playera, prywatny klient API oraz odczyt playlist.
7. Implementacja własnego wyszukiwania i rankingu YouTube.
8. Implementacja własnego resolvera strumienia.
9. Implementacja rzeczywistego playera Linux i kolejki.
10. SQLite i cache dopasowań.
11. Odporność własnego silnika na wygasłe URL-e, różne profile klienta i zmiany odpowiedzi YouTube.
12. Ekran diagnostyczny, eksport paczki i testy redakcji danych.
13. Testy pełnego przepływu i zatwierdzenie wersji Linux.
14. Dopiero później: Android, Media3, odtwarzanie w tle i pomiary energii.

## Poza zakresem MVP

- iOS,
- tworzenie lub modyfikowanie playlist YouTube,
- logowanie do YouTube,
- synchronizacja playlist pomiędzy usługami,
- pobieranie całych playlist do trybu offline,
- używanie NewPipeExtractor, `yt-dlp` lub YouTubeExplode jako zależności runtime aplikacji.
# Zasada środowiska budowania

Projekt jest kompilowany i testowany wyłącznie w Dockerze. Nie uruchamiamy `gradlew`, `gradlew.bat`, Javy, testów ani aplikacji bezpośrednio na hoście. Do budowy używamy `scripts/build.ps1`, a do uruchamiania `scripts/run.ps1`. Host służy do edycji plików i sterowania Dockerem; zależności projektu nie są na nim instalowane.

Przy starcie Nuta automatycznie sprawdza sesję Spotify zapisaną w wolumenie `nuta-session`. WebView zaczyna od `open.spotify.com` i pokazuje pełne logowanie dopiero wtedy, gdy Spotify zwróci sesję anonimową. Token dostępu pozostaje tylko w pamięci procesu; na dysku utrwalany jest profil WebView z cookies.

Ponieważ profil JCEF nie odtwarza niezawodnie sesyjnych cookies między kontenerami, testowa wersja Nuta posiada dodatkowy `SpotifyCookieSessionStore`. Cookies domen Spotify są eksportowane przez API JCEF i zapisywane jako zwykły JSON z uprawnieniami właściciela w wolumenie `nuta-session`. Przed pierwszą nawigacją są przywracane do JCEF. W logach nie zapisujemy nazw ani wartości cookies. To rozwiązanie jest wyłącznie do testów i przed wydaniem produkcyjnym musi zostać zastąpione bezpiecznym magazynem systemowym.

Jeżeli używana wersja JCEF nie udostępni cookies przez visitor API, wersja testowa zapisuje token i jego termin ważności bez szyfrowania w `spotify-session/token.test.json`. Po restarcie token jest używany tylko wtedy, gdy nadal jest ważny. Zapis tokenu nie może blokować pomyślnie zakończonego logowania. Plik testowy nie może trafić do wydania produkcyjnego.

Ekran główny pobiera spersonalizowane playlisty z wewnętrznej operacji Spotify Web `home`. Mapper wybiera elementy `spotify:playlist:*` i ignoruje nieobsługiwane albumy, podcasty oraz inne typy. Utwory playlisty są pobierane dopiero po jej wybraniu, aby ograniczyć transfer i zużycie energii.

## Radio utworu

Dolny pasek odtwarzacza udostępnia przycisk „Radio utworu” dla aktualnie wybranego utworu. Aplikacja najpierw próbuje pobrać kontekst stacji `spotify:station:track:*`, a gdy Spotify go nie udostępnia, buduje kolejkę z wyników katalogu Spotify na podstawie wykonawców, albumu i tytułu. Bieżący utwór pozostaje pierwszy, duplikaty są usuwane, a maksymalnie 20 rekomendacji jest kolejno rozwiązywanych do strumieni YouTube przez własny silnik Nuta.
