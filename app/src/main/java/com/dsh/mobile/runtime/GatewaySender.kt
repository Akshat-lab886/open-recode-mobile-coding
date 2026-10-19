package com.dsh.mobile.runtime

import android.util.Log
import com.dsh.mobile.proxy.ApprovalTokens
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Writes text to the CLI child's stdin by POSTing to `gateway.js` `/send`.
 *
 * This is the only route that reaches the child: `gateway.js` owns the pipe, so
 * a native surface cannot write to it directly. `/send` collapses newlines into
 * spaces (a stdin line is one message), so this is used for answers and single
 * messages rather than bulk input.
 *
 * Approvals go through here too, because a CLI approval is not an RPC - it is a
 * line on stdin, and the accepted token differs per tool. Answering with the
 * wrong token leaves the child blocked while the UI looks like it did something.
 */
object GatewaySender {

    private const val TAG = "GatewaySender"
    private const val TIMEOUT_MS = 8_000

    /** Posts [text] to the gateway on [port]. Returns true when accepted. */
    fun send(port: Int, text: String): Boolean = post(port, text)

    /**
     * Answers an approval prompt for [agentId].
     *
     * Fire-and-forget by design: the caller is a UI callback, and the gateway's
     * own echo/exit events are what actually update the transcript. Returning a
     * failure here would only tell us what the stream already will.
     */
    fun answer(port: Int, agentId: String, approved: Boolean): Boolean {
        val token = ApprovalTokens.answerFor(agentId, approved)
        return post(port, token)
    }

    private fun post(port: Int, text: String): Boolean {
        if (port <= 0 || text.isBlank()) return false
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://127.0.0.1:$port/send").openConnection() as HttpURLConnection)
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
                // JSON-encoded so a message containing a quote or newline does
                // not corrupt the body.
                it.write("""{"text":${quote(text)}}""")
                it.flush()
            }
            val code = connection.responseCode
            if (code !in 200..299) Log.w(TAG, "/send answered $code")
            code in 200..299
        } catch (t: Throwable) {
            Log.w(TAG, "/send failed: ${t.message}")
            false
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Minimal JSON string escaping; enough for message text. */
    private fun quote(value: String): String {
        val sb = StringBuilder(value.length + 8)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
