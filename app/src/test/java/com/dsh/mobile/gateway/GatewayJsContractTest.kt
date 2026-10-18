package com.dsh.mobile.gateway

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Cross-implementation contract tests for gateway.js.
 *
 * The approval patterns exist in TWO places — VisualGateway.kt (Kotlin) and
 * gateway.js (JS, run inside the device's Node). A drift between them is the
 * exact failure that hangs an agent on blocked stdin with no approval sheet.
 * These tests extract the patterns straight out of the JS source so the copy
 * cannot quietly rot.
 *
 * They also assert the JS parses, and that no backend is hard-coded to a
 * non-loopback bind.
 */
class GatewayJsContractTest {

    private val repoRoot: File = File(System.getProperty("user.dir")).let { dir ->
        // Gradle runs tests with the module dir as CWD.
        generateSequence(dir) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/visual-gateway/gateway.js").exists() }
            ?: dir
    }

    private fun gatewaySource(): String =
        File(repoRoot, "app/src/main/assets/visual-gateway/gateway.js").readText()

    private fun patternLiterals(vararg names: String): List<String> {
        val src = gatewaySource()
        return names.map { name ->
            val start = src.indexOf("$name = [")
            assertTrue("could not locate $name in gateway.js", start >= 0)
            val end = src.indexOf("];", start)
            src.substring(start, end)
        }
    }

    @Test
    fun `gateway js parses as valid javascript`() {
        val node = File("/usr/bin/env").let { _ ->
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, "node") }
                .firstOrNull { it.canExecute() }
        }
        assumeTrue("node not available; skipping syntax check", node != null)
        val proc = ProcessBuilder(node!!.absolutePath, "-c", gatewaySource().let {
            File(repoRoot, "app/src/main/assets/visual-gateway/gateway.js").absolutePath
        }).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("node -c reported errors:\n$out", 0, proc.waitFor())
    }

    @Test
    fun `gateway follows its child down so the service can restart it`() {
        // The service's restart logic waits on THIS process, not on the backend
        // CLI. If the gateway outlives its child, a dead backend is invisible:
        // the app reads "Running" while the UI it serves is empty and no error
        // is shown anywhere. Observed on-device with codex exiting immediately
        // (no network): the status stayed Running with a dead backend.
        val handler = gatewaySource()
            .substringAfter("child.on('exit'")
            .substringBefore("child.on('error'")
        assertTrue(
            "gateway must announce the exit to attached UIs:\n$handler",
            handler.contains("type: 'exit'"),
        )
        assertTrue(
            "gateway must exit when its child exits, or the service never learns:\n$handler",
            handler.contains("process.exit("),
        )
        assertTrue(
            "gateway must not self-respawn (it would fight the watchdog):\n$handler",
            !handler.contains("startChild()"),
        )
    }

    @Test
    fun `approval patterns include the allow-to-run wording`() {
        val block = patternLiterals("APPROVAL_PATTERNS").first()
        assertTrue(
            "gateway.js must match \"Allow <agent> to run <cmd>?\"",
            block.contains("allow .{0,40}to run"),
        )
        assertTrue(block.contains("do you want to run"))
        assertTrue(block.contains("y\\/n"))
    }

    @Test
    fun `file patterns present`() {
        val block = patternLiterals("FILE_PATTERNS").first()
        assertTrue(block.contains("allow file modification"))
        assertTrue(block.contains("apply diff"))
    }

    @Test
    fun `forced onto loopback regardless of requested host`() {
        val src = gatewaySource()
        assertTrue(
            "gateway must pin host to 127.0.0.1",
            src.contains("out.host = '127.0.0.1'"),
        )
    }

    @Test
    fun `exposes health endpoint for the engine watchdog`() {
        val src = gatewaySource()
        assertTrue("must serve /health", src.contains("'/health'"))
        assertTrue("health must be 200", src.contains("return json(res, 200, {"))
    }

    @Test
    fun `replays backlog so early approvals are not lost`() {
        val src = gatewaySource()
        assertTrue(
            "SSE clients must receive a backlog replay (first approval is " +
                "emitted before the WebView connects)",
            src.contains("for (const ev of backlog)"),
        )
    }

    @Test
    fun `confines file access to the workspace root`() {
        val src = gatewaySource()
        assertTrue(
            "path traversal must be rejected",
            src.contains("if (!base.startsWith(path.resolve(CFG.cwd)))"),
        )
    }
}