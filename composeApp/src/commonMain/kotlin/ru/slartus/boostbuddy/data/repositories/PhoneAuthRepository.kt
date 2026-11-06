// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.repositories

import ru.slartus.boostbuddy.data.api.PhoneAuthApi
import kotlinx.coroutines.CancellationException
import ru.slartus.boostbuddy.utils.toServerException

internal class PhoneAuthRepository(
    private val phoneAuthApi: PhoneAuthApi,
) {
    suspend fun sendSmsCode(
        deviceId: String,
        phone: String,
        transport: String?,
    ): Result<PhoneCodeChallengeInfo> = phoneAuthResult {
        val response = phoneAuthApi.sendPhoneVerificationCode(
            deviceId = deviceId,
            phone = phone,
            transport = transport,
        )
        val challenge = response.data?.phoneCode ?: error("empty phoneCode")
        PhoneCodeChallengeInfo(
            code = challenge.code?.takeIf { it.isNotBlank() } ?: error("empty code"),
            sentTransport = challenge.sentTransport,
            expiresInSeconds = (challenge.expiresIn ?: 0L) / 1000,
        )
    }

    suspend fun confirmSmsCode(
        deviceId: String,
        phone: String,
        challengeCode: String,
        smsCode: String,
    ): Result<PhoneAuthTokens> = phoneAuthResult {
        val response = phoneAuthApi.confirmPhoneVerificationCode(
            deviceId = deviceId,
            phone = phone,
            challengeCode = challengeCode,
            smsCode = smsCode,
        )
        PhoneAuthTokens(
            accessToken = response.accessToken?.takeIf { it.isNotBlank() }
                ?: error("empty access_token"),
            refreshToken = response.refreshToken.orEmpty(),
            expiresIn = response.expiresIn ?: 0L,
        )
    }
}

// Authentication exceptions can contain response bodies. Do not send them to logs.
private suspend fun <T> phoneAuthResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error.toServerException())
}

internal data class PhoneCodeChallengeInfo(
    val code: String,
    val sentTransport: String?,
    val expiresInSeconds: Long,
)

internal data class PhoneAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)
