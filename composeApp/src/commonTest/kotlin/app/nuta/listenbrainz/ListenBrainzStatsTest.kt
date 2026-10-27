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

    @Test
    fun activityIsSortedAndLeadingZerosAreDropped() {
        // Kształt jak z curla (listening-activity, 27.10.2026), all_time zaczyna się od pustych lat.
        val body = """{"payload":{"from_ts":1009843200,"listening_activity":[
            {"from_ts":1767225600,"listen_count":503,"time_range":"2026","to_ts":1798761599},
            {"from_ts":1009843200,"listen_count":0,"time_range":"2002","to_ts":1041379199},
            {"from_ts":1735689600,"listen_count":0,"time_range":"2025","to_ts":1767225599}
        ],"range":"all_time"}}"""
        assertEquals(listOf(ActivityBucket("2026", 1767225600, 503)), ListenBrainzStats.parseActivity(body))
    }

    @Test
    fun activityKeepsZeroBucketsInsideTheRange() {
        val body = """{"payload":{"listening_activity":[
            {"from_ts":1,"listen_count":4,"time_range":"Mon"},
            {"from_ts":2,"listen_count":0,"time_range":"Tue"},
            {"from_ts":3,"listen_count":7,"time_range":"Wed"}
        ]}}"""
        assertEquals(listOf(4L, 0L, 7L), ListenBrainzStats.parseActivity(body).map { it.count })
        assertTrue(ListenBrainzStats.parseActivity("").isEmpty())
    }
}
