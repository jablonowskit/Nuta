package app.nuta.data

import app.nuta.core.models.Track

/** Trwały cache listy ulubionych — przeżywa process death (Android prefs / desktop plik). */
interface LikedTracksCache {
    fun load(): List<Track>
    fun save(tracks: List<Track>)
    fun clear()
}

object EmptyLikedTracksCache : LikedTracksCache {
    override fun load(): List<Track> = emptyList()
    override fun save(tracks: List<Track>) = Unit
    override fun clear() = Unit
}
