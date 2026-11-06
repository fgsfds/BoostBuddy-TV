package ru.slartus.boostbuddy.data.ktor

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import ru.slartus.boostbuddy.data.repositories.SessionTokens
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SessionTokenManagerTest {
    @Test
    fun simultaneousAndLateUnauthorizedResponsesShareOneRotation() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val original = settings.replaceSession(SessionTokens("old", "old-refresh"))!!
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SessionTokens>()
        var refreshes = 0
        val manager = SessionTokenManager(settings, backgroundScope, { 100L }) {
            refreshes++
            started.complete(Unit)
            response.await()
        }
        val first = async { manager.afterUnauthorized(original) }
        started.await()
        val second = async { manager.afterUnauthorized(original) }
        response.complete(SessionTokens("new", "rotated-refresh", 1000L))
        assertEquals("new", first.await()?.tokens?.accessToken)
        assertEquals("new", second.await()?.tokens?.accessToken)
        assertEquals("new", manager.afterUnauthorized(original)?.tokens?.accessToken)
        assertEquals(1, refreshes)
        assertEquals("rotated-refresh", settings.sessionSnapshot().tokens?.refreshToken)
    }

    @Test
    fun refreshFinishingAfterLogoutCannotRestoreTheSession() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val original = settings.replaceSession(SessionTokens("old", "refresh"))!!
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SessionTokens>()
        val manager = SessionTokenManager(settings, backgroundScope, { 100L }) {
            started.complete(Unit)
            response.await()
        }
        val request = async { manager.afterUnauthorized(original) }
        started.await()
        settings.clearSession()
        response.complete(SessionTokens("late", "late-refresh"))
        assertFailsWith<CancellationException> { request.await() }
        assertNull(settings.sessionSnapshot().tokens)
    }

    @Test
    fun refreshCannotReplayAnOldRequestUnderAReplacementAccount() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val original = settings.replaceSession(SessionTokens("old", "refresh"))!!
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SessionTokens>()
        val manager = SessionTokenManager(settings, backgroundScope, { 100L }) {
            started.complete(Unit)
            response.await()
        }
        val request = async { manager.afterUnauthorized(original) }
        started.await()
        settings.replaceSession(SessionTokens("other-account", "other-refresh"))
        response.complete(SessionTokens("late", "late-refresh"))
        assertFailsWith<CancellationException> { request.await() }
        assertEquals("other-account", settings.sessionSnapshot().tokens?.accessToken)
    }

    @Test
    fun cancellingTheWaitingScreenStillPersistsARotatedRefreshToken() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        settings.replaceSession(SessionTokens("old", "refresh", 101L))
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<SessionTokens>()
        val manager = SessionTokenManager(settings, backgroundScope, { 100L }) {
            started.complete(Unit)
            response.await()
        }
        val screenRequest = async { manager.forRequest() }
        started.await()
        screenRequest.cancelAndJoin()
        response.complete(SessionTokens("new", "rotated-refresh", 1000L))
        val updated = settings.sessionFlow.first { it.tokens?.accessToken == "new" }
        assertEquals("rotated-refresh", updated.tokens?.refreshToken)
    }

    @Test
    fun transientProactiveRefreshFailureKeepsCurrentCredentials() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val original = settings.replaceSession(SessionTokens("old", "refresh", 101L))!!
        val manager = SessionTokenManager(settings, backgroundScope, { 100L }) { null }
        assertEquals(original, manager.forRequest())
        assertEquals(original, settings.sessionSnapshot())
    }
}
