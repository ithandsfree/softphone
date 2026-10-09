package net.ithandsfree.softphone.win

import com.sun.jna.platform.win32.Crypt32Util
import java.util.Base64

/**
 * Seals line secrets (SIP password, bearer, User Manager password) with Windows DPAPI, so only the same Windows
 * user on the same PC can read them back. Values written by older builds are plain text; [open] still reads
 * them, and the next save seals them. GPL-2.0.
 */
internal object SecretBox {
    private const val PREFIX = "dpapi:"
    private val entropy = "IHF Phone line".toByteArray(Charsets.UTF_8)

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val sealed = Crypt32Util.cryptProtectData(plain.toByteArray(Charsets.UTF_8), entropy, 0, "IHF Phone", null)
        return PREFIX + Base64.getEncoder().encodeToString(sealed)
    }

    /** Plain text for a sealed value; a value without the prefix is returned as it is (older builds). */
    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            String(Crypt32Util.cryptUnprotectData(bytes, entropy, 0, null), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)
}
