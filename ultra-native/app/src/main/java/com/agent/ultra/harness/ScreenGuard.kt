package com.agent.ultra.harness

/*
 * MIT License — Copyright (c) 2026 OpenClaw Foundation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

/**
 * Screen-action guard. Every automation action is bound to the screen
 * snapshot it was read from:
 *
 * - observe() captures a snapshot and returns its id (generation).
 * - act() on a snapshot id that is no longer current throws
 *   StaleTarget("Observe again before acting").
 * - A snapshot is also invalidated when the UI epoch advances or the
 *   foreground package changes since the observe.
 * - Password fields: text entry is refused outright (node password flag
 *   or password input-type variation), before any action runs.
 * - Screen-bound taps: a tap coordinate must fall inside the element it was
 *   read from (accessibility node bounds, corroborated by vision bounds when
 *   available), or it is refused as unbound — see [ScreenBounds] /
 *   [ScreenGuard.requireScreenBound].
 *
 * Adapted from OpenClaw's AccessibilityActionExecutor.kt (act,
 * actionPreflight, TargetStale) and AccessibilitySnapshotter.kt
 * (shouldRedactText) — https://github.com/openclaw/openclaw,
 * apps/android/app/src/thirdParty/java/ai/openclaw/app/accessibility/.
 * The repo's AgentAccessibilityService is the integration point: feed its
 * snapshot ids and UI-epoch/package signals into observe()/act().
 */
class StaleTarget(message: String) : Exception(message)
class SecureContentRefused(message: String) : Exception(message)
class UnboundTap(message: String) : Exception(message)

/**
 * Screen-bound tap check. A tap coordinate is only allowed when it falls inside
 * the element it was derived from — the accessibility node's bounds, and, when a
 * vision read is also available, the vision model's bounds for the same target.
 * A tap outside those bounds is unbound (a guessed or stale coordinate) and is
 * refused. Pure geometry; no Android deps (the service supplies the rects).
 */
object ScreenBounds {
    /** A screen rectangle in pixels; [right]/[bottom] are exclusive. */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val valid: Boolean get() = right > left && bottom > top
        fun contains(x: Int, y: Int): Boolean =
            valid && x >= left && x < right && y >= top && y < bottom
    }

    /**
     * True when (tapX, tapY) is inside [nodeBounds] and — when supplied — also
     * inside [visionBounds]. Both null, or an invalid/missing node rect, is false:
     * a tap with no bounds to confirm it is never screen-bound.
     */
    fun withinBounds(tapX: Int, tapY: Int, nodeBounds: Rect?, visionBounds: Rect? = null): Boolean {
        val node = nodeBounds ?: return false
        if (!node.contains(tapX, tapY)) return false
        if (visionBounds != null && !visionBounds.contains(tapX, tapY)) return false
        return true
    }
}

/** Minimal snapshot handle; the real node tree stays in the service. */
data class ScreenSnapshot(
    val id: String,
    /** bumped every time the UI redraws */
    val uiEpoch: Long,
    val foregroundPackage: String,
    val capturedAtMs: Long = System.currentTimeMillis(),
)

object PasswordField {
    /** Mirrors Android input-type password variations. */
    fun isPassword(inputType: Int, isPasswordFlag: Boolean): Boolean {
        if (isPasswordFlag) return true
        val variation = inputType and 0x00000ff0
        return variation == 0x00000080 || // TYPE_TEXT_VARIATION_PASSWORD
            variation == 0x00000090 ||    // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            variation == 0x000000e0      // TYPE_TEXT_VARIATION_WEB_PASSWORD
    }
}

class ScreenGuard {
    private var current: ScreenSnapshot? = null

    /** Call after every fresh screen read. Returns the snapshot id. */
    fun observe(uiEpoch: Long, foregroundPackage: String): String {
        val s = ScreenSnapshot(
            id = "screen:" + java.util.UUID.randomUUID().toString().take(12),
            uiEpoch = uiEpoch,
            foregroundPackage = foregroundPackage,
        )
        current = s
        return s.id
    }

    fun currentId(): String? = current?.id

    /**
     * Validate an action bound to [snapshotId] before it runs.
     * @param uiEpochNow current UI epoch from the service
     * @param packageNow current foreground package
     * @throws StaleTarget when the screen moved on; the caller re-observes.
     */
    fun preflight(snapshotId: String, uiEpochNow: Long, packageNow: String) {
        val cur = current
        if (cur == null || cur.id != snapshotId) {
            throw StaleTarget("Observe again before acting")
        }
        if (uiEpochNow > cur.uiEpoch) {
            current = null
            throw StaleTarget("UI changed since observe; re-observe before acting")
        }
        if (packageNow != cur.foregroundPackage) {
            current = null
            throw StaleTarget("Foreground app changed since observe")
        }
    }

    /**
     * Refuse text entry into password fields. Call before any set-text.
     * @throws SecureContentRefused always when the field is a password field.
     */
    fun requireNotPassword(inputType: Int, isPasswordFlag: Boolean, fieldLabel: String = "") {
        if (PasswordField.isPassword(inputType, isPasswordFlag)) {
            throw SecureContentRefused(
                "Text entry into password fields is refused" +
                    (if (fieldLabel.isNotEmpty()) " ($fieldLabel)" else ""))
        }
    }

    /**
     * Refuse a tap whose coordinate is not bound to the element it came from.
     * @throws UnboundTap when the tap falls outside the node (and vision) bounds.
     * @see ScreenBounds.withinBounds
     */
    fun requireScreenBound(
        tapX: Int, tapY: Int,
        nodeBounds: ScreenBounds.Rect?, visionBounds: ScreenBounds.Rect? = null,
    ) {
        if (!ScreenBounds.withinBounds(tapX, tapY, nodeBounds, visionBounds)) {
            throw UnboundTap("Tap ($tapX,$tapY) is outside the element it was read from; re-observe before acting")
        }
    }

    /** The screen changed out from under us; drop the binding. */
    fun invalidate() {
        current = null
    }
}
