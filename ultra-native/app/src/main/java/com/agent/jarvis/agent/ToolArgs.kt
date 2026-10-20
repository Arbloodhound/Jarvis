package com.agent.jarvis.agent

import org.json.JSONObject
import java.math.BigDecimal

/**
 * Typed tool arguments that fail closed. Pure, JVM-tested.
 *
 * `optBoolean("on", true)` turned the flashlight, Wi-Fi, Bluetooth or Do Not Disturb ON when the
 * model left "on" out or wrote something like "off"; `optInt("percent", 50)` set the volume to 50
 * when "percent" was missing. A device action should not invent a value it was not given, so
 * these return null for anything they cannot read, and the tool refuses instead.
 */
object ToolArgs {

    /**
     * A JSON boolean, or the strings "true"/"false" (case-insensitive, trimmed) — the same set
     * `optBoolean` accepts, so Brain.verifyAction reads the same answer. Null for anything else,
     * including a missing key, JSON null, "off", "1" and numbers.
     */
    fun bool(p: JSONObject, key: String): Boolean? = when (val v = p.opt(key)) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }

    /**
     * A whole number: an integer, a number with no fractional part (50.0), or a numeric string
     * ("50"). Null for anything else, including a missing key, JSON null, 50.5, non-numeric
     * strings, booleans and values outside the Int range.
     */
    fun int(p: JSONObject, key: String): Int? {
        val text = when (val v = p.opt(key)) {
            is Number -> v.toString()
            is String -> v.trim()
            else -> return null
        }
        return try {
            BigDecimal(text).intValueExact()
        } catch (_: NumberFormatException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }
}
