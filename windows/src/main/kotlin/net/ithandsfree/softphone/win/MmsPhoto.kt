package net.ithandsfree.softphone.win

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

internal data class MmsPhoto(
    val name: String,
    val type: String,
    val bytes: ByteArray,
)

/** Keep a picture under the same 1 MB ceiling Android uses, shrinking it when needed. */
internal fun prepareMmsPhoto(
    raw: ByteArray,
    suggestedName: String = "photo.jpg",
    maxBytes: Int = 1_000_000,
): MmsPhoto {
    if (raw.isEmpty()) throw IllegalArgumentException("That photo is empty.")
    if (raw.size <= maxBytes) {
        val name = cleanPhotoName(suggestedName)
        return MmsPhoto(name, photoType(name), raw)
    }
    val image = ImageIO.read(ByteArrayInputStream(raw))
        ?: throw IllegalArgumentException("Photo is over 1 MB and could not be resized.")
    var scale = 0.8
    var quality = 0.72f
    repeat(8) {
        val fitted = jpeg(image, scale, quality)
        if (fitted.size <= maxBytes) return MmsPhoto("photo.jpg", "image/jpeg", fitted)
        scale *= 0.72
        quality = (quality - 0.08f).coerceAtLeast(0.4f)
    }
    throw IllegalArgumentException("Photo is still over 1 MB after resizing.")
}

private fun jpeg(image: BufferedImage, scale: Double, quality: Float): ByteArray {
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    val scaled = image.getScaledInstance(width, height, Image.SCALE_SMOOTH)
    val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    canvas.createGraphics().apply {
        drawImage(scaled, 0, 0, java.awt.Color.WHITE, null)
        dispose()
    }
    val output = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val stream = ImageIO.createImageOutputStream(output)
    writer.output = stream
    val param = writer.defaultWriteParam
    if (param.canWriteCompressed()) {
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = quality
    }
    writer.write(null, IIOImage(canvas, null, null), param)
    writer.dispose()
    stream.close()
    return output.toByteArray()
}

private fun cleanPhotoName(name: String): String {
    val leaf = name.substringAfterLast('\\').substringAfterLast('/').ifBlank { "photo.jpg" }
    val cleaned = leaf.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
    return cleaned.ifBlank { "photo.jpg" }
}

private fun photoType(name: String): String = when {
    name.endsWith(".png", ignoreCase = true) -> "image/png"
    name.endsWith(".gif", ignoreCase = true) -> "image/gif"
    name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
    else -> "application/octet-stream"
}
