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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Znak wodny kasety magnetofonowej pod treścią ekranu. Szpule kręcą się tylko przy [playing]
 * (w pauzie animacja stoi). [progress] (0..1 = pozycja/długość utworu) nawija taśmę z lewej
 * szpuli na prawą: promień zwoju rośnie z pierwiastkiem postępu, bo ilość taśmy to pole
 * pierścienia, nie sam promień — inaczej środek utworu wyglądałby jak prawie pełna prawa
 * szpula. Zweryfikowane wizualnie 01.10.2026.
 *
 * Kąt jest czytany w ciele composable (nie tylko w Canvas), żeby każda klatka Animatable
 * wymusiła rekompozycję/przerysowanie.
 */
@Composable
fun CassetteBackground(
    playing: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE8EDF2).copy(alpha = 0.07f),
) {
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            // Ujemny przyrost = obrót przeciwnie do wskazówek zegara (obie szpule tak samo).
            rotation.animateTo(rotation.value - 360f, tween(durationMillis = 2800, easing = LinearEasing))
            rotation.snapTo(rotation.value % 360f)
        }
    }
    val angle = rotation.value
    Canvas(modifier) {
        val width = min(size.width * 0.8f, size.height * 0.8f * CASSETTE_ASPECT)
        val height = width / CASSETTE_ASPECT
        val topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f)
        drawCassette(topLeft, Size(width, height), angle, progress.coerceIn(0f, 1f), color)
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
    val hubRadius = h * 0.065f
    val maxTape = h * 0.22f
    // Pole pierścienia ~ ilość taśmy → promień ~ sqrt(udziału).
    val leftShare = 1f - progress
    val rightShare = progress
    val leftTape = hubRadius + (maxTape - hubRadius) * sqrt(leftShare)
    val rightTape = hubRadius + (maxTape - hubRadius) * sqrt(rightShare)
    val window = Path().apply {
        addRoundRect(RoundRect(Rect(at(0.27f, windowTop), Size(w * 0.46f, h * windowHeight)), CornerRadius(h * 0.14f)))
    }
    val tapeFill = color.copy(alpha = (color.alpha * 1.6f).coerceIn(0.10f, 0.18f))
    val tapeRing = color.copy(alpha = (color.alpha * 2.4f).coerceIn(0.14f, 0.26f))
    clipPath(window) {
        drawTapePack(leftCenter, hubRadius, leftTape, tapeFill, tapeRing)
        drawTapePack(rightCenter, hubRadius, rightTape, tapeFill, tapeRing)
        // Taśma między szpulami — cienki mostek u dołu okienka.
        val bridgeY = leftCenter.y + maxTape * 0.55f
        drawLine(
            tapeRing,
            Offset(leftCenter.x + leftTape * 0.15f, bridgeY),
            Offset(rightCenter.x - rightTape * 0.15f, bridgeY),
            strokeWidth = h * 0.012f,
            cap = StrokeCap.Round,
        )
    }
    val reelColor = color.copy(alpha = (color.alpha * 2.2f).coerceIn(0.12f, 0.22f))
    drawReel(leftCenter, hubRadius, angle, reelColor)
    drawReel(rightCenter, hubRadius, angle, reelColor)

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

/** Zwój taśmy: wypełniony pierścień + kilka koncentrycznych linii jak nawinięcia. */
private fun DrawScope.drawTapePack(
    center: Offset,
    hubRadius: Float,
    outerRadius: Float,
    fill: Color,
    ring: Color,
) {
    if (outerRadius <= hubRadius * 1.05f) return
    drawCircle(fill, outerRadius, center)
    // Wykrawamy hub kolorem tła kasety (prawie czarny) — bez tego zwój zasłania szprychy.
    drawCircle(Color(0xFF101418), hubRadius * 1.05f, center)
    val rings = 4
    val span = outerRadius - hubRadius
    for (i in 1..rings) {
        val r = hubRadius + span * (i / (rings + 1f))
        drawCircle(ring, r, center, style = Stroke(width = span * 0.04f))
    }
}

private fun DrawScope.drawReel(center: Offset, radius: Float, angle: Float, color: Color) {
    val spokeStroke = Stroke(width = radius * 0.22f, cap = StrokeCap.Round)
    drawCircle(color, radius, center, style = Stroke(width = radius * 0.14f))
    rotate(angle, center) {
        for (i in 0 until 6) {
            val rad = Math.toRadians(i * 60.0)
            val inner = radius * 0.2f
            val outer = radius * 0.92f
            drawLine(
                color,
                Offset(center.x + cos(rad).toFloat() * inner, center.y + sin(rad).toFloat() * inner),
                Offset(center.x + cos(rad).toFloat() * outer, center.y + sin(rad).toFloat() * outer),
                strokeWidth = spokeStroke.width,
                cap = StrokeCap.Round,
            )
        }
    }
    drawCircle(color, radius * 0.18f, center)
}
