package com.dsh.mobile.chat

import com.dsh.mobile.proxy.ApprovalTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalTokensTest {

    @Test
    fun `codex is answered with y not yes`() {
        // codex's prompt accepts `y`; answering `yes` leaves the child blocked
        // while the UI looks like it did something.
        assertEquals("y", ApprovalTokens.answerFor("codex", approved = true))
    }

    @Test
    fun `claude is answered with yes`() {
        assertEquals("yes", ApprovalTokens.answerFor("claude", approved = true))
    }

    @Test
    fun `denial is always no`() {
        listOf("codex", "claude", "hermes", "dsh", "unknown").forEach {
            assertEquals("no", ApprovalTokens.answerFor(it, approved = false))
        }
    }

    @Test
    fun `an unknown agent still gets an answer`() {
        // Guessing `yes` beats returning nothing: a missing answer hangs the
        // child forever, which is worse than a rejected prompt.
        assertEquals("yes", ApprovalTokens.answerFor("some-new-agent", approved = true))
    }

    @Test
    fun `every catalogued agent has a token`() {
        // A new agent without a token would silently fall back to "yes".
        assertEquals(
            com.dsh.mobile.data.ActiveAgent.entries.toSet(),
            ApprovalTokens.AFFIRMATIVE_TOKENS.keys,
        )
    }
}
