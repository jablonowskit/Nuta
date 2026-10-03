package app.nuta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.resources.*
import app.nuta.ui.theme.LocalNutaPalette
import org.jetbrains.compose.resources.stringResource

/**
 * Bezstanowe elementy współdzielone przez wszystkie ekrany (okładka, nagłówki, stany puste/błędu)
 * oraz formatery tekstu. Wydzielone z `App.kt`, żeby ekrany w `ui/screens/` mogły ich używać bez
 * ciągnięcia za sobą całego pliku aplikacji.
 */
@Composable
internal fun Cover(seed: String, imageUrl: String? = null, modifier: Modifier = Modifier.size(54.dp)) {
    val palette = LocalNutaPalette.current
    val colors = listOf(palette.primaryVariant, palette.secondary, palette.activeHighlight, palette.divider)
    val color = colors[(seed.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(modifier.background(color, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(seed.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        imageUrl?.let { PlatformRemoteImage(it, seed, Modifier.fillMaxSize()) }
    }
}

@Composable
internal fun Heading(title: String, subtitle: String? = null) {
    val palette = LocalNutaPalette.current
    Column {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        subtitle?.takeIf(String::isNotBlank)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = palette.muted)
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    val palette = LocalNutaPalette.current
    Text(text, color = palette.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
internal fun EmptyState(message: String) {
    val palette = LocalNutaPalette.current
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Text(message, color = palette.muted) }
}

@Composable
internal fun ErrorState(message: String) {
    val palette = LocalNutaPalette.current
    Card(backgroundColor = MaterialTheme.colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(Res.string.error_title), color = palette.danger, fontWeight = FontWeight.Bold)
            Text(message, color = palette.danger)
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
