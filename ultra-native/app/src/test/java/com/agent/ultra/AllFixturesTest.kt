package com.agent.ultra

import com.agent.ultra.agent.ScreenStructure
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every captured page, read end to end.
 *
 * Each of these caught a regression the others missed. A change that improves
 * a store page can quietly destroy a link aggregator — that happened, and this
 * is what would have caught it the same minute instead of two device installs
 * later.
 */
class AllFixturesTest {

    private fun read(name: String): List<ScreenStructure.Item> =
        ScreenStructure.items(
            ScreenStructure.parse(
                javaClass.getResourceAsStream("/$name.json")!!.bufferedReader().readText()
            )
        )

    private fun named(rows: List<ScreenStructure.Item>, field: String) =
        rows.count { r -> ScreenStructure.fields(r).any { it.name?.contains(field) == true } }

    @Test
    fun `hacker news gives stories that carry their own score`() {
        val rows = read("hn-front")
        assertTrue("expected ~30 stories, got ${rows.size}", rows.size in 20..40)
        val withPoints = named(rows, "points")
        assertTrue(
            "nearly every story must carry its own score, got $withPoints of ${rows.size}",
            withPoints >= rows.size - 3,
        )
    }

    @Test
    fun `a store page gives products that carry a price`() {
        val rows = read("amazon-search")
        assertTrue("expected a page of results, got ${rows.size}", rows.size >= 15)
        assertTrue("most products must carry a price", named(rows, "price") >= rows.size / 2)
    }

    @Test
    fun `the clock gives alarms named by the app itself`() {
        val rows = read("native-clock")
        assertTrue("expected the alarms, got ${rows.size}", rows.size in 3..8)
        assertTrue("the app's own time field must survive", named(rows, "alarm_time") >= 3)
    }

    @Test
    fun `the settings list gives its entries`() {
        val rows = read("native-settings")
        assertTrue("expected the app list, got ${rows.size}", rows.size >= 8)
    }

    @Test
    fun `no page returns a row that is a single stray value`() {
        for (page in listOf("hn-front", "amazon-search", "native-clock", "native-settings")) {
            val rows = read(page)
            assertTrue("$page returned nothing", rows.isNotEmpty())
            assertTrue(
                "$page returned rows with a single label — that is a field, not a record",
                rows.all { it.labels.size >= 2 },
            )
        }
    }
}
