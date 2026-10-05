package net.ithandsfree.softphone.ui

import net.ithandsfree.softphone.ui.screens.isZoomableChatMedia
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaZoomTest {
    @Test
    fun imageMime_isZoomable() {
        assertTrue(isZoomableChatMedia("image/jpeg", "pic.jpg", null))
        assertTrue(isZoomableChatMedia("IMAGE/PNG", "x", "https://pbx/media/a"))
    }

    @Test
    fun freepbxBlankType_withImageExtension_isZoomable() {
        assertTrue(
            isZoomableChatMedia(
                null,
                "",
                "https://pbx.example.com/ihf-softphone/index.php/v1/media/abc123.jpg",
            ),
        )
    }

    @Test
    fun freepbxOctetStream_isZoomable() {
        assertTrue(isZoomableChatMedia("application/octet-stream", "mms-part", null))
    }

    @Test
    fun nonImageMime_notZoomable() {
        assertFalse(isZoomableChatMedia("video/mp4", "clip.mp4", null))
        assertFalse(isZoomableChatMedia("application/pdf", "doc.pdf", null))
        assertFalse(isZoomableChatMedia("audio/mpeg", "voicenote.mp3", null))
    }
}
