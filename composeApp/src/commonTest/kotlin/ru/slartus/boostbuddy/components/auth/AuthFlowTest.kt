package ru.slartus.boostbuddy.components.auth

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import ru.slartus.boostbuddy.data.api.BoostyApi
import ru.slartus.boostbuddy.data.api.PhoneAuthApi
import ru.slartus.boostbuddy.data.ktor.buildBoostyAuthHttpClient
import ru.slartus.boostbuddy.data.repositories.PhoneAuthRepository
import ru.slartus.boostbuddy.data.repositories.ProfileRepository
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthFlowTest {
    @Test
    fun profileRetryReusesConfirmedCredentialsWithoutPublishingOrResendingCode() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        var sends = 0
        var confirmations = 0
        var profiles = 0
        val loggedIn = CompletableDeferred<Unit>()
        val client = buildBoostyAuthHttpClient(false, Json, MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/auth/phone/verification_code/send" -> {
                    sends++
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertTrue((request.body as TextContent).text.contains("phone=%2B381641234567"))
                    """{"data":{"phoneCode":{"code":"challenge","sentTransport":"app","expiresIn":60000}}}"""
                }
                "/auth/phone/verification_code/confirm" -> {
                    confirmations++
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertTrue((request.body as TextContent).text.contains("sms_code=1234"))
                    """{"access_token":"candidate","refresh_token":"candidate-refresh","expires_in":3600}"""
                }
                "/v1/user/current" -> {
                    profiles++
                    assertEquals("Bearer candidate", request.headers[HttpHeaders.Authorization])
                    assertNull(settings.sessionSnapshot().tokens)
                    if (profiles == 1) return@MockEngine respond("{}", HttpStatusCode.ServiceUnavailable)
                    """{"id":"profile-id"}"""
                }
                else -> error("Unexpected request")
            }
            respond(body, headers = jsonHeaders)
        })
        val component = authComponent(lifecycle, settings, client) { loggedIn.complete(Unit) }
        try {
            component.onPhoneChanged("64 123 4567")
            component.onSendSmsCodeClick()
            component.onSendSmsCodeClick()
            component.awaitState { it.mode == AuthViewState.Mode.EnterSmsCode && !it.isLoading }
            component.onResendSmsCodeClick()
            assertEquals(1, sends)
            component.onSmsCodeChanged("12 34")
            component.onConfirmSmsCodeClick()
            val failed = component.awaitState { it.canRetryLogin && !it.isLoading }
            assertEquals(AuthViewState.Mode.CompleteLogin, failed.mode)
            assertEquals("", failed.smsCode)
            assertNull(failed.challengeCode)
            assertNull(settings.sessionSnapshot().tokens)
            assertFalse(loggedIn.isCompleted)
            component.onDismissError()
            component.onRetryLoginClick()
            loggedIn.await()
            assertEquals(1, sends)
            assertEquals(1, confirmations)
            assertEquals(2, profiles)
            assertEquals("candidate", settings.sessionSnapshot().tokens?.accessToken)
            assertEquals("candidate-refresh", settings.sessionSnapshot().tokens?.refreshToken)
        } finally {
            lifecycle.destroy()
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun successfulCandidateValidationAfterLogoutCannotRecreateASession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var loggedIn = false
        val client = buildBoostyAuthHttpClient(false, Json, MockEngine {
            started.complete(Unit)
            response.await()
            respond("""{"id":"profile-id"}""", headers = jsonHeaders)
        })
        val component = authComponent(lifecycle, settings, client) { loggedIn = true }
        try {
            component.onSwitchToWebViewClick()
            component.onCookiesChanged("""auth={"accessToken":"candidate","refreshToken":"refresh"}""")
            started.await()
            settings.clearSession()
            response.complete(Unit)
            component.awaitState { !it.isLoading && it.errorMessage != null }
            assertNull(settings.sessionSnapshot().tokens)
            assertFalse(loggedIn)
        } finally {
            lifecycle.destroy()
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun leavingAuthenticationDuringValidationNeverPublishesTheCandidate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val settings = SettingsRepository(MapSettings(), backgroundScope)
        val lifecycle = LifecycleRegistry(Lifecycle.State.RESUMED)
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        var loggedIn = false
        val client = buildBoostyAuthHttpClient(false, Json, MockEngine {
            started.complete(Unit)
            response.await()
            respond("""{"id":"profile-id"}""", headers = jsonHeaders)
        })
        val component = authComponent(lifecycle, settings, client) { loggedIn = true }
        try {
            component.onSwitchToWebViewClick()
            component.onCookiesChanged("""auth={"accessToken":"candidate"}""")
            started.await()
            lifecycle.destroy()
            response.complete(Unit)
            component.scope.coroutineContext[Job]!!.join()
            assertNull(settings.sessionSnapshot().tokens)
            assertFalse(loggedIn)
        } finally {
            client.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cookieParsingPreservesEqualsAndRejectsMalformedPayloads() {
        val parsed = decodeAuthCookie("""other=value; auth={"accessToken":"value=="}; end=1""", Json)
        assertNotNull(parsed)
        assertEquals("value==", parsed.accessToken)
        assertNull(decodeAuthCookie("auth=not-json", Json))
        assertNull(decodeAuthCookie("other=value", Json))
    }

    private fun authComponent(
        lifecycle: LifecycleRegistry,
        settings: SettingsRepository,
        client: HttpClient,
        onLogin: () -> Unit,
    ) = AuthComponentImpl(
        componentContext = DefaultComponentContext(lifecycle),
        onLogined = onLogin,
        settingsRepository = settings,
        profileRepository = ProfileRepository(BoostyApi(client), client),
        phoneAuthRepository = PhoneAuthRepository(PhoneAuthApi(client)),
        json = Json,
    )

    private suspend fun AuthComponent.awaitState(predicate: (AuthViewState) -> Boolean): AuthViewState {
        viewStates.value.takeIf(predicate)?.let { return it }
        val result = CompletableDeferred<AuthViewState>()
        val observer: (AuthViewState) -> Unit = { if (predicate(it)) result.complete(it) }
        val subscription = viewStates.subscribe(observer)
        return try { result.await() } finally { subscription.cancel() }
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
