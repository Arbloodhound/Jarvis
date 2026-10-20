package com.agent.jarvis.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.agent.jarvis.net.cancelOnStop
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
    ): Result<String> = retryWithoutTemperature { completeOnce(messages, maxTokens, temperature) }

    /**
     * Newer models refuse the field outright (Pixel cloud check, 3.0.73, claude-opus-5-5:
     * "HTTP 400: `temperature` is deprecated for this model"). On that error the request goes again
     * without it, and this client never sends it again.
     */
    @Volatile var temperatureRejected = false
        private set

    private suspend fun retryWithoutTemperature(send: suspend () -> Result<String>): Result<String> {
        val r = send()
        if (!temperatureRejected && r.exceptionOrNull()?.message?.let { rejectedTemperature(it) } == true) {
            temperatureRejected = true
            android.util.Log.i("JarvisProvider", "provider rejected temperature; sent without it from now on")
            return send()
        }
        return r
    }

    private suspend fun completeOnce(
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
                .also { if (!temperatureRejected) it.put("temperature", temperature) }
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
        /**
         * Native tool definitions (OpenAI "tools"). A native call comes back as the same text a
         * text-protocol call is — {"tool":"name","params":{...}} — so callers parse one format.
         * A provider that rejects "tools" gets the request again without them, and never again
         * from this client ([nativeToolsRejected]).
         */
        tools: JSONArray? = null,
        onToken: (String) -> Unit,
    ): Result<String> {
        val useTools = tools != null && !nativeToolsRejected
        val r = retryWithoutTemperature { streamOnce(messages, maxTokens, temperature, if (useTools) tools else null, onToken) }
        if (useTools && r.exceptionOrNull()?.message?.let { NativeTools.rejectedTools(it) } == true) {
            nativeToolsRejected = true
            android.util.Log.i("JarvisProvider", "provider rejected native tools; text protocol from now on")
            return retryWithoutTemperature { streamOnce(messages, maxTokens, temperature, null, onToken) }
        }
        return r
    }

    /** True once the provider refused a request with "tools" in it. */
    @Volatile var nativeToolsRejected = false
        private set

    private suspend fun streamOnce(
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Double,
        tools: JSONArray?,
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
                .also { if (!temperatureRejected) it.put("temperature", temperature) }
                .put("stream", true)
                .also { applyVeniceParameters(it) }
            if (tools != null) bodyJson.put("tools", tools).put("tool_choice", "auto")

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
                val calls = NativeTools.CallAccumulator()
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
                        choice.optJSONObject("delta")?.optJSONArray("tool_calls")?.let { calls.add(it) }
                        val delta = choice.optJSONObject("delta")?.optString("content", "") ?: ""
                        if (delta.isNotEmpty()) {
                            full.append(delta)
                            onToken(delta)
                        }
                    }
                }
                lastFinishReason = finish
                // A native call wins over any text around it: the loop runs one tool per turn.
                calls.asTextCall()?.let { return@cancelOnStop Result.success(it) }
                if (finish == "length") {
                    android.util.Log.w("JarvisProvider", "reply cut off at max_tokens=$maxTokens (finish_reason=length)")
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
     * itself instead of the user's. Jarvis ships its own system prompt, so
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

        /** A 400 that names temperature as deprecated or unsupported. Pure, JVM-tested. */
        fun rejectedTemperature(error: String): Boolean =
            error.startsWith("HTTP 400") && error.contains("temperature") &&
                Regex("deprecated|not supported|unsupported", RegexOption.IGNORE_CASE).containsMatchIn(error)
    }
}
