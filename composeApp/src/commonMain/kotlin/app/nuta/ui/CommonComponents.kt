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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Bezstanowe elementy współdzielone przez wszystkie ekrany (okładka, nagłówki, stany puste/błędu)
 * oraz formatery tekstu. Wydzielone z `App.kt`, żeby ekrany w `ui/screens/` mogły ich używać bez
 * ciągnięcia za sobą całego pliku aplikacji.
 */
@Composable
internal fun Cover(seed: String, imageUrl: String? = null, modifier: Modifier = Modifier.size(54.dp)) {
    val colors = listOf(Color(0xFF375B4A), Color(0xFF404A75), Color(0xFF704858), Color(0xFF685C38))
    val color = colors[(seed.hashCode() and Int.MAX_VALUE) % colors.size]
    Box(modifier.background(color, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Text(seed.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        imageUrl?.let { PlatformRemoteImage(it, seed, Modifier.fillMaxSize()) }
    }
}

@Composable
internal fun Heading(title: String, subtitle: String? = null) {
    Column {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        subtitle?.takeIf(String::isNotBlank)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = Color(0xFF8D9BA6))
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text, color = Color(0xFF7E8D97), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
internal fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) { Text(message, color = Color(0xFF81909A)) }
}

@Composable
internal fun ErrorState(message: String) {
    Card(backgroundColor = Color(0xFF3A2225), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(Res.string.error_title), color = Color(0xFFFFA3A3), fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFFE6B9B9))
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1_000).coerceAtLeast(0)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}
