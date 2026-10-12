package com.dsh.mobile.ui

import com.dsh.mobile.data.ActiveAgent
import com.dsh.mobile.service.AgentRuntimeService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app bar must name the agent that is actually serving.
 *
 * It previously named the *configured* agent, so a device that had fallen back
 * to DeepSeek Harness still displayed "Claude Code (via gateway)" - a status
 * line that contradicts the log right beneath it.
 */
class DisplayedAgentTest {

    private fun running(agent: ActiveAgent) = AgentRuntimeService.ServiceStatus.Running(
        agent = agent,
        port = 3081,
        url = "http://127.0.0.1:3081",
        pidHint = "",
        workspace = "/tmp",
    )

    @Test
    fun `while running it names the agent that is serving`() {
        assertEquals(
            ActiveAgent.DEEPSEEK_HARNESS,
            displayedAgent(ActiveAgent.CLAUDE_CODE, running(ActiveAgent.DEEPSEEK_HARNESS)),
        )
    }

    @Test
    fun `when the running agent matches the configured one nothing changes`() {
        assertEquals(
            ActiveAgent.CODEX,
            displayedAgent(ActiveAgent.CODEX, running(ActiveAgent.CODEX)),
        )
    }

    @Test
    fun `before it boots it falls back to the configured agent`() {
        val booting = AgentRuntimeService.ServiceStatus.Booting("Starting…")
        assertEquals(ActiveAgent.CLAUDE_CODE, displayedAgent(ActiveAgent.CLAUDE_CODE, booting))
    }

    @Test
    fun `on failure it does not claim an agent is running`() {
        val failed = AgentRuntimeService.ServiceStatus.Error("nope", retryable = true)
        assertEquals(ActiveAgent.CODEX, displayedAgent(ActiveAgent.CODEX, failed))
    }
}
