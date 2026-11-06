// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.ktor

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import ru.slartus.boostbuddy.data.log.logger
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import ru.slartus.boostbuddy.data.repositories.SessionTokens
import ru.slartus.boostbuddy.data.repositories.tokenExpiryEpochSeconds
import ru.slartus.boostbuddy.data.repositories.models.TokenRefreshResponse

internal const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"

internal fun buildBoostyHttpClient(
    debugLog: Boolean,
    settingsRepository: SettingsRepository,
    json: Json,
    engine: HttpClientEngine? = null,
    refreshClient: HttpClient = buildBoostyAuthHttpClient(debugLog, json),
): HttpClient {
    val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokenManager = SessionTokenManager(
        settings = settingsRepository,
        refreshScope = refreshScope,
        nowSeconds = { Clock.System.now().epochSeconds },
    ) { tokens ->
        try {
            val deviceId = settingsRepository.getOrCreateDeviceId()
            val response: TokenRefreshResponse = refreshClient.post("oauth/token/") {
                header("X-From-Id", deviceId)
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("device_id", deviceId)
                            append("device_os", "web")
                            append("grant_type", "refresh_token")
                            append("refresh_token", requireNotNull(tokens.refreshToken))
                        }
                    )
                )
            }.body()
            response.accessToken?.takeIf { it.isNotBlank() }?.let { accessToken ->
                SessionTokens(
                    accessToken = accessToken,
                    refreshToken = response.refreshToken?.takeIf { it.isNotBlank() }
                        ?: tokens.refreshToken,
                    expiresAt = tokenExpiryEpochSeconds(Clock.System.now().epochSeconds, response.expiresIn),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // HTTP exceptions may include full bodies, credentials or URLs.
            logger.e("Token refresh failed: ${error::class.simpleName}")
            null
        }
    }

    val tokenRefreshPlugin = createClientPlugin("BoostyTokenRefresh") {
        on(Send) { request ->
            // An absolute URL must never receive Boosty credentials. Authenticated API calls
            // also disable redirects below, so credentials cannot cross an origin boundary.
            request.headers.remove(HttpHeaders.Authorization)
            if (!isBoostyApiUrl(request.url.build())) return@on proceed(request)

            val snapshot = tokenManager.currentInSameSession(tokenManager.forRequest())
            snapshot.tokens?.accessToken?.let { token ->
                request.headers[HttpHeaders.Authorization] = "Bearer $token"
            }
            val originalCall = proceed(request)
            tokenManager.currentInSameSession(snapshot)
            if (originalCall.response.status == HttpStatusCode.Unauthorized) {
                val refreshed = tokenManager.afterUnauthorized(snapshot)
                val retrySession = refreshed?.let(tokenManager::currentInSameSession)
                val newToken = retrySession?.tokens?.accessToken
                if (retrySession != null && newToken != null) {
                    request.headers[HttpHeaders.Authorization] = "Bearer $newToken"
                    proceed(request).also { tokenManager.currentInSameSession(retrySession) }
                } else {
                    originalCall
                }
            } else {
                originalCall
            }
        }
        onClose {
            refreshScope.cancel()
            refreshClient.close()
        }
    }

    return buildHttpClient(debugLog, json, engine = engine) {
        followRedirects = false
        install(tokenRefreshPlugin)
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.boosty.to"
            }
        }
    }
}

internal fun isBoostyApiUrl(url: Url): Boolean =
    url.protocol == URLProtocol.HTTPS && url.host.equals("api.boosty.to", ignoreCase = true) &&
        url.port == 443 && url.user == null && url.password == null

/**
 * Клиент для phone-auth эндпоинтов Boosty: без Auth-плагина (чтобы не уходил пустой
 * `Authorization: Bearer`), с десктопным User-Agent и web-специфичными заголовками
 * (`X-App`, `X-Locale`, `Referer`, `Origin`, `Accept`), как шлёт браузер.
 * `X-From-Id` выставляется на уровне каждого запроса отдельно (это deviceId).
 */
internal fun buildBoostyAuthHttpClient(
    debugLog: Boolean,
    json: Json,
    engine: HttpClientEngine? = null,
) = buildHttpClient(debugLog, json, userAgent = DESKTOP_USER_AGENT, engine = engine) {
        // Verification codes, refresh tokens and candidate Bearer tokens stay on this origin.
        followRedirects = false
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.boosty.to"
            }
            header("X-App", "web")
            header("X-Locale", "ru_RU")
            header(HttpHeaders.Referrer, "https://boosty.to/")
            header(HttpHeaders.Origin, "https://boosty.to")
            header(HttpHeaders.Accept, "application/json, text/plain, */*")
            headers.remove(HttpHeaders.AcceptCharset)
        }
    }

internal fun buildGithubHttpClient(json: Json, debugLog: Boolean) =
    buildHttpClient(debugLog, json) {
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.github.com"
            }
        }
    }

internal fun buildHttpClient(
    isDebug: Boolean,
    json: Json,
    userAgent: String = USER_AGENT,
    engine: HttpClientEngine? = null,
    block: HttpClientConfig<*>.() -> Unit = {}
): HttpClient {
    val configure: HttpClientConfig<*>.() -> Unit = {

        expectSuccess = true

        install(HttpTimeout) {
            connectTimeoutMillis = 15000
            requestTimeoutMillis = 30000
            socketTimeoutMillis = 30000
        }

        install(UserAgent) {
            agent = userAgent
        }

        install(ContentNegotiation) {
            json(json)
        }

        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    ru.slartus.boostbuddy.data.log.logger.d(
                        message.replace(
                            Regex("Bearer\\s+[^\\s-]+", RegexOption.IGNORE_CASE),
                            "Bearer secret"
                        )
                    )
                }
            }
            // Account tokens, verification codes and signed media URLs must
            // never be written to logcat, including in installable debug builds.
            level = LogLevel.NONE
        }
        block(this)
    }
    // The factory overload owns its production engine; injected test engines stay caller-owned.
    return if (engine == null) HttpClient(HttpEngineFactory().createEngine(isDebug), configure)
    else HttpClient(engine, configure)
}
