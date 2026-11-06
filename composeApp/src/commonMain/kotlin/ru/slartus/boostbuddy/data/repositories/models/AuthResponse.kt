// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.repositories.models

import kotlinx.serialization.Serializable

@Serializable
data class AuthResponse(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long? = null
) {
    override fun toString(): String = "AuthResponse(redacted)"
}
