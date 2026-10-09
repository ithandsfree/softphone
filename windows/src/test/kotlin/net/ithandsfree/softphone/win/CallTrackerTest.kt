package net.ithandsfree.softphone.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallTrackerTest {
    private fun seen(id: Int, party: String, ext: String = "9333", ringing: Boolean = false, connected: Boolean = false) =
        CallTracker.Seen(id, party, ext, ringing, connected)

    @Test
    fun callWaitingAnsweredThenBothEndLogTwoRows() {
        val t = CallTracker()
        // First call: incoming, answered at t=0.
        t.update(listOf(seen(0, "4165550101", ringing = true)), 0)
        t.update(listOf(seen(0, "4165550101", connected = true)), 1_000)
        // Second call rings on 903 beside it.
        t.update(listOf(seen(0, "4165550101", connected = true), seen(1, "7055550142", "903", ringing = true)), 61_000)
        // Hold & answer: the second becomes current, the first is on hold (still connected).
        t.update(listOf(seen(1, "7055550142", "903", connected = true), seen(0, "4165550101", connected = true)), 65_000)
        assertEquals(1_000L, t.connectedAt(0))
        assertEquals(65_000L, t.connectedAt(1))
        // The second caller hangs up after 30 s: one row, and the first call keeps its own timer.
        val first = t.update(listOf(seen(0, "4165550101", connected = true)), 95_000)
        assertEquals(listOf(CallTracker.Finished("7055550142", "903", incoming = true, answered = true, seconds = 30)), first)
        assertEquals("Incoming", first.single().kind)
        assertEquals(1_000L, t.connectedAt(0))
        val second = t.update(emptyList(), 121_000)
        assertEquals(120, second.single().seconds)
        assertEquals("9333", second.single().extension)
    }

    @Test
    fun declinedWaitingCallIsMissedAndOutgoingStaysOutgoing() {
        val t = CallTracker()
        t.update(listOf(seen(2, "18005551234", connected = true)), 0)
        t.update(listOf(seen(2, "18005551234", connected = true), seen(3, "6475550000", ringing = true)), 5_000)
        val declined = t.update(listOf(seen(2, "18005551234", connected = true)), 9_000)
        assertEquals("Missed", declined.single().kind)
        assertEquals(0, declined.single().seconds)
        val ended = t.update(emptyList(), 20_000)
        assertEquals("Outgoing", ended.single().kind)
        assertTrue(ended.single().answered)
        assertNull(t.connectedAt(2))
    }
}
