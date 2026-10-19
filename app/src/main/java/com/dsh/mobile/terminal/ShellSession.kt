package com.dsh.mobile.terminal

import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One long-lived `sh` process for the Terminal tab.
 *
 * [ShellRunner] runs each command as a fresh `sh -c`, which means `cd`, exported
 * variables, shell functions and history are lost every time. That is not a
 * shell; it is a command launcher. A real harness keeps the session, because
 * "cd into the repo, then run the tests" is the most common thing a developer
 * does.
 *
 * ## How a command is delimited
 *
 * The child is a single `sh` reading lines from a pipe. After each command a
 * sentinel line is written that carries the exit code:
 *
 * ```
 * eval "<user command>" </dev/null ; echo "__DSH_DONE__<n>:$?"
 * ```
 *
 * Three parts of that are load-bearing, and each was measured rather than
 * assumed (see ShellSessionTest):
 *
 *  - **`eval`** isolates a SYNTAX ERROR. Sending a malformed line directly to
 *    `sh` kills the process (observed: exit 2), taking every later command with
 *    it. Wrapped in `eval`, the error is reported and the session survives.
 *  - **`</dev/null`** isolates STDIN. A command that reads stdin (`read`,
 *    `cat`, an interactive tool) would otherwise consume the NEXT command line
 *    and the sentinel, desynchronising the session permanently - every later
 *    command returns the previous command's output. Redirecting stdin means
 *    such a command sees EOF instead of the session's pipe.
 *  - **the sentinel carries `$?`**, so the exit code belongs to the user's
 *    command and not to the wrapper.
 *
 * The sentinel is written to stdout, so it arrives interleaved with real output;
 * everything before it is the command's output.
 *
 * ## Threading
 *
 * Reads happen on a dedicated daemon thread per command, which is what allows a
 * timeout to abandon a command without losing the session: the thread stops
 * waiting, the process keeps its pipe, and the next command can still run. This
 * is deliberately NOT a per-command process, so [close] is the only way the
 * child dies.
 */
