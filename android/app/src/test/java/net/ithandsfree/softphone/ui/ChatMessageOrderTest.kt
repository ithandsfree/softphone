package net.ithandsfree.softphone.ui

import net.ithandsfree.softphone.data.ChatMessage
import net.ithandsfree.softphone.ui.screens.newestFirstMessages
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Chat opens with reverseLayout + newest-first — verify sort puts the latest
 * message at index 0 (bottom of the reversed LazyColumn).
 */
class ChatMessageOrderTest {
    @Test
    fun newestFirst_byIdThenTimestamp() {
        val msgs = listOf(
            ChatMessage(id = 1, body = "oldest", timestamp = "100"),
            ChatMessage(id = 3, body = "newest", timestamp = "300"),
            ChatMessage(id = 2, body = "mid", timestamp = "200"),
        )
        assertEquals(
            listOf("newest", "mid", "oldest"),
            newestFirstMessages(msgs).map { it.body },
        )
    }
}
