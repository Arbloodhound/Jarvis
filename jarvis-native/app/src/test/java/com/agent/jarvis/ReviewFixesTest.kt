package com.agent.jarvis

import com.agent.jarvis.agent.ActionGate
import com.agent.jarvis.agent.Disagreement
import com.agent.jarvis.agent.EventTrigger
import com.agent.jarvis.agent.Experience
import com.agent.jarvis.agent.ScamSignals
import com.agent.jarvis.agent.ScamSignals.Sign
import com.agent.jarvis.agent.ScreenStructure
import com.agent.jarvis.agent.ScreenStructure.Item
import com.agent.jarvis.agent.SmsCodeDetector
import com.agent.jarvis.agent.TaskMatch
import com.agent.jarvis.gate.ScamWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regressions for review findings on the scam, gate, lesson, money, trigger and code paths. */
class ScamSignalsBrandTest {

    @Before fun reset() = ScamWatch.clear()

    @Test fun brandCountrySitesAndShortLinksAreOfficial() {
        for (h in listOf("amazon.ca", "dhl.de", "paypal.me", "myaccount.google.co.uk", "apple.co",
                "amazon.com.au", "amazon.co.jp", "google.de")) {
            assertTrue(h, ScamSignals.official(h))
            assertFalse(h, ScamSignals.lookalike(h))
        }
        val a = ScamSignals.assess("Amazon", "Your order has shipped. Track it at https://www.amazon.ca/orders")
        assertFalse(a.reasons, a.scam)
        val b = ScamSignals.assess("DHL", "Ihr Paket kommt heute: https://www.dhl.de/de/privatkunden.html")
        assertFalse(b.reasons, b.scam)
    }

    @Test fun realLookalikesAreStillFlagged() {
        for (h in listOf("paypa1-login.com", "paypa1.com", "amaz0n.de", "amazon-support.xyz", "amazon.com.verify-login.ru", "paypal.co",
                "irs.de", "dhl.de.track-parcel.top")) {
            assertTrue(h, ScamSignals.lookalike(h))
        }
        assertTrue(ScamSignals.assess("x", "Your parcel is held: https://amazon.com.verify-login.ru/pay").scam)
        assertTrue(ScamSignals.assess("x", "Account locked, sign in at amazon-support.xyz").scam)
    }

    @Test fun casualChatIsNotAScam() {
        for (t in listOf(
            "My phone died last night, can you send it to my email instead?",
            "We were the winner of the pub quiz! Send it to the group chat",
            "The police closed the road, can we move it to Friday?",
            "Can you send something for the picnic?",
            "Hey, new number! Send me your feedback on the draft",
        )) {
            val a = ScamSignals.assess("Sam", t)
            assertFalse("$t <- ${a.reasons}", a.scam)
            assertFalse(t, Sign.MONEY_REQUEST in a.signs)
        }
    }

    @Test fun moneyAsksStillCount() {
        assertTrue(Sign.MONEY_REQUEST in ScamSignals.assess("x", "please send 500 dollars").signs)
        assertTrue(Sign.MONEY_REQUEST in ScamSignals.assess("x", "can you transfer £850 for rent").signs)
        assertTrue(ScamSignals.assess("x", "Hi mum, new number. I need to pay something urgently").scam)
        assertTrue(ScamSignals.assess("x", "You are a winner! Pay the $5 fee to claim").scam)
    }
}

class ActionGateDialogTest {
    @Test fun destructiveConfirmLabelsAsk() {
        for (l in listOf("Move to trash", "Move to bin", "Discard", "Clear data", "Clear storage", "Wipe",
                "Format SD card", "Erase", "Delete", "Empty trash")) {
            assertNotNull(l, ActionGate.commitmentIn(l))
        }
    }

    /** A text editor's Format menu is not a commitment; formatting storage is. */
    @Test fun bareFormatIsAMenuNotAWipe() = assertNull(ActionGate.commitmentIn("Format"))

    @Test fun labelsInOtherScriptsAndLanguagesAsk() {
        assertEquals(ActionGate.UNREADABLE, ActionGate.commitmentIn("Оплатить"))
        assertEquals(ActionGate.UNREADABLE, ActionGate.commitmentIn("Оплатить 500 ₽"))
        assertEquals(ActionGate.UNREADABLE, ActionGate.commitmentIn("支付"))
        assertNotNull(ActionGate.commitmentIn("Bezahlen"))
        assertNotNull(ActionGate.commitmentIn("Jetzt kaufen"))
        assertNotNull(ActionGate.commitmentIn("Löschen"))
        assertNotNull(ActionGate.commitmentIn("Supprimer"))
    }

    @Test fun iconsAndNavigationStillPass() {
        assertNull(ActionGate.commitmentIn("⋮"))
        assertNull(ActionGate.commitmentIn("→"))
        assertNull(ActionGate.commitmentIn("Trash"))
        assertNull(ActionGate.commitmentIn("Bin"))
        assertNull(ActionGate.commitmentIn("Café menu"))
    }
}

class ExperienceCueTest {
    @Test fun thanksAndNewRequestsAreNotCorrections() {
        for (m in listOf("No worries, thanks — now open Spotify", "No thanks", "no thank you", "No problem!",
                "Nope, that's fine", "No, it's fine", "Actually, open Spotify", "Instead open the camera",
                "now open the camera", "nothing else")) {
            assertNull(m, Experience.correction("open messages", m))
        }
    }

