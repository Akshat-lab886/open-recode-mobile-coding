package com.dsh.mobile.watchdog

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the PURE liveness classifier.
 *
 * The bug: the watchdog required HTTP 2xx. Against the real
 * `dsh --profile web` that server answers `GET /health` with **404** and
 * `GET /` with **401**, so a perfectly healthy agent was declared dead on
 * every poll — 3 failures, ~15s, restart, and around again, forever.
 *
 * Every assertion here is a no-socket pure call, so the rule cannot rot
 * back into "non-200 means dead" without a red test.
 */
class LivenessTest {

    private val port = 3157
    private val timeoutMs = 3_000

    // -- the statuses the real server actually returns -----------------------

    @Test
    fun `404 on health is healthy`() {
        // dsh has no /health route at all. That is not a dead server.
        assertTrue(Liveness.onHttpStatus(404, "/health").healthy)
    }

    @Test
    fun `401 on root is healthy`() {
        // The /api browser-trust fence answers 401 to an untrusted browser.
        assertTrue(Liveness.onHttpStatus(401, "/").healthy)
    }

    @Test
    fun `every status counts as answering`() {
        // Including 5xx: the process is up, so a restart would not help.
        for (code in listOf(200, 204, 301, 302, 400, 401, 403, 404, 418, 500, 502, 503)) {
            val verdict = Liveness.onHttpStatus(code, "/health")
            assertTrue("HTTP $code must count as alive", verdict.healthy)
            assertTrue("HTTP $code should be recorded", verdict.detail.contains("$code"))
        }
    }

    // -- the only two unhealthy paths ---------------------------------------

    @Test
    fun `connect exception is unhealthy and blames a crash`() {
        val verdict = Liveness.onTransportFailure(
            ConnectException("Connection refused"), port, timeoutMs,
        )
        assertFalse(verdict.healthy)
        assertEquals("The engine stopped unexpectedly.", verdict.friendlyReason)
        assertTrue(verdict.detail.contains("$port"))
    }

    @Test
    fun `socket timeout is unhealthy and blames doze`() {
        val verdict = Liveness.onTransportFailure(
            SocketTimeoutException("Read timed out"), port, timeoutMs,
        )
        assertFalse(verdict.healthy)
        assertEquals(
            "The engine fell asleep (battery saver may have paused it).",
            verdict.friendlyReason,
        )
        assertTrue(
            "the timeout budget belongs in the detail: ${verdict.detail}",
            verdict.detail.contains("${timeoutMs}ms"),
        )
    }

    // -- robustness ---------------------------------------------------------

    @Test
    fun `wrapped causes are still classified`() {
        // Android's HttpURLConnection (OkHttp-backed) wraps the original
        // exception, so classifying only the outermost type would silently
        // downgrade a crash into "not responding".
        val wrapped = IOException("failed to fetch", ConnectException("Connection refused"))
        assertEquals(
            "The engine stopped unexpectedly.",
            Liveness.onTransportFailure(wrapped, port, timeoutMs).friendlyReason,
        )

        val nested = IOException(
            "read failed",
            IOException("inner", SocketTimeoutException("timeout")),
        )
        assertEquals(
            "The engine fell asleep (battery saver may have paused it).",
            Liveness.onTransportFailure(nested, port, timeoutMs).friendlyReason,
        )
    }

    @Test
    fun `unknown transport errors stay generic but still unhealthy`() {
        val verdict = Liveness.onTransportFailure(
            IOException("Network is unreachable"), port, timeoutMs,
        )
        assertFalse(verdict.healthy)
        assertEquals("The engine is not responding.", verdict.friendlyReason)
        assertTrue(verdict.detail.contains("Network is unreachable"))
    }

    @Test
    fun `a null error is unhealthy rather than healthy`() {
        // Failing open here would mask a dead agent for the whole poll loop.
        val verdict = Liveness.onTransportFailure(null, port, timeoutMs)
        assertFalse(verdict.healthy)
        assertEquals("Unknown probe failure.", verdict.detail)
    }

    @Test
    fun `a self referencing cause chain cannot hang the classifier`() {
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertFalse(Liveness.onTransportFailure(a, port, timeoutMs).healthy)
    }
}