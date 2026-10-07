package net.ithandsfree.softphone.win

import kotlin.test.Test
import kotlin.test.assertEquals

class MailAppsTest {
    @Test
    fun quotedOutlookCommandKeepsThePathTogether() {
        assertEquals(
            listOf("C:\\Program Files\\Microsoft Office\\Root\\Office16\\OUTLOOK.EXE", "/recycle"),
            splitCommandLine("\"C:\\Program Files\\Microsoft Office\\Root\\Office16\\OUTLOOK.EXE\" /recycle"),
        )
    }

    @Test
    fun chooserListsEachInboxAndSkipsBrowsersAndHotmail() {
        val apps = normalizeMailApps(
            listOf(
                MailCandidate("Outlook", listOf("C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps\\olk.exe")),
                MailCandidate(
                    "Microsoft Outlook",
                    listOf("C:\\Program Files\\Microsoft Office\\Root\\Office16\\OUTLOOK.EXE", "/c", "IPM.Note"),
                ),
                MailCandidate(
                    "Microsoft Outlook",
                    listOf("C:\\Program Files\\Microsoft Office\\Root\\Office16\\OUTLOOK.EXE", "/recycle"),
                ),
                MailCandidate("Mozilla Thunderbird", listOf("C:\\Program Files\\Mozilla Thunderbird\\thunderbird.exe", "-compose")),
                MailCandidate("Windows Mail", listOf("explorer.exe", "shell:AppsFolder\\microsoft.windowscommunicationsapps")),
                MailCandidate("Windows Live Hotmail", listOf("C:\\Windows\\system32\\rundll32.exe", "hmmapi.dll,OpenInboxHandler")),
                MailCandidate("Google Chrome", listOf("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", "https://mail.google.com")),
            ),
        )
        assertEquals(
            listOf("Mozilla Thunderbird", "Outlook (classic)", "Outlook (new)", "Windows Mail"),
            apps.map { it.label },
        )
        assertEquals(
            listOf("C:\\Program Files\\Microsoft Office\\Root\\Office16\\OUTLOOK.EXE", "/recycle"),
            apps.first { it.label == "Outlook (classic)" }.command,
        )
        assertEquals(
            listOf("C:\\Program Files\\Mozilla Thunderbird\\thunderbird.exe", "-mail"),
            apps.first { it.label == "Mozilla Thunderbird" }.command,
        )
    }

    @Test
    fun storeOutlookIsNamedApartFromClassic() {
        val apps = normalizeMailApps(
            listOf(
                MailCandidate(
                    "Outlook",
                    listOf(
                        "explorer.exe",
                        "shell:AppsFolder\\Microsoft.OutlookForWindows_8wekyb3d8bbwe!Microsoft.OutlookforWindows",
                    ),
                ),
                MailCandidate("Microsoft Outlook", listOf("C:\\Office\\OUTLOOK.EXE", "/recycle")),
            ),
        )
        assertEquals(listOf("Outlook (classic)", "Outlook (new)"), apps.map { it.label })
    }

    @Test
    fun aSingleOutlookKeepsThePlainName() {
        val apps = normalizeMailApps(
            listOf(MailCandidate("Microsoft Outlook", listOf("C:\\Office\\OUTLOOK.EXE"))),
        )
        assertEquals(listOf("Outlook"), apps.map { it.label })
    }
}
