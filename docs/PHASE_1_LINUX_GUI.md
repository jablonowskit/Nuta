# Faza 1 — GUI Linux uruchamiane w Dockerze

## 1. Cel fazy

Celem jest uzyskanie pierwszej działającej, natywnej aplikacji Nuta dla Linuxa, uruchamianej w całości wewnątrz kontenera Docker i obsługiwanej z hosta przez przeglądarkę za pomocą noVNC.

Ta faza ma zweryfikować:

- poprawność szkieletu Kotlin Multiplatform + Compose Multiplatform,
- możliwość kompilacji bez lokalnej instalacji JDK, Gradle i bibliotek Linux,
- wyświetlanie natywnego okna Compose na wirtualnym ekranie,
- działanie nawigacji i podstawowych ekranów,
- architekturę stanu aplikacji i playera,
- logowanie `TRACE`–`ERROR`, redakcję danych i eksport logów,
- ręczne oraz automatyczne wykonywanie zrzutów ekranu.

## 2. Wynik końcowy

Po zakończeniu fazy poniższe polecenie ma wystarczyć do uruchomienia środowiska:

```powershell
docker compose up --build gui-test
```

Użytkownik otwiera:

```text
http://localhost:6080
```

i widzi natywne okno linuksowej aplikacji Nuta. Interfejs działa na danych testowych, a przyciski playera sterują `FakeAudioPlayer`.

Opcjonalny endpoint:

```text
GET http://localhost:6081/screenshot
```

zwraca aktualny ekran jako `image/png`.

## 3. Zakres

### W zakresie

- publiczne repozytorium i podstawowe pliki projektu,
- Gradle Wrapper i katalog wersji zależności,
- target JVM Desktop dla Linuxa,
- przygotowanie struktury `commonMain` i `desktopMain`,
- natywne okno Compose,
- wspólny design system,
- nawigacja między podstawowymi ekranami,
- dane demonstracyjne playlist i utworów,
- `FakeSpotifyRepository`,
- `FakeAudioPlayer`,
- pasek aktualnego utworu i podstawowe kontrolki,
- ekran diagnostyczny,
- `NutaLogger` z logami strukturalnymi,
- Docker, Xvfb, lekki menedżer okien, VNC i noVNC,
- automatyczne testy jednostkowe oraz test dymny kontenera,
- zrzuty ekranu i logi jako artefakty testów.

### Poza zakresem tej fazy

- prawdziwe logowanie Spotify,
- prawdziwe wywołania Spotify API,
- wyszukiwanie YouTube,
- resolver strumienia YouTube,
- rzeczywiste odtwarzanie audio,
- SQLite z danymi produkcyjnymi,
- Android, Windows i macOS,
- instalatory i podpisywanie paczek,
- testowanie Bluetooth, urządzeń audio i zużycia energii.

Integracje Spotify, YouTube i rzeczywisty player zaczynają się dopiero po zatwierdzeniu GUI oraz środowiska Linux.

## 4. Decyzje techniczne

### 4.1. Stos

- Kotlin Multiplatform,
- Compose Multiplatform,
- JVM Desktop dla pierwszego uruchomienia,
- Java 25 LTS w kontenerze,
- Gradle Wrapper zapisany w repozytorium,
- Kotlin Coroutines i `StateFlow` do stanu,
- ręczne wstrzykiwanie zależności w fazie 1,
- testy Kotlin oraz Compose UI tam, gdzie będą stabilne w Xvfb.

Wersje Kotlin, Compose i pluginów należy przypiąć w `gradle/libs.versions.toml`. Przed utworzeniem szkieletu wybieramy aktualne stabilne, wzajemnie zgodne wersje i nie używamy wersji EAP.

### 4.2. Namespace

Przed utworzeniem kodu należy ustalić docelowy namespace na podstawie właściciela publicznego repozytorium, np.:

```text
io.github.<github-owner>.nuta
```

Nie należy publikować artefaktów z tymczasowym identyfikatorem.

### 4.3. Stan i przepływ danych

Interfejs nie wywołuje bezpośrednio repozytoriów ani playera. Ekrany obserwują niezmienny stan wystawiany przez kontrolery lub view modele.

```text
UI event
   ↓
ScreenModel / Controller
   ↓
Repository lub AudioPlayer
   ↓
StateFlow
   ↓
Compose UI
```

