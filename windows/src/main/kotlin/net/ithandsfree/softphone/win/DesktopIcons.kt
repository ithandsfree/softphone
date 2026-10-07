package net.ithandsfree.softphone.win

import java.awt.Image
import java.awt.image.BaseMultiResolutionImage
import javax.imageio.ImageIO

/** Window, tray, and taskbar artwork. One drawing per size. GPL-2.0. */
internal object DesktopIcons {
    fun flavor(profile: DistributionProfile): String =
        if (profile.id == Distribution.COMMUNITY) "community" else "ihf"

    fun windowIcons(flavor: String): List<Image> {
        val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
        return sizes.mapNotNull { size -> read(flavor, "app/png/icon-$size.png") }
    }

    fun trayImage(flavor: String, theme: String, state: String): Image? {
        val images = listOf(16, 20, 24, 32, 40, 48).mapNotNull { size ->
            read(flavor, "tray/$theme/$state-$size.png")
        }
        if (images.isEmpty()) return null
        return BaseMultiResolutionImage(*images.toTypedArray())
    }

    fun badgeImage(flavor: String, name: String, scale: Double): Image? {
        val size = when {
            scale >= 2.0 -> 32
            scale >= 1.5 -> 24
            scale >= 1.25 -> 20
            else -> 16
        }
        return read(flavor, "overlay/$name-$size.png")
    }

    /** The taskbar follows SystemUsesLightTheme, not the app theme. 1 means a light taskbar. */
    fun taskbarIsLight(): Boolean {
        val text = runCatching {
            val process = ProcessBuilder(
                "reg",
                "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v",
                "SystemUsesLightTheme",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        }.getOrDefault("")
        return text.contains("0x1")
    }

    private fun read(flavor: String, path: String): Image? {
        val stream = DesktopIcons::class.java.getResourceAsStream("/icons/$flavor/$path") ?: return null
        return stream.use { ImageIO.read(it) }
    }
}
