package com.dsh.mobile.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the stratified restart policy.
 *
 * The failure being prevented: the old policy was a single fixed 5s delay, so
 * an agent that exits instantly (a rejected key, a bad preset, a missing
 * binary) restarted every 5 seconds FOREVER. On a phone that is a silent
 * battery/CPU burn that also never tells the user anything is wrong.
 */
class RestartBackoffTest {

    @Test
    fun `a run that survived startup gets the base delay and resets the loop`() {
        // However long the crash-loop history, a healthy run must not inherit
        // the grown delay.
        assertEquals(RestartBackoff.BASE_MS, RestartBackoff.delayFor(0, RestartBackoff.FAST_EXIT_MS))
        assertEquals(RestartBackoff.BASE_MS, RestartBackoff.delayFor(1, 10 * 60_000))
        assertEquals(RestartBackoff.BASE_MS, RestartBackoff.delayFor(9, RestartBackoff.FAST_EXIT_MS))
    }

    @Test
    fun `instant exits back off exponentially`() {
        val fast = RestartBackoff.FAST_EXIT_MS - 1
        assertEquals(2_000L, RestartBackoff.delayFor(1, fast))
        assertEquals(4_000L, RestartBackoff.delayFor(2, fast))
        assertEquals(8_000L, RestartBackoff.delayFor(3, fast))
        assertEquals(16_000L, RestartBackoff.delayFor(4, fast))
    }

    @Test
    fun `the delay is capped so a crash loop becomes a slow poll`() {
        val fast = 0L
        assertEquals(RestartBackoff.MAX_MS, RestartBackoff.delayFor(10, fast))
        assertEquals(RestartBackoff.MAX_MS, RestartBackoff.delayFor(16, fast))
    }

    @Test
    fun `absurd counters cannot overflow into a negative delay`() {
        // A shift of (n-1) on a Long wraps negative well before n=100; a
        // negative delay would make delay() return immediately and reinstate
        // the exact hot loop this class exists to prevent.
        for (n in intArrayOf(17, 40, 100, Int.MAX_VALUE)) {
            val d = RestartBackoff.delayFor(n, 0L)
            assertTrue("delay must stay positive for n=$n, was $d", d > 0)
            assertEquals("delay must stay capped for n=$n", RestartBackoff.MAX_MS, d)
        }
    }

    @Test
    fun `every delay is positive and within the cap`() {
        for (n in 1..20) {
            for (ranFor in longArrayOf(0, 1, RestartBackoff.FAST_EXIT_MS - 1, RestartBackoff.FAST_EXIT_MS, 1_000_000)) {
                val d = RestartBackoff.delayFor(n, ranFor)
                assertTrue("n=$n ranFor=$ranFor -> $d", d in 1..RestartBackoff.MAX_MS)
            }
        }
    }

    @Test
    fun `gives up only after a run of instant exits`() {
        assertFalse(RestartBackoff.shouldGiveUp(0))
        assertFalse(RestartBackoff.shouldGiveUp(RestartBackoff.MAX_CONSECUTIVE_FAST_EXITS - 1))
        assertTrue(RestartBackoff.shouldGiveUp(RestartBackoff.MAX_CONSECUTIVE_FAST_EXITS))
        assertTrue(RestartBackoff.shouldGiveUp(RestartBackoff.MAX_CONSECUTIVE_FAST_EXITS + 3))
    }

    @Test
    fun `the give-up budget is spent before the cap can hide a real failure`() {
        // Sanity on the interaction: the loop must reach the give-up threshold
        // LONG before the exponential delay reaches its ceiling, otherwise the
        // user waits a minute per attempt for a hopeless configuration.
        val lastDelay = RestartBackoff.delayFor(
            RestartBackoff.MAX_CONSECUTIVE_FAST_EXITS - 1,
            0L,
        )
        assertTrue(
            "the final attempt should still be quick, was ${lastDelay}ms",
            lastDelay < RestartBackoff.MAX_MS,
        )
    }
}
