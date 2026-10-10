package com.dsh.mobile.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secret redaction is the safety-critical part of the diagnostics exporter:
 * a leak here ships users' API keys into a pasted bug report.
 */
class RedactionTest {

    // Mirrors DiagnosticsStore.redact, which is private. Exercises the same
    // regexes/constants; if the store changes, update this copy in lockstep.
    private val KEY_TOKEN_RE = Regex(
        "(sk-ant-[A-Za-z0-9\\-_]+|sk-or-v1-[A-Za-z0-9\\-_]+|sk-[A-Za-z0-9]{16,}|" +
            "ds_[A-Za-z0-9\\-_]+|Bearer\\s+[A-Za-z0-9\\-_.~+/=]+)",
    )
    private val LONG_SECRET_RE = Regex("\\b[A-Za-z0-9_\\-]{40,}\\b")
    private val ENV_ASSIGN_RE = Regex(
        "(?i)(DEEPSEEK_API_KEY|DSH_API_KEY|ANTHROPIC_API_KEY|OPENROUTER_API_KEY|OPENAI_API_KEY)\\s*=\\s*[^\\s]+",
    )

    private fun redact(text: String): String {
        var out = text
        out = KEY_TOKEN_RE.replace(out, "***REDACTED***")
        out = LONG_SECRET_RE.replace(out, "***REDACTED***")
        out = ENV_ASSIGN_RE.replace(out, "$1=***REDACTED***")
        return out
    }

    @Test
    fun `redacts deepseek env assignment`() {
        val out = redact("DEEPSEEK_API_KEY=abcdef1234567890abcdef")
        assertTrue(out, out.contains("DEEPSEEK_API_KEY"))
        assertTrue(out, out.contains("***REDACTED***"))
        assertTrue("secret leaked: $out", !out.contains("abcdef1234567890abcdef"))
    }

    @Test
    fun `redacts anthropic style key token`() {
        val out = redact("Authorization: sk-ant-api03-AAAABBBBCCCCDDDDEEEEFFFFGGGG")
        assertTrue("secret leaked: $out", !out.contains("AAAABBBBCCCCDDDDEEEEFFFFGGGG"))
        assertTrue(out, out.contains("***REDACTED***"))
    }

    @Test
    fun `redacts openrouter key token`() {
        val out = redact("using sk-or-v1-1234567890abcdef1234567890abcdef")
        assertTrue("secret leaked: $out", !out.contains("1234567890abcdef1234567890abcdef"))
    }

    @Test
    fun `redacts bearer token`() {
        val out = redact("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig")
        assertTrue("secret leaked: $out", !out.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
    }

    @Test
    fun `redacts long opaque token`() {
        val token = "A".repeat(48)
        val out = redact("token=$token end")
        assertTrue("secret leaked: $out", !out.contains(token))
    }

    @Test
    fun `preserves ordinary log text`() {
        val line = "Booting DeepSeek Harness on 127.0.0.1:3080 in 1240ms"
        assertEquals(line, redact(line))
    }

    @Test
    fun `redacts multiple secrets in one line`() {
        val out = redact("DSH_API_KEY=sk-abcdefghijklmnopqrst and OPENAI_API_KEY=sk-zyxwvutsrqponmlkji")
        assertTrue(out, !out.contains("sk-abcdefghijklmnopqrst"))
        assertTrue(out, !out.contains("sk-zyxwvutsrqponmlkji"))
    }
}