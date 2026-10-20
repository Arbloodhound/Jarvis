package com.agent.ultra.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Price provenance: a price whose only source is a search-engine snippet is never
 * quoted or ranked; only a full-page-read price is rankable.
 */
class ShoppingProvenanceTest {

    private val html = """
        <div class="item"><h2>Acme Widget Pro</h2><p>$49.99</p>
        <p>Sold by: Acme Corp</p><a href="/item/1">view</a></div>
        <div class="item"><h2>Acme Widget Basic</h2><p>$29.50</p>
        <p>Seller - Bargain Bin</p><a href="/item/2">view</a></div>
    """.trimIndent()

    private fun rows() = ShoppingListings.extractListings(html, "https://example.com/shop").rows

    @Test fun fullPageReadPricesAreVerifiedAndRankable() {
        val tagged = ShoppingListings.withProvenance(rows(), ShoppingListings.PriceSource.FULL_PAGE)
        assertTrue(tagged.all { it.priceVerified })
        assertTrue(tagged.all { it.row.price != null })
        assertEquals(2, ShoppingListings.rankable(tagged).size)
    }

    @Test fun snippetPricesAreLabelledUnverifiedAndNeverRankable() {
        val tagged = ShoppingListings.withProvenance(rows(), ShoppingListings.PriceSource.SEARCH_SNIPPET)
        assertEquals(2, tagged.size)
        assertTrue(tagged.none { it.priceVerified })
        // the price is cleared so it cannot be quoted or ranked
        assertTrue(tagged.all { it.row.price == null })
        assertTrue(tagged.all { it.note.contains("unverified") && it.note.contains("full page read") })
        // the original snippet price text is preserved in the label, not silently lost
        assertTrue(tagged.any { it.note.contains("$49.99") })
        assertTrue(ShoppingListings.rankable(tagged).isEmpty())
    }

    @Test fun snippetPricesCanBeDroppedEntirely() {
        val tagged = ShoppingListings.withProvenance(
            rows(), ShoppingListings.PriceSource.SEARCH_SNIPPET, dropUnverified = true)
        assertTrue(tagged.isEmpty())
    }

    @Test fun rankableKeepsOnlyVerifiedWithPrice() {
        val full = ShoppingListings.withProvenance(rows(), ShoppingListings.PriceSource.FULL_PAGE)
        val snippet = ShoppingListings.withProvenance(rows(), ShoppingListings.PriceSource.SEARCH_SNIPPET)
        val mixed = full.take(1) + snippet
        val rankable = ShoppingListings.rankable(mixed)
        assertEquals(1, rankable.size)
        assertNull(snippet.first().row.price)
        assertFalse(rankable.first().price == null)
    }
}
