package ru.slartus.boostbuddy.data.ktor

import com.russhwolf.settings.MapSettings
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.RedirectResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ru.slartus.boostbuddy.data.repositories.SessionTokens
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HttpClientAuthenticationTest {
    @Test
    fun unauthorizedRequestRetriesOnceWithRotatedCredentials() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        settings.replaceSession(SessionTokens("old-access", "old-refresh"))
        val seenTokens = mutableListOf<String?>()
        val apiEngine = MockEngine { request ->
            seenTokens += request.headers[HttpHeaders.Authorization]
            if (seenTokens.size == 1) respond("{}", HttpStatusCode.Unauthorized)
            else respond("ok")
        }
        val refreshEngine = MockEngine { request ->
            assertEquals("/oauth/token/", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond(
                """{"access_token":"new-access","refresh_token":"rotated-refresh","expires_in":3600}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val refreshClient = buildBoostyAuthHttpClient(false, Json, refreshEngine)
        val client = buildBoostyHttpClient(false, settings, Json, apiEngine, refreshClient)
        try {
            assertEquals("ok", client.get("v1/user/current").bodyAsText())
            assertEquals<List<String?>>(listOf("Bearer old-access", "Bearer new-access"), seenTokens)
            assertEquals(1, refreshEngine.requestHistory.size)
            assertEquals("rotated-refresh", settings.sessionSnapshot().tokens?.refreshToken)
        } finally {
            client.close()
        }
    }

    @Test
    fun storedBearerNeverGoesToAnAbsoluteForeignOrInsecureOrigin() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        settings.replaceSession(SessionTokens("private-access", "private-refresh"))
        val apiEngine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("ok")
        }
        val refreshEngine = MockEngine { error("No refresh should be requested") }
        val client = buildBoostyHttpClient(
            false, settings, Json, apiEngine, buildBoostyAuthHttpClient(false, Json, refreshEngine),
        )
        try {
            for (url in listOf(
                "https://example.invalid/",
                "https://api.boosty.to.example.invalid/",
                "http://api.boosty.to/",
                "https://api.boosty.to:444/",
                "https://user:password@api.boosty.to/",
            )) {
                client.get(url) { header(HttpHeaders.Authorization, "Bearer stale-value") }.bodyAsText()
            }
            assertEquals(5, apiEngine.requestHistory.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun authenticatedRequestDoesNotFollowAnExternalRedirect() = runTest {
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        settings.replaceSession(SessionTokens("private-access"))
        val apiEngine = MockEngine { request ->
            assertEquals("api.boosty.to", request.url.host)
            respond(
                "redirect",
                HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://example.invalid/collect"),
            )
        }
        val client = buildBoostyHttpClient(
            false, settings, Json, apiEngine,
            buildBoostyAuthHttpClient(false, Json, MockEngine { error("No refresh") }),
        )
        try {
            assertFailsWith<RedirectResponseException> { client.get("v1/user/current") }
            assertEquals(1, apiEngine.requestHistory.size)
        } finally {
            client.close()
        }
    }
}
