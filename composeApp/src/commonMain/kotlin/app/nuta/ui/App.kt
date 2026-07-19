package app.nuta.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import app.nuta.resources.*
import app.nuta.AppContainer
import app.nuta.core.logging.LogEvent
import app.nuta.core.logging.LogLevel
import app.nuta.core.models.Destination
import app.nuta.core.models.Artist
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
import app.nuta.settings.BufferSize
import app.nuta.settings.LoudnessNormalization
import app.nuta.settings.CodecPreference
import app.nuta.settings.StreamQuality
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

private data class SearchViewState(
    val query: String = "",
    val result: SearchResult = SearchResult(emptyList(), emptyList()),
    val error: String? = null,
    val lastExecutedQuery: String = "",
    val youtubeStatus: String? = null,
    val searchTracks: Boolean = true,
    val searchArtists: Boolean = true,
    val searchPlaylists: Boolean = true,
)

@Composable
private fun ScrollableLazyColumn(
    modifier: Modifier = Modifier,
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    scrollToIndex: Int? = null,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToIndex) {
        scrollToIndex?.takeIf { it >= 0 }?.let { index ->
            listState.animateScrollToItem(index)
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

@Composable
fun NutaApp(container: AppContainer, onSpotifyLogin: (() -> Unit)? = null) {
    val settings by container.playbackSettings.settings.collectAsState()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, settings.fontScale)) {
    MaterialTheme(colors = NutaColors) {
        NutaAppContent(container, onSpotifyLogin)
    }
    }
}

