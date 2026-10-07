package net.ithandsfree.softphone.win

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallLogTest {
    @Test
    fun aFinishedCallRoundTrips() {
        val entry = CallEntry(1_700_000_000_000, "Outgoing", "*43", 12)
        assertEquals(entry, CallLog.decode(CallLog.encode(entry)))
    }

    @Test
    fun aBrokenLineIsSkipped() {
        assertNull(CallLog.decode("not-a-call"))
    }

    @Test
    fun todayShowsTheClock() {
        val zone = ZoneId.of("America/Toronto")
        val today = LocalDate.of(2026, 10, 7)
        val at = today.atTime(14, 14).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2:14 PM", formatRecentWhen(at, zone, today))
        val yesterday = today.minusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Yesterday", formatRecentWhen(yesterday, zone, today))
    }
}
