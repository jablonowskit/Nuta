package app.nuta.ui

import app.nuta.core.models.Track
import app.nuta.settings.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class ShareTextTest {
    private fun track(id: String) = Track(id, "Enter Sandman", listOf("Metallica"), "", 0)

    @Test
    fun mbidGetsMusicBrainzLink() {
        assertEquals(
            "Enter Sandman — Metallica\nhttps://musicbrainz.org/recording/530a80ac-a0cb-4248-b455-aeb7c03def67",
            trackShareText(track("530a80ac-a0cb-4248-b455-aeb7c03def67"), DataSource.LISTENBRAINZ),
        )
    }

    @Test
    fun spotifyIdGetsSpotifyLinkOnlyInSpotifyMode() {
        assertEquals(
            "Enter Sandman — Metallica\nhttps://open.spotify.com/track/5sICkBXVmaCQk5aISGR3x1",
            trackShareText(track("5sICkBXVmaCQk5aISGR3x1"), DataSource.SPOTIFY),
        )
        assertEquals("Enter Sandman — Metallica", trackShareText(track("5sICkBXVmaCQk5aISGR3x1"), DataSource.LISTENBRAINZ))
    }

    @Test
    fun msidSharesTextOnly() {
        assertEquals("Enter Sandman — Metallica", trackShareText(track("msid:4d2f7c87-46e2-40df-8dc4-c4b5d803e8e5"), DataSource.LISTENBRAINZ))
    }
}