@Composable
private fun NutaAppContent(container: AppContainer, onSpotifyLogin: (() -> Unit)?) {
        val playerState by container.audioPlayer.state.collectAsState()
        var destination by remember { mutableStateOf(Destination.HOME) }
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
        var favoriteLoading by remember { mutableStateOf(false) }
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

        LaunchedEffect(container.spotifyRepository) {
            container.logger.info("Application", "app_started", "Uruchomiono Nuta Linux GUI")
            runCatching { container.spotifyRepository.getPlaylists() }
                .onSuccess { playlists = it }
                .onFailure { loadError = it.message }
            loading = false
        }

        LaunchedEffect(destination, container.spotifyRepository) {
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

        LaunchedEffect(destination, container.spotifyRepository) {
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

        LaunchedEffect(playerState.currentTrack?.id, container.spotifyRepository) {
            val trackId = playerState.currentTrack?.id
            currentTrackLiked = false
            if (trackId == null) return@LaunchedEffect
            favoriteLoading = true
            runCatching { container.spotifyRepository.isTrackLiked(trackId) }
                .onSuccess { liked ->
                    if (playerState.currentTrack?.id == trackId) currentTrackLiked = liked
                }
                .onFailure { error ->
                    container.logger.warn(
                        "SpotifyLiked", "liked_status_failed", "Nie udało się sprawdzić, czy utwór jest w ulubionych",
                        fields = mapOf("reason" to (error::class.simpleName ?: "unknown")),
                    )
                }
            if (playerState.currentTrack?.id == trackId) favoriteLoading = false
        }

        val toggleCurrentTrackLiked = {
            val track = playerState.currentTrack
            if (track != null && !favoriteLoading) {
                val targetLiked = !currentTrackLiked
                scope.launch {
                    favoriteLoading = true
                    runCatching { container.spotifyRepository.setTrackLiked(track.id, targetLiked) }
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
                                fields = mapOf("reason" to (error::class.simpleName ?: "unknown")),
                            )
                        }
                    if (playerState.currentTrack?.id == track.id) favoriteLoading = false
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
                            selectedPlaylist != null -> PlaylistDetails(selectedPlaylist!!, playerState, container)
                            else -> when (destination) {
                                Destination.HOME -> HomeScreen(
                                    playlists = playlists,
                                    playerState = playerState,
                                    openPlaylists = { destination = Destination.PLAYLISTS },
                                    onSpotifyLogin = onSpotifyLogin,
                                    onSelectPlaylist = ::selectPlaylist,
                                )
                                Destination.PLAYLISTS -> PlaylistsScreen(savedPlaylists, ::selectPlaylist)
                                Destination.LIKED -> LikedScreen(likedTracks, likedLoading, likedError, playerState, container)
                                Destination.SEARCH -> SearchScreen(
                                    container = container,
                                    state = searchState,
                                    onStateChange = { searchState = it },
                                    onPlaylist = { playlist -> selectedPlaylist = playlist },
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
                if (compact) {
                    if (destination == Destination.QUEUE) {
                        Divider(color = Color(0xFF2A343D))
                        CompactPlayerBar(playerState, container, similarModeActive, { similarModeActive = it }, openQueue, displayedTrackLiked, favoriteLoading, toggleCurrentTrackLiked)
                    }
                    BottomNavigation(destination) {
                        destination = it
                        selectedPlaylist = null
                        loadError = null
                        loading = false
                    }
                } else if (destination == Destination.QUEUE) {
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
                    )
                }
            }
            }
        }
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
        Text("NUTA", color = MaterialTheme.colors.secondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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

@Composable
private fun SettingsScreen(container: AppContainer) {
    val settings by container.playbackSettings.settings.collectAsState()
    ScrollableLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading(stringResource(Res.string.settings_title), stringResource(Res.string.settings_subtitle)) }
        item {
            SettingsGroup(stringResource(Res.string.font_size_title), stringResource(Res.string.font_size_desc)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(settings.fontScale * 100).toInt()}%", modifier = Modifier.width(48.dp))
                    Slider(
                        value = settings.fontScale,
                        onValueChange = { container.playbackSettings.update(settings.copy(fontScale = it.coerceIn(0.5f, 1f))) },
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
                ) { container.playbackSettings.update(settings.copy(quality = it)) }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.codec_title), stringResource(Res.string.codec_desc)) {
                SettingOptions(
                    options = listOf(CodecPreference.AUTO to stringResource(Res.string.option_auto), CodecPreference.AAC to "AAC", CodecPreference.OPUS to "Opus"),
                    selected = settings.codec,
                ) { container.playbackSettings.update(settings.copy(codec = it)) }
            }
        }
        item {
            SettingsGroup(stringResource(Res.string.buffer_title), stringResource(Res.string.buffer_desc)) {
                SettingOptions(
                    options = listOf(BufferSize.SMALL to stringResource(Res.string.buffer_small), BufferSize.STANDARD to stringResource(Res.string.option_standard), BufferSize.LARGE to stringResource(Res.string.buffer_large)),
                    selected = settings.bufferSize,
                ) { container.playbackSettings.update(settings.copy(bufferSize = it)) }
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
                ) { container.playbackSettings.update(settings.copy(loudnessNormalization = it)) }
            }
        }
        item {
            Text(
                stringResource(Res.string.settings_footer),
                color = Color(0xFF8D9BA6),
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsGroup(title: String, description: String, content: @Composable () -> Unit) {
    Card(backgroundColor = Color(0xFF182027), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(description, color = Color(0xFF8D9BA6), fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun <T> SettingOptions(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            OutlinedButton(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    backgroundColor = if (active) Color(0xFF2F6B45) else Color.Transparent,
                    contentColor = if (active) Color.White else Color(0xFFB8C2C9),
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) { Text(label, fontSize = 11.sp, maxLines = 1) }
        }
    }
}

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
    openPlaylists: () -> Unit,
    onSpotifyLogin: (() -> Unit)?,
    onSelectPlaylist: (Playlist) -> Unit,
) {
    val recommendations = playlists.take(6)
    ScrollableLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        item {
        Card(backgroundColor = MaterialTheme.colors.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(Res.string.spotify_session_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.spotify_session_desc), color = Color(0xFFABB7C0))
                Spacer(Modifier.height(18.dp))
                Button(onClick = openPlaylists) { Text(stringResource(Res.string.open_playlists)) }
                if (onSpotifyLogin != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onSpotifyLogin) { Text(stringResource(Res.string.spotify_login_test)) }
                }
            }
        }
        }
    }
}

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
private fun PlaylistsScreen(playlists: List<Playlist>, onSelect: (Playlist) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(Res.string.library_title), stringResource(Res.string.library_subtitle))
        Spacer(Modifier.height(20.dp))
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
private fun PlaylistDetails(playlist: Playlist, playerState: PlayerState, container: AppContainer) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Heading(playlist.name, playlist.description)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { scope.launch { container.audioPlayer.setQueue(playlist.tracks); container.audioPlayer.play() } }) { Text(stringResource(Res.string.play_all)) }
        Spacer(Modifier.height(16.dp))
        ScrollableLazyColumn(Modifier.fillMaxSize()) {
            items(playlist.tracks, key = { it.id }) { track ->
                TrackRow(track, playerState.currentTrack?.id == track.id, loading = playerState.status == PlayerStatus.LOADING, onPlay = {
                    scope.launch {
                        container.audioPlayer.setQueue(playlist.tracks, playlist.tracks.indexOf(track))
                        container.audioPlayer.play()
                    }
                }, titleAction = {
                    TrackPlayButton { scope.launch {
                        container.audioPlayer.setQueue(playlist.tracks, playlist.tracks.indexOf(track))
                        container.audioPlayer.play()
                    } }
                }, subtitleAction = {
                    TrackQueueButton { scope.launch { container.audioPlayer.appendToQueue(listOf(track)) } }
                })
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
                Button(onClick = {
                    scope.launch {
                        container.audioPlayer.setQueue(tracks)
                        container.audioPlayer.play()
                    }
                }) { Text(stringResource(Res.string.liked_play_all, tracks.size)) }
                Spacer(Modifier.height(14.dp))
                ScrollableLazyColumn(Modifier.fillMaxSize(), scrollToIndex = tracks.indexOfFirst { it.id == playerState.currentTrack?.id }.takeIf { it >= 0 }) {
                    items(tracks, key = { "liked-${it.id}" }) { track ->
                        TrackRow(track, playerState.currentTrack?.id == track.id, loading = playerState.status == PlayerStatus.LOADING, onPlay = {
                            scope.launch {
                                container.audioPlayer.setQueue(tracks, tracks.indexOf(track))
                                container.audioPlayer.play()
                            }
                        }, titleAction = {
                            TrackPlayButton { scope.launch {
                                container.audioPlayer.setQueue(tracks, tracks.indexOf(track))
                                container.audioPlayer.play()
                            } }
                        }, subtitleAction = {
                            TrackQueueButton { scope.launch { container.audioPlayer.appendToQueue(listOf(track)) } }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    active: Boolean,
    loading: Boolean = false,
    onPlay: () -> Unit,
    titleAction: (@Composable () -> Unit)? = null,
    subtitleAction: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 520.dp
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) Color(0xFF203129) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active && loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colors.primary)
        } else {
            Text(if (active) "▶" else "♪", color = if (active) MaterialTheme.colors.primary else Color(0xFF7D8B95), modifier = Modifier.width(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, modifier = Modifier.weight(1f), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                titleAction?.invoke()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.artists.joinToString(), modifier = Modifier.weight(1f), color = Color(0xFF8F9CA6), fontSize = 12.sp)
                Text(formatTime(track.durationMs), color = Color(0xFF8F9CA6), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                subtitleAction?.invoke()
            }
        }
        if (!compact) Text(track.album, color = Color(0xFF8F9CA6), fontSize = 12.sp, modifier = Modifier.width(170.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    }
}

@Composable
private fun TrackPlayButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        contentPadding = PaddingValues(0.dp),
        border = null,
    ) { Text("▶") }
}

@Composable
private fun TrackQueueButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        contentPadding = PaddingValues(0.dp),
        border = null,
    ) { Text("＋") }
}

