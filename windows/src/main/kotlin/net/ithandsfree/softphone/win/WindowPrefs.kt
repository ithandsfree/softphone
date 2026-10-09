package net.ithandsfree.softphone.win

import java.awt.Rectangle
import java.io.File
import java.util.Properties

/** Remembers where this window sat, whether it stays on top, and do-not-disturb. */
internal object WindowPrefs {
    fun bounds(): Rectangle? {
        val props = load()
        val width = props.getProperty("width")?.toIntOrNull() ?: return null
        val height = props.getProperty("height")?.toIntOrNull() ?: return null
        val x = props.getProperty("x")?.toIntOrNull() ?: return null
        val y = props.getProperty("y")?.toIntOrNull() ?: return null
        if (width < 360 || height < 400) return null
        return Rectangle(x, y, width, height)
    }

    fun pinned(): Boolean = load().getProperty("pin") == "true"

    fun dnd(): Boolean = load().getProperty("dnd") == "true"

    /** Call waiting (0.1.39): a second call rings beside the current one. On unless turned off. */
    fun callWaiting(): Boolean = load().getProperty("callWaiting") != "false"

    fun saveCallWaiting(enabled: Boolean) {
        val props = load()
        props.setProperty("callWaiting", if (enabled) "true" else "false")
        store(props)
    }

    fun saveBounds(x: Int, y: Int, width: Int, height: Int) {
        val props = load()
        props.setProperty("x", x.toString())
        props.setProperty("y", y.toString())
        props.setProperty("width", width.toString())
        props.setProperty("height", height.toString())
        store(props)
    }

    fun savePin(enabled: Boolean) {
        val props = load()
        props.setProperty("pin", if (enabled) "true" else "false")
        store(props)
    }

    fun saveDnd(enabled: Boolean) {
        val props = load()
        props.setProperty("dnd", if (enabled) "true" else "false")
        store(props)
    }

    private fun load(): Properties {
        val props = Properties()
        val file = file()
        if (file.isFile) file.inputStream().use { props.load(it) }
        return props
    }

    private fun store(props: Properties) {
        val file = file()
        file.parentFile?.mkdirs()
        file.outputStream().use { props.store(it, "IHF Phone window") }
    }

    private fun file(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "window.properties")
    }
}
