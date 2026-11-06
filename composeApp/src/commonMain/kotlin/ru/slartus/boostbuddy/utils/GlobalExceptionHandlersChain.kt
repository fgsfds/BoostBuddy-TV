// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.utils

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update

typealias GlobalExceptionHandler = (Throwable) -> Boolean

class GlobalExceptionHandlersChain(
    registerPlatformHandler: (GlobalExceptionHandlersChain) -> Unit = ::registerGlobalExceptionHandler,
) {
    private data class RegisteredHandler(val id: Long, val handler: GlobalExceptionHandler)

    private val nextHandlerId = atomic(0L)
    private val handlers = atomic<List<RegisteredHandler>>(emptyList())

    init {
        registerPlatformHandler(this)
    }

    fun registerHandler(handler: GlobalExceptionHandler): Long {
        // Activity recreation can register several handlers within one second.
        // A timestamp used as an ID silently replaced the previous activity's handler.
        val newHandlerId = nextHandlerId.incrementAndGet()
        handlers.update { it + RegisteredHandler(newHandlerId, handler) }
        return newHandlerId
    }

    fun unregisterHandler(handlerId: Long): Boolean {
        while (true) {
            val current = handlers.value
            val remaining = current.filterNot { it.id == handlerId }
            if (remaining.size == current.size) return false
            if (handlers.compareAndSet(current, remaining)) return true
        }
    }

    operator fun invoke(error: Throwable): Boolean {
        // Read a snapshot: a handler may unregister itself during dispatch, and
        // platform errors may arrive concurrently with activity destruction.
        return handlers.value.asReversed().any { it.handler(error) }
    }
}
