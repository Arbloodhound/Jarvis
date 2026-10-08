package com.agent.ultra.gate

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Structured audit log for gate decisions. JSONL, one line per decision.
 *
 * Fields are chosen to be safe for public sharing: no args, no content,
 * no PII. Just the decision pattern — enough to calibrate risk scoring.
 */
object GateAuditLog {

    private const val TAG = "UltraAudit"
    private const val FILE_NAME = "gate_audit.jsonl"
    /** The previous generation: when the log passes [MAX_BYTES] it is renamed to this
     * (replacing any older one) and a fresh log starts. At most ~2 × MAX_BYTES on disk. */
    private const val ROTATED_NAME = "gate_audit.1.jsonl"
    private const val MAX_BYTES = 512 * 1024L

    enum class Outcome { ALLOWED, BLOCKED, OVERRIDDEN, AUTO_APPROVED }

    // Routes for [recordRoute]: which brain path answered. Fixed words only.
    const val ROUTE_CLOUD = "cloud"
    const val ROUTE_LOCAL_ANSWER = "local_answer"
    const val ROUTE_LOCAL_TOOL = "local_tool"
    const val ROUTE_OFFLINE_NAV = "offline_nav"
    const val ROUTE_WEB = "web"
    const val ROUTE_CLOUD_FALLBACK_LOCAL = "cloud_fallback_local"

    /** File I/O off the caller's thread (Brain records from Main.immediate). One
     * thread, so lines land in the order they were recorded. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "UltraAuditWriter").apply { isDaemon = true }
    }

    /** Set by [init] (UltraApplication.onCreate). [recordRoute] / [recordAction] have no
     * Context of their own, so they do nothing until this is set. */
    @Volatile private var appContext: Context? = null

    fun init(appContext: Context) {
        this.appContext = appContext.applicationContext ?: appContext
    }

    internal fun isInitialized(): Boolean = appContext != null

    /** Line count, read from disk once and kept current afterwards, so the
     * settings screen does not re-read the file on every composition. */
    @Volatile private var cachedCount: Int? = null

