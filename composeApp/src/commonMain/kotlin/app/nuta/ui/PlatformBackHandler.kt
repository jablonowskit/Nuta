package app.nuta.ui

import androidx.compose.runtime.Composable

/** Systemowy przycisk/gest „wstecz”. Android: BackHandler z activity-compose; desktop: brak. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
