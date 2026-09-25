package app.nuta.core.models

/**
 * Uzupełnia długość bieżącego utworu, gdy katalog jej nie podał (MusicBrainz ma nagrania z
 * `length: null`), długością faktycznie odtwarzanego nagrania. Bez tego `durationMs` zostawało 0:
 * pasek pozycji nie działał, a przewijanie wracało na początek (zgłoszone 25.09.2026).
 * Znanej długości nie nadpisujemy — to ona jest punktem odniesienia przy wyborze nagrania.
 */
fun PlayerState.withResolvedDuration(trackId: String, resolvedDurationMs: Long?): PlayerState {
    if (resolvedDurationMs == null || resolvedDurationMs <= 0) return this
    val index = currentIndex
    val current = queue.getOrNull(index) ?: return this
    if (current.id != trackId || current.durationMs > 0) return this
    return copy(queue = queue.toMutableList().also { it[index] = current.copy(durationMs = resolvedDurationMs) })
}
