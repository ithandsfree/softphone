package net.ithandsfree.softphone.ui.screens

/**
 * Decide whether a FreePBX / SMS Connector media part should open in the
 * fullscreen zoom viewer. Connector payloads often omit `type` or use
 * `application/octet-stream`; fall back to filename / URL extension.
 */
internal fun isZoomableChatMedia(type: String?, name: String, url: String?): Boolean {
    val mime = type?.trim()?.lowercase().orEmpty()
    if (mime.startsWith("image/")) return true
    if (mime.startsWith("video/") ||
        mime.startsWith("audio/") ||
        mime == "application/pdf" ||
        mime == "text/plain" ||
        mime == "text/vcard" ||
        mime == "text/x-vcard"
    ) {
        return false
    }
    val path = sequenceOf(name, url.orEmpty())
        .map { it.substringAfterLast('/').substringBefore('?').lowercase() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    return path.endsWith(".jpg") ||
        path.endsWith(".jpeg") ||
        path.endsWith(".png") ||
        path.endsWith(".gif") ||
        path.endsWith(".webp") ||
        path.endsWith(".heic") ||
        path.endsWith(".heif") ||
        path.endsWith(".bmp") ||
        // Unknown MIME + no extension: still try Coil (live MMS often looks like this).
        mime.isBlank() ||
        mime == "application/octet-stream"
}
