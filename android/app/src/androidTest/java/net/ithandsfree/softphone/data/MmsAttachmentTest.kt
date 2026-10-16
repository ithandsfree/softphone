package net.ithandsfree.softphone.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

/** Runs on a device or emulator: real Android image decoding, as the app does it when sending a picture message. */
@RunWith(AndroidJUnit4::class)
class MmsAttachmentTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun photo(name: String, w: Int, h: Int, format: Bitmap.CompressFormat, quality: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = Random(7)
        // Noise so the file is as large as a real camera photo, not a flat colour that compresses to nothing.
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        val f = File(ctx.cacheDir, name)
        FileOutputStream(f).use { bmp.compress(format, quality, it) }
        bmp.recycle()
        return f
    }

    @Test
    fun cameraSizedPhotoBecomesSmallJpeg() {
        val src = photo("camera.jpg", 4000, 3000, Bitmap.CompressFormat.JPEG, 95)
        assertTrue("test photo should be camera-sized, was ${src.length()}", src.length() > 2_000_000)
        val out = MmsAttachment.prepare(ctx.cacheDir, ctx.contentResolver, Uri.fromFile(src))
        assertEquals("image/jpeg", out.mimeType)
        assertTrue("name must end .jpg for the BFF: ${out.file.name}", out.file.name.endsWith(".jpg"))
        assertTrue("must fit the MMS limit, was ${out.file.length()}", out.file.length() <= 1_450_000)
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.file.path, b)
        assertTrue("long side at most 1600, was ${b.outWidth}x${b.outHeight}", maxOf(b.outWidth, b.outHeight) <= 1600)
        assertTrue("decodable", b.outWidth > 0 && b.outHeight > 0)
    }

    @Test
    fun smallPngIsSentAsJpegToo() {
        val src = photo("shot.png", 600, 400, Bitmap.CompressFormat.PNG, 100)
        val out = MmsAttachment.prepare(ctx.cacheDir, ctx.contentResolver, Uri.fromFile(src))
        assertTrue(out.file.name.endsWith(".jpg"))
        assertTrue(out.file.length() in 1..1_450_000)
    }

    @Test
    fun unsupportedFileIsRefusedWithAPlainReason() {
        val src = File(ctx.cacheDir, "notes.zip").apply { writeBytes(ByteArray(100)) }
        try {
            MmsAttachment.prepare(ctx.cacheDir, ctx.contentResolver, Uri.fromFile(src))
            fail("a .zip must be refused")
        } catch (e: MmsAttachment.Problem) {
            assertTrue(e.message!!.contains("can't be sent"))
        }
    }
}
