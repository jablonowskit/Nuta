package app.nuta.ui

import app.nuta.core.BuildInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import app.nuta.resources.*
import app.nuta.AppContainer
import app.nuta.ui.screens.DiagnosticsScreen
import app.nuta.ui.screens.HomeScreen
import app.nuta.ui.screens.LikedScreen
import app.nuta.ui.screens.PlaylistDetails
import app.nuta.ui.screens.PlaylistsScreen
import app.nuta.ui.screens.QueueScreen
import app.nuta.ui.screens.SearchScreen
import app.nuta.ui.screens.SettingsScreen
import app.nuta.core.models.Destination
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val NutaColors = darkColors(
    primary = Color(0xFF8BE9A8),
    primaryVariant = Color(0xFF54C57A),
    secondary = Color(0xFF9BA8FF),
    background = Color(0xFF101418),
    surface = Color(0xFF182027),
    onPrimary = Color(0xFF08130D),
    onBackground = Color(0xFFE8EDF2),
    onSurface = Color(0xFFE8EDF2),
)

internal data class SearchViewState(
    val query: String = "",
    val result: SearchResult = SearchResult(emptyList(), emptyList()),
    val error: String? = null,
    val lastExecutedQuery: String = "",
    val searchTracks: Boolean = true,
    val searchArtists: Boolean = true,
    val searchPlaylists: Boolean = true,
)

@Composable
internal fun ScrollableLazyColumn(
    modifier: Modifier = Modifier,
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    scrollToIndex: Int? = null,
    onVisibleRangeChanged: ((IntRange) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToIndex) {
        scrollToIndex?.takeIf { it >= 0 }?.let { index ->
            listState.animateScrollToItem(index)
        }
    }
    if (onVisibleRangeChanged != null) {
        LaunchedEffect(listState) {
            var debounceJob: Job? = null
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }.collect { indices ->
                debounceJob?.cancel()
                if (indices.isEmpty()) return@collect
                debounceJob = launch {
                    delay(250)
                    onVisibleRangeChanged(indices.min()..indices.max())
                }
            }
        }
    }
    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            content = content,
        )
        PlatformVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** Eksperymentalny prefetch: śledzi widoczny zakres listy (debounced) i rozwiązuje strumień dla tych utworów z wyprzedzeniem. */
@Composable
internal fun rememberPrefetchHandler(tracks: List<Track>, container: AppContainer): (IntRange) -> Unit {
    val settings by container.playbackSettings.settings.collectAsState()
    var visibleRange by remember { mutableStateOf(0..2) }
    LaunchedEffect(visibleRange, tracks, settings.prefetchEnabled) {
        if (!settings.prefetchEnabled) return@LaunchedEffect
        val toPrefetch = (visibleRange.first..visibleRange.last).mapNotNull(tracks::getOrNull)
        if (toPrefetch.isNotEmpty()) container.audioPlayer.prefetch(toPrefetch)
    }
    return { range -> visibleRange = range }
}

@Composable
fun NutaApp(container: AppContainer) {
    val settings by container.playbackSettings.settings.collectAsState()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, settings.fontScale)) {
    MaterialTheme(colors = NutaColors) {
        NutaAppContent(container)
    }
    }
}

