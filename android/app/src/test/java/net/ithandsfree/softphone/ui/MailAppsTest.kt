package net.ithandsfree.softphone.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MailAppsTest {
    @Test
    fun keepsEachMailAppAndSortsByLabel() {
        val found = listOf(
            MailAppTarget("com.google.android.gm", "com.google.android.gm.ConversationListActivityGmail", "Gmail"),
            MailAppTarget("com.microsoft.office.outlook", "com.microsoft.office.outlook.MainActivity", "Outlook"),
            MailAppTarget("com.google.android.gm", "com.google.android.gm.ConversationListActivityGmail", "Gmail"),
            MailAppTarget("net.ithandsfree.softphone", "net.ithandsfree.softphone.MainActivity", "IHF Phone"),
            MailAppTarget("", "Missing", "Blank"),
        )
        val selected = selectMailApps(found, ownPackage = "net.ithandsfree.softphone")
        assertEquals(listOf("Gmail", "Outlook"), selected.map { it.label })
        assertEquals(
            listOf("com.google.android.gm", "com.microsoft.office.outlook"),
            selected.map { it.packageName },
        )
    }

    @Test
    fun gmailAsDefaultDoesNotHideOutlook() {
        val selected = selectMailApps(
            listOf(
                MailAppTarget("com.google.android.gm", "gmail.Main", "Gmail"),
                MailAppTarget("com.microsoft.office.outlook", "outlook.Main", "Outlook"),
                MailAppTarget("com.android.chrome", "chrome.Main", "Chrome"),
            ),
            ownPackage = "net.ithandsfree.softphone",
        )
        assertEquals(listOf("Gmail", "Outlook"), selected.map { it.label })
    }
}
