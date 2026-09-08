package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nuta.core.models.PlayerState
import app.nuta.core.models.Playlist
import app.nuta.resources.*
import app.nuta.ui.EmptyState
import app.nuta.ui.Heading
import app.nuta.ui.PlaylistCard
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.StatCard
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeScreen(
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
