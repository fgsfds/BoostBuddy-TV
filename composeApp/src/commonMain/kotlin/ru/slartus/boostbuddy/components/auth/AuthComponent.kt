// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.auth

import androidx.compose.runtime.Stable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import ru.slartus.boostbuddy.components.BaseComponent
import ru.slartus.boostbuddy.data.Inject
import ru.slartus.boostbuddy.data.repositories.PhoneAuthRepository
import ru.slartus.boostbuddy.data.repositories.ProfileRepository
import ru.slartus.boostbuddy.data.repositories.SessionTokens
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import ru.slartus.boostbuddy.data.repositories.models.AuthResponse
import ru.slartus.boostbuddy.data.repositories.tokenExpiryEpochSeconds
import ru.slartus.boostbuddy.utils.ServerException

@Stable
interface AuthComponent {
    val viewStates: Value<AuthViewState>
    fun onCookiesChanged(cookies: String)
    fun onReloadClick()
    fun onSwitchToPhoneLoginClick()
    fun onSwitchToWebViewClick()
    fun onCountryCodeChanged(value: String)
    fun onPhoneChanged(value: String)
    fun onSmsCodeChanged(value: String)
    fun onSendSmsCodeClick()
    fun onResendSmsCodeClick()
    fun onConfirmSmsCodeClick()
    fun onRetryLoginClick()
    fun onBackToPhoneClick()
    fun onDismissError()
}

