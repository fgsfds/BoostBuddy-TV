// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.auth

import androidx.compose.runtime.Stable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import ru.slartus.boostbuddy.components.BaseComponent
import ru.slartus.boostbuddy.data.Inject
import ru.slartus.boostbuddy.data.repositories.PhoneAuthRepository
import ru.slartus.boostbuddy.data.repositories.ProfileRepository
import ru.slartus.boostbuddy.data.repositories.SettingsRepository
import ru.slartus.boostbuddy.data.repositories.models.AuthResponse
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
    fun onBackToPhoneClick()
    fun onDismissError()
}

@Suppress("DEPRECATION")
internal class AuthComponentImpl(
    componentContext: ComponentContext,
    private val onLogined: () -> Unit,
) : BaseComponent<AuthViewState, Any>(componentContext, AuthViewState()), AuthComponent {
    private val settingsRepository by Inject.lazy<SettingsRepository>()
    private val profileRepository by Inject.lazy<ProfileRepository>()
    private val phoneAuthRepository by Inject.lazy<PhoneAuthRepository>()
    private val json by Inject.lazy<Json>()
    private val badTokens = mutableSetOf<String>()
    private var checkTokenJob: Job? = null
    private val clearTokenJob = scope.launch { clearToken() }

    private suspend fun clearToken() {
        settingsRepository.putAccessToken(null)
        settingsRepository.putRefreshToken(null)
        settingsRepository.putTokenExpiresAt(null)
    }

    override fun onCookiesChanged(cookies: String) {
        if (viewState.mode != AuthViewState.Mode.WebView || viewState.isLoading) return
        // Cookie parsing failures may contain credentials; never log the exception/body.
        val authResponse = runCatching {
            val authCookie = parseCookies(cookies)["auth"] ?: return
            json.decodeFromString<AuthResponse>(authCookie.decodeURLQueryComponent())
        }.getOrNull() ?: return
        val token = authResponse.accessToken?.takeIf { it.isNotBlank() } ?: return
        if (token in badTokens) return
        checkTokenJob?.cancel()
        checkTokenJob = scope.launch {
            clearTokenJob.join()
            if (viewState.mode != AuthViewState.Mode.WebView) return@launch
            viewState = viewState.copy(isLoading = true)
            try {
                settingsRepository.putAccessToken(token)
                settingsRepository.putRefreshToken(authResponse.refreshToken)
                settingsRepository.putTokenExpiresAt(authResponse.expiresAt)
                val profile = profileRepository.getProfile()
                currentCoroutineContext().ensureActive()
                if (profile.isSuccess) {
                    onLogined()
                } else {
                    badTokens.add(token)
                    clearToken()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearToken()
            } finally {
                viewState = viewState.copy(isLoading = false)
            }
        }
    }

    override fun onReloadClick() {
        if (viewState.isLoading) return
        viewState = viewState.copy(url = "${AuthViewState.AUTH_URL}?${Clock.System.now().epochSeconds}")
    }

    override fun onSwitchToPhoneLoginClick() {
        if (viewState.isLoading) return
        checkTokenJob?.cancel()
        viewState = viewState.copy(
            mode = AuthViewState.Mode.EnterPhone,
            smsCode = "",
            challengeCode = null,
            challengePhone = null,
            errorMessage = null,
        )
    }

    override fun onSwitchToWebViewClick() {
        if (viewState.isLoading) return
        viewState = viewState.copy(mode = AuthViewState.Mode.WebView, errorMessage = null)
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
        viewState = viewState.copy(
            mode = AuthViewState.Mode.EnterPhone,
            smsCode = "",
            challengeCode = null,
            challengePhone = null,
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
        // Keep the existing one-minute resend cooldown even if the request times out:
        // Boosty may have sent the code before the response was lost.
        viewState = state.copy(
            isLoading = true,
            errorMessage = null,
            resendAvailableAtEpochMs = now + RESEND_COOLDOWN_MS,
        )
        scope.launch {
            try {
                clearTokenJob.join()
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
                clearTokenJob.join()
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
                settingsRepository.putAccessToken(tokens.accessToken)
                settingsRepository.putRefreshToken(tokens.refreshToken)
                settingsRepository.putTokenExpiresAt(
                    tokens.expiresIn.takeIf { it > 0 }?.let { Clock.System.now().epochSeconds + it }
                )
                val profile = profileRepository.getProfile()
                currentCoroutineContext().ensureActive()
                if (profile.isSuccess) {
                    viewState = viewState.copy(isLoading = false, smsCode = "", challengeCode = null)
                    onLogined()
                } else {
                    clearToken()
                    viewState = viewState.copy(
                        isLoading = false,
                        errorMessage = "Не удалось загрузить профиль Boosty. Проверьте соединение и запросите новый код или войдите через сайт.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                clearToken()
                viewState = viewState.copy(
                    isLoading = false,
                    errorMessage = authErrorMessage(error, confirmingCode = true),
                )
            }
        }
    }

    private fun parseCookies(cookies: String): Map<String, String> =
        cookies.split(';').mapNotNull { cookie ->
            val parts = cookie.trim().split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
}

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
