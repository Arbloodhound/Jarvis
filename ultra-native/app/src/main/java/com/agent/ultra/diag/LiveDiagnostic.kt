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
    const val DURATION_SEC = 900          // 15 minutes (owner, 2026-10-20: longer than 5); tap to stop early
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
            var lastSave = started
            var threads = SystemTrace.readThreads()
            val name = fileName(app, started)
            // Saved to Downloads/Jarvis from the start and again every minute, so a capture the
            // phone kills halfway is still there (owner: saved automatically, without asking).
            val saved = withContext(Dispatchers.IO) { createInDownloads(app, name) }
            try {
                while (isActive && System.currentTimeMillis() - started < DURATION_SEC * 1000L) {
                    delay(POLL_MS)
                    val now = System.currentTimeMillis()
                    remainingSec.intValue = ((DURATION_SEC * 1000L - (now - started)) / 1000).toInt().coerceAtLeast(0)
                    drainLogcat(sinceMs).takeIf { it.isNotBlank() }?.let { log.append(it).append('\n') }
                    sinceMs = now
                    log.append(memorySnapshot(app, now)).append('\n')
                    val (lines, next) = SystemTrace.snapshot(app, threads)
                    threads = next
                    lines.forEach { log.append(it).append('\n') }
                    // Where the RAM goes, once a minute (owner, 2026-10-20).
                    if (now - lastRam >= 60_000L) { lastRam = now; RamReport.snapshot(app).forEach { log.append(it).append('\n') } }
                    if (now - lastSave >= 60_000L) {
                        lastSave = now
                        val text = header(app, started, log.toString(), partial = true) + log
                        withContext(Dispatchers.IO) { saved?.let { writeDownloads(app, it, text) } }
                    }
                }
                RamReport.snapshot(app).forEach { log.append(it).append('\n') }
                // Catch the tail between the last poll and the stop.
                drainLogcat(sinceMs).takeIf { it.isNotBlank() }?.let { log.append(it).append('\n') }
            } finally {
                remainingSec.intValue = 0
                val file = withContext(Dispatchers.IO) { writeReport(app, started, log.toString(), name, saved) }
                lastFile.value = file
                running.value = false
            }
        }
    }

    fun stop() { job?.cancel() }   // the finally in the job still writes what was captured

    /** This app's own log lines since [sinceMs], every tag. */
    private fun drainLogcat(sinceMs: Long): String = try {
        val since = String.format(Locale.ROOT, "%d.%03d", sinceMs / 1000, sinceMs % 1000)
        // Every buffer an app may read for itself: its log lines, system messages about it, crashes,
        // and events (activity lifecycle, GC, low memory). Android shows an app its own lines only.
        val cmd = listOf("logcat", "-d", "-b", "main,system,crash,events", "-v", "threadtime", "-T", since)
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

    /** "Jarvis-DIAG_v3.0.75_2026-10-20_14-32.txt": the app version and when it started. */
    private fun fileName(ctx: Context, startedMs: Long): String =
        "Jarvis-DIAG_v${versionName(ctx)}_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date(startedMs))}.txt"

    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun header(ctx: Context, startedMs: Long, log: String, partial: Boolean): String = buildString {
        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        } catch (_: Exception) { "?" }
        appendLine("Jarvis live diagnostic ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(startedMs))}" +
            if (partial) " (still recording — saved so far)" else "")
        appendLine("App: $ver · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Window: up to ${DURATION_SEC / 60} min · polled every ${POLL_MS / 1000} s · logcat main, system, crash, events")
        appendLine("Crash in window: ${if (com.agent.ultra.bench.KitReport.hasCrash(log.lines())) "YES" else "no"}")
        appendLine("Lines: MEM = memory · CPU threads = Jarvis threads busy since the last poll · CPU clocks · PHONE · APP · RAM = where RAM goes (each minute)")
        appendLine("=".repeat(60))
    }

    private fun writeReport(ctx: Context, startedMs: Long, log: String, name: String, saved: android.net.Uri?): File? {
        val text = header(ctx, startedMs, log, partial = false) + log
        saved?.let { writeDownloads(ctx, it, text) }   // the copy in Downloads/Jarvis, final
        // cacheDir/exports is the FileProvider-shared path (res/xml/file_paths.xml) used by the share sheet.
        return try {
            val dir = File(ctx.cacheDir, "exports").also { it.mkdirs() }
            File(dir, name).also { it.writeText(text) }
        } catch (_: Exception) { null }
    }

    /** A new file in Downloads/Jarvis (no storage permission on API 29+), or null. */
    private fun createInDownloads(ctx: Context, name: String): android.net.Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Jarvis")
            }
            ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) { null }
    }

    /** Replace the Downloads copy's contents ("wt" truncates). */
    private fun writeDownloads(ctx: Context, uri: android.net.Uri, text: String) {
        try { ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } }
        catch (_: Exception) { /* the share-sheet copy still works */ }
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
