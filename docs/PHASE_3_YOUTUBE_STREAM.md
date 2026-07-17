# Faza 3 — wyszukiwanie YouTube i pozyskanie strumienia audio

## Cel

Po wybraniu utworu Spotify aplikacja samodzielnie:

1. wyszukuje kandydatów w YouTube,
2. wybiera najlepsze dopasowanie,
3. uzyskuje aktualne formaty `audio-only`,
4. wybiera format zgodny z platformą i jakością,
5. weryfikuje, że URL strumienia odpowiada danymi audio.

Po potwierdzeniu walidacji dodano prototyp `MpvAudioPlayer`. W Dockerze `mpv` używa wyjścia
`null`, więc wykonuje pobieranie, demultipleksowanie, dekodowanie i sterowanie bez fizycznego
dźwięku. Normalny Linux może używać wyjścia `auto`. Nie zapisujemy utworów ani pełnych plików.

Nie używamy NewPipeExtractor, `yt-dlp` ani YouTubeExplode jako zależności runtime. Mogą być
wyłącznie narzędziami porównawczymi podczas developmentu.

## Przepływ

```text
Spotify Track
    → YouTubeQueryBuilder
    → YouTubeSearchClient
    → YouTubeMatchRanker
    → YouTube video ID
    → YouTubePlayerClient
    → YouTubeCipherResolver
    → YouTubeStreamSelector
    → AudioStreamSource
```

Każdy element ma osobny interfejs i testy. `PlaybackCoordinator` widzi tylko wynik dopasowania
oraz gotowe `AudioStreamSource`.

## Modele i kontrakty

```kotlin
data class YouTubeCandidate(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationMs: Long?,
    val isOfficial: Boolean,
)

data class YouTubeMatch(
    val candidate: YouTubeCandidate,
    val score: Int,
    val reasons: List<String>,
)

data class AudioStreamSource(
    val url: SecretValue,
    val mimeType: String,
    val container: String,
    val codec: String,
    val bitrate: Int,
    val contentLength: Long?,
    val expiresAtMs: Long?,
)

interface YouTubeSearchService {
    suspend fun search(track: Track): List<YouTubeMatch>
}

interface YouTubeStreamResolver {
    suspend fun resolve(videoId: String, quality: AudioQuality): AudioStreamSource
}
```

URL strumienia jest sekretem, ponieważ zawiera podpisane parametry. Nie może pojawić się w
`toString()`, logach, diagnostyce ani trwałym cache.

## Pakiet 1 — klient HTTP i przechwycone fixture

1. Dodać jeden współdzielony transport HTTP z timeoutami połączenia i odpowiedzi.
2. Ustawić kontrolowany `User-Agent`, język i region używane w testach.
3. Wprowadzić typy błędów: timeout, HTTP, zły schemat, blokada geograficzna, ograniczenie wieku,
   brak formatów i wymagane logowanie.
4. Przygotować zanonimizowane fixture odpowiedzi wyszukiwania, playera i konfiguracji player JS.
5. Testy jednostkowe nie mogą zależeć od działającego YouTube.

Kryterium: parsery można rozwijać offline, a logi nie zawierają pełnych URL-i ani nagłówków.

## Pakiet 2 — własne wyszukiwanie YouTube

1. Zbudować zapytanie z tytułu, głównego wykonawcy i opcjonalnie ISRC.
2. Wywołać publiczny protokół używany przez klienta YouTube i odizolować profil klienta w
   `YouTubeClientProfile`.
3. Parsować wyłącznie wymagane pola: video ID, tytuł, kanał, długość i oznaczenia oficjalności.
4. Odrzucać playlisty, kanały, transmisje na żywo, premiery bez dostępnego nagrania i wyniki bez ID.
5. Ograniczyć liczbę kandydatów przed rankingiem.

Kryterium: dla utworu wybranego w Nuta otrzymujemy co najmniej video ID i listę alternatyw.

## Pakiet 3 — ranking dopasowania

Punkty dodatnie:

- zgodny znormalizowany tytuł,
- zgodny wykonawca lub kanał `Artist - Topic`,
- różnica długości do kilku sekund,
- `official audio`, oficjalny kanał lub dostawca katalogowy,
- zgodność ISRC, jeśli występuje w danych.

Punkty ujemne:

- `live`, `cover`, `remix`, `karaoke`, `reaction`,
- `sped up`, `slowed`, `nightcore`,
- duża różnica czasu,
- kompilacja wielu utworów.

Każdy wynik zwraca nie tylko `score`, ale również krótkie kody powodów. Do TRACE trafiają wyłącznie
kody i wartości liczbowe, bez pełnych zapytań użytkownika.

Kryterium: testy tabelaryczne obejmują wersję studyjną, live, cover, remix i błędną długość.

## Pakiet 4 — odpowiedź playera i formaty audio

