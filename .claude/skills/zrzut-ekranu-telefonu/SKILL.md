# Zrzut ekranu z telefonu

Użyj, gdy trzeba zobaczyć, co faktycznie wyświetla telefon (np. użytkownik mówi
"zrób zrzut ekranu i zobacz sam", zgłasza że coś jest niewidoczne/źle ułożone,
albo trzeba zweryfikować UI bez zgadywania po samym kodzie).

## Kroki

1. **Zrób zrzut na telefonie** (bez pojedynczych `/`, bo Git Bash na Windows
   przerabia `/sdcard/...` na ścieżkę Windows i `adb` dostaje śmieci —
   podwójny slash to obejście):
   ```
   adb shell "screencap -p //sdcard//nuta_screen.png"
   ```

2. **Pobierz na dysk** (ten sam trik z podwójnym slashem po stronie zdalnej):
   ```
   adb pull //sdcard//nuta_screen.png <ścieżka lokalna, np. scratch_screen.png>
   ```

3. **Obejrzyj** narzędziem Read (obsługuje PNG).

4. **Posprzątaj** lokalny plik tymczasowy po skończonej analizie (na telefonie
   plik w `/sdcard/` może zostać — nie przeszkadza).

## Pułapka

`adb shell screencap -p /sdcard/plik.png` z pojedynczym slashem w Git Bash na
Windows kończy się błędem `failed to stat remote object 'C:/Program
Files/Git/sdcard/plik.png'` — MSYS przepisuje ścieżkę zaczynającą się od `/`
tak, jakby to była ścieżka lokalna. Podwójny slash (`//sdcard//...`) omija tę
konwersję i dociera do `adb` bez zmian.
