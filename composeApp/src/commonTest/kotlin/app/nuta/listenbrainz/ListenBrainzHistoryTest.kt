package app.nuta.listenbrainz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListenBrainzHistoryTest {
    // Kształt z curla (/1/user/<u>/listens, 27.10.2026), przycięty do czytanych pól.
    private val body = """
        {"payload":{"count":2,"listens":[
          {"listened_at":1793096651,"recording_msid":"5ebb699b-371c-4407-a386-8a2436707d37",
           "track_metadata":{"artist_name":"The KLF","track_name":"Last Train to Trancentral","release_name":"The White Room",
             "additional_info":{"duration_ms":261000},
             "mbid_mapping":{"artist_mbids":["8092b8b7-235e-4844-9f72-95a9d5a73dbf"],
               "recording_mbid":"530a80ac-a0cb-4248-b455-aeb7c03def67",
               "release_group_mbid":"be5288fd-e2fc-3c2b-bd4a-c5c19ac2a14f"}}},
          {"listened_at":1793096000,"recording_msid":"4d2f7c87-46e2-40df-8dc4-c4b5d803e8e5",
           "track_metadata":{"artist_name":"ADEK WWA","track_name":"W KLAPECZKACH","release_name":"W KLAPECZKACH"}}
        ]}}
    """.trimIndent()

    @Test
    fun mappedListenBecomesTrackWithMbidAndCover() {
        val entry = ListenBrainzHistory.parse(body, requested = 2).entries[0]
        assertEquals(1793096651, entry.listenedAt)
        assertEquals("530a80ac-a0cb-4248-b455-aeb7c03def67", entry.track.id)
        assertEquals(261_000, entry.track.durationMs)
        assertEquals("https://coverartarchive.org/release-group/be5288fd-e2fc-3c2b-bd4a-c5c19ac2a14f/front-250", entry.track.imageUrl)
        assertEquals("8092b8b7-235e-4844-9f72-95a9d5a73dbf", entry.track.artistMbid)
    }

    @Test
    fun unmappedListenUsesMsidPrefix() {
        val track = ListenBrainzHistory.parse(body, requested = 2).entries[1].track
        assertEquals("msid:4d2f7c87-46e2-40df-8dc4-c4b5d803e8e5", track.id)
        assertEquals(0, track.durationMs)
        assertNull(track.imageUrl)
    }

    @Test
    fun fullPageGivesCursorShortPageEndsHistory() {
        assertEquals(1793096000, ListenBrainzHistory.parse(body, requested = 2).oldestTs)
        assertNull(ListenBrainzHistory.parse(body, requested = 50).oldestTs)
        assertEquals(0, ListenBrainzHistory.parse("").entries.size)
    }
}
