package com.agent.ultra.bench

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.agent.ultra.listen.WakeGate
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.ui.ChatMessage
import com.agent.ultra.ui.ChatStore
import com.agent.ultra.ui.theme.AgentUltraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Jarvis Tests": the Pixel test run without a laptop, DEBUG BUILDS ONLY (src/debug, its own
 * launcher icon; release APKs contain none of it). For phones that can't reach adb (the tester
 * is on a Chromebook). Runs the same accelerator probe and benchmark the adb scripts drive,
 * plus the 10-question brain test through the real chat brain, and saves everything (with this
 * app's own log lines) to a text file in Downloads to upload.
 *
 * Results also go to filesDir/kit-results.txt as each test finishes, so a long session can't
 * lose them to the log buffer rolling over.
 */
class TestKitActivity : ComponentActivity() {
    private val lines = mutableStateListOf<String>()
    private val busy = mutableStateOf(false)
    private val results: File get() = File(filesDir, "kit-results.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The benchmark and the brain test need this screen in front the whole time (a
        // background app is held to the small cores); keep it from sleeping.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            AgentUltraTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Jarvis Tests", style = MaterialTheme.typography.headlineSmall)
                        Text("Tap the buttons in order. Keep this screen open while a test runs.",
                            style = MaterialTheme.typography.bodyMedium)
                        val idle = !busy.value
                        Button(onClick = { start { hardwareCheck() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("1 · Hardware check (1 min)")
                        }
                        Button(onClick = { start { brainTest() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("2 · Brain test: 11 questions (airplane mode ON)")
                        }
                        Button(onClick = { start { speedTest() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("3 · Speed test (optional, ~10 min)")
                        }
                        Button(onClick = { start { saveAndShare() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("4 · Save results to Downloads + share")
                        }
                        OutlinedButton(onClick = { start { phoneInfo() } }, enabled = idle) { Text("Refresh phone info") }
                        lines.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        start { phoneInfo() }
    }

    private fun start(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        lifecycleScope.launch {
            try { block() } catch (t: Throwable) { say("ERROR: ${t.javaClass.simpleName}: ${t.message}") }
            finally { busy.value = false }
        }
    }

    private suspend fun say(s: String) = withContext(Dispatchers.Main) { lines.add(s) }

    private suspend fun record(section: String, body: List<String>) = withContext(Dispatchers.IO) {
        results.appendText("\n===== $section ${stamp()} =====\n" + body.joinToString("\n") + "\n")
    }

    // ---------------------------------------------------------------- phone info

    private suspend fun phoneInfo() {
        withContext(Dispatchers.Main) { lines.clear() }
        info().forEach { say(it) }
    }

    private fun info(): List<String> {
        val am = getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val free = StatFs(filesDir.absolutePath).availableBytes
        val engine = LocalModelEngine.shared(this)
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val recentCrash = KitReport.hasCrash(logcat(System.currentTimeMillis() - 3_600_000, listOf("AndroidRuntime")))
        return listOf(
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}",
            "Chip: $soc",
            "RAM: ${mem.totalMem / 1_048_576} MB total, ${mem.availMem / 1_048_576} MB free now",
            "Storage free: ${free / 1_073_741_824} GB",
            "Network: ${if (online()) "ONLINE" else "offline (airplane mode)"}",
            "Wake models downloaded: ${if (wakeModelsPresent()) "yes" else "NO — Jarvis → Settings → Ambient listening ON, wait 1 min"}",
            "Brain model: ${engine.modelLabel} — ${if (engine.modelPresent) "downloaded" else "NOT downloaded"}",
            "Crash in the last hour: ${if (recentCrash) "YES (it will be in the results file)" else "no"}",
        )
    }

    private fun online(): Boolean = getSystemService(ConnectivityManager::class.java).activeNetwork != null

    private fun wakeModelsPresent(): Boolean =
        listOf(WakeGate.MEL, WakeGate.EMB, WakeGate.cfgName(this)).all { File(filesDir, it).exists() }

    // ---------------------------------------------------------------- 1. hardware check

    private suspend fun hardwareCheck() {
        say("— Hardware check —")
        if (!wakeModelsPresent()) {
            say("The wake models aren't downloaded yet. Go to Jarvis → Settings → Ambient listening → ON, " +
                "wait a minute, then come back and tap 1 again.")
            return
        }
        say("Running… (about a minute; the screen may stutter)")
        val t0 = System.currentTimeMillis()
        val ran = withContext(Dispatchers.IO) { AccelProbeReceiver.runNow(applicationContext) }
        if (!ran) { say("A hardware check is already running."); return }
        val log = logcat(t0, listOf("UltraAccel"))
        record("HARDWARE CHECK", log)
        log.filter { "verdict" in it || " cpu=" in it }.forEach { say(it.substringAfter("): ")) }
        say("Done. Saved.")
    }

    // ---------------------------------------------------------------- 2. brain test

    private suspend fun brainTest() {
        say("— Brain test —")
        val engine = LocalModelEngine.shared(this)
        if (!engine.modelPresent) {
            say("No brain model downloaded. Jarvis → Settings → ON-DEVICE MODEL → Change model → pick → Download first.")
            return
        }
        if (online()) {
            say("Turn ON airplane mode first (so the phone's own brain answers, not the cloud), then tap 2 again.")
            return
        }
        say("Model: ${engine.modelLabel}. The answers also appear in a new chat in Jarvis.")
        withContext(Dispatchers.Main) {
            ChatStore.init(applicationContext)
            ChatStore.newChat()
        }
        val tps = mutableListOf<Double?>()
        var thinkLeak = 0
        val body = mutableListOf("model=${engine.modelLabel}")
        for ((i, q) in QUESTIONS.withIndex()) {
            while (withContext(Dispatchers.Main) { ChatStore.thinking.value }) delay(500)
            val shown = if (q.length > 60) q.take(57) + "…" else q
            say("Q${i + 1}: $shown")
            val t0 = System.currentTimeMillis()
            val answer = withContext(Dispatchers.Main) {
                val brain = ChatStore.chatBrain(applicationContext, engine, KIT_CONFIG_VERSION)
                ChatStore.add(ChatMessage(fromUser = true, text = q))
                ChatStore.thinking.value = true
                try { brain.run(q) } finally { ChatStore.thinking.value = false }
                ChatStore.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
            }
            val secs = (System.currentTimeMillis() - t0) / 1000
            val log = logcat(t0, listOf("UltraLlm", "UltraRoute", "AndroidRuntime"))
            val gen = KitReport.mainGen(log)
            tps += gen?.tokPerSec
            val leaked = "<think>" in answer || "</think>" in answer
            if (leaked) thinkLeak++
            val summary = "Q${i + 1}: ${secs}s · " + (gen?.let {
                "${KitReport.fmt(it.tokPerSec)} tok/s · ${it.tokens} tokens · majfaults ${it.majfaults} · free RAM ${it.availMb} MB"
            } ?: "no on-device generation logged") + (if (leaked) " · <think> TEXT SHOWED" else "")
            say(summary)
            body += summary
            body += log
            body += "answer: ${answer.take(300).replace('\n', ' ')}"
        }
        val (verdict, why) = KitReport.modelVerdict(tps.take(10), crashed = false)
        val advice = when (verdict) {
            KitReport.Model.KEEP -> "KEEP this model."
            KitReport.Model.TRY_SMALLER -> "TRY THE SMALLER ONE: Settings → ON-DEVICE MODEL → Change model → \"(IQ2_S) — roomier fallback\" → Download, then run test 2 again."
            KitReport.Model.NOT_ENOUGH_DATA -> "Not enough answers to judge — send me the results file."
        }
        val tail = listOf(
            "Verdict: $advice ($why)",
            "<think> text visible: ${if (thinkLeak == 0) "never (good)" else "$thinkLeak times (send me the file)"}",
            // Reaching this line means the app survived all of them, the long paste included.
            "Long paste (Q11): app stayed open (good)" + (if (tps.last() == null) ", but no on-device answer was logged" else ""),
        )
        tail.forEach { say(it) }
        record("BRAIN TEST", body + tail)
        say("Done. Saved. You can turn airplane mode off now.")
    }

    // ---------------------------------------------------------------- 3. speed test

    private suspend fun speedTest() {
        say("— Speed test —")
        if (!LocalModelEngine.shared(this).modelPresent) { say("Download the brain model first."); return }
        say("Running… about 10 minutes. Keep this screen open and the phone on the charger.")
        val t0 = System.currentTimeMillis()
        val ran = withContext(Dispatchers.IO) { BenchReceiver.runNow(applicationContext) }
        if (!ran) { say("A speed test is already running."); return }
        val log = logcat(t0, listOf("UltraBench"))
        record("SPEED TEST", log)
        log.filter { "threads=" in it && "warmup" !in it && "start" !in it }.forEach { say(it.substringAfter("): ")) }
        say("Done. Saved.")
    }

    // ---------------------------------------------------------------- 4. save + share

    private suspend fun saveAndShare() {
        say("— Saving —")
        val text = withContext(Dispatchers.IO) {
            buildString {
                appendLine("Jarvis test results ${stamp()}")
                info().forEach { appendLine(it) }
                if (results.exists()) append(results.readText())
                appendLine("\n===== RECENT APP LOG =====")
                logcatTail(LOG_TAGS, 6000).forEach { appendLine(it) }
            }
        }
        val name = "Jarvis-results-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())}.txt"
        val saved = withContext(Dispatchers.IO) { saveToDownloads(name, text) }
        say(if (saved) "Saved to Downloads/$name" else "Couldn't save to Downloads; use the share sheet instead.")
        val share = withContext(Dispatchers.IO) {
            File(cacheDir, name).also { it.writeText(text) }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", share)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Send Jarvis results"))
    }

    private fun saveToDownloads(name: String, text: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri != null && contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()); true } == true
        } else false
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- logcat

    /** This app's own log lines (Android only shows an app its own) for [tags] since [sinceMs]. */
    private fun logcat(sinceMs: Long, tags: List<String>): List<String> =
        runLogcat(listOf("logcat", "-d", "-v", "time", "-T", KitReport.logcatSince(sinceMs), "-s") + tags)

    private fun logcatTail(tags: List<String>, n: Int): List<String> =
        runLogcat(listOf("logcat", "-d", "-v", "time", "-t", n.toString(), "-s") + tags)

    private fun runLogcat(cmd: List<String>): List<String> = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readLines().also { p.waitFor() }
            .filter { !it.startsWith("--------- beginning of") }
    } catch (e: Exception) { listOf("(logcat unavailable: ${e.message})") }

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())

    companion object {
        private const val KIT_CONFIG_VERSION = -7001
        private val LOG_TAGS = listOf(
            "UltraLlm", "UltraRoute", "UltraBrain", "UltraAmbient", "UltraAccel", "UltraBench",
            "UltraThermal", "AndroidRuntime",
        )
        private val LONG_TEXT = (1..40).joinToString(" ") {
            "Paragraph $it. The town council met on Tuesday to discuss the new library, the bus routes " +
                "and the summer market, and agreed to publish the minutes next week."
        }
        val QUESTIONS = listOf(
            "hi",
            "explain why the sky is blue",
            "turn on the flashlight",
            "reply with three emojis and the word café",
            "what are three good dinners with chicken",
            "who was Napoleon in two sentences",
            "what's 17 times 23",
            "give me a packing list for a weekend trip",
            "explain what a mortgage is",
            "summarize everything we talked about",
            "summarize this: $LONG_TEXT",
        )
    }
}
