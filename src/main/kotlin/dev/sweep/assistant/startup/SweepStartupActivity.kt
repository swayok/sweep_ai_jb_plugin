package dev.sweep.assistant.startup

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.autocomplete.vim.VimMotionGhostTextService
import dev.sweep.assistant.services.IdeaVimIntegrationService
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.SweepConstants
import dev.sweep.assistant.utils.disableFullLineCompletion
import dev.sweep.assistant.utils.disableFullLineCompletionAndNotify
import dev.sweep.assistant.utils.showNotification
import java.awt.event.KeyEvent
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.KeyStroke

class SweepStartupActivity :
    ProjectActivity,
    DumbAware {
    override suspend fun execute(project: Project) {
        // Handle Full Line completion conflicts with similar logic to plugin conflicts
        // Delay by 5 seconds to ensure IDE subsystems (like EditorColorsManager) are fully initialized
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                if (!project.isDisposed) {
                    // This must run on the EDT because it may trigger UI/settings logic
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            handleFullLineCompletionConflicts(project)
                        }
                    }
                }
            },
            5,
            TimeUnit.SECONDS,
        )

        // Install VimMotionGhostTextHandler to handle VIM motion with ghost text
        VimMotionGhostTextService.getInstance()

        // Initialize the autocomplete request tracker
        RecentEditsTracker.getInstance(project)
        IdeaVimIntegrationService.getInstance(project).configureIdeaVimIntegration()

        // Auto-start local uvx autocomplete server if enabled and not already running.
        // Suppress when a custom OpenAI-compatible endpoint is configured — that path
        // owns autocomplete and the uvx server would just be dead weight.
        if (SweepSettings.getInstance().autocompleteLocalMode &&
            SweepSettings.getInstance().autocompleteOpenAIUrl.isEmpty()
        ) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val manager = LocalAutocompleteServerManager.getInstance()
                if (!manager.isServerHealthy()) {
                    manager.startServerInTerminal(project)
                }
            }
        }

        // Untrack plugin state files from VCS (best-effort; uses the git CLI directly
        // so this doesn't need a JGit dependency just for housekeeping)
        untrackFromGit(project, ".idea/GhostTextManager_v2.xml")
        untrackFromGit(project, ".idea/UnifiedUserActionsTrackerManager.xml")

        // Suppress KtLint plugin errors for ktlint
        // Skip when plugins with logger wrapper incompatibilities are installed
        val loggerIncompatiblePlugins =
            listOf(
                PluginId.getId("io.jmix.studio"),
            )

        val hasLoggerIncompatiblePlugin =
            loggerIncompatiblePlugins.any { pluginId ->
                PluginManagerCore.isPluginInstalled(pluginId) &&
                    PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
            }

        if (!hasLoggerIncompatiblePlugin) {
            try {
                val ktlintLogger = Logger.getInstance("com.nbadal.ktlint.KtlintAnnotator")
                ktlintLogger.setLevel(LogLevel.OFF)
            } catch (e: Throwable) {
                // Ignore - some plugin logger wrappers don't support setLevel
                Logger
                    .getInstance(SweepStartupActivity::class.java)
                    .debug("Could not set log level for ktlint logger due to plugin compatibility issue", e)
            }
        }

        // Ensure accept/reject actions are bound
        ApplicationManager.getApplication().invokeLater {
            ensureEditAutocompleteActionsAreBound()
        }
    }

    /**
     * Removes a file from git tracking (git rm --cached) without failing if git
     * isn't available or the file isn't tracked. Runs on a background thread.
     */
    private fun untrackFromGit(
        project: Project,
        relativePath: String,
    ) {
        val basePath = project.basePath ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (!File(basePath, relativePath).exists()) return@executeOnPooledThread
                ProcessBuilder("git", "rm", "--cached", "--ignore-unmatch", relativePath)
                    .directory(File(basePath))
                    .start()
                    .waitFor()
            } catch (e: Exception) {
                Logger.getInstance(SweepStartupActivity::class.java).debug("Failed to untrack $relativePath", e)
            }
        }
    }

    private fun handleFullLineCompletionConflicts(project: Project) {
        if (SweepSettings.getInstance().disableConflictingPlugins) {
            disableFullLineCompletion(project)
        } else {
            // Even when auto-disable is off, check for conflicts and notify user
            ApplicationManager.getApplication().invokeLater {
                checkAndNotifyConflictingPlugins(project, autoDisable = false)
            }
        }
    }

    private fun checkAndNotifyConflictingPlugins(
        project: Project,
        autoDisable: Boolean = false,
    ) {
        val metaData = SweepMetaData.getInstance()

        // Get only installed AND enabled conflicting plugins
        val conflictingPlugins = getConflictingPlugins()

        if (conflictingPlugins.isNotEmpty()) {
            // Double-check that plugins are actually enabled before proceeding
            val enabledConflictingPlugins =
                conflictingPlugins.filter { pluginId ->
                    PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
                }

            if (enabledConflictingPlugins.isEmpty()) {
                return
            }

            // Check if user has dismissed notifications
            if (metaData.dontShowConflictNotifications) {
                return
            }

            val pluginNames =
                enabledConflictingPlugins
                    .map { pluginId ->
                        SweepConstants.PLUGIN_ID_TO_NAME[pluginId] ?: PluginManagerCore.getPlugin(pluginId)?.name ?: pluginId.idString
                    }.joinToString(separator = ", ")

            if (autoDisable) {
                // Auto-disable mode: disable Full Line completion instead of plugins
                disableFullLineCompletion(project)
            } else {
                // Manual mode: show notification with option to disable
                showNotification(
                    project = project,
                    title = "Conflicting Autocomplete Plugins Detected",
                    body =
                        "Sweep detected the following potentially conflicting plugins: $pluginNames. " +
                            "These plugins may interfere with Sweep's autocomplete functionality. " +
                            "You can manage these plugins in Settings > Plugins.",
                    notificationGroup = "Sweep Plugin Conflicts",
                    notificationType = NotificationType.WARNING,
                    action =
                        object : NotificationAction("Disable autocomplete for these plugins") {
                            override fun actionPerformed(
                                e: AnActionEvent,
                                notification: com.intellij.notification.Notification,
                            ) {
                                notification.expire()
                                metaData.dontShowConflictNotifications = true
                                disableFullLineCompletionAndNotify(project)
                            }
                        },
                    action2 =
                        object : NotificationAction("Don't show again") {
                            override fun actionPerformed(
                                e: AnActionEvent,
                                notification: com.intellij.notification.Notification,
                            ) {
                                notification.expire()
                                metaData.dontShowConflictNotifications = true
                            }
                        },
                )
            }
        }
    }

    private fun getConflictingPlugins() =
        SweepConstants.PLUGINS_TO_DISABLE
            .filter { PluginManagerCore.isPluginInstalled(it) && PluginManagerCore.getPlugin(it)?.isEnabled == true }

    private fun ensureEditAutocompleteActionsAreBound() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val acceptActionId = AcceptEditCompletionAction.ACTION_ID
        val rejectActionId = RejectEditCompletionAction.ACTION_ID

        if (keymap.getShortcuts(acceptActionId).isEmpty()) {
            keymap.addShortcut(
                acceptActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), null),
            )
        }

        if (keymap.getShortcuts(rejectActionId).isEmpty()) {
            keymap.addShortcut(
                rejectActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), null),
            )
        }
    }
}
