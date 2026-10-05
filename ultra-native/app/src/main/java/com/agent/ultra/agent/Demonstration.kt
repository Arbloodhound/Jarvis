package com.agent.ultra.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Learning a task by watching it done once.
 *
 * The accessibility service has been recording every tap the user makes, in
 * every app — package, widget class, and the visible label of the thing
 * tapped — into a 500-entry ring buffer, alongside the text of every
 * notification. That buffer has never had a reader. It is harmless today for
 * exactly that reason.
 *
 * ## The decision, made deliberately and not by default
 *
 * Wiring a consumer to that stream as it stands would turn a dormant buffer
 * into a running log of everything its owner does on their phone. That is a
 * different product from the one being built, and nobody asked for it.
 *
 * So recording is **explicit and bounded**. Nothing here reads the stream
 * unless the user has said "watch this", and it stops when they say stop.
 * Outside a demonstration the buffer stays what it is now: ephemeral, unread,
 * and overwritten every few hundred events. There is no ambient mode and no
 * setting to turn one on, because the useful thing and the invasive thing are
 * not the same feature and should not share a switch.
 *
 * ## What a demonstration keeps
 *
 * **The path, never the payload.** A step records which app, which control, and
 * what kind of control — not what was typed into it. Someone demonstrating how
 * they pay a bill teaches the route to the payment screen; the amount and the
 * account number are theirs and are not written down. On replay the values come
 * from the request being made at the time, or the routine stops and asks.
 *
 * That limit is what makes this safe to keep. A recording of the path is a
 * macro. A recording of the payload is a copy of someone's bank details.
 */
object Demonstration {

    /** One thing the user did, reduced to what can be replayed. */
    data class Step(
        val pkg: String,
        /** The app's own id for the control, when it gave one. */
        val vid: String,
        /** Widget class, short form — what kind of thing it is. */
        val kind: String,
    ) {
        /** Two steps are the same action if they touch the same control. */
        val signature: String get() = "$pkg/${vid.ifBlank { kind }}"
    }

    /**
     * No label is stored. None.
     *
     * The first attempt kept labels that "described a control rather than its
     * contents" — short, wordlike, no digits — so that a routine would read as
     * "Log in, Pay" instead of a list of view ids. Its own test killed it:
     * **"Sarah Miller" passes every one of those checks.** So does any person's
     * name, any place, any of a hundred things that are the payload rather than
     * the path.
     *
     * There is no reliable way to tell a control's name from a value by looking
     * at the text, and a rule that is right most of the time is not good enough
     * when being wrong means writing someone's contacts to disk. So the label is
     * dropped and the app's own view id is used instead: a constant a developer
     * typed into a layout file, already proven safe for exactly this purpose by
     * [ScreenStructure.looksLikeAName], which rejects the ids that are really
     * content in disguise.
     *
     * The cost is a routine that reads as `pay_button` rather than `Pay` when an
     * app gives no better name. That is a fair price, and it is paid once, in
     * readability, rather than repeatedly, in someone's private life.
     */

    /**
     * Turn raw captured events into replayable steps.
     *
     * Pure, so the judgement about what is kept can be argued with in a test
     * instead of only observed on a phone.
     *
     * Everything that is not a tap is dropped — window changes are scenery, and
     * notification text is someone's private correspondence that happens to
     * pass through the same queue. Consecutive taps on the same control
     * collapse: a user pressing a slow button four times demonstrated one step,
     * not four.
     */
    fun stepsFrom(rawEvents: List<String>): List<Step> {
        val out = mutableListOf<Step>()
        for (raw in rawEvents) {
            val e = try { JSONObject(raw) } catch (_: Exception) { continue }
            if (e.optString("cat") != "A11Y_CLICK") continue
            val d = e.optJSONObject("data") ?: continue

            val pkg = d.optString("pkg")
            if (pkg.isBlank() || pkg == OWN_PACKAGE) continue          // its own UI is not a step
            // The service writes this when the tap came from somewhere it
            // could not inspect. Nothing to learn from it.
            val text = d.optString("text")
            if (text == "[external]") continue

            // Only ids the app authored. looksLikeAName rejects the product
            // codes and record numbers that web content exposes as ids, which
            // change every visit and are content wearing an id's clothes.
            val rawVid = d.optString("vid")
            val vid = if (ScreenStructure.looksLikeAName(rawVid)) rawVid else ""
            val step = Step(
                pkg = pkg,
                vid = vid,
                kind = d.optString("cls").substringAfterLast('.'),
            )
            // Collapse a repeat only when we actually know it is the same
            // control. With a view id, two taps on `menu_button` in a row are
            // one press held down. Without one, every button in an app is
            // "ImageButton" and collapsing on that turned three distinct taps
            // — menu, home, menu — into a single step. Not knowing whether it
            // is the same thing is not evidence that it is.
            val prev = out.lastOrNull()
            if (prev != null && step.vid.isNotBlank() && prev.signature == step.signature) continue
            out.add(step)
            if (out.size >= MAX_STEPS) break
        }
        return out
    }

