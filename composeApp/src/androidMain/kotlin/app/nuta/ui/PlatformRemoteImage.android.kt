package app.nuta.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
actual fun PlatformRemoteImage(url: String, description: String?, modifier: Modifier, fit: Boolean) {
    AsyncImage(
        model = url,
        contentDescription = description,
        contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
        modifier = modifier,
    )
}
