package com.agent.ultra.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingTest {

    private val html = """
        <div class="item"><h2>Acme Widget Pro</h2><p>$49.99</p>
        <p>Sold by: Acme Corp</p><p>In stock</p><p>Condition: New</p>
        <p>Ships from: Portland</p><a href="/item/1">view</a></div>
        <div class="item"><h2>Acme Widget Basic</h2><p>$29.50</p>
        <p>Seller - Bargain Bin</p><p>Only 3 left</p><p>Used - like new</p>
        <a href="/item/2">view</a></div>
    """.trimIndent()

    private val onion = "http://" + "a".repeat(56) + ".onion/shop"

    @Test fun multiSellerPageYieldsOneRowPerSeller() {
        val t = ShoppingListings.extractListings(html, "https://example.com/shop")
        assertEquals(2, t.rows.size)
        assertEquals(listOf("Acme Corp", "Bargain Bin"), t.rows.map { it.seller })
        assertEquals(49.99, t.rows[0].price!!.amount, 0.001)
    }

    @Test fun urlAgnosticIdenticalRows() {
        val clear = ShoppingListings.extractListings(html, "https://example.com/shop")
        val dark = ShoppingListings.extractListings(html, onion)
        assertEquals(clear.rows.map { it.copy(sourceUrl = "", fetchedAt = "") },
            dark.rows.map { it.copy(sourceUrl = "", fetchedAt = "") })
        assertTrue(dark.rows.all { it.sourceUrl == onion })
    }

    @Test fun isOnionUrlUsesHostnameParsing() {
        assertTrue(ShoppingListings.isOnionUrl(onion))
        assertTrue(ShoppingListings.isOnionUrl("http://" + "b".repeat(56) + ".onion/i/1?x=2"))
        assertFalse(ShoppingListings.isOnionUrl("https://example.com/shop"))
        assertFalse(ShoppingListings.isOnionUrl("/item/1"))
        assertFalse(ShoppingListings.isOnionUrl("https://onion.example.com/"))
    }

    @Test fun excludeOnionIsSeparateAndExplicit() {
        val mixed = ShoppingListings.extractListings(html, "https://example.com/shop").rows +
            ShoppingListings.extractListings(html, onion).rows
        val f = ShoppingListings.excludeOnion(mixed)
        assertEquals(2, f.count)
        assertEquals(2, f.excluded.size)
        assertTrue(f.excluded.all { it.sourceUrl.contains(".onion") })
    }

    @Test fun compareTableSortsMechanicallyWithLabel() {
        val t = ShoppingListings.extractListings(html, "https://example.com/shop")
        val sorted = ShoppingListings.compareTable(t.rows, "price")
        assertEquals("price (lowest first)", sorted.sort)
        assertTrue(sorted.rows[0].price!!.amount < sorted.rows[1].price!!.amount)
    }

    @Test fun reputationExtractsEvidence() {
        val page = SellerReputation.Page("https://m.example/s/acme",
            "<div>Seller: Acme Corp</div><div>4.8 out of 5</div>" +
                "<div>1,234 reviews</div><div>12 disputes</div><div>Member since 2019</div>")
        val r = SellerReputation.sellerReputation("Acme Corp", listOf(page))
        assertEquals(4.8, r.rating!!, 0.001)
        assertEquals(1234, r.reviewCount)
        assertEquals(12, r.disputes)
        assertEquals(listOf("https://m.example/s/acme"), r.sources)
    }

    @Test fun mergeShowsMethod() {
        val a = SellerReputation.SellerEntry("Acme Corp", null)
        val b = SellerReputation.SellerEntry("acme-corp", null)
        val c = SellerReputation.SellerEntry("Bargain Bin", null)
        val groups = SellerReputation.mergeSellers(listOf(a, b, c))
        assertEquals(2, groups.size)
        assertEquals("exact", groups[0].method)
    }

    @Test fun rankIsLabelledMechanical() {
        val mk = { s: String, r: Double?, n: Int? ->
            SellerReputation.SellerEntry(s, SellerReputation.Reputation(
                s, r, n, emptyList(), null, "", emptyList()))
        }
        val ranked = SellerReputation.rankSellers(
            listOf(mk("low", 3.0, 10), mk("high", 4.9, 5000)))
        assertEquals("high", ranked.sellers[0].seller)
        assertTrue(ranked.sort.contains("rating"))
    }

    @Test fun checkoutPreparesButNeverExecutes() {
        val t = ShoppingListings.extractListings(html, "https://example.com/shop")
        val plan = ShoppingCheckout.prepareOrder(t.rows[0], 2)
        assertEquals(99.98, plan.total, 0.001)
        assertEquals(2, plan.qty)
        // no execution surface: the plan carries no pay/send primitive
        val crypto = ShoppingCheckout.cryptoPrep(0.05, "bc1qxyz")
        assertEquals(0.05, crypto.amount, 0.0001)
        assertEquals("bc1qxyz", crypto.address)
    }

    @Test fun trackOrderBuildsNote() {
        val n = ShoppingCheckout.trackOrder("ORD-1", "Acme Corp", "2026-10-20")
        assertTrue(n.reminder.contains("ORD-1"))
    }

    @Test fun noBuyLanguageInDirectionQuestion() {
        val q = ShoppingCheckout.DIRECTION_QUESTION.lowercase()
        assertFalse(q.contains("buy from"))
        assertFalse(q.contains("you should buy"))
    }
}