@Suppress("DEPRECATION")
internal class AuthComponentImpl(
    componentContext: ComponentContext,
    private val onLogined: () -> Unit,
    private val settingsRepository: SettingsRepository = Inject.instance(),
    private val profileRepository: ProfileRepository = Inject.instance(),
    private val phoneAuthRepository: PhoneAuthRepository = Inject.instance(),
    private val json: Json = Inject.instance(),
) : BaseComponent<AuthViewState, Any>(componentContext, AuthViewState()), AuthComponent {
    private var rejectedWebToken: String? = null
    private var pendingLogin: PendingLogin? = null
    private val initialSessionId = settingsRepository.sessionSnapshot().sessionId
    private val loginRevision = scope.async {
        settingsRepository.clearSession(expectedSessionId = initialSessionId)?.revision
    }

    override fun onCookiesChanged(cookies: String) {
        if (viewState.mode != AuthViewState.Mode.WebView || viewState.isLoading) return
        val authResponse = decodeAuthCookie(cookies, json) ?: return
        val token = authResponse.accessToken?.takeIf { it.isNotBlank() } ?: return
        if (token == rejectedWebToken || token == pendingLogin?.tokens?.accessToken) return
        val candidate = PendingLogin(
            tokens = SessionTokens(
                accessToken = token,
                refreshToken = authResponse.refreshToken?.takeIf { it.isNotBlank() },
                expiresAt = authResponse.expiresAt?.takeIf { it > 0 },
            ),
            fromWeb = true,
        )
        pendingLogin = candidate
        viewState = viewState.copy(isLoading = true, canRetryLogin = false, errorMessage = null)
        scope.launch { validateCandidate(candidate) }
    }

    override fun onReloadClick() {
        if (viewState.isLoading) return
        pendingLogin = null
        rejectedWebToken = null
        viewState = viewState.copy(
            url = "${AuthViewState.AUTH_URL}?${Clock.System.now().toEpochMilliseconds()}",
            canRetryLogin = false,
            errorMessage = null,
        )
    }

    override fun onSwitchToPhoneLoginClick() = onBackToPhoneClick()

    override fun onSwitchToWebViewClick() {
        if (viewState.isLoading) return
        pendingLogin = null
        viewState = viewState.copy(
            mode = AuthViewState.Mode.WebView,
            smsCode = "",
            challengeCode = null,
            challengePhone = null,
            canRetryLogin = false,
            errorMessage = null,
        )
    }

    override fun onCountryCodeChanged(value: String) {
        if (viewState.isLoading || viewState.mode != AuthViewState.Mode.EnterPhone) return
        viewState = viewState.copy(countryCode = value)
    }

    override fun onPhoneChanged(value: String) {
        if (viewState.isLoading || viewState.mode != AuthViewState.Mode.EnterPhone) return
        viewState = viewState.copy(phone = value)
    }

    override fun onSmsCodeChanged(value: String) {
        if (viewState.isLoading || viewState.mode != AuthViewState.Mode.EnterSmsCode) return
        viewState = viewState.copy(smsCode = value.filter { it in '0'..'9' })
    }

    override fun onBackToPhoneClick() {
        if (viewState.isLoading) return
        pendingLogin = null
        viewState = viewState.copy(
            mode = AuthViewState.Mode.EnterPhone,
            smsCode = "",
            challengeCode = null,
            challengePhone = null,
            canRetryLogin = false,
            errorMessage = null,
        )
    }

    override fun onDismissError() {
        viewState = viewState.copy(errorMessage = null)
    }

    override fun onSendSmsCodeClick() {
        if (viewState.mode != AuthViewState.Mode.EnterPhone) return
        requestCode(transport = null)
    }

    override fun onResendSmsCodeClick() {
        if (viewState.mode != AuthViewState.Mode.EnterSmsCode) return
        requestCode(transport = "gate")
    }

    private fun requestCode(transport: String?) {
        val state = viewState
        if (state.isLoading) return
        val now = Clock.System.now().toEpochMilliseconds()
        if ((state.resendAvailableAtEpochMs ?: 0L) > now) return
        val phone = if (state.mode == AuthViewState.Mode.EnterSmsCode) {
            state.challengePhone
        } else {
            normalizePhoneNumber(state.countryCode, state.phone)
        }
        if (phone == null) {
            viewState = state.copy(
                errorMessage = "Проверьте код страны и номер. Например: +381 и 64 123 4567, или +7 и 999 123 4567. Не добавляйте код страны во второе поле.",
            )
            return
        }
        // The server may have sent a code even if its response is lost: retain the cooldown.
        viewState = state.copy(
            isLoading = true,
            errorMessage = null,
            resendAvailableAtEpochMs = now + RESEND_COOLDOWN_MS,
        )
        scope.launch {
            try {
                if (loginRevision.await() == null) return@launch sessionChanged()
                val result = phoneAuthRepository.sendSmsCode(
                    deviceId = settingsRepository.getOrCreateDeviceId(),
                    phone = phone,
                    transport = transport,
                )
                currentCoroutineContext().ensureActive()
                result.onSuccess { challenge ->
                    viewState = viewState.copy(
                        isLoading = false,
                        challengeCode = challenge.code,
                        challengePhone = phone,
                        smsCode = "",
                        resendAvailableAtEpochMs = Clock.System.now().toEpochMilliseconds() + RESEND_COOLDOWN_MS,
                        mode = AuthViewState.Mode.EnterSmsCode,
                    )
                }.onFailure { error ->
                    viewState = viewState.copy(
                        isLoading = false,
                        errorMessage = authErrorMessage(error, confirmingCode = false),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewState = viewState.copy(
                    isLoading = false,
                    errorMessage = authErrorMessage(error, confirmingCode = false),
                )
            }
        }
    }

    override fun onConfirmSmsCodeClick() {
        val state = viewState
        if (state.isLoading || state.mode != AuthViewState.Mode.EnterSmsCode) return
        val phone = state.challengePhone
        val challenge = state.challengeCode
        if (phone == null || challenge == null) {
            viewState = state.copy(errorMessage = "Запросите новый код.")
            return
        }
        val code = state.smsCode.trim()
        if (code.isEmpty()) {
            viewState = state.copy(errorMessage = "Введите код подтверждения.")
            return
        }
        viewState = state.copy(isLoading = true, errorMessage = null)
        scope.launch {
            try {
                if (loginRevision.await() == null) return@launch sessionChanged()
                val result = phoneAuthRepository.confirmSmsCode(
                    deviceId = settingsRepository.getOrCreateDeviceId(),
                    phone = phone,
                    challengeCode = challenge,
                    smsCode = code,
                )
                currentCoroutineContext().ensureActive()
                val tokens = result.getOrElse { error ->
                    viewState = viewState.copy(
                        isLoading = false,
                        errorMessage = authErrorMessage(error, confirmingCode = true),
                    )
                    return@launch
                }
                val candidate = PendingLogin(
                    tokens = SessionTokens(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken.takeIf { it.isNotBlank() },
                        expiresAt = tokenExpiryEpochSeconds(Clock.System.now().epochSeconds, tokens.expiresIn),
                    ),
                    fromWeb = false,
                )
                pendingLogin = candidate
                // A confirmed code is consumed. Retry profile validation without sending or
                // confirming another code; credentials remain private until validation succeeds.
                viewState = viewState.copy(
                    mode = AuthViewState.Mode.CompleteLogin,
                    smsCode = "",
                    challengeCode = null,
                )
                validateCandidate(candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewState = viewState.copy(
                    isLoading = false,
                    errorMessage = authErrorMessage(error, confirmingCode = true),
                )
            }
        }
    }

    override fun onRetryLoginClick() {
        if (viewState.isLoading) return
        val candidate = pendingLogin ?: return
        viewState = viewState.copy(isLoading = true, canRetryLogin = false, errorMessage = null)
        scope.launch { validateCandidate(candidate) }
    }

    private suspend fun validateCandidate(candidate: PendingLogin) {
        try {
            val revision = loginRevision.await() ?: return sessionChanged()
            if (settingsRepository.sessionSnapshot().revision != revision) return sessionChanged()
            val result = profileRepository.getProfileForToken(candidate.tokens.accessToken)
            currentCoroutineContext().ensureActive()
            if (result.isSuccess) {
                val committed = settingsRepository.replaceSession(candidate.tokens, expectedRevision = revision)
                currentCoroutineContext().ensureActive()
                if (committed == null) return sessionChanged()
                pendingLogin = null
                viewState = viewState.copy(isLoading = false, canRetryLogin = false, smsCode = "", challengeCode = null)
                onLogined()
                return
            }
            val error = result.exceptionOrNull()
            if (error is ServerException.Unauthorized ||
                (error is ServerException.Request && error.code.value == 403)
            ) {
                pendingLogin = null
                if (candidate.fromWeb) rejectedWebToken = candidate.tokens.accessToken
                viewState = viewState.copy(
                    isLoading = false,
                    canRetryLogin = false,
                    mode = if (candidate.fromWeb) AuthViewState.Mode.WebView else AuthViewState.Mode.EnterPhone,
                    errorMessage = "Boosty не подтвердил вход. Войдите через сайт или запросите новый код.",
                )
            } else {
                profileRetryAvailable()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            profileRetryAvailable()
        }
    }

    private fun profileRetryAvailable() {
        viewState = viewState.copy(
            isLoading = false,
            canRetryLogin = true,
            errorMessage = "Не удалось проверить аккаунт Boosty. Проверьте соединение и нажмите «Повторить вход». Новый код не нужен.",
        )
    }

    private fun sessionChanged() {
        pendingLogin = null
        viewState = viewState.copy(
            isLoading = false,
            canRetryLogin = false,
            errorMessage = "Сеанс входа изменился. Откройте экран входа заново.",
        )
    }

    private class PendingLogin(val tokens: SessionTokens, val fromWeb: Boolean)
}

/** Invalid cookie payloads are ignored without logging their contents. */
internal fun decodeAuthCookie(cookies: String, json: Json): AuthResponse? = runCatching {
    val cookie = cookies.split(';').firstNotNullOfOrNull { entry ->
        val parts = entry.trim().split('=', limit = 2)
        parts.getOrNull(1)?.takeIf { parts[0] == "auth" }
    } ?: return null
    json.decodeFromString<AuthResponse>(cookie.decodeURLQueryComponent())
}.getOrNull()

private const val RESEND_COOLDOWN_MS = 60_000L

private fun authErrorMessage(error: Throwable, confirmingCode: Boolean): String = when {
    error is ServerException.Request && error.code.value == 429 ->
        "Слишком много попыток. Подождите несколько минут и попробуйте снова."
    error is ServerException.Request && error.code.value == 403 ->
        "Boosty отклонил вход или требует дополнительного подтверждения. Попробуйте войти через сайт."
    error is ServerException.Unauthorized ||
        (error is ServerException.Request && error.code.value == 400) ->
        if (confirmingCode) "Код не подошёл или срок его действия истёк. Проверьте код или запросите новый."
        else "Boosty не принял номер. Проверьте код страны и номер или войдите через сайт."
    else -> if (confirmingCode) "Не удалось войти. Проверьте соединение и попробуйте ещё раз."
    else "Не удалось получить код. Проверьте соединение и попробуйте снова после окончания таймера."
}
