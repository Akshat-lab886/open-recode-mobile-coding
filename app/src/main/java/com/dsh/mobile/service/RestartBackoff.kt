package com.dsh.mobile.service

/**
 * Stratified restart policy for the agent child.
 *
 * The previous policy was one fixed 5s delay on every exit, which has two
 * failure modes that both matter on a phone:
 *
 *  - A crash loop (the agent exits instantly, e.g. a rejected API key) restarts
 *    every 5s forever, burning battery and CPU and never telling the user.
 *  - A long, healthy session that ends for a transient reason waits exactly as
 *    long as a crash, so the delay never adapts to how the last run went.
 *
 * Mobile-Harness stratifies retries by failure class (`RETRY_CONFIG`); the same
 * idea here, with the strata being how the previous run actually ended rather
 * than a guess about why.
 */
object RestartBackoff {

    /** A run shorter than this counts as "died on startup", not "ended". */
    const val FAST_EXIT_MS: Long = 5_000L

    /** First backoff step; it doubles from there. */
    const val BASE_MS: Long = 2_000L

    /** Ceiling, so a crash loop settles into a slow poll instead of a hot loop. */
    const val MAX_MS: Long = 60_000L

    /** After this many consecutive instant exits, stop and surface the error. */
    const val MAX_CONSECUTIVE_FAST_EXITS: Int = 5

    /**
     * Delay before restart number [consecutiveRestarts] (1-based).
     *
     * A run that lasted at least [FAST_EXIT_MS] is treated as healthy and gets
     * [BASE_MS], so a long session that ends does not inherit the grown delay of
     * an earlier crash loop.
     */
    fun delayFor(consecutiveRestarts: Int, ranForMs: Long): Long {
        if (ranForMs >= FAST_EXIT_MS) return BASE_MS
        // coerceIn before the shift keeps 2^(n-1) from overflowing Long, which
        // would wrap to a negative delay.
        val n = consecutiveRestarts.coerceIn(1, 16)
        return (BASE_MS shl (n - 1)).coerceAtMost(MAX_MS)
    }

    /**
     * True when the agent has died on startup too many times in a row.
     *
     * Retrying forever is the wrong outcome: if the child cannot survive its own
     * startup, the cause is configuration (a key, a preset, a missing binary)
     * and only the user can fix it. A visible, non-retryable error beats a
     * silent loop that looks like a hang.
     */
    fun shouldGiveUp(consecutiveFastExits: Int): Boolean =
        consecutiveFastExits >= MAX_CONSECUTIVE_FAST_EXITS
}
