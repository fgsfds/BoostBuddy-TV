package ru.slartus.boostbuddy.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoostyWebOriginTest {
    @Test
    fun acceptsOnlyHttpsWebsiteOrigins() {
        assertTrue(isBoostyWebOrigin("https://boosty.to/"))
        assertTrue(isBoostyWebOrigin("https://www.boosty.to:443/login?next=/"))
        assertTrue(isBoostyWebOrigin("https://BOOSTY.TO/"))
    }

    @Test
    fun rejectsLookalikesUserInfoOtherPortsAndPlaintext() {
        listOf(
            "https://boosty.to.example.com/",
            "https://notboosty.to/",
            "https://boosty.to@example.com/",
            "https://example.com@boosty.to/",
            "https://boosty.to:8443/",
            "http://boosty.to/",
            "file:///boosty.to",
            "javascript:alert(1)",
            "",
        ).forEach { assertFalse(isBoostyWebOrigin(it), it) }
        assertFalse(isBoostyWebOrigin(null))
    }
}
