package ru.slartus.boostbuddy.data.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Real loopback TLS handshakes; never connects to Boosty or uses an account. */
class HttpsVerificationTest {
    @Test
    fun debugClientRejectsUntrustedCertificate() = runBlocking {
        withHttpsServer("localhost", isDebug = true) { server, client ->
            assertFailsWith<SSLHandshakeException> { client.get(server.url("/").toString()) }
        }
    }

    @Test
    fun releaseClientRejectsUntrustedCertificate() = runBlocking {
        withHttpsServer("localhost", isDebug = false) { server, client ->
            assertFailsWith<SSLHandshakeException> { client.get(server.url("/").toString()) }
        }
    }

    @Test
    fun debugClientRejectsWrongHostnameEvenWithTrustedIssuer() = runBlocking {
        withHttpsServer("wrong.invalid", isDebug = true, trustTestCertificate = true) { server, client ->
            assertFailsWith<SSLPeerUnverifiedException> { client.get(server.url("/").toString()) }
        }
    }

    @Test
    fun debugClientAcceptsTrustedCertificateWithMatchingHostname() = runBlocking {
        withHttpsServer("localhost", isDebug = true, trustTestCertificate = true) { server, client ->
            assertEquals("verified", client.get(server.url("/").toString()).bodyAsText())
        }
    }

    private suspend fun withHttpsServer(
        certificateHost: String,
        isDebug: Boolean,
        trustTestCertificate: Boolean = false,
        block: suspend (MockWebServer, HttpClient) -> Unit,
    ) {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(certificateHost)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("verified"))
            server.start()
            val client = HttpClient(HttpEngineFactory().createEngine(isDebug)) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 5_000
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 5_000
                }
                if (trustTestCertificate) {
                    val trustedCertificates = HandshakeCertificates.Builder()
                        .addTrustedCertificate(certificate.certificate)
                        .build()
                    engine {
                        (this as OkHttpConfig).config {
                            sslSocketFactory(
                                trustedCertificates.sslSocketFactory(),
                                trustedCertificates.trustManager,
                            )
                        }
                    }
                }
            }
            try {
                block(server, client)
            } finally {
                client.close()
            }
        }
    }
}
