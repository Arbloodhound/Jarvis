package com.agent.ultra

import com.agent.ultra.gate.ScreenSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a secret on a phone, and what must not.
 *
 * The false-positive cases carry as much weight as the catches. A guard that
 * fires while someone types their house number gets switched off, and a guard
 * that is switched off protects nobody.
 */
class ScreenSecretsTest {

    private val BANK = "com.bank.app"
    private val CHAT = "com.chat.app"

    // ── What it must catch ──────────────────────────────────────────

    @Test
    fun `a one-time code with wording around it is a secret`() {
        for (screen in listOf(
            "Your verification code is 481920",
            "Enter the one-time code 4819 to continue",
            "OTP: 928374",
            "Your login PIN is 5521",
            "Security code 019283 expires in 5 minutes",
        )) {
            assertTrue("should catch: $screen", ScreenSecrets.find(screen, BANK).isNotEmpty())
        }
    }

    @Test
    fun `a card number is caught by arithmetic, with no wording at all`() {
        // Passes Luhn. No context word anywhere in the string.
        val found = ScreenSecrets.find("4539578763621486", BANK)
        assertEquals(1, found.size)
        assertEquals("a card number", found[0].why)
    }

    @Test
    fun `a card number written in groups is still caught`() {
        assertTrue(ScreenSecrets.find("4539 5787 6362 1486", BANK).isNotEmpty())
    }

    @Test
    fun `it still catches what the ported detector knew about`() {
        // built at runtime: no key-shaped literal in a public repo
        assertTrue(ScreenSecrets.find("sk-" + "abcdefghijklmnopqrstuvwx", BANK).isNotEmpty())
    }

    // ── What it must NOT catch ──────────────────────────────────────

    @Test
    fun `a bare number with no wording is not a secret`() {
        // A postcode, a house number, a year, a quantity, a price.
        for (screen in listOf(
            "Order 481920 has shipped",
            "1986",
            "Flat 4819, Manchester",
            "12345 results found",
        )) {
            assertTrue("must not fire on: $screen", ScreenSecrets.find(screen, BANK).isEmpty())
        }
    }

    @Test
    fun `a sixteen digit number that fails Luhn is not a card`() {
        assertTrue(ScreenSecrets.find("1234567812345678", BANK).isEmpty())
    }

    @Test
    fun `wording far from the number does not make it a code`() {
        val far = "Security settings are on this page. " + "filler ".repeat(20) + "Reference 552112"
        assertTrue(ScreenSecrets.find(far, BANK).isEmpty())
    }

    // ── The rule that matters ───────────────────────────────────────

    @Test
    fun `a code from the bank cannot be typed into a chat app`() {
        val seen = ScreenSecrets.find("Your verification code is 481920", BANK)
        val refusal = ScreenSecrets.refuseTyping("481920", CHAT, seen)
        assertNotNull("this is the leak the whole thing exists to stop", refusal)
        assertTrue(refusal!!.contains("one-time code"))
        assertTrue(refusal.contains(BANK))
    }

    @Test
    fun `the same code typed back into the bank is allowed`() {
        // Reading a code and entering it in the app that sent it is the normal
        // thing. Blocking it would make the agent useless for the task people
        // most want it for.
        val seen = ScreenSecrets.find("Your verification code is 481920", BANK)
        assertNull(ScreenSecrets.refuseTyping("481920", BANK, seen))
    }

    @Test
    fun `a code buried in a longer message is still caught leaving`() {
        val seen = ScreenSecrets.find("Your verification code is 481920", BANK)
        assertNotNull(ScreenSecrets.refuseTyping("hi, the code is 481920, use it", CHAT, seen))
    }

    @Test
    fun `ordinary text is not blocked`() {
        val seen = ScreenSecrets.find("Your verification code is 481920", BANK)
        assertNull(ScreenSecrets.refuseTyping("running late, see you at six", CHAT, seen))
        assertNull(ScreenSecrets.refuseTyping("", CHAT, seen))
    }

    @Test
    fun `nothing seen means nothing refused`() {
        assertNull(ScreenSecrets.refuseTyping("481920", CHAT, emptyList()))
    }

    // ── Spacing does not launder a secret ───────────────────────────

    @Test
    fun `a grouped card number on screen is caught typed ungrouped`() {
        val seen = ScreenSecrets.find("Card 4539 5787 6362 1486", BANK)
        assertNotNull(ScreenSecrets.refuseTyping("4539578763621486", CHAT, seen))
        assertNotNull(ScreenSecrets.refuseTyping("4539-5787-6362-1486", CHAT, seen))
    }

    @Test
    fun `a code on screen is caught typed with a space in it`() {
        val seen = ScreenSecrets.find("Your verification code is 481920", BANK)
        assertNotNull(ScreenSecrets.refuseTyping("481 920", CHAT, seen))
        assertNotNull(ScreenSecrets.refuseTyping("code: 481-920", CHAT, seen))
        assertTrue(ScreenSecrets.carries("481 920", "481920"))
    }

    @Test
    fun `digits from separate numbers in a sentence are not glued together`() {
        // Only spaces, dashes and dots BETWEEN digits are removed; a comma or a
        // colon still separates two numbers.
        assertNull(ScreenSecrets.refuseTyping("room 48, 1920 is fine", CHAT,
            ScreenSecrets.find("Your verification code is 481920", BANK)))
    }

    // ── Narrower context: ordinary numbers stay ordinary ────────────

    @Test
    fun `broad words no longer turn a number into a code`() {
        for (screen in listOf(
            "Confirm your 2024 booking",
            "Access 1200 items in your library",
            "Login streak: 2024 days",
            "Security update 20240501 installed",
            "Postal code 90210",
            "Use promo code 2024 at checkout",
        )) {
            assertTrue("must not fire on: $screen", ScreenSecrets.find(screen, BANK).isEmpty())
        }
    }

    @Test
    fun `an IMEI is not a card number`() {
        // 490154203237518 passes Luhn (it is the textbook IMEI example).
        assertTrue(ScreenSecrets.passesLuhn("490154203237518"))
        assertTrue(ScreenSecrets.find("490154203237518", BANK).isEmpty())
        assertTrue(ScreenSecrets.find("IMEI: 490154203237518", BANK).isEmpty())
        // A device id labelled as one is not a card, whatever its length.
        assertTrue(ScreenSecrets.find("MEID 4539578763621486", BANK).isEmpty())
    }

    @Test
    fun `a fifteen digit Amex number is still a card`() {
        assertTrue(ScreenSecrets.find("378282246310005", BANK).isNotEmpty())
    }

    @Test
    fun `the source travels with the value`() {
        val seen = ScreenSecrets.find("Your verification code is 481920", BANK)
        assertEquals(BANK, seen[0].pkg)
    }
}
