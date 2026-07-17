package app.nuta.data.fake

import app.nuta.core.logging.NutaLogger
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.domain.SpotifyRepository

object DemoLibrary {
    val tracks = listOf(
        Track("t1", "Midnight Signal", listOf("Neon Harbor"), "City Lights", 214_000),
        Track("t2", "Paper Satellites", listOf("Luna Vale"), "Orbit", 188_000),
        Track("t3", "Quiet Machines", listOf("Northbound"), "Static Hearts", 241_000),
        Track("t4", "Afterglow Avenue", listOf("Mira Stone"), "Golden Hour", 203_000),
        Track("t5", "Binary Rain", listOf("Echo Assembly"), "Soft Errors", 276_000),
        Track("t6", "Window Seat", listOf("Sunday Atlas"), "Far From Here", 196_000),
        Track("t7", "Low Battery Love", listOf("Pocket Cinema"), "Offline", 229_000),
        Track("t8", "Northern Lines", listOf("The Long Return"), "Railway Maps", 252_000),
    )

    val playlists = listOf(
        Playlist("p1", "Nocne kodowanie", "Spokojna elektronika do skupienia", tracks.take(5)),
        Playlist("p2", "Podróż", "Utwory na dłuższą trasę", tracks.drop(3)),
        Playlist("p3", "Ostatnio dodane", "Najnowsze utwory w bibliotece", tracks.reversed().take(4)),
    )
}

class FakeSpotifyRepository(private val logger: NutaLogger) : SpotifyRepository {
    override suspend fun getPlaylists(): List<Playlist> {
        logger.debug("FakeSpotifyRepository", "playlists_loaded", "Załadowano demonstracyjne playlisty", fields = mapOf("count" to DemoLibrary.playlists.size.toString()))
        return DemoLibrary.playlists
    }

    override suspend fun getPlaylistTracks(playlistId: String): List<Track> =
        DemoLibrary.playlists.firstOrNull { it.id == playlistId }?.tracks.orEmpty()

    override suspend fun search(query: String): SearchResult {
        val normalized = query.trim().lowercase()
        if (normalized == "error") error("Wymuszony błąd danych demonstracyjnych")
        val tracks = if (normalized.isBlank()) emptyList() else DemoLibrary.tracks.filter {
            it.title.lowercase().contains(normalized) || it.artists.any { artist -> artist.lowercase().contains(normalized) }
        }
        val playlists = if (normalized.isBlank()) emptyList() else DemoLibrary.playlists.filter { it.name.lowercase().contains(normalized) }
        logger.debug("FakeSpotifyRepository", "search_completed", "Zakończono lokalne wyszukiwanie", fields = mapOf("queryLength" to query.length.toString(), "results" to (tracks.size + playlists.size).toString()))
        return SearchResult(tracks, playlists)
    }

    override suspend fun getTrackRadio(seed: Track, limit: Int): List<Track> =
        DemoLibrary.tracks.filterNot { it.id == seed.id }.take(limit).also {
            logger.info("FakeSpotifyRepository", "radio_completed", "Utworzono demonstracyjne radio utworu", fields = mapOf("count" to it.size.toString()))
        }
}
