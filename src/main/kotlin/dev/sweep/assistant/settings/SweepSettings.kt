package dev.sweep.assistant.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "dev.sweep.jetbrains.settings.SweepSettings",
    storages = [Storage("SweepSettings.xml")],
)
class SweepSettings : PersistentStateComponent<SweepSettings> {
    companion object {
        private const val DEFAULT_NEXT_EDIT_PREDICTION_ON = true
        private const val DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW = true

        // -1L means "unset" so project-level values can migrate in
        private const val DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS = 300L

        // Default to false - do not automatically disable conflicting autocomplete plugins
        private const val DEFAULT_DISABLE_CONFLICTING_PLUGINS = true

        fun getInstance(): SweepSettings = ApplicationManager.getApplication().getService(SweepSettings::class.java)
    }

    // Do not notify settings changed on each save, fire it in config instead
    fun interface SettingsChangedNotifier {
        fun settingsChanged()

        companion object {
            @JvmField
            val TOPIC = Topic.create("Sweep settings changed", SettingsChangedNotifier::class.java)
        }
    }

    var nextEditPredictionFlagOn: Boolean = DEFAULT_NEXT_EDIT_PREDICTION_ON
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var acceptWordOnRightArrow: Boolean = DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    /**
     * Autocomplete debounce delay in milliseconds.
     * This is stored at the application level and applies to all projects.
     */
    var autocompleteDebounceMs: Long = DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS
        set(value) {
            val clamped = value.coerceIn(10L, 1000L)
            field = clamped
            // We intentionally do not fire notifySettingsChanged here to avoid
            // excessive message bus chatter while the user drags the slider.
        }

    /**
     * Automatically disable conflicting autocomplete plugins.
     * This is stored at the application level and applies to all projects.
     */
    var disableConflictingPlugins: Boolean = DEFAULT_DISABLE_CONFLICTING_PLUGINS
        set(value) {
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var showAutocompleteBadge: Boolean = false

    var hideAutocompleteExclusionBanner: Boolean = false

    var autocompleteLocalMode: Boolean = false

    var autocompleteLocalPort: Int = 8081

    /**
     * Newline-delimited exclusion patterns (glob-ish substrings matched against file paths)
     * for files that should never get autocomplete suggestions.
     */
    var autocompleteExclusionPatterns: MutableList<String> = mutableListOf()

    // Bring-your-own OpenAI-compatible /v1/completions endpoint for autocomplete.
    // When autocompleteOpenAIUrl is non-empty, the autocomplete path bypasses the
    // local server and talks to this endpoint directly using the
    // sweep-next-edit model prompt format. See OpenAICompletionsAdapter.
    var autocompleteOpenAIUrl: String = ""
    var autocompleteOpenAIModel: String = "sweep-next-edit-1.5b"
    var autocompleteOpenAIApiKey: String = ""
    var autocompleteOpenAIMaxTokens: Int = 512

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager
                .getApplication()
                ?.messageBus
                ?.syncPublisher(SettingsChangedNotifier.TOPIC)
                ?.settingsChanged()
        }
    }

    fun runNowAndOnSettingsChange(
        project: Project,
        parentDisposable: Disposable,
        callback: SweepSettings.() -> Unit,
    ) {
        this.callback()
        project.messageBus.connect(parentDisposable).subscribe(
            SettingsChangedNotifier.TOPIC,
            SettingsChangedNotifier {
                getInstance().callback()
            },
        )
    }

    override fun getState(): SweepSettings = this

    override fun loadState(state: SweepSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
}
