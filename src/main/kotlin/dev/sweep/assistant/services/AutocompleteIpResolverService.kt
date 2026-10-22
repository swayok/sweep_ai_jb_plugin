package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.utils.encodeString
import dev.sweep.assistant.utils.defaultJson
import dev.sweep.assistant.utils.raiseForStatus
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.future.await
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Executes next-edit-autocomplete requests against the local bundled
 * `uvx sweep-autocomplete` server. There is no cloud backend in local-only mode.
 */
@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService = project.getService(AutocompleteIpResolverService::class.java)

        private const val READ_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // HTTP client with connection pooling and keep-alive
    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    /**
     * Gets the shared HttpClient instance for connection pooling.
     * This allows other services to use the same connection pool.
     */
    fun getSharedHttpClient(): HttpClient = httpClient

    /**
     * Executes a next edit autocomplete request against the local server.
     */
    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? =
        try {
            LocalAutocompleteServerManager.getInstance().ensureServerRunning()

            val postData = encodeString(request, NextEditAutocompleteRequest.serializer())
            val postDataBytes = postData.toByteArray(Charsets.UTF_8)

            val httpRequest =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${getBaseUrl()}/backend/next_edit_autocomplete"))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(postDataBytes))
                    .build()

            val response =
                httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .await()
                    .raiseForStatus()

            var result: NextEditAutocompleteResponse? = null

            // Read line-by-line to handle server crashes mid-stream gracefully
            try {
                response.body().bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (l.isBlank()) continue
                        try {
                            val jsonElement = defaultJson.parseToJsonElement(l)
                            if (jsonElement is JsonObject && jsonElement.containsKey("status")) {
                                val status = jsonElement["status"]?.jsonPrimitive?.contentOrNull
                                if (status == "error") {
                                    val errorMsg = jsonElement["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                    logger.warn("Local autocomplete server error: $errorMsg")
                                    continue
                                }
                            }
                            result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), l)
                        } catch (e: Exception) {
                            logger.warn("Error parsing local server response: ${e.message}")
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // Server closed the stream (crash, broken pipe, etc.)
                // Process whatever we got before the closure
                logger.info("Local server stream closed: ${e.message}")
            }

            if (result != null) {
                LocalAutocompleteServerManager.getInstance().reportSuccess()
            } else {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }

            result
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            LocalAutocompleteServerManager.getInstance().reportFailure()
            throw e
        }

    /**
     * Gets the base URL of the local autocomplete server.
     */
    fun getBaseUrl(): String = LocalAutocompleteServerManager.getInstance().getServerUrl()

    override fun dispose() {
        scope.cancel()
    }
}
