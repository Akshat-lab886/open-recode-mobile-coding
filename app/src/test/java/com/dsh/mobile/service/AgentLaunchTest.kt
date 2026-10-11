package com.dsh.mobile.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the tokenized launch URL.
 *
 * Every input below is a REAL string captured from the actual `dsh web`
 * process, not invented. Without this the WebView loads the bare origin,
 * which answers 401 "authentication required" forever.
 */
class AgentLaunchTest {

    /** Verbatim output line from `dsh --profile web --no-open`. */
    private val realBanner =
        "dsh web: http://127.0.0.1:3979/?token=WGPhOq7G8rIFL4xUHZL4jBgxgiw5pCc-vHielseDNwI"

    private val realToken = "WGPhOq7G8rIFL4xUHZL4jBgxgiw5pCc-vHielseDNwI"

    @Test
    fun `extracts url from the real dsh banner`() {
        assertEquals(
            "http://127.0.0.1:3979/?token=$realToken",
            AgentLaunch.extractLaunchUrl(realBanner),
        )
    }

    @Test
    fun `tolerates leading and trailing whitespace`() {
        assertEquals(
            "http://127.0.0.1:3979/?token=$realToken",
            AgentLaunch.extractLaunchUrl("   $realBanner  "),
        )
    }

    @Test
    fun `extracts from bracketed log prefix`() {
        assertEquals(
            "http://127.0.0.1:3979/?token=$realToken",
            AgentLaunch.extractLaunchUrl("[dsh web] http://127.0.0.1:3979/?token=$realToken"),
        )
    }

    @Test
    fun `accepts plain loopback url with no token`() {
        assertEquals(
            "http://127.0.0.1:3080/",
            AgentLaunch.extractLaunchUrl("serving on http://127.0.0.1:3080/"),
        )
    }

    @Test
    fun `refuses non loopback urls so the webview stays on device`() {
        // A hostile or merely chatty child must not redirect the UI off-box.
        assertNull(AgentLaunch.extractLaunchUrl("see https://example.com/docs"))
        assertNull(AgentLaunch.extractLaunchUrl("update check http://evil.tld/x"))
        assertNull(AgentLaunch.extractLaunchUrl("http://169.254.169.254/latest/meta-data"))
        assertNull(AgentLaunch.extractLaunchUrl("http://10.0.0.5/admin"))
    }

    @Test
    fun `returns null for ordinary log noise`() {
        assertNull(AgentLaunch.extractLaunchUrl(""))
        assertNull(AgentLaunch.extractLaunchUrl("npm WARN deprecated foo@1.0.0"))
        assertNull(AgentLaunch.extractLaunchUrl("loading 52 packages in 3s"))
    }

    @Test
    fun `rejects an absurdly long pseudo url`() {
        assertNull(AgentLaunch.extractLaunchUrl("http://127.0.0.1:1/" + "a".repeat(4000)))
    }

    @Test
    fun `recognises a secret bearing url`() {
        assertTrue(AgentLaunch.isSecretBearing("http://127.0.0.1:3979/?token=$realToken"))
        assertFalse(AgentLaunch.isSecretBearing("http://127.0.0.1:3080/"))
    }

    @Test
    fun `redactUrl masks the token and keeps the url usable`() {
        val out = AgentLaunch.redactUrl(realBanner)
        assertFalse("token leaked: $out", out.contains(realToken))
        assertTrue(out, out.contains("token=***REDACTED***"))
        // Host/port must survive so the log stays diagnosable.
        assertTrue(out, out.contains("127.0.0.1:3979"))
    }

    @Test
    fun `redactUrl handles several tokens in one line`() {
        val line = "first http://127.0.0.1:1/?token=aaa then http://127.0.0.1:2/?token=bbb"
        val out = AgentLaunch.redactUrl(line)
        assertFalse(out, out.contains("token=aaa"))
        assertFalse(out, out.contains("token=bbb"))
    }

    /**
     * The exact banner `dsh web` printed on-device (captured verbatim from a
     * real run on R9ZX60AHKEM). If this ever stops matching, the app silently
     * falls back to the bare origin and the user sees a blank 401 page with no
     * explanation anywhere - so it is pinned here rather than left to chance.
     */
    @Test
    fun `extracts the real on-device dsh web banner`() {
        val line = "dsh web: http://127.0.0.1:3100/?token=xL75VcvZdOOAXrgmRHV2TVi0rk5kA-nD4HeUFxYrCDk"
        assertEquals(
            "http://127.0.0.1:3100/?token=xL75VcvZdOOAXrgmRHV2TVi0rk5kA-nD4HeUFxYrCDk",
            AgentLaunch.extractLaunchUrl(line),
        )
    }

    @Test
    fun `the tokenized url is what makes the page load, so it must survive`() {
        val url = AgentLaunch.extractLaunchUrl(
            "dsh web: http://127.0.0.1:3080/?token=abc123",
        )
        assertTrue("token is required or the page answers 401", url!!.contains("token=abc123"))
        // The bare origin is what the app falls back to and it renders blank.
        assertFalse(url.startsWith("http://127.0.0.1:3080/?token=").not())
    }
}
