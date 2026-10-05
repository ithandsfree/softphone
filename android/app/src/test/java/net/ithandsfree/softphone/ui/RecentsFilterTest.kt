package net.ithandsfree.softphone.ui

import net.ithandsfree.softphone.data.CallDirection
import net.ithandsfree.softphone.data.CallRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsFilterTest {
    private fun rec(
        lineId: String,
        direction: CallDirection = CallDirection.Outgoing,
    ) = CallRecord(
        lineId = lineId,
        peerNumber = "15555550100",
        peerDisplayName = "",
        direction = direction,
        durationSec = 0,
        answered = direction != CallDirection.Missed,
    )

    @Test
    fun allLinesMatchesEverything() {
        assertTrue(matchesRecentsFilter(rec("a"), RecentsFilter.All))
        assertTrue(matchesRecentsFilter(rec("b", CallDirection.Missed), RecentsFilter.All))
    }

    @Test
    fun perLineMatchesOnlyThatExtension() {
        assertTrue(matchesRecentsFilter(rec("ext-a"), RecentsFilter.Line("ext-a")))
        assertFalse(matchesRecentsFilter(rec("ext-b"), RecentsFilter.Line("ext-a")))
    }

    @Test
    fun missedMatchesOnlyMissed() {
        assertTrue(matchesRecentsFilter(rec("a", CallDirection.Missed), RecentsFilter.Missed))
        assertFalse(matchesRecentsFilter(rec("a", CallDirection.Outgoing), RecentsFilter.Missed))
        assertFalse(matchesRecentsFilter(rec("a", CallDirection.Incoming), RecentsFilter.Missed))
    }
}
