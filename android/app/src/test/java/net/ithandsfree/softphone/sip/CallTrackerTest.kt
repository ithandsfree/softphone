package net.ithandsfree.softphone.sip

import net.ithandsfree.softphone.data.CallDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallTrackerTest {
    private fun seen(key: Int, party: String, line: String = "a", ringing: Boolean = false, connectedAt: Long = 0) =
        CallTracker.Seen(key, party, line, ringing, connectedAt)

    @Test
    fun callWaitingAnsweredThenBothEndGiveTwoRows() {
        val t = CallTracker()
        t.update(listOf(seen(1, "4165550101", ringing = true)), 0, 1_000)
        t.update(listOf(seen(1, "4165550101", connectedAt = 1_000)), 1_000, 2_000)
        // A second call rings on line b, then Hold & answer.
        t.update(listOf(seen(1, "4165550101", connectedAt = 1_000), seen(2, "7055550142", "b", ringing = true)), 61_000, 62_000)
        t.update(listOf(seen(2, "7055550142", "b", connectedAt = 65_000), seen(1, "4165550101", connectedAt = 1_000)), 65_000, 66_000)
        // The second caller hangs up after 30 s: one row; the first call goes on.
        val first = t.update(listOf(seen(1, "4165550101", connectedAt = 1_000)), 95_000, 96_000)
        assertEquals(1, first.size)
        assertEquals(CallDirection.Incoming, first[0].direction)
        assertEquals(30, first[0].seconds)
        assertEquals("b", first[0].lineId)
        val second = t.update(emptyList(), 121_000, 122_000)
        assertEquals(120, second.single().seconds)
        assertEquals(1_000L, second.single().startedAtWall)
    }

    @Test
    fun declinedWaitingCallIsMissedAndPlacedCallIsOutgoing() {
        val t = CallTracker()
        t.update(listOf(seen(5, "18005551234", connectedAt = 10)), 10, 10)
        t.update(listOf(seen(5, "18005551234", connectedAt = 10), seen(6, "6475550000", ringing = true)), 5_000, 5_000)
        val declined = t.update(listOf(seen(5, "18005551234", connectedAt = 10)), 9_000, 9_000)
        assertEquals(CallDirection.Missed, declined.single().direction)
        assertEquals(0, declined.single().seconds)
        val ended = t.update(emptyList(), 20_010, 20_010)
        assertEquals(CallDirection.Outgoing, ended.single().direction)
        assertTrue(ended.single().answered)
        assertEquals(20, ended.single().seconds)
    }
}
