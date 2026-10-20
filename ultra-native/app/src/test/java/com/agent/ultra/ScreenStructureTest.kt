package com.agent.ultra

import com.agent.ultra.agent.ScreenStructure
import com.agent.ultra.agent.ScreenStructure.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field naming.
 *
 * The rule under test is not "name as much as possible". It is "never name
 * something wrong" — a named field gets trusted by the model, so a bad name
 * turns into a confident wrong answer about what something costs.
 */
class ScreenStructureTest {

    private fun row(vararg labels: String) = Item(labels.toList(), top = 0, clickable = true)

    private fun named(item: Item): Map<String?, List<String>> =
        ScreenStructure.fields(item).groupBy({ it.name }, { it.value })

    @Test
    fun `first label is the title`() {
        val f = ScreenStructure.fields(row("Sony WH-1000XM5", "$348.00"))
        assertEquals("title", f.first().name)
        assertEquals("Sony WH-1000XM5", f.first().value)
    }

    @Test
    fun `price rating and reviews are each named`() {
        val f = named(row(
            "Sony WH-1000XM5 Wireless Headphones",
            "$348.00",
            "4.4 out of 5 stars",
            "12,847 ratings",
        ))
        assertEquals(listOf("$348.00"), f["price"])
        assertEquals(listOf("4.4 out of 5 stars"), f["rating"])
        assertEquals(listOf("12,847 ratings"), f["reviews"])
    }

    @Test
    fun `points are named on a link aggregator`() {
        val f = named(row("Show HN: I built a thing", "412 points", "by someone 3 hours ago"))
        assertEquals(listOf("412 points"), f["points"])
    }

    @Test
    fun `a rating line that also carries a count is a rating, not a review count`() {
        // "4.5 out of 5 stars, 1,234 ratings" arrives as one label on several
        // real layouts. Naming it "reviews" would file the rating under the
        // wrong field.
        val f = named(row("Thing", "4.5 out of 5 stars, 1,234 ratings"))
        assertEquals(listOf("4.5 out of 5 stars, 1,234 ratings"), f["rating"])
        assertNull(f["reviews"])
    }

    @Test
    fun `text matching two unrelated patterns is left unnamed`() {
        // A price and a review count in one line is ambiguous about which the
        // line is for. Unnamed is the honest outcome.
        val f = named(row("Thing", "$19.99 · 240 reviews"))
        assertTrue("should not be named price", f["price"] == null)
        assertTrue("should not be named reviews", f["reviews"] == null)
        assertEquals(listOf("$19.99 · 240 reviews"), f[null])
    }

    @Test
    fun `unrecognised text keeps its value with no name`() {
        val f = named(row("Thing", "Delivery Tuesday", "Colour: Midnight Black"))
        assertEquals(listOf("Delivery Tuesday", "Colour: Midnight Black"), f[null])
    }

    @Test
    fun `a bare number is not a review count`() {
        val f = named(row("Thing", "1,234"))
        assertEquals(listOf("1,234"), f[null])
    }

    @Test
    fun `a number above five is not a rating`() {
        val f = named(row("Thing", "48 out of 5"))
        assertNull(f["rating"])
    }

    @Test
    fun `two prices in one row are listed and flagged, never picked`() {
        val (out, shown) = ScreenStructure.render(
            listOf(row("Kettle", "$99.99", "$79.99")),
            budget = 4000,
        )
        assertEquals(1, shown)
        assertTrue("both prices must survive", out.contains("$99.99") && out.contains("$79.99"))
        assertTrue("ambiguity must be stated", out.contains("do not assume which is charged"))
    }

    @Test
    fun `a single price is named plainly`() {
        val (out, _) = ScreenStructure.render(listOf(row("Kettle", "$79.99")), budget = 4000)
        assertTrue(out.contains("price: $79.99"))
        assertTrue("no ambiguity warning for one price", !out.contains("do not assume"))
    }