1. Zaimplementować `YouTubePlayerClient` z wymiennym profilem klienta.
2. Parsować status odtwarzalności i czytelnie mapować przyczyny odmowy.
3. Odczytać `adaptiveFormats` i pozostawić tylko formaty bez ścieżki wideo.
4. Obsłużyć format z bezpośrednim `url`.
5. Obsłużyć `signatureCipher`/`cipher` bez wykonywania arbitralnego kodu strony.
6. Rozpoznać i przekształcić parametr `n`, gdy jest wymagany.
7. Cache'ować wyłącznie opis transformacji playera, z krótkim TTL i kluczem wersji player JS.

Najpierw powstaje ścieżka bezpośredniego URL-a. Transformacje podpisu i `n` dokładamy na podstawie
fixture oraz rzeczywistych przypadków, nie jako jeden duży parser bez testów.

Kryterium: resolver zwraca co najmniej jeden poprawnie opisany format audio albo konkretny typ błędu.

## Pakiet 5 — wybór formatu

Preferencje domyślne:

1. audio-only,
2. format wspierany przez docelowy player,
3. Opus/WebM dla jakości i transferu, AAC/M4A jako fallback zgodności,
4. bitrate odpowiadający ustawieniu `LOW`, `NORMAL` lub `HIGH`,
5. znany `contentLength` i czas wygaśnięcia, jeśli są dostępne.

Selektor nie może zakładać, że najwyższy bitrate zawsze jest najlepszy. Uwzględnia kodek,
platformę, oszczędzanie danych i energii.

## Pakiet 6 — walidacja strumienia w Dockerze

1. Wykonać żądanie zakresowe tylko dla pierwszych kilku kilobajtów.
2. Sprawdzić kod HTTP, `Content-Type`, niezerową odpowiedź i zgodność z wybranym formatem.
3. Natychmiast odrzucić odebrane bajty; nie tworzyć pliku z utworem.
4. Dla `403` lub wygasłego URL-a wykonać dokładnie jedno ponowne rozwiązanie video ID.
5. Dla `429` uszanować `Retry-After`; nie wykonywać agresywnych retry.

Kryterium: test ręczny w kontenerze kończy się `stream_validation_completed`, bez odtwarzania
i bez pozostawienia danych audio w artefaktach.

## Pakiet 7 — integracja GUI

1. Kliknięcie utworu Spotify uruchamia stan `matching`.
2. GUI pokazuje wybrane dopasowanie YouTube oraz możliwość wyświetlenia alternatyw.
3. Następnie pokazuje `resolving` i wynik walidacji strumienia.
4. Błąd dopasowania i błąd strumienia są osobnymi komunikatami.
5. Na tym etapie przycisk odtwarzania nie uruchamia jeszcze dźwięku.

## Cache

Trwale zapisujemy wyłącznie:

```text
Spotify track ID → YouTube video ID + score + czas weryfikacji + źródło wyboru
```

URL strumienia może być przechowywany tylko w pamięci do czasu wygaśnięcia z marginesem bezpieczeństwa.
Ręczna zmiana dopasowania użytkownika ma pierwszeństwo przed automatycznym rankingiem.

## Diagnostyka

Planowane zdarzenia:

```text
youtube_search_started
youtube_search_completed
youtube_match_selected
youtube_player_request_started
youtube_player_response_received
youtube_cipher_transform_selected
youtube_stream_selected
youtube_stream_validation_started
youtube_stream_validation_completed
youtube_stream_refresh_started
```

Pola dozwolone: `operationId`, liczba kandydatów, score, kody powodów, video ID w formie skróconej
lub zahashowanej, codec, container, bitrate bucket, kod HTTP i czas operacji.

Zakazane: pełny URL strumienia, podpis, parametr `n`, cookies, tokeny, pełna treść odpowiedzi,
pełne zapytanie wyszukiwania i odebrane bajty audio.

## Testy

- parser wyników wyszukiwania,
- ranking tabelaryczny,
- parser `adaptiveFormats`,
- bezpośredni URL,
- `signatureCipher`,
- transformacja `n`,
- statusy niedostępności,
- wybór Opus/AAC i bitrate,
- wygaśnięcie URL-a i pojedynczy retry,
- `429` i `Retry-After`,
- redakcja URL-i oraz parametrów podpisu,
- test integracyjny z lokalnym serwerem fixture,
- ręczny test live uruchamiany jawnie, poza zwykłym `allTests`.

## Kolejność wykonania

1. Kontrakty, transport i fixture.
2. Wyszukiwanie YouTube.
3. Ranking i ekran alternatyw.
4. Player response oraz bezpośrednie formaty audio.
5. Podpis i transformacja `n`.
6. Selektor formatu.
7. Walidacja zakresowa w Dockerze.
8. Cache dopasowań.
9. Integracja z `PlaybackCoordinator`.

## Warunek zakończenia fazy

- prawdziwy utwór Spotify otrzymuje sensowne dopasowanie YouTube,
- użytkownik może wybrać alternatywę,
- resolver zwraca aktualny strumień audio-only,
- kontener potwierdza dostępność małym żądaniem zakresowym,
- żaden URL ani podpis nie trafia do logów lub trwałego cache,
- testy offline przechodzą bez dostępu do Spotify i YouTube,
- zmiana profilu klienta lub parsera nie wpływa na GUI i przyszły AudioPlayer.
