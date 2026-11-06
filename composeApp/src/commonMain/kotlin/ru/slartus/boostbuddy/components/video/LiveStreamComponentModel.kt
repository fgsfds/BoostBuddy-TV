// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.video

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.slartus.boostbuddy.data.repositories.StreamRepository

/**
 * Heartbeat для live-стрима: периодический PUT на server чтобы он знал, что
 * клиент смотрит. VideoComponentImpl создаёт экземпляр только когда
 * `liveBlogUrl != null` — для VOD-флоу модель отсутствует.
 */
internal class LiveStreamComponentModel(
    private val scope: CoroutineScope,
    private val streamRepository: StreamRepository,
    private val blogUrl: String,
    private val isSessionActive: () -> Boolean,
) {
    // Stopping is idempotent across playback-ended and component destruction.
    private val heartbeatJob = atomic<Job?>(null)
    private val heartbeatStopped = atomic(true)
    private val stopJob = atomic<Job?>(null)

    fun startHeartbeat() {
        if (heartbeatJob.value?.isActive == true || !isSessionActive()) return
        heartbeatStopped.value = false
        heartbeatJob.getAndSet(null)?.cancel()
        val pendingStop = stopJob.value
        heartbeatJob.value = scope.launch {
            pendingStop?.join()
            while (isActive && isSessionActive()) {
                streamRepository.heartbeat(blogUrl, stop = false)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    fun stopHeartbeat() {
        if (!heartbeatStopped.compareAndSet(expect = false, update = true)) return
        heartbeatJob.getAndSet(null)?.cancel()
        if (!isSessionActive()) return
        stopJob.value = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                withTimeoutOrNull(STOP_TIMEOUT_MS) {
                    if (isSessionActive()) streamRepository.heartbeat(blogUrl, stop = true)
                }
            }
        }
    }

    private companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        private const val STOP_TIMEOUT_MS = 1_500L
    }
}
