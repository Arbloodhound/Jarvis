package com.agent.ultra

import com.agent.ultra.agent.Disagreement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the agent is allowed to argue.
 *
 * Weighted toward silence. Every test that asserts null is protecting the
 * mechanism from itself: something that interrupts a task the user asked for
 * gets switched off, and then it protects nobody at all.
 */
class DisagreementTest {

    /** The case the whole thing exists for. */
    @Test
    fun `an order of magnitude difference is caught`() {
        val why = Disagreement.contradiction(
            "pay £240 to British Gas",
            """[{"t":"Confirm payment"},{"t":"Amount"},{"t":"£2,400.00"}]""",
        )
        assertNotNull(why)
        assertTrue(why!!, "£2,400.00" in why)
        assertTrue(why, "£240" in why)
    }

    /**
     * `240` is a substring of `2400`. Comparing as text would call this a
     * match and wave through the exact transaction this is here to stop.
     */
    @Test
    fun `comparison is arithmetic, not containment`() {
        assertEquals(240_00L, Disagreement.onScreen("£240").first().minor)
        assertEquals(2400_00L, Disagreement.onScreen("£2,400").first().minor)
        assertNotNull(Disagreement.contradiction("pay £240", """[{"t":"£2400"}]"""))
    }

    @Test
    fun `the same amount written differently is not a disagreement`() {
        assertNull(Disagreement.contradiction("pay £240", """[{"t":"£240.00"}]"""))
        assertNull(Disagreement.contradiction("pay £1,250.50", """[{"t":"£1250.5"}]"""))
    }

    /** Subtotal, tax and total is the normal shape of a checkout screen. */
    @Test
    fun `one matching amount among several is enough to stay quiet`() {
        assertNull(
            Disagreement.contradiction(
                "pay £240 for the boiler service",
                """[{"t":"Subtotal £200.00"},{"t":"VAT £40.00"},{"t":"Total £240.00"}]""",
            )
        )
    }

    @Test
    fun `no amount in the request means nothing to disagree with`() {
        assertNull(Disagreement.contradiction("open my payment history", """[{"t":"£2,400.00"}]"""))
    }

    @Test
    fun `no amount on screen means nothing to disagree with`() {
        assertNull(Disagreement.contradiction("pay £240", """[{"t":"Confirm"},{"t":"Details"}]"""))
    }

    /** Someone who types no currency symbol still named an amount. */
    @Test
    fun `a bare number after a payment verb counts as stated`() {
        assertEquals(240_00L, Disagreement.statedByUser("pay 240 to the gas people").first().minor)
        assertNotNull(Disagreement.contradiction("pay 240 to the gas people", """[{"t":"£2,400"}]"""))
    }

    /**
     * An unmarked number is a house number, a quantity, a year or an order
     * reference far more often than it is money.
     */
    @Test
    fun `unmarked numbers on screen are not treated as money`() {
        assertTrue(Disagreement.onScreen("Order 2400 · flat 240 · 2026").isEmpty())
    }

    @Test
    fun `currency codes are understood`() {
        assertEquals(240_00L, Disagreement.onScreen("240.00 GBP").first().minor)
        assertTrue(Disagreement.onScreen("240.00 KGS").isEmpty())
    }
}