    fun record(
        context: Context,
        tool: String,
        outcome: Outcome,
        rule: String?,
        observations: ObservationLog,
        riskScore: RiskScorer.Score? = null,
    ) {
        try {
            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("tool", tool)
                put("outcome", outcome.name)
                if (rule != null) put("rule", rule)
                put("obs_high", observations.highCount())
                put("obs_low", observations.lowCount())
                put("obs_tool_high", if (observations.hasHighToolObservation()) 1 else 0)
                put("obs_tool_low_only", if (observations.hasLowToolOnly()) 1 else 0)
                if (riskScore != null) put("risk", riskScore.toJson())
            }
            // The entry is built here, on the caller's thread: ObservationLog is
            // not thread-safe. Only the disk write moves.
            append(file(context), entry)
        } catch (e: Exception) {
            Log.w(TAG, "audit write failed: ${e.message}")
        }
    }

    /**
     * Which path answered a request. [reason] is a short fixed word chosen by the caller —
     * never the user's text. No-op until [init] has run.
     */
    fun recordRoute(route: String, reason: String) {
        val ctx = appContext ?: return
        try {
            append(file(ctx), routeEntry(route, reason, System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.w(TAG, "audit write failed: ${e.message}")
        }
    }

    /** Pure, JVM-tested. Exactly {ts, kind, route, reason}. */
    internal fun routeEntry(route: String, reason: String, ts: Long): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("kind", "route")
        put("route", route)
        put("reason", reason)
    }

    /**
     * One ActionGate question, answered or not. [reasonWord] is the matched commitment word
     * (e.g. "pay"), never the on-screen label. No-op until [init] has run.
     */
    fun recordAction(reasonWord: String, approved: Boolean) {
        val ctx = appContext ?: return
        try {
            append(file(ctx), actionEntry(reasonWord, approved, System.currentTimeMillis()))
        } catch (e: Exception) {
            Log.w(TAG, "audit write failed: ${e.message}")
        }
    }

    /** Pure, JVM-tested. Exactly {ts, kind, outcome, rule}. */
    internal fun actionEntry(reasonWord: String, approved: Boolean, ts: Long): JSONObject = JSONObject().apply {
        put("ts", ts)
        put("kind", "action_gate")
        put("outcome", if (approved) Outcome.OVERRIDDEN.name else Outcome.BLOCKED.name)
        put("rule", "commitment:$reasonWord")
    }

    /** Queue one line for the single writer thread; rotates once the file passes [MAX_BYTES]. */
    private fun append(f: File, entry: JSONObject) {
        val line = entry.toString() + "\n"
        writer.execute {
            try {
                f.appendText(line)
                cachedCount?.let { cachedCount = it + 1 }
                if (rotate(f, MAX_BYTES)) cachedCount = null
            } catch (e: Exception) {
                Log.w(TAG, "audit write failed: ${e.message}")
            }
        }
    }

    /** Entries in the current log and the rotated one. */
    fun entryCount(context: Context): Int = cachedCount ?: try {
        val n = countEntries(file(context))
        cachedCount = n
        n
    } catch (_: Exception) { 0 }

    /** Bytes in the current log and the rotated one. */
    fun fileSizeBytes(context: Context): Long = try {
        val f = file(context)
        f.length() + rotatedOf(f).length()
    } catch (_: Exception) { 0L }

    /** Deletes the current log and the rotated one. */
    fun delete(context: Context) {
        deleteBoth(file(context))
        cachedCount = 0
    }

    fun deviceFingerprint(context: Context): JSONObject = JSONObject().apply {
        put("ram_mb", totalRamMb(context))
        put("android", Build.VERSION.SDK_INT)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    /**
     * Build an export file with a device header and all audit entries.
     * Returns the file, ready for a share intent.
     */
    fun exportFile(context: Context): File {
        val export = File(context.cacheDir, "ultra_gate_audit_export.jsonl")
        val header = JSONObject().apply {
            put("type", "device")
            put("ts", System.currentTimeMillis())
            put("fingerprint", deviceFingerprint(context))
        }
        // One buffered writer, not a file open/close per line.
        export.bufferedWriter().use { out ->
            out.write(header.toString() + "\n")
            writeEntries(file(context), out)
        }
        return export
    }

    fun shareIntent(context: Context): Intent {
        val export = exportFile(context)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            export,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/jsonl"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Jarvis gate audit log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** The rotated generation beside [f] (gate_audit.jsonl → gate_audit.1.jsonl). */
    internal fun rotatedOf(f: File): File = File(f.parentFile, ROTATED_NAME)

    /**
     * Once [f] is larger than [max], rename it to [rotatedOf] (replacing any older one), so the
     * next write starts a fresh file. Nothing is cut in half. Returns true if it rotated.
     * Pure file logic, JVM-tested; in the app it runs on the writer thread only.
     */
    internal fun rotate(f: File, max: Long): Boolean {
        if (f.length() <= max) return false
        val old = rotatedOf(f)
        if (old.exists() && !old.delete()) Log.w(TAG, "could not remove old rotated audit log")
        if (!f.renameTo(old)) {
            Log.w(TAG, "audit rotate failed")
            return false
        }
        return true
    }

    /** Non-blank lines in [f] and its rotated generation. */
    internal fun countEntries(f: File): Int = listOf(rotatedOf(f), f).sumOf { g ->
        if (g.exists()) g.bufferedReader().useLines { lines -> lines.count { it.isNotBlank() } } else 0
    }

    /** Oldest first: the rotated generation, then the current log. Blank lines dropped. */
    internal fun writeEntries(f: File, out: java.io.Writer) {
        for (g in listOf(rotatedOf(f), f)) {
            if (g.exists()) g.forEachLine { line ->
                if (line.isNotBlank()) out.write(line + "\n")
            }
        }
    }

    internal fun deleteBoth(f: File) {
        f.delete()
        rotatedOf(f).delete()
    }

    private fun totalRamMb(context: Context): Long = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(mi)
        mi.totalMem / (1024 * 1024)
    } catch (_: Exception) { -1 }
}