    @Test
    fun `render stops at the budget instead of truncating a row`() {
        val rows = (1..50).map { row("Item $it", "$${it}.00") }
        val (out, shown) = ScreenStructure.render(rows, budget = 120)
        assertTrue("must stop early", shown in 1..49)
        assertTrue("output stays within budget", out.length <= 120)
    }

    @Test
    fun `a sponsored badge is named, so an ad is not mistaken for the best buy`() {
        val f = named(row("Cheap Thing", "Sponsored", "$9.99"))
        assertEquals(listOf("Sponsored"), f["sponsored"])
        assertEquals(listOf("$9.99"), f["price"])
    }

    @Test
    fun `a word merely containing ad is not a sponsored badge`() {
        // "ad" as a substring hits Adapter and Radio. The badge stands alone.
        val f = named(row("Thing", "USB Adapter", "Radio"))
        assertNull(f["sponsored"])
        assertEquals(listOf("USB Adapter", "Radio"), f[null])
    }

    @Test
    fun `out of stock is named, so a price on it is not the cheapest option`() {
        val f = named(row("Thing", "$4.99", "Currently unavailable"))
        assertEquals(listOf("Currently unavailable"), f["availability"])
    }

    @Test
    fun `in stock is named too`() {
        val f = named(row("Thing", "In stock", "Arrives Tuesday"))
        assertEquals(listOf("In stock", "Arrives Tuesday"), f["availability"])
    }

    @Test
    fun `an empty row yields no fields`() {
        assertEquals(emptyList<ScreenStructure.Field>(), ScreenStructure.fields(row()))
    }
}

/**
 * Container selection.
 *
 * Picking the wrong container is the failure that produced "Unknown product"
 * and a sign-in row where a product grid should have been. The winning signal
 * is uniformity times substance, never area — virtualised rows report zero
 * bounds, so an area score picks the page banner every time.
 *
 * These build the flat tree by hand so the heuristic can be proven without a
 * phone.
 */
class ScreenContainerTest {

    /** Build the flat JSON that [ScreenStructure.parse] consumes. */
    private class Tree {
        private val rows = mutableListOf<String>()
        var next = 0; private set

        fun add(parent: Int, text: String, top: Int = 0, clickable: Boolean = false): Int {
            val i = next++
            rows.add("""{"i":$i,"p":$parent,"dep":0,"t":${quote(text)},"d":"","c":$clickable,"tp":$top,"b":${top + 10}}""")
            return i
        }

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun json() = rows.joinToString(",", "[", "]")
        fun nodes() = ScreenStructure.parse(json())
    }

    @Test
    fun `a product grid beats a bigger navigation menu`() {
        val t = Tree()
        val root = t.add(-1, "")
        // 14 nav entries of one word each — more children, far less substance.
        val nav = t.add(root, "")
        repeat(14) {
            val entry = t.add(nav, "")
            t.add(entry, "Dept $it")
            t.add(entry, "›")
        }
        // 5 products, each carrying a title, a price and a rating.
        val grid = t.add(root, "")
        repeat(5) {
            val card = t.add(grid, "", top = 100 + it * 50, clickable = true)
            t.add(card, "Product $it")
            t.add(card, "$${it + 10}.99")
            t.add(card, "4.${it} out of 5 stars")
            t.add(card, "${it * 100 + 7} ratings")
        }

        val items = ScreenStructure.items(t.nodes())
        assertEquals("should pick the 5-product grid", 5, items.size)
        assertTrue(items.all { it.labels.any { l -> l.startsWith("Product ") } })
    }

    @Test
    fun `the eleven-product page still wins - the case proven on device`() {
        // This is the layout validated on a live Amazon page before the
        // scoring changed. Keeping it means a future tweak to the heuristic
        // cannot quietly undo what was proven on hardware.
        val t = Tree()
        val root = t.add(-1, "")
        val nav = t.add(root, "")
        repeat(14) {
            val entry = t.add(nav, "")
            t.add(entry, "Dept $it")
            t.add(entry, "\u203a")
        }
        val grid = t.add(root, "")
        repeat(11) {
            val card = t.add(grid, "", top = 100 + it * 50, clickable = true)
            t.add(card, "Wireless Noise Cancelling Headphones model $it")
            t.add(card, "$${it + 10}.99")
            t.add(card, "4.${it % 6} out of 5 stars")
        }
        assertEquals(11, ScreenStructure.items(t.nodes()).size)
    }

