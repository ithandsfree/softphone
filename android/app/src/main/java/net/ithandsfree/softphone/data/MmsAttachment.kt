package net.ithandsfree.softphone.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Prepares a picture or file for an MMS. The BFF decides the type from the file name's extension and accepts at
 * most 1.5 MB (carrier / Voip.ms MMS limits). Until 0.4.18 the app named every attachment ".bin", so the BFF
 * refused all of them ("unsupported_file_type"), and refused photos over 1 MB outright.
 *
 * Photos (JPEG, PNG, WebP, HEIC) are scaled to at most 1600 px on the long side and saved as JPEG, stepping the
 * quality down until the file fits [TARGET_BYTES]; the camera's rotation is kept. GIFs and other supported files
 * (PDF, vCard, audio, video) are sent as they are when small enough.
 */
object MmsAttachment {
    class Prepared(val file: File, val mimeType: String)

    /** A problem the person can act on; the message is shown as is. */
    class Problem(message: String) : Exception(message)

    private const val TARGET_BYTES = 1_000_000L
    private const val BFF_MAX_BYTES = 1_450_000L
    private const val MAX_SIDE = 1600

    /** File types the BFF accepts (SmsGateway::sendMedia). */
    private val SUPPORTED = setOf("png", "jpg", "jpeg", "gif", "tiff", "pdf", "vcf", "mp3", "wav", "ogg", "mov", "avi", "mp4", "m4a", "ics")

    fun prepare(cacheDir: File, resolver: ContentResolver, uri: Uri): Prepared {
        // Some pickers and file URIs report no type: fall back to the file name's extension.
        val mime = (resolver.getType(uri) ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }).orEmpty().lowercase()
        val stamp = System.currentTimeMillis()
        if (mime.startsWith("image/") && mime != "image/gif") {
            return Prepared(photoAsJpeg(cacheDir, resolver, uri, "mms_$stamp.jpg"), "image/jpeg")
        }
        val ext = (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "").lowercase()
            .let { if (it == "jpe") "jpg" else it }
        if (ext !in SUPPORTED) {
            throw Problem("This file type can't be sent as a picture message. Send a photo, GIF, PDF, contact card or audio clip.")
        }
        val file = File(cacheDir, "mms_$stamp.$ext")
        copy(resolver, uri, file)
        if (file.length() > BFF_MAX_BYTES) {
            file.delete()
            throw Problem("That file is too large for a picture message (about 1.4 MB at most).")
        }
        return Prepared(file, mime.ifBlank { "application/octet-stream" })
    }

    /** Plain words for the BFF's refusal reasons. */
    fun explain(error: String): String = when {
        error.contains("unsupported_file_type") -> "This file type can't be sent as a picture message."
        error.contains("file_too_large") -> "That file is too large for a picture message (about 1.4 MB at most)."
        error.contains("mms_disabled") -> "Picture messages are turned off for this line."
        else -> "Couldn't send the picture ($error)."
    }

    private fun copy(resolver: ContentResolver, uri: Uri, out: File) {
        resolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } }
            ?: throw Problem("Couldn't read that file. Try choosing it again.")
    }

    private fun photoAsJpeg(cacheDir: File, resolver: ContentResolver, uri: Uri, name: String): File {
        // A bounds-only decode always returns null by design; the size arrives in [bounds]. (0.4.18 treated the null
        // as "couldn't read" and refused every photo.)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
        if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw Problem("Couldn't read that photo. Try choosing it again.")
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw Problem("Couldn't read that photo. Try choosing it again.")
        val scaled = scaleAndRotate(decoded, rotationOf(resolver, uri))
        val out = File(cacheDir, name)
        var quality = 85
        while (true) {
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            if (out.length() <= TARGET_BYTES || quality <= 40) break
            quality -= 10
        }
        scaled.recycle()
        if (out.length() > BFF_MAX_BYTES) {
            out.delete()
            throw Problem("That photo is too large to send as a picture message.")
        }
        return out
    }

    private fun rotationOf(resolver: ContentResolver, uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)

    private fun scaleAndRotate(src: Bitmap, degrees: Int): Bitmap {
        val longSide = maxOf(src.width, src.height)
        val scale = if (longSide > MAX_SIDE) MAX_SIDE.toFloat() / longSide else 1f
        if (scale == 1f && degrees == 0) return src
        val m = Matrix().apply {
            postScale(scale, scale)
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }
}
