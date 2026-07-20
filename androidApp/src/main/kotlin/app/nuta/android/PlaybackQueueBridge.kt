package app.nuta.android

import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.flow.MutableStateFlow

object PlaybackQueueBridge {
    /** Fabryka źródła danych z cache serwisu — prefetch dogrywa przez nią początek strumienia. */
    @Volatile var streamCacheFactory: CacheDataSource.Factory? = null

    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrevious: (() -> Unit)? = null

    /** Prawdziwy stan buforowania ExoPlayera — sesja maskuje BUFFERING jako READY dla systemowych kontrolek. */
    val buffering = MutableStateFlow(false)

    /** Czy kolejka appki ma następny/poprzedni utwór — dla przycisków systemowych. */
    val hasNext = MutableStateFlow(false)
    val hasPrevious = MutableStateFlow(false)
}