@Composable
private fun NutaAppContent(container: AppContainer) {
        val playerState by container.audioPlayer.state.collectAsState()
        val playbackSettings by container.playbackSettings.settings.collectAsState()
        LaunchedEffect(playerState.currentIndex, playerState.queue, playbackSettings.prefetchEnabled) {
            if (!playbackSettings.prefetchEnabled) return@LaunchedEffect
            val upcoming = (playerState.currentIndex + 1..playerState.currentIndex + 3).mapNotNull(playerState.queue::getOrNull)
            if (upcoming.isNotEmpty()) container.audioPlayer.prefetch(upcoming)
        }
        // rememberSaveable (nie remember) — na Androidzie obrót ekranu domyślnie odtwarza
        // Activity od nowa; bez tego zakładka zawsze wracała do Start po obrocie.
        var destination by rememberSaveable(
            stateSaver = Saver<Destination, String>(save = { it.name }, restore = { name -> Destination.valueOf(name) }),
        ) { mutableStateOf(Destination.HOME) }
        var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
        var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
        var savedPlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
        var savedPlaylistsLoaded by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }
        var searchState by remember { mutableStateOf(SearchViewState()) }
        var likedTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
        var likedLoading by remember { mutableStateOf(false) }
        var likedLoaded by remember { mutableStateOf(false) }
        var likedError by remember { mutableStateOf<String?>(null) }
        var similarModeActive by remember { mutableStateOf(false) }
        var similarModeLoading by remember { mutableStateOf(false) }
        var currentTrackLiked by remember { mutableStateOf(false) }
        // tylko stan ręcznego kliknięcia — celowo NIE dzielony z tłowym sprawdzaniem "czy polubione"
        // przy zmianie utworu; gdyby to sprawdzenie się zawiesiło, nie może trwale zablokować przycisku
        var favoriteLoading by remember { mutableStateOf(false) }
        var createPlaylistDialogOpen by remember { mutableStateOf(false) }
        var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }
        var playlistActionLoading by remember { mutableStateOf(false) }
        var playlistActionError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val displayedTrackLiked = currentTrackLiked || playerState.currentTrack?.id?.let { id -> likedTracks.any { it.id == id } } == true
        val savedPlaylistsFailedPrefix = stringResource(Res.string.saved_playlists_failed_prefix)
        val unknownErrorLabel = stringResource(Res.string.unknown_error)
        val likedFetchFailedLabel = stringResource(Res.string.liked_fetch_failed)
        val errorUnknownLabel = stringResource(Res.string.error_unknown)

        fun selectPlaylist(playlist: Playlist) {
            scope.launch {
                loading = true
                loadError = null
                runCatching { container.spotifyRepository.getPlaylistTracks(playlist.id) }
                    .onSuccess { selectedPlaylist = playlist.copy(tracks = it) }
                    .onFailure {
                        loadError = it.message
                        container.logger.error(
                            "SpotifyPlaylist",
                            "playlist_open_failed",
                            "Nie udało się otworzyć playlisty",
                            fields = mapOf("playlistIdLength" to playlist.id.length.toString()),
                            throwable = it,
                        )
                    }
                loading = false
            }
        }

        fun openAddToPlaylistDialog(track: Track) {
            playlistActionError = null
            addToPlaylistTrack = track
            // lista playlist jest doczytywana leniwie dopiero na ekranie biblioteki — dialog
            // może zostać otwarty z wyszukiwania, gdzie jej jeszcze nie ma
            if (!savedPlaylistsLoaded) scope.launch {
                runCatching { container.spotifyRepository.getSavedPlaylists() }
                    .onSuccess { savedPlaylists = it; savedPlaylistsLoaded = true }
                    .onFailure { playlistActionError = it.message ?: unknownErrorLabel }
            }
        }

        LaunchedEffect(playbackSettings.dataSource) {
            savedPlaylistsLoaded = false
            savedPlaylists = emptyList()
            // Otwarta playlista mogła pochodzić z poprzedniego źródła — jej id nic nie znaczy
            // dla nowo aktywnego backendu (np. odświeżenie wysłałoby ID Spotify do ListenBrainz).
            selectedPlaylist = null
        }

        LaunchedEffect(container.spotifyRepository, playbackSettings.dataSource) {
            container.logger.info("Application", "app_started", "Uruchomiono Nuta Linux GUI")
            runCatching { container.spotifyRepository.getPlaylists() }
                .onSuccess { playlists = it }
                .onFailure { loadError = it.message }
            loading = false
        }

        LaunchedEffect(destination, container.spotifyRepository, playbackSettings.dataSource) {
            if (destination != Destination.PLAYLISTS || savedPlaylistsLoaded) return@LaunchedEffect
            runCatching { container.spotifyRepository.getSavedPlaylists() }
                .onSuccess { savedPlaylists = it; savedPlaylistsLoaded = true }
                .onFailure {
                    loadError = "$savedPlaylistsFailedPrefix ${it.message ?: unknownErrorLabel}"
                    container.logger.warn("SpotifyLibrary", "saved_playlists_failed", "Nie udało się pobrać zapisanych playlist", fields = mapOf("reason" to (it.message ?: "unknown")))
                }
        }

        LaunchedEffect(similarModeActive, playerState.currentIndex, playerState.queue.size) {
            if (!similarModeActive || similarModeLoading || playerState.currentIndex < 0) return@LaunchedEffect
            val remaining = playerState.queue.lastIndex - playerState.currentIndex
            if (remaining > 3) return@LaunchedEffect
            val seed = playerState.currentTrack ?: return@LaunchedEffect
            similarModeLoading = true
            runCatching { container.spotifyRepository.getTrackRadio(seed) }
                .onSuccess { recommendations ->
                    val knownIds = playerState.queue.mapTo(mutableSetOf(), Track::id)
                    val uniqueAdditions = recommendations.shuffled().filter { knownIds.add(it.id) }
                    val additions = uniqueAdditions.ifEmpty {
                        recommendations.shuffled().filterNot { it.id == seed.id }
                    }
                    container.audioPlayer.appendToQueue(additions)
                    container.logger.info(
                        "SpotifyRadio", "continuous_queue_extended", "Automatycznie rozszerzono kolejkę podobnych utworów",
                        fields = mapOf("added" to additions.size.toString()),
                    )
                }
                .onFailure {
                    container.logger.warn("SpotifyRadio", "continuous_queue_failed", "Nie udało się rozszerzyć kolejki podobnych utworów", fields = mapOf("reason" to (it::class.simpleName ?: "unknown")))
                }
            similarModeLoading = false
        }

        LaunchedEffect(container.spotifyRepository) {
            if (likedTracks.isEmpty() && !likedLoaded) {
                runCatching { container.spotifyRepository.getCachedLikedTracks() }
                    .onSuccess { cached -> if (likedTracks.isEmpty() && !likedLoaded) likedTracks = cached }
            }
        }

        LaunchedEffect(playbackSettings.dataSource) {
            likedLoaded = false
            likedTracks = emptyList()
        }
        LaunchedEffect(destination, container.spotifyRepository, playbackSettings.dataSource) {
            if (destination != Destination.PLAYLISTS && destination != Destination.LIKED || likedLoaded || likedLoading) return@LaunchedEffect
            likedLoading = true
            likedError = null
            runCatching { container.spotifyRepository.getLikedTracks() }
                .onSuccess {
                    likedTracks = it
                    likedLoaded = true
                }
                .onFailure { likedError = it.message ?: likedFetchFailedLabel }
            likedLoading = false
        }

        LaunchedEffect(playerState.currentTrack?.id, container.spotifyRepository, playbackSettings.dataSource) {
            val trackId = playerState.currentTrack?.id
            currentTrackLiked = false
            if (trackId == null) return@LaunchedEffect
            // unikamy zapytania sieciowego, jeśli już wiemy z załadowanej listy Ulubionych —
            // odciąża endpoint /v1/me/tracks, który Spotify łatwo rate-limituje (HTTP 429)
            if (likedTracks.any { it.id == trackId }) { currentTrackLiked = true; return@LaunchedEffect }
            runCatching { container.spotifyRepository.isTrackLiked(trackId) }
                .onSuccess { liked ->
                    // Efekt jest anulowany przy zmianie utworu, więc dotarcie tutaj oznacza,
                    // że wynik dotyczy nadal aktualnego trackId.
                    currentTrackLiked = liked
                }
                .onFailure { error ->
                    container.logger.warn(
                        "SpotifyLiked", "liked_status_failed", "Nie udało się sprawdzić, czy utwór jest w ulubionych",
                        fields = mapOf("reason" to (error::class.simpleName ?: "unknown"), "message" to (error.message ?: "")),
                    )
                }
        }

        val toggleCurrentTrackLiked = {
            val track = playerState.currentTrack
            if (track != null && !favoriteLoading) {
                val targetLiked = !currentTrackLiked
                scope.launch {
                    favoriteLoading = true
                    runCatching { container.spotifyRepository.setTrackLiked(track, targetLiked) }
                        .onSuccess {
                            if (playerState.currentTrack?.id == track.id) currentTrackLiked = targetLiked
                            likedTracks = if (targetLiked) {
                                (listOf(track) + likedTracks).distinctBy(Track::id)
                            } else {
                                likedTracks.filterNot { it.id == track.id }
                            }
                        }
                        .onFailure { error ->
                            container.logger.warn(
                                "SpotifyLiked", "liked_update_failed", "Nie udało się zmienić ulubionego utworu",
                                fields = mapOf("reason" to (error::class.simpleName ?: "unknown"), "message" to (error.message ?: "")),
                            )
                        }
                    favoriteLoading = false
                }
            }
            Unit
        }

        Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colors.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            // Leave enough room for the desktop sidebar and player controls.
            val compact = maxWidth < 960.dp
            Column(Modifier.fillMaxSize()) {
                TopBar(compact)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (!compact) Sidebar(destination) {
                        destination = it
                        selectedPlaylist = null
                        loadError = null
                        loading = false
                        container.logger.debug("Navigation", "destination_changed", "Zmieniono ekran", fields = mapOf("destination" to it.name))
                    }
                    if (!compact) Divider(Modifier.fillMaxHeight().width(1.dp), color = Color(0xFF2A343D))
                    Box(Modifier.weight(1f).fillMaxHeight().padding(if (compact) 12.dp else 24.dp)) {
                        when {
                            loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            loadError != null -> ErrorState(loadError ?: errorUnknownLabel)
                            selectedPlaylist != null -> PlaylistDetails(selectedPlaylist!!, playerState, container, onAddToPlaylist = ::openAddToPlaylistDialog)
                            else -> when (destination) {
                                Destination.HOME -> HomeScreen(
                                    playlists = playlists,
                                    playerState = playerState,
                                    onSelectPlaylist = ::selectPlaylist,
                                )
                                Destination.PLAYLISTS -> PlaylistsScreen(savedPlaylists, ::selectPlaylist, onCreatePlaylist = { createPlaylistDialogOpen = true })
                                Destination.LIKED -> LikedScreen(likedTracks, likedLoading, likedError, playerState, container, onAddToPlaylist = ::openAddToPlaylistDialog)
                                Destination.SEARCH -> SearchScreen(
                                    container = container,
                                    state = searchState,
                                    onStateChange = { searchState = it },
                                    onPlaylist = ::selectPlaylist,
                                    onAddToPlaylist = ::openAddToPlaylistDialog,
                                )
                                Destination.QUEUE -> QueueScreen(playerState, container)
                                Destination.SETTINGS -> SettingsScreen(container)
                                Destination.DIAGNOSTICS -> DiagnosticsScreen(container)
                            }
                        }
                    }
                }
                val openQueue = {
                    destination = Destination.QUEUE
                    selectedPlaylist = null
                    loadError = null
                }
                // Player jest częścią chrome'u aplikacji: widoczny na każdej zakładce, gdy jest co grać.
                // Na zakładce Kolejka pokazujemy go nawet bez utworu (stan „Nic nie gra”).
                val showPlayerBar = destination == Destination.QUEUE || playerState.currentTrack != null
                val setCollapsed: (Boolean) -> Unit = {
                    container.playbackSettings.update(playbackSettings.copy(playerCollapsed = it))
                }
                if (compact) {
                    if (showPlayerBar) {
                        Divider(color = Color(0xFF2A343D))
                        CompactPlayerBar(playerState, container, similarModeActive, { similarModeActive = it }, openQueue, displayedTrackLiked, favoriteLoading, toggleCurrentTrackLiked, playbackSettings.playerCollapsed, setCollapsed)
                    }
                    BottomNavigation(destination) {
                        destination = it
                        selectedPlaylist = null
                        loadError = null
                        loading = false
                    }
                } else if (showPlayerBar) {
                    Divider(color = Color(0xFF2A343D))
                    PlayerBar(
                        state = playerState,
                        container = container,
                        similarModeActive = similarModeActive,
                        onSimilarModeChange = { similarModeActive = it },
                        onOpenQueue = openQueue,
                        isLiked = displayedTrackLiked,
                        favoriteLoading = favoriteLoading,
                        onToggleLiked = toggleCurrentTrackLiked,
                        collapsed = playbackSettings.playerCollapsed,
                        onCollapsedChange = setCollapsed,
                    )
                }
            }
            }

            if (createPlaylistDialogOpen) CreatePlaylistDialog(
                loading = playlistActionLoading,
                error = playlistActionError,
                onDismiss = { createPlaylistDialogOpen = false; playlistActionError = null },
                onConfirm = { name, description ->
                    scope.launch {
                        playlistActionLoading = true
                        playlistActionError = null
                        runCatching { container.spotifyRepository.createPlaylist(name, description) }
                            .onSuccess { created ->
                                savedPlaylists = savedPlaylists + created
                                createPlaylistDialogOpen = false
                            }
                            .onFailure { playlistActionError = it.message ?: unknownErrorLabel }
                        playlistActionLoading = false
                    }
                },
            )

            addToPlaylistTrack?.let { track ->
                AddToPlaylistDialog(
                    track = track,
                    playlists = savedPlaylists,
                    loading = playlistActionLoading,
                    error = playlistActionError,
                    onDismiss = { addToPlaylistTrack = null; playlistActionError = null },
                    onCreateNew = { addToPlaylistTrack = null; createPlaylistDialogOpen = true },
                    onConfirm = { playlist ->
                        scope.launch {
                            playlistActionLoading = true
                            playlistActionError = null
                            runCatching { container.spotifyRepository.addTracksToPlaylist(playlist.id, listOf(track)) }
                                .onSuccess { addToPlaylistTrack = null }
                                .onFailure { playlistActionError = it.message ?: unknownErrorLabel }
                            playlistActionLoading = false
                        }
                    },
                )
            }
        }
    }