    @Test
    fun `a long list of short entries loses to a short list of substantial rows`() {
        // The general form of the bug: count is linear, so without measuring
        // how much text a row carries, any long shallow menu wins.
        val t = Tree()
        val root = t.add(-1, "")
        val menu = t.add(root, "")
        repeat(30) {
            val entry = t.add(menu, "")
            t.add(entry, "Tag$it")
            t.add(entry, "·")
        }
        val list = t.add(root, "")
        repeat(3) {
            val row = t.add(list, "", top = it * 50)
            t.add(row, "A headline that carries real content number $it")
            t.add(row, "412 points by someone 3 hours ago")
            t.add(row, "128 comments")
        }
        val items = ScreenStructure.items(t.nodes())
        assertEquals(3, items.size)
    }

    @Test
    fun `a screen with no repeating list returns nothing`() {
        val t = Tree()
        val root = t.add(-1, "")
        t.add(root, "Settings")
        val a = t.add(root, ""); t.add(a, "Wi-Fi"); t.add(a, "Connected")
        val b = t.add(root, ""); t.add(b, "About this phone")
        assertTrue(ScreenStructure.items(t.nodes()).isEmpty())
    }

    @Test
    fun `rows come back in reading order, which is tree order`() {
        // This test used to assert screen order, sorting by `top`. Real pages
        // disproved that: bounds are only trustworthy for what is currently
        // visible, and rows above and below the viewport report stale or
        // identical tops. Sorting a link aggregator's 60 rows by `top` put 14
        // correct pairs first and then a block of 17 scores with no headlines
        // beside them, and the pairing collapsed.
        //
        // The tree arrives in reading order. That is what a list means, and it
        // does not change when the user scrolls.
        val t = Tree()
        val root = t.add(-1, "")
        val list = t.add(root, "")
        for (top in listOf(300, 100, 200)) {
            val row = t.add(list, "", top = top)
            t.add(row, "Row at $top")
            t.add(row, "detail")
        }
        val items = ScreenStructure.items(t.nodes())
        assertEquals(
            "rows keep the order the tree gave them",
            listOf("Row at 300", "Row at 100", "Row at 200"),
            items.map { it.labels.first() },
        )
    }

    @Test
    fun `a title repeated across image, heading and link appears once`() {
        val t = Tree()
        val root = t.add(-1, "")
        val list = t.add(root, "")
        repeat(3) {
            val row = t.add(list, "", top = it * 50)
            t.add(row, "Same Title $it")   // image alt
            t.add(row, "Same Title $it")   // heading
            t.add(row, "Same Title $it")   // anchor
            t.add(row, "$${it}9.99")
        }
        val items = ScreenStructure.items(t.nodes())
        assertEquals(3, items.size)
        items.forEachIndexed { i, item ->
            assertEquals("title should not repeat", 1, item.labels.count { it == "Same Title $i" })
        }
    }

    @Test
    fun `tracking parameters and opaque ids are not fields`() {
        val t = Tree()
        val root = t.add(-1, "")
        val list = t.add(root, "")
        repeat(3) {
            val row = t.add(list, "", top = it * 50)
            t.add(row, "Real Title $it")
            t.add(row, "ref=sr_pg_1_$it")
            t.add(row, "https://example.com/dp/B0${it}XYZ")
            t.add(row, "$${it}9.99")
        }
        val items = ScreenStructure.items(t.nodes())
        assertEquals(3, items.size)
        assertTrue(
            "junk must be filtered",
            items.flatMap { it.labels }.none { it.startsWith("ref=") || it.startsWith("http") },
        )
    }

    @Test
    fun `a protected screen yields no nodes`() {
        val nodes = ScreenStructure.parse(com.agent.ultra.AgentAccessibilityService.PROTECTED)
        assertTrue(nodes.isEmpty())
        assertTrue(ScreenStructure.items(nodes).isEmpty())
    }

