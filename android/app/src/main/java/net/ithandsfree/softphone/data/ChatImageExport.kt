package net.ithandsfree.softphone.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Save, share or copy a picture from a conversation (MMS). The original file is downloaded again from the BFF with
 * the line's token, so what is saved is the picture as received, not a screenshot or a re-compressed copy.
 */
object ChatImageExport {
    class Picture(val file: File, val mimeType: String)

    private const val DIR = "shared_images"

    suspend fun download(context: Context, url: String, header: Pair<String, String>?, name: String): Picture =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                header?.let { setRequestProperty(it.first, it.second) }
            }
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val bytes = conn.inputStream.use { it.readBytes() }
                val mime = sniff(bytes) ?: conn.contentType?.substringBefore(';')?.trim()?.lowercase() ?: "image/jpeg"
                val dir = File(context.cacheDir, DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() } // only the picture being handled is kept
                val file = File(dir, fileName(name, mime))
                file.writeBytes(bytes)
                Picture(file, mime)
            } finally {
                conn.disconnect()
            }
        }

    /** Into Pictures/IHF Phone (Android 10+). Returns false on Android 8 and 9, where saving needs Share instead. */
    suspend fun saveToPhotos(context: Context, picture: Picture): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, picture.file.name)
            put(MediaStore.Images.Media.MIME_TYPE, picture.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/IHF Phone")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Couldn't create the photo")
        try {
            resolver.openOutputStream(uri)?.use { out -> picture.file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't write the photo")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        true
    }

    fun share(context: Context, picture: Picture) {
        val uri = uriFor(context, picture)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = picture.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, picture.file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, "Share picture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Copies the picture itself; apps that accept pasted pictures (Gmail, Messages, WhatsApp, Docs) can paste it. */
    fun copy(context: Context, picture: Picture) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, picture.file.name, uriFor(context, picture)))
    }

    private fun uriFor(context: Context, picture: Picture) =
        FileProvider.getUriForFile(context, "${context.packageName}.files", picture.file)

    private fun sniff(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "image/png"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "image/gif"
        b.size > 12 && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }

    internal fun fileName(name: String, mime: String): String {
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
            .ifBlank { "picture" }.take(60)
        return "IHF_$base.$ext"
    }
}
