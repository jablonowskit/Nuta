package app.nuta.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
actual fun PlatformVerticalScrollbar(state: LazyListState, modifier: Modifier): Unit {
    val scrollbarModifier = modifier.drawWithContent {
        drawContent()
        val layout = state.layoutInfo
        val totalItems = layout.totalItemsCount
        val visibleItems = layout.visibleItemsInfo.size
        if (totalItems <= visibleItems || size.height <= 0f) return@drawWithContent

        val viewportHeight = size.height
        // On small screens the viewport can be shorter than the preferred
        // minimum thumb size. Keep both bounds inside the actual viewport so
        // coerceIn never receives an inverted range.
        val minThumbHeight = minOf(32.dp.toPx(), viewportHeight)
        val thumbHeight = (viewportHeight * visibleItems / totalItems)
            .coerceIn(minThumbHeight, viewportHeight)
        val maxOffset = (totalItems - visibleItems).coerceAtLeast(1)
        val firstVisible = state.firstVisibleItemIndex.coerceIn(0, maxOffset)
        val thumbTop = (viewportHeight - thumbHeight) * firstVisible / maxOffset
        val x = size.width - 5.dp.toPx()

        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            topLeft = androidx.compose.ui.geometry.Offset(x - 2.dp.toPx(), 0f),
            size = androidx.compose.ui.geometry.Size(4.dp.toPx(), viewportHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.72f),
            topLeft = androidx.compose.ui.geometry.Offset(x - 4.dp.toPx(), thumbTop),
            size = androidx.compose.ui.geometry.Size(8.dp.toPx(), thumbHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
        )
    }
    androidx.compose.foundation.layout.Box(scrollbarModifier)
}