    @Test
    fun `malformed json is empty, not a crash`() {
        assertTrue(ScreenStructure.parse("not json at all").isEmpty())
        assertTrue(ScreenStructure.parse("").isEmpty())
    }
}

/**
 * Joining rows that are two halves of one thing.
 *
 * Read off a live Hacker News page: each story is two sibling table rows, the
 * title in one and "120 points by tosh 1 hour ago" in the next. Grouping by
 * container produced two separate items, so the model paired a score to a
 * headline by adjacency — the exact guess this file exists to remove.
 */
class SplitRowTest {

    private fun row(vararg labels: String) =
        ScreenStructure.Item(labels.toList(), top = 0, clickable = false)

    /** Two rows per story, the way the page reports them. */
    private fun hackerNews(count: Int) = (1..count).flatMap {
        listOf(
            row("$it.", "Story number $it (example.com)"),
            row("${it * 10} points by someone $it hours ago", "$it comments"),
        )
    }

    @Test
    fun `a title row and its points row become one item`() {
        val merged = ScreenStructure.mergeSplitRows(hackerNews(4))
        assertEquals(4, merged.size)
        merged.forEachIndexed { i, item ->
            val n = i + 1
            assertTrue("story $n keeps its title", item.labels.any { it.contains("Story number $n") })
            assertTrue("story $n keeps its own score", item.labels.any { it.contains("${n * 10} points") })
        }
    }

    @Test
    fun `a product grid where every row has a price is left alone`() {
        // Every card carries a price, so there is no heading-half to pair with.
        // Merging here would join two unrelated products.
        val grid = (1..6).map { row("Product $it", "$$it.99", "4.5 out of 5 stars") }
        assertEquals(6, ScreenStructure.mergeSplitRows(grid).size)
    }

    @Test
    fun `a list where only some rows carry metadata is left alone`() {
        // Alternation has to hold all the way down. A half-matching list is a
        // coincidence, not a structure.
        val mixed = listOf(
            row("Title A", "detail"),
            row("10 points by x", "1 comment"),
            row("Title B", "detail"),
            row("Title C", "detail"),
        )
        assertEquals(4, ScreenStructure.mergeSplitRows(mixed).size)
    }

    @Test
    fun `too short a list is never merged`() {
        val two = hackerNews(1)
        assertEquals(2, ScreenStructure.mergeSplitRows(two).size)
    }

    @Test
    fun `an odd trailing row is kept, not dropped`() {
        val rows = hackerNews(2) + row("More", "link to page 2")
        val merged = ScreenStructure.mergeSplitRows(rows)
        // Two merged stories, plus the unpaired "More" link kept as it stands.
        assertEquals(3, merged.size)
        assertTrue(merged.last().labels.contains("More"))
    }

    @Test
    fun `merged rows render with the score named against the right title`() {
        val merged = ScreenStructure.mergeSplitRows(hackerNews(3))
        val (out, _) = ScreenStructure.render(merged, budget = 4000)
        // Each numbered block must contain its own title and its own score.
        val blocks = out.split(Regex("(?m)^\\d+\\. ")).filter { it.isNotBlank() }
        assertEquals(3, blocks.size)
        blocks.forEachIndexed { i, b ->
            val n = i + 1
            assertTrue("block $n has its title", b.contains("Story number $n"))
            assertTrue("block $n has its score", b.contains("${n * 10} points"))
        }
    }
}

/** The title is the headline, not the rank marker in front of it. */
class TitleAndRedundancyTest {

    private fun row(vararg labels: String) =
        ScreenStructure.Item(labels.toList(), top = 0, clickable = false)

    private fun named(item: ScreenStructure.Item) =
        ScreenStructure.fields(item).groupBy({ it.name }, { it.value })

    @Test
    fun `a leading rank marker does not become the title`() {
        val f = named(row("1.", "GUIs should be fully keyboard-driven", "881 points"))
        assertEquals(listOf("GUIs should be fully keyboard-driven"), f["title"])
        assertEquals(listOf("1."), f["rank"])
    }

