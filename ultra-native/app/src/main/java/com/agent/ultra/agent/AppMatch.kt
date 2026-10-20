package com.agent.ultra.agent

/**
 * Which installed app a spoken name means.
 *
 * The old rule was "the label contains what was said". That fails the moment a
 * person says more than the label: on the Galaxy A15 "Samsung Notes" is labelled
 * just "Notes", so "open samsung notes" found nothing and the run gave up
 * (learnrun pass A, 2026-09-19). People say the maker's name, the package's
 * words, or less than the label — all of those have to land.
 *
 * Ranked, first rule that finds anything wins:
 *  1. package name equals the query
 *  2. label equals the query
 *  3. label contains the whole query                     ("calc" → "Calculator")
 *  4. the query contains the whole label, longest first  ("samsung notes" → "Notes")
 *  5. every query word is in the label or the package    ("google maps" → "Maps", com.google.android.apps.maps)
 *
 * Rule 4 takes the longest label so "google play store" picks "Play Store", not "Play".
 * Single-letter and filler words never count.
 */
object AppMatch {

    data class App(val label: String, val pkg: String)

    private val FILLER = setOf("app", "the", "my", "application", "open")

    /**
     * What a person calls a job, mapped to words the app is actually called.
     *
     * "Run the stopwatch" names no app on this phone: the stopwatch lives in Clock, and the
     * navigator failed with "no app matching 'Stopwatch'" (AndroidWorld, 2026-09-19). These are
     * hints, tried only after the plain rules find nothing — never overriding a real match.
     */
    private val FEATURE_HINTS = mapOf(
        "stopwatch" to "clock", "timer" to "clock", "alarm" to "clock", "alarms" to "clock",
        "texts" to "messages", "text" to "messages", "sms" to "messages",
        "photos" to "gallery", "pictures" to "gallery", "album" to "gallery", "albums" to "gallery",
        // Taking one is the camera's job, looking at them is the gallery's: "Take one photo" named
        // no app, "camera" didn't trace to the request, and the policy gate (rightly) refused it
        // (AndroidWorld CameraTakePhoto, 2026-09-20).
        "photo" to "camera", "picture" to "camera", "selfie" to "camera", "video" to "camera",
        "browser" to "chrome", "web" to "chrome", "internet" to "chrome",
        "dialer" to "phone", "call" to "phone", "calls" to "phone",
        "notes" to "notes", "note" to "notes", "markdown" to "markor",
        "music" to "music", "song" to "music", "songs" to "music",
        "events" to "calendar", "event" to "calendar", "schedule" to "calendar",
        "files" to "files", "downloads" to "files", "folder" to "files",
        "settings" to "settings", "preferences" to "settings",
        // What lives in Settings. On Android 13 an app can't switch Wi-Fi or Bluetooth itself: the
        // toggle tool said "off", the check said still on, and the way round — Settings — was
        // refused because "settings" isn't a word in "Turn wifi off" (AndroidWorld, 2026-09-20).
        "wifi" to "settings", "bluetooth" to "settings", "brightness" to "settings", "airplane" to "settings",
    )

    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in FILLER }.toSet()

    fun find(query: String, apps: List<App>): String? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        apps.firstOrNull { it.pkg.lowercase() == q }?.let { return it.pkg }
        apps.firstOrNull { it.label.lowercase() == q }?.let { return it.pkg }
        apps.firstOrNull { it.label.lowercase().contains(q) }?.let { return it.pkg }
        val qw = words(q)
        if (qw.isEmpty()) return null
        // Every word said is in the label or package: "google maps" -> Maps
        // (com.google.android.apps.maps). Checked before the looser rule below, which on a real
        // phone sent "google maps" to the Google app, whose label is also inside the query
        // (learnrun pass B, 2026-09-19).
        apps.firstOrNull { a -> (words(a.label) + words(a.pkg)).containsAll(qw) }?.let { return it.pkg }
        // Two apps with one label: Simple Calendar Pro and Google Calendar are both "Calendar" on
        // the launcher, and only the package tells them apart — com.simplemobiletools.calendar.pro,
        // where "simple" is part of a longer word. Every word said must be in the label or INSIDE
        // the package name. Without this "Simple Calendar Pro" opened Google Calendar
        // (AndroidWorld SimpleCalendarAddOneEvent, 2026-09-20).
        apps.filter { a -> qw.all { w -> w in words(a.label) || (w.length >= 3 && a.pkg.lowercase().contains(w)) } }
            .minByOrNull { it.pkg.length }?.let { return it.pkg }
        // The label is inside what was said; the label covering the most said words wins, then
        // the longest ("google play store" -> Play Store, not Google).
        apps.filter { a -> words(a.label).let { lw -> lw.isNotEmpty() && qw.containsAll(lw) } }
            .maxWithOrNull(compareBy<App>({ words(it.label).size }, { it.label.length }))?.let { return it.pkg }
        // Last: what the person is asking for rather than what it is called.
        for (w in qw) {
            val hint = FEATURE_HINTS[w] ?: continue
            if (hint == w) continue
            find(hint, apps)?.let { return it }
        }
        return null
    }

    /**
     * The user's own words for the app the model named, when both mean the same installed app.
     *
     * The gate traces an app_launch target to the request word for word, so a model that says
     * "Google Play Store" for "open the play store" is refused though it would open exactly the
     * app the user named (learnrun pass A, 2026-09-19). Rather than loosen the gate, the target is
     * rewritten to the request's words — then it traces, and it still opens the same package.
     * Null when no 1–3 word span of the request resolves to the package the target resolves to.
     */
    fun canonical(request: String, target: String, apps: List<App>): String? {
        val pkg = find(target, apps) ?: return null
        val label = apps.firstOrNull { it.pkg == pkg }?.let { words(it.label) } ?: emptySet()
        val toks = request.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        val spans = (1..3).flatMap { n ->
            (0..toks.size - n).map { i -> toks.subList(i, i + n) }
        }.filter { it.first() !in FILLER && it.last() !in FILLER }
            .filter { find(it.joinToString(" "), apps) == pkg }
        // The user's full name for it ("play store", not "play"), else the shortest that still
        // means the same app ("maps" out of "google maps and"). Same package either way.
        val best = spans.filter { it.toSet().containsAll(label) }.minByOrNull { it.size }
            ?: spans.minByOrNull { it.size }
        return best?.joinToString(" ")
    }
}
