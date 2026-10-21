package dev.sweep.assistant.autocomplete.edit

import com.github.difflib.DiffUtils
import com.intellij.openapi.diagnostic.Logger
import dev.sweep.assistant.utils.defaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Talks to any OpenAI-compatible /v1/completions endpoint serving the
 * sweep-next-edit model.
 *
 * Prompt format follows the training layout
 * (https://huggingface.co/sweepai/sweep-next-edit-1.5B/blob/main/run_model.py)
 * with the inference-time additions that cursortab.nvim uses for speed and
 * quality:
 *
 *   - Cursor-centered window instead of the whole file (small prompt, fits KV)
 *   - <|cursor|> marker inside current/ section
 *   - :start:end line-range suffix on original/current/updated tags — only
 *     when the model name contains "7B" (sweep-next-edit-v2-7B's training
 *     format requires it; the 1.5B variants omit it per their run_model.py)
 *   - Prefill of the updated/ section so the model only generates the edit
 *   - Insertion-mode prefill switch when the user just inserted above cursor
 *   - Anchor-suffix truncation: trust the original window for any tail content
 *     the model re-emits, so drift past the edit is silently discarded
 *
 * The result is reshaped into a NextEditAutocompleteResponse so the rest of
 * the IDE flow (diff UI, accept/reject keystrokes) is unchanged.
 */
object OpenAICompletionsAdapter {
    private val logger = Logger.getInstance(OpenAICompletionsAdapter::class.java)

    private const val FILE_SEP = "<|file_sep|>"
    private const val CURSOR_MARK = "<|cursor|>"
    private const val EOS_S = "</s>"
    private const val EOS_OT = "<|endoftext|>"

    private const val WINDOW_LINES_BEFORE = 30
    private const val WINDOW_LINES_AFTER = 30

    // Broad-file context, mirroring cursortab's getBroadFileContext. Sent as the
    // very first <|file_sep|> section so the model sees ~300 lines of surrounding
    // code — important for symbol recognition on mid-line completions
    // (e.g. recognising `Struc` should be `Struct` because `Struct` is defined
    // 50 lines up).
    private const val BROAD_CONTEXT_LINES_BEFORE = 150
    private const val BROAD_CONTEXT_LINES_AFTER = 150

    private const val APPROX_CHAR_PER_TOKEN = 4
    private const val MAX_PROMPT_CHARS = 6500 * APPROX_CHAR_PER_TOKEN

    // Hard character ceilings for the window/broad-context sections. The line
    // counts above assume reasonably short lines; a single minified, generated,
    // or long-string-literal line can blow the character budget far past what
    // the line counts imply, overflowing the model's context window (observed
    // as LM Studio's "Context size has been exceeded" 500 error) even though
    // the optional sections below are correctly budget-checked against
    // MAX_PROMPT_CHARS. These caps apply regardless of line length.
    private const val MAX_WINDOW_CHARS = 1000 * APPROX_CHAR_PER_TOKEN
    private const val MAX_BROAD_CONTEXT_CHARS = 2000 * APPROX_CHAR_PER_TOKEN

    // Mirror cursortab's max_diff_history_tokens=1024. Past edits beyond ~1k
    // tokens give diminishing returns and add PP cost.
    private const val MAX_RECENT_EDITS_CHARS = 1024 * APPROX_CHAR_PER_TOKEN

    // Cap retrieval/file chunks specifically. The 300-line broad context above
    // already covers the file we're editing, so this is for definitions / other
    // open files. Roughly cursortab's MaxRecentSnapshots × per-snapshot.
    private const val MAX_RETRIEVAL_CHARS = 2048 * APPROX_CHAR_PER_TOKEN

    private val okHttp: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json".toMediaType()

    @Serializable
    private data class OpenAICompletionRequest(
        val model: String,
        val prompt: String,
        val max_tokens: Int,
        val temperature: Double,
        val stop: List<String>,
        val stream: Boolean = false,
        val n: Int = 1,
        val echo: Boolean = false,
    )

    @Serializable
    private data class OpenAICompletionResponse(
        val choices: List<Choice> = emptyList(),
    ) {
        @Serializable
        data class Choice(val text: String = "")
    }

    suspend fun fetch(
        baseUrl: String,
        model: String,
        apiKey: String,
        maxTokens: Int,
        filePath: String,
        fileContents: String,
        cursorPosition: Int,
        recentEdits: List<EditRecord>,
        recentUserActions: List<UserAction>,
        contextChunks: List<FileChunk>,
        autocompleteId: String? = null,
        onPartial: (suspend (NextEditAutocompleteResponse) -> Unit)? = null,
    ): NextEditAutocompleteResponse? {
        val window = selectWindow(fileContents, cursorPosition) ?: return null
        val changesAboveCursor = hasInsertionAboveCursor(recentUserActions, window)
        val prefill = computePrefill(window.text, window.cursorOffsetInWindow, changesAboveCursor)
        val broadContext = selectBroadContext(fileContents, cursorPosition)
        val prompt =
            buildPrompt(
                model = model,
                filePath = filePath,
                broadContext = broadContext,
                window = window,
                prefill = prefill,
                recentEdits = recentEdits,
                contextChunks = contextChunks,
            )

        // Stable id used across partials + final, so the consumer can correlate.
        val id = autocompleteId ?: UUID.randomUUID().toString()

        // Emit exactly one mid-stream partial so the user sees ghost text appear
        // shortly after prompt-processing finishes (instead of waiting for full
        // generation). Subsequent partials are silently accumulated and one
        // final paint occurs at end-of-stream. Two renders total = minimal flicker.
        var alreadyEmittedFirstPartial = false
        val finalText =
            streamCompletion(baseUrl, model, apiKey, maxTokens, prompt) { accumulated ->
                if (alreadyEmittedFirstPartial || onPartial == null) return@streamCompletion
                val cleaned = postprocess(accumulated)
                if (cleaned != null) {
                    val partial =
                        buildAutocompleteResponse(
                            fileContents = fileContents,
                            window = window,
                            prefill = prefill,
                            modelOutput = cleaned,
                            id = id,
                            cursorPosition = cursorPosition,
                        )
                    if (partial != null) {
                        alreadyEmittedFirstPartial = true
                        onPartial(partial)
                    }
                }
            } ?: return null

        val cleaned = postprocess(finalText) ?: return null
        return buildAutocompleteResponse(
            fileContents = fileContents,
            window = window,
            prefill = prefill,
            modelOutput = cleaned,
            id = id,
            cursorPosition = cursorPosition,
        )
    }

    // ---------------- Window selection ----------------

    /**
     * Truncates [text] to at most [maxLen] characters, keeping a region centered
     * on [pivot] (the cursor's offset within [text]). Spends whatever budget the
     * shorter side doesn't need on the longer side, so a cursor near either edge
     * doesn't waste half the budget past the edge. Returns the truncated text and
     * the pivot's new offset within it.
     */
    private fun truncateAroundPivot(
        text: String,
        pivot: Int,
        maxLen: Int,
    ): Pair<String, Int> {
        if (text.length <= maxLen) return text to pivot
        val before = pivot
        val after = text.length - pivot
        var beforeBudget = maxLen / 2
        var afterBudget = maxLen - beforeBudget
        if (before < beforeBudget) {
            afterBudget += (beforeBudget - before)
            beforeBudget = before
        } else if (after < afterBudget) {
            beforeBudget += (afterBudget - after)
            afterBudget = after
        }
        val start = (pivot - beforeBudget).coerceIn(0, text.length)
        val end = (pivot + afterBudget).coerceIn(start, text.length)
        return text.substring(start, end) to (pivot - start)
    }

    private fun lineNumberAtOffset(
        text: String,
        offset: Int,
    ): Int {
        var line = 0
        for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') line++
        return line
    }

    private data class Window(
        val text: String,
        val startOffset: Int,
        val endOffset: Int,
        val startLine1Indexed: Int,
        val endLine1Indexed: Int,
        val cursorOffsetInWindow: Int,
        val cursorLineInFile0Indexed: Int,
    )

    private fun selectWindow(
        fileContents: String,
        cursorPosition: Int,
    ): Window? {
        if (cursorPosition < 0 || cursorPosition > fileContents.length) return null

        var cursorLine = 0
        for (i in 0 until cursorPosition) if (fileContents[i] == '\n') cursorLine++

        val totalLines =
            if (fileContents.isEmpty()) 0 else fileContents.count { it == '\n' } + 1

        val windowStartLine = (cursorLine - WINDOW_LINES_BEFORE).coerceAtLeast(0)
        val windowEndLine = (cursorLine + WINDOW_LINES_AFTER + 1).coerceAtMost(totalLines)

        // Offset of first char of windowStartLine
        var startOffset = 0
        run {
            var line = 0
            var i = 0
            while (line < windowStartLine && i < fileContents.length) {
                if (fileContents[i] == '\n') line++
                i++
            }
            startOffset = i
        }

        // Offset of first char of windowEndLine (= one past the last newline of the window)
        var endOffset = startOffset
        run {
            var line = windowStartLine
            var i = startOffset
            while (line < windowEndLine && i < fileContents.length) {
                if (fileContents[i] == '\n') line++
                i++
            }
            endOffset = i
        }

        val rawText = fileContents.substring(startOffset, endOffset)
        val rawCursorOffsetInWindow =
            (cursorPosition - startOffset).coerceIn(0, rawText.length)

        // Line counts alone assume short lines; cap by characters too so a
        // handful of very long lines (minified/generated code, long literals)
        // can't balloon the prompt past the model's context window.
        val (text, cursorOffsetInWindow) =
            truncateAroundPivot(rawText, rawCursorOffsetInWindow, MAX_WINDOW_CHARS)
        val trimmedFromStart = rawCursorOffsetInWindow - cursorOffsetInWindow
        val effectiveStartOffset = startOffset + trimmedFromStart
        val effectiveEndOffset = effectiveStartOffset + text.length

        return Window(
            text = text,
            startOffset = effectiveStartOffset,
            endOffset = effectiveEndOffset,
            startLine1Indexed = lineNumberAtOffset(fileContents, effectiveStartOffset) + 1,
            endLine1Indexed = lineNumberAtOffset(fileContents, effectiveEndOffset) + 1,
            cursorOffsetInWindow = cursorOffsetInWindow,
            cursorLineInFile0Indexed = cursorLine,
        )
    }

    /**
     * Returns ~300 lines of file content around the cursor for the broad-context
     * section. Returns an empty string for tiny files (no point duplicating what
     * the window already shows).
     */
    private fun selectBroadContext(
        fileContents: String,
        cursorPosition: Int,
    ): String {
        if (fileContents.isEmpty()) return ""
        if (cursorPosition < 0 || cursorPosition > fileContents.length) return ""

        var cursorLine = 0
        for (i in 0 until cursorPosition) if (fileContents[i] == '\n') cursorLine++

        val totalLines = fileContents.count { it == '\n' } + 1
        val startLine = (cursorLine - BROAD_CONTEXT_LINES_BEFORE).coerceAtLeast(0)
        val endLine = (cursorLine + BROAD_CONTEXT_LINES_AFTER + 1).coerceAtMost(totalLines)

        // Skip if the broad slice doesn't add anything beyond the smaller window.
        if (endLine - startLine <= (WINDOW_LINES_BEFORE + WINDOW_LINES_AFTER + 1)) return ""

        var startOffset = 0
        run {
            var line = 0
            var i = 0
            while (line < startLine && i < fileContents.length) {
                if (fileContents[i] == '\n') line++
                i++
            }
            startOffset = i
        }
        var endOffset = startOffset
        run {
            var line = startLine
            var i = startOffset
            while (line < endLine && i < fileContents.length) {
                if (fileContents[i] == '\n') line++
                i++
            }
            endOffset = i
        }
        val rawText = fileContents.substring(startOffset, endOffset)
        val rawCursorOffset = (cursorPosition - startOffset).coerceIn(0, rawText.length)
        // Same rationale as the window cap above: line counts assume short
        // lines, so cap by characters too.
        val (text, _) = truncateAroundPivot(rawText, rawCursorOffset, MAX_BROAD_CONTEXT_CHARS)
        return text.trimEnd('\r', '\n')
    }

    // ---------------- Insertion-mode detection ----------------

    private fun hasInsertionAboveCursor(
        actions: List<UserAction>,
        window: Window,
    ): Boolean {
        val last = actions.lastOrNull() ?: return false
        if (last.action_type != UserActionType.INSERT_CHAR &&
            last.action_type != UserActionType.INSERT_SELECTION
        ) {
            return false
        }
        // line_number is 1-indexed file line after the action completed.
        val actionLine0 = last.line_number - 1
        return actionLine0 < window.cursorLineInFile0Indexed
    }

    // ---------------- Prefill ----------------

    /**
     * Default: prefill = everything up to (but not including) the line the cursor
     * is on. Model generates the cursor line + everything after.
     *
     * Insertion mode: keep only the first line + trailing blank lines, so the
     * model can freely rewrite lines whose positions just shifted from the insert.
     */
    private fun computePrefill(
        windowText: String,
        cursorOffsetInWindow: Int,
        changesAboveCursor: Boolean,
    ): String {
        val cursor = cursorOffsetInWindow.coerceIn(0, windowText.length)
        val prefix = windowText.substring(0, cursor)

        if (changesAboveCursor) {
            val firstNl = prefix.indexOf('\n')
            if (firstNl < 0) return prefix
            val sb = StringBuilder(prefix.substring(0, firstNl + 1))
            var i = firstNl + 1
            while (i < prefix.length && prefix[i] == '\n') {
                sb.append('\n')
                i++
            }
            return sb.toString()
        }

        val lastNl = prefix.lastIndexOf('\n')
        return if (lastNl < 0) "" else prefix.substring(0, lastNl + 1)
    }

    // ---------------- Prompt assembly ----------------

    private fun buildPrompt(
        model: String,
        filePath: String,
        broadContext: String,
        window: Window,
        prefill: String,
        recentEdits: List<EditRecord>,
        contextChunks: List<FileChunk>,
    ): String {
        val cursorPos = window.cursorOffsetInWindow.coerceIn(0, window.text.length)
        val current =
            window.text.substring(0, cursorPos) + CURSOR_MARK + window.text.substring(cursorPos)

        // sweep-next-edit-v2-7B's training format includes `:start:end` line-range
        // suffixes on the original/current/updated headers. The 1.5B (v1 and v2)
        // omits them — see its run_model.py. Gate by model name so we stay in
        // distribution for whichever variant the user is pointing at.
        val rangeSuffix =
            if (model.contains("7b", ignoreCase = true)) {
                ":${window.startLine1Indexed}:${window.endLine1Indexed}"
            } else {
                ""
            }

        // Broad file context first — gives the model a wider view of the file so
        // it can recognise symbols defined outside the smaller cursor window.
        // Same shape cursortab uses: `<|file_sep|>{file_path}\n{300 lines}\n`.
        val broadContextSection =
            if (broadContext.isNotEmpty()) {
                buildString {
                    append(FILE_SEP).append(filePath).append("\n")
                    append(broadContext).append("\n")
                }
            } else {
                ""
            }

        val tail =
            buildString {
                append(broadContextSection)
                append(FILE_SEP).append("original/").append(filePath).append(rangeSuffix).append("\n")
                append(window.text).append("\n")
                append(FILE_SEP).append("current/").append(filePath).append(rangeSuffix).append("\n")
                append(current).append("\n")
                append(FILE_SEP).append("updated/").append(filePath).append(rangeSuffix).append("\n")
                append(prefill)
            }

        val sb = StringBuilder()
        var remaining = MAX_PROMPT_CHARS - tail.length

        var retrievalBudget = MAX_RETRIEVAL_CHARS
        for (chunk in contextChunks) {
            val section =
                buildString {
                    append(FILE_SEP).append(chunk.file_path).append("\n")
                    append(chunk.content).append("\n")
                }
            if (section.length > retrievalBudget || section.length > remaining) continue
            sb.append(section)
            retrievalBudget -= section.length
            remaining -= section.length
        }

        var editsBudget = MAX_RECENT_EDITS_CHARS
        for (edit in recentEdits) {
            val section =
                buildString {
                    append(FILE_SEP).append(edit.filePath).append(".diff\n")
                    append("original:\n").append(edit.originalText).append("\n")
                    append("updated:\n").append(edit.newText).append("\n")
                }
            if (section.length > editsBudget || section.length > remaining) continue
            sb.append(section)
            editsBudget -= section.length
            remaining -= section.length
        }

        sb.append(tail)
        return sb.toString()
    }

    // ---------------- HTTP ----------------

    /**
     * Streaming variant. Opens a `stream: true` request, reads SSE chunks
     * (`data: {...}\n\n`), accumulates the `choices[0].text` deltas, and
     * invokes `onChunk` with the running accumulated text at every newline
     * boundary (cheaper than per-token UI updates). Returns the final
     * accumulated text on completion, or null on failure / cancellation.
     */
    private suspend fun streamCompletion(
        baseUrl: String,
        model: String,
        apiKey: String,
        maxTokens: Int,
        prompt: String,
        onChunk: suspend (String) -> Unit,
    ): String? = coroutineScope {
        val body =
            defaultJson.encodeToString(
                OpenAICompletionRequest.serializer(),
                OpenAICompletionRequest(
                    model = model,
                    prompt = prompt,
                    max_tokens = maxTokens,
                    temperature = 0.0,
                    stop = listOf(FILE_SEP, EOS_OT, EOS_S),
                    stream = true,
                ),
            )

        val url = baseUrl.trimEnd('/') + "/v1/completions"
        val builder =
            Request
                .Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
                .header("Accept", "text/event-stream")
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")

        val call = okHttp.newCall(builder.build())

        // Sibling watcher: when this coroutineScope is cancelled (parent typed
        // again, scope disposal, etc.), the watcher's `finally` fires and aborts
        // the OkHttp call. Cancelling the call closes the socket, which both
        // unblocks the blocking source.readUtf8Line() below AND signals LM Studio
        // to drop the request from its queue. Without this, Job.invokeOnCompletion
        // wouldn't fire until the Job actually completed — and the Job can't
        // complete while the IO thread is blocked. Deadlock.
        val watcher =
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { call.cancel() }
                }
            }

        try {
            withContext(Dispatchers.IO) {
                val response =
                    try {
                        call.execute()
                    } catch (e: IOException) {
                        if (call.isCanceled()) throw CancellationException("Stream cancelled")
                        logger.warn("OpenAI /v1/completions stream connect failed: ${e.message}")
                        return@withContext null
                    }
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        logger.warn(
                            "OpenAI /v1/completions stream $url: ${resp.code} ${resp.body?.string()?.take(500)}",
                        )
                        return@withContext null
                    }
                    val source = resp.body?.source() ?: return@withContext null
                    val accumulated = StringBuilder()
                    var pendingEmit = false
                    var lastEmitMs = 0L
                    val minEmitGapMs = 500L
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line =
                            try {
                                source.readUtf8Line()
                            } catch (_: IOException) {
                                if (call.isCanceled()) throw CancellationException("Stream cancelled")
                                null
                            } ?: break
                        if (!line.startsWith("data:")) continue
                        val payload = line.removePrefix("data:").trim()
                        if (payload == "[DONE]" || payload.isEmpty()) continue
                        val delta =
                            try {
                                defaultJson
                                    .decodeFromString(OpenAICompletionResponse.serializer(), payload)
                                    .choices
                                    .firstOrNull()
                                    ?.text
                                    .orEmpty()
                            } catch (_: Exception) {
                                ""
                            }
                        if (delta.isEmpty()) continue
                        accumulated.append(delta)
                        if (delta.contains('\n')) pendingEmit = true
                        val now = System.currentTimeMillis()
                        if (pendingEmit && now - lastEmitMs >= minEmitGapMs) {
                            pendingEmit = false
                            lastEmitMs = now
                            onChunk(accumulated.toString())
                        }
                    }
                    accumulated.toString()
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    /**
     * Uses OkHttp because okhttp3.Call.cancel() actually aborts the in-flight
     * socket. java.net.http.HttpClient.sendAsync's CompletableFuture.cancel()
     * only flips the future's state — the server keeps processing the request,
     * which is what caused the LM Studio queue to balloon on every keystroke.
     */
    private suspend fun postCompletion(
        baseUrl: String,
        model: String,
        apiKey: String,
        maxTokens: Int,
        prompt: String,
    ): String? {
        val body =
            defaultJson.encodeToString(
                OpenAICompletionRequest.serializer(),
                OpenAICompletionRequest(
                    model = model,
                    prompt = prompt,
                    max_tokens = maxTokens,
                    temperature = 0.0,
                    stop = listOf(FILE_SEP, EOS_OT, EOS_S),
                ),
            )

        val url = baseUrl.trimEnd('/') + "/v1/completions"
        val builder =
            Request
                .Builder()
                .url(url)
                .post(body.toRequestBody(jsonMediaType))
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")

        val call = okHttp.newCall(builder.build())

        val responseBody: String =
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(
                            call: Call,
                            e: IOException,
                        ) {
                            if (!cont.isCompleted) {
                                if (call.isCanceled()) {
                                    cont.cancel()
                                } else {
                                    logger.warn("OpenAI /v1/completions request failed: ${e.message}")
                                    cont.resume("")
                                }
                            }
                        }

                        override fun onResponse(
                            call: Call,
                            response: Response,
                        ) {
                            response.use {
                                if (!it.isSuccessful) {
                                    logger.warn(
                                        "OpenAI /v1/completions $url: ${it.code} ${it.body?.string()?.take(500)}",
                                    )
                                    if (!cont.isCompleted) cont.resume("")
                                    return
                                }
                                val text = it.body?.string().orEmpty()
                                if (!cont.isCompleted) cont.resume(text)
                            }
                        }
                    },
                )
            }

        if (responseBody.isEmpty()) return null
        return try {
            defaultJson
                .decodeFromString(OpenAICompletionResponse.serializer(), responseBody)
                .choices
                .firstOrNull()
                ?.text
        } catch (e: Exception) {
            logger.warn("Failed to parse OpenAI completion response: ${e.message}")
            null
        }
    }

    // ---------------- Postprocessing ----------------

    private fun postprocess(raw: String): String? {
        var text = raw
        for (token in listOf(FILE_SEP, EOS_OT, EOS_S)) {
            val idx = text.indexOf(token)
            if (idx >= 0) text = text.substring(0, idx)
        }
        text = stripRepetition(text).trimEnd('\r', '\n')
        return if (text.isEmpty()) null else text
    }

    /**
     * Cuts off pathological repetition at the tail of the completion. Handles
     * two flavors: single-character runs ("aaaa…") and full-line repeats.
     */
    private fun stripRepetition(text: String): String {
        val tCharStripped =
            if (text.length >= 30 && text.takeLast(30).all { it == text.last() }) {
                var i = text.length - 1
                val c = text.last()
                while (i >= 0 && text[i] == c) i--
                text.substring(0, (i + 2).coerceAtMost(text.length))
            } else {
                text
            }

        val lines = tCharStripped.split('\n')
        if (lines.size < 4) return tCharStripped
        val last = lines.last()
        if (last.isBlank()) return tCharStripped
        var repeats = 0
        var idx = lines.size - 1
        while (idx >= 0 && lines[idx] == last) {
            repeats++
            idx--
        }
        return if (repeats >= 4) {
            lines.subList(0, (idx + 2).coerceAtMost(lines.size)).joinToString("\n")
        } else {
            tCharStripped
        }
    }

    // ---------------- Anchor truncation + response assembly ----------------

    /**
     * Reconstructs the predicted updated window as `prefill + modelOutput`,
     * then trusts the original window for any line-suffix the model re-emits
     * verbatim. This silently discards drift past the re-anchor point.
     */
    // The plugin renders edits ≥ 8 lines from the cursor as a "Tab to jump here"
    // hint instead of inline ghost text. For a local model that's prone to
    // imagining edits anywhere in the window, those hints clutter the UI without
    // being useful. Reject any completion whose change span doesn't stay near
    // the cursor.
    private const val MAX_EDIT_DISTANCE_LINES_FROM_CURSOR = 4

    private fun buildAutocompleteResponse(
        fileContents: String,
        window: Window,
        prefill: String,
        modelOutput: String,
        id: String = UUID.randomUUID().toString(),
        cursorPosition: Int = -1,
    ): NextEditAutocompleteResponse? {
        // Build the predicted full window: prefill + modelOutput, padded with the
        // original tail if the model stopped emitting before the end of the window.
        // Without padding, an early-stopping model (e.g. cursor at end of an
        // already-complete comment line) produces an "empty" predicted window and
        // the diff treats everything below as a deletion.
        val predictedFullText = synthesizePredictedFullText(prefill + modelOutput, window.text)
        val originalLines = window.text.split('\n')
        val predictedLines = predictedFullText.split('\n')

        val patch = DiffUtils.diff(originalLines, predictedLines)
        if (patch.deltas.isEmpty()) return null

        // Take only the FIRST delta cluster. Subsequent deltas are likely model drift
        // (re-emitted content with corruption further down). Restricting to the first
        // delta keeps the change span tight and matches cursortab's anchor-truncation
        // behaviour in spirit.
        val firstDelta = patch.deltas.first()
        val deltaStartLine = firstDelta.source.position // 0-indexed line in original window
        val deltaLineCount = firstDelta.source.lines.size

        // Build source and target as joined strings so we can refine the change span
        // at the character level — line-level granularity is too coarse for in-line
        // edits like `Bat<cursor>` → `BatchRecyclePlaidItemsResponse`.
        val sourceText = firstDelta.source.lines.joinToString("\n")
        val targetText = firstDelta.target.lines.joinToString("\n")

        val prefixLen = commonPrefixLen(sourceText, targetText)
        val rawSuffixLen = commonSuffixLen(sourceText, targetText, prefixLen)
        val suffixLen =
            minOf(
                rawSuffixLen,
                sourceText.length - prefixLen,
                targetText.length - prefixLen,
            )

        var insertedText = targetText.substring(prefixLen, targetText.length - suffixLen)
        val tailContext = sourceText.substring(sourceText.length - suffixLen)

        // Dedup: when the model emits content for the cursor area but then *also*
        // re-emits text that already exists after the cursor (a known FIM-model
        // failure mode), trim the overlap. Example bug:
        //   orig:  (res *plaidpb.Bat, err error) {
        //   new:   (res *plaidpb.BatchRecyclePlaidItemsResponse, err error, err error) {
        //   insertedText = "chRecyclePlaidItemsResponse, err error"
        //   tailContext  = ", err error) {"
        //   insertedText ends with ", err error" which is the start of tailContext
        //   → trim 11 chars off insertedText.
        val maxOverlap = minOf(insertedText.length, tailContext.length)
        for (k in maxOverlap downTo 1) {
            if (insertedText.endsWith(tailContext.substring(0, k))) {
                insertedText = insertedText.dropLast(k)
                break
            }
        }

        // Char offset of the delta's start line within window.text.
        val deltaLineStartInWindow = lineStartCharOffset(window.text, deltaStartLine)
        val absoluteStart = window.startOffset + deltaLineStartInWindow + prefixLen
        val absoluteEnd =
            window.startOffset + deltaLineStartInWindow + sourceText.length - suffixLen

        if (insertedText.isEmpty() && absoluteStart == absoluteEnd) return null
        // Model emitted only re-anchored content (nothing new) — no edit to show.
        if (insertedText.isEmpty() && tailContext.isEmpty() && deltaLineCount == 0) return null

        val newContent = insertedText

        // Drop edits that aren't near the cursor — otherwise the IDE renders them as
        // "Tab to jump here" hints that clutter the editor.
        if (cursorPosition >= 0) {
            val cursorLine = lineAtOffset(fileContents, cursorPosition)
            val editStartLine = lineAtOffset(fileContents, absoluteStart)
            val editEndLine = lineAtOffset(fileContents, absoluteEnd.coerceAtLeast(absoluteStart))
            val nearestLineDistance =
                minOf(
                    kotlin.math.abs(cursorLine - editStartLine),
                    kotlin.math.abs(cursorLine - editEndLine),
                )
            val cursorInsideEdit = cursorLine in editStartLine..editEndLine
            if (!cursorInsideEdit && nearestLineDistance > MAX_EDIT_DISTANCE_LINES_FROM_CURSOR) {
                return null
            }
        }

        return NextEditAutocompleteResponse(
            start_index = absoluteStart,
            end_index = absoluteEnd,
            completion = newContent,
            confidence = 1.0f,
            autocomplete_id = id,
            elapsed_time_ms = null,
            completions =
                listOf(
                    NextEditAutocompletion(
                        start_index = absoluteStart,
                        end_index = absoluteEnd,
                        completion = newContent,
                        confidence = 1.0f,
                        autocomplete_id = id,
                    ),
                ),
        )
    }

    /**
     * Char offset of the first character of line `lineIdx` in `text`.
     */
    private fun lineStartCharOffset(
        text: String,
        lineIdx: Int,
    ): Int {
        var i = 0
        var line = 0
        while (line < lineIdx && i < text.length) {
            if (text[i] == '\n') line++
            i++
        }
        return i
    }

    /**
     * Returns a "full" predicted window: the model's combined output plus the
     * unchanged tail of the original window if the model stopped emitting early.
     *
     * Cases:
     *  - combined covers the whole window already → return as-is.
     *  - combined ends on a clean newline → append original from the matching line.
     *  - combined's last line is partial (model edit mid-line) → trust that line,
     *    skip the same line in original, append from the NEXT line.
     *
     * This is the equivalent of the old line-count fallback but produces a single
     * string we can run line-level diff against.
     */
    private fun synthesizePredictedFullText(
        combined: String,
        originalWindowText: String,
    ): String {
        if (combined.length >= originalWindowText.length) return combined

        val combinedNewlineCount = combined.count { it == '\n' }

        var origLineStart = 0
        run {
            var nlCount = 0
            var i = 0
            while (nlCount < combinedNewlineCount && i < originalWindowText.length) {
                if (originalWindowText[i] == '\n') nlCount++
                i++
            }
            origLineStart = i
        }

        return if (combined.endsWith('\n') || combined.isEmpty()) {
            combined + originalWindowText.substring(origLineStart)
        } else {
            // combined ends mid-line; trust its last line, skip the same line in
            // the original, append from the next line down.
            var origNextLineStart = origLineStart
            while (origNextLineStart < originalWindowText.length &&
                originalWindowText[origNextLineStart] != '\n'
            ) {
                origNextLineStart++
            }
            if (origNextLineStart < originalWindowText.length) origNextLineStart++
            combined + "\n" + originalWindowText.substring(origNextLineStart.coerceAtMost(originalWindowText.length))
        }
    }

    private fun lineAtOffset(
        text: String,
        offset: Int,
    ): Int {
        val clamped = offset.coerceIn(0, text.length)
        var line = 0
        for (i in 0 until clamped) if (text[i] == '\n') line++
        return line
    }

    private fun commonPrefixLen(
        a: String,
        b: String,
    ): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffixLen(
        a: String,
        b: String,
        prefixLen: Int,
    ): Int {
        val maxA = a.length - prefixLen
        val maxB = b.length - prefixLen
        val n = minOf(maxA, maxB)
        var i = 0
        while (i < n && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return i
    }
}
