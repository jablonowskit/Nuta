package app.nuta.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlatformRemoteImage(url: String, description: String?, modifier: Modifier = Modifier)
