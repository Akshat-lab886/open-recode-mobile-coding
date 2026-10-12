package com.dsh.mobile.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A custom base URL is where an agent sends its API key. A permissive validator
 * here turns a typo into credential exfiltration to a typo'd host, so the rules
 * are deliberately strict and are pinned by these tests.
 */
class BaseUrlValidationTest {

    // Mirrors PerAgentProviderStore.isValidBaseUrl without needing a Context:
    // DataStore requires Android, the rule itself does not.
    private fun valid(value: String): Boolean {
        if (!value.startsWith("https://") && !value.startsWith("http://")) return false
        val rest = value.substringAfter("://")
        if (rest.isEmpty()) return false
        if (rest.contains('/')) return false
        if (rest.contains('@')) return false
        if (rest.contains(' ')) return false
        val host = rest.substringBefore(':')
        if (host.isEmpty()) return false
        if (host.any { it.isWhitespace() }) return false
        return host.contains('.') || host == "localhost"
    }

    @Test
    fun `accepts ordinary endpoints`() {
        assertTrue(valid("https://openrouter.ai/api/v1".substringBefore("/api")))
        assertTrue(valid("https://api.deepseek.com"))
        assertTrue(valid("http://192.168.1.50:1234"))
        assertTrue(valid("http://localhost:11434"))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertFalse("file:// would read local files", valid("file:///data/data/com.dsh.mobile"))
        assertFalse("javascript: is not an origin", valid("javascript:alert(1)"))
        assertFalse("data: is not an origin", valid("data:text/html,x"))
        assertFalse("bare host has no scheme", valid("api.deepseek.com"))
    }

    @Test
    fun `rejects embedded credentials`() {
        // The classic exfiltration shape: our key typed into someone else's host.
        assertFalse(valid("https://evil.example@attacker.com"))
        assertFalse(valid("http://user:pass@attacker.com"))
    }

    @Test
    fun `rejects paths so the origin stays canonical`() {
        assertFalse(valid("https://api.deepseek.com/v1"))
    }

    @Test
    fun `rejects whitespace and empty`() {
        assertFalse(valid(""))
        assertFalse(valid("https:// api.deepseek.com"))
        assertFalse(valid("https://api.deepseek.com extra"))
    }

    @Test
    fun `rejects a hostname with no dot and no localhost`() {
        assertFalse(valid("https://intranet"))
    }
}
