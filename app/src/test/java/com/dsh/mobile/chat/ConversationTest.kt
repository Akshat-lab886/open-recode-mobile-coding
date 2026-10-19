package com.dsh.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {

    private fun start() = Conversation()

    @Test
    fun `consecutive deltas accumulate into ONE message`() {
        // The single most important property: an agent emits many deltas per
        // turn, and one bubble per delta is how a streaming UI looks broken.
        val c = start()
            .let { ConversationReducer.agentDelta(it, "Hel") }
            .let { ConversationReducer.agentDelta(it, "lo ") }
            .let { ConversationReducer.agentDelta(it, "world") }
        assertEquals(1, c.messages.size)
        assertEquals("Hello world", c.messages[0].text)
    }

    @Test
    fun `an agent message starts in the streaming state`() {
        val c = ConversationReducer.agentDelta(start(), "hi")
        assertTrue(c.messages[0].streaming)
    }

    @Test
    fun `finishing freezes the message and records the duration`() {
        val c = start()
            .let { ConversationReducer.agentDelta(it, "done") }
            .let { ConversationReducer.agentFinished(it, workedMillis = 4_200L) }
        assertFalse(c.messages[0].streaming)
        assertEquals(4_200L, c.messages[0].workedMillis)
    }

    @Test
    fun `an empty delta is ignored`() {
        assertEquals(0, ConversationReducer.agentDelta(start(), "").messages.size)
    }

    @Test
    fun `a user turn ends the agent's stream`() {
        val c = start()
            .let { ConversationReducer.agentDelta(it, "partial") }
            .let { ConversationReducer.userSaid(it, "wait") }
        assertFalse("the agent message must not keep streaming", c.messages[0].streaming)
        assertEquals(ChatRole.USER, c.messages[1].role)
    }

    @Test
    fun `a delta after a user turn opens a NEW agent message`() {
        // Otherwise the reply to the second question is appended to the first
        // answer, producing one incoherent bubble.
        val c = start()
            .let { ConversationReducer.agentDelta(it, "first answer") }
            .let { ConversationReducer.agentFinished(it) }
            .let { ConversationReducer.userSaid(it, "again") }
            .let { ConversationReducer.agentDelta(it, "second answer") }
        assertEquals(3, c.messages.size)
        assertEquals("second answer", c.messages.last().text)
        assertEquals(ChatRole.AGENT, c.messages.last().role)
    }

    @Test
    fun `awaitingAgent is set by a user turn and cleared by agent output`() {
        val waiting = ConversationReducer.userSaid(start(), "hi")
        assertTrue(waiting.awaitingAgent)
        assertFalse(ConversationReducer.agentDelta(waiting, "x").awaitingAgent)
    }

    @Test
    fun `a failure becomes a visible message rather than silence`() {
        val c = ConversationReducer.agentFailed(start(), "exit 1: no such model")
        assertEquals(1, c.messages.size)
        assertEquals("exit 1: no such model", c.messages[0].error)
        assertFalse(c.awaitingAgent)
    }

    @Test
    fun `a failure settles a streaming message first`() {
        val c = start()
            .let { ConversationReducer.agentDelta(it, "half a sen") }
            .let { ConversationReducer.agentFailed(it, "boom") }
        assertFalse(c.messages[0].streaming)
        assertEquals(2, c.messages.size)
    }

    @Test
    fun `approvals are tracked by id`() {
        val a = ToolApproval("a1", "Bash", "run tests", listOf("src/x.kt"), "npm test", RiskLevel.LOW)
        val c = ConversationReducer.toolRequested(start(), a)
        assertEquals(1, c.pendingApprovals.size)
        assertTrue(ConversationReducer.toolResolved(c, "a1").pendingApprovals.isEmpty())
    }

    @Test
    fun `re-requesting the same approval id does not stack duplicates`() {
        val a = ToolApproval("a1", "Bash", "run tests")
        val c = start()
            .let { ConversationReducer.toolRequested(it, a) }
            .let { ConversationReducer.toolRequested(it, a) }
        assertEquals(1, c.pendingApprovals.size)
    }

    @Test
    fun `resolving an unknown approval id is harmless`() {
        val c = ConversationReducer.toolResolved(start(), "nope")
        assertTrue(c.pendingApprovals.isEmpty())
    }

    @Test
    fun `several approvals can be pending at once`() {
        val c = start()
            .let { ConversationReducer.toolRequested(it, ToolApproval("a", "Bash", "x")) }
            .let { ConversationReducer.toolRequested(it, ToolApproval("b", "Write", "y")) }
        assertEquals(listOf("a", "b"), c.pendingApprovals.map { it.id })
    }

    @Test
    fun `memory is bounded no matter how long the session runs`() {
        var c = start()
        repeat(ConversationReducer.MAX_MESSAGES + 120) { i ->
            c = ConversationReducer.userSaid(c, "message $i")
        }
        assertEquals(ConversationReducer.MAX_MESSAGES, c.messages.size)
        // The survivors are the newest, in order.
        assertEquals("message 120", c.messages.first().text)
    }

    @Test
    fun `one runaway reply cannot exhaust memory`() {
        var c = start()
        val chunk = "z".repeat(50_000)
        repeat(20) { c = ConversationReducer.agentDelta(c, chunk) }
        assertTrue(
            "retained ${c.messages[0].text.length} chars",
            c.messages[0].text.length <= ConversationReducer.MAX_MESSAGE_CHARS,
        )
        assertTrue("truncation must be visible", c.messages[0].text.contains("truncated"))
    }

    @Test
    fun `cleared drops everything`() {
        val c = start()
            .let { ConversationReducer.userSaid(it, "hi") }
            .let { ConversationReducer.toolRequested(it, ToolApproval("a", "Bash", "x")) }
        assertTrue(ConversationReducer.cleared().isEmpty)
        assertTrue(ConversationReducer.cleared().pendingApprovals.isEmpty())
        assertFalse(c.isEmpty)
    }

    @Test
    fun `preview summarises the last non-empty message on one line`() {
        val c = start()
            .let { ConversationReducer.userSaid(it, "hello") }
            .let { ConversationReducer.agentDelta(it, "line one\nline two") }
        assertEquals("line one line two", ConversationReducer.preview(c))
    }

    @Test
    fun `preview truncates with an ellipsis`() {
        val c = ConversationReducer.agentDelta(start(), "x".repeat(200))
        val p = ConversationReducer.preview(c, maxChars = 20)
        assertEquals(20, p.length)
        assertTrue(p.endsWith("\u2026"))
    }

    @Test
    fun `preview of an empty conversation is blank`() {
        assertEquals("", ConversationReducer.preview(start()))
    }

    @Test
    fun `attachments travel with the user message`() {
        val a = ChatAttachment("diagram.png", "image/png", 2048, "/w/diagram.png")
        val c = ConversationReducer.userSaid(start(), "look", listOf(a))
        assertEquals(1, c.messages[0].attachments.size)
        assertTrue(c.messages[0].attachments[0].isImage)
    }

    @Test
    fun `agentFinished on an empty conversation is safe`() {
        assertFalse(ConversationReducer.agentFinished(start()).awaitingAgent)
    }

    @Test
    fun `agentFinished with no trailing agent message does not corrupt state`() {
        val c = start().let { ConversationReducer.userSaid(it, "hi") }
        val after = ConversationReducer.agentFinished(c)
        assertEquals(1, after.messages.size)
        assertEquals(ChatRole.USER, after.messages[0].role)
        assertNull(after.messages[0].error)
    }
}
