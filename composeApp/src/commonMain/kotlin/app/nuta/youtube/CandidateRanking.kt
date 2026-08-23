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
    if (actualTitle.contains(expectedTitle) || expectedTitle.contains(actualTitle)) { score += 45; reasons += "title" }
    if (artist.isNotBlank() && haystack.contains(artist)) { score += 30; reasons += "artist" }
    durationMs?.let {
        val difference = kotlin.math.abs(it - track.durationMs)
        when {
            difference <= 3_000 -> { score += 25; reasons += "duration_exact" }
            difference <= 10_000 -> { score += 12; reasons += "duration_close" }
            difference >= 45_000 -> { score -= 25; reasons += "duration_bad" }
        }
    }
    if (isOfficial) { score += 15; reasons += "official" }
    val lyric = Regex("\\blyrics?\\b").containsMatchIn(actualTitle)
    val officialLyric = lyric && ("official lyric" in actualTitle || "official lyrics" in actualTitle)
    if (officialLyric) { score += 12; reasons += "official_lyrics" }
    else if (lyric) { score += 5; reasons += "lyrics" }
    val penalties = mapOf(" live" to 35, "cover" to 35, "remix" to 25, "karaoke" to 40, "sped up" to 35, "slowed" to 30, "nightcore" to 40)
    penalties.forEach { (word, penalty) -> if (word in " $haystack") { score -= penalty; reasons += "penalty_${word.trim().replace(' ', '_')}" } }
    return score to reasons
}

fun normalizeForRanking(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
