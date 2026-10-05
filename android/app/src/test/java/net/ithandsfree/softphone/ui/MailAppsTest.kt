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
}
