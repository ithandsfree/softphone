package net.ithandsfree.softphone.win

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Diagnostic log (0.1.35). The voice engine's lines (SIP signalling with credentials removed, call ends with their
 * jitter / loss report, transfer progress) and app events go to `%LOCALAPPDATA%\IHF Phone\logs\ihf-phone-<date>.log`.
 * During a call it also samples how loud the other person's audio arrives, which tells a quiet caller apart from a
 * quiet speaker on this PC. Fourteen days are kept. Nothing leaves the PC unless the person exports it. GPL-2.0.
 */
internal object DiagLog {
    private const val KEEP_DAYS = 14L
    private const val MAX_BYTES_PER_DAY = 25L * 1024 * 1024
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val lock = Any()
    private var day: LocalDate? = null
    private var out: java.io.Writer? = null
    private var written = 0L
    private var full = false
    @Volatile private var started = false

    // Incoming-audio sampling for the current call.
    private var sampling = false
    private var sum = 0L
    private var count = 0
    private var peak = 0
    private var quiet = 0
    private var lastReport = 0L

    fun dir(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "logs")
    }

    /** Starts draining the voice engine's log. Safe to call more than once. */
    fun start() {
        if (started) return
        started = true
        prune()
        app("IHF Phone $APP_BUILD started; Java ${System.getProperty("java.version")}; ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            app("uncaught on ${thread.name}: ${err.stackTraceToString().take(4000)}")
            previous?.uncaughtException(thread, err)
        }
        val worker = Thread {
            while (true) {
                try {
                    var line = SipBridge.pollLog()
                    while (line != null) {
                        write("sip", line)
                        line = SipBridge.pollLog()
                    }
                    sampleAudio()
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (err: Throwable) {
                    // Logging must never take the phone down.
                }
            }
        }
        worker.isDaemon = true
        worker.name = "ihf-diag-log"
        worker.start()
    }

    /** An app event (shown toasts, failed actions, setting changes). */
    fun app(message: String) = write("app", message)

    private fun sampleAudio() {
        val snap = SipBridge.snapshot()
        val live = snap.callActive && !snap.incoming && !snap.held
        if (!live) {
            if (sampling) report(final = true)
            sampling = false
            return
        }
        val level = SipBridge.rxLevel()
        if (level < 0) return
        if (!sampling) {
            sampling = true
            sum = 0; count = 0; peak = 0; quiet = 0
            lastReport = System.currentTimeMillis()
        }
        sum += level
        count++
        if (level > peak) peak = level
        if (level < 8) quiet++
        if (System.currentTimeMillis() - lastReport >= 15_000) report(final = false)
    }

    private fun report(final: Boolean) {
        if (count == 0) return
        val avg = sum / count
        val silent = quiet * 100 / count
        // Speech normally peaks well above 60 of 255 here; a peak under ~25 means the audio arrives quiet.
        write("audio", "incoming level avg $avg peak $peak (0-255), silent ${silent}% of ${count / 4}s" + if (final) " [call end]" else "")
        sum = 0; count = 0; peak = 0; quiet = 0
        lastReport = System.currentTimeMillis()
    }

    private fun write(source: String, raw: String) {
        val line = redact(raw) ?: return
        synchronized(lock) {
            try {
                val today = LocalDate.now()
                if (today != day) {
                    out?.close()
                    dir().mkdirs()
                    val file = File(dir(), "ihf-phone-$today.log")
                    written = if (file.exists()) file.length() else 0L
                    full = written >= MAX_BYTES_PER_DAY
                    out = java.io.OutputStreamWriter(java.io.FileOutputStream(file, true), Charsets.UTF_8).buffered()
                    day = today
                }
                if (full) return
                val text = "${LocalTime.now().format(time)} $source | $line\n"
                out?.write(text)
                out?.flush()
                written += text.length
                if (written >= MAX_BYTES_PER_DAY) {
                    out?.write("${LocalTime.now().format(time)} app | log full for today; later lines dropped\n")
                    out?.flush()
                    full = true
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** Last line of defence: the native side already strips Authorization headers. */
    internal fun redact(line: String): String? {
        val lower = line.lowercase()
        if ("password" in lower || "x-ihf-token" in lower || "bearer " in lower) return null
        if (lower.trimStart().startsWith("authorization:") || lower.trimStart().startsWith("proxy-authorization:")) {
            return line.trimStart().substringBefore(':') + ": [removed]"
        }
        return line.take(2000)
    }

    private fun prune() {
        val cutoff = LocalDate.now().minusDays(KEEP_DAYS)
        dir().listFiles { f -> f.name.startsWith("ihf-phone-") && f.name.endsWith(".log") }?.forEach { f ->
            val date = runCatching { LocalDate.parse(f.name.removePrefix("ihf-phone-").removeSuffix(".log")) }.getOrNull()
            if (date != null && date.isBefore(cutoff)) f.delete()
        }
    }

    /** Zips the logs and a short system summary to [target] for support. Returns the number of log files. */
    fun export(target: File, summary: String): Int {
        synchronized(lock) { runCatching { out?.flush() } }
        val logs = dir().listFiles { f -> f.name.endsWith(".log") }?.sortedBy { it.name }.orEmpty()
        ZipOutputStream(target.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("summary.txt"))
            zip.write(summary.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            logs.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        app("diagnostic log exported (${logs.size} files)")
        return logs.size
    }
}
