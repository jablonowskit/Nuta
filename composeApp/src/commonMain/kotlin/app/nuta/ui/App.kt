package app.nuta.ui

import app.nuta.core.BuildInfo
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.nuta.resources.*
import app.nuta.AppContainer
import app.nuta.ui.screens.DiagnosticsScreen
import app.nuta.ui.screens.SettingsScreen
import app.nuta.core.models.Destination
import app.nuta.core.models.Artist
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
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
private fun rememberPrefetchHandler(tracks: List<Track>, container: AppContainer): (IntRange) -> Unit {
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

@Composable
private fun HomeScreen(
    playlists: List<Playlist>,
    playerState: PlayerState,
    onSelectPlaylist: (Playlist) -> Unit,
) {
    // Zamiast stałej liczby z ustawień: pokazuj stopniowo więcej rekomendacji w miarę
    // przewijania listy w dół, dociągając kolejne partie z już pobranej puli.
    var revealedCount by remember { mutableStateOf(INITIAL_RECOMMENDATIONS_COUNT) }
    val recommendations = playlists.take(revealedCount)
    val onVisibleRangeChanged: (IntRange) -> Unit = { range ->
        // +1 bo pozycja 0 to nagłówek (Heading/statystyki), utwory zaczynają się od indeksu 1
        if (range.last >= revealedCount && revealedCount < playlists.size) {
            revealedCount = (revealedCount + RECOMMENDATIONS_PAGE_SIZE).coerceAtMost(playlists.size)
        }
    }
    ScrollableLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), onVisibleRangeChanged = onVisibleRangeChanged) {
        item {
        Heading(stringResource(Res.string.home_title), stringResource(Res.string.home_subtitle))
        Spacer(Modifier.height(24.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 520.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(stringResource(Res.string.stat_suggestions), recommendations.size.toString(), Modifier.weight(1f), compact = true)
                    StatCard(stringResource(Res.string.stat_tracks), recommendations.flatMap { it.tracks }.distinctBy { it.id }.size.toString(), Modifier.weight(1f), compact = true)
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(stringResource(Res.string.stat_suggestions), recommendations.size.toString(), Modifier.weight(1f))
                StatCard(stringResource(Res.string.stat_tracks), recommendations.flatMap { it.tracks }.distinctBy { it.id }.size.toString(), Modifier.weight(1f))
                StatCard(stringResource(Res.string.stat_player), playerState.status.name.lowercase(), Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
        }
        if (recommendations.isEmpty()) item { EmptyState(stringResource(Res.string.home_no_recommendations)) }
        items(recommendations, key = { "home-${it.id}" }) { playlist ->
            PlaylistCard(playlist) { onSelectPlaylist(playlist) }
        }
    }
}

private const val INITIAL_RECOMMENDATIONS_COUNT = 10
private const val RECOMMENDATIONS_PAGE_SIZE = 10

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier, compact: Boolean = false) {
    Card(modifier, backgroundColor = MaterialTheme.colors.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(if (compact) 12.dp else 20.dp)) {
            Text(label, color = Color(0xFF8D9BA6), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PlaylistsScreen(playlists: List<Playlist>, onSelect: (Playlist) -> Unit, onCreatePlaylist: () -> Unit) {
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
private fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 520.dp
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        backgroundColor = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(playlist.name, playlist.imageUrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(playlist.description, color = Color(0xFF94A2AD), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!compact) Text(pluralStringResource(Res.plurals.track_count, playlist.tracks.size, playlist.tracks.size), color = Color(0xFF7F8E99), fontSize = 12.sp)
        }
    }
    }
}

@Composable
private fun ArtistSearchCard(artist: Artist, onPlay: () -> Unit) {
    Card(
        backgroundColor = MaterialTheme.colors.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onPlay),
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackPlayButton(onPlay)
            Spacer(Modifier.width(10.dp))
            Text(artist.name, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PlaylistDetails(playlist: Playlist, playerState: PlayerState, container: AppContainer, onAddToPlaylist: (Track) -> Unit) {
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

@Composable
private fun LikedScreen(
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

@Composable
private fun SearchScreen(
    container: AppContainer,
    state: SearchViewState,
    onStateChange: (SearchViewState) -> Unit,
    onPlaylist: (Playlist) -> Unit,
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
                    val titleMatches = state.searchTracks && track.title.contains(word, ignoreCase = true)
                    val artistMatches = state.searchArtists && track.artists.any { it.contains(word, ignoreCase = true) }
                    titleMatches || artistMatches
                }
            }
        }
        val visiblePlaylists = if (state.searchPlaylists) state.result.playlists else emptyList()
        val searchPlaybackSettings by container.playbackSettings.settings.collectAsState()
        LaunchedEffect(visibleTracks, searchPlaybackSettings.prefetchEnabled) {
            if (searchPlaybackSettings.prefetchEnabled) container.audioPlayer.prefetch(visibleTracks)
        }
        if (state.error != null) ErrorState(state.error) else if (state.query.isNotBlank() && visibleTracks.isEmpty() && visiblePlaylists.isEmpty()) {
            EmptyState(stringResource(Res.string.search_no_results, state.query))
        } else {
            ScrollableLazyColumn(Modifier.fillMaxSize()) {
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

// DiagnosticsScreen i LogRow: patrz screens/DiagnosticsScreen.kt

/** Komplet przycisków sterowania — ten sam w pasku rozwiniętym i zwiniętym. */
@Composable
private fun CompactTransportRow(
    state: PlayerState,
    container: AppContainer,
    isLiked: Boolean,
    favoriteLoading: Boolean,
    onToggleLiked: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            // Box(contentAlignment = Center) zamiast samego textAlign na Text — textAlign centruje
            // tylko poziomo. Różne glify (⏸ vs ⏮ vs ♡) mają różne metryki czcionki (ascent/descent),
            // więc bez jawnego wyśrodkowania w Boxie każdy z nich "siadał" na innej wysokości mimo
            // identycznego Modifier.size(40.dp) na samym Tekście.
            Box(Modifier.size(40.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.previous() } }, contentAlignment = Alignment.Center) {
                Text("⏮", color = if (track != null) Color.White else Color(0xFF55616A), fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
            Box(Modifier.size(40.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) } }, contentAlignment = Alignment.Center) {
                Text("⏪︎", color = if (track != null) Color.White else Color(0xFF55616A), fontSize = 28.sp)
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (state.status == PlayerStatus.LOADING) {
                    Text("⏳︎", color = MaterialTheme.colors.primary, fontSize = 30.sp)
                } else {
                    Box(Modifier.fillMaxSize().clickable(enabled = track != null) { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }, contentAlignment = Alignment.Center) {
                        Text(if (state.status == PlayerStatus.PLAYING) "⏸" else "▶", color = MaterialTheme.colors.primary, fontSize = 30.sp)
                    }
                }
            }
            Box(Modifier.size(40.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.seekTo((state.positionMs + 10_000).coerceAtMost(state.durationMs)) } }, contentAlignment = Alignment.Center) {
                Text("⏩︎", color = if (track != null) Color.White else Color(0xFF55616A), fontSize = 28.sp)
            }
            Box(Modifier.size(40.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.next() } }, contentAlignment = Alignment.Center) {
                Text("⏭", color = if (track != null) Color.White else Color(0xFF55616A), fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
            Box(Modifier.size(40.dp).clickable(enabled = track != null && !favoriteLoading) { onToggleLiked() }, contentAlignment = Alignment.Center) {
                Text(
                    if (favoriteLoading) "…" else if (isLiked) "♥" else "♡",
                    color = if (isLiked) Color(0xFFFF4D67) else if (track != null) Color.White else Color(0xFF55616A),
                    fontSize = 32.sp,
                )
            }
            Box(
                Modifier.size(40.dp)
                    .background(if (state.shuffleEnabled) Color(0xFF2F6B45) else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = state.queue.size > 1) { scope.launch { container.audioPlayer.shuffleUpcoming(); onOpenQueue() } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⇄",
                    color = when { state.shuffleEnabled -> Color.White; state.queue.size > 1 -> MaterialTheme.colors.primary; else -> Color(0xFF55616A) },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun CompactPlayerBar(
    state: PlayerState,
    container: AppContainer,
    similarModeActive: Boolean,
    onSimilarModeChange: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    isLiked: Boolean,
    favoriteLoading: Boolean,
    onToggleLiked: () -> Unit,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    var radioLoading by remember { mutableStateOf(false) }
    val dragThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    var dragAccumulated by remember { mutableStateOf(0f) }
    // Bez stałej wysokości: tytuł i wykonawca mogą zająć do 2 linii każdy (patrz niżej),
    // a przy stałych 164dp/76dp długi tekst nachodziłby na przyciski zamiast rozepchnąć układ.
    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF131A20))
            .pointerInput(collapsed) {
                // Palec w dół zwija pasek do jednej linii, w górę rozwija. Pasek leży poza
                // LazyColumn treści, więc gest nie konkuruje ze scrollem listy.
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulated = 0f },
                    onDragEnd = {
                        if (dragAccumulated > dragThresholdPx) onCollapsedChange(true)
                        else if (dragAccumulated < -dragThresholdPx) onCollapsedChange(false)
                        dragAccumulated = 0f
                    },
                ) { _, dy -> dragAccumulated += dy }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .animateContentSize(),
    ) {
    // Uchwyt: sygnalizuje, że pasek da się przeciągnąć, i sam działa jako tap-toggle.
    Box(
        Modifier.fillMaxWidth().height(14.dp).clickable { onCollapsedChange(!collapsed) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(4.dp).background(Color(0xFF3A4650), RoundedCornerShape(2.dp)))
    }
    if (collapsed) {
        // Zwinięty pasek to jedna linia: tytuł (skrócony) plus pełny komplet przycisków —
        // rezygnujemy tylko z wykonawcy, sliderem pozycji i etykiet kodeka/bitrate'u.
        Text(
            track?.title ?: stringResource(Res.string.nothing_playing),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().clickable { onOpenQueue() },
        )
        CompactTransportRow(state, container, isLiked, favoriteLoading, onToggleLiked, onOpenQueue)
        return@Column
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable { onOpenQueue() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(track?.title ?: stringResource(Res.string.nothing_playing), maxLines = 2, overflow = TextOverflow.Clip, softWrap = true, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                streamBitrateLabel(state)?.let {
                    Text(it, color = Color(0xFF8D9BA6), fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(track?.artists?.joinToString() ?: stringResource(Res.string.choose_track), color = Color(0xFF8D9BA6), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Clip, softWrap = true, modifier = Modifier.weight(1f))
                streamCodecLabel(state)?.let {
                    Text(it, color = Color(0xFF8D9BA6), fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
    CompactTransportRow(state, container, isLiked, favoriteLoading, onToggleLiked, onOpenQueue)
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(state.positionMs), color = Color(0xFF8D9BA6), fontSize = 10.sp)
        Slider(
            value = if (state.durationMs > 0) state.positionMs.coerceAtMost(state.durationMs).toFloat() else 0f,
            onValueChange = { scope.launch { container.audioPlayer.seekTo(it.toLong()) } },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = track != null,
            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
        )
        Text(formatTime(state.durationMs), color = Color(0xFF8D9BA6), fontSize = 10.sp)
        Text(
            if (radioLoading) "…" else "♬+",
            color = if (similarModeActive) Color.White else MaterialTheme.colors.primary,
            modifier = Modifier.padding(start = 8.dp).background(if (similarModeActive) Color(0xFF2F6B45) else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(enabled = track != null && !radioLoading) {
                    if (similarModeActive) onSimilarModeChange(false) else track?.let { seed ->
                        scope.launch {
                            radioLoading = true
                            runCatching { container.spotifyRepository.getTrackRadio(seed) }.onSuccess { recommendations ->
                                val additions = recommendations.filterNot { candidate -> state.queue.any { it.id == candidate.id } }
                                if (state.queue.isEmpty()) container.audioPlayer.setQueue(listOf(seed) + additions, 0)
                                else container.audioPlayer.appendToQueue(additions)
                                onSimilarModeChange(true); onOpenQueue()
                            }
                            radioLoading = false
                        }
                    }
                }.padding(horizontal = 10.dp, vertical = 6.dp),
            fontWeight = FontWeight.Bold,
        )
    }
    }
}

private fun playerSubtitle(track: Track, state: PlayerState): String {
    val stream = streamDescription(state).takeIf(String::isNotBlank)
    return listOfNotNull(track.artists.joinToString().takeIf(String::isNotBlank), stream).joinToString(" • ")
}

private fun streamCodecLabel(state: PlayerState): String? = state.streamBitrate?.takeIf { it > 0 }?.let {
    when {
        state.streamCodec.orEmpty().contains("mp4a", ignoreCase = true) -> "AAC"
        state.streamCodec.orEmpty().contains("opus", ignoreCase = true) -> "Opus"
        state.streamCodec.isNullOrBlank() -> null
        else -> state.streamCodec
    }
}

private fun streamBitrateLabel(state: PlayerState): String? = state.streamBitrate?.takeIf { it > 0 }?.let { bitrate ->
    "${(bitrate + 500) / 1_000} kb/s"
}

private fun streamDescription(state: PlayerState): String = state.streamBitrate?.takeIf { it > 0 }?.let { bitrate ->
    val codec = when {
        state.streamCodec.orEmpty().contains("mp4a", ignoreCase = true) -> "AAC"
        state.streamCodec.orEmpty().contains("opus", ignoreCase = true) -> "Opus"
        state.streamCodec.isNullOrBlank() -> null
        else -> state.streamCodec
    }
    listOfNotNull(codec, "${(bitrate + 500) / 1_000} kb/s").joinToString(" • ")
}.orEmpty()

@Composable
private fun PlayerBar(
    state: PlayerState,
    container: AppContainer,
    similarModeActive: Boolean,
    onSimilarModeChange: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    isLiked: Boolean,
    favoriteLoading: Boolean,
    onToggleLiked: () -> Unit,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    if (collapsed) {
        // Na desktopie gest przeciągania nie ma sensu — zwijanie/rozwijanie idzie przyciskiem ▴/▾.
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(Color(0xFF131A20)).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                track?.title ?: stringResource(Res.string.nothing_playing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).clickable { onOpenQueue() },
            )
            Text(
                if (state.status == PlayerStatus.LOADING) "⏳︎" else if (state.status == PlayerStatus.PLAYING) "⏸" else "▶",
                color = MaterialTheme.colors.primary,
                fontSize = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
                    .clickable(enabled = track != null && state.status != PlayerStatus.LOADING) {
                        scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() }
                    },
            )
            Text("▴", fontSize = 18.sp, modifier = Modifier.clickable { onCollapsedChange(false) })
        }
        return
    }
    var radioLoading by remember { mutableStateOf(false) }
    var radioMessage by remember { mutableStateOf<String?>(null) }
    var radioMessageIsError by remember { mutableStateOf(false) }
    val radioDisabledLabel = stringResource(Res.string.radio_disabled)
    val radioAddedPrefix = stringResource(Res.string.radio_added_prefix)
    val radioFailedPrefix = stringResource(Res.string.radio_failed_prefix)
    val unknownErrorLabel = stringResource(Res.string.unknown_error)
    Row(
        Modifier.fillMaxWidth().height(82.dp).background(Color(0xFF131A20)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track?.title ?: "N", track?.imageUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(230.dp)) {
            Text(track?.title ?: stringResource(Res.string.nothing_playing), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
            Text(track?.let { playerSubtitle(it, state) } ?: stringResource(Res.string.choose_track), color = Color(0xFF8D9BA6), fontSize = 12.sp, maxLines = 1, lineHeight = 14.sp)
        }
        Text("▾", fontSize = 18.sp, modifier = Modifier.padding(start = 4.dp).clickable { onCollapsedChange(true) })
        Spacer(Modifier.width(14.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.previous() } }, enabled = track != null, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) { Text("⏮", fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }, enabled = track != null && state.status != PlayerStatus.LOADING, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) {
            if (state.status == PlayerStatus.LOADING) Text("⏳︎", fontSize = 28.sp)
            else Text(if (state.status == PlayerStatus.PLAYING) "⏸" else "▶", fontSize = 28.sp)
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.stop() } }, enabled = track != null && state.status != PlayerStatus.IDLE, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) { Text("⏹", fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.next() } }, enabled = track != null, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) { Text("⏭", fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = onToggleLiked,
            enabled = track != null && !favoriteLoading,
            modifier = Modifier.size(74.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isLiked) Color(0xFFFF4D67) else Color.White),
        ) { Text(if (favoriteLoading) "…" else if (isLiked) "♥" else "♡", fontSize = 30.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = { scope.launch { container.audioPlayer.shuffleUpcoming(); onOpenQueue() } },
            enabled = state.queue.size > 1,
            modifier = Modifier.size(74.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = if (state.shuffleEnabled) Color(0xFF2F6B45) else Color.Transparent,
                contentColor = if (state.shuffleEnabled) Color.White else MaterialTheme.colors.primary,
            ),
        ) { Text("⇄", fontWeight = FontWeight.Bold, fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = {
                if (similarModeActive) {
                    onSimilarModeChange(false)
                    radioMessage = radioDisabledLabel
                    radioMessageIsError = false
                    return@OutlinedButton
                }
                val seed = track ?: return@OutlinedButton
                scope.launch {
                    radioLoading = true
                    radioMessage = null
                    runCatching { container.spotifyRepository.getTrackRadio(seed) }
                        .onSuccess { recommendations ->
                            val queue = recommendations
                            val additions = recommendations.filterNot { candidate -> state.queue.any { it.id == candidate.id } }
                            if (state.queue.isEmpty()) container.audioPlayer.setQueue(listOf(seed) + additions, 0)
                            else container.audioPlayer.appendToQueue(additions)
                            radioMessage = "$radioAddedPrefix ${queue.size}"
                            radioMessageIsError = false
                            onSimilarModeChange(true)
                            onOpenQueue()
                        }
                        .onFailure {
                            radioMessage = "$radioFailedPrefix ${it.message ?: unknownErrorLabel}"
                            radioMessageIsError = true
                        }
                    radioLoading = false
                }
            },
            enabled = track != null && !radioLoading,
            modifier = Modifier.size(74.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = if (similarModeActive) Color(0xFF2F6B45) else Color.Transparent,
                contentColor = if (similarModeActive) Color.White else MaterialTheme.colors.primary,
            ),
        ) { Text(if (radioLoading) "…" else "♬+", fontSize = 22.sp) }
        Spacer(Modifier.width(18.dp))
        Text(formatTime(state.positionMs), color = Color(0xFF8D9BA6), fontSize = 11.sp)
        Slider(
            value = if (state.durationMs > 0) state.positionMs.toFloat() else 0f,
            onValueChange = { value -> scope.launch { container.audioPlayer.seekTo(value.toLong()) } },
            valueRange = 0f..(state.durationMs.coerceAtLeast(1L).toFloat()),
            enabled = track != null,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(formatTime(state.durationMs), color = Color(0xFF8D9BA6), fontSize = 11.sp)
        Spacer(Modifier.width(14.dp))
        Text(
            when (state.status) {
                PlayerStatus.LOADING -> stringResource(Res.string.status_buffering)
                PlayerStatus.PLAYING -> stringResource(Res.string.status_playing)
                PlayerStatus.PAUSED -> stringResource(Res.string.status_paused)
                PlayerStatus.ERROR -> stringResource(Res.string.status_error)
                else -> state.status.name.lowercase()
            },
            color = if (state.status == PlayerStatus.ERROR) Color(0xFFFF7B7B) else MaterialTheme.colors.primary,
            fontSize = 11.sp,
        )
        radioMessage?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = if (radioMessageIsError) Color(0xFFFF7B7B) else Color(0xFF8FE9AD), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun QueueScreen(state: PlayerState, container: AppContainer) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Heading(stringResource(Res.string.nav_queue)) }
            if (state.queue.isNotEmpty()) {
                OutlinedButton(onClick = { scope.launch { container.audioPlayer.clearQueue() } }) {
                    Text(stringResource(Res.string.clear_queue))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (state.queue.isEmpty()) {
            EmptyState(stringResource(Res.string.queue_empty))
        } else {
            // Klucz bez indeksu, żeby zmiana kolejności (shuffle, usunięcie utworu) nie
            // unieważniała wszystkich kolejnych wierszy. Ten sam utwór może wystąpić w
            // kolejce wielokrotnie, więc numerujemy powtórzenia.
            val queueKeys = remember(state.queue) {
                val seen = mutableMapOf<String, Int>()
                state.queue.map { track ->
                    val occurrence = seen.getOrElse(track.id) { 0 }
                    seen[track.id] = occurrence + 1
                    "queue-${track.id}-$occurrence"
                }
            }
            ScrollableLazyColumn(Modifier.fillMaxSize(), scrollToIndex = state.currentIndex) {
                    items(state.queue.indices.toList(), key = { index -> queueKeys[index] }) { index ->
                        val item = state.queue[index]
                        val active = index == state.currentIndex
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (active) Color(0xFF203129) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    scope.launch { container.audioPlayer.playAt(index) }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when {
                                    !active -> "${index + 1}."
                                    state.status == PlayerStatus.LOADING -> "⏳︎"
                                    state.status == PlayerStatus.ERROR -> "⚠︎"
                                    else -> "▶"
                                },
                                color = when {
                                    !active -> Color(0xFF7D8B95)
                                    state.status == PlayerStatus.ERROR -> Color(0xFFFF7B7B)
                                    else -> MaterialTheme.colors.primary
                                },
                                modifier = Modifier.width(38.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Clip, softWrap = true, modifier = Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        buildString {
                                            append(item.artists.joinToString())
                                            // Utwory rozwiązywane z YouTube często nie mają prawdziwego albumu —
                                            // pole album bywa wtedy wypełnione tytułem utworu, co dawało widoczne powtórzenie.
                                            if (item.album.isNotBlank() && !item.album.equals(item.title, ignoreCase = true)) {
                                                append(" • ${item.album}")
                                            }
                                        },
                                        color = Color(0xFF8F9CA6),
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(formatTime(item.durationMs), color = Color(0xFF8F9CA6), fontSize = 12.sp)
                                }
                            }
                            Text(
                                "✕",
                                color = Color(0xFF7D8B95),
                                modifier = Modifier.size(32.dp).clickable { scope.launch { container.audioPlayer.removeFromQueue(index) } },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
            }
        }
    }
}

// Cover/Heading/SectionLabel/EmptyState/ErrorState oraz formatery czasu i rozmiaru:
// patrz CommonComponents.kt
