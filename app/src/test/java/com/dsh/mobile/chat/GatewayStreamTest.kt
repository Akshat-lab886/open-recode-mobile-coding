package com.dsh.mobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseFramerTest {

    private fun framesFor(vararg lines: String): List<String> {
        val framer = SseFramer()
        return lines.mapNotNull { framer.onLine(it) }
    }

    @Test
    fun `a data line dispatches on the blank line`() {
        assertEquals(listOf("""{"type":"message"}"""), framesFor("""data: {"type":"message"}""", ""))
    }

    @Test
    fun `the field space is not part of the payload`() {
        assertEquals(listOf("{}"), framesFor("data: {}", ""))
    }

    @Test
    fun `only ONE leading space is stripped`() {
        // "data:  x" carries a meaningful leading space per the spec.
        assertEquals(listOf(" x"), framesFor("data:  x", ""))
    }

    @Test
    fun `multi-line data joins with a newline`() {
        assertEquals(listOf("a\nb"), framesFor("data: a", "data: b", ""))
    }

    @Test
    fun `comments are ignored`() {
        // gateway.js opens every stream with ": connected".
        assertEquals(emptyList<String>(), framesFor(": connected"))
        assertEquals(listOf("{}"), framesFor(": connected", "data: {}", ""))
    }

    @Test
    fun `a blank line with no accumulated data yields nothing`() {
        assertNull(SseFramer().onLine(""))
    }

    @Test
    fun `unknown fields are ignored`() {
        assertEquals(listOf("{}"), framesFor("id: 7", "event: message", "retry: 100", "data: {}", ""))
    }

    @Test
    fun `consecutive frames are dispatched separately`() {
        assertEquals(listOf("1", "2"), framesFor("data: 1", "", "data: 2", ""))
    }

    @Test
    fun `reset drops a partial frame`() {
        val framer = SseFramer()
        framer.onLine("data: partial")
        framer.reset()
        assertNull(framer.onLine(""))
    }

    @Test
    fun `a line with no colon is ignored`() {
        assertNull(SseFramer().onLine("garbage"))
    }
}

class GatewayStreamEventsTest {

    @Test
    fun `an echo becomes a user turn`() {
        val e = GatewayStreamEvents.parse("""{"type":"echo","role":"user","text":"fix the build"}""")
        assertEquals(GatewayStreamEvent.UserEcho("fix the build"), e)
    }

    @Test
    fun `a message becomes assistant text`() {
        val e = GatewayStreamEvents.parse("""{"type":"message","role":"assistant","text":"Done."}""")
        assertEquals(GatewayStreamEvent.AssistantText("Done."), e)
    }

    @Test
    fun `an approval carries id kind and summary`() {
        val e = GatewayStreamEvents.parse(
            """{"type":"approval","id":"appr-1","kind":"COMMAND","backend":"claude","summary":"Run npm test?"}""",
        ) as GatewayStreamEvent.ApprovalRequired
        assertEquals("appr-1", e.id)
        assertEquals("COMMAND", e.kind)
        assertEquals("Run npm test?", e.summary)
    }

    @Test
    fun `an error becomes a failure`() {
        val e = GatewayStreamEvents.parse("""{"type":"error","message":"spawn failed"}""")
        assertEquals(GatewayStreamEvent.Failed("spawn failed"), e)
    }

    @Test
    fun `an exit carries its code`() {
        assertEquals(GatewayStreamEvent.Exited(1), GatewayStreamEvents.parse("""{"type":"exit","code":1}"""))
    }

    @Test
    fun `raw output is ignored so it cannot bury the reply`() {
        assertEquals(
            GatewayStreamEvent.Ignored,
            GatewayStreamEvents.parse("""{"type":"raw","stream":"stdout","text":"npm WARN"}"""),
        )
    }

    @Test
    fun `an UNKNOWN type is ignored rather than fatal`() {
        // gateway.js ships separately, so a newer gateway may emit types this
        // build has never heard of. That must not tear down the stream.
        assertEquals(GatewayStreamEvent.Ignored, GatewayStreamEvents.parse("""{"type":"telemetry","x":1}"""))
    }

    @Test
    fun `malformed payloads are ignored`() {
        assertEquals(GatewayStreamEvent.Ignored, GatewayStreamEvents.parse("not json"))
        assertEquals(GatewayStreamEvent.Ignored, GatewayStreamEvents.parse("{}"))
        assertEquals(GatewayStreamEvent.Ignored, GatewayStreamEvents.parse(""))
    }

    @Test
    fun `an empty text is ignored rather than rendering a blank bubble`() {
        assertEquals(GatewayStreamEvent.Ignored, GatewayStreamEvents.parse("""{"type":"message","text":""}"""))
        assertEquals(GatewayStreamEvent.Ignored, GatewayStreamEvents.parse("""{"type":"echo"}"""))
    }

    @Test
    fun `agent text containing JSON is not mistaken for an event`() {
        // The end-to-end reason for the real parser: this is assistant text that
        // happens to contain a JSON object, and it must arrive intact.
        val payload = """{"type":"message","text":"config is {\"type\":\"exit\",\"code\":1}"}"""
        val e = GatewayStreamEvents.parse(payload)
        assertEquals(GatewayStreamEvent.AssistantText("""config is {"type":"exit","code":1}"""), e)
    }

    @Test
    fun `an approval with no id still parses`() {
        val e = GatewayStreamEvents.parse("""{"type":"approval","summary":"Allow?"}""")
            as GatewayStreamEvent.ApprovalRequired
        // A stable placeholder beats a null id: the approval still needs to be
        // answerable and dismissable.
        assertEquals("unknown", e.id)
        assertEquals("Allow?", e.summary)
    }
}
