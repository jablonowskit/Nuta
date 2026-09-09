package app.nuta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.core.models.Playlist
import app.nuta.resources.*
import org.jetbrains.compose.resources.pluralStringResource

/**
 * Karty używane przez więcej niż jeden ekran: [PlaylistCard] przez Start, Bibliotekę i Szukaj,
 * [StatCard] przez Start. Trzymane wspólnie, żeby żaden ekran nie musiał importować wnętrza innego.
 */
@Composable
internal fun StatCard(label: String, value: String, modifier: Modifier, compact: Boolean = false) {
    Card(modifier, backgroundColor = MaterialTheme.colors.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(if (compact) 12.dp else 20.dp)) {
            Text(label, color = Color(0xFF8D9BA6), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(value, fontSize = if (compact) 20.sp else 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun PlaylistCard(playlist: Playlist, onClick: () -> Unit) {
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
