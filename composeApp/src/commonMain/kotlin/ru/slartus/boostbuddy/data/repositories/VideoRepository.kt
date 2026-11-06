// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.repositories

import ru.slartus.boostbuddy.data.api.BoostyApi
import ru.slartus.boostbuddy.data.log.logger
import kotlinx.coroutines.CancellationException

internal class VideoRepository(
    private val boostyApi: BoostyApi
) {
    suspend fun putTimeCode(videoId: String, timeCode: Long): Result<Unit> = runCatching {
        boostyApi.putVideoTimeCode(videoId, timeCode)
        Unit
    }.onFailure {
        if (it is CancellationException) throw it
        logger.e("Unable to save video progress")
    }
}
