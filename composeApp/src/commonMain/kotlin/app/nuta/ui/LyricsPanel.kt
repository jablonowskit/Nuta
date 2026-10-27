package app.nuta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.core.models.Track
import app.nuta.lyrics.LrcLibClient
import app.nuta.lyrics.Lyrics
import app.nuta.resources.Res
import app.nuta.resources.lyrics_failed
import app.nuta.resources.lyrics_instrumental
import app.nuta.resources.lyrics_none
import app.nuta.ui.theme.LocalNutaPalette
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

/** „♪” obok wyłącznika czasowego — pokazuje/chowa [LyricsPanel]. */
@Composable
internal fun LyricsToggleButton(visible: Boolean, onToggle: (Boolean) -> Unit) {
    Text(
        "♪",
        color = if (visible) MaterialTheme.colors.primary else LocalNutaPalette.current.muted,
        fontSize = 16.sp,
        modifier = Modifier.clickable { onToggle(!visible) }.padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * Tekst bieżącego utworu z LRCLIB. Zsynchronizowany: podświetla linię dla [positionMs] i trzyma
 * ją w widoku (pozycja przychodzi co 0,5–1 s z playera, to wystarcza dla linii). Bez znaczników
 * czasu: zwykły przewijany tekst.
 */
@Composable
internal fun LyricsPanel(track: Track?, positionMs: Long, modifier: Modifier = Modifier) {
    val palette = LocalNutaPalette.current
    val client = remember { LrcLibClient() }
    var loading by remember(track?.id) { mutableStateOf(track != null) }
    var lyrics by remember(track?.id) { mutableStateOf<Lyrics?>(null) }
    var failed by remember(track?.id) { mutableStateOf(false) }
    LaunchedEffect(track?.id) {
        val current = track ?: return@LaunchedEffect
        try {
            lyrics = client.lyrics(current)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            failed = true
        }
        loading = false
    }
    Box(modifier.padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        val synced = lyrics?.synced
        val plain = lyrics?.plain
        when {
            loading -> CircularProgressIndicator(Modifier.padding(8.dp))
            synced != null -> {
                val listState = rememberLazyListState()
                val current = synced.indexOfLast { it.timeMs <= positionMs }
                LaunchedEffect(current) {
                    // dwie linie zapasu nad bieżącą — widać, co było przed chwilą
                    if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(synced) { index, line ->
                        Text(
                            line.text.ifEmpty { "♪" },
                            color = if (index == current) MaterialTheme.colors.primary else palette.muted,
                            fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        )
                    }
                }
            }
            plain != null -> Text(
                plain,
                color = palette.muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            )
            else -> Text(
                stringResource(
                    when {
                        failed -> Res.string.lyrics_failed
                        lyrics?.instrumental == true -> Res.string.lyrics_instrumental
                        else -> Res.string.lyrics_none
                    },
                ),
                color = palette.muted,
                fontSize = 13.sp,
            )
        }
    }
}