@Composable
private fun BufferingIndicator(color: Color = MaterialTheme.colors.primary) {
    val transition = rememberInfiniteTransition()
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 900
                0.25f at 0
                1f at 300
                0.25f at 900
            },
        ),
    )
    Row(Modifier.size(32.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { Text("●", color = color, fontSize = 7.sp, modifier = Modifier.alpha(alpha)) }
    }
}

@Composable
private fun SearchScreen(
    container: AppContainer,
    state: SearchViewState,
    onStateChange: (SearchViewState) -> Unit,
    onPlaylist: (Playlist) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playerState by container.audioPlayer.state.collectAsState()
    val currentState by rememberUpdatedState(state)
    val searchUnknownError = stringResource(Res.string.search_unknown_error)
    val preparingStreamLabel = stringResource(Res.string.preparing_stream)
    suspend fun playTrack(track: Track) {
        container.audioPlayer.setQueue(listOf(track), 0)
        container.audioPlayer.play()
    }

    LaunchedEffect(state.query, state.searchTracks, state.searchArtists, state.searchPlaylists, container.spotifyRepository) {
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
        runCatching { container.spotifyRepository.search(submittedQuery) }
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
        state.youtubeStatus?.takeIf { false }?.let {
            Card(backgroundColor = Color(0xFF202B32), modifier = Modifier.fillMaxWidth()) {
                Text(it, color = Color(0xFF8FE9AD), modifier = Modifier.padding(14.dp))
            }
            Spacer(Modifier.height(14.dp))
        }
        val visibleTracks = state.result.tracks.filter { track ->
            val titleMatches = state.searchTracks && track.title.contains(state.query, ignoreCase = true)
            val artistMatches = state.searchArtists && track.artists.any { it.contains(state.query, ignoreCase = true) }
            titleMatches || artistMatches
        }
        val visiblePlaylists = if (state.searchPlaylists) state.result.playlists else emptyList()
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
                            scope.launch {
                                onStateChange(state.copy(youtubeStatus = preparingStreamLabel))
                                playTrack(track)
                                onStateChange(state.copy(youtubeStatus = null))
                            }
                        }, titleAction = {
                            TrackPlayButton {
                                scope.launch {
                                    onStateChange(state.copy(youtubeStatus = preparingStreamLabel))
                                    playTrack(track)
                                    onStateChange(state.copy(youtubeStatus = null))
                                }
                            }
                            }, subtitleAction = {
                            TrackQueueButton { scope.launch { container.audioPlayer.appendToQueue(listOf(track)) } }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScopeCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, fontSize = 12.sp, color = Color(0xFFD5DCE1))
    }
}

