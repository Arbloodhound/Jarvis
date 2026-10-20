package com.agent.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TakeoverPassTest {
    private val settle = TakeoverPass.SETTLE_MS

    // The flag was set during this very call: the whole settle time, never zero (the bug: zero).
    @Test fun justOpenedWaitsTheWholeSettle() = assertEquals(settle, TakeoverPass.waitMs(10_000, 10_000))

    @Test fun partlySettledWaitsTheRest() = assertEquals(settle - 50, TakeoverPass.waitMs(10_000, 10_050))

    // A second gesture while the overlay has long been open goes at once.
    @Test fun settledLongAgoGoesNow() = assertEquals(0L, TakeoverPass.waitMs(10_000, 10_000 + settle + 500))

    // The change failed or the overlay was hidden in between: wait anyway, never forever.
    @Test fun neverOpenedWaitsOnlyTheSettle() = assertEquals(settle, TakeoverPass.waitMs(0, 99_999))

    // A clock that reads earlier than the change (should not happen) is still bounded.
    @Test fun clockBeforeChangeIsBounded() = assertEquals(settle, TakeoverPass.waitMs(10_000, 9_000))
}
