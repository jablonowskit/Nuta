package app.nuta.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.media3.common.MediaMetadata
import app.nuta.core.models.Track
import java.io.ByteArrayOutputStream

/**
 * Metadane sesji multimediów, które Samsung One UI pokazuje w chipie paska stanu.
 *
 * Sam tytuł/wykonawca nie wystarcza: chip zostaje przy poprzednim utworze, dopóki nie
 * zmieni się bitmapa okładki. Zrzuty z 2026-10-03 — w apce i w szufladzie powiadomień
 * Limahl, w pasku nadal „ZZ Top". Płytka-litera z `track.id` wymusza odświeżenie nawet
 * gdy ListenBrainz/MusicBrainz nie dają `imageUrl`.
 */
internal object NotificationArtwork {
    private val tileColors = intArrayOf(0xFF2D6A4F.toInt(), 0xFF40916C.toInt(), 0xFF1B4332.toInt(), 0xFF52B788.toInt())

    fun metadata(track: Track): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString())
            .setAlbumTitle(track.album)
            .setArtworkData(letterTile(track), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        track.imageUrl?.takeIf { it.isNotBlank() }?.let { builder.setArtworkUri(Uri.parse(it)) }
        return builder.build()
    }

    private fun letterTile(track: Track): ByteArray {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(tileColors[(track.id.hashCode() and Int.MAX_VALUE) % tileColors.size])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = size * 0.45f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val letter = track.title.firstOrNull()?.uppercaseChar()?.toString() ?: "N"
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, paint)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }
}
