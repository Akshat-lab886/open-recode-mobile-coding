package com.dsh.mobile.runtime

import android.util.Log
import com.dsh.mobile.chat.GatewayStreamEvent
import com.dsh.mobile.chat.GatewayStreamEvents
import com.dsh.mobile.chat.SseFramer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Subscribes to `gateway.js`'s `GET /events` SSE stream.
 *
 * This is the native conversation's live source. `gateway.js` already owns the
 * CLI child's stdin/stdout and parses it, so consuming this stream gives the
 * native surface real agent output WITHOUT re-plumbing the launch path or
 * fighting the WebView over the same pipes.
 *
 * The connection is held open for the life of the agent, so the read timeout is
 * infinite by design; [stop] closes it. A dropped stream is reported once via
 * [onClosed] rather than retried in a tight loop, because the usual cause is the
 * agent exiting and a reconnect storm would not fix that.
 */
class GatewayStreamClient(
    private val port: Int,
    private val onEvent: (GatewayStreamEvent) -> Unit,
    private val onClosed: (reason: String?) -> Unit = {},
) {
    private var job: Job? = null

    @Volatile
    private var connection: HttpURLConnection? = null

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val framer = SseFramer()
            var failure: String? = null
            try {
                val conn = (URL("http://127.0.0.1:$port/events").openConnection() as HttpURLConnection)
                connection = conn
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "text/event-stream")
                // No caching: an intermediary must never replay a stale stream.
                conn.setRequestProperty("Cache-Control", "no-cache")
                // 0 = wait forever. The stream is meant to stay open.
                conn.readTimeout = 0
                conn.connectTimeout = 5_000
                conn.connect()

                val stream = conn.inputStream
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                reader.useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        val payload = framer.onLine(line) ?: continue
                        runCatching { onEvent(GatewayStreamEvents.parse(payload)) }
                            .onFailure { Log.w(TAG, "stream listener failed", it) }
                    }
                }
            } catch (t: Throwable) {
                failure = t.message ?: t::class.java.simpleName
                if (isActive) Log.w(TAG, "gateway stream ended: $failure")
            } finally {
                connection = null
                if (isActive) runCatching { onClosed(failure) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Cancel alone does not unblock a thread parked in a socket read on all
        // platforms; disconnecting the connection does.
        runCatching { connection?.disconnect() }
        connection = null
    }

    private companion object {
        const val TAG = "GatewayStream"
    }
}
