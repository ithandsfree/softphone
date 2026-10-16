package net.ithandsfree.softphone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasteNumberTest {
    @Test
    fun cleansNumbersFromMapsMailAndMessages() {
        assertEquals("14165550177", numberFromPaste("+1 (416) 555-0177"))
        assertEquals("4165550177", numberFromPaste("416.555.0177"))
        assertEquals("4165550177", numberFromPaste(" (416) 555-0177 \n"))
        assertEquals("14165550177", numberFromPaste("tel:+14165550177"))
        assertEquals("14165550177", numberFromPaste("tel:%2B1-416-555-0177"))
        assertEquals("4165550177", numberFromPaste("416-555-0177 x12"))
        assertEquals("4165550177", numberFromPaste("416-555-0177 ext. 12"))
        assertEquals("14165550177", numberFromPaste("tel:+14165550177;ext=12"))
        assertEquals("*97", numberFromPaste("*97"))
    }

    @Test
    fun refusesTextThatIsNotANumber() {
        assertNull(numberFromPaste(null))
        assertNull(numberFromPaste("   "))
        assertNull(numberFromPaste("Call me at the office"))
        assertNull(numberFromPaste("https://maps.example.com/place/123"))
        assertNull(numberFromPaste("7"))
    }
}
