package ru.slartus.boostbuddy.components.video

internal data class PlaybackCheckpoint(val position: Long, val playWhenReady: Boolean)

/** Owns a player independently of whether a stopped UI is able to recompose. */
internal class PlaybackLifecycleController<T : Any>(
    initialCheckpoint: PlaybackCheckpoint,
    private val create: (PlaybackCheckpoint) -> T,
    private val snapshot: (T) -> PlaybackCheckpoint,
    private val release: (T) -> Unit,
    private val onChanged: (T?) -> Unit,
) {
    var current: T? = null
        private set
    private var checkpoint = initialCheckpoint

    fun start() {
        if (current != null) return
        val resource = create(checkpoint)
        current = resource
        onChanged(resource)
    }

    fun stop() {
        val resource = current ?: return
        // Invalidate first, so retained effects cannot use the released generation.
        current = null
        try {
            checkpoint = snapshot(resource)
        } finally {
            try {
                release(resource)
            } finally {
                onChanged(null)
            }
        }
    }
}
