package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
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
import kotlinx.coroutines.CancellationException
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
    // Tylko przy FAKTYCZNEJ zmianie źródła: LaunchedEffect startuje przy każdym wejściu na ekran,
    // także po powrocie ze szczegółów wykonawcy — bezwarunkowe czyszczenie kasowało wtedy wyniki
    // i wyszukiwanie ruszało od nowa (zgłoszone 25.09.2026).
    LaunchedEffect(settings.dataSource) {
        val source = currentState.resultDataSource
        if (source != null && source != settings.dataSource) {
            onStateChange(currentState.copy(result = SearchResult(emptyList(), emptyList()), resultDataSource = null, lastExecutedQuery = ""))
        }
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
                loading = false,
            ))
            return@LaunchedEffect
        }
        // Powrót ze szczegółów playlisty/wykonawcy usuwa ten ekran z kompozycji (renderowany jest
        // wtedy PlaylistDetails), więc po powrocie LaunchedEffect startuje od nowa — bez tego
        // warunku to samo zapytanie leciało drugi raz do sieci, kasując widoczne już wyniki.
        val alreadyHasResults = state.result.run { tracks.isNotEmpty() || playlists.isNotEmpty() || artists.isNotEmpty() }
        if (submittedQuery == currentState.lastExecutedQuery && alreadyHasResults &&
            currentState.resultDataSource == settings.dataSource
        ) return@LaunchedEffect
        delay(400)
        // Ustawiane DOPIERO po debounce: przy szybkim pisaniu każdy poprzedni LaunchedEffect
        // jest anulowany, zanim tu dotrze, więc spinner nie miga po każdym znaku — tylko gdy
        // zapytanie faktycznie ruszyło do sieci. Bez tego pola ekran przez cały czas
        // oczekiwania (debounce + samo zapytanie, wydłużone przez retry na 503 z MusicBrainz —
        // potrafi to trwać kilka sekund) pokazywał "brak wyników", co czytało się jako "nie działa".
        onStateChange(currentState.copy(loading = true))
        var finished = false
        try {
        // Spotify nie zna składni "|"/"&" — do zapytania serwerowego wysyłamy same słowa,
        // dokładne dopasowanie OR/AND liczymy potem lokalnie (visibleTracks niżej).
        val serverSearchTerm = submittedQuery.split(Regex("[|&\\s]+")).filter(String::isNotBlank).distinct().joinToString(" ")
        runCatching { container.spotifyRepository.search(serverSearchTerm) }
            .also { if (it.exceptionOrNull() !is CancellationException) finished = true }
            .onSuccess {
                if (currentState.query == submittedQuery) {
                    onStateChange(currentState.copy(result = it, error = null, lastExecutedQuery = submittedQuery, loading = false, resultDataSource = settings.dataSource))
                }
            }
            .onFailure { error ->
                // Regresja 19.09.2026: `search()` uruchamia teraz dwa żądania równolegle przez
                // `coroutineScope { async {...} }` (patrz ListenBrainzRepository.search) — gdy
                // ten LaunchedEffect jest anulowany (naturalne przy szybkim pisaniu: kolejny
                // znak startuje nowy efekt i Compose anuluje ten), `coroutineScope` propaguje
                // `CancellationException`. `runCatching` NIE rethrow'uje jej automatycznie, więc
                // trafiała tu jako zwykły błąd z komunikatem „The coroutine scope left the
                // composition" — użytkownik widział to jako czerwony błąd na ekranie, mimo że
                // to nie awaria, tylko naturalne anulowanie przegranego wyścigu zapytań.
                // Trzeba rzucić dalej, żeby korutyna faktycznie się zakończyła — inaczej
                // dotrwałaby do onStateChange mimo że jest już martwa.
                if (error is CancellationException) throw error
                if (currentState.query == submittedQuery) {
                    onStateChange(currentState.copy(error = error.message ?: searchUnknownError, lastExecutedQuery = submittedQuery, loading = false))
                }
            }
        } finally {
            // Anulowanie w trakcie zapytania (wyjście do szczegółów wykonawcy, zmiana źródła)
            // omija oba powyższe gałęzie i zostawiało loading = true — spinner kręcił się bez
            // końca (zgłoszone 25.09.2026 na desktopie). Nowe zapytanie i tak ustawi go ponownie.
            // Tylko po PRZERWANYM zapytaniu. Po udanym `currentState` jest jeszcze sprzed
            // rekompozycji (bez nowych wyników), więc copy() nadpisywało świeże wyniki pustą
            // listą — ekran pokazywał "Brak wyników" mimo 20 trafień w logu.
            // I tylko gdy zapytanie się nie zmieniło: blokujące żądanie HTTP kończy się dopiero po
            // odpowiedzi/timeoucie, więc przerwane stare zapytanie potrafiło dojść tu kilka sekund
            // później i zgasić spinner NOWEMU — ekran pokazywał wtedy "Brak wyników" w trakcie
            // szukania. Przy zmianie tekstu spinnerem rządzi już nowe zapytanie.
            if (!finished && currentState.query == submittedQuery) onStateChange(currentState.copy(loading = false))
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
        // Każde pole wyboru włącza/ukrywa SWOJĄ sekcję, tak jak "Wykonawcy" i "Playlisty". Wcześniej
        // "Utwory" znaczyło tylko "dopasowuj po tytule", więc utwory trafione po wykonawcy zostawały
        // i odznaczenie nie dawało żadnej reakcji (zgłoszone 25.09.2026), a odznaczenie "Wykonawcy"
        // po cichu wycinało też utwory dopasowane po nazwie wykonawcy.
        val visibleTracks = if (!state.searchTracks) emptyList() else state.result.tracks.filter { track ->
            queryOrGroups.isEmpty() || queryOrGroups.any { andWords ->
                andWords.all { word ->
                    track.title.matchesLoosely(word) || track.artists.any { it.matchesLoosely(word) }
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
        if (state.error != null) {
            ErrorState(state.error)
        } else if (state.loading && visibleTracks.isEmpty() && visiblePlaylists.isEmpty() && visibleArtists.isEmpty()) {
            // Bez tego stanu ekran przez cały czas wyszukiwania (debounce + zapytanie, wydłużone
            // przez retry na 503 z przeciążonego MusicBrainz — potrafi to trwać kilka sekund)
            // pokazywał "brak wyników", zanim właściwe wyniki zdążyły przyjść.
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(top = 24.dp))
        } else if (state.query.isNotBlank() && visibleTracks.isEmpty() && visiblePlaylists.isEmpty() && visibleArtists.isEmpty() &&
            !(state.searchPlaylists && state.result.playlistsUnavailable)
        ) {
            // Gdy jedynym powodem pustki jest awaria wyszukiwania playlist, "brak wyników" byłoby
            // mylące — wtedy schodzimy niżej i pokazujemy sekcję z informacją o niedostępności.
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
                } else if (state.searchPlaylists && state.result.playlistsUnavailable) {
                    // Awaria wyszukiwania playlist (ListenBrainz playlist/search bywa martwy —
                    // GET wisi bez odpowiedzi, zweryfikowane curlem 21.09.2026) dawała pustą
                    // sekcję nieodróżnialną od realnego braku trafień.
                    item { SectionLabel(stringResource(Res.string.section_playlists)) }
                    item {
                        Text(
                            stringResource(Res.string.playlist_search_unavailable),
                            color = Color(0xFF8D9BA6),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
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
