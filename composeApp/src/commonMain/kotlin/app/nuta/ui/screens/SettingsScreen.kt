package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.AppContainer
import app.nuta.resources.*
import app.nuta.settings.AppTheme
import app.nuta.settings.AudioSource
import app.nuta.settings.BufferSize
import app.nuta.settings.CodecPreference
import app.nuta.settings.DataSource
import app.nuta.settings.LoudnessNormalization
import app.nuta.settings.StreamQuality
import app.nuta.settings.YouTubeClientProfile
import app.nuta.ui.Heading
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.openUrlInBrowser
import app.nuta.ui.theme.LocalNutaPalette
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsScreen(container: AppContainer) {
    val settings by container.playbackSettings.settings.collectAsState()
    ScrollableLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading(stringResource(Res.string.settings_title), stringResource(Res.string.settings_subtitle)) }
        item {
            SettingsGroup(stringResource(Res.string.font_size_title), stringResource(Res.string.font_size_desc)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(settings.fontScale * 100).toInt()}%", modifier = Modifier.width(48.dp))
                    Slider(
                        value = settings.fontScale,
                        onValueChange = { value -> container.playbackSettings.update { it.copy(fontScale = value.coerceIn(0.5f, 1f)) } },
                        valueRange = 0.5f..1f,
                        steps = 4,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.quality_title), stringResource(Res.string.quality_desc)) {
                SettingOptions(
                    options = listOf(
                        StreamQuality.AUTO to stringResource(Res.string.option_auto),
                        StreamQuality.DATA_SAVER to stringResource(Res.string.quality_data_saver),
                        StreamQuality.STANDARD to stringResource(Res.string.option_standard),
                        StreamQuality.BEST to stringResource(Res.string.quality_best),
                    ),
                    selected = settings.quality,
                ) { value -> container.playbackSettings.update { it.copy(quality = value) } }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.codec_title), stringResource(Res.string.codec_desc)) {
                SettingOptions(
                    options = listOf(CodecPreference.AUTO to stringResource(Res.string.option_auto), CodecPreference.AAC to "AAC", CodecPreference.OPUS to "Opus"),
                    selected = settings.codec,
                ) { value -> container.playbackSettings.update { it.copy(codec = value) } }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.buffer_title), stringResource(Res.string.buffer_desc)) {
                SettingOptions(
                    options = listOf(BufferSize.SMALL to stringResource(Res.string.buffer_small), BufferSize.STANDARD to stringResource(Res.string.option_standard), BufferSize.LARGE to stringResource(Res.string.buffer_large)),
                    selected = settings.bufferSize,
                ) { value -> container.playbackSettings.update { it.copy(bufferSize = value) } }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.loudness_title), stringResource(Res.string.loudness_desc)) {
                SettingOptions(
                    options = listOf(
                        LoudnessNormalization.OFF to stringResource(Res.string.loudness_off),
                        LoudnessNormalization.GENTLE to stringResource(Res.string.loudness_gentle),
                        LoudnessNormalization.NORMAL to stringResource(Res.string.loudness_normal),
                    ),
                    selected = settings.loudnessNormalization,
                ) { value -> container.playbackSettings.update { it.copy(loudnessNormalization = value) } }
            }
        }
        item {
            SettingsGroup(
                "Źródło audio",
                "Skąd rozwiązywać strumień audio dla utworów. Automatycznie próbuje YouTube, a przy błędzie samo przełącza się na SoundCloud dla tego utworu. SoundCloud ma mniejszą bibliotekę i niższą jakość (128kbps mp3), ale nie podlega tym samym ograniczeniom co YouTube.",
            ) {
                SettingOptions(
                    options = listOf(
                        AudioSource.AUTO to "AUTO",
                        AudioSource.YOUTUBE to "YouTube",
                        AudioSource.SOUNDCLOUD to "SoundCloud",
                    ),
                    selected = settings.audioSource,
                ) { value -> container.playbackSettings.update { it.copy(audioSource = value) } }
            }
        }
        item {
            SettingsGroup(
                "Źródło danych",
                "Skąd brać wyszukiwanie, playlisty, ulubione i rekomendacje. ListenBrainz całkowicie zastępuje Spotify (wyszukiwanie przez MusicBrainz, reszta przez ListenBrainz) — audio nadal leci z YouTube/SoundCloud jak dziś.",
            ) {
                SettingOptions(
                    options = listOf(
                        DataSource.LISTENBRAINZ to "ListenBrainz",
                        DataSource.SPOTIFY to "Spotify",
                    ),
                    selected = settings.dataSource,
                ) { value -> container.playbackSettings.update { it.copy(dataSource = value) } }
                if (settings.dataSource == DataSource.LISTENBRAINZ) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.listenBrainzUsername,
                        onValueChange = { value -> container.playbackSettings.update { it.copy(listenBrainzUsername = value) } },
                        label = { Text("Nazwa użytkownika ListenBrainz") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.listenBrainzApiToken,
                        onValueChange = { value -> container.playbackSettings.update { it.copy(listenBrainzApiToken = value) } },
                        label = { Text("Token API ListenBrainz") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { openUrlInBrowser("https://listenbrainz.org/settings/") }) {
                        Text("Wygeneruj token")
                    }
                }
            }
        }
        item {
            SettingsGroup(
                "Profil klienta YouTube",
                "Profil używany do rozwiązywania strumienia YouTube. AUTO wybiera aktualnie wspierany profil VISIONOS; przełączenie na SoundCloud realizuje ustawienie Źródło audio = AUTO.",
            ) {
                SettingOptions(
                    options = listOf(
                        YouTubeClientProfile.AUTO to "AUTO",
                        YouTubeClientProfile.VISIONOS to "VISIONOS",
                    ),
                    selected = settings.youtubeClientProfile,
                ) { value -> container.playbackSettings.update { it.copy(youtubeClientProfile = value) } }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.prefetch_title), stringResource(Res.string.prefetch_desc)) {
                SettingOptions(
                    options = listOf(false to stringResource(Res.string.loudness_off), true to stringResource(Res.string.option_enabled)),
                    selected = settings.prefetchEnabled,
                ) { value -> container.playbackSettings.update { it.copy(prefetchEnabled = value) } }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.theme_title), stringResource(Res.string.theme_desc)) {
                SettingOptions(
                    options = listOf(
                        AppTheme.FOREST to stringResource(Res.string.theme_forest),
                        AppTheme.MIDNIGHT to stringResource(Res.string.theme_midnight),
                        AppTheme.VINYL to stringResource(Res.string.theme_vinyl),
                        AppTheme.EMBER to stringResource(Res.string.theme_ember),
                    ),
                    selected = settings.theme,
                ) { value -> container.playbackSettings.update { it.copy(theme = value) } }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.cassette_background_title), stringResource(Res.string.cassette_background_desc)) {
                SettingOptions(
                    options = listOf(false to stringResource(Res.string.loudness_off), true to stringResource(Res.string.option_enabled)),
                    selected = settings.cassetteBackground,
                ) { value -> container.playbackSettings.update { it.copy(cassetteBackground = value) } }
            }
        }
        item {
            var cacheBytes by remember { mutableStateOf<Long?>(null) }
            var clearedJustNow by remember { mutableStateOf(false) }
            var refreshTrigger by remember { mutableStateOf(0) }
            val scope = rememberCoroutineScope()
            val cacheSizeUnknownLabel = stringResource(Res.string.cache_size_unknown)
            LaunchedEffect(refreshTrigger) {
                cacheBytes = null
                cacheBytes = container.audioPlayer.cacheSizeBytes()
            }
            val palette = LocalNutaPalette.current
            SettingsGroup(stringResource(Res.string.cache_title), stringResource(Res.string.cache_desc)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        cacheBytes?.let(::formatBytes) ?: cacheSizeUnknownLabel,
                        color = palette.muted,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            container.audioPlayer.clearCache()
                            clearedJustNow = true
                            refreshTrigger += 1
                        }
                    }) { Text(stringResource(Res.string.cache_clear_button)) }
                }
                if (clearedJustNow) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(Res.string.cache_cleared), color = palette.success, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(Res.string.cache_limit_label), fontSize = 12.sp)
                SettingOptions(
                    options = listOf(50 to "50 MB", 100 to "100 MB", 150 to "150 MB", 300 to "300 MB"),
                    selected = settings.cacheSizeMb,
                ) { value -> container.playbackSettings.update { it.copy(cacheSizeMb = value) } }
                Text(stringResource(Res.string.cache_limit_restart_note), color = palette.muted, fontSize = 11.sp)
            }
        }
        item {
            val palette = LocalNutaPalette.current
            Text(
                stringResource(Res.string.settings_footer),
                color = palette.muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Text(
                "Nuta • by jablonowskit",
                color = palette.onMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, description: String, content: @Composable () -> Unit) {
    val palette = LocalNutaPalette.current
    Card(backgroundColor = MaterialTheme.colors.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(description, color = palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun <T> SettingOptions(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val palette = LocalNutaPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            OutlinedButton(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    backgroundColor = if (active) palette.primaryVariant else Color.Transparent,
                    contentColor = if (active) Color.White else palette.onBackground,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) { Text(label, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

// Integer arithmetic zamiast String.format: to (JVM-only) rozszerzenie stdlib nie istnieje
// we wspólnym kodzie KMP — commonMain kompiluje się też pod cele nie-JVM.
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${scaledOneDecimal(bytes, 1_073_741_824L)} GB"
    bytes >= 1_048_576L -> "${scaledOneDecimal(bytes, 1_048_576L)} MB"
    bytes >= 1_024L -> "${bytes / 1_024L} KB"
    else -> "$bytes B"
}

private fun scaledOneDecimal(value: Long, unit: Long): String {
    val tenths = value * 10 / unit
    return "${tenths / 10}.${tenths % 10}"
}
