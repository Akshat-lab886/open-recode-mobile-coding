package com.dsh.mobile.watchdog

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Performs one liveness probe against a loopback agent port.
 *
 * Probe order — and why:
 *
 *  1. **TCP connect (primary).** The listener accepting a connection IS the
 *     liveness signal. It costs one round-trip on loopback, needs no
 *     protocol agreement, and cannot be defeated by a route the server
 *     deliberately does not implement. This is the same check the boot
 *     sequence already gates on (`AgentRuntimeService.waitForPort`).
 *  2. **HTTP `GET /health`, then `GET /` (fallback).** Only reached when the
 *     TCP connect failed. It exists to (a) recover if the TCP failure was
 *     transient and the server is answering anyway, and (b) yield the most
 *     specific error to classify. Any status it returns is treated as ALIVE
 *     by [Liveness.onHttpStatus] — see that object for why 401/404 must not
 *     be read as "dead".
 *
 * Blocking; the caller is responsible for running it on an IO dispatcher.
 */
object EngineHealthProbe {

    /** Paths tried, in order, when the TCP connect did not succeed. */
    val PROBE_PATHS = listOf("/health", "/")

    fun probe(host: String, port: Int, connectTimeoutMs: Int): ProbeVerdict {
        val tcp = try {
            Socket().use { it.connect(InetSocketAddress(host, port), connectTimeoutMs) }
            null
        } catch (e: Exception) {
            e
        }
        if (tcp == null) {
            // The listener accepted us — the agent is alive, full stop. No
            // HTTP round-trip: any status the server would return is
            // irrelevant once the socket itself proves liveness.
            return ProbeVerdict(
                healthy = true,
                detail = "TCP connection to $host:$port accepted.",
            )
        }

        var lastError: Exception? = tcp
        for (path in PROBE_PATHS) {
            var conn: HttpURLConnection? = null
            try {
                conn = URL("http://$host:$port$path").openConnection() as HttpURLConnection
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = connectTimeoutMs
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "dsh-mobile-watchdog")
                conn.connect()
                // getResponseCode() does NOT throw on 4xx/5xx — it returns
                // them. Reading the code is exactly the signal we want.
                val code = conn.responseCode
                if (code != -1) return Liveness.onHttpStatus(code, path)
            } catch (e: Exception) {
                lastError = e
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return Liveness.onTransportFailure(lastError, port, connectTimeoutMs, host)
    }
}