package app.nuta.musicbrainz

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Artist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.net.httpGet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * Znacznik ostatniego żądania do `musicbrainz.org` — chroniony [requestMutex], bo
     * [search] woła dwa żądania (recording + artist) i mogą lecieć współbieżne wyszukiwania
     * z różnych ekranów. Pole instancji, nie companion object: throttling ma obowiązywać per
     * `MusicBrainzRepository`, a nie globalnie w procesie (żeby testy jednostkowe nie dzieliły
     * stanu między sobą).
     */
    private var lastRequestAtMs = 0L
    private val requestMutex = Mutex()

    suspend fun search(query: String): SearchResult {
        val tracks = searchTracks(query)
        val artists = searchArtists(query)
        return SearchResult(tracks = tracks, playlists = emptyList(), artists = artists)
    }

    /** Same nagrania — osobno od wykonawców, żeby UI mogło je pokazać bez czekania na drugie żądanie. */
    suspend fun searchTracks(query: String): List<Track> {
        val lucene = buildLuceneQuery(query) ?: return emptyList()
        val encoded = java.net.URLEncoder.encode(lucene, "UTF-8")
        val tracks = parseRecordings(throttledGet("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=20"))
        logger.info("MusicBrainz", "search_completed", "Zakończono wyszukiwanie MusicBrainz", fields = mapOf("results" to tracks.size.toString()))
        return tracks
    }

    /**
     * Wykonawcy pasujący do zapytania (`ws/2/artist/`). Osobne żądanie, bo endpoint nagrań
     * zwraca tylko wykonawców przypisanych do konkretnych utworów — sam wpisany „Haddaway"
     * ma dać wykonawcę nawet wtedy, gdy żaden jego utwór nie trafi w top wyników.
     * Błąd tego żądania nie może wywalić całego wyszukiwania, dlatego runCatching.
     */
    suspend fun searchArtists(query: String): List<Artist> {
        val lucene = buildArtistQuery(query) ?: return emptyList()
        return runCatching {
            val encoded = java.net.URLEncoder.encode(lucene, "UTF-8")
            parseArtists(throttledGet("https://musicbrainz.org/ws/2/artist/?query=$encoded&fmt=json&limit=10"))
        }.getOrElse { error ->
            // CancellationException musi lecieć dalej — inaczej anulowanie korutyny (np. przy
            // szybkim pisaniu, gdy kolejny znak startuje nowe wyszukiwanie) zostałoby cicho
            // zamienione na "brak wykonawców", zamiast prawidłowo przerwać całą operację.
            if (error is CancellationException) throw error
            logger.warn("MusicBrainz", "artist_search_failed", "Nie udało się wyszukać wykonawców", fields = mapOf("reason" to (error.message ?: "unknown")))
            emptyList()
        }
    }

    /**
     * Utwory wykonawcy po jego MBID (`arid:` w indeksie nagrań) — zweryfikowane 09.09.2026:
     * dla Haddawaya 829 nagrań z czasami trwania. [Artist.id] w trybie ListenBrainz jest MBID-em
     * (tak buduje je [parseArtists]), więc nie trzeba go najpierw rozwiązywać.
     */
    suspend fun artistTracks(artist: Artist, limit: Int): List<Track> {
        if (!MbidRegex.matches(artist.id)) {
            logger.warn("MusicBrainz", "artist_id_not_mbid", "Identyfikator wykonawcy nie jest MBID — pomijam", fields = mapOf("artistId" to artist.id))
            return emptyList()
        }
        val encoded = java.net.URLEncoder.encode("arid:${artist.id}", "UTF-8")
        val body = throttledGet("https://musicbrainz.org/ws/2/recording/?query=$encoded&fmt=json&limit=$limit")
        return parseRecordings(body)
    }

    /**
     * `httpGet` do `musicbrainz.org` z throttlingiem i retry na 503.
     *
     * Zweryfikowane curlem 12.09.2026: dwa żądania wystrzelone bez odstępu dają 503
     * („The MusicBrainz web server is currently busy") niezawodnie, za każdym razem —
     * dokładnie ten komunikat, który zobaczył użytkownik w UI po wyszukaniu „because”
     * (przy włączonych filtrach Utwory+Wykonawcy, czyli dokładnie ta para żądań).
     * Zaskoczenie: sam minimalny odstęp [MinIntervalMs] **nie wystarczał** — nawet przy
     * 1,1 s odstępu 2 z 6 żądań nadal dostały 503 (limit MusicBrainz jest współdzielony
     * z innym ruchem, nie jest to czysto lokalne "1 req/s"). Jedyne, co dało 3/3 sukcesów
     * w tym samym teście: pojedynczy retry z 2 s backoffu po samym 503. Dlatego oba
     * mechanizmy razem — throttling ogranicza to, na co mamy wpływ, retry łapie resztę.
     *
     * [MusicBrainzTimeoutMs] jest znacznie krótszy niż domyślny [DefaultHttpTimeoutMs]:
     * zweryfikowane curlem 19.09.2026, gdy serwer odpowiada — sukcesem albo błędem — robi to
     * w 0,3-0,6 s, nigdy wolniej. Znaleziony na urządzeniu przypadek, który to uzasadnia:
     * wyszukiwanie „unbeliev” trwało ~42 s, bo po 503 retry trafił w żądanie, które w ogóle
     * nie dostało odpowiedzi — z domyślnymi timeoutami (15 s connect + 20 s read) plus 2 s
     * backoffu retry to się sumowało. Krótszy timeout nie skraca czasu przy prawdziwym 503
     * (ten przychodzi szybko i tak), tylko ogranicza czas czekania na martwe połączenie.
     */
    private suspend fun throttledGet(url: String): String {
        requestMutex.withLock {
            val waitMs = MinIntervalMs - (nowMs() - lastRequestAtMs)
            if (waitMs > 0) delay(waitMs)
            lastRequestAtMs = nowMs()
        }
        return try {
            httpGet(url, headers = mapOf("User-Agent" to UserAgent), timeoutMs = MusicBrainzTimeoutMs)
        } catch (error: Throwable) {
            if (!isHttp503(error)) throw error
            logger.warn("MusicBrainz", "rate_limited_retry", "MusicBrainz zwrócił 503 — ponawiam po backoffie", fields = mapOf("url" to url))
            delay(RetryBackoffMs)
            requestMutex.withLock { lastRequestAtMs = nowMs() }
            httpGet(url, headers = mapOf("User-Agent" to UserAgent), timeoutMs = MusicBrainzTimeoutMs)
        }
    }

    // System.currentTimeMillis jest legalne w commonMain: projekt celuje tylko w jvm("desktop")
    // + Android, obie platformy JVM-owe (patrz też ListenBrainzScrobbler.nowMs).
    private fun nowMs(): Long = System.currentTimeMillis()

    internal companion object {
        const val UserAgent = "Nuta/1.0 ( https://github.com/jablonowskit/Nuta )"

        /** Minimalny odstęp między dwoma żądaniami do musicbrainz.org z tej instancji. */
        const val MinIntervalMs = 1_100L

        /** Ile czekać po 503, zanim ponowimy raz — zweryfikowane curlem: 2 s dało 3/3 sukcesów. */
        const val RetryBackoffMs = 2_000L

        /** Timeout (connect+read) dla żądań do musicbrainz.org — zweryfikowane curlem
            19.09.2026: serwer odpowiada w 0,3-0,6 s, sukcesem albo błędem. 5 s to duży margines
            ponad obserwowany czas, ale wciąż dużo krótszy niż domyślne 20 s dla innych serwisów. */
        const val MusicBrainzTimeoutMs = 5_000

        /**
         * Rozpoznaje 503 po treści wyjątku [httpGet][app.nuta.net.httpGet] (format
         * `"HTTP $status: $response"`, patrz HttpFetch.android.kt/desktop.kt) — nie ma tu
         * typowanego kodu statusu, `httpGet` rzuca zwykły wyjątek z tekstem.
         */
        fun isHttp503(error: Throwable): Boolean = error.message?.startsWith("HTTP 503") == true

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
         * Zwraca null dla pustego zapytania (nie ma czego szukać).
         */
        fun buildLuceneQuery(query: String): String? {
            val words = sanitizeWords(query)
            if (words.isEmpty()) return null
            val boostExact = words.size >= 2
            return words.joinToString(" AND ") { """(${luceneClause("recording", it, boostExact)} OR ${luceneClause("artistname", it, boostExact)})""" }
        }

        /**
         * Zapytanie o wykonawców: każde słowo musi trafić w nazwę albo alias (`artist`
         * obejmuje nazwę, `alias` łapie warianty pisowni).
         */
        fun buildArtistQuery(query: String): String? {
            val words = sanitizeWords(query)
            if (words.isEmpty()) return null
            val boostExact = words.size >= 2
            return words.joinToString(" AND ") { """(${luceneClause("artist", it, boostExact)} OR ${luceneClause("alias", it, boostExact)})""" }
        }

        /**
         * Jedna klauzula pola:słowo, z prefiksowym wildcardem dla "bezpiecznych" słów.
         *
         * Zweryfikowane curlem 19.09.2026 — problem realny, nie kosmetyczny: wpisując
         * fragment słowa w trakcie pisania (np. „unbe” zanim doklepiesz „Unbelievable”),
         * poprzednia wersja (cytowana fraza `"unbe"`) szukała DOKŁADNEGO tokenu „unbe” i
         * dawała 14 przypadkowych trafień zamiast prawdziwego utworu. Wildcard `unbe*`
         * (bez cudzysłowu) daje 3535 trafień z „Unbelievers”/„Unbelievable Truth” na czele.
         *
         * Wildcard działa tylko na słowie BEZ znaków specjalnych Lucene — testowane osobno:
         * `AC\/DC*` (escapowany slash + wildcard) dał 0 wyników, mimo że `AC\/DC` (sam escape,
         * bez wildcarda) i `unbe*` (sam wildcard, bez znaków specjalnych) działają osobno.
         * Dlatego słowa ze znakami specjalnymi (np. „AC/DC") zostają przy cytowanej frazie —
         * to zachowanie sprzed tej zmiany, nadal poprawne dla tego przypadku.
         *
         * `"unbe"*` (gwiazdka PO zamkniętym cudzysłowie) też przetestowane i odrzucone: nie
         * jest błędem składni, ale MusicBrainz najwyraźniej ignoruje `*` w tej pozycji —
         * `count` wychodzi ~40 milionów (prawie cała baza), więc to nie jest kontrolowany
         * prefiks, tylko przypadek, że dokładne trafienia i tak mają najwyższy `score`.
         */
        /**
         * Dokładne słowo z premią (`^4`) OR prefiks. Sam wildcard dawał wszystkim trafieniom ten
         * sam wynik (Lucene nie punktuje trafień prefiksowych), więc kolejność była przypadkowa:
         * dla "pet shop boys sin" `sin*` łapało też "Single" i "It's a Sin" ginęło wśród
         * "Single Version" (zgłoszone 25.09.2026, sprawdzone curlem). Prefiks zostaje, żeby
         * niedokończone słowo nadal znajdowało pełne.
         *
         * Premia tylko przy zapytaniach z kilku słów: samo "unbe" to zwykle niedokończone pisanie,
         * a z premią na górę wracały utwory zatytułowane dosłownie "Unbe" — dokładnie błąd z
         * 19.09.2026 (sprawdzone curlem po zmianie).
         */
        private fun luceneClause(field: String, word: String, boostExact: Boolean): String = when {
            !SafeForWildcardRegex.matches(word) -> """$field:"$word""""
            boostExact -> "($field:$word^$ExactWordBoost OR $field:$word*)"
            else -> "$field:$word*"
        }

        private const val ExactWordBoost = 4

        /** Parser `ws/2/artist/`; pomija wpisy bez id lub nazwy zamiast rzucać wyjątkiem. */
        fun parseArtists(body: String): List<Artist> {
            if (body.isBlank()) return emptyList()
            val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            val artists = (root["artists"] as? JsonArray).orEmpty()
            return artists.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                // MusicBrainz nie udostępnia okładek/zdjęć w tym endpoincie — imageUrl zostaje
                // null, a UI rysuje zastępczą kafelkę z pierwszą literą (patrz Cover).
                Artist(id = id, name = name)
            }
        }

        /** Usuwa znaki, których cytowanie nie neutralizuje, i rozbija zapytanie na słowa. */
        private fun sanitizeWords(query: String): List<String> = query.split(WhitespaceRegex)
            // Cudzysłów i backslash to jedyne znaki, których cytowanie nie neutralizuje —
            // zostawione, zamknęłyby frazę w środku i zepsuły składnię.
            .map { it.replace("\"", "").replace("\\", "").trim() }
            .filter(String::isNotBlank)

        private val WhitespaceRegex = Regex("\\s+")
        private val MbidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** UWAGA: `\w` w Kotlinie/JVM NIE jest Unicode-aware domyślnie — odrzuca „światła”
            (dowiedzione testem `wildcardWorksForWordsWithPolishDiacritics`, który bez tej
            poprawki faliła). `\p{L}` (dowolna litera Unicode) i `\p{N}` (dowolna cyfra Unicode)
            działają bez dodatkowych flag i obejmują polskie znaki z ogonkami. */
        private val SafeForWildcardRegex = Regex("^[\\p{L}\\p{N}_]+$")

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
