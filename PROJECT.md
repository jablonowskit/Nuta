# Nuta — decyzje, które nadal obowiązują

Opis projektu, moduły i zasady pracy: [`AGENTS.md`](AGENTS.md).
Ten plik trzyma **wyłącznie** decyzje i ograniczenia, które są nadal aktualne i których
nie da się wyczytać z kodu. Jeśli coś tutaj rozjedzie się z kodem — kod ma rację, a wpis
trzeba poprawić albo usunąć.

Zapis historyczny (jak myśleliśmy w lipcu 2026, w dużej części nieaktualny):
[`docs/PROJECT_HISTORIA_2026-07.md`](docs/PROJECT_HISTORIA_2026-07.md).

## Decyzje projektowe

- **OAuth do Spotify jest wykluczony** — nie „jeszcze nie zrobiony". Uwierzytelnianie
  idzie przez sesję Web Playera (ciasteczko `sp_dc`), celem jest parytet ze Spotube.
  Nie proponuj OAuth jako rozwiązania problemów z logowaniem.
- **iOS jest poza zakresem.** Android to platforma główna; desktop odłożony.
- **Nuta nie tworzy playlist w YouTube i nie wymaga logowania do YouTube.**
- **Spotube można analizować, nie kopiować** — architekturę i rozwiązania techniczne
  tak, kod przy zachowaniu licencji.

## Bezpieczeństwo — sekrety nigdy w logach

Nigdy nie logujemy:

- tokenów sesji Spotify, tokenów Web Playera, tokenu API ListenBrainz,
- ciasteczek (szczególnie `sp_dc`) oraz nagłówków `Cookie` i `Authorization`,
- pełnych podpisanych URL-i strumieni i parametrów podpisu,
- haseł, kluczy API, zawartości `.env`,
- pełnych odpowiedzi HTTP mogących zawierać dane użytkownika.

Redakcja jest centralna w warstwie HTTP (`[REDACTED]` zamiast wartości) i **objęta
testami** (`LogRedactorTest`, `SecretValueTest`) — nie polegamy na dyscyplinie przy
każdym wywołaniu loggera. Wyciek `sp_dc` traktujemy jak przejęcie sesji użytkownika;
to konkretny błąd Spotube, którego nie powtarzamy.

Telemetria poza urządzenie jest domyślnie wyłączona. Eksport paczki diagnostycznej
wymaga świadomej akcji użytkownika i zawiera wyłącznie zredagowane dane.

Awaria zapisu logu nie może zatrzymać odtwarzania. Ta sama zasada dotyczy nieudanego
scrobbla — utracony wpis w statystykach nie jest powodem, żeby muzyka przestała grać.

## Istotne ograniczenia

- **Scraping InnerTube YouTube jest adwersarialny.** YouTube zmienia wymagania klientów,
  throttling i schematy podpisywania URL-i bez ostrzeżenia. Aktualny nierozwiązany
  blocker: [`docs/sabr-blocker/`](docs/sabr-blocker/).
- **Protokół Web Playera Spotify jest niepubliczny** — może przestać działać bez
  ostrzeżenia i podlega regulaminowi Spotify.
- **Dwa resolvery YouTube** (androidowy i desktopowy) to osobny kod, nie wspólny —
  mogą się rozjechać i trzeba je poprawiać niezależnie.
- **Publiczna dystrybucja** wymaga ponownej oceny wobec regulaminów Spotify i YouTube
  przed publikacją.
- **Energooszczędność na Androidzie** jest wymogiem, nie życzeniem: natywne odtwarzanie
  w usłudze tła, bez zbędnego wybudzania przy zablokowanym ekranie.
