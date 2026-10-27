# Pomysły na ulepszenia

Lista otwartych pomysłów, żeby nic nie zginęło między sesjami. Po zrobieniu punkt
usuń stąd — historia jest w gicie. Rozmiar to zgrubna ocena: mały ≈ jeden commit,
średni ≈ kilka plików, duży ≈ zmiana architektury.

## Do zrobienia

### Słuchanie
- **Pamięć pozycji w długich utworach** (mały) — miksy/sety wznawiają się od miejsca
  przerwania przy ponownym odtworzeniu.
- **Pełny gapless / crossfade** (duży) — następny utwór w playliście ExoPlayera zamiast
  jednego elementu. Dopiero jeśli preload początku dźwięku (`preloadNextTrack`) nie zbije
  przerwy poniżej ~0,2 s. Pomiar 27.10.2026: ~0,8 s przed preloadem.
- **Tryb offline / przypinanie** (duży) — trwałe zapisanie strumieni w `SimpleCache`.

### Biblioteka
- **Edycja playlist ListenBrainz** (średni) — usuwanie utworów, zmiana kolejności (API to
  obsługuje).

### Wygoda
- **Aktualizacja APK z GitHuba w aplikacji** (średni) — bez adb i kabla.
- **Skróty klawiszowe na desktopie** (mały) — spacja, strzałki.

## Drobne rzeczy techniczne
- **Desktop: wyszukiwanie Spotify** — REST `/v1/search` z tokenem web playera
  prawdopodobnie zawsze pada (komentarz w kodzie Androida); niezweryfikowane.
- **Desktop: okładki** — `PlatformRemoteImage.desktop.kt` zwraca `Unit`, okładek nie ma
  (więc i kolor tła z okładki działa tylko na Androidzie).
- **Scrobbler: identyczny `listened_at`** — dwa kolejne odsłuchania w ListenBrainz miały ten sam
  `listened_at` (1793096651) przy różnych `inserted_at` (sprawdzone curlem 27.10.2026). Możliwy
  błąd liczenia `nowMs() - positionMs` w `ListenBrainzScrobbler.submitListen`; do zbadania.
