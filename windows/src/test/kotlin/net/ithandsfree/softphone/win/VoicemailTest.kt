package net.ithandsfree.softphone.win

import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoicemailTest {
    private val zone = ZoneId.of("America/Toronto")
    private val today = LocalDate.of(2026, 10, 8)
    private fun at(day: LocalDate, h: Int, m: Int) = ZonedDateTime.of(day.atTime(h, m), zone).toInstant().toEpochMilli()

    @Test
    fun rowTimesReadLikeThePhone() {
        assertEquals("4:52 PM", formatVoicemailWhen(at(today, 16, 52), zone, today))
        assertEquals("Yesterday 4:52 PM", formatVoicemailWhen(at(today.minusDays(1), 16, 52), zone, today))
        assertEquals("Mon 9:12 AM", formatVoicemailWhen(at(LocalDate.of(2026, 10, 5), 9, 12), zone, today))
        assertEquals("Sep 26", formatVoicemailWhen(at(LocalDate.of(2026, 9, 26), 10, 0), zone, today))
        assertEquals("Jun 24, 2025", formatVoicemailWhen(at(LocalDate.of(2025, 6, 24), 10, 0), zone, today))
        assertEquals("0:41", formatSeconds(41))
        assertEquals("12:05", formatSeconds(725))
    }

    @Test
    fun pbxCallerNamesThatAreNoise() {
        kotlin.test.assertNull(callerName("CID:15555550193"))
        kotlin.test.assertNull(callerName("4165550177"))
        kotlin.test.assertNull(callerName(""))
        assertEquals("Dana Whitfield", callerName("\"Dana Whitfield\""))
    }

    @Test
    fun blockedCallerIdsCannotBeCalledBack() {
        assertFalse(recentCanCall("Unavailable"))
        assertFalse(recentCanCall("Restricted"))
        assertTrue(recentCanCall("4165550177"))
    }

    @Test
    fun parsesTheBffVoicemailList() {
        // Shape returned by the live PBX on 2026-10-08 (number anonymised).
        val text = """{"extension":"903","permissions":{"voicemail":true,"playback":true,"download":true},"dial":"*97",
            "new":0,"old":1,"messages":[{"id":"1750785621-000000e4","folder":"Old","new":false,"urgent":false,
            "at":1750785621,"number":"Unavailable","name":"","duration":12}]}"""
        val r = Json { ignoreUnknownKeys = true }.decodeFromString(VoicemailResponse.serializer(), text)
        assertEquals("*97", r.dial)
        assertEquals(1, r.old)
        assertEquals("1750785621-000000e4", r.messages.single().id)
        assertFalse(r.messages.single().new)
        assertEquals(12, r.messages.single().duration)
    }
}
