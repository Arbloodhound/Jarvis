package com.agent.ultra.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shopping brief assembly: one row per seller-offer, the onion carve-out applied, reputation
 * ranked, no buy language. Pure — no network.
 */
class ShoppingRunTest {

    private val clear = """
        <div class="item"><h2>Sony WH-1000XM5</h2><p>${'$'}349.99</p>
        <p>Sold by: Acme Audio</p><p>In stock</p><p>Condition: New</p><a href="/i/1">view</a></div>
        <div class="item"><h2>Sony WH-1000XM5</h2><p>${'$'}299.50</p>
        <p>Seller - Bargain Bin</p><p>Only 2 left</p><a href="/i/2">view</a></div>
    """.trimIndent()

    private val rep = "<div>Seller: Acme Audio</div><div>4.7 out of 5</div>" +
        "<div>2,100 reviews</div><div>3 disputes</div>"

    private val onionHtml = """<div class="item"><h2>Sony WH-1000XM5</h2><p>${'$'}199.00</p>
        <p>Seller - DarkDeals</p><a href="/i/9">view</a></div>""".trimIndent()

    private val onionUrl = "http://" + "a".repeat(56) + ".onion/shop"

    private fun pages() = listOf(
        "https://shop.example/x" to clear,
        "https://rep.example/acme" to rep,
        onionUrl to onionHtml,
    )

    @Test fun onionRowsAreExcludedAndCounted() {
        val built = ShoppingRun.build(pages(), "2026-10-15T00:00:00Z")
        assertEquals(2, built.offers.count)                 // two clearnet offers
        assertEquals(1, built.excludedOnion)                // the DarkDeals onion row dropped
        assertTrue(built.offers.rows.none { ShoppingListings.isOnionUrl(it.sourceUrl) })
    }

    @Test fun offersSortedByPriceLowestFirst() {
        val built = ShoppingRun.build(pages(), "2026-10-15T00:00:00Z")
        assertEquals("price (lowest first)", built.offers.sort)
        assertEquals(299.50, built.offers.rows[0].price!!.amount, 0.001)
        assertEquals(349.99, built.offers.rows[1].price!!.amount, 0.001)
    }

    @Test fun reputationNeverCitesAnOnionSource() {
        val built = ShoppingRun.build(pages(), "2026-10-15T00:00:00Z")
        val acme = built.ranked.sellers.firstOrNull { it.seller == "Acme Audio" }?.reputation
        assertEquals(4.7, acme!!.rating!!, 0.001)
        assertEquals(2100, acme.reviewCount)
        assertTrue(acme.sources.none { it.contains(".onion") })
    }

    @Test fun renderInformsAndAsksWithoutBuyLanguage() {
        val built = ShoppingRun.build(pages(), "2026-10-15T00:00:00Z")
        val text = ShoppingRun.render("sony headphones", built)
        assertTrue(text.contains("onion marketplace row(s) excluded"))
        assertTrue(text.contains("No pick"))
        assertTrue(text.endsWith(ShoppingCheckout.DIRECTION_QUESTION))
        val low = text.lowercase()
        assertFalse(low.contains("you should buy"))
        assertFalse(low.contains("i recommend buying"))
        assertFalse(low.contains("best buy is"))
    }

    @Test fun emptyPagesRenderCleanly() {
        val built = ShoppingRun.build(emptyList(), "2026-10-15T00:00:00Z")
        assertEquals(0, built.offers.count)
        val text = ShoppingRun.render("nothing", built)
        assertTrue(text.contains("No priced listings found"))
    }
}
