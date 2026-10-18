package com.dsh.mobile.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {

    private fun resolved(input: String): CommandResolution.Resolved =
        SlashCommands.resolve(input) as CommandResolution.Resolved

    @Test
    fun `text without a leading slash is sent as typed`() {
        val r = SlashCommands.resolve("write a haiku")
        assertTrue(r is CommandResolution.Plain)
        assertEquals("write a haiku", (r as CommandResolution.Plain).text)
    }

    @Test
    fun `a bare slash opens the palette instead of sending`() {
        assertTrue(SlashCommands.resolve("/") is CommandResolution.Pending)
        assertTrue(SlashCommands.resolve("/   ") is CommandResolution.Pending)
    }

    @Test
    fun `a template command expands to its instruction`() {
        val r = resolved("/plan")
        assertEquals("plan", r.command.name)
        assertEquals(SlashCommands.find("plan")!!.payload, r.text)
    }

    @Test
    fun `extra text after a template command is appended as the request`() {
        val r = resolved("/plan add a dark mode toggle")
        assertTrue(r.text.contains("My request: add a dark mode toggle"))
        assertTrue(r.text.startsWith(SlashCommands.find("plan")!!.payload))
    }

    @Test
    fun `native commands are sent verbatim and never rewritten into prose`() {
        // The bug this guards: an agent that matches a literal token would stop
        // recognising "/help" if it arrived as an English sentence.
        assertEquals("/help", resolved("/help").text)
        assertEquals("/clear-context", resolved("/clear").text)
        assertEquals("/reindex-workspace", resolved("/reindex").text)
        assertEquals("/stop-generation", resolved("/stop").text)
    }

    @Test
    fun `trailing text on a native command is dropped not forwarded`() {
        // "/help me" must still arrive as "/help".
        assertEquals("/help", resolved("/help me").text)
        assertEquals("/clear-context", resolved("/clear please").text)
    }

    @Test
    fun `aliases resolve to the same command`() {
        assertEquals("/clear-context", resolved("/clear-context").text)
        assertEquals("/stop-generation", resolved("/stop-generation").text)
        assertEquals("clear", resolved("/clear").command.name)
    }

    @Test
    fun `command names are case insensitive`() {
        assertEquals("/help", resolved("/HELP").text)
        assertEquals("plan", resolved("/PLAN").command.name)
    }

    @Test
    fun `an unknown command is reported with suggestions, never sent`() {
        val r = SlashCommands.resolve("/bogus")
        assertTrue(r is CommandResolution.Unknown)
        assertEquals("bogus", (r as CommandResolution.Unknown).name)
        assertTrue(r.suggestions.isNotEmpty())
    }

    @Test
    fun `a partial name filters the palette`() {
        assertEquals(listOf("plan"), SlashCommands.matching("pl").map { it.name })
        assertTrue(SlashCommands.matching("c").any { it.name == "compact" })
        assertEquals(SlashCommands.ALL.size, SlashCommands.matching("").size)
    }

    @Test
    fun `an unknown partial still suggests the commands that match`() {
        val r = SlashCommands.resolve("/pl") as CommandResolution.Unknown
        assertTrue(r.suggestions.any { it.name == "plan" })
    }

    @Test
    fun `leading and trailing whitespace does not break resolution`() {
        assertEquals("plan", resolved("  /plan  ").command.name)
        assertEquals("/help", resolved(" /help ").text)
    }

    @Test
    fun `every quick chip resolves to a real command`() {
        assertTrue(SlashCommands.QUICK_CHIPS.isNotEmpty())
        for (chip in SlashCommands.QUICK_CHIPS) {
            val r = SlashCommands.resolve("/${chip.name}")
            assertTrue("chip ${chip.name} did not resolve", r is CommandResolution.Resolved)
        }
    }

    @Test
    fun `native and template kinds are both represented`() {
        assertTrue(SlashCommands.ALL.any { it.kind == CommandKind.NATIVE })
        assertTrue(SlashCommands.ALL.any { it.kind == CommandKind.TEMPLATE })
    }

    @Test
    fun `command tokens are unique so lookups cannot collide`() {
        val tokens = SlashCommands.ALL.flatMap { it.tokens }
        assertEquals(tokens.size, tokens.toSet().size)
    }
}
