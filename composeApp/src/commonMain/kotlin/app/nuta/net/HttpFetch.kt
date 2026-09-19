package app.nuta.net

/** Domyślny timeout połączenia/odczytu (ms) — dopasowany do wolniejszych, ale wciąż zdrowych
    serwisów (YouTube resolve, Spotify). Część serwisów (patrz [timeoutMs] w wywołaniach)
    odpowiada w praktyce w <1s, więc dla nich osobny, krótszy timeout jest uzasadniony —
    zweryfikowane 19.09.2026: gdy MusicBrainz nie odpowiada w ogóle (a nie tylko zwraca 503),
    domyślne 15s connect + 20s read + 2s backoffu retry dały ~42s oczekiwania na wyszukiwanie. */
const val DefaultHttpTimeoutMs = 20_000

/** Zwykły GET/POST bez żadnego per-platformowego nagłówka specjalnego (w przeciwieństwie do
    YouTube/SoundCloud) — potrzebny tylko dlatego, że Ktor nie jest jeszcze zależnością projektu. */
expect suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = DefaultHttpTimeoutMs): String

expect suspend fun httpPost(url: String, headers: Map<String, String> = emptyMap(), body: String = "", timeoutMs: Int = DefaultHttpTimeoutMs): String
