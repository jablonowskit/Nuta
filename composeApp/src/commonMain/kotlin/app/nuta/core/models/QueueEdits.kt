package app.nuta.core.models

/**
 * Edycje kolejki wspólne dla wszystkich playerów (Media3, mpv, fake) — każdy tylko podmienia
 * swój stan i zapisuje kolejkę. Bieżący utwór nigdy się nie zmienia: przesuwa się najwyżej jego
 * indeks, więc odtwarzanie leci dalej bez przeładowania strumienia.
 */

/** Wstawia [tracks] zaraz za bieżącym utworem („Odtwórz jako następny”). */
fun PlayerState.withInsertedNext(tracks: List<Track>): PlayerState {
    if (tracks.isEmpty()) return this
    val at = (currentIndex + 1).coerceIn(0, queue.size)
    return copy(queue = queue.toMutableList().apply { addAll(at, tracks) })
}

/** Przenosi utwór z [from] na [to] (przeciąganie w kolejce). */
fun PlayerState.withMoved(from: Int, to: Int): PlayerState {
    if (from !in queue.indices || to !in queue.indices || from == to) return this
    val newQueue = queue.toMutableList().apply { add(to, removeAt(from)) }
    val newCurrent = when {
        currentIndex == from -> to
        from < currentIndex && to >= currentIndex -> currentIndex - 1
        from > currentIndex && to <= currentIndex -> currentIndex + 1
        else -> currentIndex
    }
    return copy(queue = newQueue, currentIndex = newCurrent)
}
