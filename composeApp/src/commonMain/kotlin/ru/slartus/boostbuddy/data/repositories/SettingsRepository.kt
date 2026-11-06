// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.repositories

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getBooleanFlow
import com.russhwolf.settings.coroutines.getBooleanOrNullFlow
import com.russhwolf.settings.coroutines.getFloatFlow
import com.russhwolf.settings.coroutines.getStringOrNullFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.slartus.boostbuddy.data.repositories.models.VideoQuality
import kotlin.random.Random

@OptIn(ExperimentalSettingsApi::class)
internal class SettingsRepository(
    private val settings: ObservableSettings,
    coroutineScope: CoroutineScope,
) {
    private val locker = Mutex()
    private val sessionState = MutableStateFlow(
        SessionSnapshot(revision = 0L, sessionId = 0L, tokens = readStoredSession())
    )

    /** One atomic snapshot; rotating tokens does not change the account/session identity. */
    val sessionFlow: StateFlow<SessionSnapshot> = sessionState.asStateFlow()
    val tokenFlow: StateFlow<String?> = sessionFlow.map { it.tokens?.accessToken }
        .stateIn(coroutineScope, SharingStarted.Eagerly, sessionState.value.tokens?.accessToken)

    fun sessionSnapshot(): SessionSnapshot = sessionState.value

    /** Commit a validated login only if the session in which it started still exists. */
    suspend fun replaceSession(
        tokens: SessionTokens,
        expectedRevision: Long? = null,
    ): SessionSnapshot? = changeSession(tokens, expectedRevision, replaceIdentity = true)

    /** A late refresh must never restore a logged-out session or overwrite another login. */
    suspend fun updateSession(expectedRevision: Long, tokens: SessionTokens): SessionSnapshot? =
        changeSession(tokens, expectedRevision, replaceIdentity = false)

    suspend fun clearSession(
        expectedRevision: Long? = null,
        expectedSessionId: Long? = null,
    ): SessionSnapshot? = changeSession(
        null, expectedRevision, replaceIdentity = true, expectedSessionId = expectedSessionId,
    )

    private suspend fun changeSession(
        tokens: SessionTokens?,
        expectedRevision: Long?,
        replaceIdentity: Boolean,
        expectedSessionId: Long? = null,
    ): SessionSnapshot? {
        currentCoroutineContext().ensureActive()
        // A credential transaction cannot be interrupted between durable storage and publication.
        // This lock covers local settings only, never a network request.
        return withContext(NonCancellable + Dispatchers.IO) {
            locker.withLock {
                val previous = sessionState.value
                if (expectedRevision != null && previous.revision != expectedRevision) {
                    return@withLock null
                }
                if (expectedSessionId != null && previous.sessionId != expectedSessionId) {
                    return@withLock null
                }
                val next = SessionSnapshot(
                    revision = previous.revision + 1,
                    sessionId = previous.sessionId + if (replaceIdentity) 1 else 0,
                    tokens = tokens,
                )
                // One record prevents mixed access/refresh tokens after cancellation or restart.
                // A stored null also prevents stale legacy keys from reviving a logged-out session.
                settings.putString(SESSION_KEY, sessionJson.encodeToString(tokens))
                sessionState.value = next
                next
            }
        }
    }

    private fun readStoredSession(): SessionTokens? {
        val record = settings.getStringOrNull(SESSION_KEY)
        if (record != null) {
            // Fail closed for a corrupt record; never fall back to stale legacy credentials.
            val tokens = runCatching { sessionJson.decodeFromString<SessionTokens?>(record) }.getOrNull()
            removeLegacySession()
            return tokens
        }
        val accessToken = settings.getStringOrNull(ACCESS_TOKEN_KEY)?.takeIf { it.isNotBlank() }
        val legacy = accessToken?.let {
            SessionTokens(
                accessToken = it,
                refreshToken = settings.getStringOrNull(REFRESH_TOKEN_KEY)?.takeIf { token -> token.isNotBlank() },
                expiresAt = settings.getLongOrNull(TOKEN_EXPIRES_AT_KEY),
            )
        }
        settings.putString(SESSION_KEY, sessionJson.encodeToString(legacy))
        removeLegacySession()
        return legacy
    }

    private fun removeLegacySession() {
        settings.remove(ACCESS_TOKEN_KEY)
        settings.remove(REFRESH_TOKEN_KEY)
        settings.remove(TOKEN_EXPIRES_AT_KEY)
    }

    val appSettingsFlow: Flow<AppSettings>
        get() = combine(
            settings.getBooleanOrNullFlow(DARK_MODE_KEY),
            settings.getBooleanFlow(SYSTEM_PLAYER_KEY, false),
            settings.getBooleanFlow(DEBUG_LOG, false),
            settings.getStringOrNullFlow(PREFERRED_QUALITY_KEY).map { it.toVideoQualityOrNull() },
            settings.getFloatFlow(PREFERRED_PLAYBACK_SPEED_KEY, DEFAULT_PLAYBACK_SPEED)
        ) { darkMode, systemPlayer, debugLog, preferredQuality, preferredPlaybackSpeed ->
            AppSettings(
                isDarkMode = darkMode,
                useSystemVideoPlayer = systemPlayer,
                debugLog = debugLog,
                preferredQuality = preferredQuality,
                preferredPlaybackSpeed = preferredPlaybackSpeed
            )
        }

    suspend fun getSettings(): AppSettings = withContext(Dispatchers.IO) {
        AppSettings(
            isDarkMode = settings.getBooleanOrNull(DARK_MODE_KEY),
            useSystemVideoPlayer = settings.getBoolean(SYSTEM_PLAYER_KEY, false),
            debugLog = settings.getBoolean(DEBUG_LOG, false),
            preferredQuality = settings.getStringOrNull(PREFERRED_QUALITY_KEY)
                .toVideoQualityOrNull(),
            preferredPlaybackSpeed = settings.getFloat(
                PREFERRED_PLAYBACK_SPEED_KEY,
                DEFAULT_PLAYBACK_SPEED
            )
        )
    }

    suspend fun setDarkMode(value: Boolean) = withContext(Dispatchers.IO) {
        putBoolean(DARK_MODE_KEY, value)
    }

    suspend fun setUseSystemVideoPlayer(value: Boolean) = withContext(Dispatchers.IO) {
        putBoolean(SYSTEM_PLAYER_KEY, value)
    }

    suspend fun setPreferredQuality(value: VideoQuality?) = withContext(Dispatchers.IO) {
        if (value == null) {
            remove(PREFERRED_QUALITY_KEY)
        } else {
            putString(PREFERRED_QUALITY_KEY, value.name)
        }
    }

    suspend fun setPreferredPlaybackSpeed(value: Float) = withContext(Dispatchers.IO) {
        putFloat(PREFERRED_PLAYBACK_SPEED_KEY, value)
    }

    suspend fun setDebugLog(value: Boolean) = withContext(Dispatchers.IO) {
        putBoolean(DEBUG_LOG, value)
    }

    suspend fun getAccessToken(): String? = sessionSnapshot().tokens?.accessToken

    suspend fun getOrCreateDeviceId(): String = withContext(Dispatchers.IO) {
        locker.withLock {
            val existing = settings.getStringOrNull(DEVICE_ID_KEY)
            if (existing != null) return@withLock existing
            val generated = generateDeviceId()
            settings.putString(DEVICE_ID_KEY, generated)
            generated
        }
    }

    private fun generateDeviceId(): String {
        val bytes = ByteArray(16).also { Random.nextBytes(it) }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        fun ByteArray.hex(from: Int, to: Int) = buildString {
            for (i in from until to) {
                val v = this@hex[i].toInt() and 0xff
                append(v.toString(16).padStart(2, '0'))
            }
        }
        return buildString {
            append(bytes.hex(0, 4)); append('-')
            append(bytes.hex(4, 6)); append('-')
            append(bytes.hex(6, 8)); append('-')
            append(bytes.hex(8, 10)); append('-')
            append(bytes.hex(10, 16))
        }
    }

    suspend fun setLastDonateNotifyVersion(version: String) =
        putString(LAST_DONATE_NOTIFY_VERSION_KEY, version)

    suspend fun getLastDonateNotifyVersion(): String? =
        getString(LAST_DONATE_NOTIFY_VERSION_KEY)

    private suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.getStringOrNull(key)
        }
    }

    private suspend fun putString(key: String, value: String) = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.putString(key, value)
        }
    }

    private suspend fun putBoolean(key: String, value: Boolean) = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.putBoolean(key, value)
        }
    }

    private suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            locker.withLock {
                settings.getBoolean(key, defaultValue)
            }
        }

    private suspend fun getBooleanOrNull(key: String): Boolean? = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.getBooleanOrNull(key)
        }
    }

    private suspend fun putLong(key: String, value: Long) = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.putLong(key, value)
        }
    }

    private suspend fun putFloat(key: String, value: Float) = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.putFloat(key, value)
        }
    }

    private suspend fun getLong(key: String) = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.getLongOrNull(key)
        }
    }

    private suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        locker.withLock {
            settings.remove(key)
        }
    }

    suspend fun getDonationPromptVersion(): String? =
        settings.getString(KEY_DONATION_PROMPT_VERSION, "")

    suspend fun setDonationPromptVersion(version: String) {
        settings.putString(KEY_DONATION_PROMPT_VERSION, version)
    }

    private companion object {
        val sessionJson = Json { ignoreUnknownKeys = true }
        const val SESSION_KEY = "auth_session_v1"
        const val KEY_DONATION_PROMPT_VERSION = "KEY_DONATION_PROMPT_VERSION"
        const val ACCESS_TOKEN_KEY = "access_token"
        const val REFRESH_TOKEN_KEY = "refresh_token"
        const val TOKEN_EXPIRES_AT_KEY = "token_expires_at"
        const val DEVICE_ID_KEY = "device_id"
        const val DARK_MODE_KEY = "dark_mode"
        const val SYSTEM_PLAYER_KEY = "system_player"
        const val DEBUG_LOG = "debug_log"
        const val LAST_DONATE_NOTIFY_VERSION_KEY = "LAST_DONATE_NOTIFY_VERSION_KEY"
        const val PREFERRED_QUALITY_KEY = "preferred_video_quality"
        const val PREFERRED_PLAYBACK_SPEED_KEY = "preferred_playback_speed"
        const val DEFAULT_PLAYBACK_SPEED = 1f
    }
}

