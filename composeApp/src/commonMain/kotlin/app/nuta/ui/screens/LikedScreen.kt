package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.nuta.ui.shareTrack
import app.nuta.ui.startTrackRadio
import app.nuta.ui.rememberPrefetchHandler
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll

@Composable
internal fun LikedScreen(
    tracks: List<Track>,
    loading: Boolean,
    error: String?,
    playerState: PlayerState,
    container: AppContainer,
    onAddToPlaylist: (Track) -> Unit,
    onRetry: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // rememberSaveable: filtr i sortowanie przeżywają przejście na inną zakładkę i z powrotem
    var filter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(LikedSort.RECENT) }
    // Przy ~100 polubionych przewijanie w poszukiwaniu jednego utworu było męczące. „Odtwórz
    // wszystkie” i „Dodaj do kolejki” biorą to, co widać — z aktywnym filtrem tylko trafienia.
    val shown = remember(tracks, filter, sort) {
        val needle = filter.trim()
        val filtered = if (needle.isEmpty()) tracks
        else tracks.filter { track ->
            track.title.contains(needle, ignoreCase = true) || track.artists.any { it.contains(needle, ignoreCase = true) }
        }
        when (sort) {
            // API zwraca polubienia od najnowszych (pole `created`, sprawdzone curlem) — to jest ta kolejność
            LikedSort.RECENT -> filtered
            LikedSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
            LikedSort.ARTIST -> filtered.sortedWith(compareBy({ it.artists.firstOrNull().orEmpty().lowercase() }, { it.title.lowercase() }))
        }
    }
    Column(Modifier.fillMaxSize()) {
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error != null -> {
                ErrorState(error)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onRetry, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Ponów")
                }
            }
            tracks.isEmpty() -> EmptyState(stringResource(Res.string.liked_empty))
            else -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = shown.isNotEmpty(), onClick = {
                        scope.launch {
                            container.audioPlayer.setQueue(shown)
                            container.audioPlayer.play()
                        }
                    }) { Text(stringResource(Res.string.liked_play_all, shown.size), maxLines = 1, softWrap = false) }
                    OutlinedButton(enabled = shown.isNotEmpty(), onClick = { scope.launch { container.audioPlayer.appendToQueue(shown) } }) { Text(stringResource(Res.string.add_all_to_queue), maxLines = 1, softWrap = false) }
                    // jeden przycisk przełączający po kolei zamiast trzeciego rzędu — na telefonie brakuje miejsca
                    OutlinedButton(onClick = { sort = LikedSort.entries[(sort.ordinal + 1) % LikedSort.entries.size] }) {
                        Text(
                            stringResource(
                                when (sort) {
                                    LikedSort.RECENT -> Res.string.sort_recent
                                    LikedSort.TITLE -> Res.string.sort_title
                                    LikedSort.ARTIST -> Res.string.sort_artist
                                },
                            ),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text(stringResource(Res.string.filter_liked)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                val onVisibleRangeChanged = rememberPrefetchHandler(shown, container)
                ScrollableLazyColumn(Modifier.fillMaxSize(), scrollToIndex = shown.indexOfFirst { it.id == playerState.currentTrack?.id }.takeIf { it >= 0 }, onVisibleRangeChanged = onVisibleRangeChanged) {
                    items(shown, key = { "liked-${it.id}" }) { track ->
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
                        }, onLongPress = { onAddToPlaylist(track) }, onPlayNext = { scope.launch { container.audioPlayer.insertNext(listOf(track)) } }, onRadio = { scope.launch { startTrackRadio(container, track) } }, onShare = { shareTrack(container, track) })
                    }
                }
            }
        }
    }
}

private enum class LikedSort { RECENT, TITLE, ARTIST }
