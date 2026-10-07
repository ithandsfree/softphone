package net.ithandsfree.softphone.win

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.ImageIcon
import javax.swing.filechooser.FileSystemView

/** One installed program that opens an inbox. GPL-2.0. */
internal data class MailAppTarget(
    val label: String,
    val command: List<String>,
)

/** A mail program found in the registry, a store package, or a known install path. */
internal data class MailCandidate(
    val label: String,
    val command: List<String>,
)

/**
 * Mail programs on this PC, one row per program, ready to open the inbox.
 * Browsers and the old Hotmail handler are left out.
 */
internal fun installedMailApps(): List<MailAppTarget> {
    val found = ArrayList<MailCandidate>()
    found += storeMailApps()
    found += registryMailApps()
    found += knownDesktopMailApps()
    return normalizeMailApps(found).filter { commandCanRun(it.command) }
}

internal fun normalizeMailApps(candidates: List<MailCandidate>): List<MailAppTarget> {
    val byProgram = LinkedHashMap<String, MailAppTarget>()
    for (candidate in candidates) {
        val command = inboxCommand(candidate.command)
        if (command.isEmpty() || !isMailProgram(candidate.label, command)) continue
        val key = programKey(command)
        if (byProgram.containsKey(key)) continue
        byProgram[key] = MailAppTarget(candidate.label, command)
    }
    val hasNew = byProgram.values.any { isNewOutlook(it) }
    val hasClassic = byProgram.values.any { isClassicOutlook(it) }
    return byProgram.values
        .map { app -> app.copy(label = displayLabel(app, hasNew, hasClassic)) }
        .sortedBy { it.label.lowercase() }
}

