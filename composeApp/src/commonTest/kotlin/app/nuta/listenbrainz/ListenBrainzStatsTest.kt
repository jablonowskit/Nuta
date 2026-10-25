package app.nuta.listenbrainz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListenBrainzStatsTest {
    @Test
    fun recordingsBecomePlayableTracksWithCover() {
        val body = """{"payload":{"recordings":[{"artist_mbids":["a1"],"artist_name":"BTS","caa_id":7,
            "caa_release_mbid":"rel","listen_count":249861,"recording_mbid":"r1","release_name":"ARIRANG","track_name":"SWIM"}]}}"""
        val entry = ListenBrainzStats.parse(body, StatsKind.RECORDINGS).single()
        assertEquals(249861, entry.listenCount)
        val track = entry.track!!
        assertEquals("r1", track.id)
        assertEquals(listOf("BTS"), track.artists)
        assertEquals("a1", track.artistMbid)
        assertEquals("https://coverartarchive.org/release/rel/7-250.jpg", track.imageUrl)
    }

    @Test
    fun artistsAndReleasesHaveNoTrack() {
        val artists = ListenBrainzStats.parse("""{"payload":{"artists":[{"artist_mbid":"a1","artist_name":"BTS","listen_count":5}]}}""", StatsKind.ARTISTS)
        assertEquals("BTS", artists.single().title)
        assertNull(artists.single().track)
        val releases = ListenBrainzStats.parse("""{"payload":{"releases":[{"artist_name":"BTS","release_name":"ARIRANG","listen_count":3}]}}""", StatsKind.RELEASES)
        assertEquals("ARIRANG", releases.single().title)
        assertEquals("BTS", releases.single().subtitle)
    }

    @Test
    fun noContentMeansEmptyList() {
        // HTTP 204: ListenBrainz jeszcze nie policzył statystyk użytkownika.
        assertTrue(ListenBrainzStats.parse("", StatsKind.ARTISTS).isEmpty())
    }
}
