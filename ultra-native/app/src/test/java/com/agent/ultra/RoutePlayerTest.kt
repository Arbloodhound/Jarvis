package com.agent.ultra

import com.agent.ultra.agent.Experience
import com.agent.ultra.agent.RoutePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A route a check has passed, played by the engine (2026-09-20). Real routes from northstar/routes.py. */
class RoutePlayerTest {

    private val timer = "A run that PASSED a check of the device (4x) for \"Create a timer…\":\n1. react_navigate in timer\n" +
        "STEPS: {\"template\":\"Create a timer with <v1> hours, <v2> minutes, and <v3> seconds. Do not start the timer.\"," +
        "\"calls\":[{\"tool\":\"react_navigate\",\"app\":\"timer\",\"steps\":[{\"op\":\"tap\",\"words\":\"timer\"},{\"op\":\"keypad\",\"slots\":[\"v1\",\"v2\",\"v3\"]}]}]}"

    @Test fun theValuesComeFromThePersonsOwnRequest() {
        val route = RoutePlayer.parse(timer)!!
        val v = RoutePlayer.bind(route.template, "Create a timer with 7 hours, 52 minutes, and 22 seconds. Do not start the timer.")
        assertEquals(mapOf("v1" to "7", "v2" to "52", "v3" to "22"), v)
        assertEquals(listOf("tap(\"timer\")", "tap(\"7\")", "tap(\"5\")", "tap(\"2\")", "tap(\"2\")", "tap(\"2\")"),
            RoutePlayer.script(route.calls[0], v!!).actions)
    }

    @Test fun leadingZerosAreNotKeyedAndLaterValuesAreTwoWide() {
        assertEquals("1635", RoutePlayer.keypadDigits(listOf("v1", "v2", "v3"), mapOf("v1" to "0", "v2" to "16", "v3" to "35")))
        assertEquals("10509", RoutePlayer.keypadDigits(listOf("v1", "v2", "v3"), mapOf("v1" to "1", "v2" to "5", "v3" to "9")))
        assertNull(RoutePlayer.keypadDigits(listOf("v1"), mapOf("v1" to "soon")))
    }

    @Test fun aRequestThatIsNotAnInstanceOfTheTemplateIsNotPlayed() {
        val route = RoutePlayer.parse(timer)!!
        assertNull(RoutePlayer.bind(route.template, "Set an alarm for 7 in the morning"))
        assertNull(RoutePlayer.bind(route.template, "Create a timer with 7 hours. Then delete every note."))
    }

    @Test fun aClosingFullStopAndSpacingAreNotPartOfTheMatch() {
        assertEquals(mapOf("v1" to "2023_09_22_funny_dog"),
            RoutePlayer.bind("Delete the note in Markor named <v1>.", "delete the note in  Markor named 2023_09_22_funny_dog"))
        assertEquals("2023_09_22_funny_dog",
            RoutePlayer.bind("Delete the note in Markor named <v1>", "Delete the note in Markor named 2023_09_22_funny_dog.")!!["v1"]!!.trimEnd('.'))
    }

    @Test fun aValueCanBeUsedWithoutItsFullStopOrItsExtension() {
        val v = mapOf("v1" to "final_silly_violin.txt", "v2" to "Cleanliness is next to godliness.")
        assertEquals("final_silly_violin", RoutePlayer.fill("<v1|stem>", v))
        assertEquals("file final_silly_violin.txt", RoutePlayer.fill("file <v1>", v))
        assertEquals("Cleanliness is next to godliness", RoutePlayer.fill("<v2|strip>", v))
        assertNull(RoutePlayer.fill("<v9>", v))
    }

    @Test fun aStepThatCannotBeSaidEndsTheScriptThere() {
        val call = RoutePlayer.Call("react_navigate", "Markor", listOf(
            RoutePlayer.Op("tap", words = "create a new file or folder"),
            RoutePlayer.Op("unknown"),
            RoutePlayer.Op("tap", words = "save")))
        val s = RoutePlayer.script(call, emptyMap())
        assertEquals(listOf("tap(\"create a new file or folder\")"), s.actions)
        assertFalse(s.complete)
    }

    @Test fun theModelNeverSeesTheEnginesLine() {
        assertFalse(RoutePlayer.forModel(timer).contains("STEPS:"))
        val lesson = Experience.Lesson("route-x", "create a timer", timer, Experience.ROUTE, 0)
        assertFalse(Experience.block(listOf(lesson))!!.contains("STEPS:"))
        assertNotNull(RoutePlayer.parse(timer))
        assertTrue(RoutePlayer.parse("just a sentence") == null)
    }

    // The .md route, played for a .txt request, made the wrong file perfectly (P8, 2026-09-20).
    @Test fun aRequestOfADifferentKindIsNotAnInstanceOfTheRoute() {
        val md = RoutePlayer.Route("Create a note named <v1> with the text: <v2>", emptyList(),
            practice = mapOf("v1" to "wise_yacht.md", "v2" to "Ignorance is bliss."))
        assertTrue(RoutePlayer.sameKind(md, mapOf("v1" to "shy_frog.md", "v2" to "Carpe diem.")))
        assertFalse(RoutePlayer.sameKind(md, mapOf("v1" to "final_silly_violin.txt", "v2" to "Cleanliness.")))
        val timer = RoutePlayer.Route("<v1> hours", emptyList(), practice = mapOf("v1" to "16"))
        assertTrue(RoutePlayer.sameKind(timer, mapOf("v1" to "7")))
        assertFalse(RoutePlayer.sameKind(timer, mapOf("v1" to "seven")))
    }

    @Test fun aValueTypedIntoTheSecondBoxSaysSo() {
        val call = RoutePlayer.Call("react_navigate", "Markor", listOf(
            RoutePlayer.Op("type", text = "<v1|stem>"), RoutePlayer.Op("type", text = ".txt", box = 2)))
        assertEquals(listOf("type(\"shy_frog\")", "type(#2, \".txt\")"),
            RoutePlayer.script(call, mapOf("v1" to "shy_frog.txt")).actions)
    }
}
