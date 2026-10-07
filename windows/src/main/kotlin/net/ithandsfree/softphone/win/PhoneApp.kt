package net.ithandsfree.softphone.win

import java.io.File
import javax.swing.SwingUtilities

/** Desktop client. GPL-2.0. Live hosts stay out of this source file. */
fun main(args: Array<String>) {
    val link = args.firstOrNull { looksLikeLaunchLink(it) }
    if (EnrolRelay.forward(link)) return
    val profile = distributionProfile(
        Distribution.from(System.getProperty("ihf.flavor") ?: System.getenv("IHF_FLAVOR")),
    )
    val host = HostConfig.load(profile)
    SwingUtilities.invokeLater {
        val frame = PhoneFrame(profile, host)
        frame.isVisible = true
        if (link != null) frame.acceptExternalLink(link)
        registerSetupProtocol()
        EnrolRelay.listen { message ->
            frame.reveal()
            if (message != "focus") frame.acceptExternalLink(message)
        }
    }
}

private fun looksLikeLaunchLink(raw: String): Boolean {
    val value = raw.trim().trim('"')
    return looksLikeSetupLink(value) || dialTarget(value) != null
}

private fun looksLikeSetupLink(raw: String): Boolean {
    val value = raw.trim().trim('"')
    return value.startsWith("ihfphone://", ignoreCase = true) ||
        value.contains("/enrol/", ignoreCase = true) ||
        value.contains("/enroll/", ignoreCase = true)
}

/** Lets the browser button Open in IHF Phone start this installed app. Does not delay the window. */
private fun registerSetupProtocol() {
    val exe = ProcessHandle.current().info().command().orElse("")
    if (!exe.endsWith("IHF Phone.exe", ignoreCase = true)) return
    val thread = Thread {
        runCatching {
            val safe = exe.replace("'", "''")
            val dollar = '$'
            val script = """
                function Set-IhfProto([string]${dollar}name, [string]${dollar}label, [bool]${dollar}force) {
                  ${dollar}key = "HKCU:\Software\Classes\${dollar}name"
                  ${dollar}cmd = Join-Path ${dollar}key 'shell\open\command'
                  ${dollar}current = ''
                  if (Test-Path ${dollar}cmd) { ${dollar}current = (Get-ItemProperty -Path ${dollar}cmd -Name '(default)' -ErrorAction SilentlyContinue).'(default)' }
                  if (-not ${dollar}force -and ${dollar}current -and (${dollar}current -notlike '*IHF Phone.exe*')) { return }
                  New-Item -Path ${dollar}cmd -Force | Out-Null
                  Set-ItemProperty -Path ${dollar}key -Name '(default)' -Value ${dollar}label
                  New-ItemProperty -Path ${dollar}key -Name 'URL Protocol' -Value '' -PropertyType String -Force | Out-Null
                  Set-ItemProperty -Path ${dollar}cmd -Name '(default)' -Value ('"' + '$safe' + '" "%1"')
                }
                Set-IhfProto 'ihfphone' 'URL:IHF Phone' ${dollar}true
                Set-IhfProto 'sip' 'URL:SIP' ${dollar}false
                Set-IhfProto 'tel' 'URL:Telephone' ${dollar}false
            """.trimIndent()
            ProcessBuilder("powershell.exe", "-NoProfile", "-Command", script).start().waitFor()
        }
    }
    thread.isDaemon = true
    thread.name = "ihf-protocol"
    thread.start()
}
