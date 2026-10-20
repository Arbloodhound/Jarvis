package com.agent.jarvis.agent

import com.agent.jarvis.agent.OnionSafety.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class OnionSafetyTest {

    private val ddg = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion"
    /** Valid v3 addresses made for the tests (key bytes 0..31, and 32 × 0x07): checksums right, nobody's service. */
    private val other = "aaaqeayeaudaocajbifqydiob4ibceqtcqkrmfyydenbwha5dyp3kead.onion"
    private val other2 = "a4dqobyha4dqobyha4dqobyha4dqobyha4dqobyha4dqobyha4dwc6ad.onion"
    private val known = OnionSafety.DIRECTORY

    @Test fun sha3MatchesTheJdkAndTheStandardVector() {
        assertEquals("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            Sha3.sha3256("abc".toByteArray()).joinToString("") { "%02x".format(it) })
        for (n in listOf(0, 1, 135, 136, 137, 300)) {
            val input = ByteArray(n) { (it * 31 + 7).toByte() }
            assertTrue(MessageDigest.getInstance("SHA3-256").digest(input).contentEquals(Sha3.sha3256(input)))
        }
    }

    @Test fun v3ChecksumAndVersionAreChecked() {
        assertTrue(OnionSafety.isV3(ddg))
        assertTrue(OnionSafety.isV3("www.$ddg"))
        assertTrue(OnionSafety.isV3(other))
        // one character changed: still 56 base32 characters, checksum wrong
        assertFalse(OnionSafety.isV3(ddg.replaceFirst("duckduckgogg", "duckduckgogh")))
        assertFalse(OnionSafety.isV3("duckduckgo.onion"))
        assertFalse(OnionSafety.isV3(ddg.replace('2', '1')))    // '1' isn't base32
    }

    @Test fun knownAddressIsTrustedAndUnlistedCleanIsOnlyUnknown() {
        assertEquals(Verdict.TRUSTED, OnionSafety.classify("http://$ddg/html?q=x", null, known)!!.verdict)
        val clean = OnionSafety.classify("http://$other/", "<title>A wiki</title><p>Links and articles.</p>", known)!!
        assertEquals(Verdict.UNKNOWN, clean.verdict)
        assertEquals(Verdict.UNKNOWN, OnionSafety.classify("http://$other/", null, known)!!.verdict)
        assertNull(OnionSafety.classify("https://duckduckgo.com/", null, known))
    }

    @Test fun lookalikesAreLikelyPhish() {
        val oneOff = ddg.replaceFirst("gogg42", "gogg43")
        val r = OnionSafety.classify("http://$oneOff/", null, known)!!
        assertEquals(Verdict.LIKELY_PHISH, r.verdict)
        assertTrue(r.reasons.single().contains("1 character away from DuckDuckGo"))
        // a vanity clone: same start, different everywhere else
        val vanity = "duckdu" + "a".repeat(50) + ".onion"
        assertEquals(Verdict.LIKELY_PHISH, OnionSafety.classify(vanity, null, known)!!.verdict)
        assertEquals(ddg, OnionSafety.lookalikeOf(vanity, known.keys))
        assertNull(OnionSafety.lookalikeOf(other, known.keys))
        assertNull(OnionSafety.lookalikeOf(ddg, known.keys))
        assertEquals(1, OnionSafety.levenshtein("kitten", "sitten")); assertEquals(3, OnionSafety.levenshtein("kitten", "sitting"))
    }

    @Test fun seedPhraseRequestIsDispositiveButAWarningIsNot() {
        val phish = "<form><p>Wallet sync: enter your 12-word recovery phrase to restore access</p><input type=password></form>"
        assertEquals(Verdict.SCAM, OnionSafety.classify("http://$other/", phish, known)!!.verdict)
        assertEquals(Verdict.SCAM, OnionSafety.classify("http://$other/", "Please paste your private key below to claim", known)!!.verdict)
        val warning = "Security tip: never enter your seed phrase on any website."
        assertEquals(Verdict.UNKNOWN, OnionSafety.classify("http://$other/", warning, known)!!.verdict)
        // a negation in an earlier clause doesn't make the ask a warning
        assertEquals(Verdict.SCAM, OnionSafety.classify("http://$other/", "Don't wait — enter your 12-word recovery phrase to claim", known)!!.verdict)
    }

    @Test fun cryptoDoublingIsScam() {
        assertEquals(Verdict.SCAM, OnionSafety.classify("http://$other/", "Send 0.1 BTC and get 2x back within an hour!", known)!!.verdict)
        assertEquals(Verdict.SCAM, OnionSafety.classify("http://$other/", "We double your bitcoin, guaranteed.", known)!!.verdict)
    }

    @Test fun impersonationByTitleIsLikelyPhishAndLoginFormIsOnlyNoted() {
        val r = OnionSafety.classify("http://$other/", "<title>DuckDuckGo — Privacy, simplified.</title>", known)!!
        assertEquals(Verdict.LIKELY_PHISH, r.verdict)
        val forum = OnionSafety.classify("http://$other/", "<title>Is DuckDuckGo safe? - forum</title><input type=\"password\">", known)!!
        assertEquals(Verdict.UNKNOWN, forum.verdict)
        assertTrue(forum.reasons.contains(OnionSafety.Signal.LOGIN_FORM.words))
    }

    @Test fun ownListKeepsOnlyValidAddressesAndJoinsTheKnownSet() {
        val text = "http://$other/  My wiki\nnot-an-address\n${ddg.replaceFirst("gogg42", "gogg43")} fake\n$other2"
        assertEquals("$other My wiki\n$other2", OnionSafety.cleanOwn(text))
        val k = OnionSafety.knownGood(text)
        assertEquals("My wiki", k[other]); assertEquals("your list", k[other2]); assertEquals("DuckDuckGo", k[ddg])
        assertEquals(Verdict.TRUSTED, OnionSafety.classify("http://$other2/", null, k)!!.verdict)
        assertTrue(OnionSafety.parseOwn("").isEmpty())
    }

    @Test fun findsOnionUrlsAndAddressesInText() {
        val t = "Is http://$other/forum/t1 a clone? Also $ddg."
        assertEquals(listOf("http://$other/forum/t1", "http://$ddg"), OnionSafety.urlsIn(t))
        assertEquals(listOf(other, ddg), OnionSafety.addressesIn(t))
        assertEquals("duckduck…zczad.onion", OnionSafety.short(ddg))
        assertEquals("$other", OnionSafety.onionHost("http://user@$other:80/x"))
        // a longer run is not the address hidden at its end
        assertTrue(OnionSafety.urlsIn("is x$ddg legit?").isEmpty())
        assertTrue(OnionSafety.urlsIn("http://x$ddg/").isEmpty())
    }
}
