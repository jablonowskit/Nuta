package app.nuta.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Średni kolor okładki (bez prawie czarnych i prawie białych pikseli — tła i ramki zaniżałyby
 * nasycenie) do tła rozwiniętego playera. null, gdy okładki nie ma albo platforma jej nie ładuje.
 */
@Composable
expect fun rememberCoverColor(url: String?): Color?

@Composable
expect fun PlatformRemoteImage(url: String, description: String?, modifier: Modifier = Modifier, fit: Boolean = false)
