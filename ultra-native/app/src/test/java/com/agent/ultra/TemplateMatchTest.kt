package com.agent.ultra

import com.agent.ultra.agent.ScreenStructure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the records by the page's own repeating template.
 *
 * The statistical method this replaces had to be corrected twice, and on a
 * live store page it returned eighteen rows from eleven screens — mixing
 * whole product cards with orphaned fragments of other cards. A results page
 * is one template repeated, and the accessibility tree says so directly once
 * class names are carried through.
 */
class TemplateMatchTest {

    /** Builds the flat tree, now with class names. */
    private class Tree {
        private val rows = mutableListOf<String>()
        private var next = 0

        fun add(parent: Int, text: String, cls: String, top: Int = 0, vid: String = ""): Int {
            val i = next++
            rows.add(
                """{"i":$i,"p":$parent,"dep":0,"t":${q(text)},"d":"","c":false,""" +
                    """"tp":$top,"b":${top + 40},"cls":${q(cls)},"vid":${q(vid)}}"""
            )
            return i
        }

        private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun nodes() = ScreenStructure.parse(rows.joinToString(",", "[", "]"))
    }

    /** A store page: real product cards, plus loose fragments that share a
     * parent with them but are not products. */
    private fun storePage(products: Int, fragments: Int): Tree {
        val t = Tree()
        val root = t.add(-1, "", "FrameLayout")
        val list = t.add(root, "", "RecyclerView")
        repeat(products) {
            val card = t.add(list, "", "ViewGroup", top = 100 + it * 200)
            t.add(card, "Wireless Headphones model $it with long descriptive name", "TextView")
            t.add(card, "$${20 + it}.99", "TextView")
            t.add(card, "4.${it % 6} out of 5 stars", "TextView")
        }
        // Fragments: same parent, different shape, still carrying text.
        repeat(fragments) {
            val frag = t.add(list, "", "LinearLayout", top = 5000 + it * 50)
            t.add(frag, "Rated 4.4 out of 5 stars by 88506 reviews.", "TextView")
        }
        return t
    }

    @Test
    fun `product cards are found and fragments are excluded`() {
        val items = ScreenStructure.items(storePage(products = 12, fragments = 6).nodes())
        assertEquals("only the repeated card template counts", 12, items.size)
        assertTrue(
            "every record is a product",
            items.all { row -> row.labels.any { it.startsWith("Wireless Headphones model") } },
        )
    }

    @Test
    fun `every product keeps its own price`() {
        val items = ScreenStructure.items(storePage(products = 8, fragments = 3).nodes())
        items.forEachIndexed { i, row ->
            val n = row.labels.first { it.startsWith("Wireless Headphones model") }
                .removePrefix("Wireless Headphones model ").substringBefore(" ").toInt()
            assertTrue(
                "product $n must carry its own price",
                row.labels.contains("$${20 + n}.99"),
            )
        }
    }

    @Test
    fun `a view id wins over the class when the app provides one`() {
        val t = Tree()
        val root = t.add(-1, "", "FrameLayout")
        val list = t.add(root, "", "ListView")
        // Same class, different ids: these are different kinds of thing.
        repeat(5) {
            val row = t.add(list, "", "ViewGroup", top = it * 100, vid = "result_row")
            t.add(row, "Result title number $it here", "TextView", vid = "title")
            t.add(row, "$${it}9.99", "TextView", vid = "price")
        }
        repeat(4) {
            val ad = t.add(list, "", "ViewGroup", top = 900 + it * 100, vid = "promo_banner")
            t.add(ad, "Promoted banner content $it", "TextView", vid = "promo_text")
        }
        val items = ScreenStructure.items(t.nodes())
        assertEquals(5, items.size)
        assertTrue(items.all { row -> row.labels.any { it.startsWith("Result title") } })
    }

    @Test
    fun `a card and an identical block nested inside it are not both records`() {
        val t = Tree()
        val root = t.add(-1, "", "FrameLayout")
        val list = t.add(root, "", "RecyclerView")
        repeat(5) {
            val outer = t.add(list, "", "ViewGroup", top = it * 200)
            val inner = t.add(outer, "", "ViewGroup", top = it * 200)
            t.add(inner, "Nested product title number $it", "TextView")
            t.add(inner, "$${it}4.99", "TextView")
        }
        val items = ScreenStructure.items(t.nodes())
        assertEquals("each product counted once, not twice", 5, items.size)
    }

    @Test
    fun `a tree with no class names falls back and still works`() {
        // An older build's dump carries no "cls". The previous method must
        // still run rather than the reader returning nothing.
        val rows = mutableListOf(
            """{"i":0,"p":-1,"dep":0,"t":"","d":"","c":false,"tp":0,"b":10}"""
        )
        var i = 1
        repeat(5) { r ->
            val row = i++
            rows.add("""{"i":$row,"p":0,"dep":1,"t":"","d":"","c":true,"tp":${r * 50},"b":${r * 50 + 40}}""")
            rows.add("""{"i":${i++},"p":$row,"dep":2,"t":"Product title number $r","d":"","c":false,"tp":${r * 50},"b":${r * 50 + 20}}""")
            rows.add("""{"i":${i++},"p":$row,"dep":2,"t":"$${r}9.99","d":"","c":false,"tp":${r * 50},"b":${r * 50 + 40}}""")
        }
        val items = ScreenStructure.items(ScreenStructure.parse(rows.joinToString(",", "[", "]")))
        assertEquals(5, items.size)
    }
}
