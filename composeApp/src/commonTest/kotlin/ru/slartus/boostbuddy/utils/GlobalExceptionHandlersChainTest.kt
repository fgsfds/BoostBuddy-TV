package ru.slartus.boostbuddy.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlobalExceptionHandlersChainTest {
    private fun chain() = GlobalExceptionHandlersChain(registerPlatformHandler = {})

    @Test
    fun rapidRegistrationsKeepBothHandlersAndUseDistinctIds() {
        val chain = chain()
        val visited = mutableListOf<String>()
        val first = chain.registerHandler { visited += "first"; true }
        val second = chain.registerHandler { visited += "second"; false }

        assertNotEquals(first, second)
        assertTrue(chain(IllegalStateException("test")))
        assertEquals(listOf("second", "first"), visited)
    }

    @Test
    fun unregisteringDestroyedActivityRestoresPreviousHandler() {
        val chain = chain()
        var previousCalls = 0
        var destroyedCalls = 0
        chain.registerHandler { previousCalls++; true }
        val destroyed = chain.registerHandler { destroyedCalls++; true }

        assertTrue(chain.unregisterHandler(destroyed))
        assertFalse(chain.unregisterHandler(destroyed))
        assertTrue(chain(IllegalStateException("test")))
        assertEquals(1, previousCalls)
        assertEquals(0, destroyedCalls)
    }

    @Test
    fun latestAcceptingHandlerStopsDispatch() {
        val chain = chain()
        chain.registerHandler { error("Older handler must not run") }
        chain.registerHandler { true }

        assertTrue(chain(IllegalStateException("test")))
    }

    @Test
    fun handlerCanUnregisterItselfWhileDispatching() {
        val chain = chain()
        var handlerId = 0L
        handlerId = chain.registerHandler {
            assertTrue(chain.unregisterHandler(handlerId))
            true
        }

        assertTrue(chain(IllegalStateException("test")))
        assertFalse(chain(IllegalStateException("test")))
    }
}
