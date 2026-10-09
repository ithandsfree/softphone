package net.ithandsfree.softphone.win

import java.awt.event.KeyEvent
import java.io.File
import java.util.Properties

/**
 * Remappable keyboard shortcuts (`desktop-shortcuts.png`). The sheet and Settings › Keyboard shortcuts both read
 * this table; [PhoneFrame.dispatchShortcut] matches key presses against it. GPL-2.0.
 *
 * Saved in `%LOCALAPPDATA%\IHF Phone\shortcuts.properties` as `ACTION=ctrl+shift+<KeyEvent code>`; only changed
 * keys are written, so a later build can change a default without touching what the person chose.
 */
internal data class Chord(val key: Int, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
    /** True for a key with no Ctrl or Alt: only allowed for in-call keys, and only when not typing. */
    val plain: Boolean get() = !ctrl && !alt

    fun matches(event: KeyEvent): Boolean =
        event.keyCode == key && event.isControlDown == ctrl && event.isAltDown == alt &&
            event.isShiftDown == shift && !event.isMetaDown

    fun label(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        add(keyName(key))
    }.joinToString(" ")

    fun encode(): String = buildList {
        if (ctrl) add("ctrl")
        if (alt) add("alt")
        if (shift) add("shift")
        add(key.toString())
    }.joinToString("+")

    companion object {
        fun parse(raw: String?): Chord? {
            val parts = raw?.trim()?.lowercase()?.split('+')?.filter { it.isNotBlank() } ?: return null
            val key = parts.lastOrNull()?.toIntOrNull() ?: return null
            if (key <= 0) return null
            val mods = parts.dropLast(1)
            if (mods.any { it !in setOf("ctrl", "alt", "shift") }) return null
            return Chord(key, "ctrl" in mods, "alt" in mods, "shift" in mods)
        }

        fun of(event: KeyEvent): Chord = Chord(event.keyCode, event.isControlDown, event.isAltDown, event.isShiftDown)

        fun ctrl(key: Int, shift: Boolean = false) = Chord(key, ctrl = true, shift = shift)
        fun alt(key: Int) = Chord(key, alt = true)
        fun ctrlAlt(key: Int) = Chord(key, ctrl = true, alt = true)

        /** Locale-independent names, so the sheet matches the design on any Windows language. */
        fun keyName(code: Int): String = when (code) {
            in KeyEvent.VK_0..KeyEvent.VK_9, in KeyEvent.VK_A..KeyEvent.VK_Z -> code.toChar().toString()
            in KeyEvent.VK_F1..KeyEvent.VK_F12 -> "F${code - KeyEvent.VK_F1 + 1}"
            in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> "Num ${code - KeyEvent.VK_NUMPAD0}"
            KeyEvent.VK_ENTER -> "Enter"
            KeyEvent.VK_SPACE -> "Space"
            KeyEvent.VK_UP -> "↑"
            KeyEvent.VK_DOWN -> "↓"
            KeyEvent.VK_LEFT -> "←"
            KeyEvent.VK_RIGHT -> "→"
            KeyEvent.VK_HOME -> "Home"
            KeyEvent.VK_END -> "End"
            KeyEvent.VK_PAGE_UP -> "Page Up"
            KeyEvent.VK_PAGE_DOWN -> "Page Down"
            KeyEvent.VK_INSERT -> "Insert"
            KeyEvent.VK_DELETE -> "Del"
            KeyEvent.VK_BACK_SPACE -> "Backspace"
            KeyEvent.VK_COMMA -> ","
            KeyEvent.VK_PERIOD -> "."
            KeyEvent.VK_SLASH -> "/"
            KeyEvent.VK_SEMICOLON -> ";"
            KeyEvent.VK_QUOTE -> "'"
            KeyEvent.VK_OPEN_BRACKET -> "["
            KeyEvent.VK_CLOSE_BRACKET -> "]"
            KeyEvent.VK_BACK_SLASH -> "\\"
            KeyEvent.VK_MINUS -> "-"
            KeyEvent.VK_EQUALS -> "="
            KeyEvent.VK_BACK_QUOTE -> "`"
            else -> KeyEvent.getKeyText(code)
        }

        /** Keys that are only modifiers: a capture waits for the real key after them. */
        fun isModifierOnly(code: Int): Boolean = code in setOf(
            KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT, KeyEvent.VK_META, KeyEvent.VK_WINDOWS,
            KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_UNDEFINED, 0,
        )
    }
}

internal enum class ShortcutGroup(val title: String) {
    ANYWHERE("Anywhere"),
    CALLS("Calls"),
    MESSAGES("Messages"),
    SYSTEM("System-wide (off by default)"),
}

/**
 * One remappable action. [inCall] keys may be a plain letter (they never fire while typing). [global] keys are
 * registered with Windows only when system-wide hotkeys are turned on.
 */
