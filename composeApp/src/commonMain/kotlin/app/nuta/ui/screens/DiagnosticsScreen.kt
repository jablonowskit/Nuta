package app.nuta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.AppContainer
import app.nuta.core.logging.LogEvent
import app.nuta.core.logging.LogLevel
import app.nuta.resources.*
import app.nuta.ui.EmptyState
import app.nuta.ui.Heading
import app.nuta.ui.ScrollableLazyColumn
import app.nuta.ui.theme.LocalNutaPalette
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiagnosticsScreen(container: AppContainer) {
    val events by container.logger.events.collectAsState()
    val level by container.logger.minimumLevel.collectAsState()
    val scope = rememberCoroutineScope()
    val palette = LocalNutaPalette.current
    Column(Modifier.fillMaxSize()) {
        Heading(stringResource(Res.string.diagnostics_title), stringResource(Res.string.diagnostics_subtitle))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.log_level), color = palette.muted)
            listOf(LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE).forEach { item ->
                OutlinedButton(
                    onClick = { container.logger.setMinimumLevel(item) },
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = if (level == item) palette.activeHighlight else Color.Transparent),
                ) { Text(item.name) }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { scope.launch { container.audioPlayer.simulateError() } }) { Text(stringResource(Res.string.simulate_error)) }
            OutlinedButton(onClick = container.logger::clear) { Text(stringResource(Res.string.clear)) }
        }
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth().weight(1f), backgroundColor = palette.chrome, shape = RoundedCornerShape(10.dp)) {
            if (events.isEmpty()) EmptyState(stringResource(Res.string.no_events)) else ScrollableLazyColumn(Modifier.padding(10.dp).fillMaxSize(), reverseLayout = true) {
                items(events.reversed()) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun LogRow(item: LogEvent) {
    val palette = LocalNutaPalette.current
    val color = when (item.level) {
        LogLevel.ERROR -> Color(0xFFFF7B7B)
        LogLevel.WARN -> Color(0xFFFFD37B)
        LogLevel.INFO -> Color(0xFF8BE9A8)
        LogLevel.DEBUG -> Color(0xFF9BA8FF)
        LogLevel.TRACE -> Color(0xFF88949D)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text(item.level.name.padEnd(5), color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(55.dp))
            Text(item.module, color = palette.onBackground, fontSize = 11.sp, modifier = Modifier.width(150.dp))
            Text(item.event, color = palette.muted, fontSize = 11.sp)
        }
        Text(item.message, color = palette.onBackground, fontSize = 12.sp)
        if (item.fields.isNotEmpty()) Text(item.fields.entries.joinToString("  ") { "${it.key}=${it.value}" }, color = palette.onMuted, fontSize = 10.sp)
    }
}
