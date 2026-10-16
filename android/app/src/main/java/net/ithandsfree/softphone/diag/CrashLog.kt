package net.ithandsfree.softphone.diag

import android.content.Context
import android.content.Intent
import android.os.Build
import net.ithandsfree.softphone.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps the last crash (and start-up problems the app recovered from) in a private file, so a tester can send it
 * from the app on the next start. No account data, numbers or messages are written: only versions, the phone model
 * and the error with its stack trace.
 */
object CrashLog {
    private const val FILE = "crash-report.txt"
    private const val MAX_CHARS = 60_000

    /** Call first in Application.onCreate: records an uncaught crash, then lets Android close the app as usual. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(app, "CRASH on thread ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A problem the app recovered from (for example a store reset); kept for the report, the app keeps running. */
    fun recordNonFatal(context: Context, what: String, error: Throwable) {
        runCatching { append(context.applicationContext, "RECOVERED: $what", error) }
    }

    private const val STEPS = "startup-steps.txt"

    /** Last step of a start without a screen (boot, message checks); a trail ending here finished normally. */
    const val APP_READY = "app ready"

    /**
     * Call at the very start of Application.onCreate. When the previous start never finished (any crash, including
     * one in native code that leaves no Java stack trace), records the last step it reached and returns it; the app
     * then starts in safe mode so the report can be sent. Starts a new trail either way.
     */
    fun beginStartup(context: Context): String? {
        val f = File(context.filesDir, STEPS)
        val stuckAfter = (if (f.exists()) f.readLines().lastOrNull { it.isNotBlank() } ?: "app start" else null)
            ?.takeIf { it != APP_READY }
        if (stuckAfter != null) {
            runCatching {
                appendText(
                    context,
                    "PREVIOUS START DID NOT FINISH: last step reached \"$stuckAfter\" (the crash was in the step after " +
                        "it; a crash in native code leaves no stack trace). This start runs in safe mode: calling paused.",
                )
            }
        }
        f.writeText("")
        return stuckAfter
    }

    /** Marks a start-up step as reached (the trail that [beginStartup] reads after a failed start). */
    fun step(context: Context, name: String) {
        runCatching { File(context.filesDir, STEPS).appendText("$name\n") }
    }

    /** The app is up and showing its screen: the start finished. */
    fun startupDone(context: Context) {
        File(context.filesDir, STEPS).delete()
    }

    fun read(context: Context): String? =
        file(context).takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }

    /** An email / share sheet with the report, for the tester to send to IT Hands Free. */
    fun shareIntent(context: Context): Intent? {
        val report = read(context) ?: return null
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "IHF Phone crash report ${BuildConfig.VERSION_NAME} (${Build.MANUFACTURER} ${Build.MODEL})")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        return Intent.createChooser(send, "Send crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun appendText(context: Context, title: String) {
        val entry = "=== $title at ${Instant.now()}\napp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "phone ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n"
        val f = file(context)
        val existing = if (f.exists()) f.readText() else ""
        f.writeText((existing + entry).takeLast(MAX_CHARS))
    }

    private fun append(context: Context, title: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            append("=== ").append(title).append(" at ").append(Instant.now()).append('\n')
            append("app ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("phone ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append(trace).append('\n')
        }
        val f = file(context)
        val existing = if (f.exists()) f.readText() else ""
        f.writeText((existing + entry).takeLast(MAX_CHARS))
    }

    private fun file(context: Context) = File(context.filesDir, FILE)
}
