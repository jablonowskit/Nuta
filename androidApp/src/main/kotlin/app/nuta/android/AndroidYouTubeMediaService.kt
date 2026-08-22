package app.nuta.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Track
import app.nuta.core.security.SecretValue
import app.nuta.youtube.AudioFormatSelector
import app.nuta.youtube.AudioStreamSource
import app.nuta.youtube.YouTubeCandidate
import app.nuta.youtube.YouTubeMatch
import app.nuta.youtube.YouTubeMediaService
import app.nuta.youtube.YouTubeResolution
import app.nuta.settings.PlaybackSettingsStore
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidYouTubeMediaService(
    private val logger: NutaLogger,
    private val settingsStore: PlaybackSettingsStore,
    private val context: Context,
) : YouTubeMediaService {
    private val json = Json { ignoreUnknownKeys = true }
    private val ejsSolver = YtEjsSolver(context, logger)
    /** base.js zwykle jest stabilny między kolejnymi rozwiązaniami (zmienia się tylko przy nowej
        wersji odtwarzacza YouTube) — jeden slot cache oszczędza osobne pobranie ~200-300 KB przy
        każdym utworze. */
    private var cachedPlayerJs: Pair<String, String>? = null

    override suspend fun resolve(track: Track): YouTubeResolution {
        val matches = search(track)
        val selected = matches.firstOrNull() ?: error("YouTube nie zwrócił kandydatów")
        val stream = resolveStream(selected.candidate.videoId)
        logger.info("AndroidYouTube", "stream_resolved", "Wybrano strumień audio YouTube", fields = mapOf(
            "codec" to stream.codec,
            "mimeType" to stream.mimeType,
            "bitrate" to stream.bitrate.toString(),
            "score" to selected.score.toString(),
        ))
        return YouTubeResolution(selected, matches.drop(1), stream)
    }

    override suspend fun validate(stream: AudioStreamSource) {
        val url = stream.url.use { it }
        request(url, range = true)
    }

    private suspend fun search(track: Track): List<YouTubeMatch> {
        val base = listOf(track.artists.firstOrNull().orEmpty(), track.title).filter(String::isNotBlank).joinToString(" ")
        val candidates = listOf("$base official audio", "$base lyrics").flatMap { query ->
            val html = request("https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}&hl=en&gl=US")
            extractObjects(html, "\"videoRenderer\":").mapNotNull(::candidate)
        }
        return candidates.distinctBy(YouTubeCandidate::videoId).take(30).map { rank(track, it) }.sortedByDescending(YouTubeMatch::score)
    }

    private fun candidate(raw: String): YouTubeCandidate? = runCatching {
        val item = json.parseToJsonElement(raw).jsonObject
        val duration = text(item["lengthText"] as? JsonObject)?.let(::duration) ?: return null
        val title = text(item["title"] as? JsonObject) ?: return null
        val channel = text(item["ownerText"] as? JsonObject).orEmpty()
        YouTubeCandidate(item["videoId"]?.jsonPrimitive?.contentOrNull ?: return null, title, channel, duration,
            channel.lowercase().endsWith(" - topic") || "official audio" in title.lowercase())
    }.getOrNull()

    private fun rank(track: Track, candidate: YouTubeCandidate): YouTubeMatch {
        val expected = normalize(track.title); val actual = normalize(candidate.title)
        val artist = normalize(track.artists.firstOrNull().orEmpty()); val haystack = normalize(candidate.title + " " + candidate.channel)
        var score = 0; val reasons = mutableListOf<String>()
        if (actual.contains(expected) || expected.contains(actual)) { score += 45; reasons += "title" }
        if (artist.isNotBlank() && artist in haystack) { score += 30; reasons += "artist" }
        candidate.durationMs?.let { val diff = kotlin.math.abs(it - track.durationMs); if (diff <= 3_000) score += 25 else if (diff <= 10_000) score += 12 else if (diff >= 45_000) score -= 25 }
        if (candidate.isOfficial) score += 15
        val lyric = Regex("\\blyrics?\\b").containsMatchIn(actual)
        val officialLyric = lyric && ("official lyric" in actual || "official lyrics" in actual)
        if (officialLyric) { score += 12; reasons += "official_lyrics" }
        else if (lyric) { score += 5; reasons += "lyrics" }
        listOf(" live" to 35, "cover" to 35, "remix" to 25, "karaoke" to 40, "sped up" to 35, "slowed" to 30).forEach { (word, penalty) -> if (word in " $haystack") score -= penalty }
        return YouTubeMatch(candidate, score, reasons)
    }

    private suspend fun resolveStream(videoId: String): AudioStreamSource {
        val watch = request("https://www.youtube.com/watch?v=$videoId&hl=en&gl=US")
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(watch)?.groupValues?.get(1) ?: error("Brak klucza YouTube")
        val webVersion = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(watch)?.groupValues?.get(1) ?: error("Brak wersji YouTube")
        val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(watch)?.groupValues?.get(1)
        // VISIONOS (klient YouTube na Apple Vision Pro) — jedyny profil, którego YouTube jeszcze
        // nie objął wymuszeniem SABR, według NewPipeExtractor (który realnie działa dziś w Spotube
        // na tym urządzeniu — sprawdzone bezpośrednio w ich źródle: WEB/ANDROID/ANDROID_VR/IOS/TV
        // już są enforced, VISIONOS to ich obecny "kolejny przystanek" w tej samej gonitwie).
        // To NIE jest trwałe rozwiązanie — samo NewPipe śledzi to centralnie w issue #12248 i co
        // kilka tygodni przeskakuje na nowego klienta, gdy ten aktualny też zostanie zablokowany.
        resolveViaVisionOs(videoId, visitor, watch)?.let { return it }
        // ANDROID_VR dawał bezpośrednie (nieszyfrowane) audio bez transformacji podpisu — ale od
        // 17.08.2026 YouTube zaczął go aktywnie blokować (wymóg PO Tokena dla formatów audio-only;
        // patrz yt-dlp #17348/#16150, usunięty z domyślnych klientów w yt-dlp 2026.08.19). Zastąpione
        // zwykłym klientem ANDROID — dokładne pola (clientVersion, androidSdkVersion, osName/osVersion,
        // User-Agent) zgodne 1:1 z yt-dlp's INNERTUBE_CLIENTS['android'] (sprawdzone bezpośrednio
        // w źródłach yt-dlp), bo błędna/przestarzała wersja klienta kończy się HTTP 400 z serwera
        // YouTube — sam zestaw pól nie wystarczy, muszą się zgadzać też konkretne wartości.
        // ANDROID i ANDROID_VR przestały dawać cokolwiek poza formatami SABR (bez "url" i bez
        // "signatureCipher" — YouTube wymaga teraz osobnego binarnego protokołu UMP, którego nie
        // implementujemy). IOS i TVHTML5 dodane jako kolejny eksperyment — być może omijają SABR,
        // ale nawet yt-dlp nie daje na to gwarancji (obie mają w swojej polityce PO Tokena wymóg
        // dla formatów HTTPS/DASH), więc traktujemy to jako "spróbuj, może się uda", nie jako
        // potwierdzone rozwiązanie. Pola dla obu 1:1 z yt-dlp INNERTUBE_CLIENTS (sprawdzone wprost
        // w źródłach yt-dlp) — błędna wersja/pole = HTTP 400, jak już się przekonaliśmy.
        val profiles = listOf(
            Profile("WEB", webVersion, "1", USER_AGENT),
            Profile("ANDROID", "21.26.364", "3", ANDROID_AGENT),
            Profile("IOS", "21.26.4", "5", IOS_AGENT),
            Profile("TVHTML5", "7.20260707.07.00", "7", TV_AGENT),
        )
        var last = "UNKNOWN"
        for (profile in profiles) {
            val client = mutableMapOf<String, JsonElement>("clientName" to JsonPrimitive(profile.name), "clientVersion" to JsonPrimitive(profile.version), "hl" to JsonPrimitive("en"), "gl" to JsonPrimitive("US"))
            visitor?.let { client["visitorData"] = JsonPrimitive(it) }
            when (profile.name) {
                "ANDROID" -> {
                    client["androidSdkVersion"] = JsonPrimitive(30)
                    client["osName"] = JsonPrimitive("Android")
                    client["osVersion"] = JsonPrimitive("11")
                    client["userAgent"] = JsonPrimitive(profile.agent)
                }
                "IOS" -> {
                    client["deviceMake"] = JsonPrimitive("Apple")
                    client["deviceModel"] = JsonPrimitive("iPhone16,2")
                    client["osName"] = JsonPrimitive("iPhone")
                    client["osVersion"] = JsonPrimitive("18.3.2.22D82")
                }
            }
            val body = JsonObject(mapOf("videoId" to JsonPrimitive(videoId), "contentCheckOk" to JsonPrimitive(true), "racyCheckOk" to JsonPrimitive(true),
                "context" to JsonObject(mapOf("client" to JsonObject(client))))).toString()
            val root = json.parseToJsonElement(request("https://www.youtube.com/youtubei/v1/player?key=$key", body, profile.agent,
                mapOf("X-YouTube-Client-Name" to profile.id, "X-YouTube-Client-Version" to profile.version) + (visitor?.let { mapOf("X-Goog-Visitor-Id" to it) } ?: emptyMap()))).jsonObject
            last = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            if (last != "OK") continue
            val formats = root["streamingData"]?.jsonObject?.get("adaptiveFormats") as? JsonArray ?: continue
            val audioItems = formats.map(JsonElement::jsonObject).filter { it["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("audio/") == true }
            val resolved = runCatching { resolveFormats(audioItems, watch) }.getOrElse {
                logger.warn("AndroidYouTube", "cipher_resolve_failed", "Nie udało się rozwiązać sygnatur/n dla profilu", fields = mapOf("profile" to profile.name, "reason" to (it.message ?: "unknown")))
                emptyList()
            }
            selectFormat(resolved)?.let { return it }
        }
        error("YouTube playability: $last")
    }

    /**
     * Profil VISIONOS ma zupełnie inny kształt żądania niż resztka (endpoint youtubei.googleapis.com
     * bez klucza API, losowy token "t" w query, minimalne nagłówki) — sprawdzone wprost w źródłach
     * NewPipeExtractor. Zwraca null (nie rzuca) na każdą awarię, żeby zwykła pętla profili WEB/
     * ANDROID/IOS/TVHTML5 dalej działała jako fallback, gdyby ten eksperyment nie zadziałał.
     */
    private suspend fun resolveViaVisionOs(videoId: String, visitor: String?, watchHtml: String): AudioStreamSource? = runCatching {
        val client = mutableMapOf<String, JsonElement>(
            "clientName" to JsonPrimitive("VISIONOS"),
            "clientVersion" to JsonPrimitive("1.04"),
            "platform" to JsonPrimitive("MOBILE"),
            "deviceMake" to JsonPrimitive("Apple"),
            "deviceModel" to JsonPrimitive("RealityDevice17,1"),
            "osName" to JsonPrimitive("visionOS"),
            "osVersion" to JsonPrimitive("26.6.0.23O770"),
            "clientScreen" to JsonPrimitive("WATCH"),
            "hl" to JsonPrimitive("en"),
            "gl" to JsonPrimitive("US"),
        )
        visitor?.let { client["visitorData"] = JsonPrimitive(it) }
        val body = JsonObject(mapOf(
            "videoId" to JsonPrimitive(videoId),
            "contentCheckOk" to JsonPrimitive(true),
            "racyCheckOk" to JsonPrimitive(true),
            "context" to JsonObject(mapOf("client" to JsonObject(client))),
        )).toString()
        val token = (1..12).map { VISIONOS_TOKEN_CHARS.random() }.joinToString("")
        val url = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&t=$token&id=$videoId"
        val root = json.parseToJsonElement(request(url, body, VISIONOS_AGENT, mapOf("X-Goog-Api-Format-Version" to "2"))).jsonObject
        val status = root["playabilityStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.contentOrNull
        if (status != "OK") { logger.warn("AndroidYouTube", "visionos_not_ok", "VISIONOS playability nie OK", fields = mapOf("status" to (status ?: "brak"))); return@runCatching null }
        val formats = root["streamingData"]?.jsonObject?.get("adaptiveFormats") as? JsonArray ?: return@runCatching null
        val audioItems = formats.map(JsonElement::jsonObject).filter { it["mimeType"]?.jsonPrimitive?.contentOrNull?.startsWith("audio/") == true }
        selectFormat(resolveFormats(audioItems, watchHtml))
    }.onFailure { logger.warn("AndroidYouTube", "visionos_failed", "Profil VISIONOS nie zadziałał", fields = mapOf("reason" to (it.message ?: "unknown"))) }.getOrNull()

    /**
     * Dla formatów z gotowym "url" (klient ANDROID) tylko rozwiązuje ewentualny throttling "n".
     * Dla formatów z "signatureCipher" (klient WEB — jedyny sposób na audio, gdy ANDROID akurat
     * nie ma dobrego formatu) doklejamy do tego jeszcze deszyfrowanie podpisu "s". Oba wyzwania
     * idą w JEDNYM wywołaniu solvera (YtEjsSolver, uruchamiający oficjalny JS z yt-dlp/ejs w
     * WebView), żeby nie inicjalizować silnika JS ani nie parsować base.js osobno na format.
     */
    private suspend fun resolveFormats(items: List<JsonObject>, watchHtml: String): List<AudioStreamSource> {
        data class Pending(val item: JsonObject, val url: String, val nChallenge: String?, val sigChallenge: String?, val sigParamName: String)
        val pending = items.mapNotNull { item ->
            val direct = item["url"]?.jsonPrimitive?.contentOrNull
            if (direct != null) {
                Pending(item, direct, Regex("[?&]n=([^&]+)").find(direct)?.groupValues?.get(1), null, "")
            } else {
                val cipher = item["signatureCipher"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val fields = parseQuery(cipher)
                val innerUrl = fields["url"] ?: return@mapNotNull null
                val sig = fields["s"] ?: return@mapNotNull null
                Pending(item, innerUrl, Regex("[?&]n=([^&]+)").find(innerUrl)?.groupValues?.get(1), sig, fields["sp"] ?: "signature")
            }
        }
        if (pending.isEmpty()) {
            logger.warn("AndroidYouTube", "no_pending_formats", "Żaden format audio nie miał url ani signatureCipher", fields = mapOf(
                "items" to items.size.toString(),
                "firstItemKeys" to (items.firstOrNull()?.keys?.joinToString(",") ?: "brak"),
                "firstItem" to (items.firstOrNull()?.toString()?.take(800) ?: "brak"),
            ))
            return emptyList()
        }
        val nChallenges = pending.mapNotNull { it.nChallenge }.distinct()
        val sigChallenges = pending.mapNotNull { it.sigChallenge }.distinct()
        val solutions = if (nChallenges.isEmpty() && sigChallenges.isEmpty()) emptyMap() else {
            val playerJs = fetchPlayerJs(watchHtml)
            if (playerJs == null) {
                logger.warn("AndroidYouTube", "player_js_missing", "Nie znaleziono base.js — pomijam deszyfrowanie", fields = emptyMap())
                return pending.filter { it.nChallenge == null && it.sigChallenge == null }.map { format(it.item, it.url) }
            }
            ejsSolver.solve(playerJs, nChallenges, sigChallenges)
        }
        logger.info("AndroidYouTube", "ejs_solved", "Wynik solvera EJS", fields = mapOf(
            "pending" to pending.size.toString(),
            "nChallenges" to nChallenges.size.toString(),
            "sigChallenges" to sigChallenges.size.toString(),
            "solutions" to solutions.size.toString(),
        ))
        return pending.mapNotNull { p ->
            var url = p.url
            p.nChallenge?.let { n -> url = replaceQueryParam(url, "n", solutions[n] ?: return@mapNotNull null) }
            p.sigChallenge?.let { sig -> url = "$url&${p.sigParamName}=${URLEncoder.encode(solutions[sig] ?: return@mapNotNull null, "UTF-8")}" }
            format(p.item, url)
        }
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&').mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) null else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
    }.toMap()

    private fun replaceQueryParam(url: String, name: String, value: String): String {
        val encoded = URLEncoder.encode(value, "UTF-8")
        return if (Regex("[?&]$name=[^&]*").containsMatchIn(url)) Regex("([?&]$name=)[^&]*").replace(url) { it.groupValues[1] + encoded }
        else "$url&$name=$encoded"
    }

    private suspend fun fetchPlayerJs(watchHtml: String): String? {
        val jsPath = Regex("\"jsUrl\":\"([^\"]+)\"").find(watchHtml)?.groupValues?.get(1) ?: return null
        val jsUrl = if (jsPath.startsWith("http")) jsPath else "https://www.youtube.com$jsPath"
        cachedPlayerJs?.let { (url, content) -> if (url == jsUrl) return content }
        return runCatching { request(jsUrl) }.onSuccess { cachedPlayerJs = jsUrl to it }.getOrNull()
    }

    private fun selectFormat(formats: List<AudioStreamSource>): AudioStreamSource? {
        val settings = settingsStore.settings.value
        val manager = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        return AudioFormatSelector.select(
            formats = formats,
            quality = settings.quality,
            codec = settings.codec,
            unmeteredNetwork = manager?.let(::unmeteredNetwork),
            systemDataSaver = manager?.let(::systemDataSaver) ?: false,
        )
    }

    /** Tylko dla trybu AUTO: na połączeniu limitowanym nie ciągniemy najwyższego bitrate. */
    private fun unmeteredNetwork(manager: ConnectivityManager): Boolean? = runCatching {
        val active = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(active) ?: return null
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrNull()

    /**
     * Systemowe „oszczędzanie danych". WHITELISTED oznacza, że użytkownik zwolnił Nutę
     * z ograniczeń, więc nie schodzimy wtedy do najniższej jakości.
     */
    private fun systemDataSaver(manager: ConnectivityManager): Boolean = runCatching {
        manager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }.getOrDefault(false)

    private fun format(item: JsonObject, url: String): AudioStreamSource {
        val mime = item["mimeType"]!!.jsonPrimitive.content
        return AudioStreamSource(SecretValue.of(url), mime, mime.substringAfter("audio/").substringBefore(';'),
            Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1).orEmpty(), item["bitrate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            item["contentLength"]?.jsonPrimitive?.content?.toLongOrNull(), Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1_000))
    }

    private suspend fun request(url: String, body: String? = null, agent: String = USER_AGENT, headers: Map<String, String> = emptyMap(), range: Boolean = false): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000; connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", agent); headers.forEach(connection::setRequestProperty)
            if (range) connection.setRequestProperty("Range", "bytes=0-4095")
            if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Origin", "https://www.youtube.com"); connection.outputStream.use { it.write(body.toByteArray()) } }
            val status = connection.responseCode; val response = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(status in 200..299) { "YouTube HTTP $status" }; response
        } finally { connection.disconnect() }
    }

    private fun text(value: JsonObject?): String? = value?.get("simpleText")?.jsonPrimitive?.contentOrNull ?: (value?.get("runs") as? JsonArray)?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    private fun duration(value: String): Long? = value.split(':').mapNotNull(String::toLongOrNull).takeIf(List<Long>::isNotEmpty)?.fold(0L) { total, part -> total * 60 + part }?.times(1_000)
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    private fun extractObjects(source: String, marker: String): List<String> { val out=mutableListOf<String>(); var from=0; while(out.size<40){val m=source.indexOf(marker,from);if(m<0)break;val start=source.indexOf('{',m+marker.length);if(start<0)break;var depth=0;var quoted=false;var escaped=false;var end=-1;for(i in start until source.length){val c=source[i];if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='\"')quoted=false}else if(c=='\"')quoted=true else if(c=='{')depth++ else if(c=='}'&&--depth==0){end=i+1;break}};if(end<0)break;out+=source.substring(start,end);from=end};return out }
    private data class Profile(val name: String, val version: String, val id: String, val agent: String)
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/128.0 Safari/537.36"
        /** Musi być zgodny z User-Agent, którym PlaybackService realnie ściąga bajty audio (Media3
            DefaultHttpDataSource) — Google CDN odrzuca (HTTP 403) URL wynegocjowany dla klienta
            ANDROID, jeśli faktyczne żądanie o dane przyjdzie z UA wyglądającym jak przeglądarka.
            WEB w praktyce nigdy nie daje bezpośredniego (nieszyfrowanego) audio, więc to ten UA,
            nie przeglądarkowy USER_AGENT, jest używany przy każdym realnym odtwarzaniu. */
        const val ANDROID_AGENT = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
        const val IOS_AGENT = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
        const val TV_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
        const val VISIONOS_AGENT = "com.google.visionos.youtube/1.04(RealityDevice17,1; U; CPU visionOS 26_6_0 like Mac OS X; US)"
        private const val VISIONOS_TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
