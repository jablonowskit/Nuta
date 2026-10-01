package app.nuta.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.min

/**
 * Znak wodny kasety magnetofonowej pod treścią ekranu. Szpule kręcą się tylko przy [playing]
 * (w pauzie animacja stoi, więc nie kosztuje baterii), a [progress] przewija taśmę z lewej
 * szpuli na prawą. Kąt czytany jest wyłącznie w fazie rysowania — obrót nie wywołuje rekompozycji.
 */
@Composable
fun CassetteBackground(
    playing: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE8EDF2).copy(alpha = 0.06f),
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            rotation.animateTo(rotation.value + 360f, tween(durationMillis = 4000, easing = LinearEasing))
            rotation.snapTo(rotation.value % 360f)
        }
    }
    Canvas(modifier) {
        val width = min(size.width * 0.8f, size.height * 0.8f * CASSETTE_ASPECT)
        val height = width / CASSETTE_ASPECT
        val topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f)
        drawCassette(topLeft, Size(width, height), rotation.value, progress.coerceIn(0f, 1f), color)
    }
}

private const val CASSETTE_ASPECT = 1.6f

private fun DrawScope.drawCassette(topLeft: Offset, size: Size, angle: Float, progress: Float, color: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.008f)
    fun at(x: Float, y: Float) = Offset(topLeft.x + x * w, topLeft.y + y * h)

    drawRoundRect(color, topLeft, size, CornerRadius(w * 0.04f), style = stroke)
    drawRoundRect(color, at(0.07f, 0.08f), Size(w * 0.86f, h * 0.58f), CornerRadius(w * 0.025f), style = stroke)
    for (y in listOf(0.15f, 0.21f)) drawLine(color, at(0.12f, y), at(0.88f, y), strokeWidth = stroke.width * 0.6f)

    val windowTop = 0.30f
    val windowHeight = 0.28f
    drawRoundRect(color, at(0.27f, windowTop), Size(w * 0.46f, h * windowHeight), CornerRadius(h * 0.14f), style = stroke)

    val leftCenter = at(0.335f, windowTop + windowHeight / 2f)
    val rightCenter = at(0.665f, windowTop + windowHeight / 2f)
    val hubRadius = h * 0.055f
    val minTape = hubRadius * 1.15f
    val maxTape = h * 0.2f
    val leftTape = maxTape - (maxTape - minTape) * progress
    val rightTape = minTape + (maxTape - minTape) * progress
    val window = Path().apply {
        addRoundRect(RoundRect(Rect(at(0.27f, windowTop), Size(w * 0.46f, h * windowHeight)), CornerRadius(h * 0.14f)))
    }
    clipPath(window) {
        drawCircle(color.copy(alpha = color.alpha * 0.6f), leftTape, leftCenter)
        drawCircle(color.copy(alpha = color.alpha * 0.6f), rightTape, rightCenter)
    }
    drawReel(leftCenter, hubRadius, angle, color, stroke)
    drawReel(rightCenter, hubRadius, angle, color, stroke)

    val trapezoid = Path().apply {
        moveTo(at(0.2f, 1f).x, at(0.2f, 1f).y)
        lineTo(at(0.26f, 0.76f).x, at(0.26f, 0.76f).y)
        lineTo(at(0.74f, 0.76f).x, at(0.74f, 0.76f).y)
        lineTo(at(0.8f, 1f).x, at(0.8f, 1f).y)
    }
    drawPath(trapezoid, color, style = stroke)
    for (x in listOf(0.34f, 0.66f)) drawCircle(color, h * 0.025f, at(x, 0.88f), style = stroke)
    for (x in listOf(0.43f, 0.57f)) drawCircle(color, h * 0.015f, at(x, 0.88f))

    for ((x, y) in listOf(0.035f to 0.06f, 0.965f to 0.06f, 0.035f to 0.94f, 0.965f to 0.94f, 0.5f to 0.7f)) {
        drawCircle(color, h * 0.014f, at(x, y))
    }
}

private fun DrawScope.drawReel(center: Offset, radius: Float, angle: Float, color: Color, stroke: Stroke) {
    drawCircle(color, radius, center, style = stroke)
    rotate(angle, center) {
        for (i in 0 until 6) {
            rotate(i * 60f, center) {
                drawRect(
                    color,
                    Offset(center.x - radius * 0.12f, center.y - radius * 0.8f),
                    Size(radius * 0.24f, radius * 0.35f),
                )
            }
        }
    }
}
