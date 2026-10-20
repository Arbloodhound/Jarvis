package com.agent.jarvis.gate

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
        /** Delivered by a system API, but written by whoever sent it: an SMS, the
         * clipboard, a web page or search result. The channel is trustworthy; the
         * content is not. */
        THIRD_PARTY_CONTENT,
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
            "device_location" -> Source.SYSTEM_API
            "contacts_read" -> Source.SYSTEM_API
            // Attacker-writable content arriving through a system channel.
            "sms_read", "clipboard_read" -> Source.THIRD_PARTY_CONTENT
            // Results of driving another app or the web: what came back was
            // written by that app or page.
            "react_navigate", "web_search", "open_url" -> Source.THIRD_PARTY_CONTENT
            // Shopping listings and browsed pages: written by the seller, site or page.
            "shop_search", "web_browse" -> Source.THIRD_PARTY_CONTENT
            // Overheard speech: anyone near the phone can put words there.
            "ambient_recall" -> Source.THIRD_PARTY_CONTENT
            "read_text_on_screen", "read_screen_deep", "describe_screen", "screen_ocr", "look_at_screen" -> Source.ACCESSIBILITY_TREE
            "screenshot", "notification_read" -> Source.ACCESSIBILITY_TREE
            // Pure actions (toggles, volume, clipboard_write) observe nothing.
            else -> null
        }

        fun confidenceOf(source: Source): Confidence = when (source) {
            Source.SYSTEM_API -> Confidence.HIGH
            Source.OPERATOR_CONFIRMATION -> Confidence.HIGH
            Source.USER_REQUEST -> Confidence.HIGH
            Source.ACCESSIBILITY_TREE -> Confidence.LOW
            Source.THIRD_PARTY_CONTENT -> Confidence.LOW
        }
    }
}
