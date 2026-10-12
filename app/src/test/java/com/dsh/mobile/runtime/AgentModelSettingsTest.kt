package com.dsh.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent boots with no usable provider unless this section is written.
 *
 * The shipped settings.yaml pointed at `http://localhost:20128/v1` with
 * `apiKeyEnv: GH_API_KEY` - a dead endpoint from another tool. With that in
 * place DSH renders send and attachment disabled and the chat box accepts
 * nothing, which is indistinguishable from a broken UI.
 *
 * The namespace and field names are not guesses: they come from the installed
 * agent's own `@deepseek-ai/dsh-agent-default-model` type definitions, and the
 * provider route from `@deepseek-ai/dsh-llm-deepseek`.
 */
class AgentModelSettingsTest {

    @Test
    fun `writes the namespace the agent actually reads`() {
        val yaml = AgentModelSettings.renderYamlSection(
            AgentModelSettings.PROVIDER_DEEPSEEK,
            AgentModelSettings.DEFAULT_MODEL,
        )
        assertTrue("namespace must be agent-default-model: $yaml", yaml.contains("agent-default-model:"))
        assertTrue(yaml.contains("provider: deepseek-official"))
        assertTrue(yaml.contains("model: deepseek-v4-flash"))
    }

    @Test
    fun `appends to a file that has no such section`() {
        val out = AgentModelSettings.replaceOrAppendSection(
            "ui-theme:\n  preference: dark\n",
            "agent-default-model",
            AgentModelSettings.renderYamlSection(
                AgentModelSettings.PROVIDER_DEEPSEEK,
                AgentModelSettings.DEFAULT_MODEL,
            ),
        )
        assertTrue(out.contains("ui-theme:"))
        assertTrue(out.contains("agent-default-model:"))
        assertTrue(out.contains("deepseek-v4-flash"))
    }

    @Test
    fun `replaces a previous selection without duplicating the key`() {
        val out = AgentModelSettings.replaceOrAppendSection(
            """
            ui-theme:
              preference: dark
            agent-default-model:
              provider: deepseek-official
              model: deepseek-v4-flash
            llm-pi-ai:
              providers:
                gh:
                  baseURL: http://localhost:20128/v1
            """.trimIndent() + "\n",
            "agent-default-model",
            AgentModelSettings.renderYamlSection(
                AgentModelSettings.PROVIDER_DEEPSEEK,
                "deepseek-v4-pro",
            ),
        )
        assertEquals("the key must appear exactly once", 1, Regex("agent-default-model:").findAll(out).count())
        assertTrue(out.contains("model: deepseek-v4-pro"))
        assertFalse("the stale value must be gone", out.contains("model: deepseek-v4-flash\n"))
    }

    @Test
    fun `replacing one section leaves the agents other keys alone`() {
        val out = AgentModelSettings.replaceOrAppendSection(
            "ui-theme:\n  preference: dark\nagent-presets:\n  default: ptc\n",
            "agent-default-model",
            AgentModelSettings.renderYamlSection(
                AgentModelSettings.PROVIDER_DEEPSEEK,
                AgentModelSettings.DEFAULT_MODEL,
            ),
        )
        // Reformatting the agent's own settings as a side effect of setting a
        // model is exactly the kind of surprise that loses a user's config.
        assertTrue(out.contains("ui-theme:"))
        assertTrue(out.contains("preference: dark"))
        assertTrue(out.contains("agent-presets:"))
        assertTrue(out.contains("default: ptc"))
    }

    @Test
    fun `handles an empty settings file`() {
        val out = AgentModelSettings.replaceOrAppendSection(
            "",
            "agent-default-model",
            AgentModelSettings.renderYamlSection(
                AgentModelSettings.PROVIDER_DEEPSEEK,
                AgentModelSettings.DEFAULT_MODEL,
            ),
        )
        assertTrue(out.contains("agent-default-model:"))
    }

    @Test
    fun `writing the same selection twice is a no-op`() {
        val section = AgentModelSettings.renderYamlSection(
            AgentModelSettings.PROVIDER_DEEPSEEK,
            AgentModelSettings.DEFAULT_MODEL,
        )
        val first = AgentModelSettings.replaceOrAppendSection("", "agent-default-model", section)
        val second = AgentModelSettings.replaceOrAppendSection(first, "agent-default-model", section)
        assertEquals("an unchanged file must not be rewritten on every boot", first, second)
    }

    @Test
    fun `every advertised model is one the adapter really registers`() {
        // Guards against shipping a model id that does not exist: the ids were
        // read out of the installed plugin rather than invented.
        listOf("deepseek-v4-flash", "deepseek-v4-pro", "deepseek-v4-flash-vision-exp", "deepseek-flash")
            .forEach { assertTrue("$it missing", AgentModelSettings.KNOWN_MODELS.contains(it)) }
        assertTrue(AgentModelSettings.DEFAULT_MODEL in AgentModelSettings.KNOWN_MODELS)
    }
}
