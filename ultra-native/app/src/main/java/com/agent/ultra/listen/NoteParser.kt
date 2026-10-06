package com.agent.ultra.listen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A note as the model wrote it, before its time words are resolved. */
data class NoteDraft(val kind: NoteKind, val text: String, val whenPhrase: String?)

/**
 * Tolerant parser for the librarian's "one JSON object per line" output. Pure, JVM-tested.
 *
 * Tolerates: a leftover reasoning trace (anything up to the last </think>, or an unterminated
 * <think> to the end), code fences, bullets/numbering before an object, a whole JSON array
 * instead of lines, "NONE", and prose lines (ignored). Rejects — counts, never stores — any
 * object that doesn't parse strictly, has an unknown kind, an empty or over-long text, or
 * wrong-typed fields, and a line cut off mid-object (the token limit). At most [MAX_NOTES]
 * notes per chunk.
 */
object NoteParser {
    const val MAX_NOTES = 12
    const val MAX_TEXT = 300

    data class Result(val notes: List<NoteDraft>, val rejected: Int)

    private val json = Json { isLenient = false; ignoreUnknownKeys = true }

    fun parse(raw: String): Result {
        val body = stripReasoning(raw)
        val notes = ArrayList<NoteDraft>()
        var rejected = 0

        fun take(e: JsonElement?) {
            val d = (e as? JsonObject)?.let(::draft)
            if (d == null) rejected++ else if (notes.size < MAX_NOTES) notes += d
        }

        val whole = body.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (whole.startsWith("[")) {
            val arr = runCatching { json.parseToJsonElement(whole) }.getOrNull() as? JsonArray
            if (arr != null) { arr.forEach(::take); return Result(notes, rejected) }
        }
        for (line0 in body.lines()) {
            val line = line0.trim()
            if (line.isEmpty() || line.startsWith("```")) continue
            val open = line.indexOf('{')
            val close = line.lastIndexOf('}')
            if (open < 0) continue                               // prose / NONE: not a note
            if (close < open) { rejected++; continue }           // cut off mid-object
            take(runCatching { json.parseToJsonElement(line.substring(open, close + 1)) }.getOrNull())
        }
        return Result(notes, rejected)
    }

    /** Drop a reasoning trace: through the last </think>, and any unterminated <think> to the end. */
    fun stripReasoning(raw: String): String {
        var s = raw
        val close = s.lastIndexOf("</think>")
        if (close >= 0) s = s.substring(close + "</think>".length)
        val open = s.indexOf("<think>")
        if (open >= 0) s = s.substring(0, open)
        return s
    }

    private fun draft(o: JsonObject): NoteDraft? {
        val kind = NoteKind.parse(string(o["kind"]) ?: return null) ?: return null
        val text = string(o["text"])?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (text.isEmpty() || text.length > MAX_TEXT) return null
        val whenEl = o["when"] ?: o["time"]
        val whenPhrase = when (whenEl) {
            null, JsonNull -> null
            else -> string(whenEl)   // a number or object: keep the note, just without a time
        }?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("none", true) }
        return NoteDraft(kind, text, whenPhrase)
    }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
}
