package net.ithandsfree.softphone.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SipSignallingTest {
    @Test
    fun tlsRegistrarUses5061() {
        assertEquals(
            "sip:pbx.example.com:5061;transport=tls",
            sipRegistrarUri("pbx.example.com", SipSignalling.TLS),
        )
    }

    @Test
    fun tcpAndUdpKeepTheDefaultPort() {
        assertEquals("sip:pbx.example.com", sipRegistrarUri(" pbx.example.com ", SipSignalling.TCP))
        assertEquals("sip:pbx.example.com", sipRegistrarUri("pbx.example.com", SipSignalling.UDP))
    }

    @Test
    fun blankDomainDoesNotInventAHost() {
        assertEquals("sip:", sipRegistrarUri("  ", SipSignalling.TLS))
    }

    @Test
    fun accountIdStaysASipUri() {
        assertEquals("sip:1001@pbx.example.com", sipAccountIdUri("1001", "pbx.example.com"))
    }

    @Test
    fun pemBundleWrapsEachCertificate() {
        val pem = pemEncodeCertificates(listOf(byteArrayOf(1, 2, 3), byteArrayOf()))
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.trimEnd().endsWith("-----END CERTIFICATE-----"))
        assertEquals(1, pem.split("-----BEGIN CERTIFICATE-----").size - 1)
    }
}
