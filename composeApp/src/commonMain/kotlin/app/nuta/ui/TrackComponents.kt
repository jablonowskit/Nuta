package app.nuta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nuta.core.models.Track
import app.nuta.resources.Res
import app.nuta.resources.add_to_playlist_menu
import app.nuta.resources.play_next
import app.nuta.resources.track_radio
import app.nuta.resources.share_track
import app.nuta.resources.music_video
import app.nuta.listenbrainz.ListenBrainzRepository
import app.nuta.settings.DataSource
import app.nuta.AppContainer
import kotlinx.coroutines.CancellationException
import app.nuta.ui.theme.LocalNutaPalette
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Wiersz utworu i jego przyciski akcji — używane przez ekrany Playlisty, Ulubione, Szukaj
 * i Kolejka, więc mieszkają obok siebie, a nie w którymkolwiek z tych ekranów.
 */
@Composable
internal fun TrackRow(
    track: Track,
    active: Boolean,
    loading: Boolean = false,
    onPlay: () -> Unit,
    titleAction: (@Composable () -> Unit)? = null,
    subtitleAction: (@Composable () -> Unit)? = null,
    // Zamiast trzeciej ikony "☰" na stałe widocznej w wierszu: przytrzymanie utworu
    // otwiera dodawanie do playlisty — mniej ikon, więcej miejsca, gest znany z innych appek.
    onLongPress: (() -> Unit)? = null,
    // Z tym przytrzymanie otwiera menu („Odtwórz jako następny” + dodawanie do playlisty)
    // zamiast od razu dialogu playlist.
    onPlayNext: (() -> Unit)? = null,
    onRadio: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onVideo: (() -> Unit)? = null,
) {
    val palette = LocalNutaPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    val hasMenu = onPlayNext != null || onRadio != null || onShare != null || onVideo != null
    val longPress: (() -> Unit)? = if (hasMenu) ({ menuOpen = true }) else onLongPress
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 520.dp
    val rowModifier = Modifier.fillMaxWidth()
        .background(if (active) palette.activeHighlight else Color.Transparent, RoundedCornerShape(8.dp))
        .let { base ->
            if (longPress != null) base.combinedClickable(onClick = onPlay, onLongClick = longPress)
            else base.clickable(onClick = onPlay)
        }
        .padding(horizontal = 12.dp, vertical = 8.dp)
    Row(
        rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Okładka z ikoną stanu na wierzchu: aktywny utwór nadal widać po ▶/⏳ (i po tle wiersza).
        Box(Modifier.padding(end = 10.dp), contentAlignment = Alignment.Center) {
            Cover(track.title, track.imageUrl, Modifier.size(40.dp), zoomable = true)
            if (active) {
                Box(Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(if (loading) "⏳︎" else "▶", color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            // Jedna linia z wielokropkiem zamiast zawijania do 2 linii — długie tytuły
            // (częste, np. "(feat. ...)"/"Radio Edit") wcześniej prawie zawsze zajmowały
            // dodatkową linię, znacząco zmniejszając liczbę widocznych utworów na ekranie.
            Text(track.title, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.artists.joinToString(), modifier = Modifier.weight(1f), color = palette.muted, fontSize = 12.sp)
                Text(formatTime(track.durationMs), color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
            }
        }
        if (!compact) Text(track.album, color = palette.muted, fontSize = 12.sp, modifier = Modifier.width(170.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (titleAction != null || subtitleAction != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                titleAction?.invoke()
                subtitleAction?.invoke()
            }
        }
    }
    if (hasMenu) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onPlayNext != null) {
                DropdownMenuItem(onClick = { menuOpen = false; onPlayNext() }) { Text(stringResource(Res.string.play_next)) }
            }
            if (onRadio != null) {
                DropdownMenuItem(onClick = { menuOpen = false; onRadio() }) { Text(stringResource(Res.string.track_radio)) }
            }
            if (onShare != null) {
                DropdownMenuItem(onClick = { menuOpen = false; onShare() }) { Text(stringResource(Res.string.share_track)) }
            }
            if (onVideo != null) {
                DropdownMenuItem(onClick = { menuOpen = false; onVideo() }) { Text(stringResource(Res.string.music_video)) }
            }
            if (onLongPress != null) {
                DropdownMenuItem(onClick = { menuOpen = false; onLongPress() }) { Text(stringResource(Res.string.add_to_playlist_menu)) }
            }
        }
    }
    }
}

