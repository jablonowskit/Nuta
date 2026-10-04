package app.nuta.data

import app.nuta.core.models.Track

/**
 * Trwały cache listy ulubionych — przeżywa process death (Android prefs / desktop plik).
 * Wpisy są per [username]; [load] zwraca pustą listę przy mismatch użytkownika.
 */
interface LikedTracksCache {
    fun load(username: String): List<Track>
    fun save(username: String, tracks: List<Track>)
    fun clear()
}

object EmptyLikedTracksCache : LikedTracksCache {
    override fun load(username: String): List<Track> = emptyList()
    override fun save(username: String, tracks: List<Track>) = Unit
    override fun clear() = Unit
}
