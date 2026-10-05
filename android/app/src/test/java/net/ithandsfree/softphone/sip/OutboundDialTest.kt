package net.ithandsfree.softphone.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class OutboundDialTest {
    @Test
    fun nanpTenDigitPrefixedWithOne() {
        assertEquals("15555550100", outboundDialDigits("5555550100"))
        assertEquals("15555550100", outboundDialDigits("(555) 555-0100"))
    }

    @Test
    fun alreadyElevenDigitUnchanged() {
        assertEquals("15555550100", outboundDialDigits("15555550100"))
    }

    @Test
    fun shortExtensionLiteral() {
        assertEquals("1002", outboundDialDigits("1002"))
        assertEquals("1001", outboundDialDigits("1001"))
    }

    @Test
    fun starCodesLiteral() {
        assertEquals("*72", outboundDialDigits("*72"))
        assertEquals("+15555550100", outboundDialDigits("+15555550100"))
    }
}
