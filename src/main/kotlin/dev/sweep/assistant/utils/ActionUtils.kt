package dev.sweep.assistant.utils

import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.SystemInfo
import javax.swing.KeyStroke

fun getKeyStrokesForAction(actionId: String): List<KeyStroke> {
    val keymap = KeymapManager.getInstance().activeKeymap
    return keymap
        .getShortcuts(actionId)
        .asSequence()
        .filterIsInstance<com.intellij.openapi.actionSystem.KeyboardShortcut>()
        .flatMap { sequenceOf(it.firstKeyStroke, it.secondKeyStroke) }
        .filterNotNull()
        .toList()
}

fun parseKeyStrokesToPrint(k: KeyStroke?): String? {
    if (k == null) return null
    return k
        .toString()
        .replace("pressed ", "")
        .replace("meta", "⌘")
        .replace("control", "Ctrl")
        .replace("ctrl", "Ctrl")
        .replace("alt", if (SystemInfo.isMac) "⌥" else "Alt")
        .replace("shift", if (SystemInfo.isMac) "⇧" else "Shift")
        .replace("BACK_SPACE", "⌫")
        .replace("ENTER", "⏎")
        .replace(" ", if (SystemInfo.isMac) "" else "+")
}
