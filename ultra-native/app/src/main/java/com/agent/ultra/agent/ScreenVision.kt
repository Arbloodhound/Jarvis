package com.agent.ultra.agent

import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import com.agent.ultra.AgentAccessibilityService
import com.agent.ultra.local.VisionModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Seeing the screen as a picture, for what the accessibility tree does not carry (owner, 2026-10-07:
 * "add … Google's ML Kit … the vision model, another two to four gigabytes is fine"). Games, maps,
 * images with writing on them, canvas-drawn and many web pages give the tree little or nothing.
 *
 * - [screenshot]: the accessibility service's own takeScreenshot (Android 11+, needs
 *   canTakeScreenshot in its config; turning the service off and on again picks that up). No
 *   picture is saved anywhere. Secure windows (banking, passwords) come back black, by design.
 * - [ocr]: ML Kit text recognition, the bundled Latin model — inside the APK, offline, ~100 ms.
 * - [look]: the on-device vision model ([VisionModel], Qwen2.5-VL 3B) describes the picture or
 *   answers a question about it, when it has been downloaded (Settings → SCREEN VISION).
 *
 * Everything read this way goes through [AgentController.noteScreenSecrets] like tree text does,
 * so a code seen only in a picture is still held back from leaving the phone.
 * Runtime on the phone is unproven: nothing here has run on a device.
 */
object ScreenVision {
    private const val TAG = "UltraVision"

    /** Why there is no screenshot, or null when one can be taken. */
    fun unavailable(): String? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> "reading the screen as a picture needs Android 11 or later"
        AgentAccessibilityService.getInstance() == null -> "the accessibility service is not running"
        else -> null
    }

    /** The screen as a software ARGB bitmap, or null (no service, too soon after the last one, secure). */
    suspend fun screenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val svc = AgentAccessibilityService.getInstance() ?: return null
        // Android allows about one a second; a second try covers a read straight after another.
        repeat(2) { attempt ->
            val shot = withTimeoutOrNull(4000) { capture(svc) }
            if (shot is Bitmap) return shot
            if (shot != AccessibilityServiceError.TOO_SOON || attempt == 1) {
                android.util.Log.w(TAG, "screenshot failed: $shot")
                return null
            }
            kotlinx.coroutines.delay(1100)
        }
        return null
    }

    private enum class AccessibilityServiceError { TOO_SOON, OTHER }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun capture(svc: AgentAccessibilityService): Any = suspendCancellableCoroutine { cont ->
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Throwable) { null } finally { buffer.close() }
                        if (cont.isActive) cont.resume(bmp ?: AccessibilityServiceError.OTHER)
                    }
                    override fun onFailure(errorCode: Int) {
                        val e = if (errorCode == android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                            AccessibilityServiceError.TOO_SOON else AccessibilityServiceError.OTHER
                        if (cont.isActive) cont.resume(e)
                    }
                })
        } catch (e: Throwable) {
            // SecurityException when the running service predates canTakeScreenshot.
            android.util.Log.w(TAG, "takeScreenshot: ${e.message}")
            if (cont.isActive) cont.resume(AccessibilityServiceError.OTHER)
        }
    }

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The words in [bitmap], in reading order ([OcrLayout]), or null when recognition failed. */
    suspend fun ocr(bitmap: Bitmap): String? {
        val t0 = System.currentTimeMillis()
        val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text?> { cont ->
            // A phone where ML Kit can't start (e.g. a ROM missing something it expects) reads as
            // "no text", never as a crash of the tool or the navigation run.
            val task = try { recognizer.process(InputImage.fromBitmap(bitmap, 0)) } catch (e: Throwable) {
                android.util.Log.w(TAG, "ocr unavailable: $e")
                cont.resume(null); return@suspendCancellableCoroutine
            }
            task
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e ->
                    android.util.Log.w(TAG, "ocr failed: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
        } ?: return null
        val lines = text.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
            val r = l.boundingBox ?: return@mapNotNull null
            OcrLayout.Line(l.text, r.left, r.top, r.right, r.bottom)
        }
        val out = OcrLayout.format(lines, bitmap.width, bitmap.height)
        android.util.Log.i(TAG, "ocr ${lines.size} lines, ${out.length} chars in ${System.currentTimeMillis() - t0} ms")
        return out
    }

    /** A screenshot read by OCR, with any secret on it noted; null when no picture could be had. */
    suspend fun readScreen(controller: AgentController): String? {
        val bmp = screenshot() ?: return null
        return try {
            ocr(bmp)?.also { controller.noteScreenSecrets(it) }
        } finally { bmp.recycle() }
    }

    /**
     * What the vision model sees on the screen, or answers about it ([question] blank = describe it).
     * Null when the model is not downloaded or no picture could be had.
     */
    suspend fun look(controller: AgentController, question: String): String? {
        val ctx = AgentAccessibilityService.getInstance()?.applicationContext ?: return null
        if (!VisionModel.ready(ctx)) return null
        val bmp = screenshot() ?: return null
        return try {
            val prompt = question.trim().ifBlank {
                "Describe this phone screen for someone who cannot see it: which app it is, what is on it, " +
                    "any text, prices, buttons and pictures, top to bottom. Be concise and exact."
            }
            withContext(Dispatchers.Default) { VisionModel.describe(ctx, bmp, prompt) }
                ?.also { controller.noteScreenSecrets(it) }
        } finally { bmp.recycle() }
    }
}