    @Test
    fun `the rendered headline is the story, not the number`() {
        val (out, _) = ScreenStructure.render(
            listOf(row("2.", "Htmx 4.0", "708 points")),
            budget = 4000,
        )
        assertTrue("headline must be the story", out.startsWith("1. Htmx 4.0"))
        assertTrue("the rank is kept as a field", out.contains("rank: 2."))
    }

    @Test
    fun `rank markers in several shapes are all recognised`() {
        for (marker in listOf("1.", "12)", "3", "100.")) {
            val f = named(row(marker, "A headline", "5 points"))
            assertEquals("$marker should be a rank", listOf("A headline"), f["title"])
        }
    }

    @Test
    fun `a row that is all ordinals still yields a title`() {
        val f = named(row("1.", "2."))
        assertEquals(listOf("1."), f["title"])
    }

    @Test
    fun `a long line restating a short one is dropped`() {
        // Hacker News reports the score twice: on its own, and inside the
        // whole subtext line. Both name themselves points.
        val f = named(row(
            "Iceland votes on whether to restart talks",
            "123 points by tosh 1 hour ago | hide | 127 comments",
            "123 points",
        ))
        assertEquals("only the specific score survives", listOf("123 points"), f["points"])
    }

    @Test
    fun `two genuinely different values both survive`() {
        // Not redundancy — a row really can show two prices.
        val f = named(row("Kettle", "$99.99", "$79.99"))
        assertEquals(listOf("$99.99", "$79.99"), f["price"])
    }
}

/**
 * Separator labels carry nothing and must not reach the model.
 *
 * A regex over `[\p{Punct}\s]` was not enough: the page emits "|" wrapped in
 * non-breaking spaces, which Java's `\s` does not match and Kotlin's `trim()`
 * does not remove, so it survived every filter and reached the model as a
 * field of its own.
 */
class SeparatorFilterTest {

    private fun labelsOf(vararg labels: String): List<String> {
        val t = StringBuilder("[")
        // one container, N children each holding one label
        t.append("""{"i":0,"p":-1,"dep":0,"t":"","d":"","c":false,"tp":0,"b":10}""")
        var i = 1
        val rows = mutableListOf<String>()
        repeat(3) { r ->
            val row = i++
            rows.add("""{"i":$row,"p":0,"dep":1,"t":"","d":"","c":true,"tp":${r * 50},"b":${r * 50 + 10}}""")
            for (l in labels) {
                rows.add("""{"i":${i++},"p":$row,"dep":2,"t":"${l.replace("\"", "\\\"")}","d":"","c":false,"tp":${r * 50},"b":${r * 50 + 10}}""")
            }
        }
        t.append(",").append(rows.joinToString(","))
        t.append("]")
        return ScreenStructure.items(ScreenStructure.parse(t.toString()))
            .firstOrNull()?.labels ?: emptyList()
    }

    @Test
    fun `a pipe wrapped in non-breaking spaces is dropped`() {
        val labels = labelsOf("A real headline here", " | ", "123 points")
        assertFalse("the separator must not survive", labels.any { it.contains("|") })
        assertTrue(labels.any { it.contains("A real headline") })
    }

    @Test
    fun `plain separators are dropped`() {
        val labels = labelsOf("A real headline here", "|", "(", ")", "·", "123 points")
        assertEquals(listOf("A real headline here", "123 points"), labels)
    }

    @Test
    fun `a bare query string is dropped`() {
        val labels = labelsOf("A real headline here", "vote?id=49489057&how=up&goto=news", "123 points")
        assertFalse(labels.any { it.startsWith("vote?") })
    }

    @Test
    fun `real short text is kept`() {
        // Not everything short is noise: a currency amount or a count matters.
        val labels = labelsOf("A real headline here", "$5", "4.5", "123 points")
        assertTrue(labels.contains("$5"))
        assertTrue(labels.contains("4.5"))
    }
}