W fazie 1 nie dodajemy frameworka DI. Zależności są składane w jednym `AppContainer`, aby ograniczyć złożoność i umożliwić łatwe podstawienie implementacji fake.

### 4.4. Bezpieczeństwo środowiska testowego

- noVNC i endpoint zrzutów wiążemy wyłącznie z `127.0.0.1`,
- VNC nie jest wystawiane bezpośrednio poza sieć kontenera,
- obraz nie zawiera sekretów ani plików `.env`,
- proces aplikacji działa jako użytkownik bez uprawnień root,
- logi przechodzą przez centralną redakcję,
- testowe dane nie zawierają prawdziwych danych konta Spotify.

## 5. Planowana struktura repozytorium

```text
Nuta/
├── PROJECT.md
├── README.md
├── LICENSE
├── .gitignore
├── .dockerignore
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/.../nuta/
│       │   ├── App.kt
│       │   ├── core/
│       │   │   ├── logging/
│       │   │   ├── models/
│       │   │   └── time/
│       │   ├── data/fake/
│       │   ├── domain/
│       │   │   ├── player/
│       │   │   └── spotify/
│       │   └── ui/
│       │       ├── design/
│       │       ├── navigation/
│       │       ├── shell/
│       │       └── screens/
│       ├── commonTest/kotlin/
│       ├── desktopMain/kotlin/.../nuta/
│       │   ├── Main.kt
│       │   └── platform/
│       └── desktopTest/kotlin/
├── docker/
│   └── gui-test/
│       ├── Dockerfile
│       ├── entrypoint.sh
│       ├── supervisord.conf
│       └── openbox/
├── scripts/
│   ├── wait-for-gui.ps1
│   ├── gui-smoke-test.ps1
│   └── capture-screenshot.ps1
├── test-data/
│   ├── playlists.json
│   └── tracks.json
├── artifacts/
│   ├── screenshots/
│   └── logs/
├── docker-compose.yml
└── docs/
    └── PHASE_1_LINUX_GUI.md
```

`artifacts/` ma być ignorowany przez Git z wyjątkiem ewentualnego pliku `.gitkeep`.

## 6. Ekrany pierwszej wersji GUI

### 6.1. App Shell

Stały szkielet okna:

```text
┌──────────────────────────────────────────────────────────────┐
│ Pasek tytułu / status środowiska                            │
├──────────────┬───────────────────────────────────────────────┤
│ Nawigacja    │ Główna zawartość                             │
│              │                                               │
│ Start        │                                               │
│ Playlisty    │                                               │
│ Wyszukiwanie │                                               │
│ Diagnostyka  │                                               │
├──────────────┴───────────────────────────────────────────────┤
│ Okładka | utwór | poprzedni | play | następny | czas       │
└──────────────────────────────────────────────────────────────┘
```

Minimalny rozmiar okna: `1024 × 640`. Bazowa rozdzielczość kontenera: `1440 × 900`.

### 6.2. Ekran startowy

- nazwa aplikacji,
- informacja, że działa tryb danych demonstracyjnych,
- przycisk przejścia do playlist,
- stan `FakeAudioPlayer`,
- ostatnie zdarzenie diagnostyczne.

### 6.3. Playlisty

- lista demonstracyjnych playlist,
- okładka zastępcza, nazwa i liczba utworów,
- stan pusty,
- stan ładowania,
- stan błędu wymuszany przełącznikiem developerskim.

### 6.4. Szczegóły playlisty

- nagłówek playlisty,
- lista utworów,
- tytuł, wykonawca, album i długość,
- przycisk odtworzenia pojedynczego utworu,
- przycisk odtworzenia całej kolejki,
- zaznaczenie aktualnie odtwarzanego wiersza.

### 6.5. Wyszukiwanie

- pole tekstowe,
- filtrowanie lokalnych danych demonstracyjnych,
- wyniki utworów i playlist,
- pusty wynik,
- wyczyszczenie zapytania.

### 6.6. Diagnostyka

- aktualna wersja i platforma,
- aktualny poziom logowania,
- przełączanie `INFO`, `DEBUG` i `TRACE`,
- lista ostatnich zdarzeń,
- filtrowanie po poziomie i module,
- skopiowanie `operationId`,
- przycisk wykonania zrzutu ekranu,
- przycisk eksportu zredagowanych logów,
- przycisk usunięcia logów.

### 6.7. Dolny player

