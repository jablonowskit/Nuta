package app.nuta.search

import java.text.Normalizer

/**
 * Dopasowanie tekstu odporne na znaki diakrytyczne — „swiatla" pasuje do „światła".
 *
 * Ekran Szukaj filtruje pobrane wyniki lokalnie (żeby działała składnia `|` / `&` bez ponownego
 * zapytania), ale zwykłe `contains` ukrywało utwory, które serwis **poprawnie znalazł**:
 * użytkownik wpisuje polskie tytuły bez ogonków, a MusicBrainz zwraca je z ogonkami. Efekt był
 * dotkliwy — ekran pokazywał „brak wyników" dla utworu, który leżał już w pamięci.
 * Potwierdzone 09.09.2026 dla „Tadeusz Wozniak Zegarmistrz swiatla" → MusicBrainz zwracał
 * „Tadeusz Woźniak — Zegarmistrz światła", a filtr to odrzucał.
 */
internal fun String.matchesLoosely(word: String): Boolean =
    foldDiacritics().contains(word.foldDiacritics(), ignoreCase = true)

/**
 * Rozkłada znaki na literę bazową + znak diakrytyczny (NFD) i usuwa same znaki diakrytyczne,
 * więc „ś" → „s", „ó" → „o". Litery z przekreśleniem (ł, ø) nie rozkładają się w NFD — nie mają
 * osobnego znaku diakrytycznego — dlatego mapujemy je wprost.
 */
internal fun String.foldDiacritics(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(DiacriticMarksRegex, "")
        .replace('ł', 'l').replace('Ł', 'L')
        .replace('ø', 'o').replace('Ø', 'O')

/** `\p{Mn}` = Unicode "Mark, nonspacing", czyli same znaki diakrytyczne po rozkładzie NFD. */
private val DiacriticMarksRegex = Regex("""\p{Mn}+""")
