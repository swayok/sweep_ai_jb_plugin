package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.columns
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import dev.sweep.assistant.utils.SweepConstants
import javax.swing.JComponent
import javax.swing.JPasswordField

class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()
    private var panel: com.intellij.openapi.ui.DialogPanel? = null
    private var statusLabel: JBLabel? = null

    override fun getDisplayName(): String = SweepConstants.PLUGIN_NAME

    override fun createComponent(): JComponent {
        val exclusionPatternsArea =
            JBTextArea(settings.autocompleteExclusionPatterns.joinToString("\n")).apply {
                rows = 4
            }

        val newPanel =
            panel {
                group("Code Completion") {
                    row {
                        checkBox("Enable code completion")
                            .bindSelected(settings::nextEditPredictionFlagOn)
                    }
                    row {
                        checkBox("Accept word on right arrow")
                            .bindSelected(settings::acceptWordOnRightArrow)
                    }
                    row {
                        checkBox("Show badge in suggestion popup")
                            .bindSelected(settings::showAutocompleteBadge)
                    }
                    row {
                        checkBox("Automatically disable conflicting autocomplete plugins")
                            .bindSelected(settings::disableConflictingPlugins)
                    }
                    row("Debounce (ms):") {
                        intTextField(10..1000)
                            .bindIntText({ settings.autocompleteDebounceMs.toInt() }, { settings.autocompleteDebounceMs = it.toLong() })
                            .columns(6)
                    }
                    row("Exclude files (one pattern per line):") {}
                    row {
                        cell(exclusionPatternsArea).align(AlignX.FILL)
                    }
                }
                group("Local AI") {
                    row {
                        checkBox("Enable local autocomplete server")
                            .bindSelected(settings::autocompleteLocalMode)
                    }
                    row("Local server port:") {
                        intTextField(1..65535)
                            .bindIntText(settings::autocompleteLocalPort)
                            .columns(6)
                    }
                    row {
                        button("Start Server") {
                            LocalAutocompleteServerManager.getInstance().startServerInTerminal(project)
                        }
                        statusLabel = label("").component as? JBLabel
                        refreshStatusLabel()
                    }
                    row {
                        comment(
                            "Custom OpenAI-compatible endpoint (overrides the local server above). " +
                                "Talks /v1/completions to e.g. LM Studio, llama.cpp, vLLM serving sweep-next-edit-1.5B.",
                        )
                    }
                    row("Endpoint URL:") {
                        textField()
                            .bindText(settings::autocompleteOpenAIUrl)
                            .align(AlignX.FILL)
                    }
                    row("Model:") {
                        textField()
                            .bindText(settings::autocompleteOpenAIModel)
                            .align(AlignX.FILL)
                    }
                    row("API key:") {
                        cell(JPasswordField(settings.autocompleteOpenAIApiKey, 24))
                            .align(AlignX.FILL)
                            .applyToComponent {
                                document.addDocumentListener(
                                    object : javax.swing.event.DocumentListener {
                                        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = sync()

                                        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = sync()

                                        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = sync()

                                        fun sync() {
                                            settings.autocompleteOpenAIApiKey = String(password)
                                        }
                                    },
                                )
                            }
                    }
                    row("Max tokens:") {
                        intTextField(16..8192)
                            .bindIntText(settings::autocompleteOpenAIMaxTokens)
                            .columns(6)
                    }
                }
            }

        exclusionPatternsArea.document.addDocumentListener(
            object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = sync()

                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = sync()

                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = sync()

                fun sync() {
                    settings.autocompleteExclusionPatterns =
                        exclusionPatternsArea.text
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toMutableList()
                }
            },
        )

        panel = newPanel
        newPanel.reset()
        return newPanel
    }

    private fun refreshStatusLabel() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val healthy = LocalAutocompleteServerManager.getInstance().isServerHealthy()
            ApplicationManager.getApplication().invokeLater {
                statusLabel?.text = if (healthy) "Server is running" else "Server is not running"
            }
        }
    }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }
}
