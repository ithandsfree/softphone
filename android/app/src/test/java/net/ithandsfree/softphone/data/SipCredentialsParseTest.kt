package net.ithandsfree.softphone.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SipCredentialsParseTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun parsesSipCredentialsResponse() {
        val raw = """
            {"ok":true,"extension":"1001","secret":"example-secret","tech":"pjsip"}
        """.trimIndent()
        val parsed = json.decodeFromString(SipCredentialsResponse.serializer(), raw)
        assertTrue(parsed.ok)
        assertEquals("1001", parsed.extension)
        assertEquals("example-secret", parsed.secret)
        assertEquals("pjsip", parsed.tech)
    }

    @Test
    fun parsesMarkReadAndDeleteResponses() {
        val marked = json.decodeFromString(
            MarkReadResponse.serializer(),
            """{"ok":true,"marked":4}""",
        )
        assertEquals(4, marked.marked)
        val deleted = json.decodeFromString(
            DeleteResponse.serializer(),
            """{"ok":true,"deleted":12}""",
        )
        assertEquals(12, deleted.deleted)
    }
}
