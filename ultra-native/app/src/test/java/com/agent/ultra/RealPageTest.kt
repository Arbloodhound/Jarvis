package com.agent.ultra

import com.agent.ultra.agent.ScreenStructure
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real page, captured from the phone.
 *
 * `amazon-search.json` is the accessibility tree of an Amazon search results
 * page exactly as Chrome exposes it — 1,907 nodes. Synthetic trees pin
 * behaviour; only a real one shows what a page is actually like, and this one
 * showed three things no synthetic test had:
 *
 *  - Chrome reports web content as 1,096 identical `android.view.View` nodes
 *    with no view ids, so a signature built from class names alone carries
 *    almost no information on a web page. It is decisive in a native app and
 *    nearly useless here.
 *  - Scoring a group by count times median size picked the *rating widget* —
 *    twenty-four of them, tidy and identical, nested inside the product cards
 *    that were the actual answer.
 *  - One card matches the template at several nesting levels, so a single
 *    product came back three times.
 */
class RealPageTest {

    private fun tree() = ScreenStructure.parse(
        javaClass.getResourceAsStream("/amazon-search.json")!!.bufferedReader().readText()
    )

    private fun productOf(item: ScreenStructure.Item) =
        item.labels.firstOrNull { it.length > 30 && !it.startsWith("Rated") } ?: item.labels.first()

    @Test
    fun `the records are products, not the rating widgets inside them`() {
        val items = ScreenStructure.items(tree())
        assertTrue("expected a real page of results, got ${items.size}", items.size >= 15)

        val named = items.map { ScreenStructure.fields(it) }
        val withPrice = named.count { f -> f.any { it.name == "price" } }
        val withRating = named.count { f -> f.any { it.name == "rating" } }

        // The old scoring returned rows that were a rating and nothing else.
        assertTrue("most records must carry a price, got $withPrice of ${items.size}",
            withPrice >= items.size / 2)
        assertTrue("most records must carry a rating, got $withRating of ${items.size}",
            withRating >= items.size / 2)
    }

    @Test
    fun `records carry the product name, which is what the user asked for`() {
        val items = ScreenStructure.items(tree())
        val products = items.map { productOf(it) }.distinct()
        assertTrue("expected many distinct products, got ${products.size}", products.size >= 10)
        // Names read off the captured page.
        for (expected in listOf("Sony WH-CH520", "BERIBES", "KVIDIO", "Soundcore")) {
            assertTrue(
                "$expected should appear in some record",
                products.any { it.contains(expected) },
            )
        }
    }

    @Test
    fun `a product keeps its own price rather than a neighbour's`() {
        val items = ScreenStructure.items(tree())
        val sony = items.filter { productOf(it).contains("Sony WH-CH520") }
        assertTrue("the Sony must be found", sony.isNotEmpty())
        for (row in sony) {
            val prices = ScreenStructure.fields(row).filter { it.name == "price" }
            assertTrue("the Sony row must carry a price of its own", prices.isNotEmpty())
        }
    }
}