internal enum class Shortcut(
    val group: ShortcutGroup,
    val label: String,
    val default: Chord,
    val inCall: Boolean = false,
    val global: Boolean = false,
) {
    SEARCH(ShortcutGroup.ANYWHERE, "Search or dial", Chord.ctrl(KeyEvent.VK_K)),
    LINE_1(ShortcutGroup.ANYWHERE, "Switch to line 1", Chord.ctrl(KeyEvent.VK_1)),
    LINE_2(ShortcutGroup.ANYWHERE, "Switch to line 2", Chord.ctrl(KeyEvent.VK_2)),
    NEW_MESSAGE(ShortcutGroup.ANYWHERE, "New message", Chord.ctrl(KeyEvent.VK_N)),
    TOGGLE_DND(ShortcutGroup.ANYWHERE, "Toggle Do not disturb (active line)", Chord.ctrl(KeyEvent.VK_D, shift = true)),
    TAB_CALLS(ShortcutGroup.ANYWHERE, "Go to Calls", Chord.alt(KeyEvent.VK_1)),
    TAB_MESSAGES(ShortcutGroup.ANYWHERE, "Go to Messages", Chord.alt(KeyEvent.VK_2)),
    TAB_LINES(ShortcutGroup.ANYWHERE, "Go to Lines", Chord.alt(KeyEvent.VK_3)),
    TAB_SETTINGS(ShortcutGroup.ANYWHERE, "Go to Settings", Chord.alt(KeyEvent.VK_4)),
    CALL_ANSWER(ShortcutGroup.CALLS, "Call / answer", Chord.ctrl(KeyEvent.VK_ENTER)),
    END_DECLINE(ShortcutGroup.CALLS, "End / decline", Chord.ctrl(KeyEvent.VK_D)),
    MUTE(ShortcutGroup.CALLS, "Mute", Chord(KeyEvent.VK_M), inCall = true),
    HOLD(ShortcutGroup.CALLS, "Hold / resume", Chord(KeyEvent.VK_H), inCall = true),
    TRANSFER(ShortcutGroup.CALLS, "Transfer", Chord(KeyEvent.VK_T), inCall = true),
    KEYPAD(ShortcutGroup.CALLS, "Show keypad (then type digits)", Chord(KeyEvent.VK_K), inCall = true),
    NEXT_THREAD(ShortcutGroup.MESSAGES, "Next thread", Chord.ctrl(KeyEvent.VK_DOWN)),
    PREVIOUS_THREAD(ShortcutGroup.MESSAGES, "Previous thread", Chord.ctrl(KeyEvent.VK_UP)),
    GLOBAL_ANSWER(ShortcutGroup.SYSTEM, "Answer from any app", Chord.ctrlAlt(KeyEvent.VK_A), global = true),
    GLOBAL_HANG_UP(ShortcutGroup.SYSTEM, "Hang up from any app", Chord.ctrlAlt(KeyEvent.VK_H), global = true),
    GLOBAL_MUTE(ShortcutGroup.SYSTEM, "Mute from any app", Chord.ctrlAlt(KeyEvent.VK_M), global = true),
}

/** Fixed keys shown on the sheet but not remappable (text-box conventions and the sheet itself). */
internal val FIXED_SHORTCUTS: List<Triple<ShortcutGroup, String, String>> = listOf(
    Triple(ShortcutGroup.ANYWHERE, "This sheet", "?"),
    Triple(ShortcutGroup.MESSAGES, "Send", "Enter"),
    Triple(ShortcutGroup.MESSAGES, "New line", "Shift Enter"),
    Triple(ShortcutGroup.MESSAGES, "Paste image as MMS", "Ctrl V"),
)

internal class Keymap(private val chords: Map<Shortcut, Chord>, val globalEnabled: Boolean) {
    fun chord(action: Shortcut): Chord = chords[action] ?: action.default

    /** The in-window action for this key press, or null. Global actions are matched by Windows, not here. */
    fun actionFor(event: KeyEvent): Shortcut? =
        Shortcut.entries.firstOrNull { !it.global && chord(it).matches(event) }

    /** Who already uses [chord], other than [except]. Window keys and system-wide keys are checked separately. */
    fun owner(chord: Chord, except: Shortcut): Shortcut? =
        Shortcut.entries.firstOrNull { it != except && it.global == except.global && chord(it) == chord }

    /** Why [chord] cannot be given to [action], or null when it can. */
    fun refusal(action: Shortcut, chord: Chord): String? {
        RESERVED[chord]?.let { return "$it is kept for Windows and text boxes" }
        if (chord.plain && !action.inCall) {
            return "Use Ctrl or Alt with a key, so typing never triggers it"
        }
        if (action.global && !(chord.ctrl || chord.alt)) return "System-wide keys need Ctrl or Alt"
        if (action.global && (chord.key !in GLOBAL_KEYS)) return "Use a letter, digit or F-key for a system-wide key"
        if (chord.plain && chord.key == KeyEvent.VK_ENTER) return "Enter alone is kept for calling and sending"
        owner(chord, action)?.let { return "${chord.label()} is already ${it.label}" }
        return null
    }

