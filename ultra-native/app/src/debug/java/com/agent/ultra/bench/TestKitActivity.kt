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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.agent.ultra.listen.WakeGate
import com.agent.ultra.local.DownloadPlan
import com.agent.ultra.local.Downloads
import com.agent.ultra.local.LocalModelEngine
import com.agent.ultra.ui.ChatMessage
import com.agent.ultra.ui.ChatStore
import com.agent.ultra.ui.theme.AgentUltraTheme
import kotlinx.coroutines.CompletableDeferred
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
    /** Live "Q2 running: 35 s" line, so a long silent think doesn't look like a hang. */
    private val status = mutableStateOf("")
    private var job: kotlinx.coroutines.Job? = null
    /** The feature check in progress ("Skip this part" goes to it). */
    private val featureRunner = mutableStateOf<FeatureCheck.Runner?>(null)
    /** A Yes/No question from a probe, and where its answer goes. */
    private val confirmText = mutableStateOf<String?>(null)
    private var confirmAnswer: CompletableDeferred<Boolean>? = null
    private val results: File get() = File(filesDir, "kit-results.txt")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The benchmark and the brain test need this screen in front the whole time (a
        // background app is held to the small cores); keep it from sleeping.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // As opening Jarvis does: listening left on but stopped by a restart or an update (the
        // Feature check otherwise finds "running=no", Pixel 2026-10-06 20:44).
        com.agent.ultra.listen.AmbientListenService.resumeIfOff(this)
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
                        Button(onClick = { start { downloadAll() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("0 · Download everything missing (Wi-Fi)")
                        }
                        Button(onClick = { start { hardwareCheck() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("1 · Hardware check (1 min)")
                        }
                        Button(onClick = { start { brainTest(QUICK_QUESTIONS) } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("2 · Quick brain test: 3 questions (airplane mode ON)")
                        }
                        OutlinedButton(onClick = { start { brainTest(QUESTIONS) } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("2b · Full brain test: 11 questions (long)")
                        }
                        Button(onClick = { start { speedTest() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("3 · Speed test (optional, ~10 min)")
                        }
                        Button(onClick = { start { featureCheck() } }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                            Text("5 · Feature check: tests every feature and scores it (~20–40 min, airplane mode ON)")
                        }
                        // Not gated on busy: results so far can be saved while a long test runs.
                        Button(onClick = { lifecycleScope.launch { runCatching { saveAndShare() } } }, modifier = Modifier.fillMaxWidth()) {
                            Text("4 · Save results to Downloads + share")
                        }
                        OutlinedButton(onClick = { start { phoneInfo() } }, enabled = idle) { Text("Refresh phone info") }
                        if (busy.value) {
                            OutlinedButton(onClick = { job?.cancel() }) { Text("Stop the test") }
                        }
                        featureRunner.value?.let { r ->
                            OutlinedButton(onClick = { r.skipCurrent() }) { Text("Skip this part") }
                        }
                        confirmText.value?.let { question ->
                            AlertDialog(
                                onDismissRequest = { answerConfirm(false) },
                                title = { Text("Jarvis Tests") },
                                text = { Text(question) },
                                confirmButton = { TextButton(onClick = { answerConfirm(true) }) { Text("Yes, go ahead") } },
                                dismissButton = { TextButton(onClick = { answerConfirm(false) }) { Text("No, skip it") } },
                            )
                        }
                        if (status.value.isNotEmpty()) {
                            Text(status.value, style = MaterialTheme.typography.bodyMedium)
                        }
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
        job = lifecycleScope.launch {
            try { block() }
            catch (_: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    lines.add("Stopped. What finished so far is saved (button 4).")
                }
            }
            catch (t: Throwable) { say("ERROR: ${t.javaClass.simpleName}: ${t.message}") }
            finally { busy.value = false; status.value = ""; featureRunner.value = null; answerConfirm(false) }
        }
    }

    private suspend fun say(s: String) = withContext(Dispatchers.Main) { lines.add(s) }

    private suspend fun record(section: String, body: List<String>) = withContext(Dispatchers.IO) {
        results.appendText("\n===== $section ${stamp()} · app $appVersion =====\n" + body.joinToString("\n") + "\n")
    }

    /** This build's version ("2.6.10 (27)"): the results file keeps every earlier run, so each
     *  section says which build it came from (a save right after an update re-sends old runs). */
    private val appVersion: String by lazy {
        try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        } catch (_: Exception) { "?" }
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
            "App: $appVersion (each section below names the build it ran on)",
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.DISPLAY}",
            "Chip: $soc",
            "RAM: ${mem.totalMem / 1_048_576} MB total, ${mem.availMem / 1_048_576} MB free now",
            "Storage free: ${free / 1_073_741_824} GB",
            "Network: ${if (online()) "ONLINE" else "offline (airplane mode)"}",
            "Wake models downloaded: ${if (wakeModelsPresent()) "yes" else "NO — tap 0 · Download everything missing"}",
            "Brain model: ${engine.modelLabel} — ${if (engine.modelPresent) "downloaded" else "NOT downloaded"}",
            "Crash in the last hour: ${if (recentCrash) "YES (it will be in the results file)" else "no"}",
        )
    }

    private fun online(): Boolean = getSystemService(ConnectivityManager::class.java).activeNetwork != null

    private fun wakeModelsPresent(): Boolean =
        listOf(WakeGate.MEL, WakeGate.EMB, WakeGate.cfgName(this)).all { File(filesDir, it).exists() }

    // ---------------------------------------------------------------- 0. downloads

    /** Settings → DOWNLOADS → Download all missing, shown here item by item. The download runs in
     *  the app (Downloads), so "Stop the test" stops only this screen's watching, not the download. */
    private suspend fun downloadAll() {
        say("— Download everything missing —")
        val before = withContext(Dispatchers.IO) { Downloads.assets(applicationContext) }
        if (DownloadPlan.missing(before).isEmpty() && !Downloads.state.value.running) {
            say("Everything is already downloaded."); return
        }
        if (!Downloads.downloadAllMissing(applicationContext) && !Downloads.state.value.running) {
            say("Nothing to download."); return
        }
        say("Downloading ${DownloadPlan.size(DownloadPlan.missingBytes(before))}, smallest first. Keep this screen open.")
        val reported = mutableSetOf<String>()
        while (true) {
            val run = Downloads.state.value
            val assets = withContext(Dispatchers.IO) { Downloads.assets(applicationContext) }
            for (a in assets) {
                val st = run.items[a.id]
                if (st is DownloadPlan.ItemState.Running) status.value = "${a.label}: ${DownloadPlan.status(a, st)}"
                if ((st is DownloadPlan.ItemState.Done || st is DownloadPlan.ItemState.Failed) && reported.add(a.id))
                    say("${a.label}: ${DownloadPlan.status(a, st)}")
            }
            if (!run.running) break
            delay(1_000)
        }
        val after = withContext(Dispatchers.IO) { Downloads.assets(applicationContext) }
        val items = Downloads.state.value.items
        record("DOWNLOADS", after.map { "${it.label} (${DownloadPlan.size(it.bytes)}): ${DownloadPlan.status(it, items[it.id])}" })
        val left = DownloadPlan.missing(after)
        say(if (left.isEmpty()) "Done: everything is downloaded." else "Done, ${left.size} still missing — tap 0 again to retry.")
    }

    // ---------------------------------------------------------------- 1. hardware check

    private suspend fun hardwareCheck() {
        say("— Hardware check —")
        if (!wakeModelsPresent()) {
            say("The wake models aren't downloaded yet. Tap 0 · Download everything missing, then tap 1 again.")
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

    private suspend fun brainTest(questions: List<String>) {
        say("— Brain test —")
        val engine = LocalModelEngine.shared(this)
        if (!engine.modelPresent) {
            say("No brain model downloaded. Tap 0 · Download everything missing first.")
            return
        }
        if (online()) {
            say("Turn ON airplane mode first (so the phone's own brain answers, not the cloud), then tap 2 again.")
            return
        }
        say("Model: ${engine.modelLabel}. The answers also appear in a new chat in Jarvis. " +
            "The first answer also loads the model and the brain thinks silently before answering, " +
            "so a minute or more per question can be normal.")
        newChat()
        val tps = mutableListOf<Double?>()
        var thinkLeak = 0
        val body = mutableListOf("model=${engine.modelLabel}")
        for ((i, q) in questions.withIndex()) {
            while (withContext(Dispatchers.Main) { ChatStore.thinking.value }) delay(500)
            val shown = if (q.length > 60) q.take(57) + "…" else q
            say("Q${i + 1}: $shown")
            val t0 = System.currentTimeMillis()
            val ticker = lifecycleScope.launch {
                while (true) {
                    status.value = "Q${i + 1} running: ${(System.currentTimeMillis() - t0) / 1000} s" +
                        if (i == 0 && !engine.loaded) " (loading the model first)" else " (thinking / answering)"
                    delay(1000)
                }
            }
            val answer = try { askBrain(q) } finally { ticker.cancel(); status.value = "" }
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
            // Per question, so a stopped test still leaves its numbers in the results file.
            record("BRAIN TEST Q${i + 1}", listOf(summary, "answer: ${answer.take(300).replace('\n', ' ')}") + log)
        }
        val (verdict, why) = KitReport.modelVerdict(tps.take(10), crashed = false)
        // Model-aware: names a smaller preset that exists, from this model's measured speed.
        val advice = FeatureProbes.modelAdvice(engine, verdict, KitReport.median(tps.take(10)))
        val tail = listOfNotNull(
            "Verdict: $advice ($why)",
            "<think> text visible: ${if (thinkLeak == 0) "never (good)" else "$thinkLeak times (send me the file)"}",
            // Reaching this line means the app survived all of them, the long paste included.
            if (questions !== QUESTIONS) null
            else "Long paste (Q11): app stayed open (good)" + (if (tps.last() == null) ", but no on-device answer was logged" else ""),
        )
        tail.forEach { say(it) }
        record("BRAIN TEST SUMMARY", listOf(body.first()) + tail)
        say("Done. Saved. You can turn airplane mode off now.")
    }

    /** A fresh chat in Jarvis, so earlier turns can't shape the next answers. */
    private suspend fun newChat() = withContext(Dispatchers.Main) {
        ChatStore.init(applicationContext)
        ChatStore.newChat()
    }

    /** [q] through the real chat brain, in the current chat (it shows in Jarvis too); its answer. */
    private suspend fun askBrain(q: String): String {
        while (withContext(Dispatchers.Main) { ChatStore.thinking.value }) delay(500)
        return withContext(Dispatchers.Main) {
            val brain = ChatStore.chatBrain(applicationContext, LocalModelEngine.shared(this@TestKitActivity), KIT_CONFIG_VERSION)
            ChatStore.add(ChatMessage(fromUser = true, text = q))
            ChatStore.thinking.value = true
            try { brain.run(q) } finally { ChatStore.thinking.value = false }
            ChatStore.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
        }
    }

    // ---------------------------------------------------------------- 3. speed test

    private suspend fun speedTest() {
        say("— Speed test —")
        if (!LocalModelEngine.shared(this).modelPresent) { say("Download the brain model first."); return }
        say("Running… about 15 minutes (4, 5 and 6 threads, each without and with the speed boost). " +
            "Keep this screen open and the phone on the charger.")
        val t0 = System.currentTimeMillis()
        val ran = withContext(Dispatchers.IO) { BenchReceiver.runNow(applicationContext) }
        if (!ran) { say("A speed test is already running."); return }
        val log = logcat(t0, listOf("UltraBench"))
        record("SPEED TEST", log)
        log.filter { "threads=" in it && "warmup" !in it && "start" !in it }.forEach { say(it.substringAfter("): ")) }
        say("Done. Saved.")
    }

    // ---------------------------------------------------------------- 5. feature check

    /** Every feature, tested and scored ([FeatureCheck]); each result goes to the results file as it ends. */
    private suspend fun featureCheck() {
        say("— Feature check —")
        say("Tests every feature, one after another, and scores it: PASS, WARN, FAIL or MISSING (not installed). " +
            "What Jarvis overheard and its notes are not changed (memory search uses a separate test file). " +
            "The chat parts show up as a new chat in Jarvis, like the brain test. " +
            "\"Skip this part\" moves on; \"Stop the test\" ends it.")
        if (online()) say("Tip: airplane mode is OFF, so two parts use a stand-in for the offline chat or are skipped.")
        val env = ProbeEnv(
            ctx = applicationContext,
            say = { say(it) },
            status = { status.value = it },
            confirm = { askConfirm(it) },
            askBrain = { askBrain(it) },
            newChat = { newChat() },
            online = { online() },
            logcat = { since, tags -> logcat(since, tags) },
        )
        val runner = FeatureCheck.Runner(env)
        featureRunner.value = runner
        val all = try {
            runner.run(
                onStart = { p, i ->
                    status.value = "Part ${i + 1}/${FeatureCheck.PROBES.size}: ${p.title}"
                    say("Part ${i + 1}/${FeatureCheck.PROBES.size}: ${p.title}…")
                },
                onResults = { _, results ->
                    for (r in results) {
                        val line = FeatureGrade.line(r)
                        say(line)
                        record("FEATURE ${r.feature}", listOf(line) + r.detail)
                    }
                },
            )
        } finally {
            featureRunner.value = null
        }
        val table = FeatureGrade.summary(all)
        record("FEATURE CHECK SUMMARY", table)
        say("— Summary —")
        table.forEach { say(it) }
        say("Done. Saved. Tap 4 to save the results file and send it.")
    }

    private suspend fun askConfirm(question: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            confirmAnswer?.complete(false)
            confirmAnswer = answer
            confirmText.value = question
        }
        return try { answer.await() } finally {
            // Cancelled (Skip, Stop, a timeout) or answered: the dialog goes with the question.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (confirmAnswer === answer) { confirmText.value = null; confirmAnswer = null }
            }
        }
    }

    private fun answerConfirm(yes: Boolean) {
        confirmText.value = null
        confirmAnswer?.complete(yes)
        confirmAnswer = null
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
            "UltraThermal", "UltraMemory", "UltraSpeak", "UltraOfflineNav", "UltraLibrarian", "AndroidRuntime",
        )
        /** One per thinking tier the router uses: light, short maths, normal. */
        val QUICK_QUESTIONS = FeatureCases.QUICK_QUESTIONS
        val QUESTIONS = FeatureCases.KIT_QUESTIONS
    }
}
