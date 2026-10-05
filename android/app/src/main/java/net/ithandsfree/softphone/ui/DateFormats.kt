package net.ithandsfree.softphone.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Formats FreePBX SMS timestamps (unix seconds/ms or already-readable strings). */
fun formatSmsTimestamp(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val trimmed = raw.trim()
    if (trimmed.contains('-') || trimmed.contains(':') || trimmed.contains('/')) {
        return trimmed
    }
    val epoch = trimmed.toLongOrNull() ?: return trimmed
    val millis = if (epoch < 1_000_000_000_000L) epoch * 1000L else epoch
    return formatCallWhen(millis)
}

fun formatCallWhen(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) ->
            timeFmt.format(Date(millis))
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) == 1 ->
            "Yesterday"
        now.timeInMillis - millis < TimeUnit.DAYS.toMillis(7) ->
            SimpleDateFormat("EEE", Locale.getDefault()).format(Date(millis))
        else ->
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}

fun formatCallDuration(sec: Int): String {
    if (sec <= 0) return ""
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
