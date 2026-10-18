package com.dsh.mobile.terminal

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a shell command for the Terminal tab.
 *
 * Android's `sh` is toybox's shell, and toybox is present on every device, so
 * the Terminal works without proot. The agent's full Alpine environment lives
 * inside proot and is NOT what this runs: this is a sandboxed shell as the app
 * user, scoped to the workspace.
 */
object ShellRunner {

    /** Output beyond this is truncated, so a runaway stream cannot OOM the app. */
    const val MAX_OUTPUT_BYTES: Int = 256 * 1024

    /** Commands are killed after this; a hung command must not wedge the tab. */
    const val DEFAULT_TIMEOUT_MS: Long = 30_000

    /** What a finished command produced. */
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    ) {
        /** Combined view, stderr appended after stdout. */
        val combined: String
            get() = buildString {
                append(stdout)
                if (stderr.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(stderr)
                }
            }
    }

    /**
     * Runs [command] through `sh -c` in [workingDir].
     *
     * stdout and stderr are drained on separate threads. Draining them
     * sequentially on one thread is the classic bug here: a command that fills
     * the stderr pipe buffer blocks forever while the parent is still waiting to
     * read stdout, and the tab hangs with no output and no error.
     */
    fun run(
        command: String,
        workingDir: File,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result {
        if (command.isBlank()) {
            return Result(0, "", "", timedOut = false)
        }
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .directory(workingDir)
                .start()

            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = pump(process.inputStream, out)
            val errThread = pump(process.errorStream, err)

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            outThread.join(1_000)
            errThread.join(1_000)
            Result(
                exitCode = if (finished) process.exitValue() else 124,
                stdout = out.toString(),
                stderr = err.toString(),
                timedOut = !finished,
            )
        } catch (e: Exception) {
            Result(127, "", e.message ?: "failed to start shell", timedOut = false)
        }
    }

    /** Drains one stream into [sink], stopping at [MAX_OUTPUT_BYTES]. */
    private fun pump(stream: java.io.InputStream, sink: StringBuilder): Thread {
        val thread = Thread {
            try {
                stream.bufferedReader().use { reader ->
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (sink.length < MAX_OUTPUT_BYTES) {
                            sink.append(buf, 0, minOf(n, MAX_OUTPUT_BYTES - sink.length))
                        }
                        // Keep draining past the cap so the child never blocks on
                        // a full pipe; we simply stop recording.
                    }
                }
            } catch (_: Exception) {
                // Stream closed by destroyForcibly on timeout - expected.
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Whether a command is a plausible single line to submit.
     *
     * The Terminal runs arbitrary shell by design (the agent itself runs
     * arbitrary code), so this is NOT a safety gate - it only rejects input that
     * cannot do anything useful, like an embedded newline trying to smuggle a
     * second command past the UI's single-line field.
     */
    fun isSubmittable(command: String): Boolean =
        command.isNotBlank() && !command.contains('\n') && !command.contains('\r')
}

/** One completed command and its output, as shown in the transcript. */
data class TerminalEntry(val command: String, val output: String, val failed: Boolean)

/**
 * Bounds the Terminal transcript.
 *
 * ShellRunner caps a SINGLE command's output at 256KB, but the transcript is a
 * long-lived Compose state list: a user running a hundred commands would retain
 * tens of megabytes of scrollback for the life of the process. Both the number
 * of entries and the length of each are bounded here, so the tab's memory is a
 * constant rather than a function of how long the session has been open.
 */
object TerminalTranscript {

    /** How many commands stay in the scrollback. */
    const val MAX_ENTRIES: Int = 200

    /** How much of each command's output is retained. */
    const val MAX_OUTPUT_CHARS: Int = 16_000

    /**
     * Shortens [text] so the RESULT is never longer than [MAX_OUTPUT_CHARS].
     *
     * The notice is budgeted INSIDE the cap rather than appended past it. An
     * earlier revision returned MAX_OUTPUT_CHARS plus a ~40 character notice,
     * which made the documented per-entry cap a lie and the whole-transcript
     * memory ceiling unprovable.
     */
    fun truncateOutput(text: String): String {
        if (text.length <= MAX_OUTPUT_CHARS) return text
        val notice = "\n... (truncated, ${text.length - MAX_OUTPUT_CHARS} more characters)"
        val keep = (MAX_OUTPUT_CHARS - notice.length).coerceAtLeast(0)
        return text.take(keep) + notice
    }

    /** [entry] appended to [existing], oldest evicted past [MAX_ENTRIES]. */
    fun append(existing: List<TerminalEntry>, entry: TerminalEntry): List<TerminalEntry> {
        val bounded = entry.copy(output = truncateOutput(entry.output))
        val out = ArrayList<TerminalEntry>(existing.size + 1)
        out += existing
        out += bounded
        // Keep the newest MAX_ENTRIES; drop the rest from the front.
        return if (out.size <= MAX_ENTRIES) out else out.subList(out.size - MAX_ENTRIES, out.size).toList()
    }
}
