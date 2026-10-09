package net.ithandsfree.softphone.win

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import javax.swing.SwingUtilities

/**
 * System-wide hotkeys (answer, hang up, mute from any app). Off by default: they take the key from every other app
 * while IHF Phone runs. Uses Win32 `RegisterHotKey` on a thread of its own with a message loop; the hotkeys belong
 * to that thread and are released when it ends. GPL-2.0.
 */
internal object GlobalHotkeys {
    private const val WM_HOTKEY = 0x0312
    private const val WM_QUIT = 0x0012
    private const val MOD_ALT = 0x0001
    private const val MOD_CONTROL = 0x0002
    private const val MOD_SHIFT = 0x0004
    private const val MOD_NOREPEAT = 0x4000

    @Volatile
    private var threadId = 0
    private var thread: Thread? = null

    /**
     * Registers the system-wide keys in [map] when it has them turned on, replacing any earlier set. [onAction] runs
     * on the Swing thread. [onResult] gets the keys Windows refused (another app already owns them).
     */
    @Synchronized
    fun apply(map: Keymap, onAction: (Shortcut) -> Unit, onResult: (List<Shortcut>) -> Unit = {}) {
        stop()
        if (!map.globalEnabled || !isWindows()) return
        val wanted = Shortcut.entries.filter { it.global }.associateWith { map.chord(it) }
        val ready = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            val refused = mutableListOf<Shortcut>()
            val ids = mutableListOf<Int>()
            wanted.entries.forEachIndexed { index, (action, chord) ->
                val id = 0x4948 + index
                val mods = MOD_NOREPEAT or (if (chord.ctrl) MOD_CONTROL else 0) or
                    (if (chord.alt) MOD_ALT else 0) or (if (chord.shift) MOD_SHIFT else 0)
                if (User32.INSTANCE.RegisterHotKey(null, id, mods, chord.key)) ids += id else refused += action
            }
            ready.countDown()
            SwingUtilities.invokeLater { onResult(refused) }
            val msg = WinUser.MSG()
            try {
                while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
                    if (msg.message == WM_HOTKEY) {
                        val action = wanted.keys.elementAtOrNull(msg.wParam.toInt() - 0x4948) ?: continue
                        SwingUtilities.invokeLater { onAction(action) }
                    }
                }
            } finally {
                ids.forEach { User32.INSTANCE.UnregisterHotKey(null, it) }
            }
        }
        worker.isDaemon = true
        worker.name = "ihf-hotkeys"
        worker.start()
        thread = worker
        ready.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Synchronized
    fun stop() {
        val worker = thread ?: return
        val id = threadId
        if (id != 0) {
            User32.INSTANCE.PostThreadMessage(id, WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        }
        worker.join(1000)
        thread = null
        threadId = 0
    }

    private fun isWindows() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
}
