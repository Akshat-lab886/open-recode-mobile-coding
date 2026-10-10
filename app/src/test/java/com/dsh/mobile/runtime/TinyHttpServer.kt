package com.dsh.mobile.runtime

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal loopback HTTP/1.1 server for downloader tests.
 *
 * Hand-rolled on a raw [ServerSocket] rather than `com.sun.net.httpserver`:
 * that package lives in the `jdk.httpserver` module, which is not on the
 * Android unit-test compile classpath.
 */
class TinyHttpServer(
    private val body: ByteArray,
    private val statusLine: String = "200 OK",
) : Closeable {

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    private val hits = java.util.concurrent.atomic.AtomicInteger(0)

    /** Number of requests served; used to assert assets are not re-fetched. */
    val requestCount: Int get() = hits.get()

    init {
        thread(name = "tiny-http", isDaemon = true) {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (_: Exception) {
                    null
                } ?: continue

                try {
                    sock.use { handle(it) }
                } catch (_: Exception) {
                    // A failed request must not kill the test server.
                }
            }
        }
    }

    private fun handle(sock: Socket) {
        hits.incrementAndGet()
        val reader = sock.getInputStream().bufferedReader()
        // Drain request line + headers so the client sees a clean response.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val out = sock.getOutputStream()
        if (statusLine.startsWith("404")) {
            out.write(
                ("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
        } else {
            out.write(
                ("HTTP/1.1 $statusLine\r\n" +
                    "Content-Type: application/octet-stream\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            out.write(body)
        }
        out.flush()
    }

    override fun close() {
        runCatching { server.close() }
    }
}