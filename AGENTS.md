# Zasady pracy w repozytorium Nuta

## Budowanie i testowanie

- Nie uruchamiaj Gradle, kompilatora, testów ani aplikacji bezpośrednio na hoście.
- Nie używaj na hoście `gradlew`, `gradlew.bat`, lokalnej Javy ani lokalnego SDK.
- Budowanie i testy wykonuj zawsze wewnątrz obrazu Docker za pomocą `scripts/build.ps1`.
- Aplikację uruchamiaj za pomocą `scripts/run.ps1`.
- Host służy wyłącznie do edycji plików oraz sterowania Dockerem.
- Nie instaluj na hoście zależności projektu ani narzędzi potrzebnych do kompilacji.
- Jeżeli weryfikacja nie jest możliwa w istniejącym obrazie, popraw Dockerfile lub skrypty zamiast uruchamiać narzędzia lokalnie.

- wyjatek do dignozu aplikacji windows mozesz uruchamiac jave i sama aplikacje na hoscie

## Dobór modelu do zadań

- Do prostych, jednoznacznych zadań (proste wyszukiwanie, drobne poprawki, mechaniczne zmiany) używaj prostszych/tańszych modeli LLM.
- Do zadań złożonych (wieloetapowe zmiany, analiza architektury, trudne decyzje projektowe) używaj bardziej zaawansowanych modeli LLM.

## Czym jest ten projekt

Odtwarzacz muzyki w Kotlin Multiplatform. Dwa niezależne przełączniki:
- **Źródło danych** (`DataSource`): SPOTIFY albo LISTENBRAINZ — metadane, wyszukiwanie,
  playlisty, ulubione. Każde działa „jakby drugie nie istniało".
- **Źródło audio** (`AudioSource`): AUTO / YOUTUBE / SOUNDCLOUD — sam strumień dźwięku.

Spotify: nieoficjalne API Web Playera (OAuth **wykluczony decyzją projektu**; desktop
przechwytuje ciasteczko `sp_dc` przez helper WebView2). ListenBrainz: własne API plus
MusicBrainz jako katalog do wyszukiwania (ListenBrainz sam wyszukiwania nie ma).
Audio: YouTube przez nieoficjalne wewnętrzne API „InnerTube" (bez SDK i bez klucza API)
lub SoundCloud. Android to platforma główna (mała, oszczędna apka: Kotlin + Compose +
Media3 + systemowy WebView); desktop używa `mpv` przez JSON IPC i jest odłożony.

## Moduły

- `:composeApp` — kod wspólny KMP (`commonMain`, `androidMain`, `desktopMain`): UI,
  abstrakcje odtwarzania, klienci Spotify/ListenBrainz/MusicBrainz, scrobbler,
  desktopowy resolver YouTube (`NutaYouTubeMediaService`) i player (`MpvAudioPlayer`).
- `:androidApp` — warstwa androidowa: `Media3AudioPlayer`, `PlaybackService`
  (MediaSessionService), `AndroidYouTubeMediaService` (osobny resolver InnerTube,
  niezależny od desktopowego — **mogą się rozjechać**), `YtEjsSolver`,
  `LoudnessAudioProcessor`.

## Jak weryfikować zmiany

Kolejność ma znaczenie — od najtańszego do najdroższego:

1. **Żądania sieciowe sprawdzaj curlem, zanim napiszesz Kotlina.** Nie zgaduj kształtu
   odpowiedzi API i nie czekaj na CI, żeby się dowiedzieć. Odpowiedzi z curla nadają się
   wprost na fixture'y testowe.
2. **Testy jednostkowe na prawdziwych odpowiedziach API** — parsery JSON, progi, DSP.
   To jedyna dokumentacja, która psuje build, gdy przestaje być prawdą, więc ma
   pierwszeństwo przed opisem w pliku `.md`.
3. **CI + telefon** — brak lokalnego Android SDK, więc pliki androidowe kompiluje
   dopiero CI. Całą procedurę wraz z pułapkami opisuje skill `wgraj-na-telefon`.
4. Etykieta w prawym górnym rogu apki pokazuje `v<wersja> · <git-sha>`, więc da się
   potwierdzić, z którego commitu pochodzi zainstalowany build.

Procedury powtarzalne siedzą w `.claude/skills/` (`wgraj-na-telefon`,
`sprawdz-scrobble`) — nie powtarzaj ich tutaj, żeby nie utrzymywać dwóch źródeł prawdy.

## Dokumentowanie ustaleń

- Ustalenia zapisuj **przy kodzie**, którego dotyczą: KDoc/komentarz z datą weryfikacji
  („zweryfikowane curlem 2026-09-08", „potwierdzone na danych z konta 09.09.2026").
  Osobne pliki spec rozjeżdżają się z kodem w ciszy — projekt miał formalny spec
  `youtube-audio-source` w OpenSpec i po trzech tygodniach wymieniał profile klienta
  YouTube (`WEB`/`ANDROID`/`IOS`/`TVHTML5`), z których **żaden już nie istniał** w kodzie.
- Wyjątek: przebieg badań, którego nie da się odtworzyć z kodu ani z historii git —
  co próbowaliśmy i dlaczego nie zadziałało. To trafia do `docs/`.
- Nigdy nie zapisuj sekretów (tokeny, ciasteczka `sp_dc`) w logach ani w repo.

## Znany kruchy obszar

Scraping InnerTube YouTube jest z natury adwersarialny — YouTube zmienia wymagania
klientów, throttling i schematy podpisywania URL-i bez ostrzeżenia i bez gwarancji
stabilności. Aktualnie nierozwiązany blocker (SABR/UMP) opisuje
`docs/sabr-blocker/` — łącznie z hipotezami, które okazały się błędne.

## Konwencje commitów

- Każda zmiana: commit z opisową wiadomością, stopka
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`, push na `main`.