class ShellSession(
    workingDir: File,
    private val outputLimitChars: Int = DEFAULT_OUTPUT_LIMIT_CHARS,
) {
    /** What a finished command produced. */
    data class Result(
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean,
        /** True when the session died and was replaced. */
        val restarted: Boolean = false,
        /** Set when the command could not be run at all. */
        val failure: String? = null,
    )

    private val workDir: File = workingDir
    private val counter = AtomicLong(0)
    private var process: Process? = null
    private var stdin: OutputStream? = null
    private val buffer = StringBuilder()
    private val lock = Object()

    /**
     * Sentinels belonging to commands that were abandoned on timeout.
     *
     * Their output and sentinel can still arrive AFTER the timeout, landing in
     * the next command's window. Left alone they appear as garbage in the user's
     * output (observed: `__DSH_DONE__1:0` printed before the next result), so
     * they are filtered out of later output.
     */
    private val staleTokens = ArrayList<String>()

    @Volatile
    private var closed = false

    /**
     * Whether the child process is currently running. A PURE check.
     *
     * Deliberately side-effect free. An earlier version restarted the child
     * inside this getter, which made [run]'s own restart flag unreachable - it
     * asked `isAlive`, the getter silently respawned, and `restarted` stayed
     * false, so callers were never told the shell had died. Restarting is
     * [ensureStarted]'s job.
     */
    val isAlive: Boolean
        get() = !closed && process?.isAlive == true

    /** Whether this session can still be used (started or startable). */
    val isOpen: Boolean
        get() = !closed

    /**
     * Starts the child if it is not already running. Idempotent.
     *
     * Returns true when a child is running afterwards.
     */
    fun ensureStarted(): Boolean = start()

    /**
     * Runs [command], returning its output and exit code.
     *
     * Restarts the session once if it has died, because a dead shell is far more
     * likely to be a crash than a user intent to stop.
     */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        if (closed) return Result("", 130, false, failure = "session closed")
        if (command.isBlank()) return Result("", 0, false)
        // A restart is reported to the caller, so a command that ran in a fresh
        // shell is distinguishable from one that inherited the previous state.
        val restarted = !isAlive
        if (!ensureStarted()) {
            return Result("", 127, false, restarted = restarted, failure = "could not start a shell")
        }

        val token = "__DSH_DONE__${counter.incrementAndGet()}:"
        // Single line, so the wrapper cannot be split by a newline inside the
        // command. Newlines in the command are escaped below.
        val wrapped = buildString {
            append("eval ")
            append(quoteForDoubleQuotes(command))
            append(" </dev/null ; echo \"")
            append(token)
            append("$?\"")
        }

        // The buffer is NOT cleared here. A timed-out command's sentinel can
        // still arrive afterwards, and clearing would let that stale sentinel be
        // matched by the NEXT command - which then returns the previous command's
        // output and, worse, its exit code. Instead each command records how far
        // the buffer had been consumed, and only looks past that offset.
        val startAt = synchronized(lock) { buffer.length }
        val out = stdin
        if (out == null) return Result("", 127, false, failure = "no stdin")

        return try {
            out.write((wrapped + "\n").toByteArray())
            out.flush()
        } catch (e: Exception) {
            // Broken pipe: the child died between the liveness check and here.
            return Result("", 127, false, restarted = restarted, failure = "shell exited")
        }.let {
            collect(token, startAt, timeoutMs, restarted)
        }
    }

    /**
     * Waits for [token] to appear past [startAt], or the timeout.
     *
     * [startAt] is the buffer offset this command began at, so a sentinel left
     * behind by an abandoned command can never be mistaken for this one's.
     */
    private fun collect(token: String, startAt: Int, timeoutMs: Long, restarted: Boolean): Result {
        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        while (true) {
            val snapshot = synchronized(lock) { buffer.toString() }
            // Guard: the ring trim can move the window, so clamp rather than
            // assuming the offset is still valid.
            val from = startAt.coerceIn(0, snapshot.length)
            val at = snapshot.indexOf(token, from)
            if (at >= 0) {
                val after = snapshot.substring(at + token.length)
                val codeText = after.takeWhile { it.isDigit() || it == '-' }
                val code = codeText.toIntOrNull() ?: 0
                val output = clean(snapshot.substring(from, at))
                // Any stale sentinel has now been consumed along with this
                // window, so forget it.
                synchronized(lock) { staleTokens.removeAll { snapshot.contains(it) } }
                return Result(
                    output = bound(output),
                    exitCode = code,
                    timedOut = false,
                    restarted = restarted,
                )
            }
            if (!isAlive) {
                // The child exited without a sentinel: report what it printed.
                val output = synchronized(lock) { buffer.toString() }
                process = null
                stdin = null
                return Result(
                    output = bound(output.drop(startAt.coerceAtMost(output.length))),
                    exitCode = 127,
                    timedOut = false,
                    restarted = restarted,
                    failure = "shell exited",
                )
            }
            if (System.currentTimeMillis() > deadline) {
                timedOut = true
                break
            }
            Thread.sleep(POLL_MS)
        }
        val partial = synchronized(lock) { buffer.toString() }
        // Remember this command's sentinel: it has not arrived yet and will land
        // in a later command's window.
        synchronized(lock) { staleTokens += token }
        return Result(
            bound(clean(partial.drop(startAt.coerceAtMost(partial.length)))),
            124,
            timedOut = true,
            restarted = restarted,
        )
    }

    /**
     * Removes the sentinels of abandoned commands from [text].
     *
     * Also drops anything before a stale sentinel: that is the tail of the
     * abandoned command's own output, which belongs to a command the user was
     * already told timed out.
     */
    private fun clean(text: String): String {
        var out = text
        synchronized(lock) {
            for (token in staleTokens) {
                val at = out.indexOf(token)
                if (at >= 0) {
                    val lineEnd = out.indexOf('\n', at)
                    out = if (lineEnd >= 0) out.substring(lineEnd + 1) else ""
                }
            }
        }
        return out
    }

    /**
     * Caps recorded output.
     *
     * The reader keeps draining past the cap (so the child never blocks on a
     * full pipe) but stops recording, and the notice is budgeted INSIDE the cap
     * so the documented ceiling is the real one.
     */
    private fun bound(text: String): String {
        if (text.length <= outputLimitChars) return text
        val notice = "\n... (truncated)"
        return text.take((outputLimitChars - notice.length).coerceAtLeast(0)) + notice
    }

    private fun start(): Boolean {
        if (closed) return false
        if (process?.isAlive == true) return true
        return try {
            val p = ProcessBuilder("sh")
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            process = p
            stdin = p.outputStream
            // Single daemon reader for the whole session; commands append to the
            // shared buffer and [run] scans it for its sentinel.
            Thread {
                val reader = p.inputStream.bufferedReader()
                val chunk = CharArray(4096)
                try {
                    while (true) {
                        val n = reader.read(chunk)
                        if (n < 0) break
                        synchronized(lock) {
                            buffer.append(chunk, 0, n)
                            // Hard ceiling on the raw buffer, so a command that
                            // floods output cannot grow it without bound while a
                            // later command is waiting.
                            if (buffer.length > outputLimitChars * 4) {
                                buffer.delete(0, buffer.length - outputLimitChars * 2)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Process destroyed on close - expected.
                }
            }.apply {
                isDaemon = true
                name = "shell-session-reader"
                start()
            }
            // Prove the child is usable before reporting success.
            true
        } catch (e: Exception) {
            process = null
            stdin = null
            false
        }
    }

    /** Terminates the child. The session cannot be reused afterwards. */
    fun close() {
        closed = true
        runCatching { stdin?.close() }
        runCatching { process?.let { p ->
            p.destroy()
            if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
        } }
        stdin = null
        process = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000
        const val DEFAULT_OUTPUT_LIMIT_CHARS: Int = 64_000
        private const val POLL_MS = 12L

        /**
         * Escapes [command] for inclusion inside a double-quoted `eval` payload.
         *
         * Inside double quotes `sh` expands `$`, backticks and `\`, so a command
         * containing them would be interpreted by the OUTER shell before `eval`
         * ever saw it - `echo $HOME` would arrive as the expanded path instead of
         * the literal text, and a backtick would execute. All three are escaped,
         * plus the double quote itself. Newlines are preserved as `\n` inside the
         * quotes so a multi-line command stays one input line.
         */
        fun quoteForDoubleQuotes(command: String): String {
            val sb = StringBuilder(command.length + 16)
            sb.append('"')
            for (c in command) {
                when (c) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '$' -> sb.append("\\$")
                    '`' -> sb.append("\\`")
                    '\n' -> sb.append("\\n")
                    '\r' -> Unit
                    else -> sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}
