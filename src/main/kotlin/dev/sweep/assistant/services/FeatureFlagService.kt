package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Stub feature-flag service. The plugin no longer polls a backend for feature
 * flags (there is no backend in local-only mode) — every lookup simply
 * returns the caller-supplied default so call sites don't need to change.
 */
@Service(Service.Level.PROJECT)
class FeatureFlagService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): FeatureFlagService = project.getService(FeatureFlagService::class.java)
    }

    fun isFeatureEnabled(flagKey: String): Boolean = false

    fun getFeatureFlag(flagKey: String): String? = null

    fun getNumericFeatureFlag(
        flagKey: String,
        defaultValue: Int,
    ): Int = defaultValue

    fun getStringFeatureFlag(
        flagKey: String,
        defaultValue: String,
    ): String = defaultValue

    fun getAllFeatureFlags(): Map<String, String> = emptyMap()

    fun isInitialized(): Boolean = true

    fun refreshFeatureFlags() {
        // No-op: no backend to refresh from in local-only mode.
    }

    override fun dispose() {}
}
