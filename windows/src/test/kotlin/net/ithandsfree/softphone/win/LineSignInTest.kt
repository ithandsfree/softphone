package net.ithandsfree.softphone.win

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineSignInTest {
    private val line = EnrolledLine(
        token = "bearer-1",
        did = "15555550100",
        extension = "1001",
        sipPassword = "sip-secret",
        sipDomain = "pbx.example.com",
        displayName = "Sam",
    )

    @Test
    fun aSealedValueOpensAndIsNotPlain() {
        val sealed = SecretBox.seal("sip-secret")
        assertTrue(SecretBox.isSealed(sealed))
        assertFalse(sealed.contains("sip-secret"))
        assertEquals("sip-secret", SecretBox.open(sealed))
    }

    @Test
    fun aPlainValueFromAnOlderBuildStillOpens() {
        assertEquals("sip-secret", SecretBox.open("sip-secret"))
        assertEquals("", SecretBox.seal(""))
    }

    @Test
    fun linesRoundTripWithTheSignInSealed() {
        val file = tempFile()
        val signed = line.copy(umUsername = "user@example.com", umPassword = "um-pass")
        LineBook.write(file, listOf(signed), "1001")
        val text = file.readText()
        assertFalse(text.contains("um-pass"))
        assertFalse(text.contains("sip-secret"))
        assertFalse(text.contains("bearer-1"))
        assertFalse(LineBook.hasPlainSecrets(file))
        assertEquals(listOf(signed), LineBook.read(file))
    }

    @Test
    fun aPlainFileIsReadAndFlagged() {
        val file = tempFile()
        file.writeText(
            """
            count=1
            default=1001
            0.token=bearer-1
            0.did=15555550100
            0.extension=1001
            0.secret=sip-secret
            0.domain=pbx.example.com
            0.name=Sam
            """.trimIndent(),
        )
        assertTrue(LineBook.hasPlainSecrets(file))
        assertEquals(listOf(line), LineBook.read(file))
    }

    @Test
    fun setupAddsTheNumberNotOnThisPcYet() {
        val offered = listOf(LineInfo("15555550100", "1001"), LineInfo("15555550199", "1002"))
        assertEquals("1002", pickLineToAdd(offered, listOf("5555550100"))?.extension)
        assertEquals("1001", pickLineToAdd(offered, emptyList())?.extension)
        assertEquals("1001", pickLineToAdd(offered, listOf("15555550100", "15555550199"))?.extension)
        assertNull(pickLineToAdd(listOf(LineInfo("", "1001")), emptyList()))
    }

    @Test
    fun numbersDisplayLikeTheMockups() {
        assertEquals("(416) 555-0142", displayNumber("14165550142"))
        assertEquals("(416) 555-0142", displayNumber("+14165550142"))
        assertEquals("(416) 555-0142", displayNumber("4165550142"))
        assertEquals("*43", displayNumber("*43"))
        assertEquals("9300", displayNumber("9300"))
        assertTrue(sameNumber("(416) 555-0142", "14165550142"))
        assertFalse(sameNumber("9300", "14165550142"))
        assertFalse(sameNumber("", ""))
    }

    @Test
    fun bffTimesBecomeMilliseconds() {
        assertEquals(1_791_434_894_000L, epochMsOf("1791434894"))
        assertEquals(1_791_434_894_000L, epochMsOf("1791434894000"))
        assertNull(epochMsOf("2026-10-08 00:48:14"))
        assertNull(epochMsOf(null))
    }

    @Test
    fun providerRepeatsCollapseButRealRepliesStay() {
        data class M(val text: String, val at: Long)
        val rows = listOf(M("Response", 1_000), M("Response", 1_500), M("Response", 2_000), M("Response", 60_000), M("ok", 61_000))
        val kept = net.ithandsfree.softphone.win.ui.collapseRepeats(rows, key = { it.text }, at = { it.at })
        assertEquals(listOf(M("Response", 1_000), M("Response", 60_000), M("ok", 61_000)), kept)
    }

    @Test
    fun localCallsMatchTheirPbxCall() {
        val calls = listOf(
            PbxCall(id = "1.1", at = 1_000, peer = "14165550142", duration = 35, recording = true),
            PbxCall(id = "2.2", at = 5_000, peer = "14165550142", duration = 10),
            PbxCall(id = "3.3", at = 1_010, peer = "*43", duration = 8),
        )
        // Local entry stored at the end of the call (start 1000 s + 35 s), within a few seconds.
        assertEquals("1.1", matchPbxCall("(416) 555-0142", 1_037_000, 35, calls)?.id)
        assertEquals("3.3", matchPbxCall("*43", 1_019_000, 8, calls)?.id)
        assertNull(matchPbxCall("(416) 555-0142", 3_000_000, 35, calls))
    }

    private fun tempFile(): File = Files.createTempFile("lines", ".properties").toFile().also { it.deleteOnExit() }
}
