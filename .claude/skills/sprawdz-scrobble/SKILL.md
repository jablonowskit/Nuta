---
name: sprawdz-scrobble
description: Sprawdź, czy scrobble z Nuty dotarły na konto ListenBrainz i czy mają poprawne znaczniki czasu. Użyj, gdy użytkownik mówi że przesłuchał utwór, pyta czy scrobblowanie działa, albo po zmianach w ListenBrainzScrobbler/ListenThreshold/tickerze pozycji.
---

# Weryfikacja scrobbli ListenBrainz

Konto testowe: **`piotrowskit`**. Endpoint `/listens` jest **publiczny** — do odczytu
nie potrzebujesz tokenu. Tokenu API i tak nie odczytasz: siedzi w release APK, a
`adb shell run-as app.nuta` zwraca `package not debuggable` (to poprawne zachowanie,
nie awaria). Bez tokenu **nie usuniesz** wpisu — o usunięcie poproś użytkownika.

## Odczyt

```bash
curl -s -H "User-Agent: Nuta/1.0 ( https://github.com/jablonowskit/Nuta )" \
  "https://api.listenbrainz.org/1/user/piotrowskit/listens?count=10"
```

Dla każdego wpisu wypisz: `listened_at`, `inserted_at`, wykonawcę, tytuł,
`additional_info.submission_client`, `duration_ms`.

## Co czytać w wynikach

- **`submission_client: "Nuta"`** → scrobbel z naszej apki.
- **`submission_client: null`** → to lipcowy import ze Spotify, nie nasz.

## Sprawdź znaczniki czasu — tu był prawdziwy błąd

Samo istnienie wpisu **nie znaczy, że jest poprawny**. Policz dla każdego wpisu z Nuty:

```
implikowana_pozycja = inserted_at - listened_at
próg = min(duration_ms / 2, 240000)
```

Trzy sygnały błędu:

1. **`implikowana_pozycja > duration_ms`** → pozycja nie mogła pochodzić z tego utworu.
   To pozostałość po poprzednim, czyli scrobbel poszedł po zerowym czasie słuchania.
2. **Dwa różne utwory z identycznym `listened_at`**, choć `inserted_at` się różni →
   ta sama usterka; historia traci chronologię, a ListenBrainz porządkuje ją właśnie
   po `listened_at`.
3. **`implikowana_pozycja` znacznie różna od `progu`** → scrobbel poszedł w złym momencie.

### Znaleziony przypadek (09.09.2026, naprawiony w `0ecfd63`)

„Chandelier" (215 280 ms) miał `listened_at` identyczny jak poprzedni „United"
(240 000 ms), choć `inserted_at` różnił się o 2 minuty. Implikowana pozycja: 241 s —
**więcej niż cała długość utworu**.

Przyczyna nie była w scrobblerze, a w playerze: `startTicker()` wołał `ticker?.cancel()`,
które nie czeka na zakończenie korutyny. Stara wisiała w odczycie `player.currentPosition`
i po powrocie nadpisywała `positionMs` wyzerowane chwilę wcześniej przez `move()`.
Wyścig, więc trafiał tylko utwór po automatycznym przejściu kolejki.

Naprawione w trzech miejscach — jeśli objaw wróci, sprawdź je w tej kolejności:
`Media3AudioPlayer.startTicker` (numer generacji), `MpvAudioPlayer.startTicker`
(`playbackGeneration` przy zapisie pozycji), `ListenThreshold.isReached`
(odrzuca `positionMs > durationMs`).

## Warunki, żeby scrobbel w ogóle poszedł

Jeśli nic nie dotarło, sprawdź po kolei — zanim zaczniesz debugować kod:

- **Tryb `DataSource.LISTENBRAINZ`** — w trybie Spotify scrobbler nie wysyła nic (świadoma decyzja).
- **Token ustawiony** w Ustawieniach; puste pole = ciche wyjście bez żądania.
- **Przesłuchana połowa utworu albo 4 minuty**, co wcześniej (`ListenThreshold`).
- **Utwór dłuższy niż 30 s** — nasz próg klienta, nie ograniczenie API
  (serwis 20-sekundowe wpisy przyjmuje).

## Efekt uboczny

Rekomendacje (`cf/recommendation`) i playlisty Daily/Weekly Jams MetaBrainz generuje
**wyłącznie** z historii odsłuchań. Póki historia jest pusta lub zatruta błędnymi
wpisami, te funkcje nie mają z czego się uczyć — dlatego błędny `listened_at` warto
usunąć, a nie zostawić „bo to tylko jeden wpis".
