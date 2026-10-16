package net.ithandsfree.softphone.ui

import net.ithandsfree.softphone.data.SoftphoneAccount
import net.ithandsfree.softphone.ui.theme.isVoiceOnly
import net.ithandsfree.softphone.ui.theme.toLineUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceOnlyLineTest {
    private fun account(did: String, ext: String) =
        SoftphoneAccount(label = "ext $ext", umUsername = "u", umPassword = "", did = did, sipExtension = ext)

    @Test fun lineListedUnderItsExtensionIsVoiceOnly() {
        assertTrue(account(did = "9991", ext = "9991").isVoiceOnly())
        assertTrue(account(did = "", ext = "32").isVoiceOnly())
        assertFalse(account(did = "15555550135", ext = "9991").isVoiceOnly())
    }

    @Test fun voiceOnlyLineShowsNoNumber() {
        val line = account(did = "9991", ext = "9991").toLineUi(0)
        assertTrue(line.voiceOnly)
        assertEquals("", line.did)
        assertEquals("9991", line.ext)
    }
}