- dane aktualnego utworu,
- play/pauza,
- poprzedni/następny,
- suwak czasu,
- aktualny czas i długość,
- stan: idle, loading, playing, paused, ended, error.

Player korzysta wyłącznie z `FakeAudioPlayer`. Symulowany czas utworu jest aktualizowany najwyżej raz na sekundę, aby od początku unikać niepotrzebnych częstych aktualizacji UI.

## 7. Modele i kontrakty fazy 1

Minimalne modele:

```text
Track
Playlist
PlayerState
PlaybackQueue
NavigationDestination
LogEvent
DiagnosticSession
```

Minimalne interfejsy:

```kotlin
interface SpotifyRepository {
    suspend fun getPlaylists(): List<Playlist>
    suspend fun getPlaylistTracks(playlistId: String): List<Track>
    suspend fun search(query: String): SearchResult
}

interface AudioPlayer {
    val state: StateFlow<PlayerState>
    suspend fun setQueue(tracks: List<Track>, startIndex: Int = 0)
    suspend fun play()
    suspend fun pause()
    suspend fun seekTo(positionMs: Long)
    suspend fun next()
    suspend fun previous()
}
```

Implementacje fazy 1:

```text
SpotifyRepository → FakeSpotifyRepository
AudioPlayer       → FakeAudioPlayer
NutaLogger        → ConsoleLogger + RotatingJsonFileLogger
```

Modele nie mogą zależeć od Compose ani klas platformowych.

## 8. Obraz Docker `gui-test`

### 8.1. Warstwy obrazu

Preferowany jest build wieloetapowy:

```text
builder
├── JDK 25 LTS
├── Gradle Wrapper
├── źródła
└── skompilowana dystrybucja Linux

runtime
├── minimalny Linux
├── biblioteki X11/fonty
├── Xvfb
├── Openbox
├── x11vnc
├── noVNC/websockify
├── narzędzie screenshot
└── gotowa aplikacja Nuta
```

Obraz runtime nie powinien zawierać Gradle ani kodu źródłowego.

### 8.2. Uruchamianie procesów

Proces startowy uruchamia kolejno:

1. Xvfb na `DISPLAY=:99`,
2. Openbox,
3. x11vnc dostępne tylko wewnątrz kontenera,
4. noVNC na porcie `6080`,
5. usługę zrzutów na porcie `6081`,
6. aplikację Nuta zmaksymalizowaną na wirtualnym ekranie.

Kontener kończy działanie błędem, jeżeli aplikacja niespodziewanie się zamknie. Log każdego procesu trafia na `stdout/stderr` oraz do katalogu artefaktów.

### 8.3. Docker Compose

Serwis `gui-test`:

- buduje obraz z katalogu projektu,
- publikuje `127.0.0.1:6080:6080`,
- publikuje `127.0.0.1:6081:6081`,
- montuje `./artifacts:/artifacts`,
- ma healthcheck sprawdzający noVNC i proces aplikacji,
- otrzymuje poziom logowania przez `NUTA_LOG_LEVEL`,
- otrzymuje stałą rozdzielczość przez `NUTA_SCREEN_SIZE`.

Planowane wartości domyślne:

```text
NUTA_LOG_LEVEL=DEBUG
NUTA_SCREEN_SIZE=1440x900x24
NUTA_FAKE_DATA=true
```

## 9. Pakiety prac

### Pakiet 0 — przygotowanie publicznego repozytorium

Zadania:

1. zainicjalizować Git,
2. ustalić właściciela i namespace,
3. wybrać licencję open source,
4. utworzyć `.gitignore` i `.dockerignore`,
5. dodać `README.md` z instrukcją uruchomienia,
6. sprawdzić, czy w repozytorium nie ma sekretów.

Kryterium odbioru:

- repozytorium można bezpiecznie opublikować,
- żaden plik lokalny, cache ani artefakt nie jest śledzony.

### Pakiet 1 — szkielet Kotlin/Compose

Zadania:

1. utworzyć Gradle Wrapper,
2. skonfigurować Kotlin Multiplatform i Compose,
3. dodać target desktop JVM,
4. utworzyć `commonMain`, `desktopMain` i testy,
5. wyświetlić okno `Nuta — Linux GUI`,
6. dodać prosty test uruchomienia modelu wspólnego.

Kryterium odbioru:

