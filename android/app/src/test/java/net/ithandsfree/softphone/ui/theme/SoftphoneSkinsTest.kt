package net.ithandsfree.softphone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftphoneSkinsTest {
    @Test
    fun resolveKnownIds() {
        assertEquals(SkinId.IhfNight, SoftphoneSkins.resolve("ihf_night").id)
        assertEquals(SkinId.CarbonSignal, SoftphoneSkins.resolve("carbon_signal").id)
        assertEquals(SkinId.IhfMist, SoftphoneSkins.resolve("ihf_mist").id)
    }

    @Test
    fun resolveUnknownFallsBackToNight() {
        assertEquals(SkinId.IhfNight, SoftphoneSkins.resolve("nope").id)
        assertEquals(SkinId.IhfNight, SoftphoneSkins.resolve(null).id)
    }

    @Test
    fun packsAreDistinctAndDarkFlagsSensible() {
        assertTrue(SoftphoneSkins.IhfNight.isDark)
        assertTrue(SoftphoneSkins.CarbonSignal.isDark)
        assertFalse(SoftphoneSkins.IhfMist.isDark)
        assertTrue(
            SoftphoneSkins.IhfNight.colors.gold != SoftphoneSkins.CarbonSignal.colors.gold,
        )
        assertEquals(3, SoftphoneSkins.all.size)
    }
}