/**
 * Ikonka akcji przy utworze z wyraźnym potwierdzeniem kliknięcia: sam ripple na przycisku 24dp jest
 * ledwo widoczny, a efekt akcji (start odtwarzania, dopisanie do kolejki) bywa opóźniony o sieć,
 * więc przez chwilę wyglądało to, jakby przycisk nie działał.
 */
@Composable
private fun TrackActionButton(label: String, confirmLabel: String, fontSize: TextUnit, onClick: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val palette = app.nuta.ui.theme.LocalNutaPalette.current
    LaunchedEffect(confirming) {
        if (confirming) { delay(700); confirming = false }
    }
    Box(
        Modifier.size(24.dp)
            .background(if (confirming) MaterialTheme.colors.primary else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { confirming = true; onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (confirming) confirmLabel else label,
            fontSize = fontSize,
            color = if (confirming) palette.onPrimary else MaterialTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Tekst do udostępnienia: „Tytuł — Wykonawcy” + link, jeśli da się go zbudować z id.
 * MBID → MusicBrainz (otwiera się bez konta), Spotify → open.spotify.com, MSID i reszta → sam tekst.
 */
internal fun trackShareText(track: Track, dataSource: DataSource): String {
    val label = listOf(track.title, track.artists.joinToString()).filter(String::isNotBlank).joinToString(" — ")
    val link = when {
        ListenBrainzRepository.MbidRegex.matches(track.id) -> "https://musicbrainz.org/recording/${track.id}"
        dataSource == DataSource.SPOTIFY && SpotifyIdRegex.matches(track.id) -> "https://open.spotify.com/track/${track.id}"
        else -> null
    }
    return if (link == null) label else "$label\n$link"
}

private val SpotifyIdRegex = Regex("^[0-9A-Za-z]{22}$")

/**
 * Wyszukiwanie teledysku w YouTube. Nie film, z którego gra dźwięk: dopasowanie celowo wybiera
 * wersję audio (często kanał „- Topic” ze statyczną okładką), a nie teledysk. Na Androidzie link
 * youtube.com otwiera aplikację YouTube, jeśli jest zainstalowana.
 */
internal fun musicVideoUrl(track: Track): String {
    val query = listOf(track.artists.firstOrNull().orEmpty(), track.title, "official video").filter(String::isNotBlank).joinToString(" ")
    return "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8")
}

internal fun openMusicVideo(track: Track) = openUrlInBrowser(musicVideoUrl(track))

/** „YT” w rozwiniętym playerze — teledysk bieżącego utworu (patrz [musicVideoUrl]). */
@Composable
internal fun MusicVideoButton(track: Track?, compact: Boolean = false) {
    Text(
        "YT",
        color = if (track != null) MaterialTheme.colors.primary else LocalNutaPalette.current.muted,
        fontSize = if (compact) 10.sp else 13.sp,
        lineHeight = if (compact) CompactIconLineHeight else TextUnit.Unspecified,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clickable(enabled = track != null) { track?.let(::openMusicVideo) }.padding(iconPadding(compact)),
    )
}

/** Ikony w pionie obok tytułu (telefon) muszą zmieścić się we wysokości dwóch linii tekstu. */
internal val CompactIconLineHeight = 14.sp

internal fun iconPadding(compact: Boolean): PaddingValues =
    if (compact) PaddingValues(horizontal = 10.dp, vertical = 1.dp) else PaddingValues(horizontal = 8.dp, vertical = 6.dp)

/** Udostępnia [track] systemowo (desktop: schowek). Źródło danych czytane w chwili kliknięcia. */
internal fun shareTrack(container: AppContainer, track: Track) =
    shareText(trackShareText(track, container.playbackSettings.settings.value.dataSource))

/**
 * „Graj podobne”: kolejka = ten utwór + radio od niego. Najpierw pobieramy radio, potem
 * startujemy — gdyby utwór ruszył sam, QueueContinuation (ostatni w kolejce) dokładałby
 * równolegle drugie radio.
 */
internal suspend fun startTrackRadio(container: AppContainer, track: Track) {
    val radio = try {
        container.spotifyRepository.getTrackRadio(track)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        container.logger.warn("TrackRadio", "radio_failed", "Nie udało się pobrać podobnych utworów", fields = mapOf("reason" to (error.message ?: "unknown")))
        emptyList()
    }
    container.audioPlayer.setQueue(listOf(track) + radio.filterNot { it.id == track.id }, 0)
    container.audioPlayer.play()
}

@Composable
internal fun TrackPlayButton(onClick: () -> Unit) = TrackActionButton("▶", "▶", 11.sp, onClick)

@Composable
internal fun TrackQueueButton(onClick: () -> Unit) = TrackActionButton("+", "✓", 12.sp, onClick)
