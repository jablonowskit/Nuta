package app.nuta.youtube

import app.nuta.core.models.Track

/**
 * Ocenia, jak dobrze kandydat (z YouTube, SoundCloud, czy jakiegokolwiek innego źródła
 * przeszukiwanego po tytule/artyście) odpowiada oczekiwanemu utworowi. Wyodrębnione z
 * (wcześniej zduplikowanej) logiki `rank()` w `AndroidYouTubeMediaService`/
 * `NutaYouTubeMediaService`, żeby nowe resolwery (np. SoundCloud) nie musiały pisać tego
 * samego scoringu po raz trzeci i czwarty.
 */
fun rankCandidate(track: Track, title: String, channelOrUser: String, durationMs: Long?, isOfficial: Boolean): Pair<Int, List<String>> {
    val expectedTitle = normalizeForRanking(track.title)
    val actualTitle = normalizeForRanking(title)
    val artist = normalizeForRanking(track.artists.firstOrNull().orEmpty())
    val haystack = normalizeForRanking("$title $channelOrUser")
    var score = 0
    val reasons = mutableListOf<String>()

    // Pokrycie słów oczekiwanego tytułu, a nie zawieranie się napisów w dowolną stronę.
    // Dawne `expectedTitle.contains(actualTitle)` dawało pełne punkty krótszemu tytułowi:
    // "Hole In My Life" wygrywało z utworem "Hole in My Life / Hit the Road Jack" (zgłoszone
    // 25.09.2026 — grał inny utwór). Częściowe pokrycie zostawia miejsce na drobne różnice
    // zapisu, np. "(Peter Rauhofer Drowned World dub Part 1)" vs "(...Drowned World Dub)".
    val expectedWords = expectedTitle.split(' ').filter(String::isNotBlank).toSet()
    val actualWords = actualTitle.split(' ').filter(String::isNotBlank).toSet()
    if (expectedWords.isNotEmpty()) {
        val coverage = expectedWords.count { it in actualWords }.toDouble() / expectedWords.size
        when {
            coverage >= 1.0 -> { score += 45; reasons += "title" }
            coverage >= PartialTitleCoverage -> { score += 30; reasons += "title_partial" }
        }
    }
    if (artist.isNotBlank() && haystack.contains(artist)) { score += 30; reasons += "artist" }
    durationMs?.let {
        val difference = kotlin.math.abs(it - track.durationMs)
        when {
            difference <= 3_000 -> { score += 25; reasons += "duration_exact" }
            difference <= 10_000 -> { score += 12; reasons += "duration_close" }
            difference >= 45_000 -> { score -= 25; reasons += "duration_bad" }
            // Wcześniej 10–45 s różnicy nie kosztowało nic — a to zwykle inna wersja utworu.
            else -> { score -= 10; reasons += "duration_off" }
        }
    }
    if (isOfficial) { score += 15; reasons += "official" }
    val lyric = Regex("\\blyrics?\\b").containsMatchIn(actualTitle)
    val officialLyric = lyric && ("official lyric" in actualTitle || "official lyrics" in actualTitle)
    if (officialLyric) { score += 12; reasons += "official_lyrics" }
    else if (lyric) { score += 5; reasons += "lyrics" }

    // Kary liczone z tytułu kandydata (nie z nazwy kanału — "Madonna Remixes" to zwykły kanał)
    // i pomijane, gdy oczekiwany utwór sam jest taką wersją: remiks/dub/live w kolejce nie może
    // tracić punktów za to, że jest dokładnie tym, czego szukamy.
    val expectedIsRemix = expectedWords.any { it in RemixMarkers }
    Penalties.forEach { (phrase, penalty) ->
        val inCandidate = " $phrase " in " $actualTitle "
        val expectedToo = " $phrase " in " $expectedTitle " || (phrase == "remix" && expectedIsRemix)
        if (inCandidate && !expectedToo) { score -= penalty; reasons += "penalty_${phrase.replace(' ', '_')}" }
    }
    return score to reasons
}

/**
 * Najlepszy kandydat albo wyjątek, jeśli nawet on jest zbyt słabo dopasowany. Wcześniej grał
 * zawsze pierwszy kandydat, choćby z wynikiem bliskim zera — wtedy zamiast utworu leciało coś
 * innego, a użytkownik mógł to zauważyć tylko uchem. Lepiej jawny błąd (w trybie AUTO
 * SourceSelectingMediaService spróbuje wtedy SoundCloud) niż cicho zły utwór.
 */
fun selectBestMatch(matches: List<YouTubeMatch>, source: String): YouTubeMatch {
    val best = matches.firstOrNull() ?: error("$source nie zwrócił kandydatów")
    check(best.score >= MinAcceptableMatchScore) {
        "$source: brak pewnego dopasowania (najlepszy: \"${best.candidate.title}\", wynik ${best.score})"
    }
    return best
}

/** Np. tytuł + wykonawca bez zgodnego czasu, albo częściowy tytuł + wykonawca + zgodny czas. */
const val MinAcceptableMatchScore = 40
private const val PartialTitleCoverage = 0.75
private val RemixMarkers = setOf("remix", "mix", "dub", "edit", "rework", "bootleg", "version")
private val Penalties = mapOf("live" to 35, "cover" to 35, "remix" to 25, "karaoke" to 40, "sped up" to 35, "slowed" to 30, "nightcore" to 40)

/** `\p{L}` zamiast `a-z`: polskie litery nie są już wycinane ze środka słów ("światła" -> "wiat a"). */
fun normalizeForRanking(value: String): String = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