/** Credentials deliberately redact their generated diagnostic representation. */
@Serializable
internal data class SessionTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long? = null,
) {
    init { require(accessToken.isNotBlank()) { "Empty access token" } }
    override fun toString(): String = "SessionTokens(redacted)"
}

internal data class SessionSnapshot(
    val revision: Long,
    val sessionId: Long,
    val tokens: SessionTokens?,
) {
    val isAuthenticated: Boolean get() = tokens != null
}

internal fun tokenExpiryEpochSeconds(now: Long, expiresInSeconds: Long?): Long? =
    expiresInSeconds?.takeIf { it > 0 && now <= Long.MAX_VALUE - it }?.let { now + it }

private fun String?.toVideoQualityOrNull(): VideoQuality? =
    this?.let { name -> VideoQuality.entries.firstOrNull { it.name == name } }

data class AppSettings(
    val isDarkMode: Boolean?,
    val useSystemVideoPlayer: Boolean,
    val debugLog: Boolean,
    val preferredQuality: VideoQuality?,
    val preferredPlaybackSpeed: Float
) {
    companion object {
        val Default: AppSettings = AppSettings(
            isDarkMode = null,
            useSystemVideoPlayer = false,
            debugLog = false,
            preferredQuality = null,
            preferredPlaybackSpeed = 1f
        )
    }
}

