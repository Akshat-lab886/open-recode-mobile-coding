package com.dsh.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSurfacePolicyTest {

    @Test
    fun `gateway-backed agents get the native surface`() {
        listOf("claude", "codex", "hermes", "antigravity").forEach {
            assertEquals("$it should stream natively", ChatSurface.NATIVE, ChatSurface.forAgent(it))
        }
    }

    @Test
    fun `dsh falls back because it has no gateway stream`() {
        // dsh boots its own server and requires a tokenized URL plus a WebSocket
        // mux; there is no SSE stream to read.
        assertEquals(ChatSurface.WEB_FALLBACK, ChatSurface.forAgent("dsh"))
    }

    @Test
    fun `opencode falls back`() {
        assertEquals(ChatSurface.WEB_FALLBACK, ChatSurface.forAgent("opencode"))
    }

    @Test
    fun `an unknown agent falls back rather than guessing native`() {
        // An unverified agent must not get a pane that may never fill.
        assertEquals(ChatSurface.WEB_FALLBACK, ChatSurface.forAgent("some-new-agent"))
        assertEquals(ChatSurface.WEB_FALLBACK, ChatSurface.forAgent(""))
    }

    @Test
    fun `no agent selected falls back`() {
        assertEquals(ChatSurface.WEB_FALLBACK, ChatSurface.forAgent(null))
    }

    @Test
    fun `every fallback explains itself`() {
        // An empty pane with no explanation is the failure mode this prevents.
        val reason = ChatSurface.reasonFor("dsh", ChatSurface.WEB_FALLBACK)
        assertTrue("should name the agent", reason.contains("dsh"))
        assertTrue("should say why", reason.contains("stream"))
    }

    @Test
    fun `the native reason does not apologise`() {
        val reason = ChatSurface.reasonFor("claude", ChatSurface.NATIVE)
        assertTrue(reason.contains("Native"))
        assertTrue("must not mention a fallback", !reason.contains("web UI"))
    }

    @Test
    fun `the null-agent reason is about waiting not about failing`() {
        val reason = ChatSurface.reasonFor(null, ChatSurface.WEB_FALLBACK)
        assertTrue(reason.contains("Waiting"))
    }
}
