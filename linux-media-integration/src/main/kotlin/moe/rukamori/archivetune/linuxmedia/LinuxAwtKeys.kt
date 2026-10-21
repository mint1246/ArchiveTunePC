package moe.rukamori.archivetune.linuxmedia

import java.awt.event.KeyEvent

/** AWT differs from the persisted Windows key IDs for navigation, punctuation and Super. */
internal fun awtToPersistedKey(code: Int): Int? = when (code) {
    KeyEvent.VK_UNDEFINED -> null
    KeyEvent.VK_ENTER -> LinuxVirtualKeys.ENTER
    KeyEvent.VK_META, KeyEvent.VK_WINDOWS -> LinuxVirtualKeys.WINDOWS
    KeyEvent.VK_CONTEXT_MENU -> LinuxVirtualKeys.MENU
    KeyEvent.VK_ALT_GRAPH -> LinuxVirtualKeys.ALT
    KeyEvent.VK_INSERT -> LinuxVirtualKeys.INSERT
    KeyEvent.VK_DELETE -> LinuxVirtualKeys.DELETE
    KeyEvent.VK_PRINTSCREEN -> 0x2C
    KeyEvent.VK_HELP -> 0x2F
    KeyEvent.VK_SEMICOLON -> 0xBA
    KeyEvent.VK_EQUALS -> 0xBB
    KeyEvent.VK_COMMA -> 0xBC
    KeyEvent.VK_MINUS -> 0xBD
    KeyEvent.VK_PERIOD -> 0xBE
    KeyEvent.VK_SLASH -> 0xBF
    KeyEvent.VK_BACK_QUOTE -> 0xC0
    KeyEvent.VK_OPEN_BRACKET -> 0xDB
    KeyEvent.VK_BACK_SLASH -> 0xDC
    KeyEvent.VK_CLOSE_BRACKET -> 0xDD
    KeyEvent.VK_QUOTE -> 0xDE
    in KeyEvent.VK_F13..KeyEvent.VK_F24 -> 0x7C + code - KeyEvent.VK_F13
    else -> code.takeIf { it in 1..0xFF }
}
