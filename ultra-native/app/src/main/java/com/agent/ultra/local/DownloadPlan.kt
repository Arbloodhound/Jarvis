package com.agent.ultra.local

import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Every file Jarvis downloads, as one list: what it is, how big, whether it is on the phone, and
 * how far a download of it has got. The rules behind Settings → DOWNLOADS, the first-run card in
 * the chat and the Jarvis Tests "Download everything missing" button. [Downloads] fills it from
 * the real files and runs the existing download code for each. Pure, JVM-tested.
 */
object DownloadPlan {

    /** One downloadable thing. [bytes]: its download size (0 = unknown). [essential]: Jarvis
     *  misses a feature without it (the first-run card shows only for these). [partBytes]: an
     *  unfinished download already on the phone. */
    data class Asset(
        val id: String,
        val label: String,
        val bytes: Long,
        val present: Boolean,
        val essential: Boolean = true,
        val partBytes: Long = 0L,
    )

    /** Where a run left one item. */
    sealed class ItemState {
        object Queued : ItemState()
        /** [fraction] 0..1, or null when the size isn't known yet. */
        data class Running(val fraction: Float?) : ItemState()
        object Done : ItemState()
        data class Failed(val error: String) : ItemState()
    }

    /** What a feature-check or test line says about something not downloaded. */
    const val MISSING_HINT = "missing — Settings → DOWNLOADS → Download all missing"

    fun missing(assets: List<Asset>): List<Asset> = assets.filter { !it.present }

    /** Bytes still to fetch: what is missing, less what its unfinished download already holds. */
    fun missingBytes(assets: List<Asset>): Long =
        missing(assets).sumOf { (it.bytes - it.partBytes.coerceAtMost(it.bytes)).coerceAtLeast(0L) }

    /** Download order: smallest first, so the voice, speech and memory models land in a minute
     *  or two and the multi-GB brain comes last (an unknown size goes last too). */
    fun order(assets: List<Asset>): List<Asset> =
        missing(assets).sortedBy { if (it.bytes > 0) it.bytes else Long.MAX_VALUE }

    /** Show the chat's first-run card: something essential is missing and it wasn't dismissed. */
    fun showCard(assets: List<Asset>, dismissed: Boolean): Boolean =
        !dismissed && assets.any { it.essential && !it.present }

    /** "Jarvis needs 6.0 GB of downloads". */
    fun cardText(assets: List<Asset>): String = "Jarvis needs ${size(missingBytes(assets))} of downloads"

    /** Decimal units, as the size is quoted elsewhere: "0.9 MB", "59.7 MB", "4.8 GB". */
    fun size(bytes: Long): String = when {
        bytes <= 0L -> "size unknown"
        bytes >= 1_000_000_000L -> String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
    }

    /** Fraction done from an unfinished download's bytes; null when the total is unknown. */
    fun fraction(partBytes: Long, totalBytes: Long): Float? =
        if (totalBytes <= 0L) null else (partBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat()

    /** The status column: "✓", "missing", "downloading 42%", "waiting", "failed: …". */
    fun status(asset: Asset, state: ItemState?): String = when {
        state is ItemState.Running ->
            "downloading" + (state.fraction?.let { " ${(it * 100).toInt()}%" } ?: "…")
        asset.present -> "✓"
        state is ItemState.Failed -> "failed: ${state.error}"
        state is ItemState.Queued -> "waiting"
        asset.partBytes > 0 -> "missing (${size(asset.partBytes)} done)"
        else -> "missing"
    }

    /** How one item of a run ended. */
    data class Outcome(val id: String, val ok: Boolean, val error: String? = null)

    /**
     * Fetches [ids] one after another with [fetch] (null = success, else the error to show). An
     * item that fails or throws is recorded and the rest still run; cancelling stops the run.
     */
    suspend fun runSequential(
        ids: List<String>,
        fetch: suspend (String) -> String?,
        onStart: (String) -> Unit = {},
        onDone: (Outcome) -> Unit = {},
    ): List<Outcome> {
        val out = mutableListOf<Outcome>()
        for (id in ids) {
            onStart(id)
            val o = try {
                val err = fetch(id)
                if (err == null) Outcome(id, true) else Outcome(id, false, err)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome(id, false, e.message ?: e.javaClass.simpleName)
            }
            out += o
            onDone(o)
        }
        return out
    }
}
