package com.dsh.mobile.service

import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the URL an agent prints on startup.
 *
 * ## Why this exists
 *
 * `dsh web` does NOT serve its UI at a bare `http://127.0.0.1:<port>`.
 * It sits behind a browser-trust fence and answers **401** with
 *
 *     dsh web authentication required; reopen the URL printed by dsh web.
 *
 * The real entry point is printed on stdout as
 *
 *     dsh web: http://127.0.0.1:3979/?token=<secret>
 *
 * and that tokenized URL answers **303** (auth handshake). Loading the bare
 * origin therefore leaves the WebView showing an authentication error forever.
 *
 * Because the child's stdout was redirected straight into a log file, that URL
 * was previously invisible to the app. [pump] tees the stream to the log AND
 * scans it.
 */
object AgentLaunch {

    /**
     * Matches the `dsh web: <url>` banner. Tolerates surrounding whitespace and
     * a `[dsh web]` bracketed prefix, both of which have been observed.
     */
    private val URL_LINE = Regex(
        """(?:dsh\s+web\s*:?\s*)?((?:https?://)[^\s]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** Only trust loopback URLs — a hostile child must not redirect the WebView off-device. */
    private val LOOPBACK = Regex("""^https?://(127\.0\.0\.1|localhost|\[::1\])(:\d+)?/""")

    /**
     * Extracts the launch URL from one stdout line.
     *
     * Returns null for anything that is not a loopback URL, which keeps the
     * WebView pinned to the device even if the agent prints other links
     * (docs URLs, update notices, telemetry endpoints).
     */
    fun extractLaunchUrl(line: String): String? {
        val url = URL_LINE.find(line.trim())?.groupValues?.get(1) ?: return null
        if (!LOOPBACK.containsMatchIn(url)) return null
        // Guard against a child printing something enormous as its "URL".
        return if (url.length <= 2048) url else null
    }

    /** True when the URL carries a token that must be redacted from logs. */
    fun isSecretBearing(url: String): Boolean = url.contains("token=")

    /**
     * Tees [stream] into [logFile] line by line, publishing the first launch
     * URL it sees. Returns immediately; pumping continues on a daemon thread.
     *
     * @param onLine invoked for every complete line (used to mirror to Logcat).
     */
    fun pump(
        stream: InputStream,
        logFile: File,
        found: AtomicReference<String?> = AtomicReference(null),
        onLine: (String) -> Unit = {},
    ): Thread {
        val t = Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val writer = PrintWriter(logFile.writer().buffered(), true)
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(writer) { writer.println(line) }
                        onLine(line)
                        if (found.get() == null) {
                            extractLaunchUrl(line)?.let { found.compareAndSet(null, it) }
                        }
                    }
                    synchronized(writer) { writer.flush() }
                }
            } catch (_: Exception) {
                // Stream closed on shutdown; nothing to recover.
            }
        }
        t.isDaemon = true
        t.name = "dsh-stdout-pump"
        t.start()
        return t
    }

    /** Masks the token so logs and diagnostic exports never leak it. */
    fun redactUrl(url: String): String = url.replace(Regex("token=[^&\\s]+"), "token=***REDACTED***")
}