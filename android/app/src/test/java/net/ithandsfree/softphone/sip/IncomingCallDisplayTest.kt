package net.ithandsfree.softphone.sip

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingCallDisplayTest {
    @Test
    fun stripsSipUri() {
        assertEquals(
            "5555550100",
            IncomingCallNotifier.displayRemote("sip:15555550100@pbx.example.com"),
        )
    }

    @Test
    fun stripsAngleBrackets() {
        assertEquals(
            "5555550199",
            IncomingCallNotifier.displayRemote("<sip:15555550199@pbx.example.com>"),
        )
    }
}
