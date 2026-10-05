package com.agent.ultra

import com.agent.ultra.agent.ScreenStructure
import org.junit.Test

/**
 * Native app screens, captured from the phone.
 *
 * Web content and a native app are not the same problem. Chrome reports a
 * search results page as 1,907 nodes, 1,096 of them an anonymous
 * `android.view.View` with no id. The same kind of screen in a native app is
 * an order of magnitude smaller and every row carries a real view id.
 */
class NativeAppTest {

    private fun tree(name: String) = ScreenStructure.parse(
        javaClass.getResourceAsStream("/native-$name.json")!!.bufferedReader().readText()
    )

    private fun report(name: String) {
        val nodes = tree(name)
        val withId = nodes.count { it.vid.isNotBlank() }
        println("$name: ${nodes.size} nodes, $withId with a view id")
        val items = ScreenStructure.items(nodes)
        println("$name: ${items.size} records")
        items.take(6).forEach { r ->
            val f = ScreenStructure.fields(r)
            val named = f.filter { it.name != null && it.name != "title" }.map { "${it.name}=${it.value.take(20)}" }
            println("   • ${f.first().value.take(48)}  ${if (named.isEmpty()) "" else named}")
        }
    }

    @Test fun settings() = report("settings")
    @Test fun clock() = report("clock")
}

/**
 * The app's own field names.
 *
 * A native screen labels its nodes. Reading a regex over a value to work out
 * what it is, when the app already said so, is the same mistake as rebuilding
 * page structure from text statistics.
 */
class ViewIdFieldNameTest {

    private fun tree(name: String) = ScreenStructure.parse(
        javaClass.getResourceAsStream("/native-$name.json")!!.bufferedReader().readText()
    )

    @Test
    fun `alarms carry the app's own field names`() {
        val items = ScreenStructure.items(tree("clock"))
        val named = items.flatMap { r ->
            ScreenStructure.fields(r).mapNotNull { f -> f.name?.let { it to f.value } }
        }.toMap()

        // The Clock app names these itself; nothing here is inferred from text.
        org.junit.Assert.assertTrue("expected a named time", named.keys.any { it.contains("alarm_time") })
        org.junit.Assert.assertTrue("expected a named am/pm", named.keys.any { it.contains("alarm_ampm") })
        org.junit.Assert.assertTrue(
            "expected the alarm's own name",
            named.keys.any { it.contains("alarm_name") },
        )
    }

    @Test
    fun `each alarm keeps its own time`() {
        val items = ScreenStructure.items(tree("clock"))
        val morning = items.firstOrNull { r -> r.labels.any { it.contains("Morning Alarm") } }
        org.junit.Assert.assertNotNull("the Morning Alarm row must exist", morning)
        val time = ScreenStructure.fields(morning!!).firstOrNull { it.name?.contains("alarm_time") == true }
        org.junit.Assert.assertEquals("8:15", time?.value)
    }

    @Test
    fun `ids that name a slot rather than a meaning are rejected`() {
        for (junk in listOf("text", "text1", "container", "row", "tv", "t1", "layout", "", "value")) {
            org.junit.Assert.assertNull("'$junk' should not become a field name",
                ScreenStructure.fieldNameFor(junk))
        }
    }

    @Test
    fun `wrapper words are trimmed from an id`() {
        org.junit.Assert.assertEquals("alarm_time", ScreenStructure.fieldNameFor("alarm_item_time"))
        org.junit.Assert.assertEquals("price", ScreenStructure.fieldNameFor("price"))
        org.junit.Assert.assertEquals("sender_name", ScreenStructure.fieldNameFor("sender_name"))
    }
}
