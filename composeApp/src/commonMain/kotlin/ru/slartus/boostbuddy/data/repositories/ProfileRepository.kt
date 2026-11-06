// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.repositories

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import ru.slartus.boostbuddy.data.api.BoostyApi
import ru.slartus.boostbuddy.utils.fetchOrError

internal class ProfileRepository(
    private val boostyApi: BoostyApi,
    private val authHttpClient: HttpClient,
) {
    suspend fun getProfile(): Result<Profile> =
        fetchOrError {
            val response: ProfileResponse = boostyApi.current().body()

            response.toProfile()
        }

    /** Validate a candidate without publishing it to the app or refreshing another session. */
    suspend fun getProfileForToken(accessToken: String): Result<Profile> = fetchOrError {
        require(accessToken.isNotBlank()) { "Empty access token" }
        val response: ProfileResponse = authHttpClient.get("v1/user/current") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }.body()
        response.toProfile()
    }
}

data class Profile(val id: String, val blogUrl: String?)

@Serializable
private data class ProfileResponse(
    val id: String? = null,
    val blogUrl: String? = null
) {
    fun toProfile(): Profile = Profile(
        id = id?.takeIf { it.isNotBlank() } ?: error("Missing profile id"),
        blogUrl = blogUrl,
    )
}