@Composable
private fun DiagnosticsScreen(container: AppContainer) {
    val events by container.logger.events.collectAsState()
    val level by container.logger.minimumLevel.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(Res.string.diagnostics_title), stringResource(Res.string.diagnostics_subtitle))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.log_level), color = Color(0xFF9AA7B0))
            listOf(LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE).forEach { item ->
                OutlinedButton(
                    onClick = { container.logger.setMinimumLevel(item) },
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (level == item) Color(0xFF263A30) else Color.Transparent),
                ) { Text(item.name) }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { container.audioPlayer.simulateError() } }) { Text(stringResource(Res.string.simulate_error)) }
            OutlinedButton(onClick = container.logger::clear) { Text(stringResource(Res.string.clear)) }
        }
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth().weight(1f), backgroundColor = Color(0xFF0C1013), shape = RoundedCornerShape(10.dp)) {
            if (events.isEmpty()) EmptyState(stringResource(Res.string.no_events)) else ScrollableLazyColumn(Modifier.padding(10.dp).fillMaxSize(), reverseLayout = true) {
                items(events.reversed()) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun LogRow(item: LogEvent) {
    val color = when (item.level) {
        LogLevel.ERROR -> Color(0xFFFF7B7B)
        LogLevel.WARN -> Color(0xFFFFD37B)
        LogLevel.INFO -> Color(0xFF8BE9A8)
        LogLevel.DEBUG -> Color(0xFF9BA8FF)
        LogLevel.TRACE -> Color(0xFF88949D)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text(item.level.name.padEnd(5), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(55.dp))
            Text(item.module, color = Color(0xFFC4CED5), fontSize = 11.sp, modifier = Modifier.width(150.dp))
            Text(item.event, color = Color(0xFF93A1AB), fontSize = 11.sp)
        }
        Text(item.message, color = Color(0xFFD5DCE1), fontSize = 12.sp)
        if (item.fields.isNotEmpty()) Text(item.fields.entries.joinToString("  ") { "${it.key}=${it.value}" }, color = Color(0xFF6F7F89), fontSize = 10.sp)
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
) {
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    var radioLoading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().height(164.dp).background(Color(0xFF131A20)).padding(horizontal = 10.dp, vertical = 5.dp)) {
    Row(
        Modifier.fillMaxWidth().height(76.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(60.dp).clickable { onOpenQueue() }) { Cover(track?.title ?: "N", track?.imageUrl, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clickable { onOpenQueue() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(track?.title ?: stringResource(Res.string.nothing_playing), maxLines = 2, overflow = TextOverflow.Clip, softWrap = true, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(streamDescription(state), color = Color(0xFF8D9BA6), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp).padding(start = 4.dp))
            }
            Text(track?.artists?.joinToString() ?: stringResource(Res.string.choose_track), color = Color(0xFF8D9BA6), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Clip, softWrap = true)
        }
    }
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("⏮", modifier = Modifier.size(32.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.previous() } }, color = if (track != null) Color.White else Color(0xFF55616A), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("◀◀", modifier = Modifier.size(32.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) } }, color = if (track != null) Color.White else Color(0xFF55616A), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (state.status == PlayerStatus.LOADING) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colors.primary)
                }
            } else Text(if (state.status == PlayerStatus.PLAYING) "Ⅱ" else "▶", modifier = Modifier.size(32.dp).clickable(enabled = track != null) { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }, color = MaterialTheme.colors.primary, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text("▶▶", modifier = Modifier.size(32.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.seekTo((state.positionMs + 10_000).coerceAtMost(state.durationMs)) } }, color = if (track != null) Color.White else Color(0xFF55616A), fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("⏭", modifier = Modifier.size(32.dp).clickable(enabled = track != null) { scope.launch { container.audioPlayer.next() } }, color = if (track != null) Color.White else Color(0xFF55616A), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                if (favoriteLoading) "…" else if (isLiked) "♥" else "♡",
                modifier = Modifier.size(32.dp).clickable(enabled = track != null && !favoriteLoading) { onToggleLiked() },
                color = if (isLiked) Color(0xFFFF4D67) else if (track != null) Color.White else Color(0xFF55616A),
                fontSize = 18.sp,
            )
            Text(
                "⇄",
                color = when { state.shuffleEnabled -> Color.White; state.queue.size > 1 -> MaterialTheme.colors.primary; else -> Color(0xFF55616A) },
                modifier = Modifier.background(if (state.shuffleEnabled) Color(0xFF2F6B45) else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = state.queue.size > 1) { scope.launch { container.audioPlayer.shuffleUpcoming(); onOpenQueue() } }
                    .size(32.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
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
) {
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
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
        Spacer(Modifier.width(14.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.previous() } }, enabled = track != null, modifier = Modifier.size(42.dp), contentPadding = PaddingValues(0.dp)) { Text("⏮") }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }, enabled = track != null && state.status != PlayerStatus.LOADING, modifier = Modifier.size(42.dp), contentPadding = PaddingValues(0.dp)) {
            if (state.status == PlayerStatus.LOADING) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colors.onPrimary)
            else Text(if (state.status == PlayerStatus.PLAYING) "⏸" else "▶")
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.stop() } }, enabled = track != null && state.status != PlayerStatus.IDLE, modifier = Modifier.size(42.dp), contentPadding = PaddingValues(0.dp)) { Text("⏹") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.next() } }, enabled = track != null, modifier = Modifier.size(42.dp), contentPadding = PaddingValues(0.dp)) { Text("⏭") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = onToggleLiked,
            enabled = track != null && !favoriteLoading,
            modifier = Modifier.size(42.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isLiked) Color(0xFFFF4D67) else Color.White),
        ) { Text(if (favoriteLoading) "…" else if (isLiked) "♥" else "♡", fontSize = 18.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = { scope.launch { container.audioPlayer.shuffleUpcoming(); onOpenQueue() } },
            enabled = state.queue.size > 1,
            modifier = Modifier.size(42.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = if (state.shuffleEnabled) Color(0xFF2F6B45) else Color.Transparent,
                contentColor = if (state.shuffleEnabled) Color.White else MaterialTheme.colors.primary,
            ),
        ) { Text("⇄", fontWeight = FontWeight.Bold) }
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
            modifier = Modifier.size(42.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = if (similarModeActive) Color(0xFF2F6B45) else Color.Transparent,
                contentColor = if (similarModeActive) Color.White else MaterialTheme.colors.primary,
            ),
        ) { Text(if (radioLoading) "…" else "♬+") }
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
        Heading(stringResource(Res.string.nav_queue))
        Spacer(Modifier.height(14.dp))
        if (state.queue.isEmpty()) {
            EmptyState(stringResource(Res.string.queue_empty))
        } else {
            ScrollableLazyColumn(Modifier.fillMaxSize(), scrollToIndex = state.currentIndex) {
                    items(state.queue.indices.toList(), key = { index -> "queue-$index-${state.queue[index].id}" }) { index ->
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
                            Text(if (active) "▶" else "${index + 1}.", color = if (active) MaterialTheme.colors.primary else Color(0xFF7D8B95), modifier = Modifier.width(38.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Clip, softWrap = true, modifier = Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        buildString {
                                            append(item.artists.joinToString())
                                            if (item.album.isNotBlank()) append(" • ${item.album}")
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
                        }
                    }
            }
        }
    }
}

@Composable
private fun Cover(seed: String, imageUrl: String? = null, modifier: Modifier = Modifier.size(54.dp)) {
    val colors = listOf(Color(0xFF375B4A), Color(0xFF404A75), Color(0xFF704858), Color(0xFF685C38))
    val color = colors[(seed.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(modifier.background(color, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(seed.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        imageUrl?.let { PlatformRemoteImage(it, seed, Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Heading(title: String, subtitle: String? = null) {
    Column {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        subtitle?.takeIf(String::isNotBlank)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Color(0xFF8D9BA6))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Color(0xFF7E8D97), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Text(message, color = Color(0xFF81909A)) }
}

@Composable
private fun ErrorState(message: String) {
    Card(backgroundColor = Color(0xFF3A2225), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(Res.string.error_title), color = Color(0xFFFFA3A3), fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFFE6B9B9))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
