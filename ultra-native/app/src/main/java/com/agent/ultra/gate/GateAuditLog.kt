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
    private const val MAX_BYTES = 512 * 1024L

    enum class Outcome { ALLOWED, BLOCKED, OVERRIDDEN, AUTO_APPROVED }

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
            val f = file(context)
            f.appendText(entry.toString() + "\n")
            if (f.length() > MAX_BYTES) trim(f)
        } catch (e: Exception) {
            Log.w(TAG, "audit write failed: ${e.message}")
        }
    }

    fun entryCount(context: Context): Int = try {
        val f = file(context)
        if (f.exists()) f.readLines().count { it.isNotBlank() } else 0
    } catch (_: Exception) { 0 }

    fun fileSizeBytes(context: Context): Long = try {
        file(context).length()
    } catch (_: Exception) { 0L }

    fun delete(context: Context) {
        file(context).delete()
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
        export.writeText("")
        val header = JSONObject().apply {
            put("type", "device")
            put("ts", System.currentTimeMillis())
            put("fingerprint", deviceFingerprint(context))
        }
        export.appendText(header.toString() + "\n")
        val source = file(context)
        if (source.exists()) {
            source.forEachLine { line ->
                if (line.isNotBlank()) export.appendText(line + "\n")
            }
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
            putExtra(Intent.EXTRA_SUBJECT, "Agent Ultra gate audit log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun trim(f: File) {
        val lines = f.readLines()
        f.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
    }

    private fun totalRamMb(context: Context): Long = try {
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(mi)
        mi.totalMem / (1024 * 1024)
    } catch (_: Exception) { -1 }
}
