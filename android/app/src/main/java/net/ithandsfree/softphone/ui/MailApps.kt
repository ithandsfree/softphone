package net.ithandsfree.softphone.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import java.util.Locale

/**
 * One installed app that can open a mailbox. [activityName] is the activity
 * the system reported for that app.
 */
data class MailAppTarget(
    val packageName: String,
    val activityName: String,
    val label: String,
)

/**
 * Keep one entry per app, drop this app, and sort by the name the user sees.
 */
fun selectMailApps(found: List<MailAppTarget>, ownPackage: String): List<MailAppTarget> {
    val seen = HashSet<String>()
    return found
        .asSequence()
        .filter { it.packageName.isNotBlank() && it.activityName.isNotBlank() }
        .filter { it.packageName != ownPackage }
        .filter { seen.add(it.packageName) }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
        .toList()
}

/**
 * Show every mail app installed on this phone (Gmail, Outlook, and others)
 * instead of opening whichever app is the default.
 */
fun openMailAppChooser(context: Context) {
    val targets = selectMailApps(discoverMailApps(context), context.packageName)
    val intents = targets.map { target ->
        Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
            component = ComponentName(target.packageName, target.activityName)
        }
    }
    val launch = when {
        intents.isEmpty() -> Intent.createChooser(
            Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")),
            "Choose email app",
        )
        intents.size == 1 -> Intent.createChooser(intents[0], "Choose email app")
        else -> Intent.createChooser(intents[0], "Choose email app").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
        }
    }
    runCatching { context.startActivity(launch) }
}

private fun discoverMailApps(context: Context): List<MailAppTarget> {
    val pm = context.packageManager
    val email = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL)
    val fromCategory = queryActivities(pm, email).mapNotNull { it.toMailApp(pm) }
    if (fromCategory.isNotEmpty()) return fromCategory
    val mailto = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:"))
    return queryActivities(pm, mailto)
        .mapNotNull { it.toMailApp(pm) }
        .filterNot { it.packageName in browserPackages }
}

private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }
}

private fun ResolveInfo.toMailApp(pm: PackageManager): MailAppTarget? {
    val activity = activityInfo ?: return null
    val pkg = activity.packageName ?: return null
    val name = activity.name ?: return null
    val label = loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
    return MailAppTarget(pkg, name, label)
}

/** mailto: is also claimed by browsers. They are not mail apps. */
private val browserPackages = setOf(
    "com.android.chrome",
    "com.chrome.beta",
    "com.chrome.dev",
    "com.sec.android.app.sbrowser",
    "com.microsoft.emmx",
    "org.mozilla.firefox",
    "org.mozilla.firefox_beta",
    "com.brave.browser",
    "com.opera.browser",
)
