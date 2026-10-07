package net.ithandsfree.softphone.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneRulesTest {
    private val token = "a".repeat(48)

    @Test
    fun bareHexTokenIsKept() {
        assertEquals(token, normalizeEnrolToken("  $token  "))
        assertTrue(looksLikeEnrolToken(token))
    }

    @Test
    fun deepLinkAndHttpsLandingBothYieldTheToken() {
        assertEquals(token, normalizeEnrolToken("ihfphone://enroll/$token"))
        assertEquals(
            token,
            normalizeEnrolToken("https://pbx.example.com/ihf-softphone/enrol/$token/"),
        )
        assertEquals(
            token,
            normalizeEnrolToken("https://pbx.example.com/ihf/enroll/$token"),
        )
    }

    @Test
    fun shortPasteIsNotAToken() {
        assertFalse(looksLikeEnrolToken("1001"))
        assertFalse(looksLikeEnrolToken("abc"))
    }

    @Test
    fun dialledNumberUsesTheLargeTypeUntilItGrowsPastAPhoneNumber() {
        assertEquals(52, dialTypeSize("4165550100"))
        assertEquals(52, dialTypeSize("14165550100"))
        assertEquals(40, dialTypeSize("1".repeat(15)))
        assertEquals(28, dialTypeSize("1".repeat(16)))
        assertEquals("(416) 555-0100", formatDialDigits("4165550100"))
        assertEquals("*97", formatDialDigits("*97"))
        assertEquals("*43", displayParty("sip:*43@pbx.example.com;transport=tls", "echo"))
    }

    @Test
    fun extensionStaysLiteralAndNanpGainsCountryCode() {
        assertEquals("1001", outboundDialDigits("1001"))
        assertEquals("*97", outboundDialDigits("*97"))
        assertEquals("15555550100", outboundDialDigits("(555) 555-0100"))
    }

    @Test
    fun registrarPrefersTlsOn5061() {
        assertEquals(SipSignalling.TLS, preferredSignalling(tlsAvailable = true, tcpAvailable = true))
        assertEquals(SipSignalling.TCP, preferredSignalling(tlsAvailable = false, tcpAvailable = true))
        assertEquals(null, preferredSignalling(tlsAvailable = false, tcpAvailable = false))
        assertEquals(
            "sip:1001@pbx.example.com",
            sipAccountIdUri("1001", "pbx.example.com"),
        )
        assertEquals(
            "sip:pbx.example.com:5061;transport=tls",
            sipRegistrarUri("pbx.example.com", SipSignalling.TLS),
        )
        assertEquals(
            "sip:pbx.example.com",
            sipRegistrarUri("pbx.example.com", SipSignalling.TCP),
        )
        assertEquals(
            "sip:1001@pbx.example.com;transport=tls",
            sipCallUri("1001", "pbx.example.com", SipSignalling.TLS),
        )
        assertEquals(
            "sip:1001@pbx.example.com",
            sipCallUri("1001", "pbx.example.com", SipSignalling.TCP),
        )
    }

    @Test
    fun welcomeEmailNeedsARealAddress() {
        assertEquals(true, looksLikeEmail("user@example.com"))
        assertEquals(false, looksLikeEmail("not-an-email"))
        assertEquals(false, looksLikeEmail("a@b"))
    }

    @Test
    fun liveCallTimerIsGoldClockFormat() {
        assertEquals("00:00", formatLiveCallTimer(0))
        assertEquals("02:14", formatLiveCallTimer(134))
        assertEquals("1:02:03", formatLiveCallTimer(3723))
    }

    @Test
    fun ihfHidesTheServerAndCommunityLeavesItEmpty() {
        val ihf = distributionProfile(Distribution.IHF)
        val community = distributionProfile(Distribution.COMMUNITY)
        assertEquals("IHF Phone", ihf.productName)
        assertEquals(false, ihf.serverEditable)
        assertEquals("https://pbx.example.com/ihf-softphone/index.php", ihf.defaultApiBase)
        assertEquals("Softphone", community.productName)
        assertEquals("Works with FreePBX", community.footerCaption)
        assertEquals("Canadian-hosted Cloud PBX", ihf.footerCaption)
        assertEquals(true, community.serverEditable)
        assertEquals("", community.defaultApiBase)
        assertEquals("", community.defaultSipDomain)
        assertEquals(
            HostConfig("", ""),
            resolveHost(community, envApi = null, envSip = null, fileApi = null, fileSip = null),
        )
        assertEquals(
            HostConfig("https://pbx.example.com/ihf-softphone/index.php", "pbx.example.com"),
            resolveHost(ihf, envApi = "  ", envSip = null, fileApi = null, fileSip = null),
        )
    }

    @Test
    fun oversizedPhotoIsShrunkUnderTheLimit() {
        val image = java.awt.image.BufferedImage(900, 900, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val random = kotlin.random.Random(7)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, random.nextInt())
            }
        }
        val raw = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", raw)
        val photo = prepareMmsPhoto(raw.toByteArray(), "snap.png", maxBytes = 40_000)
        assertTrue(photo.bytes.size <= 40_000)
        assertEquals("photo.jpg", photo.name)
        assertEquals("image/jpeg", photo.type)
    }

    @Test
    fun aPluggedInHeadsetMatchesTheNewOutput() {
        val previous = setOf("Speakers (Realtek(R) Audio)")
        val names = listOf("Speakers (Realtek(R) Audio)", "Headset Earphone (Jabra Speak)")
        assertEquals("Headset Earphone (Jabra Speak)", matchOutputName("Headset Earphone (Jabra Speak)", names, previous))
        assertEquals("Headset Earphone (Jabra Speak)", matchOutputName("Jabra Speak 410", names, previous))
    }

    @Test
    fun enrolledLineIsRememberedForTheNextLaunch() {
        val file = java.io.File.createTempFile("ihf-line", ".properties")
        file.deleteOnExit()
        val line = EnrolledLine(
            token = "a".repeat(48),
            did = "1001",
            extension = "1001",
            sipPassword = "secret",
            sipDomain = "pbx.example.com",
            displayName = "Desk",
        )
        LineStore.write(file, line)
        assertEquals(line, LineStore.read(file))
        file.writeText("token=\n")
        assertEquals(null, LineStore.read(file))
        file.delete()
    }

    @Test
    fun standardRingtonesAreDistinctSounds() {
        val rendered = RingtoneLibrary.builtIn.map { RingtoneLibrary.render(it.id) }
        assertEquals(6, rendered.size)
        rendered.forEach { bytes ->
            assertTrue(bytes.size >= 16000)
            assertTrue(bytes.any { it.toInt() != 0 })
        }
        assertTrue(rendered.distinctBy { it.contentHashCode() }.size == rendered.size)
        assertEquals(RingtoneLibrary.render("missing").contentHashCode(), RingtoneLibrary.render("two-tone").contentHashCode())
    }

    @Test
    fun customRingtoneAcceptsAWavAndRefusesOtherNames() {
        assertEquals("wav", ringtoneExtension("ring.WAV"))
        assertEquals("aiff", ringtoneExtension("tone.aif"))
        assertEquals(null, ringtoneExtension("song.mp3"))
        val wav = java.io.File.createTempFile("ihf-ring", ".wav")
        wav.deleteOnExit()
        val image = javax.sound.sampled.AudioFormat(8000f, 16, 1, true, false)
        val bytes = ByteArray(800)
        javax.sound.sampled.AudioSystem.write(
            javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(bytes), image, 400),
            javax.sound.sampled.AudioFileFormat.Type.WAVE,
            wav,
        )
        assertTrue(ringtoneReadable(wav))
        wav.delete()
    }

    @Test
    fun recentActionsFollowTheLineRules() {
        assertFalse(recentCanCall("anonymous"))
        assertFalse(recentCanCall("Unknown"))
        assertEquals("Caller ID withheld", recentTextBlock("withheld"))
        assertEquals("Extensions can't receive texts", recentTextBlock("903"))
        assertEquals("Extensions can't receive texts", recentTextBlock("*43"))
        assertNull(recentTextBlock("4165550100"))
        assertTrue(recentCanCall("4165550100"))
    }

    @Test
    fun telAndSipLinksBecomeANumber() {
        assertEquals("4165550177", dialTarget("tel:4165550177"))
        assertEquals("+14165550177", dialTarget("tel:+1-416-555-0177"))
        assertEquals("1001", dialTarget("sip:1001@pbx.example.com"))
        assertNull(dialTarget("https://pbx.example.com/ihf-softphone/"))
    }

    @Test
    fun caBundleIsWrittenOutsideTheInstallFolder() {
        val file = caBundleFile()
        assertTrue(file.isAbsolute)
        assertEquals("ca-bundle.pem", file.name)
        assertFalse(file.path.replace('\\', '/').contains("native/out"))
    }
}
