package com.agent.ultra.research

/**
 * Gated checkout preparation — port of the TESTED Python reference
 * (jarvis-v4/shopping/checkout.py).
 *
 * GATED PREPARATION ONLY. This builds the order plan and STOPS at
 * the owner's confirmation. There is NO purchase-execution path in
 * this file: no cart APIs, no payment sending. Approval hands the
 * plan to the existing gated tool flow (their 120s confirm card) —
 * a SEPARATE step, never implied here.
 *
 * cryptoPrep is display-only {amount, address} for the confirm
 * card. Keys and seed phrases are NEVER handled — not here, not
 * anywhere in this module.
 *
 * SOURCE ONLY: not compiled in this sandbox. Pure logic — no
 * Android deps. The confirm-card wiring itself is repo-side.
 */
object ShoppingCheckout {

    data class OrderPlan(
        val title: String,
        val seller: String,
        val unitPrice: Double,
        val currency: String,
        val qty: Int,
        val total: Double,
        val link: String,
        val sourceUrl: String,
    )

    data class CryptoPrep(val amount: Double, val address: String)

    data class OrderNote(
        val orderNo: String,
        val seller: String,
        val expectedDate: String,
        val reminder: String,
    )

    /** Build an order plan from a listing row. Preparation only. */
    fun prepareOrder(row: ShoppingListings.Row, qty: Int = 1): OrderPlan {
        val q = maxOf(1, qty)
        val unit = row.price?.amount ?: 0.0
        return OrderPlan(
            title = row.title,
            seller = row.seller,
            unitPrice = unit,
            currency = row.price?.currency ?: "",
            qty = q,
            total = unit * q,
            link = row.link,
            sourceUrl = row.sourceUrl,
        )
    }

    /**
     * Display-only crypto payment details for the confirm card.
     * The repo side deep-links the wallet app with these; the
     * module never sees keys or seed phrases.
     */
    fun cryptoPrep(amount: Double, address: String): CryptoPrep =
        CryptoPrep(amount, address.trim())

    /**
     * Save an order as a note + reminder structure. Persistence
     * itself is repo-side (notes store); this builds the record.
     */
    fun trackOrder(orderNo: String, seller: String, expectedDate: String): OrderNote =
        OrderNote(
            orderNo = orderNo.trim(),
            seller = seller.trim(),
            expectedDate = expectedDate.trim(),
            reminder = "Order $orderNo from $seller expected $expectedDate",
        )

    /**
     * The question the agent asks after presenting listings or
     * reputation — inform and ask, never permit/forbid. Wording
     * must stay free of buy recommendations.
     */
    const val DIRECTION_QUESTION =
        "What do you want next — details on a listing, check a seller further, " +
            "prepare an order, or drop it?"
}
