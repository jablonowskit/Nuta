package app.nuta.android

import android.content.SharedPreferences
import app.nuta.settings.BufferSize
import app.nuta.settings.CodecPreference
import app.nuta.settings.PlaybackSettingsStore
import app.nuta.settings.StreamQuality
import app.nuta.settings.YouTubePlaybackSettings
import app.nuta.settings.LoudnessNormalization
import app.nuta.settings.YouTubeClientProfile
import app.nuta.settings.AudioSource
import app.nuta.settings.DataSource
import app.nuta.settings.AppTheme
import app.nuta.settings.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidPlaybackSettingsStore(
    private val preferences: SharedPreferences,
    private val credentials: CredentialStore,
) : PlaybackSettingsStore {
    private val state = MutableStateFlow(read())
    override val settings: StateFlow<YouTubePlaybackSettings> = state.asStateFlow()

    override fun update(value: YouTubePlaybackSettings) {
        val validated = value.copy(
            fontScale = value.fontScale.coerceIn(0.5f, 1f),
            cacheSizeMb = value.cacheSizeMb.coerceIn(25, 500),
        )
        preferences.edit()
            .putFloat("fontScale", validated.fontScale)
            .putString("youtubeQuality", validated.quality.name)
            .putString("youtubeCodec", validated.codec.name)
            .putString("youtubeBuffer", validated.bufferSize.name)
            .putString("loudnessNormalization", validated.loudnessNormalization.name)
            .putString("youtubeClientProfile", validated.youtubeClientProfile.name)
            .putString("audioSource", validated.audioSource.name)
            .putString("dataSource", validated.dataSource.name)
            .putString("listenBrainzUsername", validated.listenBrainzUsername)
            .putBoolean("prefetchEnabled", validated.prefetchEnabled)
            .putBoolean("playerCollapsed", validated.playerCollapsed)
            .putInt("cacheSizeMb", validated.cacheSizeMb)
            .putBoolean("cassetteBackground", validated.cassetteBackground)
            .putString("theme", validated.theme.name)
            .remove("listenBrainzApiToken")
            .apply()
        if (validated.listenBrainzApiToken.isBlank()) credentials.clear(ListenBrainzTokenKey)
        else credentials.save(ListenBrainzTokenKey, validated.listenBrainzApiToken)
        state.value = validated
    }

    private fun read() = YouTubePlaybackSettings(
        fontScale = preferences.getFloat("fontScale", 1f).coerceIn(0.5f, 1f),
        quality = enumValue(preferences.getString("youtubeQuality", null), StreamQuality.BEST),
        codec = enumValue(preferences.getString("youtubeCodec", null), CodecPreference.AAC),
        bufferSize = enumValue(preferences.getString("youtubeBuffer", null), BufferSize.STANDARD),
        loudnessNormalization = enumValue(preferences.getString("loudnessNormalization", null), LoudnessNormalization.OFF),
        youtubeClientProfile = enumValue(preferences.getString("youtubeClientProfile", null), YouTubeClientProfile.AUTO),
        audioSource = enumValue(preferences.getString("audioSource", null), AudioSource.YOUTUBE),
        dataSource = enumValue(preferences.getString("dataSource", null), DataSource.LISTENBRAINZ),
        listenBrainzUsername = preferences.getString("listenBrainzUsername", "") ?: "",
        listenBrainzApiToken = readTokenAndMigrate(),
        prefetchEnabled = preferences.getBoolean("prefetchEnabled", false),
        playerCollapsed = preferences.getBoolean("playerCollapsed", false),
        cacheSizeMb = preferences.getInt("cacheSizeMb", 150).coerceIn(25, 500),
        cassetteBackground = preferences.getBoolean("cassetteBackground", true),
        theme = enumValue(preferences.getString("theme", null), AppTheme.FOREST),
    )

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private fun readTokenAndMigrate(): String {
        credentials.load(ListenBrainzTokenKey)?.let { return it }
        val legacy = preferences.getString("listenBrainzApiToken", "").orEmpty()
        if (legacy.isNotBlank()) {
            credentials.save(ListenBrainzTokenKey, legacy)
            preferences.edit().remove("listenBrainzApiToken").apply()
        }
        return legacy
    }

    private companion object {
        const val ListenBrainzTokenKey = "listenbrainz.api-token"
    }
}
