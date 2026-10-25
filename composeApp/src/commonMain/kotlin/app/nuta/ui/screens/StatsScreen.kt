package app.nuta.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.AppContainer
import app.nuta.core.models.Artist
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.listenbrainz.ListenBrainzStats
import app.nuta.listenbrainz.StatsEntry
import app.nuta.listenbrainz.StatsKind
import app.nuta.listenbrainz.StatsRange
import app.nuta.resources.*
import app.nuta.settings.DataSource
import app.nuta.ui.Cover
import app.nuta.ui.EmptyState
import app.nuta.ui.ErrorState
import app.nuta.ui.Heading
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.TrackRow
import app.nuta.ui.theme.LocalNutaPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Statystyki ListenBrainz (odpowiednik listenbrainz.org/statistics): topki utworów, wykonawców
 * i albumów — własne (nazwa użytkownika z Ustawień) albo ogólnoświatowe.
 */
@Composable
internal fun StatsScreen(
    playerState: PlayerState,
    container: AppContainer,
    onArtist: (Artist) -> Unit,
) {
    val settings by container.playbackSettings.settings.collectAsState()
    val username = settings.listenBrainzUsername.takeIf(String::isNotBlank)
    val stats = remember { ListenBrainzStats() }
    val scope = rememberCoroutineScope()
    var mine by remember { mutableStateOf(username != null) }
    var kind by remember { mutableStateOf(StatsKind.RECORDINGS) }
    var range by remember { mutableStateOf(StatsRange.MONTH) }
    var entries by remember { mutableStateOf<List<StatsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mine, username, kind, range) {
        entries = emptyList()
        error = null
        if (mine && username == null) return@LaunchedEffect
        loading = true
        try {
            entries = stats.top(if (mine) username else null, kind, range)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "?"
            container.logger.warn("Stats", "stats_failed", "Nie udało się pobrać statystyk", fields = mapOf("reason" to (e.message ?: "unknown")))
        } finally {
            loading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChoiceRow(listOf(true to Res.string.stats_mine, false to Res.string.stats_global), mine) { mine = it }
        Spacer(Modifier.height(6.dp))
        ChoiceRow(
            listOf(
                StatsKind.RECORDINGS to Res.string.stats_tracks,
                StatsKind.ARTISTS to Res.string.stats_artists,
                StatsKind.RELEASES to Res.string.stats_albums,
            ),
            kind,
        ) { kind = it }
        Spacer(Modifier.height(6.dp))
        ChoiceRow(
            listOf(
                StatsRange.WEEK to Res.string.stats_week,
                StatsRange.MONTH to Res.string.stats_month,
                StatsRange.YEAR to Res.string.stats_year,
                StatsRange.ALL_TIME to Res.string.stats_all_time,
            ),
            range,
        ) { range = it }
        Spacer(Modifier.height(12.dp))
        val tracks = entries.mapNotNull { it.track }
        when {
            mine && username == null -> EmptyState(stringResource(Res.string.stats_no_username))
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error != null -> ErrorState(error ?: "")
            entries.isEmpty() -> EmptyState(stringResource(Res.string.stats_empty))
            else -> {
                if (tracks.isNotEmpty()) {
                    Button(onClick = { scope.launch { container.audioPlayer.setQueue(tracks); container.audioPlayer.play() } }) {
                        Text(stringResource(Res.string.liked_play_all, tracks.size), maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                // Wejście w wykonawcę idzie po MBID — działa tylko przy źródle ListenBrainz
                // (Spotify szukałoby po swoim id i nic by nie znalazło).
                val artistsClickable = settings.dataSource == DataSource.LISTENBRAINZ
                ScrollableLazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(entries, key = { i, e -> "stats-$i-${e.mbid}" }) { index, entry ->
                        val track = entry.track
                        if (track != null) {
                            TrackRow(
                                track,
                                playerState.currentTrack?.id == track.id,
                                loading = playerState.status == PlayerStatus.LOADING,
                                onPlay = { scope.launch { container.audioPlayer.setQueue(listOf(track), 0); container.audioPlayer.play() } },
                                subtitleAction = { ListenCount(entry.listenCount) },
                            )
                        } else {
                            val mbid = entry.mbid
                            val onClick: (() -> Unit)? =
                                if (mbid != null && kind == StatsKind.ARTISTS && artistsClickable) {
                                    { onArtist(Artist(mbid, entry.title)) }
                                } else null
                            EntryRow(index + 1, entry, onClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, StringResource>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            if (value == selected) Button(onClick = {}) { Text(stringResource(label), maxLines = 1, softWrap = false) }
            else OutlinedButton(onClick = { onSelect(value) }) { Text(stringResource(label), maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
private fun ListenCount(count: Long) {
    Text(count.toString(), color = LocalNutaPalette.current.muted, fontSize = 11.sp)
}

@Composable
private fun EntryRow(position: Int, entry: StatsEntry, onClick: (() -> Unit)?) {
    val palette = LocalNutaPalette.current
    Row(
        Modifier.fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$position", color = palette.muted, modifier = Modifier.width(32.dp))
        Cover(entry.title, entry.imageUrl, Modifier.size(40.dp), zoomable = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.subtitle.isNotBlank()) Text(entry.subtitle, color = palette.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ListenCount(entry.listenCount)
    }
}
