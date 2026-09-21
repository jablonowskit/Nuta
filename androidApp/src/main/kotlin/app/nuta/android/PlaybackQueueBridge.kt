package app.nuta.android

import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.flow.MutableStateFlow

object PlaybackQueueBridge {
    /** Cache zbuforowanych strumieni — do odczytu rozmiaru i czyszczenia z Ustawień. Powstaje
        leniwie, przy pierwszym otwarciu strumienia (patrz LazyCacheDataSourceFactory), więc przed
        pierwszym odtworzeniem jest nullem i Ustawienia pokazują wtedy 0 B. */
    @Volatile var streamCache: Cache? = null

    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrevious: (() -> Unit)? = null

    /** Prawdziwy stan buforowania ExoPlayera — sesja maskuje BUFFERING jako READY dla systemowych kontrolek. */
    val buffering = MutableStateFlow(false)

    /** Czy kolejka appki ma następny/poprzedni utwór — dla przycisków systemowych. */
    val hasNext = MutableStateFlow(false)
    val hasPrevious = MutableStateFlow(false)
}
