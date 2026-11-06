// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** Serial, conflated progress writes; an unsuccessful save remains eligible for retry. */
internal class TimeCodeManager(
    private val scope: CoroutineScope,
    private val saveTimeCode: suspend (videoId: String, seconds: Long) -> Result<Unit>,
) {
    private data class Position(val contentId: String, val milliseconds: Long)

    private var contentId: String? = null
    private var latestPosition: Position? = null
    private var lastSentPosition: Long? = null
    private val positions = Channel<Position>(Channel.CONFLATED)
    private val writeMutex = Mutex()

    init {
        scope.launch {
            for (position in positions) persist(position, force = false)
        }
    }

    fun setContentId(id: String) {
        if (contentId == id) return
        contentId = id
        latestPosition = null
        lastSentPosition = null
    }

    fun onPositionChanged(position: Long) {
        // Media3 uses negative sentinel values before a timeline exists.
        if (position < 0) return
        val value = Position(contentId ?: return, position)
        latestPosition = value
        positions.trySend(value)
    }

    /** Capture synchronously, before navigation destroys the component scope. */
    fun putLastPosition() = latestPosition?.let { position ->
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Only this final snapshot may outlive the component, and only for a
            // bounded best-effort save. A slow network must never hold Back.
            withContext(NonCancellable) {
                withTimeoutOrNull(FINAL_SAVE_TIMEOUT_MS) { persist(position, force = true) }
            }
        }
    }

    private suspend fun persist(position: Position, force: Boolean) = writeMutex.withLock {
        if (position.contentId != contentId || position != latestPosition) return@withLock
        val difference = abs(position.milliseconds - (lastSentPosition ?: 0L))
        if ((lastSentPosition != null && difference == 0L) ||
            (!force && difference < SAVE_INTERVAL_MS)) return@withLock
        val result = saveTimeCode(position.contentId, position.milliseconds / 1000L)
        if (result.isSuccess && position.contentId == contentId) {
            lastSentPosition = position.milliseconds
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
        const val FINAL_SAVE_TIMEOUT_MS = 1_500L
    }
}
