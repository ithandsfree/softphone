package net.ithandsfree.softphone.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LegalLinksTest {
    @Test
    fun privacySectionIsThePlayPolicyPlusIhfAnchor() {
        assertEquals(
            LegalLinks.PLAY_PRIVACY_POLICY + "#ihf-phone",
            LegalLinks.PRIVACY_SECTION,
        )
        assertEquals(
            LegalLinks.PRIVACY_SECTION,
            LegalLinks.httpsUrl(LegalLinks.PRIVACY_SECTION),
        )
    }

    @Test
    fun sourceIsThePublicRepoAndNotThePrivacyPolicy() {
        assertEquals(
            "https://github.com/ithandsfree/softphone",
            LegalLinks.SOURCE_REPOSITORY,
        )
        assertTrue(LegalLinks.SOURCE_REPOSITORY != LegalLinks.PLAY_PRIVACY_POLICY)
        assertEquals(
            LegalLinks.SOURCE_REPOSITORY,
            LegalLinks.httpsUrl("  ${LegalLinks.SOURCE_REPOSITORY}  "),
        )
    }

    @Test
    fun rejectsNonHttps() {
        assertNull(LegalLinks.httpsUrl(""))
        assertNull(LegalLinks.httpsUrl("http://ithandsfree.com/privacy"))
        assertNull(LegalLinks.httpsUrl("https://ithandsfree.com/privacy extra"))
    }

    @Test
    fun packagedLicenceIsGpl2() {
        val file = licenceAsset()
        val text = file.readText()
        assertTrue(file.length() > 1000)
        assertTrue(LegalLinks.looksLikeGpl2(text))
    }

    private fun licenceAsset(): File {
        val candidates = listOf(
            File("src/main/assets/${LegalLinks.LICENSE_ASSET}"),
            File("android/app/src/main/assets/${LegalLinks.LICENSE_ASSET}"),
            File("softphone/android/app/src/main/assets/${LegalLinks.LICENSE_ASSET}"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("GPL asset missing. cwd=${File(".").absolutePath}")
    }
}
