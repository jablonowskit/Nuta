package app.nuta.musicbrainz

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.net.httpGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wyszukiwanie katalogu w trybie ListenBrainz — ListenBrainz samo nie ma wyszukiwania,
 * korzysta z siostrzanej bazy metadanych MusicBrainz. Kształt JSON (`artist-credit[].artist.name`)
 * różni się od batch-lookupu ListenBrainz (`artist.artists[].name`), więc ma własny, osobny
 * parser — zweryfikowane curlem 2026-08-23.
 */
class MusicBrainzRepository(private val logger: NutaLogger) {
    suspend fun search(query: String): SearchResult {
        val lucene = buildLuceneQuery(query) ?: return SearchResult(emptyList(), emptyList())
        val encoded = java.net.URLEncoder.encode(lucene, "UTF-8")
        val body = httpGet(
            "https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=20",
            headers = mapOf("User-Agent" to UserAgent),
        )
        val tracks = parseRecordings(body)
        logger.info("MusicBrainz", "search_completed", "Zakończono wyszukiwanie MusicBrainz", fields = mapOf("results" to tracks.size.toString()))
        return SearchResult(tracks = tracks, playlists = emptyList(), artists = emptyList())
    }

    internal companion object {
        const val UserAgent = "Nuta/1.0 ( https://github.com/jablonowskit/Nuta )"

        /**
         * Buduje zapytanie Lucene wymagające, by **każde** wpisane słowo trafiło w tytuł
         * nagrania **albo** w nazwę wykonawcy — bez zgadywania, które słowa są czym.
         *
         * Wcześniej surowy tekst szedł prosto do `query=`, co dawało fatalne wyniki: MusycBrainz
         * szukał słów gdziekolwiek i dla „Haddaway What Is Love" zwracał 2,2 mln trafień, na
         * czele covery przypadkowych wykonawców, a oryginał nie mieścił się nawet w top 25.
         * Po zmianie (zweryfikowane 09.09.2026): 376 trafień z „Haddaway — What Is Love" na
         * pierwszym miejscu; „Ice MC Scream" 365 598 → 24 z poprawnym utworem na czele.
         *
         * Każde słowo jest wstawiane jako fraza w cudzysłowach, bo to neutralizuje znaki
         * specjalne Lucene bez ich wycinania — „AC/DC" wyszukuje się poprawnie, a wcześniejsza
         * próba zamiany takich znaków na spacje rozbijała zapytanie.
         *
         * Zwraca null dla pustego zapytania (nie ma czego szukać).
         */
        fun buildLuceneQuery(query: String): String? {
            val words = query.split(WhitespaceRegex)
                // Cudzysłów i backslash to jedyne znaki, których cytowanie nie neutralizuje —
                // zostawione, zamknęłyby frazę w środku i zepsuły składnię.
                .map { it.replace("\"", "").replace("\\", "").trim() }
                .filter(String::isNotBlank)
            if (words.isEmpty()) return null
            return words.joinToString(" AND ") { """(recording:"$it" OR artistname:"$it")""" }
        }

        private val WhitespaceRegex = Regex("\\s+")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parsowanie odpowiedzi `ws/2/recording/` wydzielone z [search], żeby dało się je
         * sprawdzić testami na prawdziwych odpowiedziach API bez sieci — tu wychodziły błędy,
         * których kompilator nie widzi (brakujące pola, `length: null`, jawne JSON-owe null-e).
         *
         * Pusta odpowiedź i niekompletne wpisy dają pustą listę / pominięty wpis zamiast
         * wyjątku: awaria wyszukiwania nie może wywalić apki.
         */
        fun parseRecordings(body: String): List<Track> {
            if (body.isBlank()) return emptyList()
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val recordings = (root["recordings"] as? JsonArray).orEmpty()
            return recordings.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val artistCredit = (obj["artist-credit"] as? JsonArray).orEmpty()
                val artists = artistCredit.mapNotNull {
                    (it as? JsonObject)?.get("artist")?.let { a -> a as? JsonObject }?.get("name")?.jsonPrimitive?.contentOrNull
                }
                val artistMbid = (artistCredit.firstOrNull() as? JsonObject)
                    ?.get("artist")?.let { it as? JsonObject }
                    ?.get("id")?.jsonPrimitive?.contentOrNull
                Track(
                    id = id,
                    title = title,
                    artists = artists,
                    album = "",
                    // `length` bywa jawnym null-em — wtedy 0, a odtwarzacz nie klampuje pozycji
                    // do zera (patrz seekTo w Media3AudioPlayer/MpvAudioPlayer).
                    durationMs = obj["length"]?.jsonPrimitive?.longOrNull ?: 0L,
                    artistMbid = artistMbid,
                )
            }
        }
    }
}