@Composable
private fun CreatePlaylistDialog(
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.create_playlist_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.playlist_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(Res.string.playlist_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = MaterialTheme.colors.error, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim(), description.trim()) }, enabled = name.isNotBlank() && !loading) {
                Text(stringResource(Res.string.create))
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun AddToPlaylistDialog(
    track: Track,
    playlists: List<Playlist>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreateNew: () -> Unit,
    onConfirm: (Playlist) -> Unit,
) {
    var selected by remember { mutableStateOf<Playlist?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.add_to_playlist_title, track.title)) },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(stringResource(Res.string.no_playlists), color = Color(0xFF94A2AD), fontSize = 13.sp)
                } else {
                    ScrollableLazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (selected?.id == playlist.id) Color(0xFF203129) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { selected = playlist }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (selected?.id == playlist.id) "●" else "○", color = if (selected?.id == playlist.id) MaterialTheme.colors.primary else Color(0xFF7D8B95), modifier = Modifier.width(24.dp))
                                Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = MaterialTheme.colors.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onCreateNew, enabled = !loading) { Text(stringResource(Res.string.create_playlist_title)) }
            }
        },
        confirmButton = {
            Button(onClick = { selected?.let(onConfirm) }, enabled = selected != null && !loading) {
                Text(stringResource(Res.string.add))
            }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !loading) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun TopBar(compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Color(0xFF131A20)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Nuta", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.primary)
        Spacer(Modifier.width(12.dp))
        if (!compact) Text(stringResource(Res.string.app_tagline), color = Color(0xFF8D9BA6), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(
            "v${BuildInfo.VERSION_NAME} · ${BuildInfo.GIT_SHA}",
            color = MaterialTheme.colors.secondary,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun BottomNavigation(selected: Destination, onSelect: (Destination) -> Unit) {
    val labels = mapOf(
        Destination.HOME to stringResource(Res.string.nav_home),
        Destination.PLAYLISTS to stringResource(Res.string.nav_playlists),
        Destination.LIKED to stringResource(Res.string.nav_liked_short),
        Destination.SEARCH to stringResource(Res.string.nav_search_short),
        Destination.QUEUE to stringResource(Res.string.nav_queue),
        Destination.SETTINGS to stringResource(Res.string.nav_settings_short),
    )
    Row(Modifier.fillMaxWidth().height(58.dp).background(Color(0xFF131A20)).padding(horizontal = 4.dp)) {
        Destination.entries.filter { it != Destination.DIAGNOSTICS }.forEach { item ->
            val active = item == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(item) }
                    .background(if (active) Color(0xFF24332B) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labels.getValue(item),
                    color = if (active) MaterialTheme.colors.primary else Color(0xFFC5CFD7),
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// SettingsScreen, SettingsGroup i SettingOptions: patrz screens/SettingsScreen.kt

@Composable
private fun Sidebar(selected: Destination, onSelect: (Destination) -> Unit) {
    Column(Modifier.width(190.dp).fillMaxHeight().background(Color(0xFF131A20)).padding(16.dp)) {
        Text(stringResource(Res.string.sidebar_navigation), color = Color(0xFF788792), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Destination.entries.forEach { item ->
            val active = item == selected
            Box(
                Modifier.fillMaxWidth()
                    .background(if (active) Color(0xFF24332B) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Text(destinationLabel(item), color = if (active) MaterialTheme.colors.primary else Color(0xFFC5CFD7), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(stringResource(Res.string.sidebar_phase), color = Color(0xFF66737D), fontSize = 11.sp)
    }
}

@Composable
private fun destinationLabel(destination: Destination): String = stringResource(
    when (destination) {
        Destination.HOME -> Res.string.nav_home
        Destination.PLAYLISTS -> Res.string.nav_playlists
        Destination.LIKED -> Res.string.nav_liked
        Destination.SEARCH -> Res.string.nav_search
        Destination.QUEUE -> Res.string.nav_queue
        Destination.SETTINGS -> Res.string.nav_settings
        Destination.DIAGNOSTICS -> Res.string.nav_diagnostics
    },
)

// HomeScreen: patrz screens/HomeScreen.kt

// PlaylistsScreen i PlaylistDetails: patrz screens/PlaylistsScreen.kt

// LikedScreen: patrz screens/LikedScreen.kt

// SearchScreen i SearchScopeCheckbox: patrz screens/SearchScreen.kt

// QueueScreen: patrz screens/QueueScreen.kt
