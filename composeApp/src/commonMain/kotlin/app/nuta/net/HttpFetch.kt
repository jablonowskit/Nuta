package app.nuta.net

/** Zwykły GET/POST bez żadnego per-platformowego nagłówka specjalnego (w przeciwieństwie do
    YouTube/SoundCloud) — potrzebny tylko dlatego, że Ktor nie jest jeszcze zależnością projektu. */
expect suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String

expect suspend fun httpPost(url: String, headers: Map<String, String> = emptyMap(), body: String = ""): String
