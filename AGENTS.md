# Zasady pracy w repozytorium Nuta

## Budowanie i testowanie

- Nie uruchamiaj Gradle, kompilatora, testów ani aplikacji bezpośrednio na hoście.
- Nie używaj na hoście `gradlew`, `gradlew.bat`, lokalnej Javy ani lokalnego SDK.
- Budowanie i testy wykonuj zawsze wewnątrz obrazu Docker za pomocą `scripts/build.ps1`.
- Aplikację uruchamiaj za pomocą `scripts/run.ps1`.
- Host służy wyłącznie do edycji plików oraz sterowania Dockerem.
- Nie instaluj na hoście zależności projektu ani narzędzi potrzebnych do kompilacji.
- Jeżeli weryfikacja nie jest możliwa w istniejącym obrazie, popraw Dockerfile lub skrypty zamiast uruchamiać narzędzia lokalnie.

