package com.agent.ultra.provider

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat client. One method: send a message list, get
 * the assistant's text back. Matches the contract the brain was proven against
 * (Venice llama-3.3-70b, /chat/completions, no streaming).
 */
class OpenAiClient(
    private val config: ProviderConfig,
    /**
     * Deadline for the whole call, connect to last byte. The read timeout alone never fires on a
     * stream that trickles a byte now and then, so without this a call could run forever.
     * Tests pass about a second.
     */
    callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
) {

    val modelName: String get() = config.model

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    data class ChatMessage(val role: String, val content: String)

    /**
     * finish_reason of the most recent reply ("stop", "length", ...), or null when
     * the server sent none. "length" means the reply was cut at max_tokens: a tool
     * call in it is unfinished and must not be executed as if it were whole.
     */
    @Volatile var lastFinishReason: String? = null
        private set

    /** True when the most recent reply was cut off at the token limit. */
    val lastReplyTruncated: Boolean get() = lastFinishReason == "length"

    suspend fun complete(
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Double,
    ): Result<String> = withContext(Dispatchers.IO) {
        lastFinishReason = null
        try {
            val msgs = JSONArray()
            for (m in messages) {
                msgs.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            val bodyJson = JSONObject()
                .put("model", config.model)
                .put("messages", msgs)
                .put("max_tokens", maxTokens)
                .put("temperature", temperature)
                .put("stream", false)
                .also { applyVeniceParameters(it) }

            val req = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = http.newCall(req)
            cancelOnStop(call) { call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@cancelOnStop Result.failure(
                        Exception("HTTP ${resp.code}: ${body.take(300)}")
                    )
                }
                val choice = JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                lastFinishReason = finishReasonOf(choice)
                val text = choice
                    .getJSONObject("message")
                    .optString("content", "")
                if (text.isBlank()) Result.failure(Exception("empty model response"))
                else Result.success(text)
            } }
        } catch (e: Exception) {
            // Stopped: the cancelled call's IOException is the Stop, not a model failure.
            ensureActive()
            Result.failure(e)
        }
    }

    /**
     * Streaming variant: SSE deltas delivered via onToken, full text returned.
     * Falls back to a clean failure if the endpoint can't stream.
     */
    suspend fun completeStreaming(
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Double,
        onToken: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        lastFinishReason = null
        try {
            val msgs = JSONArray()
            for (m in messages) {
                msgs.put(JSONObject().put("role", m.role).put("content", m.content))
            }
            val bodyJson = JSONObject()
                .put("model", config.model)
                .put("messages", msgs)
                .put("max_tokens", maxTokens)
                .put("temperature", temperature)
                .put("stream", true)
                .also { applyVeniceParameters(it) }

            val req = Request.Builder()
                .url(config.baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = http.newCall(req)
            cancelOnStop(call) { call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    return@cancelOnStop Result.failure(Exception("HTTP ${resp.code}: ${body.take(300)}"))
                }
                val full = StringBuilder()
                var finish: String? = null
                resp.body!!.source().use { source ->
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val frame = try { JSONObject(data) } catch (_: Exception) { continue /* non-JSON SSE line skipped */ }
                        // A provider that fails mid-stream sends an error frame with HTTP 200
                        // already on the wire. Swallowing it returned the partial text as a
                        // complete reply.
                        streamError(frame)?.let { msg ->
                            return@cancelOnStop Result.failure(Exception("stream error: $msg"))
                        }
                        val choice = frame.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        finishReasonOf(choice)?.let { finish = it }
                        val delta = choice.optJSONObject("delta")?.optString("content", "") ?: ""
                        if (delta.isNotEmpty()) {
                            full.append(delta)
                            onToken(delta)
                        }
                    }
                }
                lastFinishReason = finish
                if (finish == "length") {
                    android.util.Log.w("UltraProvider", "reply cut off at max_tokens=$maxTokens (finish_reason=length)")
                }
                if (full.isBlank()) Result.failure(Exception("empty stream"))
                else Result.success(full.toString())
            } }
        } catch (e: Exception) {
            // Stopped: the cancelled call's IOException is the Stop, not a model failure.
            ensureActive()
            Result.failure(e)
        }
    }

    /**
     * Runs [block] (the blocking execute and body read of [call]) and cancels [call] if the
     * calling coroutine is cancelled meanwhile. A blocking OkHttp call ignores coroutine
     * cancellation, so Stop kept the stream (and the run) going until it ended by itself.
     * The cancelled call throws an IOException in [block], which the callers' catch turns into
     * a failed Result as for any other network failure.
     */
    private suspend fun <T> cancelOnStop(call: Call, block: () -> T): T = coroutineScope {
        // Unconfined: the cancel runs on the thread that cancels, not after a wait for a free IO
        // thread. Cancelling a call that already finished does nothing.
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try { block() } finally { watcher.cancel() }
    }

    private fun finishReasonOf(choice: JSONObject): String? =
        if (choice.isNull("finish_reason")) null else choice.optString("finish_reason").ifBlank { null }

    /** The message of an SSE error frame ({"error":{...}} or {"error":"..."}), or null. */
    private fun streamError(frame: JSONObject): String? {
        if (!frame.has("error") || frame.isNull("error")) return null
        val e = frame.opt("error")
        return when (e) {
            is JSONObject -> e.optString("message").ifBlank { e.toString() }.take(300)
            else -> e.toString().take(300)
        }
    }

    /**
     * Venice prepends its own ~1000-token system prompt unless told not to.
     * Measured on llama-3.3-70b: with it, prompt_tokens goes 26 -> 1081 and
     * the model's chat template breaks outright (replies come back as
     * "assistant<|end_header_id|>assistant..."), or it answers questions about
     * itself instead of the user's. Ultra ships its own system prompt, so
     * Venice's is turned off. Only sent to Venice hosts — other
     * OpenAI-compatible servers reject unknown top-level fields.
     */
    private fun applyVeniceParameters(body: JSONObject) {
        if (!config.baseUrl.contains("venice.ai", ignoreCase = true)) return
        body.put(
            "venice_parameters",
            JSONObject().put("include_venice_system_prompt", false)
        )
    }

    companion object {
        /** Production deadline for one model call (connect to last streamed byte). */
        const val DEFAULT_CALL_TIMEOUT_MS = 180_000L
    }
}