- build przechodzi w czystym środowisku,
- aplikacja uruchamia natywne okno na normalnym Linuksie.

### Pakiet 2 — pierwszy obraz GUI

Zadania:

1. przygotować wieloetapowy `Dockerfile`,
2. uruchomić Xvfb, Openbox, x11vnc i noVNC,
3. uruchomić okno Nuta na `DISPLAY=:99`,
4. ograniczyć porty do localhost,
5. dodać healthcheck i poprawne zatrzymanie procesów.

Kryterium odbioru:

- `docker compose up --build gui-test` działa na Docker Desktop z kontenerami Linux,
- `http://localhost:6080` pokazuje okno Nuta,
- kliknięcia i klawiatura docierają do aplikacji,
- zatrzymanie Compose usuwa kontener bez wiszących procesów.

### Pakiet 3 — logger i diagnostyka bazowa

Zadania:

1. zdefiniować `NutaLogger` i `LogEvent`,
2. zaimplementować poziomy `TRACE`–`ERROR`,
3. dodać `operationId`, czas UTC i czas operacji,
4. dodać redakcję nazwanych pól oraz URL-i,
5. zapisywać czytelny tekst na konsoli i JSON Lines do pliku,
6. dodać rotację oraz testy redakcji.

Kryterium odbioru:

- start, nawigacja i akcje playera są widoczne w logach,
- test umieszczający fałszywy token potwierdza, że zapisano `[REDACTED]`,
- błąd loggera nie zamyka aplikacji.

### Pakiet 4 — design system i App Shell

Zadania:

1. zdefiniować kolory, typografię, odstępy i kształty,
2. przygotować jasny i ciemny motyw,
3. utworzyć boczną nawigację,
4. utworzyć obszar zawartości,
5. utworzyć dolny pasek playera,
6. obsłużyć minimalny rozmiar okna i zmianę wymiarów.

Kryterium odbioru:

- shell poprawnie działa w `1024 × 640` i `1440 × 900`,
- wszystkie elementy są dostępne klawiaturą,
- tekst nie nachodzi na kontrolki.

### Pakiet 5 — dane fake i ekrany

Zadania:

1. przygotować deterministyczne dane testowe,
2. zaimplementować `FakeSpotifyRepository`,
3. zbudować ekran startowy,
4. zbudować playlisty i szczegóły playlisty,
5. zbudować lokalne wyszukiwanie,
6. obsłużyć loading, empty i error,
7. dodać stabilne identyfikatory elementów dla testów UI.

Kryterium odbioru:

- można przejść pełną ścieżkę Start → Playlisty → Utwór,
- wyszukiwanie filtruje dane,
- stany loading, empty i error można wymusić oraz zobaczyć.

### Pakiet 6 — `FakeAudioPlayer`

Zadania:

1. zaimplementować kolejkę i indeks utworu,
2. zaimplementować play/pauzę,
3. zaimplementować next/previous,
4. symulować pozycję utworu,
5. zaimplementować seek,
6. obsłużyć koniec kolejki i symulowany błąd,
7. powiązać stan z dolnym paskiem i listą utworów.

Kryterium odbioru:

- wszystkie kontrolki zmieniają stan deterministycznie,
- aktualny utwór jest zaznaczony,
- testy jednostkowe obejmują granice kolejki oraz seek.

### Pakiet 7 — diagnostyka GUI i screenshoty

Zadania:

1. zbudować ekran diagnostyczny,
2. dodać tymczasowe przełączanie poziomu logowania,
3. pokazać ostatnie zdarzenia bez blokowania UI,
4. udostępnić screenshot jako plik w `/artifacts/screenshots`,
5. udostępnić `GET /screenshot`,
6. dodać eksport zredagowanych logów.

Kryterium odbioru:

- endpoint odpowiada `200` i `Content-Type: image/png`,
- PNG ma oczekiwany rozmiar i nie jest pusty,
- eksportowane logi przechodzą ponowny filtr redakcji.

### Pakiet 8 — testy i stabilizacja

Zadania:

1. dodać testy modeli, nawigacji i fake playera,
2. dodać testy redakcji logów,
3. dodać test dymny uruchomienia kontenera,
4. sprawdzić healthcheck,
5. wykonać screenshoty przy dwóch rozdzielczościach,
6. sprawdzić ponowne uruchomienie po czystym buildzie,
7. udokumentować znane problemy.

