package app.nuta.player

import app.nuta.core.models.Track

/**
 * Miejsce przerwania w długich utworach (miksy, sety, słuchowiska): ponowne odtworzenie — także
 * po przejściu na inny utwór i powrocie — zaczyna od niego zamiast od 0:00. Piosenek to nie
 * dotyczy, te gra się od początku.
 *
 * Wpis znika, gdy utwór dobiegnie końca albo słuchacz cofnie się na sam początek. Zapis jako
 * tekst („id<TAB>pozycja” w linii) przez [load]/[save]: SharedPreferences na Androidzie, plik
 * na desktopie. Najstarsze wpisy ponad [MaxEntries] wypadają.
 */
class LongTrackPositions(private val load: () -> String, private val save: (String) -> Unit) {
    private val lock = Any()
    private val positions: LinkedHashMap<String, Long> by lazy { decode(load()) }

    fun positionFor(track: Track): Long? = synchronized(lock) { positions[track.id] }

    /** Wołane z pozycją grającego utworu; [durationMs] decyduje, czy utwór w ogóle jest „długi”. */
    fun remember(track: Track, positionMs: Long, durationMs: Long) {
        if (durationMs < LongTrackMs) return
        synchronized(lock) {
            if (positionMs < MinPositionMs || positionMs > durationMs - EndMarginMs) {
                positions.remove(track.id) ?: return
            } else {
                if (positions[track.id] == positionMs) return
                positions.remove(track.id)
                positions[track.id] = positionMs
                while (positions.size > MaxEntries) positions.remove(positions.keys.first())
            }
            save(encode(positions))
        }
    }

    /** Utwór dograny do końca — następnym razem od początku. */
    fun forget(track: Track) = synchronized(lock) {
        positions.remove(track.id) ?: return
        save(encode(positions))
    }

    companion object {
        const val LongTrackMs = 15 * 60_000L
        /** Krócej słuchane to raczej „sprawdzam, co to” niż przerwany odsłuch. */
        const val MinPositionMs = 30_000L
        /** Przerwane tuż przed końcem traktujemy jak dograne. */
        const val EndMarginMs = 30_000L
        const val MaxEntries = 50

        internal fun encode(positions: Map<String, Long>): String =
            positions.entries.joinToString("\n") { (id, position) -> "$id\t$position" }

        internal fun decode(text: String): LinkedHashMap<String, Long> = LinkedHashMap<String, Long>().apply {
            text.lineSequence().forEach { line ->
                val id = line.substringBefore('\t')
                val position = line.substringAfter('\t', "").toLongOrNull()
                if (id.isNotBlank() && position != null) put(id, position)
            }
        }
    }
}
