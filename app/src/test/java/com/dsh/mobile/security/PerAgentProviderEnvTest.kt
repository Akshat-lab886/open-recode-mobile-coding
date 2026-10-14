package com.dsh.mobile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure env-var mapping extracted into [PerAgentProviderStore.envFor],
 * which is how each agent's child process receives its credential + endpoint.
 *
 * The rule under test: an agent-specific key wins over the shared provider key,
 * the provider maps to the correct env var, and an empty key injects nothing
 * (an empty-string var would let a missing key read as "intentionally blank").
 */
class PerAgentProviderEnvTest {

    // Build a store without Android by calling the pure mapping function that
    // environmentFor delegates to (no Context/EncryptedSharedPreferences needed).
    private fun env(provider: String, agentKey: String, baseUrl: String? = null) =
        PerAgentProviderStore.envFor(provider, agentKey, baseUrl)

    @Test
    fun `deepseek key maps to DEEPSEEK_API_KEY and DSH_API_KEY`() {
        val e = env("deepseek", "sk-xxx")
        assertEquals("sk-xxx", e["DEEPSEEK_API_KEY"])
        assertEquals("sk-xxx", e["DSH_API_KEY"])
        assertFalse(e.containsKey("OPENAI_API_KEY"))
    }

    @Test
    fun `openrouter key maps to OPENROUTER_API_KEY and OPENAI_API_KEY`() {
        val e = env("openrouter", "or-xxx")
        assertEquals("or-xxx", e["OPENROUTER_API_KEY"])
        assertEquals("or-xxx", e["OPENAI_API_KEY"])
    }

    @Test
    fun `anthropic key maps to ANTHROPIC_API_KEY only`() {
        val e = env("anthropic", "sk-ant-xxx")
        assertEquals("sk-ant-xxx", e["ANTHROPIC_API_KEY"])
        assertFalse(e.containsKey("OPENAI_API_KEY"))
    }

    @Test
    fun `a blank agent key injects no credential vars`() {
        val e = env("openrouter", "")
        assertTrue(e.isEmpty())
    }

    @Test
    fun `a custom base URL sets the openai-compatible origin triple`() {
        val e = env("deepseek", "sk-xxx", baseUrl = "https://openrouter.ai")
        assertEquals("https://openrouter.ai", e["OPENAI_BASE_URL"])
        assertEquals("https://openrouter.ai", e["ANTHROPIC_BASE_URL"])
        assertEquals("https://openrouter.ai", e["OPENAI_API_BASE"])
    }

    @Test
    fun `an invalid base URL is dropped rather than injected`() {
        // A typo here is the classic exfiltration shape (credential sent to a
        // wrong host), so the validator must reject it before it reaches env.
        val e = env("deepseek", "sk-xxx", baseUrl = "https://evil.example@attacker.com")
        assertFalse(e.containsKey("OPENAI_BASE_URL"))
        assertFalse(e.containsKey("OPENAI_API_BASE"))
    }
}
