package app.nuta.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
actual fun rememberCoverColor(url: String?): Color? {
    val context = LocalContext.current
    val loadCovers = LocalLoadCovers.current
    val color by produceState<Color?>(null, url, loadCovers) {
        if (url == null || !loadCovers) { value = null; return@produceState }
        // Ten sam ImageLoader co AsyncImage — miniatura zwykle już jest w cache'u pamięci/dysku.
        val request = ImageRequest.Builder(context).data(url).size(48).allowHardware(false).build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
        value = result?.let { withContext(Dispatchers.Default) { averageColor(it.image.toBitmap()) } }
    }
    return color
}

private fun averageColor(bitmap: android.graphics.Bitmap): Color? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    var r = 0L; var g = 0L; var b = 0L; var n = 0
    for (p in pixels) {
        val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
        val luma = (299 * pr + 587 * pg + 114 * pb) / 1000
        if (luma < 20 || luma > 235) continue
        r += pr; g += pg; b += pb; n++
    }
    if (n == 0) return null
    return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

@Composable
actual fun PlatformRemoteImage(url: String, description: String?, modifier: Modifier, fit: Boolean) {
    AsyncImage(
        model = url,
        contentDescription = description,
        contentScale = if (fit) ContentScale.Fit else ContentScale.Crop,
        modifier = modifier,
    )
}
