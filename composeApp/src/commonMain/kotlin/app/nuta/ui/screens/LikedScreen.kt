package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nuta.AppContainer
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.resources.*
import app.nuta.ui.EmptyState
import app.nuta.ui.ErrorState
import app.nuta.ui.Heading
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.TrackPlayButton
import app.nuta.ui.TrackQueueButton
import app.nuta.ui.TrackRow
import app.nuta.ui.rememberPrefetchHandler
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LikedScreen(
    tracks: List<Track>,
    loading: Boolean,
    error: String?,
    playerState: PlayerState,
    container: AppContainer,
    onAddToPlaylist: (Track) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(Res.string.liked_title))
        Spacer(Modifier.height(16.dp))
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error != null -> ErrorState(error)
            tracks.isEmpty() -> EmptyState(stringResource(Res.string.liked_empty))
            else -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            container.audioPlayer.setQueue(tracks)
                            container.audioPlayer.play()
                        }
                    }) { Text(stringResource(Res.string.liked_play_all, tracks.size), maxLines = 1, softWrap = false) }
                    OutlinedButton(onClick = { scope.launch { container.audioPlayer.appendToQueue(tracks) } }) { Text(stringResource(Res.string.add_all_to_queue), maxLines = 1, softWrap = false) }
                }
                Spacer(Modifier.height(14.dp))
                val onVisibleRangeChanged = rememberPrefetchHandler(tracks, container)
                ScrollableLazyColumn(Modifier.fillMaxSize(), scrollToIndex = tracks.indexOfFirst { it.id == playerState.currentTrack?.id }.takeIf { it >= 0 }, onVisibleRangeChanged = onVisibleRangeChanged) {
                    items(tracks, key = { "liked-${it.id}" }) { track ->
                        TrackRow(track, playerState.currentTrack?.id == track.id, loading = playerState.status == PlayerStatus.LOADING, onPlay = {
                            scope.launch {
                                container.audioPlayer.setQueue(listOf(track), 0)
                                container.audioPlayer.play()
                            }
                        }, titleAction = {
                            TrackPlayButton { scope.launch {
                                container.audioPlayer.setQueue(listOf(track), 0)
                                container.audioPlayer.play()
                            } }
                        }, subtitleAction = {
                            TrackQueueButton { scope.launch { container.audioPlayer.appendToQueue(listOf(track)) } }
                        }, onLongPress = { onAddToPlaylist(track) })
                    }
                }
            }
        }
    }
}

// TrackRow, TrackActionButton/PlayButton/QueueButton i BufferingIndicator:
// patrz TrackComponents.kt
