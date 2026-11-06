// Modified for BoostBuddy TV on 2026-11-06; see TV_FORK_CHANGES.md.
package ru.slartus.boostbuddy.data.ktor

import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp


internal actual class HttpEngineFactory actual constructor() {
    actual fun createEngine(isDebug: Boolean): HttpClientEngineFactory<HttpClientEngineConfig> {
        // Debug APKs handle real account credentials too. Logging must never
        // change TLS trust or hostname verification.
        return OkHttp
    }
}