    fun with(action: Shortcut, chord: Chord): Keymap = Keymap(chords + (action to chord), globalEnabled)

    fun reset(action: Shortcut): Keymap = Keymap(chords - action, globalEnabled)

    fun resetAll(): Keymap = Keymap(emptyMap(), globalEnabled)

    fun withGlobal(enabled: Boolean): Keymap = Keymap(chords, enabled)

    fun isDefault(action: Shortcut): Boolean = chord(action) == action.default

    fun toProperties(): Properties {
        val props = Properties()
        chords.forEach { (action, chord) -> if (chord != action.default) props.setProperty(action.name, chord.encode()) }
        props.setProperty("global.enabled", globalEnabled.toString())
        return props
    }

    companion object {
        /** Windows and text-box keys a person cannot take for an action. */
        private val RESERVED: Map<Chord, String> = mapOf(
            Chord.ctrl(KeyEvent.VK_C) to "Ctrl C",
            Chord.ctrl(KeyEvent.VK_V) to "Ctrl V",
            Chord.ctrl(KeyEvent.VK_X) to "Ctrl X",
            Chord.ctrl(KeyEvent.VK_Z) to "Ctrl Z",
            Chord.ctrl(KeyEvent.VK_Y) to "Ctrl Y",
            Chord.ctrl(KeyEvent.VK_A) to "Ctrl A",
            Chord.alt(KeyEvent.VK_F4) to "Alt F4",
            Chord.alt(KeyEvent.VK_TAB) to "Alt Tab",
            Chord.alt(KeyEvent.VK_SPACE) to "Alt Space",
            Chord.ctrlAlt(KeyEvent.VK_DELETE) to "Ctrl Alt Del",
            Chord(KeyEvent.VK_TAB) to "Tab",
            Chord(KeyEvent.VK_TAB, shift = true) to "Shift Tab",
            Chord(KeyEvent.VK_ESCAPE) to "Esc",
            Chord(KeyEvent.VK_SPACE) to "Space",
            Chord(KeyEvent.VK_BACK_SPACE) to "Backspace",
            Chord(KeyEvent.VK_DELETE) to "Del",
            Chord(KeyEvent.VK_UP) to "↑",
            Chord(KeyEvent.VK_DOWN) to "↓",
            Chord(KeyEvent.VK_LEFT) to "←",
            Chord(KeyEvent.VK_RIGHT) to "→",
            Chord(KeyEvent.VK_F10, shift = true) to "Shift F10",
            Chord.ctrl(KeyEvent.VK_C, shift = true) to "Ctrl Shift C",
        ) + (KeyEvent.VK_0..KeyEvent.VK_9).associate { Chord(it) to Chord.keyName(it) } +
            listOf(KeyEvent.VK_NUMBER_SIGN, KeyEvent.VK_ASTERISK, KeyEvent.VK_MULTIPLY).associate { Chord(it) to Chord.keyName(it) }

        /** Keys whose Java code equals the Windows virtual-key code, for RegisterHotKey. */
        val GLOBAL_KEYS: Set<Int> = ((KeyEvent.VK_0..KeyEvent.VK_9) + (KeyEvent.VK_A..KeyEvent.VK_Z) +
            (KeyEvent.VK_F1..KeyEvent.VK_F12)).toSet()

        fun fromProperties(props: Properties): Keymap {
            val chords = Shortcut.entries.mapNotNull { action ->
                Chord.parse(props.getProperty(action.name))?.let { action to it }
            }.toMap()
            val loaded = Keymap(emptyMap(), props.getProperty("global.enabled") == "true")
            // Drop any saved key that would now be refused (a hand-edited file, or a rule added later).
            return chords.entries.fold(loaded) { map, (action, chord) ->
                if (map.refusal(action, chord) == null) map.with(action, chord) else map
            }
        }
    }
}

/** Loads and saves the keymap. Reads are cheap and cached; the file changes only from Settings. */
internal object KeymapStore {
    @Volatile
    private var cached: Keymap? = null

    fun current(): Keymap = cached ?: load().also { cached = it }

    fun save(map: Keymap) {
        cached = map
        val file = file()
        file.parentFile?.mkdirs()
        file.outputStream().use { map.toProperties().store(it, "IHF Phone keyboard shortcuts") }
    }

    private fun load(): Keymap {
        val props = Properties()
        val file = file()
        if (file.isFile) runCatching { file.inputStream().use { props.load(it) } }
        return Keymap.fromProperties(props)
    }

    private fun file(): File {
        val root = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("java.io.tmpdir")
        return File(File(root, "IHF Phone"), "shortcuts.properties")
    }
}
