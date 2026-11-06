// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.utils

import ru.slartus.boostbuddy.data.log.logger
import kotlinx.coroutines.CancellationException

suspend fun <T> fetchOrError(block: suspend () -> T): Result<T> {
    return runCatching {
        val data = block()

        Result.success(data)
    }.getOrElse {
        if (it is CancellationException) throw it
        // HTTP exception messages may contain signed URLs or response bodies.
        logger.e("Request failed: ${it::class.simpleName}")
        Result.failure(it.toServerException())
    }
}
