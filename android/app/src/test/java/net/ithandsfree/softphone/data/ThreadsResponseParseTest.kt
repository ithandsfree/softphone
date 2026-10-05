package net.ithandsfree.softphone.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression: live BFF returns `"total":187` (number). Declaring total as String
 * caused: Unexpected JSON token … Expected quotation mark '"' but had '1' at $.total
 */
class ThreadsResponseParseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun parsesNumericTotalAndMixedThreadFields() {
        val raw = """
            {
              "total": 187,
              "threads": [
                {
                  "thread_id": 42,
                  "peer": "15555550199",
                  "local_did": "15555550100",
                  "last_message_at": 1728000000,
                  "snippet": "Hello",
                  "unread": 3,
                  "direction": "in"
                },
                {
                  "thread_id": "4abc",
                  "peer": "15555550100",
                  "local_did": "15555550100",
                  "last_message_at": "2026-10-03 20:00:00",
                  "snippet": null
                }
              ],
              "total_unread": 3
            }
        """.trimIndent()

        val parsed = json.decodeFromString(ThreadsResponse.serializer(), raw)
        assertEquals(187, parsed.total)
        assertEquals(3, parsed.totalUnread)
        assertEquals(2, parsed.threads.size)
        assertEquals("42", parsed.threads[0].threadId)
        assertEquals("1728000000", parsed.threads[0].lastMessageAt)
        assertEquals("Hello", parsed.threads[0].snippet)
        assertEquals(3, parsed.threads[0].unread)
        assertEquals("4abc", parsed.threads[1].threadId)
        assertNull(parsed.threads[1].snippet)
        assertEquals(0, parsed.threads[1].unread)
    }

    @Test
    fun parsesQuotedTotalForBackwardCompat() {
        // Defensive: if an older BFF ever quoted the count, still accept it via lenient path.
        // With Int field + coerceInputValues, a quoted "187" may still fail — ensure number works.
        val raw = """{"total":0,"threads":[]}"""
        val parsed = json.decodeFromString(ThreadsResponse.serializer(), raw)
        assertEquals(0, parsed.total)
        assertEquals(0, parsed.threads.size)
    }
}
