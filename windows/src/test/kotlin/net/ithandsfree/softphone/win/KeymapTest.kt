package net.ithandsfree.softphone.win

import java.awt.event.KeyEvent
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeymapTest {
    private val defaults = Keymap.fromProperties(Properties())

    @Test
    fun defaultsMatchTheDesignSheet() {
        assertEquals("Ctrl K", defaults.chord(Shortcut.SEARCH).label())
        assertEquals("Ctrl Shift D", defaults.chord(Shortcut.TOGGLE_DND).label())
        assertEquals("Ctrl ↓", defaults.chord(Shortcut.NEXT_THREAD).label())
        assertEquals("Ctrl Alt A", defaults.chord(Shortcut.GLOBAL_ANSWER).label())
        assertFalse(defaults.globalEnabled)
        // No two in-window actions share a key, and no two system-wide ones do.
        Shortcut.entries.forEach { assertNull(defaults.owner(defaults.chord(it), it), it.name) }
    }

    @Test
    fun chordRoundTripsThroughTheFile() {
        val chord = Chord(KeyEvent.VK_F7, ctrl = true, shift = true)
        assertEquals(chord, Chord.parse(chord.encode()))
        assertNull(Chord.parse("meta+75"))
        assertNull(Chord.parse("ctrl+"))
        val saved = defaults.with(Shortcut.MUTE, Chord(KeyEvent.VK_U)).withGlobal(true).toProperties()
        val loaded = Keymap.fromProperties(saved)
        assertEquals(Chord(KeyEvent.VK_U), loaded.chord(Shortcut.MUTE))
        assertTrue(loaded.globalEnabled)
        assertFalse(saved.containsKey(Shortcut.SEARCH.name), "defaults are not written")
    }

    @Test
    fun refusesKeysThatWouldBreakTypingOrClash() {
        assertNotNull(defaults.refusal(Shortcut.SEARCH, Chord(KeyEvent.VK_S)), "plain letter outside a call")
        assertNull(defaults.refusal(Shortcut.MUTE, Chord(KeyEvent.VK_U)), "plain letter for an in-call key")
        assertNotNull(defaults.refusal(Shortcut.MUTE, Chord(KeyEvent.VK_5)), "digits are DTMF")
        assertNotNull(defaults.refusal(Shortcut.SEARCH, Chord.ctrl(KeyEvent.VK_V)), "paste")
        assertNotNull(defaults.refusal(Shortcut.SEARCH, Chord.ctrl(KeyEvent.VK_N)), "already New message")
        assertNull(defaults.refusal(Shortcut.SEARCH, Chord.ctrl(KeyEvent.VK_K)), "its own key")
        assertNotNull(defaults.refusal(Shortcut.GLOBAL_MUTE, Chord(KeyEvent.VK_F9)), "global needs a modifier")
        assertNotNull(defaults.refusal(Shortcut.GLOBAL_MUTE, Chord.ctrlAlt(KeyEvent.VK_DOWN)), "global needs a VK-safe key")
        assertNull(defaults.refusal(Shortcut.GLOBAL_MUTE, Chord.ctrlAlt(KeyEvent.VK_F9)))
        // A window key and a system-wide key are matched in different places, so they may share a chord.
        assertNull(defaults.refusal(Shortcut.GLOBAL_ANSWER, Chord.ctrl(KeyEvent.VK_K)))
    }

    @Test
    fun aRefusedKeyInTheFileFallsBackToTheDefault() {
        val props = Properties()
        props.setProperty(Shortcut.SEARCH.name, Chord(KeyEvent.VK_S).encode())
        props.setProperty(Shortcut.NEW_MESSAGE.name, Chord.ctrl(KeyEvent.VK_C).encode())
        val loaded = Keymap.fromProperties(props)
        assertEquals(Shortcut.SEARCH.default, loaded.chord(Shortcut.SEARCH))
        assertEquals(Shortcut.NEW_MESSAGE.default, loaded.chord(Shortcut.NEW_MESSAGE))
    }
}
