package com.dsh.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the resume-id capture half of the Mobile-Harness
 * `bindResume` pattern.
 *
 * The failure this guards against: a parser that is too eager. Agent output is
 * full of `"sessionId":"unknown"`-style placeholders and of ids that belong to a
 * DIFFERENT harness. A wrong value here is not a no-op — it is handed to the
 * next spawn as `--resume <garbage>`, which makes the CLI exit non-zero and
 * reads to the user as "the agent is installed and nothing works". So every
 * rejection case below matters as much as the happy paths.
 */
class ResumeIdParserTest {

    private val uuid = "c1a2b3d4-e5f6-7890-abcd-ef1234567890"
    private val hex = "9a8b7c6d5e4f3a2b"

    @Test
    fun `antigravity conversationId is captured`() {
        assertEquals(
            uuid,
            ResumeIdParser.extractResumeId(
                "antigravity",
                """{"type":"Initialized","conversationId":"$uuid"}""",
            ),
        )
        assertEquals(
            uuid,
            ResumeIdParser.extractResumeId("antigravity", "conversation_id: $uuid"),
        )
    }

    @Test
    fun `codex thread id is captured from log and json`() {
        assertEquals(uuid, ResumeIdParser.extractResumeId("codex", "thread_id: $uuid"))
        assertEquals(uuid, ResumeIdParser.extractResumeId("codex", """{"threadId":"$uuid"}"""))
    }

    @Test
    fun `claude session id is captured`() {
        assertEquals(uuid, ResumeIdParser.extractResumeId("claude", """{"session_id":"$uuid"}"""))
        assertEquals(uuid, ResumeIdParser.extractResumeId("claude", "sessionId=$uuid"))
    }

    @Test
    fun `opencode cliSessionId is captured`() {
        assertEquals(hex, ResumeIdParser.extractResumeId("opencode", "cliSessionId=$hex"))
        assertEquals(uuid, ResumeIdParser.extractResumeId("opencode", """{"sessionId":"$uuid"}"""))
    }

    @Test
    fun `dsh session id is captured`() {
        assertEquals(uuid, ResumeIdParser.extractResumeId("dsh", "sessionId: $uuid"))
    }

    @Test
    fun `placeholder values are rejected`() {
        // These are the words CLIs actually print when there is no id yet.
        assertNull(ResumeIdParser.extractResumeId("claude", """{"sessionId":"unknown"}"""))
        assertNull(ResumeIdParser.extractResumeId("codex", "thread_id: none"))
        assertNull(ResumeIdParser.extractResumeId("antigravity", """{"conversationId":"null"}"""))
        assertNull(ResumeIdParser.extractResumeId("opencode", "sessionId=undefined"))
    }

    @Test
    fun `a value with no digit is rejected`() {
        // "resumethething" is 14 chars and alphanumeric but is prose, not an id.
        assertNull(ResumeIdParser.extractResumeId("claude", """{"sessionId":"resumethething"}"""))
    }

    @Test
    fun `a too-short value is rejected`() {
        assertNull(ResumeIdParser.extractResumeId("claude", """{"sessionId":"abc123"}"""))
    }

    @Test
    fun `another harness's key is not accepted`() {
        // codex binds threadId; a conversationId line must NOT be read as codex's
        // own id, or the next spawn gets `--thread-id <antigravity-id>`.
        assertNull(ResumeIdParser.extractResumeId("codex", """{"conversationId":"$uuid"}"""))
        // and vice versa
        assertNull(ResumeIdParser.extractResumeId("antigravity", """{"threadId":"$uuid"}"""))
    }

    @Test
    fun `blank and oversized lines are rejected without matching`() {
        assertNull(ResumeIdParser.extractResumeId("claude", ""))
        assertNull(ResumeIdParser.extractResumeId("claude", "   "))
        val huge = "sessionId: $uuid " + "x".repeat(9_000)
        assertNull(ResumeIdParser.extractResumeId("claude", huge))
    }

    @Test
    fun `keysFor gives the upstream bindResume spelling per harness`() {
        assertEquals(listOf("conversationId", "conversation_id"), ResumeIdParser.keysFor("antigravity"))
        assertEquals(listOf("threadId", "thread_id"), ResumeIdParser.keysFor("codex"))
        assertEquals(listOf("sessionId", "session_id"), ResumeIdParser.keysFor("claude"))
    }

    @Test
    fun `isPlausibleId enforces the id shape`() {
        assertTrue(ResumeIdParser.isPlausibleId(uuid))
        assertTrue(ResumeIdParser.isPlausibleId(hex))
        assertTrue(ResumeIdParser.isPlausibleId("abc_123-def_456"))
        assertFalse("no digit", ResumeIdParser.isPlausibleId("resumethething"))
        assertFalse("too short", ResumeIdParser.isPlausibleId("a1b2c3"))
        assertFalse("space", ResumeIdParser.isPlausibleId("id with space 1"))
        assertFalse("too long", ResumeIdParser.isPlausibleId("a1" + "b".repeat(200)))
    }
}
