---
name: wgraj-na-telefon
description: Zbuduj przez CI i zainstaluj APK Nuty na podłączonym telefonie, z potwierdzeniem, że instalacja faktycznie się wykonała. Użyj, gdy użytkownik mówi "wgraj", "zainstaluj", "wrzuć na telefon" albo prosi o przetestowanie zmian na urządzeniu.
---

# Wgranie Nuty na telefon

Brak lokalnego Android SDK — pliki androidowe kompiluje dopiero CI. Docker na hoście
bywa niedostępny, więc CI jest w praktyce jedyną kompilacją Androida.

## Kroki

1. **Sprawdź, że zmiany są wypchnięte.** CI buduje z `origin/main`, nie z katalogu roboczego.
   ```
   git status --short && git log --oneline -1
   ```
   Jeśli są niezacommitowane zmiany albo lokalny commit przed pushem — najpierw to domknij,
   inaczej zainstalujesz starą wersję i tego nie zauważysz.

2. **Sprawdź telefon:** `adb devices` musi pokazać urządzenie ze stanem `device`.
   Stan `unauthorized` lub brak wpisu → poproś użytkownika o podłączenie/odblokowanie;
   nie próbuj obchodzić.

3. **Poczekaj na CI i pobierz APK:**
   ```
   python scripts/download-latest-apk.py
   ```
   Zwraca ścieżkę APK. Zielone CI to jednocześnie potwierdzenie, że kompilacja i testy przeszły.
   Build trwa kilka minut — ustaw timeout ~900 s.

4. **Zainstaluj:**
   ```
   python scripts/deploy-android.py --apk "<ścieżka z kroku 3>"
   ```
   Zachowuje dane aplikacji (tokeny, ustawienia, kolejka) i uruchamia apkę.

5. **POTWIERDŹ instalację — nie pomijaj tego kroku:**
   ```
   adb shell dumpsys package app.nuta | grep -E "versionName|lastUpdateTime"
   date '+%Y-%m-%d %H:%M:%S'
   ```
   `lastUpdateTime` musi być sprzed kilku–kilkunastu sekund. Jeśli jest starszy,
   instalacja się **nie** wykonała, mimo braku błędu.

## Pułapka: `--install` nie instaluje

`scripts/download-latest-apk.py --install` bywa, że tylko pobiera APK, wypisuje jego
ścieżkę i **kończy się bez instalacji — nie zgłaszając żadnego błędu**.
Potwierdzone 09.09.2026: `lastUpdateTime` został sprzed dwóch godzin.

Dlatego: wywołuj `download-latest-apk.py` bez `--install`, a instalację rób osobno
przez `deploy-android.py`. I zawsze sprawdzaj `lastUpdateTime` — bez tego zameldujesz
„wgrane", gdy na telefonie stoi stara wersja.

## Czego NIE robić

- Nie uruchamiaj `gradlew`/`gradlew.bat` ani lokalnej Javy na hoście (patrz AGENTS.md).
- Nie odinstalowuj apki, żeby „mieć czysto" — traci tokeny ListenBrainz/Spotify,
  a użytkownik musi logować się od nowa.
