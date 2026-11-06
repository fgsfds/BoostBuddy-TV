// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.components.auth

data class AuthViewState(
    val url: String = AUTH_URL,
    val mode: Mode = Mode.EnterPhone,
    val countryCode: String = "+381",
    val phone: String = "",
    val smsCode: String = "",
    val challengeCode: String? = null,
    val challengePhone: String? = null,
    val resendAvailableAtEpochMs: Long? = null,
    val isLoading: Boolean = false,
    val canRetryLogin: Boolean = false,
    val errorMessage: String? = null,
) {
    enum class Mode { WebView, EnterPhone, EnterSmsCode, CompleteLogin }

    companion object {
        internal const val AUTH_URL = "https://boosty.to"
    }
}

/**
 * Combines an explicitly chosen country code and national number. Country codes and
 * domestic trunk prefixes are never guessed. Boosty validates the numbering plan.
 */
internal fun normalizePhoneNumber(countryCode: String, nationalNumber: String): String? {
    val countryDigits = countryCode.trim().removePrefix("+")
    if (countryDigits.length !in 1..3 || countryDigits.first() !in '1'..'9' ||
        countryDigits.any { it !in '0'..'9' }
    ) return null

    val number = nationalNumber.trim()
    if (number.any { it !in '0'..'9' && !it.isWhitespace() && it !in "()-" }) return null
    val nationalDigits = number.filter { it in '0'..'9' }
    if (nationalDigits.isEmpty()) return null
    val fullNumber = countryDigits + nationalDigits
    if (fullNumber.length !in 7..15) return null
    return "+$fullNumber"
}
