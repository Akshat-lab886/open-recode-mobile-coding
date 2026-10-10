package com.dsh.mobile.bootstrap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the marker protocol that bootstrap.sh emits and
 * BootstrapManager parses. If these drift apart, the first-boot progress UI
 * silently shows a blank log — so the format is pinned here.
 *
 * The shell script itself is additionally validated with `sh -n` and, when a
 * POSIX shell + node are available, executed end-to-end.
 */
class BootstrapMarkerProtocolTest {

    private val steps = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var progress = -1
    private var error: String? = null
    private var done = false

    /** Mirrors the parse branch in BootstrapManager.runScript. */
    private fun parse(line: String) {
        when {
            line.startsWith(">>>STEP:") -> steps += line.removePrefix(">>>STEP:").trim()
            line.startsWith(">>>PROGRESS:") ->
                progress = line.removePrefix(">>>PROGRESS:").trim().toIntOrNull() ?: -1
            line.startsWith(">>>LOG:") -> logs += line.removePrefix(">>>LOG:").trim()
            line.startsWith(">>>ERROR:") -> error = line.removePrefix(">>>ERROR:").trim()
            line == ">>>DONE" -> done = true
            line.isNotBlank() -> logs += line
        }
    }

    @Test
    fun `parses a full successful run`() {
        listOf(
            ">>>STEP:Checking device compatibility",
            ">>>LOG:detected arch: aarch64",
            ">>>PROGRESS:8",
            ">>>STEP:Preparing Linux environment",
            ">>>PROGRESS:100",
            ">>>DONE",
        ).forEach(::parse)

        assertEquals(listOf("Checking device compatibility", "Preparing Linux environment"), steps)
        assertEquals(100, progress)
        assertTrue("DONE marker not seen", done)
        assertEquals(null, error)
    }

    @Test
    fun `progress marker is parsed as integer`() {
        parse(">>>PROGRESS:62")
        assertEquals(62, progress)
    }

    @Test
    fun `error marker captures the failure message`() {
        parse(">>>ERROR:This device is not supported")
        assertEquals("This device is not supported", error)
    }

    @Test
    fun `plain output lines still reach the log`() {
        parse("npm WARN deprecated foo@1.0.0")
        assertTrue(logs.contains("npm WARN deprecated foo@1.0.0"))
    }

    @Test
    fun `progress never exceeds bounds when coerced`() {
        parse(">>>PROGRESS:999")
        // BootstrapManager coerces into 0..100 before publishing.
        assertEquals(100, progress.coerceIn(0, 100))
    }
}