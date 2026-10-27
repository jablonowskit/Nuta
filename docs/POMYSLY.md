# Pomysły na ulepszenia

Lista otwartych pomysłów, żeby nic nie zginęło między sesjami. Po zrobieniu punkt
usuń stąd — historia jest w gicie. Rozmiar to zgrubna ocena: mały ≈ jeden commit,
średni ≈ kilka plików, duży ≈ zmiana architektury.

## Do zrobienia

### Słuchanie
- **Teksty utworów** (średni) — LRCLIB (darmowe API), także zsynchronizowane, przewijane
  z odtwarzaniem.
- **Equalizer** (średni) — presety (bas, wokal) w tym samym łańcuchu audio co
  `LoudnessAudioProcessor`.
- **Wznawianie po podłączeniu słuchawek/BT** (mały) — pauza po odłączeniu już jest
  (`setHandleAudioBecomingNoisy`), brakuje drugiego kierunku.
- **Pamięć pozycji w długich utworach** (mały) — miksy/sety wznawiają się od miejsca
  przerwania przy ponownym odtworzeniu.
- **Pełny gapless / crossfade** (duży) — następny utwór w playliście ExoPlayera zamiast
  jednego elementu. Dopiero jeśli preload początku dźwięku (`preloadNextTrack`) nie zbije
  przerwy poniżej ~0,2 s. Pomiar 27.10.2026: ~0,8 s przed preloadem.
- **Tryb offline / przypinanie** (duży) — trwałe zapisanie strumieni w `SimpleCache`.

### Biblioteka
- **Historia odtwarzania** (średni) — ostatnio grane (ListenBrainz albo lokalnie)
  z ponownym odtworzeniem.
- **Sortowanie Polubionych** (mały) — data polubienia / wykonawca / tytuł.
- **Edycja playlist ListenBrainz** (średni) — usuwanie utworów, zmiana kolejności (API to
  obsługuje).
- **Wykres w Statystykach** (średni) — odsłuchania dziennie/tygodniowo.

### Wygoda
- **Kolor tła z okładki** (mały) — rozwinięty player w dominującym kolorze okładki.
- **Pozycja przewinięcia list** (mały) — zachowana po zmianie zakładki.
- **Udostępnianie utworu** (mały) — link ListenBrainz/MusicBrainz przez systemowe
  „Udostępnij”.
- **Aktualizacja APK z GitHuba w aplikacji** (średni) — bez adb i kabla.
- **Skróty klawiszowe na desktopie** (mały) — spacja, strzałki.

## Drobne rzeczy techniczne
- **Tryb podobnych (⇄) poza UI** (mały) — dokładanie siedzi w `LaunchedEffect` w `App.kt`,
  więc przy wygaszonym ekranie pewnie nie działa (niezweryfikowane). Przenieść do
  mechanizmu jak `QueueContinuation`.
- **„Ponów” po nieudanym otwarciu playlisty** (mały) — przeładowuje listę playlist zamiast
  tej jednej playlisty.
- **Desktop: wyszukiwanie Spotify** — REST `/v1/search` z tokenem web playera
  prawdopodobnie zawsze pada (komentarz w kodzie Androida); niezweryfikowane.
- **Desktop: okładki** — `PlatformRemoteImage.desktop.kt` zwraca `Unit`, okładek nie ma.
