package com.agent.ultra.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The live diagnostic the floating button runs in the real app (not the test app). The owner taps
 * it, it captures everything the running app actually does for [DURATION_SEC] — real log lines from
 * every subsystem, memory and storage over time, and any crash — then writes one file the owner can
 * share back. This records the real app running; it changes nothing and runs no stand-in.
 *
 * logcat with no tag filter and no READ_LOGS still shows a process its own lines only (Android's
 * rule), which is exactly this app's activity. We poll since the last read so a chatty run (ambient
 * listening) can't overflow the ring buffer before the window ends.
 *
 * Process-wide [state] + its own [scope] so a capture survives screen switches and rotation; the
 * floating button only reads [state].
 */
object LiveDiagnostic {
    const val DURATION_SEC = 300          // 5 minutes
    private const val POLL_MS = 8_000L     // drain logcat + snapshot memory this often

    /** Every log tag the app writes (collected from the source), so nothing it does is missed. */
    private val TAGS = listOf(
        "UltraActionGate", "UltraAmbient", "UltraApp", "UltraAudit", "UltraAuditWriter", "UltraBrain",
        "UltraBrowse", "UltraClaims", "UltraConnect", "UltraDownloads", "UltraFlow", "UltraGate",
        "UltraLearn", "UltraLibrarian", "UltraLlm", "UltraMemory", "UltraNav", "UltraNotif",
        "UltraObserve", "UltraOfflineNav", "UltraPerceive", "UltraPrompt", "UltraProvider",
        "UltraRoute", "UltraScheduler", "UltraShop", "UltraSpeak", "UltraThermal", "UltraTor",
        "UltraTrigger", "UltraVision", "UltraVlm", "UltraVoice", "UltraWalk", "UltraWeb",
        "AndroidRuntime",  // uncaught exceptions / FATAL
    )

    val running = mutableStateOf(false)
    /** Seconds left in the window, for the countdown next to the button. */
    val remainingSec = mutableIntStateOf(0)
    /** The finished file, ready to share; null until a capture completes. */
    val lastFile = mutableStateOf<File?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun toggle(ctx: Context) { if (running.value) stop() else start(ctx) }

    fun start(ctx: Context) {
        if (running.value) return
        val app = ctx.applicationContext
        running.value = true
        lastFile.value = null
        remainingSec.intValue = DURATION_SEC
        job = scope.launch {
            val started = System.currentTimeMillis()
            val log = StringBuilder()
            var sinceMs = started
            var lastRam = 0L
            try {
                while (isActive && System.currentTimeMillis() - started < DURATION_SEC * 1000L) {
                    delay(POLL_MS)
                    val now = System.currentTimeMillis()
                    remainingSec.intValue = ((DURATION_SEC * 1000L - (now - started)) / 1000).toInt().coerceAtLeast(0)
                    drainLogcat(sinceMs).takeIf { it.isNotBlank() }?.let { log.append(it).append('\n') }
                    sinceMs = now
                    log.append(memorySnapshot(app, now)).append('\n')
                    // Where the RAM goes, once a minute (owner, 2026-10-20).
                    if (now - lastRam >= 60_000L) { lastRam = now; RamReport.snapshot(app).forEach { log.append(it).append('\n') } }
                }
                RamReport.snapshot(app).forEach { log.append(it).append('\n') }
                // Catch the tail between the last poll and the stop.
                drainLogcat(sinceMs).takeIf { it.isNotBlank() }?.let { log.append(it).append('\n') }
            } finally {
                remainingSec.intValue = 0
                val file = withContext(Dispatchers.IO) { writeReport(app, started, log.toString()) }
                lastFile.value = file
                running.value = false
            }
        }
    }

    fun stop() { job?.cancel() }   // the finally in the job still writes what was captured

    /** This app's own log lines since [sinceMs], every tag. */
    private fun drainLogcat(sinceMs: Long): String = try {
        val since = String.format(Locale.ROOT, "%d.%03d", sinceMs / 1000, sinceMs % 1000)
        val cmd = listOf("logcat", "-d", "-v", "threadtime", "-T", since)
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("--------- beginning of") }.joinToString("\n")
        }
    } catch (e: Exception) { "(logcat unavailable: ${e.message})" }

    private fun memorySnapshot(ctx: Context, now: Long): String {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val maxHeapMb = rt.maxMemory() / 1_048_576
        val freeRamMb = mi.availMem / 1_048_576
        val low = mi.lowMemory
        val freeDiskMb = try { StatFs(ctx.filesDir.path).availableBytes / 1_048_576 } catch (_: Exception) { -1 }
        val t = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(now))
        return "MEM $t · heap ${usedHeapMb}/${maxHeapMb} MB · free RAM ${freeRamMb} MB${if (low) " (LOW)" else ""} · free disk ${freeDiskMb} MB"
    }

    private fun writeReport(ctx: Context, startedMs: Long, log: String): File? {
        val header = buildString {
            val ver = try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
            } catch (_: Exception) { "?" }
            appendLine("Jarvis live diagnostic ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(startedMs))}")
            appendLine("App: $ver · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Window: ${DURATION_SEC}s · tags: ${TAGS.size}")
            appendLine("Crash in window: ${if (com.agent.ultra.bench.KitReport.hasCrash(log.lines())) "YES" else "no"}")
            appendLine("=".repeat(60))
        }
        val text = header + log
        val name = "Jarvis-live-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(startedMs))}.txt"
        saveToDownloads(ctx, name, text)   // a copy kept on the phone (API 29+)
        // cacheDir/exports is the FileProvider-shared path (res/xml/file_paths.xml) used by the share sheet.
        return try {
            val dir = File(ctx.cacheDir, "exports").also { it.mkdirs() }
            File(dir, name).also { it.writeText(text) }
        } catch (_: Exception) { null }
    }

    /** Keep a copy in Downloads so it stays on the phone; no storage permission on API 29+. */
    private fun saveToDownloads(ctx: Context, name: String, text: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } catch (_: Exception) { /* share-sheet copy still works */ }
    }

    /** Open the Android share sheet for a finished report (send it on, or save it elsewhere). */
    fun share(ctx: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(android.content.Intent.createChooser(send, "Send Jarvis diagnostic")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
