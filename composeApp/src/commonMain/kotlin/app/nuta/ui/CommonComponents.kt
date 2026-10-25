package app.nuta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
/** Ustawienie „Pobieraj okładki” — false: [Cover] nie pobiera obrazów, zostaje kafelek z literą. */
internal val LocalLoadCovers = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * Kafelek okładki. [zoomable]: kliknięcie otwiera okładkę na cały ekran (w większej
 * rozdzielczości, patrz [largeCoverUrl]); kliknięcie w powiększenie je zamyka.
 */
@Composable
internal fun Cover(seed: String, imageUrl: String? = null, modifier: Modifier = Modifier.size(54.dp), zoomable: Boolean = false) {
    val palette = LocalNutaPalette.current
    val colors = listOf(palette.primaryVariant, palette.secondary, palette.activeHighlight, palette.divider)
    val color = colors[(seed.hashCode() and Int.MAX_VALUE) % colors.size]
    val loadCovers = LocalLoadCovers.current
    var zoomed by remember { mutableStateOf(false) }
    val canZoom = zoomable && loadCovers && imageUrl != null
    Box(
        modifier.background(color, RoundedCornerShape(8.dp))
            .let { if (canZoom) it.clickable { zoomed = true } else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(seed.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        if (loadCovers) imageUrl?.let { PlatformRemoteImage(it, seed, Modifier.fillMaxSize()) }
    }
    if (zoomed && imageUrl != null) {
        Dialog(onDismissRequest = { zoomed = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(
                Modifier.fillMaxSize().background(Color.Black).clickable { zoomed = false },
                contentAlignment = Alignment.Center,
            ) {
                // Najpierw miniatura (już w cache), na wierzchu duża wersja, gdy dojdzie.
                PlatformRemoteImage(imageUrl, seed, Modifier.fillMaxWidth().aspectRatio(1f), fit = true)
                PlatformRemoteImage(largeCoverUrl(imageUrl), seed, Modifier.fillMaxWidth().aspectRatio(1f), fit = true)
            }
        }
    }
}

/** Cover Art Archive daje miniatury 250/500/1200 px — do powiększenia bierzemy 1200. */
internal fun largeCoverUrl(url: String): String =
    if ("coverartarchive.org" in url) url.replace("-250.jpg", "-1200.jpg").replace("/front-250", "/front-1200") else url

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
