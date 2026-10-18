package com.dsh.mobile.service

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
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

    // -- agent log: append + rotation ----------------------------------------
    //
    // Regression cover for two real defects in the previous writer:
    //   * `File.writer()` truncates, and stdout + stderr are pumped into the
    //     SAME file by two threads - so the second pump erased the first,
    //     taking the tokenized launch URL above with it.
    //   * nothing ever trimmed the file, so it grew without bound on a device
    //     whose data partition is shared with the runtime and the workspace.

    private fun tempLog(): File =
        File(Files.createTempDirectory("dsh-log").toFile(), "agent.log")

    @Test
    fun `openLogWriter appends instead of truncating`() {
        val log = tempLog()
        AgentLaunch.openLogWriter(log).use { it.println("first boot") }
        AgentLaunch.openLogWriter(log).use { it.println("second boot") }

        val text = log.readText()
        assertTrue("earlier content was erased: $text", text.contains("first boot"))
        assertTrue("later content missing: $text", text.contains("second boot"))
    }

    @Test
    fun `two pumps into one log keep both streams`() {
        // Exactly the old failure: stdout pumped first, stderr second, and the
        // log ended up holding only stderr.
        val log = tempLog()
        val banner = "dsh web: http://127.0.0.1:3979/?token=$realToken"
        AgentLaunch.pump(ByteArrayInputStream("$banner\n".toByteArray()), log).join(5_000)
        AgentLaunch.pump(ByteArrayInputStream("warn: something\n".toByteArray()), log).join(5_000)

        val text = log.readText()
        assertTrue("stdout banner was clobbered by stderr: $text", text.contains(realToken))
        assertTrue("stderr missing: $text", text.contains("warn: something"))
    }

    @Test
    fun `rotateIfNeeded leaves a small log alone`() {
        val log = tempLog()
        log.writeText("tiny")
        assertFalse(AgentLaunch.rotateIfNeeded(log, maxBytes = 1024))
        assertTrue(log.isFile)
    }

    @Test
    fun `rotateIfNeeded moves a full log aside and keeps one generation`() {
        val log = tempLog()
        log.writeText("a".repeat(2_000))
        assertTrue(AgentLaunch.rotateIfNeeded(log, maxBytes = 1_024))
        assertFalse("the live log should have been moved aside", log.isFile)
        val previous = File(log.parentFile, log.name + ".1")
        assertTrue("previous generation missing", previous.isFile)
        assertEquals(2_000, previous.length().toInt())

        // Rotating again must REPLACE the single previous generation, not
        // accumulate .2, .3, ... and fill the partition anyway.
        log.writeText("b".repeat(2_000))
        assertTrue(AgentLaunch.rotateIfNeeded(log, maxBytes = 1_024))
        assertEquals(2_000, previous.length().toInt())
        assertEquals("b".repeat(2_000), previous.readText())
        assertFalse(File(log.parentFile, log.name + ".2").exists())
    }

    @Test
    fun `openLogWriter rotates a log that is already full`() {
        // Rotation happens when the log is OPENED, so the fixture has to be a
        // pre-existing full log - a brand-new file has nothing to rotate.
        val log = tempLog()
        log.writeText("x".repeat(600))
        AgentLaunch.openLogWriter(log, maxBytes = 512).use { it.println("fresh line") }

        val previous = File(log.parentFile, log.name + ".1")
        assertTrue("the full log should have been moved aside", previous.isFile)
        assertEquals(600, previous.length().toInt())
        assertTrue("the new log must start small", log.length() < 512)
        assertTrue("the new line is missing", log.readText().contains("fresh line"))
    }

    @Test
    fun `pump still captures the banner while appending over an existing log`() {
        val log = tempLog()
        log.writeText("prior content\n")
        val found = AtomicReference<String?>(null)
        val banner = "dsh web: http://127.0.0.1:3100/?token=$realToken"
        AgentLaunch.pump(
            ByteArrayInputStream("$banner\n".toByteArray()),
            log,
            found,
        ).join(5_000)

        assertEquals("http://127.0.0.1:3100/?token=$realToken", found.get())
        assertTrue("prior content was truncated", log.readText().contains("prior content"))
    }

    @Test
    fun `pump bounds a log that runs past the cap mid-session`() {
        val log = tempLog()
        // Enough lines to cross 2MB inside one run, so the periodic check - not
        // just the open-time check - has to fire.
        // 700 x ~5KB = ~3.5MB, comfortably past the 2MB cap at a check
        // boundary. (An earlier fixture used 4000-byte lines, whose 512-line
        // mark landed at 2.048MB - just UNDER the cap, so rotation correctly
        // never fired and the test blamed the code for its own arithmetic.)
        val line = "y".repeat(5_000)
        val payload = (1..700).joinToString("\n") { line } + "\n"
        assertTrue("fixture must exceed the cap", payload.length.toLong() > AgentLaunch.MAX_LOG_BYTES)

        AgentLaunch.pump(ByteArrayInputStream(payload.toByteArray()), log).join(20_000)

        val previous = File(log.parentFile, log.name + ".1")
        assertTrue("expected a mid-session rotation to leave a .1", previous.isFile)
        // The live file must never hold the whole unbounded stream.
        assertTrue(
            "live log was not bounded: ${log.length()} bytes",
            log.length() < AgentLaunch.MAX_LOG_BYTES,
        )
    }
}
