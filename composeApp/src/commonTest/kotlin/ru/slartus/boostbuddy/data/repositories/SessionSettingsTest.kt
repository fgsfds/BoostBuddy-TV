package ru.slartus.boostbuddy.data.repositories

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.ObservableSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionSettingsTest {
    @Test
    fun legacyCredentialsMigrateAsOneRecordAndSurviveRestart() = runTest {
        val storage = MapSettings().apply {
            putString("access_token", "legacy-access")
            putString("refresh_token", "legacy-refresh")
            putLong("token_expires_at", 42L)
        }
        val settings = SettingsRepository(storage, backgroundScope)
        val expected = SessionTokens("legacy-access", "legacy-refresh", 42L)
        assertEquals(expected, settings.sessionSnapshot().tokens)
        assertSame(settings.tokenFlow, settings.tokenFlow)
        assertFalse(storage.hasKey("access_token"))
        assertFalse(storage.hasKey("refresh_token"))
        assertEquals(expected, SettingsRepository(storage, backgroundScope).sessionSnapshot().tokens)
    }

    @Test
    fun logoutAndCorruptNewRecordNeverFallBackToLegacyTokens() = runTest {
        val storage = MapSettings()
        val settings = SettingsRepository(storage, backgroundScope)
        settings.replaceSession(SessionTokens("access", "refresh"))
        settings.clearSession()
        storage.putString("access_token", "stale-access")
        assertNull(SettingsRepository(storage, backgroundScope).sessionSnapshot().tokens)
        storage.putString("auth_session_v1", "broken record")
        storage.putString("access_token", "stale-access")
        assertNull(SettingsRepository(storage, backgroundScope).sessionSnapshot().tokens)
    }

    @Test
    fun refreshPreservesIdentityAndCannotOverwriteLogoutOrNewLogin() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val original = settings.replaceSession(SessionTokens("old", "old-refresh"))!!
        val refreshed = settings.updateSession(original.revision, SessionTokens("new", "new-refresh"))!!
        assertEquals(original.sessionId, refreshed.sessionId)
        assertTrue(refreshed.revision > original.revision)
        assertNull(settings.updateSession(original.revision, SessionTokens("late", "late-refresh")))
        settings.clearSession()
        assertNull(settings.updateSession(refreshed.revision, SessionTokens("late", "late-refresh")))
        val replacement = settings.replaceSession(SessionTokens("another-account"))!!
        assertNull(settings.replaceSession(SessionTokens("stale-login"), refreshed.revision))
        assertEquals(replacement, settings.sessionSnapshot())
    }

    @Test
    fun authEntryCanClearItsRotatedSessionButNotAReplacementLogin() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val original = settings.replaceSession(SessionTokens("original", "refresh"))!!
        settings.updateSession(original.revision, SessionTokens("rotated", "rotated-refresh"))
        assertTrue(settings.clearSession(expectedSessionId = original.sessionId) != null)
        val replacement = settings.replaceSession(SessionTokens("another-account"))!!
        assertNull(settings.clearSession(expectedSessionId = original.sessionId))
        assertEquals(replacement, settings.sessionSnapshot())
    }

    @Test
    fun cancellationAfterStorageWriteCannotLeavePublishedTokensOutOfSync() = runTest {
        val backing = MapSettings()
        var cancelWriter: (() -> Unit)? = null
        val storage = object : ObservableSettings by backing {
            override fun putString(key: String, value: String) {
                backing.putString(key, value)
                if (key == "auth_session_v1") cancelWriter?.invoke()
            }
        }
        val settings = SettingsRepository(storage, backgroundScope)
        val tokens = SessionTokens("new-access", "rotated-refresh", 100L)
        val writer = launch(start = CoroutineStart.LAZY) { settings.replaceSession(tokens) }
        cancelWriter = { writer.cancel() }
        writer.start()
        writer.join()
        cancelWriter = null
        assertTrue(writer.isCancelled)
        assertEquals(tokens, settings.sessionSnapshot().tokens)
        assertEquals(tokens, SettingsRepository(storage, backgroundScope).sessionSnapshot().tokens)
    }

    @Test
    fun credentialDiagnosticsAndInvalidExpiryAreSafe() {
        val tokens = SessionTokens("secret-access", "secret-refresh", 100L)
        assertFalse(tokens.toString().contains("secret"))
        assertFalse(SessionSnapshot(1, 1, tokens).toString().contains("secret"))
        assertEquals(160L, tokenExpiryEpochSeconds(100L, 60L))
        assertNull(tokenExpiryEpochSeconds(100L, null))
        assertNull(tokenExpiryEpochSeconds(100L, 0L))
        assertNull(tokenExpiryEpochSeconds(100L, Long.MAX_VALUE))
    }
}
