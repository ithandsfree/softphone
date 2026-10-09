package net.ithandsfree.softphone.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiagLogTest {
    @Test
    fun credentialsNeverReachTheLog() {
        assertEquals("Authorization: [removed]", DiagLog.redact("Authorization: Digest username=\"9333\", response=\"abc\""))
        assertEquals("Proxy-Authorization: [removed]", DiagLog.redact("  Proxy-Authorization: Digest nonce=\"x\""))
        assertNull(DiagLog.redact("sip password=hunter2"))
        assertNull(DiagLog.redact("X-IHF-Token: abc"))
        assertNull(DiagLog.redact("header Bearer abc.def"))
        assertEquals("SIP/2.0 486 Busy Here", DiagLog.redact("SIP/2.0 486 Busy Here"))
    }
}
