package app.nuta.ui.theme

import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.nuta.settings.AppTheme

/**
 * Tokeny skórki poza Material `Colors` — chrome (top/sidebar/player), muted, activeHighlight
 * itd. Były wcześniej rozrzucone jako hardcoded `Color(0xFF…)` po UI.
 */
data class NutaPalette(
    val primary: Color,
    val primaryVariant: Color,
    val secondary: Color,
    val background: Color,
    val surface: Color,
    val chrome: Color,
    val divider: Color,
    val muted: Color,
    val onMuted: Color,
    val activeHighlight: Color,
    val danger: Color,
    val success: Color,
    val onPrimary: Color,
    val onBackground: Color,
) {
    fun toColors(): Colors = darkColors(
        primary = primary,
        primaryVariant = primaryVariant,
        secondary = secondary,
        background = background,
        surface = surface,
        error = danger,
        onPrimary = onPrimary,
        onSecondary = onPrimary,
        onBackground = onBackground,
        onSurface = onBackground,
        onError = onBackground,
    )
}

val LocalNutaPalette = staticCompositionLocalOf { AppTheme.FOREST.toPalette() }

@Composable
fun ProvideNutaPalette(palette: NutaPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNutaPalette provides palette, content = content)
}

fun AppTheme.toPalette(): NutaPalette = when (this) {
    AppTheme.FOREST -> NutaPalette(
        primary = Color(0xFF8BE9A8),
        primaryVariant = Color(0xFF54C57A),
        secondary = Color(0xFF9BA8FF),
        background = Color(0xFF101418),
        surface = Color(0xFF182027),
        chrome = Color(0xFF131A20),
        divider = Color(0xFF2A343D),
        muted = Color(0xFF8D9BA6),
        onMuted = Color(0xFF55616A),
        activeHighlight = Color(0xFF24332B),
        danger = Color(0xFFFF7B7B),
        success = Color(0xFF8FE9AD),
        onPrimary = Color(0xFF08130D),
        onBackground = Color(0xFFE8EDF2),
    )
    AppTheme.MIDNIGHT -> NutaPalette(
        primary = Color(0xFF7EB6FF),
        primaryVariant = Color(0xFF4A8FE0),
        secondary = Color(0xFF9BD5C8),
        background = Color(0xFF0C1018),
        surface = Color(0xFF151C28),
        chrome = Color(0xFF111722),
        divider = Color(0xFF253044),
        muted = Color(0xFF8A98AB),
        onMuted = Color(0xFF556274),
        activeHighlight = Color(0xFF1C2A40),
        danger = Color(0xFFFF7B7B),
        success = Color(0xFF8FE9AD),
        onPrimary = Color(0xFF071018),
        onBackground = Color(0xFFE6ECF5),
    )
    AppTheme.VINYL -> NutaPalette(
        primary = Color(0xFFD4A0FF),
        primaryVariant = Color(0xFFA86AD4),
        secondary = Color(0xFFFF9BC8),
        background = Color(0xFF120E16),
        surface = Color(0xFF1C1622),
        chrome = Color(0xFF17121C),
        divider = Color(0xFF33283C),
        muted = Color(0xFFA094B0),
        onMuted = Color(0xFF655A72),
        activeHighlight = Color(0xFF2A1F38),
        danger = Color(0xFFFF7B7B),
        success = Color(0xFF8FE9AD),
        onPrimary = Color(0xFF140A1A),
        onBackground = Color(0xFFF0E8F6),
    )
    AppTheme.EMBER -> NutaPalette(
        primary = Color(0xFFFFB86A),
        primaryVariant = Color(0xFFE08A3A),
        secondary = Color(0xFFFF8F7A),
        background = Color(0xFF14100C),
        surface = Color(0xFF1E1812),
        chrome = Color(0xFF19140F),
        divider = Color(0xFF3A3026),
        muted = Color(0xFFA89A8A),
        onMuted = Color(0xFF6A5E50),
        activeHighlight = Color(0xFF332618),
        danger = Color(0xFFFF7B7B),
        success = Color(0xFF8FE9AD),
        onPrimary = Color(0xFF1A1008),
        onBackground = Color(0xFFF5EDE4),
    )
}
