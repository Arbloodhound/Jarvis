package com.agent.ultra.local

/**
 * Resume rules for the model download (a 10 GB file over a phone's Wi-Fi: it WILL be interrupted).
 * The bytes already in model.part are kept and the server is asked for the rest with an HTTP
 * Range request. Pure, JVM-tested.
 */
object DownloadResume {
    /** What to do with a response. [append]: keep the .part and add to it from [startAt];
     *  otherwise start the file over from 0. [total]: full file size, -1 when unknown. */
    data class Plan(val append: Boolean, val startAt: Long, val total: Long) {
        val complete: Boolean get() = total >= 0 && startAt >= total
    }

    /** The Range header for a .part already holding [existing] bytes; null for a fresh start. */
    fun rangeHeader(existing: Long): String? = if (existing > 0) "bytes=$existing-" else null

    /**
     * [existing]: bytes in the .part when the request was sent. [code]: HTTP status.
     * [contentLength]: this response's body length (-1 unknown). [contentRange]: its
     * Content-Range header. Null = unusable response (caller treats it as an HTTP error).
     */
    fun plan(existing: Long, code: Int, contentLength: Long, contentRange: String?): Plan? = when (code) {
        // Partial content: only trust it if it starts exactly where our file ends.
        206 -> {
            val start = rangeStart(contentRange)
            if (start != existing) null
            else Plan(true, existing, totalOf(contentRange) ?: if (contentLength >= 0) existing + contentLength else -1)
        }
        // The server ignored the Range (or there was none): the body is the whole file.
        200 -> Plan(false, 0, contentLength)
        // Nothing left to send: the .part is already the whole file if the sizes agree.
        416 -> totalOf(contentRange)?.takeIf { it == existing }?.let { Plan(true, existing, it) }
        else -> null
    }

    /** "bytes 100-199/1000" → 100; null when absent or malformed. */
    fun rangeStart(contentRange: String?): Long? =
        contentRange?.let { Regex("""bytes\s+(\d+)-\d+/""").find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** "bytes 100-199/1000" → 1000 (also the 416 form, star then /1000); null when absent, malformed or a star. */
    fun totalOf(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    /** Wait before retry [attempt] (1-based): 5 s, 10 s, 20 s, 40 s, then 60 s. */
    fun backoffMs(attempt: Int): Long = minOf(60_000L, 5_000L shl (attempt - 1).coerceIn(0, 4))
}
