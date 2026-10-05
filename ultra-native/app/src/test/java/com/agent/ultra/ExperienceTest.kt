package com.agent.ultra

import com.agent.ultra.agent.Experience
import com.agent.ultra.agent.Experience.Step
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceTest {

    private fun step(tool: String, params: String, ok: Boolean, result: String = if (ok) "done" else "Error: nope") =
        Step(tool, JSONObject(params), ok, result)

    @Test fun wallAndFixBecomeOneLesson() {
        val got = Experience.capture("open the calculator", listOf(
            step("app_launch", """{"target":"calculator"}""", false, "Error: no launchable app matching 'calculator'"),
            step("app_launch", """{"target":"Calculator"}""", true, "Launched Calculator"),
        ))
        assertEquals(1, got.size)
        val t = got[0].text
        assertTrue(t, t.contains("no launchable app matching 'calculator'") && t.contains("What worked: app_launch {\"target\":\"Calculator\"}"))
        assertEquals("self", got[0].source)
    }

    @Test fun sameFamilyCountsAsTheWayRound() {
        val got = Experience.capture("open my bank history", listOf(
            step("app_launch", """{"target":"History"}""", false),
            step("react_navigate", """{"goal":"open history","appHint":"Chrome"}""", true),
        ))
        assertEquals(1, got.size)
    }

    @Test fun unresolvedWallAndCleanRunTeachNothing() {
        assertTrue(Experience.capture("x", listOf(step("wifi_toggle", "{}", false))).isEmpty())
        assertTrue(Experience.capture("x", listOf(step("wifi_toggle", "{}", true))).isEmpty())
        // retrying the identical call is not a way round
        assertTrue(Experience.capture("x", listOf(step("wifi_toggle", """{"on":false}""", false),
            step("wifi_toggle", """{"on":false}""", true))).isEmpty())
    }

    @Test fun sameWallTwiceIsOneUid() {
        val a = Experience.capture("open calculator", listOf(step("app_launch", """{"target":"calc"}""", false, "Error: no launchable app matching 'calc'"), step("app_launch", """{"target":"Calculator"}""", true)))
        val b = Experience.capture("open calculator", listOf(step("app_launch", """{"target":"calcu"}""", false, "Error: no launchable app matching 'calcu'"), step("app_launch", """{"target":"Calculator"}""", true)))
        assertEquals(a[0].uid, b[0].uid)
    }

    @Test fun corrections() {
        val c = Experience.correction("open messages", "no, I meant WhatsApp")
        assertNotNull(c)
        assertTrue(c!!.text.contains("the user corrected: \"no, I meant WhatsApp\""))
        assertNull(Experience.correction("open messages", "now open the camera"))
        assertNull(Experience.correction("", "no"))
    }

    @Test fun recallFindsTheRightLessonAndSkipsBenched() {
        val calc = Experience.Lesson("ultra-self-1", "open the calculator app_launch no launchable app", "calc lesson", "self", 0)
        val wifi = Experience.Lesson("ultra-self-2", "turn wifi off wifi_toggle", "wifi lesson", "self", 0)
        assertEquals(listOf("ultra-self-1"), Experience.recall("open calculator please", listOf(calc, wifi)).map { it.uid })
        assertTrue(Experience.recall("what's the weather", listOf(calc, wifi)).isEmpty())
        val benched = calc.copy(served = 3, ok = 0, fail = 3)
        assertTrue(Experience.recall("open calculator", listOf(benched)).isEmpty())
    }

    @Test fun blockCarriesTrackRecord() {
        val l = Experience.Lesson("u", "w", "the text", "self", 0, served = 4, ok = 3, fail = 1)
        val b = Experience.block(listOf(l))!!
        assertTrue(b, b.contains("- the text (served 4x: 3 good runs, 1 bad)"))
        assertNull(Experience.block(emptyList()))
    }

    @Test fun jsonlRoundTripsInNorthstarsFormat() {
        val l = Experience.Lesson("ultra-self-abc", "open calculator", "calc lesson", "self", 0)
        val line = Experience.toJsonl(l)
        val o = JSONObject(line)
        assertEquals("lesson", o.getString("kind"))
        assertEquals("open calculator", o.getJSONArray("when").getString(0))
        val back = Experience.fromJsonl(line)!!
        assertEquals("ultra-self-abc", back.uid)
        assertEquals("northstar", back.source)
        assertNull(Experience.fromJsonl("""{"uid":"Bad UID!","when":["x"],"text":"y"}"""))
        assertNull(Experience.fromJsonl("not json"))
    }

    @Test fun noFixLessonWhenTheRunNeverFinished() {
        val steps = listOf(step("react_navigate", """{"goal":"wifi"}""", false), step("app_launch", """{"target":"Settings"}""", true))
        assertTrue(Experience.capture("open wifi settings", steps, finished = false).isEmpty())
        assertEquals(1, Experience.capture("open wifi settings", steps, finished = true).size)
    }

    @Test fun sameCallFailingTwiceIsADeadEnd() {
        val nav = """{"goal":"open WiFi settings","appHint":"Settings"}"""
        val got = Experience.capture("open the wifi settings page", listOf(
            step("react_navigate", nav, false, "Error: navigation incomplete after 15 steps"),
            step("react_navigate", nav, false, "Error: navigation incomplete after 15 steps"),
        ), finished = false)
        assertEquals(1, got.size)
        assertTrue(got[0].uid, got[0].uid.startsWith("ultra-deadend-"))
        assertTrue(got[0].text.contains("failed twice") && got[0].text.contains("settings_open"))
    }

    @Test fun aLongGoalStillFindsItsLesson() {
        val lesson = Experience.Lesson("u", "create a new note or file inside an app", "tap the unlabelled + button", "northstar", 0)
        val hits = Experience.recall(
            "Create a new note in Markor named 2023_01_26_wise_yacht.md with the following text: Ignorance is bliss.",
            listOf(lesson))
        assertEquals(listOf("u"), hits.map { it.uid })
    }

    @Test fun anUnrelatedGoalStillFindsNothing() {
        val lesson = Experience.Lesson("u", "create a new note or file inside an app", "tap the unlabelled + button", "northstar", 0)
        assertTrue(Experience.recall("Turn wifi off and tell me the battery level", listOf(lesson)).isEmpty())
    }

    @Test fun oneSharedWordIsNotEnough() {
        val lesson = Experience.Lesson("u", "create a new note or file inside an app", "x", "northstar", 0)
        assertTrue(Experience.recall("Create a playlist called road trip in Retro Music", listOf(lesson)).isEmpty())
    }

    // A run Ultra called done that an outside check found not done (AndroidWorld, 2026-09-20).
    @Test fun aFailedVerdictNamesTheApproachThatDidNotDoTheJob() {
        val l = Experience.verdict("Create a timer with 0 hours, 16 minutes, and 35 seconds.", listOf(
            step("note_create", """{"text":"Timer"}""", true),
            step("alarm_set", """{"hour":0,"minute":35}""", true),
        ))
        assertNotNull(l)
        assertEquals("verdict", l!!.source)
        assertTrue(l.text, l.text.contains("note_create → alarm_set") && l.text.contains("NOT done") && l.text.contains("react_navigate"))
        // A different seed of the same task must find it.
        assertEquals(1, Experience.recall("Create a timer with 1 hours, 5 minutes, and 20 seconds. Do not start the timer.", listOf(l)).size)
    }

    @Test fun aVerdictOnARunWhereEveryCallFailedTeachesNothingNew() {
        assertNull(Experience.verdict("Run the stopwatch.", listOf(step("app_launch", """{"target":"x"}""", false))))
    }

    @Test fun answeringInWordsWhenTheJobWasToDoSomethingIsALesson() {
        val l = Experience.verdict("Delete the file calm_owl_edited.mp3 from the Ringtones folder.", emptyList())!!
        assertTrue(l.text, l.text.contains("without doing anything") && l.text.contains("react_navigate"))
        assertEquals(1, Experience.recall("Delete the file q2a8_fancy_banana.mp3 from the Notifications folder.", listOf(l)).size)
    }

    @Test fun aRouteFromNorthstarKeepsItsStepsAndIsFoundByADifferentSeed() {
        val steps = (1..12).joinToString("\n") { "$it. tap \"Button number $it with a long label\"" }
        val line = JSONObject().put("uid", "route-markor-create-note").put("kind", "route")
            .put("when", org.json.JSONArray(listOf("Create a new note in Markor named <file_name> with the following text: <text>", "MarkorCreateNote")))
            .put("text", "A run that PASSED a check of the device (1x):\n$steps").toString()
        val l = Experience.fromJsonl(line)!!
        assertEquals(Experience.ROUTE, l.source)
        assertTrue("a route longer than a sentence must survive import", l.text.contains("Button number 12"))
        assertEquals(1, Experience.recall("Create a new note in Markor named 2023_02_03_shy_frog.md with the following text: Carpe diem.", listOf(l)).size)
    }

    @Test fun aVerdictLessonIsJudgedOnTheApproachItWarnedAboutNotOnTheWholeTask() {
        val l = Experience.verdict("Create a timer with 0 hours, 16 minutes, and 35 seconds.", listOf(
            step("note_create", """{"text":"Timer"}""", true), step("alarm_set", """{"hour":0}""", true)))!!
        assertEquals(false, Experience.verdictHeld(l, listOf(
            step("note_create", """{"text":"T"}""", true), step("alarm_set", """{"hour":1}""", true))))
        assertEquals(true, Experience.verdictHeld(l, listOf(step("react_navigate", """{"goal":"set timer"}""", false))))
        assertNull(Experience.verdictHeld(Experience.Lesson("u", "w", "t", "self", 0), emptyList()))
    }
}
