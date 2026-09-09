package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nuta.AppContainer
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Playlist
import app.nuta.core.models.Track
import app.nuta.resources.*
import app.nuta.ui.EmptyState
import app.nuta.ui.Heading
import app.nuta.ui.PlaylistCard
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.TrackPlayButton
import app.nuta.ui.TrackQueueButton
import app.nuta.ui.TrackRow
import app.nuta.ui.rememberPrefetchHandler
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll

@Composable
internal fun PlaylistsScreen(playlists: List<Playlist>, onSelect: (Playlist) -> Unit, onCreatePlaylist: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(Res.string.library_title), stringResource(Res.string.library_subtitle))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCreatePlaylist) { Text(stringResource(Res.string.create_playlist_title), maxLines = 1, softWrap = false) }
        Spacer(Modifier.height(12.dp))
        if (playlists.isEmpty()) EmptyState(stringResource(Res.string.no_playlists)) else ScrollableLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(playlists, key = { it.id }) { playlist -> PlaylistCard(playlist) { onSelect(playlist) } }
        }
    }
}


@Composable
internal fun PlaylistDetails(playlist: Playlist, playerState: PlayerState, container: AppContainer, onAddToPlaylist: (Track) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Heading(playlist.name, playlist.description)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { container.audioPlayer.setQueue(playlist.tracks); container.audioPlayer.play() } }) { Text(stringResource(Res.string.play_all), maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = { scope.launch { container.audioPlayer.appendToQueue(playlist.tracks) } }) { Text(stringResource(Res.string.add_all_to_queue), maxLines = 1, softWrap = false) }
        }
        Spacer(Modifier.height(16.dp))
        val onVisibleRangeChanged = rememberPrefetchHandler(playlist.tracks, container)
        ScrollableLazyColumn(Modifier.fillMaxSize(), onVisibleRangeChanged = onVisibleRangeChanged) {
            items(playlist.tracks, key = { it.id }) { track ->
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
