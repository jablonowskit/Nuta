package app.nuta.android

import kotlinx.coroutines.flow.MutableStateFlow

object PlaybackQueueBridge {
    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrevious: (() -> Unit)? = null

    /** Prawdziwy stan buforowania ExoPlayera — sesja maskuje BUFFERING jako READY dla systemowych kontrolek. */
    val buffering = MutableStateFlow(false)
}
