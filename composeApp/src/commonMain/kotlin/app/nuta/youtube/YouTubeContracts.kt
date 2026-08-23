package app.nuta.youtube

import app.nuta.core.models.Track
import app.nuta.core.security.SecretValue

data class YouTubeCandidate(
    val videoId: String,
    val title: String,
    val channel: String,
    val durationMs: Long?,
    val isOfficial: Boolean,
)

data class YouTubeMatch(
    val candidate: YouTubeCandidate,
    val score: Int,
    val reasons: List<String>,
)

data class AudioStreamSource(
    val url: SecretValue,
    val mimeType: String,
    val container: String,
    val codec: String,
    val bitrate: Int,
    val contentLength: Long?,
    val expiresAtMs: Long?,
)

data class YouTubeResolution(
    val match: YouTubeMatch,
    val alternatives: List<YouTubeMatch>,
    val stream: AudioStreamSource,
)

interface YouTubeMediaService {
    suspend fun resolve(track: Track): YouTubeResolution
    suspend fun validate(stream: AudioStreamSource)
}
