package net.ithandsfree.softphone.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
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
 * Callers pass every source together. A Gmail hit from the default-email
 * category must not hide Outlook found another way.
 */
fun selectMailApps(found: List<MailAppTarget>, ownPackage: String): List<MailAppTarget> {
    val seen = HashSet<String>()
    return found
        .asSequence()
        .filter { it.packageName.isNotBlank() && it.activityName.isNotBlank() }
        .filter { it.packageName != ownPackage }
        .filter { it.packageName !in browserPackages }
        .filter { seen.add(it.packageName) }
        .sortedBy { it.label.lowercase(Locale.ROOT) }
        .toList()
}

/**
 * Show the mail apps installed on this phone. The list is drawn in this app
 * so Android cannot skip it and open the default (usually Gmail).
 */
fun openMailAppChooser(context: Context) {
    val apps = mailAppsOnDevice(context)
    if (apps.isEmpty()) {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")),
                    "Choose email app",
                ),
            )
        }
        return
    }
    val iconSize = (40 * context.resources.displayMetrics.density).toInt()
    val gap = (16 * context.resources.displayMetrics.density).toInt()
    val adapter = object : ArrayAdapter<MailAppTarget>(
        context,
        android.R.layout.select_dialog_item,
        android.R.id.text1,
        apps,
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            val app = getItem(position) ?: return view
            view.text = app.label
            view.compoundDrawablePadding = gap
            view.setCompoundDrawablesRelative(appIcon(context, app.packageName, iconSize), null, null, null)
            return view
        }
    }
    AlertDialog.Builder(context)
        .setTitle("Choose email app")
        .setAdapter(adapter) { _, which -> launchMailApp(context, apps[which]) }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun appIcon(context: Context, packageName: String, sizePx: Int): Drawable? {
    val raw = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return null
    val icon = raw.mutate()
    icon.setBounds(0, 0, sizePx, sizePx)
    return icon
}

internal fun mailAppsOnDevice(context: Context): List<MailAppTarget> {
    val pm = context.packageManager
    val fromCategory = queryActivities(
        pm,
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL),
    ).mapNotNull { it.toMailApp(pm) }
    val fromMailto = listOf(
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
        Intent(Intent.ACTION_VIEW, Uri.parse("mailto:")),
    ).flatMap { probe -> queryActivities(pm, probe).mapNotNull { it.toMailApp(pm) } }
    return selectMailApps(
        fromCategory + fromMailto + knownInstalledMailApps(pm),
        context.packageName,
    )
}

internal fun launchMailApp(context: Context, target: MailAppTarget) {
    val launch = context.packageManager.getLaunchIntentForPackage(target.packageName)
        ?: Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(target.packageName, target.activityName)
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(launch) }
}

private fun knownInstalledMailApps(pm: PackageManager): List<MailAppTarget> {
    val out = ArrayList<MailAppTarget>()
    for (pkg in knownMailPackages) {
        val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
        val component = launch.component ?: continue
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg).trim().ifBlank { pkg }
        out += MailAppTarget(pkg, component.className, label)
    }
    return out
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

/**
 * Packages that often do not register as the system email app, so a query for
 * the default email category returns only Gmail. Declared in the manifest
 * queries block so Android 11+ lets us see them.
 */
private val knownMailPackages = listOf(
    "com.google.android.gm",
    "com.microsoft.office.outlook",
    "com.samsung.android.email.provider",
    "com.yahoo.mobile.client.android.mail",
    "com.fsck.k9",
    "ch.protonmail.android",
    "com.readdle.spark",
    "me.bluemail.mail",
    "org.kman.AquaMail",
    "com.easilydo.mail",
    "com.android.email",
)
