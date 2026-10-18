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
