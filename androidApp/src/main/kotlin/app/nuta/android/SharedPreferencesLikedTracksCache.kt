package app.nuta.android

import android.content.SharedPreferences
import android.util.Base64
import app.nuta.core.models.Track
import app.nuta.data.LikedTracksCache

/** Ten sam format wierszy co cache Spotify w [SpotifyAndroidRepository]. */
class SharedPreferencesLikedTracksCache(
    private val preferences: SharedPreferences,
) : LikedTracksCache {
    override fun load(): List<Track> =
        preferences.getString(Key, "").orEmpty().lineSequence().filter(String::isNotBlank).mapNotNull { row ->
            runCatching {
                val v = row.split("\u001e").map { String(Base64.decode(it, Base64.DEFAULT)) }
                Track(v[0], v[1], v[2].split("\u001f"), v[3], v[4].toLong(), v[5].ifBlank { null }, v.getOrNull(6)?.ifBlank { null })
            }.getOrNull()
        }.toList()

    override fun save(tracks: List<Track>) {
        val value = tracks.joinToString("\n") { t ->
            listOf(
                t.id,
                t.title,
                t.artists.joinToString("\u001f"),
                t.album,
                t.durationMs.toString(),
                t.imageUrl.orEmpty(),
                t.artistMbid.orEmpty(),
            ).joinToString("\u001e") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        }
        preferences.edit().putString(Key, value).apply()
    }

    override fun clear() {
        preferences.edit().remove(Key).apply()
    }

    private companion object {
        const val Key = "likedTracks"
    }
}
