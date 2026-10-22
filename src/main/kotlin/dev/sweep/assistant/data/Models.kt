package dev.sweep.assistant.data

import com.intellij.openapi.application.PermanentInstallationID
import dev.sweep.assistant.utils.getDebugInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
abstract class BaseRequest {
    @SerialName("debug_info")
    val debugInfo: String = getDebugInfo()

    @SerialName("device_id")
    val deviceId: String = PermanentInstallationID.get()
}
