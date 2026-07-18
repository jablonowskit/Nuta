package app.nuta.ui

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
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.AppContainer
import app.nuta.core.logging.LogEvent
import app.nuta.core.logging.LogLevel
import app.nuta.core.models.Destination
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Playlist
import app.nuta.core.models.SearchResult
import app.nuta.core.models.Track
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
    MaterialTheme(colors = NutaColors) {
        val playerState by container.audioPlayer.state.collectAsState()
        var destination by remember { mutableStateOf(Destination.HOME) }
        var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
        var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }
        var searchState by remember { mutableStateOf(SearchViewState()) }
        var likedTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
        var likedLoading by remember { mutableStateOf(false) }
        var likedLoaded by remember { mutableStateOf(false) }
        var likedError by remember { mutableStateOf<String?>(null) }
        var similarModeActive by remember { mutableStateOf(false) }
        var similarModeLoading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

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

        LaunchedEffect(destination, container.spotifyRepository) {
            if (destination != Destination.LIKED || likedLoaded || likedLoading) return@LaunchedEffect
            likedLoading = true
            likedError = null
            runCatching { container.spotifyRepository.getLikedTracks() }
                .onSuccess {
                    likedTracks = it
                    likedLoaded = true
                }
                .onFailure { likedError = it.message ?: "Nie udało się pobrać ulubionych" }
            likedLoading = false
        }

        Surface(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = MaterialTheme.colors.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 700.dp
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
                            loadError != null -> ErrorState(loadError ?: "Nieznany błąd")
                            selectedPlaylist != null -> PlaylistDetails(selectedPlaylist!!, playerState, container)
                            else -> when (destination) {
                                Destination.HOME -> HomeScreen(
                                    playlists = playlists,
                                    playerState = playerState,
                                    openPlaylists = { destination = Destination.PLAYLISTS },
                                    onSpotifyLogin = onSpotifyLogin,
                                    onSelectPlaylist = ::selectPlaylist,
                                )
                                Destination.PLAYLISTS -> PlaylistsScreen(playlists, ::selectPlaylist)
                                Destination.LIKED -> LikedScreen(likedTracks, likedLoading, likedError, playerState, container)
                                Destination.SEARCH -> SearchScreen(
                                    container = container,
                                    state = searchState,
                                    onStateChange = { searchState = it },
                                    onPlaylist = { playlist -> selectedPlaylist = playlist },
                                )
                                Destination.QUEUE -> QueueScreen(playerState, container)
                                Destination.DIAGNOSTICS -> DiagnosticsScreen(container)
                            }
                        }
                    }
                }
                Divider(color = Color(0xFF2A343D))
                val openQueue = {
                    destination = Destination.QUEUE
                    selectedPlaylist = null
                    loadError = null
                }
                if (compact) {
                    CompactPlayerBar(playerState, container, similarModeActive, { similarModeActive = it }, openQueue)
                    BottomNavigation(destination) {
                        destination = it
                        selectedPlaylist = null
                        loadError = null
                        loading = false
                    }
                } else PlayerBar(playerState, container, similarModeActive, onSimilarModeChange = { similarModeActive = it }, onOpenQueue = openQueue)
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
        if (!compact) Text("Spotify Web + YouTube", color = Color(0xFF8D9BA6), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(if (compact) "ANDROID" else "NUTA", color = MaterialTheme.colors.secondary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun BottomNavigation(selected: Destination, onSelect: (Destination) -> Unit) {
    val labels = mapOf(
        Destination.HOME to "Start", Destination.PLAYLISTS to "Listy", Destination.LIKED to "Lubię",
        Destination.SEARCH to "Szukaj", Destination.QUEUE to "Kolejka", Destination.DIAGNOSTICS to "Logi",
    )
    Row(Modifier.fillMaxWidth().height(58.dp).background(Color(0xFF131A20)).padding(horizontal = 4.dp)) {
        Destination.entries.forEach { item ->
            val active = item == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(item) }
                    .background(if (active) Color(0xFF24332B) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(labels.getValue(item), color = if (active) MaterialTheme.colors.primary else Color(0xFFC5CFD7), fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Destination, onSelect: (Destination) -> Unit) {
    Column(Modifier.width(190.dp).fillMaxHeight().background(Color(0xFF131A20)).padding(16.dp)) {
        Text("NAWIGACJA", color = Color(0xFF788792), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Destination.entries.forEach { item ->
            val active = item == selected
            Box(
                Modifier.fillMaxWidth()
                    .background(if (active) Color(0xFF24332B) else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Text(item.label, color = if (active) MaterialTheme.colors.primary else Color(0xFFC5CFD7), fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("Faza 1 • Linux-first", color = Color(0xFF66737D), fontSize = 11.sp)
    }
}

@Composable
private fun HomeScreen(
    playlists: List<Playlist>,
    playerState: PlayerState,
    openPlaylists: () -> Unit,
    onSpotifyLogin: (() -> Unit)?,
    onSelectPlaylist: (Playlist) -> Unit,
) {
    ScrollableLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
        Heading("Dla Ciebie", "Rekomendacje z głównej strony Spotify")
        Spacer(Modifier.height(24.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 520.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("Playlisty", playlists.size.toString(), Modifier.weight(1f), compact = true)
                    StatCard("Utwory", playlists.flatMap { it.tracks }.distinctBy { it.id }.size.toString(), Modifier.weight(1f), compact = true)
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard("Playlisty", playlists.size.toString(), Modifier.weight(1f))
                StatCard("Utwory", playlists.flatMap { it.tracks }.distinctBy { it.id }.size.toString(), Modifier.weight(1f))
                StatCard("Player", playerState.status.name.lowercase(), Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
        }
        if (playlists.isEmpty()) item { EmptyState("Spotify nie zwrócił rekomendowanych playlist") }
        items(playlists, key = { "home-${it.id}" }) { playlist ->
            PlaylistCard(playlist) { onSelectPlaylist(playlist) }
        }
        item {
        Card(backgroundColor = MaterialTheme.colors.surface, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text("Sesja Spotify", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text("Rekomendacje są pobierane z zalogowanej sesji Spotify Web.", color = Color(0xFFABB7C0))
                Spacer(Modifier.height(18.dp))
                Button(onClick = openPlaylists) { Text("Otwórz playlisty") }
                if (onSpotifyLogin != null) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onSpotifyLogin) { Text("Test logowania Spotify") }
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
        Heading("Playlisty", "Biblioteka demonstracyjna")
        Spacer(Modifier.height(20.dp))
        if (playlists.isEmpty()) EmptyState("Brak playlist") else ScrollableLazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Cover(playlist.name)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(playlist.description, color = Color(0xFF94A2AD), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!compact) Text("${playlist.tracks.size} utworów", color = Color(0xFF7F8E99), fontSize = 12.sp)
        }
    }
    }
}

@Composable
private fun PlaylistDetails(playlist: Playlist, playerState: PlayerState, container: AppContainer) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Heading(playlist.name, playlist.description)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { scope.launch { container.audioPlayer.setQueue(playlist.tracks); container.audioPlayer.play() } }) { Text("Odtwórz całość") }
        Spacer(Modifier.height(16.dp))
        ScrollableLazyColumn(Modifier.fillMaxSize()) {
            items(playlist.tracks, key = { it.id }) { track ->
                TrackRow(track, playerState.currentTrack?.id == track.id) {
                    scope.launch {
                        container.audioPlayer.setQueue(playlist.tracks, playlist.tracks.indexOf(track))
                        container.audioPlayer.play()
                    }
                }
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
        Heading("Ulubione", "Utwory zapisane na Twoim koncie Spotify")
        Spacer(Modifier.height(16.dp))
        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error != null -> ErrorState(error)
            tracks.isEmpty() -> EmptyState("Nie znaleziono ulubionych utworów")
            else -> {
                Button(onClick = {
                    scope.launch {
                        container.audioPlayer.setQueue(tracks)
                        container.audioPlayer.play()
                    }
                }) { Text("▶ Odtwórz wszystkie (${tracks.size})") }
                Spacer(Modifier.height(14.dp))
                ScrollableLazyColumn(Modifier.fillMaxSize(), scrollToIndex = tracks.indexOfFirst { it.id == playerState.currentTrack?.id }.takeIf { it >= 0 }) {
                    items(tracks, key = { "liked-${it.id}" }) { track ->
                        TrackRow(track, playerState.currentTrack?.id == track.id) {
                            scope.launch {
                                container.audioPlayer.setQueue(tracks, tracks.indexOf(track))
                                container.audioPlayer.play()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, active: Boolean, onPlay: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 520.dp
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) Color(0xFF203129) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (active) "▶" else "♪", color = if (active) MaterialTheme.colors.primary else Color(0xFF7D8B95), modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            Text(track.artists.joinToString(), color = Color(0xFF8F9CA6), fontSize = 12.sp)
        }
        if (!compact) Text(track.album, color = Color(0xFF8F9CA6), fontSize = 12.sp, modifier = Modifier.width(170.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatTime(track.durationMs), color = Color(0xFF8F9CA6), fontSize = 12.sp)
    }
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

    LaunchedEffect(state.query, container.spotifyRepository) {
        val submittedQuery = state.query
        if (submittedQuery == state.lastExecutedQuery) return@LaunchedEffect
        if (submittedQuery.isBlank()) {
            onStateChange(state.copy(
                result = SearchResult(emptyList(), emptyList()),
                error = null,
                lastExecutedQuery = submittedQuery,
            ))
            return@LaunchedEffect
        }
        delay(400)
        runCatching { container.spotifyRepository.search(submittedQuery) }
            .onSuccess { onStateChange(state.copy(result = it, error = null, lastExecutedQuery = submittedQuery)) }
            .onFailure { onStateChange(state.copy(error = it.message ?: "Nieznany błąd wyszukiwania", lastExecutedQuery = submittedQuery)) }
    }

    Column(Modifier.fillMaxSize()) {
        Heading("Wyszukiwanie", "Przeszukuje lokalne dane demonstracyjne")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = { onStateChange(state.copy(query = it)) },
            label = { Text("Tytuł, wykonawca lub playlista") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(18.dp))
        state.youtubeStatus?.let {
            Card(backgroundColor = Color(0xFF202B32), modifier = Modifier.fillMaxWidth()) {
                Text(it, color = Color(0xFF8FE9AD), modifier = Modifier.padding(14.dp))
            }
            Spacer(Modifier.height(14.dp))
        }
        if (state.error != null) ErrorState(state.error) else if (state.query.isNotBlank() && state.result.tracks.isEmpty() && state.result.playlists.isEmpty()) {
            EmptyState("Brak wyników dla „${state.query}”")
        } else {
            ScrollableLazyColumn(Modifier.fillMaxSize()) {
                if (state.result.playlists.isNotEmpty()) {
                    item { SectionLabel("PLAYLISTY") }
                    items(state.result.playlists, key = { "p-${it.id}" }) { PlaylistCard(it) { onPlaylist(it) } }
                    item { Spacer(Modifier.height(18.dp)) }
                }
                if (state.result.tracks.isNotEmpty()) {
                    item { SectionLabel("UTWORY") }
                    items(state.result.tracks, key = { "t-${it.id}" }) { track ->
                        TrackRow(track, playerState.currentTrack?.id == track.id) {
                            scope.launch {
                                onStateChange(state.copy(youtubeStatus = "Player: przygotowywanie strumienia YouTube…"))
                                container.audioPlayer.setQueue(state.result.tracks, state.result.tracks.indexOf(track))
                                container.audioPlayer.play()
                                onStateChange(state.copy(youtubeStatus = null))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(container: AppContainer) {
    val events by container.logger.events.collectAsState()
    val level by container.logger.minimumLevel.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Heading("Diagnostyka", "Logi strukturalne i symulacja problemów")
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Poziom:", color = Color(0xFF9AA7B0))
            listOf(LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE).forEach { item ->
                OutlinedButton(
                    onClick = { container.logger.setMinimumLevel(item) },
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (level == item) Color(0xFF263A30) else Color.Transparent),
                ) { Text(item.name) }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { container.audioPlayer.simulateError() } }) { Text("Symuluj błąd") }
            OutlinedButton(onClick = container.logger::clear) { Text("Wyczyść") }
        }
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth().weight(1f), backgroundColor = Color(0xFF0C1013), shape = RoundedCornerShape(10.dp)) {
            if (events.isEmpty()) EmptyState("Brak zdarzeń") else ScrollableLazyColumn(Modifier.padding(10.dp).fillMaxSize(), reverseLayout = true) {
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
private fun CompactPlayerBar(state: PlayerState, container: AppContainer, similarModeActive: Boolean, onSimilarModeChange: (Boolean) -> Unit, onOpenQueue: () -> Unit) {
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    var radioLoading by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().height(116.dp).background(Color(0xFF131A20)).padding(horizontal = 10.dp, vertical = 5.dp)) {
    Row(
        Modifier.fillMaxWidth().weight(1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clickable { onOpenQueue() }) { Cover(track?.title ?: "N") }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clickable { onOpenQueue() }) {
            Text(track?.title ?: "Nic nie odtwarzamy", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(track?.artists?.joinToString() ?: "Wybierz utwór", color = Color(0xFF8D9BA6), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("◀", modifier = Modifier.clickable(enabled = track != null) { scope.launch { container.audioPlayer.previous() } }.padding(10.dp), color = if (track != null) Color.White else Color(0xFF55616A))
        Text(if (state.status == PlayerStatus.PLAYING) "Ⅱ" else "▶", modifier = Modifier.clickable(enabled = track != null) { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }.padding(12.dp), color = MaterialTheme.colors.primary, fontSize = 18.sp)
        Text("▶", modifier = Modifier.clickable(enabled = track != null) { scope.launch { container.audioPlayer.next() } }.padding(10.dp), color = if (track != null) Color.White else Color(0xFF55616A))
    }
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(state.positionMs), color = Color(0xFF8D9BA6), fontSize = 10.sp)
        Slider(
            value = if (state.durationMs > 0) state.positionMs.coerceAtMost(state.durationMs).toFloat() else 0f,
            onValueChange = { scope.launch { container.audioPlayer.seekTo(it.toLong()) } },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = track != null,
            modifier = Modifier.weight(1f).padding(horizontal = 5.dp),
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
                                container.audioPlayer.setQueue((listOf(seed) + recommendations).distinctBy(Track::id))
                                container.audioPlayer.play(); onSimilarModeChange(true); onOpenQueue()
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

@Composable
private fun PlayerBar(
    state: PlayerState,
    container: AppContainer,
    similarModeActive: Boolean,
    onSimilarModeChange: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    var radioLoading by remember { mutableStateOf(false) }
    var radioMessage by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().height(98.dp).background(Color(0xFF131A20)).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track?.title ?: "N")
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(230.dp)) {
            Text(track?.title ?: "Nic nie odtwarzamy", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
            Text(track?.artists?.joinToString() ?: "Wybierz utwór z playlisty", color = Color(0xFF8D9BA6), fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.previous() } }, enabled = track != null) { Text("⏮") }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }, enabled = track != null) {
            Text(if (state.status == PlayerStatus.PLAYING) "⏸" else "▶")
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.stop() } }, enabled = track != null && state.status != PlayerStatus.IDLE) { Text("⏹") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.next() } }, enabled = track != null) { Text("⏭") }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = {
                if (similarModeActive) {
                    onSimilarModeChange(false)
                    radioMessage = "Automatyczne podobne wyłączone"
                    return@OutlinedButton
                }
                val seed = track ?: return@OutlinedButton
                scope.launch {
                    radioLoading = true
                    radioMessage = null
                    runCatching { container.spotifyRepository.getTrackRadio(seed) }
                        .onSuccess { recommendations ->
                            val queue = (listOf(seed) + recommendations).distinctBy(Track::id)
                            container.audioPlayer.setQueue(queue)
                            container.audioPlayer.play()
                            radioMessage = "Dodano ${queue.size} podobnych utworów"
                            onSimilarModeChange(true)
                            onOpenQueue()
                        }
                        .onFailure { radioMessage = "Nie udało się znaleźć podobnych — ${it.message ?: "nieznany błąd"}" }
                    radioLoading = false
                }
            },
            enabled = track != null && !radioLoading,
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
        Text(state.status.name.lowercase(), color = if (state.status == PlayerStatus.ERROR) Color(0xFFFF7B7B) else MaterialTheme.colors.primary, fontSize = 11.sp)
        radioMessage?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = if (it.startsWith("Nie udało")) Color(0xFFFF7B7B) else Color(0xFF8FE9AD), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun QueueScreen(state: PlayerState, container: AppContainer) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Heading(
            "Aktualna kolejka",
            if (state.queue.isEmpty()) "Kolejka jest pusta" else "${state.queue.size} utworów • odtwarzany ${state.currentIndex + 1}",
        )
        Spacer(Modifier.height(14.dp))
        if (state.queue.isEmpty()) {
            EmptyState("Uruchom utwór, playlistę albo radio, aby utworzyć kolejkę")
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
                                Text(item.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.artists.joinToString(), color = Color(0xFF8F9CA6), fontSize = 12.sp, maxLines = 1)
                            }
                            Text(item.album, color = Color(0xFF8F9CA6), fontSize = 12.sp, modifier = Modifier.width(160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatTime(item.durationMs), color = Color(0xFF8F9CA6), fontSize = 12.sp)
                        }
                    }
            }
        }
    }
}

@Composable
private fun Cover(seed: String) {
    val colors = listOf(Color(0xFF375B4A), Color(0xFF404A75), Color(0xFF704858), Color(0xFF685C38))
    val color = colors[(seed.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(Modifier.size(54.dp).background(color, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(seed.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Color(0xFF8D9BA6))
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
            Text("Wystąpił problem", color = Color(0xFFFFA3A3), fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFFE6B9B9))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
