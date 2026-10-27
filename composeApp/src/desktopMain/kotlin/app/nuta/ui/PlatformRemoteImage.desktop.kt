package app.nuta.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Desktop nie ładuje okładek (patrz [PlatformRemoteImage]) — tło playera zostaje z motywu. */
@Composable
actual fun rememberCoverColor(url: String?): Color? = null

@Composable
actual fun PlatformRemoteImage(url: String, description: String?, modifier: Modifier, fit: Boolean) = Unit
