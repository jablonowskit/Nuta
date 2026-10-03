package app.nuta.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.AppContainer
import app.nuta.core.models.PlayerState
import app.nuta.core.models.PlayerStatus
import app.nuta.core.models.Track
import app.nuta.resources.*
import app.nuta.ui.theme.LocalNutaPalette
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
private fun CompactTransportRow(
    state: PlayerState,
    container: AppContainer,
    isLiked: Boolean,
    favoriteLoading: Boolean,
    onToggleLiked: () -> Unit,
    onOpenQueue: () -> Unit,
    similarModeActive: Boolean,
    onSimilarModeChange: (Boolean) -> Unit,
    // Zwinięty i rozwinięty pasek trzymają "♬+" w tym samym rzędzie co transport
    // (prev/play/next/like/shuffle). Wcześniej w rozwiniętym trybie żył osobno przy
    // sliderze pozycji — wyglądało jak osobna linia kontrolek.
    showSimilarButton: Boolean = true,
) {
    val palette = LocalNutaPalette.current
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    var radioLoading by remember { mutableStateOf(false) }
    var radioError by remember { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val buttonSize = if (maxWidth < 360.dp) 36.dp else 40.dp
        Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            // Box(contentAlignment = Center) zamiast samego textAlign na Text — textAlign centruje
            // tylko poziomo. Różne glify (⏸ vs ⏮ vs ♡) mają różne metryki czcionki (ascent/descent),
            // więc bez jawnego wyśrodkowania w Boxie każdy z nich "siadał" na innej wysokości mimo
            // identycznego Modifier.size(40.dp) na samym Tekście.
            Box(Modifier.size(buttonSize).clickable(enabled = track != null) { scope.launch { container.audioPlayer.previous() } }, contentAlignment = Alignment.Center) {
                Text("⏮", color = if (track != null) Color.White else palette.onMuted, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
            Box(Modifier.size(buttonSize).clickable(enabled = track != null) { scope.launch { container.audioPlayer.seekTo((state.positionMs - 10_000).coerceAtLeast(0)) } }, contentAlignment = Alignment.Center) {
                Text("⏪︎", color = if (track != null) Color.White else palette.onMuted, fontSize = 28.sp)
            }
            Box(Modifier.size(buttonSize), contentAlignment = Alignment.Center) {
                if (state.status == PlayerStatus.LOADING) {
                    Text("⏳︎", color = MaterialTheme.colors.primary, fontSize = 30.sp)
                } else {
                    Box(Modifier.fillMaxSize().clickable(enabled = track != null) { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }, contentAlignment = Alignment.Center) {
                        Text(if (state.status == PlayerStatus.PLAYING) "⏸" else "▶", color = MaterialTheme.colors.primary, fontSize = 30.sp)
                    }
                }
            }
            Box(Modifier.size(buttonSize).clickable(enabled = track != null) {
                scope.launch {
                    val target = state.positionMs + 10_000
                    container.audioPlayer.seekTo(if (state.durationMs > 0) target.coerceAtMost(state.durationMs) else target)
                }
            }, contentAlignment = Alignment.Center) {
                Text("⏩︎", color = if (track != null) Color.White else palette.onMuted, fontSize = 28.sp)
            }
            Box(Modifier.size(buttonSize).clickable(enabled = track != null) { scope.launch { container.audioPlayer.next() } }, contentAlignment = Alignment.Center) {
                Text("⏭", color = if (track != null) Color.White else palette.onMuted, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            }
            Box(Modifier.size(buttonSize).clickable(enabled = track != null && !favoriteLoading) { onToggleLiked() }, contentAlignment = Alignment.Center) {
                Text(
                    if (favoriteLoading) "…" else if (isLiked) "♥" else "♡",
                    color = if (isLiked) Color(0xFFFF4D67) else if (track != null) Color.White else palette.onMuted,
                    fontSize = 32.sp,
                )
            }
            Box(
                Modifier.size(buttonSize)
                    .background(if (state.shuffleEnabled) palette.primaryVariant else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = state.queue.size > 1) { scope.launch { container.audioPlayer.shuffleUpcoming(); onOpenQueue() } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⇄",
                    color = when { state.shuffleEnabled -> Color.White; state.queue.size > 1 -> MaterialTheme.colors.primary; else -> palette.onMuted },
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (showSimilarButton) {
                Box(
                    Modifier.size(buttonSize)
                        .background(if (similarModeActive) palette.primaryVariant else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable(enabled = track != null && !radioLoading) {
                            if (similarModeActive) onSimilarModeChange(false) else track?.let { seed ->
                                scope.launch {
                                    radioLoading = true
                                    radioError = null
                                    try {
                                        val recommendations = container.spotifyRepository.getTrackRadio(seed)
                                        val additions = recommendations.filterNot { candidate -> state.queue.any { it.id == candidate.id } }
                                        if (state.queue.isEmpty()) container.audioPlayer.setQueue(listOf(seed) + additions, 0)
                                        else container.audioPlayer.appendToQueue(additions)
                                        onSimilarModeChange(true)
                                        onOpenQueue()
                                    } catch (error: Throwable) {
                                        radioError = error.message ?: "Nie udało się pobrać podobnych utworów"
                                    } finally {
                                        radioLoading = false
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (radioLoading) "…" else "♬+",
                        color = if (similarModeActive) Color.White else if (track != null) MaterialTheme.colors.primary else palette.onMuted,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        radioError?.let {
            Text(it, color = palette.danger, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        }
    }
}

@Composable
internal fun CompactPlayerBar(
    state: PlayerState,
    container: AppContainer,
    similarModeActive: Boolean,
    onSimilarModeChange: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    isLiked: Boolean,
    favoriteLoading: Boolean,
    onToggleLiked: () -> Unit,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
) {
    val palette = LocalNutaPalette.current
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    val dragThresholdPx = with(LocalDensity.current) { 24.dp.toPx() }
    var dragAccumulated by remember { mutableStateOf(0f) }
    // Bez stałej wysokości w trybie rozwiniętym: tytuł/wykonawca mogą zająć do 2 linii.
    Column(
        Modifier.fillMaxWidth()
            .background(palette.chrome)
            .pointerInput(collapsed) {
                // Palec w dół zwija, w górę rozwija. Pasek leży poza LazyColumn treści,
                // więc gest nie konkuruje ze scrollem listy.
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulated = 0f },
                    onDragEnd = {
                        if (dragAccumulated > dragThresholdPx) onCollapsedChange(true)
                        else if (dragAccumulated < -dragThresholdPx) onCollapsedChange(false)
                        dragAccumulated = 0f
                    },
                ) { _, dy -> dragAccumulated += dy }
            }
            .animateContentSize(),
    ) {
    if (collapsed) {
        // Mini-player jak Spotify/YT Music: cienki postęp + jedna linia
        // [okładka | tytuł/artysta | ▶ ⏭]. Reszta kontrolek po przeciągnięciu w górę.
        val progress = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(palette.divider)) {
            Box(
                Modifier.fillMaxWidth(progress).height(2.dp)
                    .background(MaterialTheme.colors.primary),
            )
        }
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(
                track?.title ?: "N",
                track?.imageUrl,
                Modifier.size(40.dp).clickable { onCollapsedChange(false) },
            )
            // weight(1f) zabiera całą wolną szerokość — po prawej zostają tylko dwa małe przyciski.
            Column(
                Modifier.weight(1f).padding(start = 10.dp, end = 4.dp).clickable { onCollapsedChange(false) },
            ) {
                Text(
                    track?.title ?: stringResource(Res.string.nothing_playing),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    track?.artists?.joinToString() ?: stringResource(Res.string.choose_track),
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    fontSize = 11.sp,
                )
            }
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (state.status == PlayerStatus.LOADING) {
                    Text("⏳︎", color = MaterialTheme.colors.primary, fontSize = 20.sp)
                } else {
                    Box(
                        Modifier.fillMaxSize().clickable(enabled = track != null) {
                            scope.launch {
                                if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause()
                                else container.audioPlayer.play()
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.status == PlayerStatus.PLAYING) "⏸" else "▶",
                            color = MaterialTheme.colors.primary,
                            fontSize = 20.sp,
                        )
                    }
                }
            }
            Box(
                Modifier.size(36.dp).clickable(enabled = track != null) {
                    scope.launch { container.audioPlayer.next() }
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⏭",
                    color = if (track != null) Color.White else palette.onMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        }
        return@Column
    }
    Column(Modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
    // Uchwyt tylko w trybie rozwiniętym — w mini i tak widać, że da się przeciągnąć.
    Box(
        Modifier.fillMaxWidth().height(14.dp).clickable { onCollapsedChange(true) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(36.dp).height(4.dp).background(palette.divider, RoundedCornerShape(2.dp)))
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable { onOpenQueue() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(track?.title ?: stringResource(Res.string.nothing_playing), maxLines = 2, overflow = TextOverflow.Clip, softWrap = true, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                streamBitrateLabel(state)?.let {
                    Text(it, color = palette.muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(track?.artists?.joinToString() ?: stringResource(Res.string.choose_track), color = palette.muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Clip, softWrap = true, modifier = Modifier.weight(1f))
                streamCodecLabel(state)?.let {
                    Text(it, color = palette.muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
    CompactTransportRow(state, container, isLiked, favoriteLoading, onToggleLiked, onOpenQueue, similarModeActive, onSimilarModeChange)
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(state.positionMs), color = palette.muted, fontSize = 10.sp)
        PositionSlider(
            state = state,
            container = container,
            enabled = track != null,
            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
        )
        Text(formatTime(state.durationMs), color = palette.muted, fontSize = 10.sp)
    }
    }
    }
}

/**
 * Suwak pozycji, który podczas przeciągania rysuje się z lokalnego stanu, a nie z
 * `state.positionMs`. Bez tego uchwyt wracał pod palec i ruszał dopiero, gdy player
 * faktycznie przeskoczył i zaraportował nową pozycję — czyli po dociągnięciu bufora.
 * `seekTo` leci raz, po puszczeniu palca, zamiast przy każdej klatce przeciągania.
 */
@Composable
private fun PositionSlider(
    state: PlayerState,
    container: AppContainer,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var scrubbingMs by remember { mutableStateOf<Float?>(null) }
    // Po puszczeniu palca player przez chwilę raportuje jeszcze starą pozycję, więc trzymamy
    // docelową wartość aż do momentu, gdy faktycznie do niej dojedzie — inaczej uchwyt mrugnąłby
    // z powrotem na poprzednie miejsce.
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.currentTrack?.id) {
        scrubbingMs = null
        pendingSeekMs = null
    }
    LaunchedEffect(state.positionMs, pendingSeekMs) {
        val pending = pendingSeekMs ?: return@LaunchedEffect
        if (kotlin.math.abs(state.positionMs - pending) < SeekSettledToleranceMs) pendingSeekMs = null
    }
    val displayedMs = scrubbingMs ?: pendingSeekMs?.toFloat() ?: state.positionMs.toFloat()
    val durationMs = state.durationMs.coerceAtLeast(1).toFloat()
    Slider(
        value = displayedMs.coerceIn(0f, durationMs),
        onValueChange = { scrubbingMs = it },
        onValueChangeFinished = {
            val target = scrubbingMs?.toLong()
            scrubbingMs = null
            if (target != null) {
                pendingSeekMs = target
                scope.launch { container.audioPlayer.seekTo(target) }
            }
        },
        valueRange = 0f..durationMs,
        enabled = enabled && state.durationMs > 0,
        modifier = modifier,
    )
}

/** Na tyle blisko celu, że uznajemy seek za wykonany (player raportuje pozycję skokowo). */
private const val SeekSettledToleranceMs = 1_500L

private fun playerSubtitle(track: Track, state: PlayerState): String {
    val stream = streamDescription(state).takeIf(String::isNotBlank)
    return listOfNotNull(track.artists.joinToString().takeIf(String::isNotBlank), stream).joinToString(" • ")
}

private fun streamCodecLabel(state: PlayerState): String? = state.streamBitrate?.takeIf { it > 0 }?.let {
    when {
        state.streamCodec.orEmpty().contains("mp4a", ignoreCase = true) -> "AAC"
        state.streamCodec.orEmpty().contains("opus", ignoreCase = true) -> "Opus"
        state.streamCodec.isNullOrBlank() -> null
        else -> state.streamCodec
    }
}

private fun streamBitrateLabel(state: PlayerState): String? = state.streamBitrate?.takeIf { it > 0 }?.let { bitrate ->
    "${(bitrate + 500) / 1_000} kb/s"
}

private fun streamDescription(state: PlayerState): String = state.streamBitrate?.takeIf { it > 0 }?.let { bitrate ->
    val codec = when {
        state.streamCodec.orEmpty().contains("mp4a", ignoreCase = true) -> "AAC"
        state.streamCodec.orEmpty().contains("opus", ignoreCase = true) -> "Opus"
        state.streamCodec.isNullOrBlank() -> null
        else -> state.streamCodec
    }
    listOfNotNull(codec, "${(bitrate + 500) / 1_000} kb/s").joinToString(" • ")
}.orEmpty()

@Composable
internal fun PlayerBar(
    state: PlayerState,
    container: AppContainer,
    similarModeActive: Boolean,
    onSimilarModeChange: (Boolean) -> Unit,
    onOpenQueue: () -> Unit,
    isLiked: Boolean,
    favoriteLoading: Boolean,
    onToggleLiked: () -> Unit,
    collapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
) {
    val palette = LocalNutaPalette.current
    val scope = rememberCoroutineScope()
    val track = state.currentTrack
    if (collapsed) {
        // Na desktopie gest przeciągania nie ma sensu — zwijanie/rozwijanie idzie przyciskiem ▴/▾.
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(palette.chrome).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                track?.title ?: stringResource(Res.string.nothing_playing),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).clickable { onOpenQueue() },
            )
            Text(
                if (state.status == PlayerStatus.LOADING) "⏳︎" else if (state.status == PlayerStatus.PLAYING) "⏸" else "▶",
                color = MaterialTheme.colors.primary,
                fontSize = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
                    .clickable(enabled = track != null && state.status != PlayerStatus.LOADING) {
                        scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() }
                    },
            )
            Text("▴", fontSize = 18.sp, modifier = Modifier.clickable { onCollapsedChange(false) })
        }
        return
    }
    var radioLoading by remember { mutableStateOf(false) }
    var radioMessage by remember { mutableStateOf<String?>(null) }
    var radioMessageIsError by remember { mutableStateOf(false) }
    val radioDisabledLabel = stringResource(Res.string.radio_disabled)
    val radioAddedPrefix = stringResource(Res.string.radio_added_prefix)
    val radioFailedPrefix = stringResource(Res.string.radio_failed_prefix)
    val unknownErrorLabel = stringResource(Res.string.unknown_error)
    Row(
        Modifier.fillMaxWidth().height(82.dp).background(palette.chrome).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(track?.title ?: "N", track?.imageUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(230.dp)) {
            Text(track?.title ?: stringResource(Res.string.nothing_playing), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, lineHeight = 16.sp)
            Text(track?.let { playerSubtitle(it, state) } ?: stringResource(Res.string.choose_track), color = palette.muted, fontSize = 12.sp, maxLines = 1, lineHeight = 14.sp)
        }
        Text("▾", fontSize = 18.sp, modifier = Modifier.padding(start = 4.dp).clickable { onCollapsedChange(true) })
        Spacer(Modifier.width(14.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.previous() } }, enabled = track != null, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) { Text("⏮", fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { scope.launch { if (state.status == PlayerStatus.PLAYING) container.audioPlayer.pause() else container.audioPlayer.play() } }, enabled = track != null && state.status != PlayerStatus.LOADING, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) {
            if (state.status == PlayerStatus.LOADING) Text("⏳︎", fontSize = 28.sp)
            else Text(if (state.status == PlayerStatus.PLAYING) "⏸" else "▶", fontSize = 28.sp)
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.stop() } }, enabled = track != null && state.status != PlayerStatus.IDLE, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) { Text("⏹", fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = { scope.launch { container.audioPlayer.next() } }, enabled = track != null, modifier = Modifier.size(74.dp), contentPadding = PaddingValues(0.dp)) { Text("⏭", fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = onToggleLiked,
            enabled = track != null && !favoriteLoading,
            modifier = Modifier.size(74.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isLiked) Color(0xFFFF4D67) else Color.White),
        ) { Text(if (favoriteLoading) "…" else if (isLiked) "♥" else "♡", fontSize = 30.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = { scope.launch { container.audioPlayer.shuffleUpcoming(); onOpenQueue() } },
            enabled = state.queue.size > 1,
            modifier = Modifier.size(74.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = if (state.shuffleEnabled) palette.primaryVariant else Color.Transparent,
                contentColor = if (state.shuffleEnabled) Color.White else MaterialTheme.colors.primary,
            ),
        ) { Text("⇄", fontWeight = FontWeight.Bold, fontSize = 28.sp) }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(
            onClick = {
                if (similarModeActive) {
                    onSimilarModeChange(false)
                    radioMessage = radioDisabledLabel
                    radioMessageIsError = false
                    return@OutlinedButton
                }
                val seed = track ?: return@OutlinedButton
                scope.launch {
                    radioLoading = true
                    radioMessage = null
                    try {
                            val recommendations = container.spotifyRepository.getTrackRadio(seed)
                            val queue = recommendations
                            val additions = recommendations.filterNot { candidate -> state.queue.any { it.id == candidate.id } }
                            if (state.queue.isEmpty()) container.audioPlayer.setQueue(listOf(seed) + additions, 0)
                            else container.audioPlayer.appendToQueue(additions)
                            radioMessage = "$radioAddedPrefix ${queue.size}"
                            radioMessageIsError = false
                            onSimilarModeChange(true)
                            onOpenQueue()
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        radioMessage = "$radioFailedPrefix ${error.message ?: unknownErrorLabel}"
                        radioMessageIsError = true
                    } finally {
                        radioLoading = false
                    }
                }
            },
            enabled = track != null && !radioLoading,
            modifier = Modifier.size(74.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                backgroundColor = if (similarModeActive) palette.primaryVariant else Color.Transparent,
                contentColor = if (similarModeActive) Color.White else MaterialTheme.colors.primary,
            ),
        ) { Text(if (radioLoading) "…" else "♬+", fontSize = 22.sp) }
        Spacer(Modifier.width(18.dp))
        Text(formatTime(state.positionMs), color = palette.muted, fontSize = 11.sp)
        PositionSlider(
            state = state,
            container = container,
            enabled = track != null,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Text(formatTime(state.durationMs), color = palette.muted, fontSize = 11.sp)
        Spacer(Modifier.width(14.dp))
        Text(
            when (state.status) {
                PlayerStatus.LOADING -> stringResource(Res.string.status_buffering)
                PlayerStatus.PLAYING -> stringResource(Res.string.status_playing)
                PlayerStatus.PAUSED -> stringResource(Res.string.status_paused)
                PlayerStatus.ERROR -> stringResource(Res.string.status_error)
                else -> state.status.name.lowercase()
            },
            color = if (state.status == PlayerStatus.ERROR) palette.danger else MaterialTheme.colors.primary,
            fontSize = 11.sp,
        )
        radioMessage?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = if (radioMessageIsError) palette.danger else palette.success, fontSize = 11.sp, maxLines = 1)
        }
    }
}
