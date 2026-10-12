package com.dsh.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent ships its own working model default; our job is to get out of the
 * way.
 *
 * `@deepseek-ai/dsh-base` registers the `llm-deepseek` adapter and already
 * configures:
 *
 *     - id: agent-default-model
 *       name: '@deepseek-ai/dsh-agent-default-model'
 *       config: { provider: deepseek-official, model: deepseek-flash }
 *
 * A user-layer `agent-default-model` section REPLACES that row's whole config
 * instead of merging into it. An earlier version of this class wrote exactly
 * such a section, which shadowed the working default and left chat disabled
 * with "Chat and attachments are off until a model is set". Verified on device:
 * with the override the banner shows; without it, chat is enabled.
 */
class AgentModelSettingsTest {

    @Test
    fun `a stale override is detected`() {
        val yaml = """
            ui-theme:
              preference: dark
            agent-default-model:
              provider: deepseek-official
              model: deepseek-v4-flash
        """.trimIndent() + "\n"
        assertTrue(yaml.lines().any { it.trimEnd() == "agent-default-model:" })
    }

    @Test
    fun `removing the override leaves the agent's other settings alone`() {
        val yaml = """
            ui-theme:
              preference: dark
            agent-default-model:
              provider: deepseek-official
              model: deepseek-v4-flash
            llm-pi-ai:
              providers:
                gh:
                  baseURL: http://localhost:20128/v1
        """.trimIndent() + "\n"
        val out = AgentModelSettings.replaceOrAppendSection(
            yaml,
            AgentModelSettings.SECTION,
            "",
        )
        assertFalse("the override must be gone", out.contains("agent-default-model:"))
        assertFalse(out.contains("deepseek-v4-flash"))
        // Everything the agent owns has to survive: this class only ever removes
        // its own section.
        assertTrue(out.contains("ui-theme:"))
        assertTrue(out.contains("preference: dark"))
        assertTrue(out.contains("llm-pi-ai:"))
        assertTrue(out.contains("localhost:20128"))
    }

    @Test
    fun `removing an absent section is a no-op`() {
        val yaml = "ui-theme:\n  preference: dark\n"
        assertEquals(
            yaml,
            AgentModelSettings.replaceOrAppendSection(yaml, AgentModelSettings.SECTION, ""),
        )
    }

    @Test
    fun `apply does nothing when the file is absent`() {
        val missing = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "dsh-absent-${System.nanoTime()}.yaml",
        )
        assertFalse(AgentModelSettings.apply(missing))
        assertFalse(missing.exists())
    }

    @Test
    fun `apply removes an override and reports the change`() {
        val f = java.io.File.createTempFile("dsh-settings", ".yaml")
        try {
            f.writeText(
                "ui-theme:\n  preference: dark\nagent-default-model:\n" +
                    "  provider: deepseek-official\n  model: deepseek-v4-flash\n",
            )
            assertTrue("apply must report that it changed the file", AgentModelSettings.apply(f))
            val after = f.readText()
            assertFalse(after.contains("agent-default-model:"))
            assertTrue(after.contains("preference: dark"))
            // Idempotent: a second boot must not keep rewriting the file.
            assertFalse(AgentModelSettings.apply(f))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `apply is a no-op on a clean config`() {
        val f = java.io.File.createTempFile("dsh-clean", ".yaml")
        try {
            f.writeText("ui-theme:\n  preference: dark\n")
            assertFalse(AgentModelSettings.apply(f))
            assertTrue(f.readText().contains("preference: dark"))
        } finally {
            f.delete()
        }
    }
}
