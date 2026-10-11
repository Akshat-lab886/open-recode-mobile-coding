package com.dsh.mobile.watchdog

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end probe tests against a real loopback socket.
 *
 * These pin the observable behaviour that the restart loop depends on. Each
 * of the "fenced" cases below is a status the REAL `dsh --profile web`
 * server returns, and every one of them failed under the old 2xx-only rule.
 */
class EngineHealthProbeTest {

    private val host = "127.0.0.1"
    private val timeoutMs = 3_000

    @Test
    fun `a server answering 404 on health is alive`() {
        StatusServer(404).use { server ->
            val verdict = EngineHealthProbe.probe(host, server.port, timeoutMs)
            assertTrue("404 /health must not be read as dead: ${verdict.detail}", verdict.healthy)
        }
    }

    @Test
    fun `a server answering 401 on root is alive`() {
        StatusServer(401).use { server ->
            val verdict = EngineHealthProbe.probe(host, server.port, timeoutMs)
            assertTrue("401 / must not be read as dead: ${verdict.detail}", verdict.healthy)
        }
    }

    @Test
    fun `a server answering 200 is alive`() {
        StatusServer(200).use { server ->
            assertTrue(EngineHealthProbe.probe(host, server.port, timeoutMs).healthy)
        }
    }

    @Test
    fun `repeated probes stay healthy and do not flap`() {
        StatusServer(404).use { server ->
            repeat(5) {
                assertTrue(EngineHealthProbe.probe(host, server.port, timeoutMs).healthy)
            }
        }
    }

    @Test
    fun `a closed port is unhealthy with the crash wording`() {
        val dead = StatusServer(404)
        val port = dead.port
        dead.close()

        val verdict = EngineHealthProbe.probe(host, port, timeoutMs)
        assertFalse("a refused connection is the one real dead signal", verdict.healthy)
        assertEquals("The engine stopped unexpectedly.", verdict.friendlyReason)
    }

    @Test
    fun `a listener that accepts but never answers is still alive`() {
        // Documented semantics: the accepted TCP connection IS the liveness
        // signal. A wedged HTTP layer must not trigger a restart loop — the
        // user would lose in-flight work over a route that is merely stuck.
        BlackHoleServer().use { server ->
            val verdict = EngineHealthProbe.probe(host, server.port, 500)
            assertTrue(
                "TCP connect alone proves the server is alive: ${verdict.detail}",
                verdict.healthy,
            )
        }
    }

    /**
     * Loopback HTTP/1.1 server that answers *every* path with one status —
     * the shape of the real DSH server, where `/health` is a 404 and `/` is
     * a 401. Hand-rolled on a raw [ServerSocket] because `com.sun.net.httpserver`
     * is not on the Android unit-test compile classpath.
     */
    private class StatusServer(private val status: Int) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            thread(name = "status-server", isDaemon = true) {
                while (!server.isClosed) {
                    val sock = try {
                        server.accept()
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    thread(isDaemon = true) {
                        runCatching {
                            sock.use {
                                val reader = it.getInputStream().bufferedReader()
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                it.getOutputStream().apply {
                                    write(
                                        ("HTTP/1.1 $status X\r\n" +
                                            "Content-Length: 0\r\n" +
                                            "Connection: close\r\n\r\n").toByteArray(),
                                    )
                                    flush()
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    /** Accepts connections and never writes a byte — a wedged server. */
    private class BlackHoleServer : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort

        init {
            thread(name = "black-hole", isDaemon = true) {
                val held = mutableListOf<Socket>()
                while (!server.isClosed) {
                    val sock = try {
                        server.accept()
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    held += sock // keep it open; never answer
                }
                held.forEach { runCatching { it.close() } }
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }
}