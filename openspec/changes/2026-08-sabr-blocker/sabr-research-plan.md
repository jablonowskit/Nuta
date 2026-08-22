# Plan: zgłębienie protokołu SABR/UMP (badanie, bez pisania kodu)

## Kontekst

Sesja debugowania YouTube-audio z 2026-08-22 (patrz `proposal.md` w tym katalogu)
ustaliła ostatecznie: PoToken/BotGuard **nie jest** przyczyną, dla której klient `WEB`
(i inne poza `VISIONOS`/`ANDROID_VR`) nie daje klasycznych, podpisanych URL-i audio.
Potwierdzone dwukrotnie podsłuchem ruchu prawdziwej przeglądarki Chrome przez CDP
(`Network.requestWillBeSent`): rzeczywiste odtwarzanie w przeglądarce idzie w 100%
przez **SABR/UMP** — binarny protokół POST do `videoplayback` (bez `itag=`, bez
podpisanego URL-a w starym stylu), a nie przez `GET + Range` na gotowym URL-u z
`adaptiveFormats[]`, na którym opiera się cała ta apka (i yt-dlp/NewPipe generalnie).

`VISIONOS` (i formalnie `ANDROID_VR`, choć ten jest już martwy z innego powodu — patrz
`proposal.md`) działają dziś tylko dlatego, że YouTube nie wymusił na nich jeszcze
SABR — to z definicji tymczasowe, już czwarty klient w tej samej sytuacji od kiedy
NewPipe/community to śledzi (WEB → TVHTML5 → ANDROID_VR → VISIONOS).

Cel tego zadania: zbadać SABR jako potencjalną **trwałą** drogę naprzód, zamiast dalej
gonić kolejne nie-jeszcze-zablokowane klienty. To ma być **czysty research** (bez
pisania kodu produkcyjnego), żeby ocenić realny zakres pracy przed podjęciem decyzji,
czy warto to robić.

Ustalone wcześniej fakty (z researchu wykonanego 2026-08-22, nie do powtarzania):
- yt-dlp ma niedokończoną, niezmergowaną implementację SABR: PR #13515, ~21500 linii
  kodu, 14+ miesięcy w review — to jest miara skali tego zadania robionego przez
  zespół, nie jednej osoby w wolnym czasie.
- SABR wymaga PoToken (w przeciwieństwie do `ANDROID_VR`, gdzie token jest ignorowany
  przez `/player` — potwierdzone tego samego dnia) — czyli dopiero na tej ścieżce
  eksperyment z generowaniem tokenu przez prawdziwy Chrome/CDP (`cdp_pot.mjs`, scratch
  script, nie w repo) staje się potencjalnie przydatny.
- Format komunikacji to binarny protokół "UMP" (nie zwykły JSON/HTTP) — dokładna
  struktura nieznana z tej sesji, tylko obserwowana "z zewnątrz" jako nieprzejrzysty
  ciąg bajtów w przechwyconym ruchu.

## Zakres: TYLKO research, bez kodu

Efektem końcowym ma być rzeczowa ocena (dokument), nie żadna zmiana w apce.

### 1. Znajdź i przeczytaj istniejącą dokumentację/implementacje UMP/SABR
- yt-dlp PR #13515 (opis, dyskusja w review, ewentualnie linkowane dokumenty
  projektowe) — co dokładnie obejmuje, jakie komponenty wyodrębniają autorzy (parser
  UMP, klient SABR, integracja z formatami).
- `LuanRT`/pokrewne repo (community reverse-engineering YouTube internals) — czy
  istnieje osobny, mniejszy pakiet tylko do parsowania UMP (bez całego yt-dlp),
  łatwiejszy do zrozumienia/przeniesienia.
- NewPipeExtractor — sprawdzić, czy (i w jakim stopniu) już ma jakiekolwiek
  wsparcie/próby SABR, albo czy explicite go unika (skoro dziś wiemy, że stoi na
  `VISIONOS`).
- Ogólnodostępne opisy formatu UMP (blog posty, gists, dyskusje na GitHub Issues) —
  zebrać wszystko, co wyjaśnia strukturę wiadomości (segmenty, media header, format
  ramek) bez potrzeby czytania całego kodu yt-dlp.

### 2. Realny zakres pracy do wdrożenia w tej apce (KMP: Android + desktop)
Oceń, punkt po punkcie, co trzeba by faktycznie zbudować, żeby
`AndroidYouTubeMediaService` (i desktopowy `NutaYouTubeMediaService`) mogły odtwarzać
przez SABR:
- Parser/koder wiadomości UMP (odczyt segmentów, nagłówków formatów, obsługa błędów).
- Klient SABR: sesja, żądania kolejnych segmentów, mapowanie na to, czego oczekuje
  ExoPlayer/mpv (to prawdopodobnie wymaga własnego `MediaSource`/`DataSource` na
  Androidzie, nie da się tego owinąć w istniejący `DefaultHttpDataSource` jak
  dotychczasowe fixy).
- Generowanie PoToken w produkcji (nie tylko lokalny spike) — czy realistycznie da się
  to zrobić w Android WebView (jak sugerował wcześniejszy plan) teraz, gdy wiemy, że
  token faktycznie jest wymagany na tej ścieżce (w przeciwieństwie do ANDROID_VR).
- Odtwarzanie na desktopie (mpv) — mpv nie ma nic wspólnego z UMP/SABR, więc potrzebny
  byłby osobny "proxy" (lokalny mini-serwer HTTP w desktopMain, tłumaczący SABR→zwykły
  stream dla mpv) — oszacować, czy to nawet jest wykonalne bez pisania własnego
  dekodera audio/wideo.

### 3. Oszacowanie czasu/ryzyka i alternatywy
- Szczera ocena: dni? tygodnie? Czy w ogóle wykonalne dla jednej osoby w tej skali
  projektu.
- Ryzyko: SABR może się zmieniać tak jak BotGuard — czy to nie jest kolejny wyścig
  zbrojeń, tylko na innym poziomie.
- Krótkie porównanie z alternatywą (rezygnacja z YouTube na rzecz innego źródła audio —
  SoundCloud / własny backend) jako opcji "mniej pracy, inny problem" — nie rozwijać w
  pełny plan, tylko zanotować jako punkt odniesienia.

## Co NIE jest częścią tego zadania
- Żadnego kodu Kotlin/Android/desktop.
- Żadnej próby faktycznej implementacji parsera UMP "na próbę".
- Żadnych zmian w `AndroidYouTubeMediaService`/`PlaybackService`/ustawieniach.

## Wynik / dostawa
Wynik researchu (jak działa UMP/SABR, jaki jest realny zakres pracy, jakie istniejące
implementacje/dokumentacja są dostępne, ocena czasu/ryzyka) trafia do kolejnego pliku w
tym samym katalogu: `openspec/changes/2026-08-sabr-blocker/sabr-research.md`,
committed i wypchnięty na `main` — dostępny dla przyszłych sesji/innych narzędzi, nie
tylko w rozmowie, w której powstał.

## Weryfikacja
Brak weryfikacji technicznej (to nie zmiana w kodzie) — sukces = wystarczające dane do
podjęcia świadomej decyzji "robimy SABR" / "nie robimy SABR teraz".