    @Test fun realCorrectionsAre() {
        for (m in listOf("no, I meant WhatsApp", "No, WhatsApp", "that's wrong", "Wrong app", "not what I asked",
                "no that's not it", "Actually, I meant the other one", "I meant Signal", "nope, the green one")) {
            assertNotNull(m, Experience.correction("open messages", m))
        }
    }
}

class DisagreementDecimalTest {
    @Test fun decimalCommasAreDecimal() {
        assertEquals(240_00L, Disagreement.onScreen("€240,00").first().minor)
        assertEquals(1234_56L, Disagreement.onScreen("€1.234,56").first().minor)
        assertEquals(2400_50L, Disagreement.onScreen("£2,400.50").first().minor)
        assertEquals(1234_00L, Disagreement.onScreen("€1.234").first().minor)
        assertNull(Disagreement.contradiction("pay €240", """[{"t":"Total €240,00"}]"""))
        assertNull(Disagreement.contradiction("pay €1234.56", """[{"t":"€1.234,56"}]"""))
        assertNotNull(Disagreement.contradiction("pay €240", """[{"t":"€2.400,00"}]"""))
    }

    @Test fun ambiguousAmountsAreNotCompared() {
        assertTrue(Disagreement.onScreen("€1.234.56").isEmpty())
        assertNull(Disagreement.contradiction("pay €240", """[{"t":"€1.234.56"},{"t":"€99.00"}]"""))
        assertTrue(Disagreement.onScreen("$0.125").isEmpty())
    }
}

class EventTriggerRepostTest {
    @Before fun reset() {
        EventTrigger.forgetRecent()
        val f = EventTrigger::class.java.getDeclaredField("triggers"); f.isAccessible = true
        @Suppress("UNCHECKED_CAST") (f.get(EventTrigger) as MutableList<Any>).clear()
    }

    private val counting = object : EventTrigger.Trigger {
        var n = 0
        override val name = "count"
        override fun evaluate(context: android.content.Context, pkg: String, title: String, text: String) =
            EventTrigger.Action(EventTrigger.Action.Type.TOAST, "x").also { n++ }
    }

    @Test fun sameNotificationRepostedRunsOnce() {
        EventTrigger.register(counting)
        val ctx = android.content.ContextWrapper(null)
        assertEquals(1, EventTrigger.evaluate(ctx, "com.sms", "Bank", "Your code is 482913", key = "k1").size)
        assertEquals(0, EventTrigger.evaluate(ctx, "com.sms", "Bank", "Your code is 482913", key = "k1").size)
        assertEquals(1, EventTrigger.evaluate(ctx, "com.sms", "Bank", "Your code is 771204", key = "k1").size)
        assertEquals(1, EventTrigger.evaluate(ctx, "com.sms", "Bank", "Your code is 482913", key = "k2").size)
        assertEquals(0, EventTrigger.evaluate(ctx, "com.sms", "Bank", "summary", key = "k3", groupSummary = true).size)
        assertEquals(3, counting.n)
    }

    @Test fun repostWindowExpires() {
        assertFalse(EventTrigger.seenRecently("k", "t", "x", nowMs = 0))
        assertTrue(EventTrigger.seenRecently("k", "t", "x", nowMs = 1_000))
        assertFalse(EventTrigger.seenRecently("k", "t", "x", nowMs = 1_000 + 11 * 60_000L))
    }
}

class SmsCodeContextTest {
    @Test fun nearestCodeNotFirstNumber() {
        assertEquals("482913", SmsCodeDetector.extract("Sept 5 2026: your verification code is 482913"))
        assertEquals("482913", SmsCodeDetector.extract("On 2026-09-05 your verification code is 482913"))
        assertEquals("739201", SmsCodeDetector.extract("Order 5521: use code 739201 to log in"))
    }

    @Test fun phonesMoneyAndLookalikeWordsAreNotCodes() {
        assertNull(SmsCodeDetector.extract("Call 0800 123 4567 to confirm"))
        assertNull(SmsCodeDetector.extract("$1500 is confirmed"))
        assertNull(SmsCodeDetector.extract("Your payment of $1500 was confirmed"))
        assertNull(SmsCodeDetector.extract("Your parcel 8812 is out for shipping today"))
        assertNull(SmsCodeDetector.extract("Please send your zip code 90210 again"))
    }

    @Test fun realOtpFormats() {
        assertEquals("482913", SmsCodeDetector.extract("G-482913 is your Google verification code."))
        assertEquals("5531", SmsCodeDetector.extract("Your Uber code: 5531. Never share this code."))
        assertEquals("847261", SmsCodeDetector.extract("847261 is your Instagram code. Don't share it."))
        assertEquals("30418", SmsCodeDetector.extract("Your PIN is 30418"))
        assertEquals("662190", SmsCodeDetector.extract("Use 662190 as your one-time password for HDFC Bank txn of Rs 1500.00"))
    }
}

class TaskMatchBothWaysTest {
    @Test fun longMemoryCannotClaimShortRequest() {
        val asked = setOf("open")
        val remembered = setOf("open", "history", "page", "from", "three", "dot", "menu", "chrome")
        assertEquals(0.0, TaskMatch.score(asked, remembered), 0.0001)
    }
}

class RatingBoundaryTest {
    @Test fun starterIsNotARating() {
        val f = ScreenStructure.fields(Item(listOf("Bundle", "2 Starter packs $9.99"), top = 0, clickable = true))
            .groupBy({ it.name }, { it.value })
        assertNull(f["rating"])
        val g = ScreenStructure.fields(Item(listOf("Thing", "4 stars"), top = 0, clickable = true))
            .groupBy({ it.name }, { it.value })
        assertEquals(listOf("4 stars"), g["rating"])
    }
}
