package net.ithandsfree.softphone.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Save / Share / Copy for a conversation picture: download with the token, save into Photos, share through FileProvider. */
@RunWith(AndroidJUnit4::class)
class ChatImageExportTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun png(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2255AA.toInt()) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /** One-request HTTP server: answers the picture only when the line's token header is present. */
    private fun serveOnce(body: ByteArray): Int {
        val server = ServerSocket(0)
        thread {
            server.use { s ->
                s.accept().use { sock ->
                    val head = sock.getInputStream().bufferedReader().let { r ->
                        generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    }
                    val ok = head.any { it.equals("Authorization: Bearer test-token", ignoreCase = true) }
                    val out = sock.getOutputStream()
                    if (ok) {
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(body)
                    } else {
                        out.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    }
                    out.flush()
                }
            }
        }
        return server.localPort
    }

    @Test
    fun downloadSaveAndShareUri() = runBlocking {
        val bytes = png()
        val port = serveOnce(bytes)
        val picture = ChatImageExport.download(
            ctx,
            "http://127.0.0.1:$port/media/photo%201.png",
            "Authorization" to "Bearer test-token",
            "photo 1.png",
        )
        // Type comes from the bytes, not the server's generic content type.
        assertEquals("image/png", picture.mimeType)
        assertEquals("IHF_photo_1.png", picture.file.name)
        assertTrue(picture.file.readBytes().contentEquals(bytes))

        // Share / Copy hand out a FileProvider address other apps can read.
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", picture.file)
        val read = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertTrue(read.contentEquals(bytes))

        if (Build.VERSION.SDK_INT >= 29) {
            assertTrue(ChatImageExport.saveToPhotos(ctx, picture))
            val found = ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH),
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(picture.file.name),
                null,
            )!!.use { c ->
                var match: android.net.Uri? = null
                while (c.moveToNext()) {
                    if (c.getString(1).startsWith("Pictures/IHF Phone")) {
                        match = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    }
                }
                match
            }
            assertTrue("saved picture is in Pictures/IHF Phone", found != null)
            val saved = ctx.contentResolver.openInputStream(found!!)!!.use { BitmapFactory.decodeStream(it) }
            assertEquals(64, saved.width)
            ctx.contentResolver.delete(found, null, null)
        }
    }

    @Test
    fun withoutTheTokenNothingIsDownloaded() = runBlocking {
        val port = serveOnce(png())
        val failed = runCatching {
            ChatImageExport.download(ctx, "http://127.0.0.1:$port/media/x.png", null, "x.png")
        }.isFailure
        assertTrue(failed)
    }
}
