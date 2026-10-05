package com.agent.ultra.gate

/**
 * One thing the agent observed, and how much to trust it.
 *
 * The accessibility tree is LOW by construction. A hostile app can render
 * whatever text it wants, so anything read from the tree is exactly as
 * trustworthy as the app that wrote it — and the agent has no way to know
 * which apps are hostile. System APIs (BatteryManager, CameraManager,
 * ClipboardManager, PackageManager) return ground truth, so they are HIGH.
 *
 * The operator's tap on a gate card is HIGH, but only because it reaches
 * this code through the onClick callback in Ultra's own process, not through
 * the tree reporting the card "looks confirmed." If the tree were the
 * source, the tap would carry the same LOW as every other tree observation
 * and the gate's one escape hatch would be rebuilt inside the wall.
 *
 * Observations decay. A battery read from ten minutes ago is not a current
 * fact. The default window mirrors ActionGate's 120-second timeout.
 */
data class Fact(
    val tool: String,
    val source: Source,
    val confidence: Confidence,
    val timestamp: Long = System.currentTimeMillis(),
    val summary: String = "",
) {
    enum class Source {
        SYSTEM_API,
        ACCESSIBILITY_TREE,
        OPERATOR_CONFIRMATION,
        USER_REQUEST,
    }

    enum class Confidence { HIGH, LOW }

    fun isRecent(maxAgeMs: Long = MAX_AGE_MS): Boolean =
        System.currentTimeMillis() - timestamp <= maxAgeMs

    fun effectiveConfidence(maxAgeMs: Long = MAX_AGE_MS): Confidence =
        if (isRecent(maxAgeMs)) confidence else Confidence.LOW

    companion object {
        const val MAX_AGE_MS = 120_000L

        fun sourceOf(tool: String): Source? = when (tool) {
            "battery_status", "device_info", "system_info" -> Source.SYSTEM_API
            "clipboard_read", "clipboard_write" -> Source.SYSTEM_API
            "device_location" -> Source.SYSTEM_API
            "sms_read", "contacts_read" -> Source.SYSTEM_API
            "flashlight_toggle", "wifi_toggle", "bluetooth_toggle",
            "do_not_disturb", "volume_set" -> Source.SYSTEM_API
            "read_text_on_screen", "read_screen_deep", "describe_screen" -> Source.ACCESSIBILITY_TREE
            "screenshot", "notification_read" -> Source.ACCESSIBILITY_TREE
            else -> null
        }

        fun confidenceOf(source: Source): Confidence = when (source) {
            Source.SYSTEM_API -> Confidence.HIGH
            Source.OPERATOR_CONFIRMATION -> Confidence.HIGH
            Source.USER_REQUEST -> Confidence.HIGH
            Source.ACCESSIBILITY_TREE -> Confidence.LOW
        }
    }
}
