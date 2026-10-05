package net.ithandsfree.softphone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolTokenTest {
    private val tok = "31f6fe5dde2c5d674dd2f33689a5907b0592d114596077f1"

    @Test
    fun bareToken() {
        assertEquals(tok, normalizeEnrolToken("  $tok \n"))
        assertTrue(looksLikeEnrolToken(tok))
    }

    @Test
    fun deepLink() {
        assertEquals(tok, normalizeEnrolToken("ihfphone://enroll/$tok"))
    }

    @Test
    fun httpsEnrolWithPort() {
        val url =
            "https://pbx.example.com:8443/ihf-softphone/enrol/$tok/"
        assertEquals(tok, normalizeEnrolToken(url))
    }

    @Test
    fun legacyHttpsEnroll() {
        val url = "https://pbx.example.com/ihf/enroll/$tok"
        assertEquals(tok, normalizeEnrolToken(url))
    }

    @Test
    fun truncatedRejected() {
        assertFalse(looksLikeEnrolToken("674dd2f33689a5907b0592d114596077f1"))
    }
}
