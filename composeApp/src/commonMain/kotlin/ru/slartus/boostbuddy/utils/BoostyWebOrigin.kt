package ru.slartus.boostbuddy.utils

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/** Only the official website may supply a browser login cookie. */
internal fun isBoostyWebOrigin(value: String?): Boolean {
    if (value == null) return false
    return runCatching {
        val url = Url(value)
        url.protocol == URLProtocol.HTTPS && url.port == 443 &&
            url.user.isNullOrEmpty() && url.password.isNullOrEmpty() &&
            url.host.lowercase() in setOf("boosty.to", "www.boosty.to")
    }.getOrDefault(false)
}
