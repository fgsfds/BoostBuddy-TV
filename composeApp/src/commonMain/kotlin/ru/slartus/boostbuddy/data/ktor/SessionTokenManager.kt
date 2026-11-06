package ru.slartus.boostbuddy.data.ktor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.slartus.boostbuddy.data.repositories.SessionSnapshot
import ru.slartus.boostbuddy.data.repositories.SessionTokens
import ru.slartus.boostbuddy.data.repositories.SettingsRepository

/** Coalesces refreshes without making the lifetime of a rotated token depend on one screen. */
internal class SessionTokenManager(
    private val settings: SettingsRepository,
    private val refreshScope: CoroutineScope,
    private val nowSeconds: () -> Long,
    private val refreshTokens: suspend (SessionTokens) -> SessionTokens?,
) {
    private val refreshLock = Mutex()
    private var inFlight: Pair<Long, Deferred<SessionSnapshot?>>? = null

    suspend fun forRequest(): SessionSnapshot {
        val snapshot = settings.sessionSnapshot()
        val tokens = snapshot.tokens ?: return snapshot
        val expiresAt = tokens.expiresAt
        if (expiresAt != null && expiresAt <= nowSeconds() + REFRESH_THRESHOLD_SECONDS) {
            refresh(snapshot)
        }
        return currentInSameSession(snapshot)
    }

    suspend fun afterUnauthorized(rejected: SessionSnapshot): SessionSnapshot? {
        val current = currentInSameSession(rejected)
        if (current.tokens == null) return null
        // Another request may already have refreshed while this request was in flight.
        if (current.revision != rejected.revision) return current
        val refreshed = refresh(rejected)
        currentInSameSession(rejected)
        return refreshed
    }

    fun currentInSameSession(snapshot: SessionSnapshot): SessionSnapshot {
        val current = settings.sessionSnapshot()
        if (current.sessionId != snapshot.sessionId) {
            // Never replay an old request with a different account's credentials, or deliver
            // its old account data after logout. Cancellation is not a login/network error.
            throw CancellationException("Boosty session changed")
        }
        return current
    }

    private suspend fun refresh(snapshot: SessionSnapshot): SessionSnapshot? {
        val refresh = refreshLock.withLock {
            val current = currentInSameSession(snapshot)
            if (current.revision != snapshot.revision) return current
            val tokens = current.tokens ?: return null
            if (tokens.refreshToken.isNullOrBlank()) return null
            inFlight?.takeIf { it.first == current.revision && it.second.isActive }?.second
                ?: refreshScope.async {
                    val replacement = refreshTokens(tokens) ?: return@async null
                    settings.updateSession(current.revision, replacement)
                }.also { inFlight = current.revision to it }
        }
        // Cancelling a caller does not cancel the shared refresh: the server may already have
        // rotated its refresh token. Logout still wins through the revision compare-and-set.
        return refresh.await()
    }

    private companion object {
        const val REFRESH_THRESHOLD_SECONDS = 300L
    }
}
