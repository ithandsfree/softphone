package net.ithandsfree.softphone.win

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.swing.SwingUtilities

/**
 * One desktop window owns the setup link. A second launch, including
 * ihfphone:// from the browser, hands the link to the window already open.
 * GPL-2.0.
 */
object EnrolRelay {
    private const val PORT = 47321

    fun forward(link: String?): Boolean {
        allowForeground()
        return try {
            Socket("127.0.0.1", PORT).use { sock ->
                sock.getOutputStream().write(((link ?: "focus") + "\n").toByteArray())
                sock.shutdownOutput()
            }
            Thread.sleep(500)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** The shortcut is the foreground process. Windows only lets that process hand the screen to the one already running. */
    private fun allowForeground() {
        if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) return
        runCatching {
            val user32 = Native.load("user32", User32Focus::class.java) as User32Focus
            user32.AllowSetForegroundWindow(-1)
        }
    }

    private interface User32Focus : StdCallLibrary {
        fun AllowSetForegroundWindow(dwProcessId: Int): Boolean
    }

    fun listen(onMessage: (String) -> Unit) {
        val thread = Thread {
            runCatching {
                val server = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                server.use {
                    while (true) {
                        val line = it.accept().use { sock ->
                            sock.getInputStream().bufferedReader().readLine()
                        } ?: continue
                        SwingUtilities.invokeLater { onMessage(line) }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.name = "ihf-enrol-relay"
        thread.start()
    }
}