    /** A demonstration that taught nothing is not worth saving under a name. */
    fun worthKeeping(steps: List<Step>): Boolean = steps.size >= MIN_STEPS

    fun toJson(steps: List<Step>): String {
        val arr = JSONArray()
        for (s in steps) {
            arr.put(JSONObject().put("pkg", s.pkg).put("vid", s.vid).put("kind", s.kind))
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<Step> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Step(it.optString("pkg"), it.optString("vid"), it.optString("kind"))
            }
        }
    } catch (_: Exception) { emptyList() }

    /** How a routine reads when the user asks what they taught it. */
    fun describe(steps: List<Step>): String {
        if (steps.isEmpty()) return "nothing was recorded"
        return steps.mapIndexed { i, s ->
            val what = s.vid.ifBlank { s.kind }
            "${i + 1}. $what  (${s.pkg})"
        }.joinToString("\n")
    }

    // ── Recording ───────────────────────────────────────────────────
    //
    // Deliberately a tiny amount of state, and deliberately not persisted: if
    // the process dies mid-demonstration the recording is simply gone, which is
    // the right failure. A half-remembered routine that survives a crash is
    // worse than no routine.

    @Volatile private var recording = false
    @Volatile private var startedAt = 0L
    private val captured = mutableListOf<Step>()

    /** The screens passed through, which is the part that actually works. */
    private var route = listOf<ScreenJourney.Waypoint>()

    /** The screen we are on now, not yet judged worth recording. */
    private var pendingKey = ""
    private var pendingWaypoint: ScreenJourney.Waypoint? = null
    private var pendingSince = 0L

    val journey: List<ScreenJourney.Waypoint> get() = synchronized(this) { route.toList() }

    /**
     * A screen came to the front while recording.
     *
     * Called from the service when a window changes. Throttled, because a
     * single navigation fires several window events and reading the whole tree
     * for each is wasted work on someone's phone while they are trying to
     * demonstrate something.
     */
    @Synchronized
    fun noteScreen(pkg: String, nodes: List<ScreenStructure.Node>) {
        if (!recording) return
        // Our own screens are not part of anyone's route.
        //
        // The step recorder has always known this; the screen recorder did
        // not, and nobody noticed because the two are different code paths
        // that were written months apart. Every route was therefore recorded
        // starting from Agent Ultra, because the user necessarily opens Agent
        // Ultra to say "stop watching". Replaying one then tried to launch the
        // agent from inside the agent, matched its own screen as the first
        // waypoint before doing anything at all, and reported reaching a step
        // it had not taken.
        if (pkg.isBlank() || pkg == OWN_PACKAGE) return
        // No throttle here any more, and that is the point.
        //
        // Coalescing events belongs in one place, and that place is the
        // sampler, which now waits for the event stream to go quiet and reads
        // once. A second throttle here was not a safety net — it silently
        // dropped whichever racing thread arrived second, which was sometimes
        // the one carrying a screen nothing else would report. Two owners of
        // one decision is how a recording became a coin toss.
        val now = System.currentTimeMillis()
        // A screen counts once the user has left it, having stayed a while.
        //
        // Two earlier designs were wrong in opposite directions. Recording
        // every sample captured pages part-way through loading — a progress
        // bar and a reload button hash to a screen that exists for under a
        // second and never recurs, and a route built from those cannot be
        // walked. Requiring two samples of the same screen then recorded
        // almost nothing, because a screen that has settled stops producing
        // the events that would sample it: the design depended on the absence
        // it was trying to detect.
        //
        // Leaving is the reliable signal. Moving to a new screen always fires
        // an event, so the previous one can be judged then — and judged on how
        // long the user stayed, which is what separates a page someone read
        // from a frame that flickered past on the way to it.
        val seen = ScreenJourney.waypointOf(pkg, nodes) ?: return
        if (seen.key == pendingKey) return
        confirmPending()
        pendingKey = seen.key
        pendingWaypoint = seen
        pendingSince = now
    }

    /**
     * Commit the screen we were on, if the user actually stopped there.
     *
     * Called when a new screen appears and again when watching stops, because
     * the last screen of a demonstration is usually the point of it — the user
     * arrives at History and says "stop watching", and nothing else comes
     * along to push it out.
     */
    private fun confirmPending() {
        val w = pendingWaypoint ?: return
        if (System.currentTimeMillis() - pendingSince < DWELL_MS) {
            pendingWaypoint = null
            return
        }
        val before = route.size
        route = ScreenJourney.append(route, w)
        if (route.size != before) {
            // The digest is logged so two recordings of the same walk can be
            // compared. It is a hash of which view ids a screen is built from
            // and holds nothing that was on the screen.
            android.util.Log.i("UltraLearn", "screen ${route.size}: ${w.pkg}/${w.digest}")
        }
        pendingWaypoint = null
    }

    val isRecording: Boolean get() = recording

    /**
     * Begin watching.
     *
     * Drains and discards whatever is already in the buffer first. Those events
     * happened before the user asked to be watched, and keeping them would be
     * recording without being asked — the exact thing this design refuses.
     */
    @Synchronized
    fun start(): Boolean {
        // Starting again while already watching must NOT wipe what has been
        // seen. Measured: asked to stop and name a routine, the model called
        // watch_me first and then stop_watching, and a full demonstration was
        // destroyed between the two — reported back as "I did not see enough",
        // which is the worst possible answer because it blames the user for
        // the tool's mistake.
        if (recording) {
            android.util.Log.i("UltraLearn", "already watching — kept the ${captured.size} steps so far")
            return false
        }
        com.agent.ultra.AgentAccessibilityService.drainPendingLogs()
        captured.clear()
        route = emptyList()
        pendingKey = ""
        pendingWaypoint = null
        pendingSince = 0L
        startedAt = System.currentTimeMillis()
        recording = true
        android.util.Log.i("UltraLearn", "watching — nothing was kept from before this moment")
        return true
    }

    /**
     * Take everything since the last look.
     *
     * Called periodically while recording, because the buffer holds only 500
     * events and a long demonstration would otherwise lose its beginning.
     */
    @Synchronized
    fun collect() {
        if (!recording) return
        val fresh = stepsFrom(com.agent.ultra.AgentAccessibilityService.drainPendingLogs())
        for (s in fresh) {
            val prev = captured.lastOrNull()
            if (prev != null && s.vid.isNotBlank() && prev.signature == s.signature) continue
            captured.add(s)
        }
    }

    /**
     * Has enough time passed for anyone to have shown us anything?
     *
     * The catalog says to use one of these tools per request and never to start
     * and stop in the same turn. The model does it anyway — twice, watched
     * live: it called watch_me, then stop_watching, and the demonstration the
     * user was about to give never had a chance to happen.
     *
     * Words in a prompt are a request. This is the engine holding the shape:
     * nobody demonstrates a task in under a few seconds, so a stop that soon is
     * not a stop, it is the model finishing its own sentence.
     */
    val tooSoonToStop: Boolean
        get() = recording && System.currentTimeMillis() - startedAt < MIN_WATCH_MS

    /** Stop watching and hand back what was learned. */
    @Synchronized
    fun stop(): List<Step> {
        collect()
        confirmPending()   // the screen they ended on is usually the point
        recording = false
        val result = captured.toList()
        captured.clear()
        android.util.Log.i("UltraLearn", "stopped watching after ${result.size} steps")
        return result
    }

    /** Abandon a recording without keeping any of it. */
    @Synchronized
    fun cancel() {
        recording = false
        captured.clear()
        route = emptyList()
        pendingKey = ""
        pendingWaypoint = null
        pendingSince = 0L
        com.agent.ultra.AgentAccessibilityService.drainPendingLogs()
    }

    /** What has been seen so far, for showing the user mid-recording. */
    @Synchronized
    fun soFar(): List<Step> { collect(); return captured.toList() }

    private const val OWN_PACKAGE = "com.agent.ultra"
    /**
     * How long a screen must hold before it is part of a route.
     *
     * Long enough that a page mid-load does not qualify, short enough that a
     * menu someone opens and immediately taps through still does.
     */
    private const val DWELL_MS = 1500L

    private const val MAX_STEPS = 40
    private const val MIN_STEPS = 2


    /** Below this, nothing was demonstrated — the request simply came back
     * round to the model too fast. */
    private const val MIN_WATCH_MS = 6000L
}
