package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nuta.AppContainer
import app.nuta.core.models.Artist
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.resources.*
import app.nuta.search.matchesLoosely
import app.nuta.ui.ArtistSearchCard
import app.nuta.ui.EmptyState
import app.nuta.ui.ErrorState
import app.nuta.ui.PlaylistCard
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.SearchViewState
import app.nuta.ui.SectionLabel
import app.nuta.ui.TrackPlayButton
import app.nuta.ui.TrackQueueButton
import app.nuta.ui.TrackRow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.delay

@Composable
internal fun SearchScreen(
    container: AppContainer,
    state: SearchViewState,
    onStateChange: (SearchViewState) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onArtist: (Artist) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playerState by container.audioPlayer.state.collectAsState()
    val currentState by rememberUpdatedState(state)
    val searchUnknownError = stringResource(Res.string.search_unknown_error)
    val settings by container.playbackSettings.settings.collectAsState()
    suspend fun playTrack(track: Track) {
        container.audioPlayer.setQueue(listOf(track), 0)
        container.audioPlayer.play()
    }

    // Bez tego przełączenie DataSource zostawiało na ekranie wyniki wyszukiwania z
    // poprzedniego backendu — kliknięcie takiego wyniku wysyłało ID z jednego źródła
    // (np. Spotify) do drugiego (ListenBrainz), które go nie rozpoznaje.
    LaunchedEffect(settings.dataSource) {
        onStateChange(currentState.copy(result = SearchResult(emptyList(), emptyList())))
    }

    // Filtry (searchTracks/Artists/Playlists) tylko zawężają już pobrane wyniki lokalnie
    // (patrz visibleTracks/visiblePlaylists niżej) — nie powinny wywoływać ponownego zapytania sieciowego.
    LaunchedEffect(state.query, container.spotifyRepository, settings.dataSource) {
        val submittedQuery = state.query
        if (submittedQuery.isBlank()) {
            onStateChange(currentState.copy(
                result = SearchResult(emptyList(), emptyList()),
                error = null,
                lastExecutedQuery = submittedQuery,
            ))
            return@LaunchedEffect
        }
        delay(400)
        // Spotify nie zna składni "|"/"&" — do zapytania serwerowego wysyłamy same słowa,
        // dokładne dopasowanie OR/AND liczymy potem lokalnie (visibleTracks niżej).
        val serverSearchTerm = submittedQuery.split(Regex("[|&\\s]+")).filter(String::isNotBlank).distinct().joinToString(" ")
        runCatching { container.spotifyRepository.search(serverSearchTerm) }
            .onSuccess {
                if (currentState.query == submittedQuery) {
                    onStateChange(currentState.copy(result = it, error = null, lastExecutedQuery = submittedQuery))
                }
            }
            .onFailure {
                if (currentState.query == submittedQuery) {
                    onStateChange(currentState.copy(error = it.message ?: searchUnknownError, lastExecutedQuery = submittedQuery))
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        
        OutlinedTextField(
            value = state.query,
            onValueChange = { onStateChange(state.copy(query = it)) },
            label = { Text(stringResource(Res.string.search_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 380.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SearchScopeCheckbox(stringResource(Res.string.filter_tracks), state.searchTracks) { onStateChange(state.copy(searchTracks = it)) }
                    SearchScopeCheckbox(stringResource(Res.string.filter_artists), state.searchArtists) { onStateChange(state.copy(searchArtists = it)) }
                    SearchScopeCheckbox(stringResource(Res.string.filter_playlists), state.searchPlaylists) { onStateChange(state.copy(searchPlaylists = it)) }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchScopeCheckbox(stringResource(Res.string.filter_tracks), state.searchTracks) { onStateChange(state.copy(searchTracks = it)) }
                    SearchScopeCheckbox(stringResource(Res.string.filter_artists), state.searchArtists) { onStateChange(state.copy(searchArtists = it)) }
                    SearchScopeCheckbox(stringResource(Res.string.filter_playlists), state.searchPlaylists) { onStateChange(state.copy(searchPlaylists = it)) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        // "|" rozdziela grupy OR, w każdej grupie "&" albo spacja rozdziela wymagane słowa (AND).
        val queryOrGroups = state.query.split("|").map { group ->
            group.trim().split(Regex("[&\\s]+")).filter(String::isNotBlank)
        }.filter(List<String>::isNotEmpty)
        val visibleTracks = state.result.tracks.filter { track ->
            queryOrGroups.isEmpty() || queryOrGroups.any { andWords ->
                andWords.all { word ->
                    val titleMatches = state.searchTracks && track.title.matchesLoosely(word)
                    val artistMatches = state.searchArtists && track.artists.any { it.matchesLoosely(word) }
                    titleMatches || artistMatches
                }
            }
        }
        val visiblePlaylists = if (state.searchPlaylists) state.result.playlists else emptyList()
        // Wykonawcy zawężani tym samym dopasowaniem co utwory (odpornym na diakrytyki),
        // żeby lista nie pokazywała trafień niezwiązanych z wpisanym tekstem.
        val visibleArtists = if (!state.searchArtists) emptyList() else state.result.artists.filter { artist ->
            queryOrGroups.isEmpty() || queryOrGroups.any { andWords -> andWords.all { artist.name.matchesLoosely(it) } }
        }
        val searchPlaybackSettings by container.playbackSettings.settings.collectAsState()
        LaunchedEffect(visibleTracks, searchPlaybackSettings.prefetchEnabled) {
            if (searchPlaybackSettings.prefetchEnabled) container.audioPlayer.prefetch(visibleTracks)
        }
        if (state.error != null) ErrorState(state.error) else if (state.query.isNotBlank() && visibleTracks.isEmpty() && visiblePlaylists.isEmpty() && visibleArtists.isEmpty()) {
            EmptyState(stringResource(Res.string.search_no_results, state.query))
        } else {
            ScrollableLazyColumn(Modifier.fillMaxSize()) {
                if (visibleArtists.isNotEmpty()) {
                    item { SectionLabel(stringResource(Res.string.section_artists)) }
                    items(visibleArtists, key = { "a-${it.id}" }) { artist ->
                        ArtistSearchCard(artist) { onArtist(artist) }
                    }
                    item { Spacer(Modifier.height(18.dp)) }
                }
                if (visiblePlaylists.isNotEmpty()) {
                    item { SectionLabel(stringResource(Res.string.section_playlists)) }
                    items(visiblePlaylists, key = { "p-${it.id}" }) { PlaylistCard(it) { onPlaylist(it) } }
                    item { Spacer(Modifier.height(18.dp)) }
                }
                if (visibleTracks.isNotEmpty()) {
                    item { SectionLabel(stringResource(Res.string.section_tracks)) }
                    items(visibleTracks, key = { "t-${it.id}" }) { track ->
                        TrackRow(track, playerState.currentTrack?.id == track.id, loading = playerState.status == PlayerStatus.LOADING, onPlay = {
                            scope.launch { playTrack(track) }
                        }, titleAction = {
                            TrackPlayButton {
                                scope.launch { playTrack(track) }
                            }
                            }, subtitleAction = {
                            TrackQueueButton { scope.launch { container.audioPlayer.appendToQueue(listOf(track)) } }
                        }, onLongPress = { onAddToPlaylist(track) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScopeCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, fontSize = 12.sp, color = Color(0xFFD5DCE1))
    }
}
