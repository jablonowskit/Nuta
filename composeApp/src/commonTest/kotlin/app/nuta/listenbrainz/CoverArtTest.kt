package app.nuta.listenbrainz

import app.nuta.musicbrainz.MusicBrainzRepository
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoverArtTest {
    @Test
    fun jspfTrackGetsCoverFromCaaMetadata() {
        val track = ListenBrainzRepository.trackFromJspf(Json.parseToJsonElement(
            """{"identifier":["https://musicbrainz.org/recording/r1"],"title":"T","creator":"A",
               "extension":{"https://musicbrainz.org/doc/jspf#track":{"additional_metadata":{"caa_id":42,"caa_release_mbid":"rel"}}}}""",
        ))!!
        assertEquals("https://coverartarchive.org/release/rel/42-250.jpg", track.imageUrl)
    }

    @Test
    fun missingCaaDataGivesNoCover() {
        assertNull(ListenBrainzRepository.coverArtUrl(null, "42"))
        assertNull(ListenBrainzRepository.coverArtUrl("rel", null))
    }

    @Test
    fun metadataLookupCarriesAlbumAndCover() {
        val tracks = ListenBrainzRepository.parseMetadataLookup(
            """{"r1":{"recording":{"name":"T","length":1000},"artist":{"artists":[]},
               "release":{"name":"Album","caa_id":7,"caa_release_mbid":"rel"}}}""",
            listOf("r1"),
        )
        assertEquals("Album", tracks.single().album)
        assertEquals("https://coverartarchive.org/release/rel/7-250.jpg", tracks.single().imageUrl)
    }

    @Test
    fun musicBrainzRecordingGetsReleaseGroupCover() {
        val tracks = MusicBrainzRepository.parseRecordings(
            """{"recordings":[{"id":"r1","title":"T","artist-credit":[],
               "releases":[{"title":"Album","release-group":{"id":"rg"}}]}]}""",
        )
        assertEquals("Album", tracks.single().album)
        assertEquals("https://coverartarchive.org/release-group/rg/front-250", tracks.single().imageUrl)
    }
}
