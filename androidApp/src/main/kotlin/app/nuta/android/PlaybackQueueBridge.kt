package app.nuta.android

object PlaybackQueueBridge {
    @Volatile var onNext: (() -> Unit)? = null
    @Volatile var onPrevious: (() -> Unit)? = null
}
