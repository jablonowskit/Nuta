package app.nuta.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.AppContainer
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.resources.*
import app.nuta.ui.Cover
import app.nuta.ui.EmptyState
import app.nuta.ui.Heading
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.SectionLabel
import app.nuta.ui.formatTime
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable

@Composable
internal fun QueueScreen(state: PlayerState, container: AppContainer) {
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
