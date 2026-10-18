package com.dsh.mobile.service

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the URL an agent prints on startup, and owns the agent log file.
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
 *
 * ## Two bugs [openLogWriter] fixes
 *
 *  1. **Truncation.** `File.writer()` truncates. stdout and stderr are pumped
 *     by two separate threads into the SAME file, so the second pump erased
 *     everything the first had written - including the banner above. The log
 *     therefore held only whatever the later stream produced.
 *  2. **Unbounded growth.** Nothing ever trimmed the file, so a long-lived
 *     install grew it until the data partition filled. Mobile-Harness caps its
 *     logs at 2MB; the same ceiling applies here.
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

    /** Only trust loopback URLs - a hostile child must not redirect the WebView off-device. */
    private val LOOPBACK = Regex("""^https?://(127\.0\.0\.1|localhost|\[::1\])(:\d+)?/""")

    /**
     * Ceiling for one agent log, matching Mobile-Harness's 2MB cap.
     *
     * A phone's data partition is shared with the extracted runtime and the
     * workspace; an agent left running for hours writes far more than that.
     */
    const val MAX_LOG_BYTES: Long = 2L * 1024 * 1024

    /**
     * Both pumps write the same file, so they must share ONE lock.
     *
     * With a per-writer lock the stdout and stderr threads interleave inside a
     * single line, and the log becomes unreadable exactly when it is needed.
     */
    private val LOG_LOCK = Any()

    /**
     * How often [pump] re-checks the log size, in lines.
     *
     * The rotated generation can overshoot [MAX_LOG_BYTES] by up to one check
     * interval, so this stays small: a `length()` stat every 64 lines is free,
     * and it keeps the previous generation near the cap rather than a full
     * interval's worth above it.
     */
    private const val ROTATE_CHECK_EVERY_LINES = 64

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
     * Rotates [logFile] to `<name>.1` when it has reached [maxBytes], keeping
     * exactly one previous generation. Returns true when it actually rotated.
     *
     * Checked both when the log is opened and periodically while pumping, since
     * one agent run can produce more output than a single boot.
     */
    fun rotateIfNeeded(logFile: File, maxBytes: Long = MAX_LOG_BYTES): Boolean {
        if (maxBytes <= 0) return false
        if (!logFile.isFile || logFile.length() < maxBytes) return false
        val previous = File(logFile.parentFile, logFile.name + ".1")
        // Exactly ONE generation: drop the older one rather than keeping a set
        // that grows without bound too.
        previous.delete()
        return logFile.renameTo(previous)
    }

    /**
     * Opens [logFile] for appending, rotating it first when it is already full.
     *
     * Append mode is what keeps the two pumps from erasing each other; see the
     * class docs.
     */
    fun openLogWriter(
        logFile: File,
        maxBytes: Long = MAX_LOG_BYTES,
    ): PrintWriter {
        logFile.parentFile?.mkdirs()
        rotateIfNeeded(logFile, maxBytes)
        return PrintWriter(FileOutputStream(logFile, /* append = */ true).buffered(), true)
    }

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
                var current = openLogWriter(logFile)
                try {
                    stream.bufferedReader().use { reader ->
                        var linesSinceCheck = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            synchronized(LOG_LOCK) { current.println(line) }
                            onLine(line)
                            if (found.get() == null) {
                                extractLaunchUrl(line)?.let { found.compareAndSet(null, it) }
                            }
                            if (++linesSinceCheck >= ROTATE_CHECK_EVERY_LINES) {
                                linesSinceCheck = 0
                                if (logFile.length() >= MAX_LOG_BYTES) {
                                    synchronized(LOG_LOCK) {
                                        // Rotate, then reopen, so the live writer
                                        // never keeps writing into the file that
                                        // was just moved aside.
                                        current.flush()
                                        current.close()
                                        current = openLogWriter(logFile)
                                    }
                                }
                            }
                        }
                        synchronized(LOG_LOCK) { current.flush() }
                    }
                } finally {
                    runCatching { current.close() }
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
