package com.dsh.mobile.data

import com.dsh.mobile.data.SessionHistoryStore.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session history is local-only by requirement, so the real risk is not a leak -
 * it is unbounded growth filling the user's storage, which is the same storage
 * the agents themselves live on.
 */
class SessionHistoryPruneTest {

    private fun session(i: Int, bytes: Long = 0) = Session(
        id = "s$i",
        agentId = "dsh",
        label = "DeepSeek Harness",
        startedAt = 1_000L + i,   // higher = newer
        endedAt = 1_000L + i,
        workspace = "/ws",
        preview = "hello",
        logFile = "/tmp/s$i.log",
    )

    @Test
    fun `nothing is pruned when under both limits`() {
        val list = (1..10).map { session(it) }
        val kept = SessionHistoryStore.selectToKeep(list, { 1024 })
        assertEquals(10, kept.size)
    }

    @Test
    fun `count limit keeps the newest sessions`() {
        val list = (1..60).map { session(it) }
        val kept = SessionHistoryStore.selectToKeep(list, { 0 })
        assertEquals(SessionHistoryStore.MAX_SESSIONS, kept.size)
        assertEquals(
            "kept newest-first, so the newest session must be kept",
            "s60",
            kept.first().id,
        )
        assertEquals(
            "the oldest session is the one evicted",
            "s11",
            kept.last().id,
        )
    }

    @Test
    fun `byte limit evicts oldest first`() {
        val list = (1..10).map { session(it, bytes = 10L * 1024 * 1024) }
        val kept = SessionHistoryStore.selectToKeep(list, { 10L * 1024 * 1024 })
        assertEquals("only 3 x 10MB fits under a 32MB cap", 3, kept.size)
        assertEquals("s10", kept.first().id)
        assertEquals("s8", kept.last().id)
    }

    @Test
    fun `whichever limit binds first wins`() {
        val list = (1..10).map { session(it, bytes = 1L * 1024 * 1024) }
        val byCount = SessionHistoryStore.selectToKeep(list, { 0 }, maxSessions = 4)
        assertEquals(4, byCount.size)
        val byBytes = SessionHistoryStore.selectToKeep(
            list, { 1L * 1024 * 1024 }, maxSessions = 50, maxBytes = 3L * 1024 * 1024,
        )
        assertEquals(3, byBytes.size)
    }

    @Test
    fun `a single oversized session still yields an empty list rather than overflowing`() {
        val list = listOf(session(1, bytes = SessionHistoryStore.MAX_TOTAL_BYTES * 4))
        val kept = SessionHistoryStore.selectToKeep(list, { SessionHistoryStore.MAX_TOTAL_BYTES * 4 })
        assertTrue("must not keep a session that alone breaks the cap", kept.isEmpty())
    }

    @Test
    fun `an empty history prunes to empty`() {
        assertTrue(SessionHistoryStore.selectToKeep(emptyList(), { 1 }).isEmpty())
    }

    @Test
    fun `limits are phone-appropriate`() {
        assertTrue(
            "50 sessions is a sane ceiling",
            SessionHistoryStore.MAX_SESSIONS in 10..200,
        )
        assertTrue(
            "32MB ceiling must not be large enough to threaten agent storage",
            SessionHistoryStore.MAX_TOTAL_BYTES <= 64L * 1024 * 1024,
        )
    }
}
