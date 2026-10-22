package dev.sweep.assistant.tracking

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

enum class EventType {
    INSTALL_SWEEP,
    UNINSTALL_SWEEP,
    AUTOCOMPLETE_SNOOZED,
    AUTOCOMPLETE_DISABLED,
    AUTOCOMPLETE_KEYBINDING_CHANGED,
}

/**
 * Stub telemetry service. There is no backend to report usage events to in
 * local-only mode, so every call is a no-op — kept so call sites don't need
 * to change.
 */
@Service(Service.Level.APP)
class TelemetryService {
    fun sendUsageEvent(
        eventType: EventType,
        userProperties: Map<String, String> = emptyMap(),
        eventProperties: Map<String, String> = emptyMap(),
    ) {
        // No-op: no backend to report to in local-only mode.
    }

    fun reportUserStoppingChatEvent(project: Project) {
        // No-op: chat was removed along with the backend it reported to.
    }

    companion object {
        fun getInstance(): TelemetryService = ApplicationManager.getApplication().getService(TelemetryService::class.java)
    }
}
