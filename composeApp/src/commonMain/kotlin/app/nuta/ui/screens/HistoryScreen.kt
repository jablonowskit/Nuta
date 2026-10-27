package app.nuta.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.AppContainer
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.listenbrainz.HistoryEntry
import app.nuta.listenbrainz.ListenBrainzHistory
import app.nuta.resources.*
import app.nuta.ui.EmptyState
import app.nuta.ui.ErrorState
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.TrackRow
import app.nuta.ui.startTrackRadio
import app.nuta.ui.theme.LocalNutaPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Ostatnio grane z ListenBrainz (to, co wysłał scrobbler i inne klienty użytkownika). Lokalnej
 * historii aplikacja nie prowadzi — ListenBrainz i tak ją ma, także z innych urządzeń.
 */
@Composable
internal fun HistoryScreen(
    playerState: PlayerState,
    container: AppContainer,
    onAddToPlaylist: (Track) -> Unit,
) {
    val settings by container.playbackSettings.settings.collectAsState()
    val username = settings.listenBrainzUsername.trim().takeIf(String::isNotEmpty)
    val history = remember { ListenBrainzHistory() }
    val scope = rememberCoroutineScope()
    var entries by remember(username) { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var cursor by remember(username) { mutableStateOf<Long?>(null) }
    var loading by remember(username) { mutableStateOf(false) }
    var error by remember(username) { mutableStateOf<String?>(null) }

    suspend fun load(user: String, maxTs: Long?) {
        loading = true
        error = null
        try {
            val page = history.page(user, maxTs)
            entries = if (maxTs == null) page.entries else entries + page.entries
            cursor = page.oldestTs
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "?"
            container.logger.warn("History", "history_failed", "Nie udało się pobrać historii", fields = mapOf("reason" to (e.message ?: "unknown")))
        } finally {
            loading = false
        }
    }

    LaunchedEffect(username) { username?.let { load(it, null) } }

    Column(Modifier.fillMaxSize()) {
        when {
            username == null -> EmptyState(stringResource(Res.string.stats_no_username))
            entries.isEmpty() && loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            entries.isEmpty() && error != null -> {
                ErrorState(error ?: "")
                Spacer(Modifier.height(10.dp))
                Button(onClick = { scope.launch { load(username, null) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(Res.string.retry))
                }
            }
            entries.isEmpty() -> EmptyState(stringResource(Res.string.history_empty))
            else -> ScrollableLazyColumn(Modifier.fillMaxSize()) {
                // klucz z czasem: ten sam utwór bywa w historii wiele razy
                itemsIndexed(entries, key = { index, entry -> "history-${entry.listenedAt}-$index" }) { _, entry ->
                    val track = entry.track
                    TrackRow(
                        track,
                        active = playerState.currentTrack?.id == track.id,
                        loading = playerState.status == PlayerStatus.LOADING,
                        onPlay = { scope.launch { container.audioPlayer.setQueue(listOf(track), 0); container.audioPlayer.play() } },
                        subtitleAction = { PlayedAt(entry.listenedAt) },
                        onLongPress = { onAddToPlaylist(track) },
                        onPlayNext = { scope.launch { container.audioPlayer.insertNext(listOf(track)) } },
                        onRadio = { scope.launch { startTrackRadio(container, track) } },
                    )
                }
                item {
                    val next = cursor
                    if (next != null || loading) {
                        OutlinedButton(
                            onClick = { scope.launch { load(username, next) } },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) { Text(stringResource(if (loading) Res.string.history_loading else Res.string.history_load_older)) }
                    }
                    error?.let { ErrorState(it) }
                }
            }
        }
    }
}

private val TimeOfDay = DateTimeFormatter.ofPattern("HH:mm")
private val DayAndTime = DateTimeFormatter.ofPattern("d.MM HH:mm")

/** „14:32” dla dzisiejszych, „26.10 21:10” dla starszych — w strefie telefonu. */
@Composable
private fun PlayedAt(listenedAt: Long) {
    val time = Instant.ofEpochSecond(listenedAt).atZone(ZoneId.systemDefault())
    val label = if (time.toLocalDate() == LocalDate.now()) time.format(TimeOfDay) else time.format(DayAndTime)
    Text(label, color = LocalNutaPalette.current.muted, fontSize = 11.sp)
}