internal fun splitCommandLine(raw: String): List<String> {
    val parts = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    for (ch in raw.trim()) {
        when {
            ch == '"' -> quoted = !quoted
            ch.isWhitespace() && !quoted -> {
                if (current.isNotEmpty()) {
                    parts += current.toString()
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) parts += current.toString()
    return parts
}

private fun storeMailApps(): List<MailCandidate> {
    val local = System.getenv("LOCALAPPDATA").orEmpty()
    if (local.isBlank()) return emptyList()
    val found = ArrayList<MailCandidate>()
    val packages = File(local, "Packages")
    if (File(packages, "Microsoft.OutlookForWindows_8wekyb3d8bbwe").isDirectory) {
        val alias = File(local, "Microsoft\\WindowsApps\\olk.exe")
        if (alias.exists()) {
            found += MailCandidate("Outlook", listOf(alias.absolutePath))
        } else {
            found += MailCandidate(
                "Outlook",
                listOf(
                    "explorer.exe",
                    "shell:AppsFolder\\Microsoft.OutlookForWindows_8wekyb3d8bbwe!Microsoft.OutlookforWindows",
                ),
            )
        }
    }
    if (File(packages, "microsoft.windowscommunicationsapps_8wekyb3d8bbwe").isDirectory) {
        found += MailCandidate(
            "Windows Mail",
            listOf(
                "explorer.exe",
                "shell:AppsFolder\\microsoft.windowscommunicationsapps_8wekyb3d8bbwe!microsoft.windowslive.mail",
            ),
        )
    }
    return found
}

private fun registryMailApps(): List<MailCandidate> {
    val roots = listOf(
        "HKCU\\Software\\Clients\\Mail",
        "HKLM\\Software\\Clients\\Mail",
        "HKLM\\Software\\WOW6432Node\\Clients\\Mail",
    )
    val found = ArrayList<MailCandidate>()
    for (root in roots) {
        val listing = regText(root) ?: continue
        val subkeys = listing.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("HKEY_") && it.contains("\\Clients\\Mail\\", ignoreCase = true) }
        for (key in subkeys) {
            val label = regValue(key).orEmpty()
            val commandLine = regValue("$key\\shell\\open\\command") ?: continue
            val command = splitCommandLine(expandEnv(commandLine))
            if (command.isNotEmpty()) found += MailCandidate(label.ifBlank { "Email" }, command)
        }
    }
    return found
}

private fun knownDesktopMailApps(): List<MailCandidate> {
    val programFiles = System.getenv("ProgramFiles").orEmpty()
    val programFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()
    val local = System.getenv("LOCALAPPDATA").orEmpty()
    val found = ArrayList<MailCandidate>()
    fun add(label: String, candidates: List<File>, vararg args: String) {
        val exe = candidates.firstOrNull { it.isFile && it.length() > 0L } ?: return
        found += MailCandidate(label, listOf(exe.absolutePath) + args)
    }
    add(
        "Thunderbird",
        listOf(
            File(programFiles, "Mozilla Thunderbird\\thunderbird.exe"),
            File(programFilesX86, "Mozilla Thunderbird\\thunderbird.exe"),
            File(local, "Mozilla Thunderbird\\thunderbird.exe"),
        ),
        "-mail",
    )
    add(
        "eM Client",
        listOf(
            File(programFiles, "eM Client\\MailClient.exe"),
            File(programFilesX86, "eM Client\\MailClient.exe"),
        ),
    )
    add(
        "Mailbird",
        listOf(
            File(local, "Mailbird\\Mailbird.exe"),
            File(local, "mailbird\\Mailbird.exe"),
            File(programFiles, "Mailbird\\Mailbird.exe"),
        ),
    )
    add(
        "Spark",
        listOf(
            File(local, "Programs\\Spark Desktop\\Spark Desktop.exe"),
            File(local, "Programs\\Spark\\Spark.exe"),
            File(programFiles, "Spark\\Spark.exe"),
        ),
    )
    add(
        "Mailspring",
        listOf(File(local, "Mailspring\\Mailspring.exe"), File(programFiles, "Mailspring\\Mailspring.exe")),
    )
    add(
        "Postbox",
        listOf(File(programFiles, "Postbox\\postbox.exe"), File(programFilesX86, "Postbox\\postbox.exe")),
    )
    return found
}

private fun inboxCommand(command: List<String>): List<String> {
    if (command.isEmpty()) return emptyList()
    val exeName = File(command.first()).name.lowercase()
    val exe = command.first()
    return when (exeName) {
        "outlook.exe" -> listOf(exe, "/recycle")
        "thunderbird.exe" -> listOf(exe, "-mail")
        else -> {
            val compose = command.any { arg ->
                arg.equals("/c", ignoreCase = true) ||
                    arg.equals("-compose", ignoreCase = true) ||
                    arg.startsWith("ipm.note", ignoreCase = true)
            }
            if (compose) listOf(exe) else command
        }
    }
}

private fun isMailProgram(label: String, command: List<String>): Boolean {
    val exeName = File(command.first()).name.lowercase()
    if (exeName == "rundll32.exe") return false
    if (exeName in browserExecutables) return false
    if (label.contains("hotmail", ignoreCase = true)) return false
    if (command.any { it.contains("hmmapi", ignoreCase = true) }) return false
    return true
}

private fun programKey(command: List<String>): String {
    if (command.first().endsWith("explorer.exe", ignoreCase = true) && command.size > 1) {
        return command[1].lowercase()
    }
    return command.first().lowercase()
}

private fun isNewOutlook(app: MailAppTarget): Boolean =
    app.command.any { part ->
        part.contains("olk.exe", ignoreCase = true) ||
            part.contains("OutlookForWindows", ignoreCase = true)
    }

private fun isClassicOutlook(app: MailAppTarget): Boolean =
    File(app.command.first()).name.equals("outlook.exe", ignoreCase = true)

private fun displayLabel(app: MailAppTarget, hasNew: Boolean, hasClassic: Boolean): String {
    return when {
        hasNew && hasClassic && isNewOutlook(app) -> "Outlook (new)"
        hasNew && hasClassic && isClassicOutlook(app) -> "Outlook (classic)"
        isNewOutlook(app) || isClassicOutlook(app) -> "Outlook"
        app.label.isBlank() -> File(app.command.first()).nameWithoutExtension
        else -> app.label
    }
}

private fun commandCanRun(command: List<String>): Boolean {
    if (command.isEmpty()) return false
    val exe = command.first()
    if (exe.equals("explorer.exe", ignoreCase = true)) return true
    val file = File(exe)
    if (!file.exists()) return false
    if (exe.contains("\\WindowsApps\\", ignoreCase = true)) return true
    return file.isFile && file.length() > 0L
}

private fun regValue(key: String): String? {
    val text = regText(key) ?: return null
    val line = text.lineSequence().firstOrNull {
        it.contains("REG_SZ") || it.contains("REG_EXPAND_SZ")
    } ?: return null
    val mark = when {
        line.contains("REG_EXPAND_SZ") -> line.indexOf("REG_EXPAND_SZ") + "REG_EXPAND_SZ".length
        else -> line.indexOf("REG_SZ") + "REG_SZ".length
    }
    return line.substring(mark).trim().ifBlank { null }
}

private fun regText(key: String): String? {
    val process = ProcessBuilder("reg", "query", key)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    if (code != 0 || text.contains("ERROR:", ignoreCase = true)) return null
    return text
}

/** App-list icon for the chooser. Store apps use their package logo; desktop apps use the exe icon. */
internal fun mailIcon(app: MailAppTarget): Image? {
    val logo = storeLogo(app)
    if (logo != null && logo.isFile) return ImageIcon(logo.absolutePath).image
    val exe = app.command.firstOrNull { path ->
        path.endsWith(".exe", ignoreCase = true) && !path.endsWith("explorer.exe", ignoreCase = true)
    } ?: return null
    val file = File(exe)
    if (!file.isFile) return null
    val icon = runCatching { FileSystemView.getFileSystemView().getSystemIcon(file, 32, 32) }.getOrNull() ?: return null
    if (icon is ImageIcon && icon.image != null) return icon.image
    val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    val canvas = image.createGraphics()
    icon.paintIcon(null, canvas, 0, 0)
    canvas.dispose()
    return image
}

private fun storeLogo(app: MailAppTarget): File? {
    val (packageName, relative) = when {
        isNewOutlook(app) -> "Microsoft.OutlookForWindows" to "assets\\AppList.targetsize-32.png"
        app.command.any { it.contains("windowscommunicationsapps", ignoreCase = true) } ->
            "microsoft.windowscommunicationsapps" to "images\\HxMailAppList.targetsize-32.png"
        else -> return null
    }
    val home = appxInstallLocation(packageName) ?: return null
    return File(home, relative)
}

private val packageHomes = HashMap<String, File?>()

private fun appxInstallLocation(name: String): File? {
    if (packageHomes.containsKey(name)) return packageHomes[name]
    val windows = System.getenv("SystemRoot") ?: "C:\\Windows"
    val powershell = File(windows, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
    val exe = if (powershell.isFile) powershell.absolutePath else "powershell.exe"
    val dir = runCatching {
        val process = ProcessBuilder(
            exe,
            "-NoProfile",
            "-Command",
            "(Get-AppxPackage -Name '$name').InstallLocation",
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        text.lineSequence().map { it.trim() }.firstOrNull { File(it).isDirectory }?.let { File(it) }
    }.getOrNull()
    packageHomes[name] = dir
    return dir
}

private fun expandEnv(value: String): String {
    var expanded = value
    val pattern = Regex("%([^%]+)%")
    for (match in pattern.findAll(value)) {
        val env = System.getenv(match.groupValues[1])
        if (!env.isNullOrEmpty()) expanded = expanded.replace(match.value, env)
    }
    return expanded
}

private val browserExecutables = setOf(
    "chrome.exe",
    "msedge.exe",
    "firefox.exe",
    "brave.exe",
    "opera.exe",
    "iexplore.exe",
)
