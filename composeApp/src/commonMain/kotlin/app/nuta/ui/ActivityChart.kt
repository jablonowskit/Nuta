package app.nuta.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.listenbrainz.ActivityBucket
import app.nuta.resources.Res
import app.nuta.resources.stats_activity_total
import app.nuta.ui.theme.LocalNutaPalette
import org.jetbrains.compose.resources.stringResource

/**
 * Słupki odsłuchań w czasie (dzień/miesiąc/rok — zależnie od zakresu). Podpisujemy tylko
 * pierwszy i ostatni kubełek: przy 30+ słupkach na telefonie podpis pod każdym byłby nieczytelny.
 */
@Composable
internal fun ActivityChart(buckets: List<ActivityBucket>, modifier: Modifier = Modifier) {
    if (buckets.isEmpty()) return
    val palette = LocalNutaPalette.current
    val barColor = MaterialTheme.colors.primary
    val emptyColor = palette.divider
    val max = buckets.maxOf { it.count }.coerceAtLeast(1)
    Column(modifier) {
        Text(
            stringResource(Res.string.stats_activity_total, buckets.sumOf { it.count }.toInt()),
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val slot = size.width / buckets.size
            val gap = (slot * 0.2f).coerceAtMost(4.dp.toPx())
            val barWidth = (slot - gap).coerceAtLeast(1f)
            buckets.forEachIndexed { index, bucket ->
                // zero rysujemy cienką kreską — widać, że dzień był, tylko bez słuchania
                val height = if (bucket.count == 0L) 2.dp.toPx() else size.height * bucket.count / max
                drawRoundRect(
                    color = if (bucket.count == 0L) emptyColor else barColor,
                    topLeft = Offset(index * slot + gap / 2, size.height - height),
                    size = Size(barWidth, height),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(buckets.first().label, color = palette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (buckets.size > 1) Text(buckets.last().label, color = palette.muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}
