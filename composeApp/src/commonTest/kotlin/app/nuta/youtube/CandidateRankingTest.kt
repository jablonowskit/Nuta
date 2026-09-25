package app.nuta.youtube

import app.nuta.core.models.Track
import app.nuta.core.models.withResolvedDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Kandydaci to prawdziwe wyniki wyszukiwania YouTube z 25.09.2026 dla kolejki, w której
 * zamiast "Hole in My Life / Hit the Road Jack" grało samo "Hole In My Life".
 */
class CandidateRankingTest {
    private fun track(artist: String, title: String, durationMs: Long) =
        Track(id = "t", title = title, artists = listOf(artist), album = "", durationMs = durationMs)

    private fun best(track: Track, vararg candidates: YouTubeCandidate): YouTubeCandidate =
        candidates.map { c ->
            val (score, reasons) = rankCandidate(track, c.title, c.channel, c.durationMs, c.isOfficial)
            YouTubeMatch(c, score, reasons)
        }.maxBy(YouTubeMatch::score).candidate

    private fun score(track: Track, c: YouTubeCandidate) =
        rankCandidate(track, c.title, c.channel, c.durationMs, c.isOfficial).first

    private val police = track("The Police", "Hole in My Life / Hit the Road Jack", 271_000)
    private val policeStudio = YouTubeCandidate("a", "Hole In My Life", "The Police", 291_000, false)
    private val policeMedley = YouTubeCandidate("b", "THE POLICE  Hole In My Life / Hit The Road Jack, Oakland 1983", "ferrari934", 289_000, false)
    private val hitTheRoadJack = YouTubeCandidate("c", "Hit the Road Jack", "Release - Topic", 119_000, true)

    @Test
    fun shorterTitleNoLongerCountsAsFullMatch() {
        // Dawniej oba dostawały po 75 pkt i wygrywał pierwszy z wyników — utwór bez drugiej połowy.
        assertEquals(policeMedley, best(police, policeStudio, policeMedley, hitTheRoadJack))
        assertTrue(score(police, policeStudio) < MinAcceptableMatchScore, "sam 'Hole In My Life' nie może przejść progu")
    }

    @Test
    fun remixTrackIsNotPenalizedForBeingARemix() {
        // Utwór w kolejce sam jest remiksem (dub), więc kara "remix" nie może trafiać w jego
        // wersję z kanału "Madonna Remixes" — a nazwa kanału w ogóle nie powinna jej wyzwalać.
        val madonna = track("Madonna", "Impressive Instant (Peter Rauhofer Drowned World dub Part 1)", 509_000)
        val official = YouTubeCandidate("m", "Madonna - Impressive Instant (Peter Rauhofer's Drowned World Dub)", "Madonna Remixes", 507_000, false)
        val score = score(madonna, official)
        assertTrue(score >= MinAcceptableMatchScore, "poprawna wersja dostała tylko $score")
    }

    @Test
    fun remixCandidateIsStillPenalizedForOriginalTrack() {
        val original = track("The Human League", "Together in Electric Dreams", 232_000)
        val clean = YouTubeCandidate("h1", "The Human League - Together In Electric Dreams", "Franklin Ellesmere", 232_000, false)
        val remix = YouTubeCandidate("h2", "The Human League - Together In Electric Dreams (Remix)", "someone", 232_000, false)
        assertTrue(score(original, clean) > score(original, remix))
    }

    @Test
    fun durationOffByTwentySecondsCostsPoints() {
        val t = track("Artist", "Song", 200_000)
        val exact = YouTubeCandidate("x", "Artist - Song", "Artist", 200_000, false)
        val off = YouTubeCandidate("y", "Artist - Song", "Artist", 220_000, false)
        assertTrue(score(t, off) < score(t, exact) - 25, "20 s różnicy powinno kosztować więcej niż brak bonusu")
    }

    @Test
    fun missingTrackDurationIsNotComparedAtAll() {
        // "St. Elmos Fire" z MusicBrainz przyszło z długością 0 — każdy kandydat dostawał
        // "duration_bad" i wygrało lyric video z kanału fana (25.09.2026).
        val t = track("John Parr", "St. Elmos Fire", 0)
        val (_, reasons) = rankCandidate(t, "John Parr - St. Elmo's Fire", "John Parr", 250_000, false)
        assertTrue(reasons.none { it.startsWith("duration") }, reasons.toString())
    }

    @Test
    fun resolvedDurationFillsOnlyUnknownLength() {
        val unknown = app.nuta.core.models.Track("a", "A", listOf("X"), "", 0)
        val known = app.nuta.core.models.Track("b", "B", listOf("X"), "", 200_000)
        val state = app.nuta.core.models.PlayerState(queue = listOf(unknown, known), currentIndex = 0)
        assertEquals(250_000, state.withResolvedDuration("a", 250_000).durationMs)
        val onKnown = state.copy(currentIndex = 1)
        assertEquals(200_000, onKnown.withResolvedDuration("b", 250_000).durationMs)
    }

    @Test
    fun polishLettersAreKeptInsideWords() {
        assertEquals("światła miasta", normalizeForRanking("Światła  Miasta!"))
    }

    @Test
    fun weakBestMatchIsRejectedInsteadOfPlayingWrongSong() {
        val weak = YouTubeMatch(policeStudio, MinAcceptableMatchScore - 1, emptyList())
        assertFailsWith<IllegalStateException> { selectBestMatch(listOf(weak), "YouTube") }
        assertFailsWith<IllegalStateException> { selectBestMatch(emptyList(), "YouTube") }
        val good = YouTubeMatch(policeMedley, MinAcceptableMatchScore, emptyList())
        assertEquals(good, selectBestMatch(listOf(good), "YouTube"))
    }
}
