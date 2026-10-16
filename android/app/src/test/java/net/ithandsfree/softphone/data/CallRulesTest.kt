package net.ithandsfree.softphone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CallRulesTest {
    private val zone = ZoneId.of("America/Toronto")
    private val today = LocalDate.of(2026, 10, 8)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun callerNameHidesTrunkNoise() {
        assertNull(callerName("CID:4165550100"))
        assertNull(callerName("\"4165550100\""))
        assertNull(callerName("Unknown"))
        assertNull(callerName("  "))
        assertEquals("Jane Smith", callerName("\"Jane Smith\""))
    }

    @Test fun withheldCallerCannotBeCalledBack() {
        assertFalse(canCallBack("anonymous"))
        assertFalse(canCallBack("Withheld"))
        assertFalse(canCallBack(""))
        assertTrue(canCallBack("+14165550100"))
        assertTrue(canCallBack("201"))
    }

    @Test fun extensionsAndCodesAreNotTexted() {
        assertEquals("Extensions can't receive texts", textBlock("201"))
        assertEquals("Extensions can't receive texts", textBlock("*97"))
        assertEquals("Caller ID withheld", textBlock("anonymous"))
        assertNull(textBlock("4165550100"))
    }

    @Test fun voicemailTimesReadLikeTheDesktop() {
        assertEquals("4:52 PM", formatVoicemailWhen(at(2026, 10, 8, 16, 52), zone, today))
        assertEquals("Yesterday 9:05 AM", formatVoicemailWhen(at(2026, 10, 7, 9, 5), zone, today))
        assertEquals("Mon 9:12 AM", formatVoicemailWhen(at(2026, 10, 5, 9, 12), zone, today))
        assertEquals("Sep 26", formatVoicemailWhen(at(2026, 9, 26, 10, 0), zone, today))
        assertEquals("Dec 1, 2025", formatVoicemailWhen(at(2025, 12, 1, 10, 0), zone, today))
        assertEquals("", formatVoicemailWhen(0, zone, today))
    }

    @Test fun secondsFormat() {
        assertEquals("0:41", formatSeconds(41))
        assertEquals("12:05", formatSeconds(725))
        assertEquals("0:00", formatSeconds(-3))
    }

    @Test fun matchesThePbxRowEndingNearThisCall() {
        val start = at(2026, 10, 8, 10, 0)
        val near = PbxCall(id = "a", peer = "+1 (416) 555-0100", at = start / 1000 + 2, duration = 60)
        val far = PbxCall(id = "b", peer = "4165550100", at = start / 1000 - 3600, duration = 60)
        val other = PbxCall(id = "c", peer = "4165550199", at = start / 1000, duration = 60)
        assertEquals("a", matchPbxCall("14165550100", start, 60, listOf(far, other, near))?.id)
        assertNull(matchPbxCall("14165550100", start, 60, listOf(far, other)))
        assertNull(matchPbxCall("", start, 60, listOf(near)))
    }
}
