package app.nuta.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.core.models.Track
import kotlinx.coroutines.delay

/**
 * Wiersz utworu i jego przyciski akcji — używane przez ekrany Playlisty, Ulubione, Szukaj
 * i Kolejka, więc mieszkają obok siebie, a nie w którymkolwiek z tych ekranów.
 */
@Composable
internal fun TrackRow(
    track: Track,
    active: Boolean,
    loading: Boolean = false,
    onPlay: () -> Unit,
    titleAction: (@Composable () -> Unit)? = null,
    subtitleAction: (@Composable () -> Unit)? = null,
    // Zamiast trzeciej ikony "☰" na stałe widocznej w wierszu: przytrzymanie utworu
    // otwiera dodawanie do playlisty — mniej ikon, więcej miejsca, gest znany z innych appek.
    onLongPress: (() -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 520.dp
    val rowModifier = Modifier.fillMaxWidth()
        .background(if (active) Color(0xFF203129) else Color.Transparent, RoundedCornerShape(8.dp))
        .let { base ->
            if (onLongPress != null) base.combinedClickable(onClick = onPlay, onLongClick = onLongPress)
            else base.clickable(onClick = onPlay)
        }
        .padding(horizontal = 12.dp, vertical = 8.dp)
    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (active && loading) {
            Text("⏳︎", fontSize = 14.sp, modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        } else {
            Text(if (active) "▶" else "♪", color = if (active) MaterialTheme.colors.primary else Color(0xFF7D8B95), modifier = Modifier.width(28.dp))
        }
        Column(Modifier.weight(1f)) {
            // Jedna linia z wielokropkiem zamiast zawijania do 2 linii — długie tytuły
            // (częste, np. "(feat. ...)"/"Radio Edit") wcześniej prawie zawsze zajmowały
            // dodatkową linię, znacząco zmniejszając liczbę widocznych utworów na ekranie.
            Text(track.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.artists.joinToString(), modifier = Modifier.weight(1f), color = Color(0xFF8F9CA6), fontSize = 12.sp)
                Text(formatTime(track.durationMs), color = Color(0xFF8F9CA6), fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            }
        }
        if (!compact) Text(track.album, color = Color(0xFF8F9CA6), fontSize = 12.sp, modifier = Modifier.width(170.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (titleAction != null || subtitleAction != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                titleAction?.invoke()
                subtitleAction?.invoke()
            }
        }
    }
    }
}

/**
 * Ikonka akcji przy utworze z wyraźnym potwierdzeniem kliknięcia: sam ripple na przycisku 24dp jest
 * ledwo widoczny, a efekt akcji (start odtwarzania, dopisanie do kolejki) bywa opóźniony o sieć,
 * więc przez chwilę wyglądało to, jakby przycisk nie działał.
 */
@Composable
private fun TrackActionButton(label: String, confirmLabel: String, fontSize: TextUnit, onClick: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (confirming) { delay(700); confirming = false }
    }
    Box(
        Modifier.size(24.dp)
            .background(if (confirming) MaterialTheme.colors.primary else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { confirming = true; onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (confirming) confirmLabel else label,
            fontSize = fontSize,
            color = if (confirming) Color(0xFF0B1116) else MaterialTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun TrackPlayButton(onClick: () -> Unit) = TrackActionButton("▶", "▶", 11.sp, onClick)

@Composable
internal fun TrackQueueButton(onClick: () -> Unit) = TrackActionButton("+", "✓", 12.sp, onClick)

/**
 * UWAGA: obecnie **nieużywany** — w całym repo nie ma ani jednego wywołania (stan na 08.09.2026,
 * sprawdzone przed wydzieleniem tego pliku; było tak już wcześniej, w monolitycznym App.kt, gdzie
 * `private` bez użycia dawało tylko ostrzeżenie kompilatora). Zostawiony celowo, bo buforowanie
 * jest dziś sygnalizowane znakiem "⏳︎" w [TrackRow], a ten wskaźnik jest gotową alternatywą.
 * Do usunięcia, jeśli nie zostanie podłączony.
 */
@Composable
internal fun BufferingIndicator(color: Color = MaterialTheme.colors.primary) {
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
