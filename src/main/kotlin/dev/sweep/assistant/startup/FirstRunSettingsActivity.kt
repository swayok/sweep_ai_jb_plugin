package dev.sweep.assistant.startup

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import dev.sweep.assistant.utils.SweepConstants.SWEEP_ACTION_IDS_TO_DEFAULT_SHORTCUTS
import javax.swing.KeyStroke

/**
 * On first run, binds default keyboard shortcuts for Sweep actions that don't
 * already have one, and removes conflicting third-party bindings for those
 * shortcuts (unless the user has already remapped them manually).
 */
class FirstRunSettingsActivity :
    ProjectActivity,
    Disposable {
    /**
     * Get the OS-appropriate keybinding from a list of keybindings
     * @param keyBindings List of keybindings (e.g., ["meta J", "ctrl J"])
     * @return The appropriate keybinding for the current OS
     */
    private fun getOSAppropriateKeyBinding(keyBindings: List<String>): String =
        if (SystemInfo.isMac) {
            keyBindings.firstOrNull { it.contains("meta") } ?: keyBindings.first()
        } else {
            keyBindings.firstOrNull { it.contains("ctrl") } ?: keyBindings.first()
        }

    override suspend fun execute(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            val keyMap = KeymapManager.getInstance().activeKeymap

            for ((actionId, keyBindings) in SWEEP_ACTION_IDS_TO_DEFAULT_SHORTCUTS) {
                var needsRebinding = false
                val existingShortcuts = keyMap.getShortcuts(actionId)
                // Requires rebinding if no shortcuts exist
                if (existingShortcuts.isEmpty()) {
                    needsRebinding = true
                }

                // Check if any other non-sweep actions share these shortcuts
                for (existingShortcut in existingShortcuts) {
                    val conflictingActions = keyMap.getActionIds(existingShortcut).filter { !it.startsWith("dev.sweep.assistant") }
                    if (conflictingActions.isNotEmpty()) {
                        needsRebinding = true
                        break
                    }
                }

                if (!needsRebinding) {
                    continue
                }

                var containsNonDefaultBinding = false

                // Check if the user has mapped this shortcut to a non default binding
                for (existingShortcut in existingShortcuts) {
                    if (existingShortcut !in keyBindings.map { KeyboardShortcut(KeyStroke.getKeyStroke(it), null) }) {
                        containsNonDefaultBinding = true
                        break
                    }
                }
                // If the user has manually remapped, do not overwrite their shortcuts
                if (containsNonDefaultBinding) {
                    continue
                }

                // Remove all conflicting shortcuts and track overridden key bindings
                val osKeyBinding = getOSAppropriateKeyBinding(keyBindings)
                val keyStroke = KeyStroke.getKeyStroke(osKeyBinding)

                if (keyStroke != null) {
                    val conflictingActionIds = keyMap.getActionIds(keyStroke)
                    for (conflictingActionId in conflictingActionIds) {
                        if (!conflictingActionId.startsWith("dev.sweep.assistant")) {
                            val shortcuts = keyMap.getShortcuts(conflictingActionId)
                            for (shortcut in shortcuts) {
                                if (shortcut is KeyboardShortcut && shortcut.firstKeyStroke == keyStroke) {
                                    keyMap.removeShortcut(conflictingActionId, shortcut)
                                }
                            }
                        }
                    }
                }

                // Add the OS-appropriate shortcut
                if (keyStroke != null) {
                    keyMap.addShortcut(actionId, KeyboardShortcut(keyStroke, null))
                }
            }
        }
    }

    override fun dispose() {
        // Cleanup will be handled automatically when the message bus connection is disposed
    }
}