Kryterium odbioru:

- wszystkie testy przechodzą w Dockerze,
- nieudany test zachowuje log i screenshot,
- środowisko można odtworzyć wyłącznie na podstawie repozytorium i Dockera.

### Pakiet 9 — CI dla Linux GUI

Zadania:

1. dodać workflow uruchamiany na publicznym runnerze Linux,
2. uruchamiać testy Gradle,
3. budować obraz `gui-test`,
4. uruchamiać test dymny pod Xvfb/noVNC,
5. dodawać logi i screenshot tylko przy błędzie,
6. skonfigurować cache Gradle.

Kryterium odbioru:

- czysty push uruchamia powtarzalną weryfikację,
- status workflow jest widoczny w repozytorium,
- logi nie zawierają sekretów ani danych prywatnych.

## 10. Kolejność realizacji

```text
Pakiet 0
   ↓
Pakiet 1
   ↓
Pakiet 2
   ↓
Pakiet 3
   ↓
Pakiet 4
   ↓
Pakiet 5
   ↓
Pakiet 6
   ↓
Pakiet 7
   ↓
Pakiet 8
   ↓
Pakiet 9
```

Po pakiecie 2 należy wykonać pierwszy ręczny odbiór obrazu w noVNC. Po pakiecie 6 należy wykonać odbiór całej nawigacji i symulowanego playera. Nie rozpoczynamy prawdziwej integracji Spotify przed zakończeniem pakietu 8.

## 11. Testy ręczne

Lista kontrolna:

1. uruchomić `docker compose up --build gui-test`,
2. zaczekać na zdrowy status kontenera,
3. otworzyć noVNC,
4. przejść przez wszystkie pozycje nawigacji,
5. otworzyć playlistę,
6. uruchomić utwór,
7. sprawdzić play, pauzę, seek, next i previous,
8. wyszukać utwór,
9. wymusić pusty wynik i błąd,
10. włączyć `TRACE`,
11. sprawdzić zdarzenia diagnostyczne,
12. pobrać screenshot,
13. wyeksportować logi,
14. sprawdzić, że nie ma w nich danych oznaczonych jako sekret,
15. zatrzymać kontener i uruchomić go ponownie.

## 12. Definition of Done fazy 1

Faza jest zakończona dopiero, gdy:

- projekt buduje się od zera wyłącznie przy użyciu Dockera,
- noVNC pokazuje natywną aplikację Linux,
- nawigacja i wszystkie zaplanowane ekrany działają,
- fake dane są deterministyczne,
- `FakeAudioPlayer` obsługuje pełną podstawową kolejkę,
- GUI działa w dwóch wymaganych rozdzielczościach,
- logi strukturalne i redakcja mają testy,
- screenshot można wykonać automatycznie,
- test dymny kontenera przechodzi,
- build CI przechodzi w publicznym repozytorium,
- README pozwala nowej osobie uruchomić GUI bez dodatkowej wiedzy,
- nie ma sekretów, lokalnych ścieżek ani zależności od konfiguracji komputera autora.

## 13. Ryzyka i działania zapobiegawcze

| Ryzyko | Działanie |
|---|---|
| Compose nie startuje bez bibliotek graficznych | test minimalnego okna w pakiecie 2 i jawna lista bibliotek runtime |
| noVNC pokazuje czarny ekran | kolejność startu Xvfb → Openbox → VNC → aplikacja i logi każdego procesu |
| różnice fontów psują screenshoty | przypięty zestaw fontów w obrazie i stałe DPI |
| kontener działa jako root | osobny użytkownik runtime i poprawne prawa do `/artifacts` |
| logi blokują UI | zapis asynchroniczny, ograniczony bufor i test przeciążenia |
| testy screenshotów są niestabilne | stała rozdzielczość, motyw, dane, fonty i wyłączone animacje testowe |
| obraz staje się zbyt duży | wieloetapowy build i brak toolchainu w warstwie runtime |
| projekt za wcześnie integruje zewnętrzne API | twardy zakres fazy i implementacje fake do końca pakietu 8 |

## 14. Następny krok po zatwierdzeniu planu

Rozpocząć pakiet 0, a następnie pakiet 1. Pierwszym technicznym punktem kontrolnym jest okno z tekstem `Nuta — Linux GUI` widoczne pod `http://localhost:6080` po wykonaniu jednego polecenia Docker Compose.
