package com.dsh.mobile.bootstrap

import android.content.Context
import android.util.Log
import com.dsh.mobile.runtime.AgentRuntime
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BOOTSTRAP ENGINE — first-boot environment setup executor.
 *
 * Runs `assets/bootstrap.sh` on first app init (and on demand from the error
 * card's Retry button). The script's machine-readable markers
 * (`>>>STEP:`, `>>>PROGRESS:`, `>>>LOG:`, `>>>ERROR:`, `>>>DONE`) are parsed
 * into [BootstrapState] so the Compose [BootstrapProgressView] shows the user
 * exactly what is configuring — no terminal, just a progress bar + step log.
 *
 * Idempotent: the script short-circuits when the marker file exists; this
 * manager additionally skips execution when [isBootstrapped] is already true
 * unless `force = true`.
 */
class BootstrapManager(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null

    private val _state = MutableStateFlow<BootstrapState>(BootstrapState.Idle)
    val state: StateFlow<BootstrapState> = _state.asStateFlow()

    /** True when a previous successful run stamped the marker file. */
    fun isBootstrapped(): Boolean =
        File(AgentRuntime.runtimeRoot(appContext), MARKER_NAME).exists() &&
            AgentRuntime.nodeBinary(appContext).canExecute()

    fun bootstrapLogFile(): File =
        File(appContext.filesDir, "dsh-logs/bootstrap.log").apply {
            parentFile?.mkdirs()
        }

    /**
     * Run the bootstrap script. No-op when already bootstrapped unless
     * [force] is true (Retry button / backend switch needing missing CLIs).
     */
    fun ensureBootstrapped(force: Boolean = false) {
        if (runJob?.isActive == true) return
        if (!force && isBootstrapped()) {
            _state.value = BootstrapState.Ready
            return
        }
        runJob = scope.launch { runScript() }
    }

    fun cancel() {
        runJob?.cancel()
        runJob = null
    }

    fun destroy() {
        scope.cancel()
    }

    // -- execution ----------------------------------------------------------

    private suspend fun runScript() = withContext(Dispatchers.IO) {
        _state.value = BootstrapState.Running(
            step = "Starting setup…",
            progress = 0,
            lines = emptyList(),
        )
        val lines = ArrayDeque<String>()
        fun push(line: String) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        fun update(step: String? = null, progress: Int? = null, error: String? = null) {
            val cur = _state.value as? BootstrapState.Running
            _state.value = BootstrapState.Running(
                step = step ?: cur?.step ?: "",
                progress = (progress ?: cur?.progress ?: 0).coerceIn(0, 100),
                lines = lines.toList(),
                error = error ?: cur?.error,
            )
        }

        try {
            appendLog("=== bootstrap started ===")
            val script = stageScript()
            val runtimeRoot = AgentRuntime.runtimeRoot(appContext)
            // The working directory MUST exist inside the guest rootfs.
            // PRoot chdir()s to the current directory inside the guest and
            // hard-exits (255) when it is missing:
            //   can't chdir("/data/.../files/./.") in the guest rootfs
            // process.cwd == filesDir does not exist under runtimeRoot, so the
            // guest cannot see it. `run-as` inherited a CWD that did exist,
            // which is why manual testing never reproduced this.
            val process = ProcessBuilder("sh", script.absolutePath)
                .directory(runtimeRoot)
                .apply {
                    // Keep PATH functional on-device; script resolves the rest.
                    environment()["RUNTIME_ROOT"] = runtimeRoot.absolutePath
                    environment()["NATIVE_LIB_DIR"] = appContext.applicationInfo.nativeLibraryDir
                    environment()["ROOTFS_TAR"] =
                        File(runtimeRoot, "rootfs.tar.gz").absolutePath
                    // Node ships inside the rootfs, so the "node dir" IS the
                    // runtime root. See AgentRuntime.nodeBinary().
                    environment()["NODE_DIR"] = runtimeRoot.absolutePath
                    // Apps targeting API 29+ cannot execute files in their own
                    // data dir (execute_no_trans). bootstrap.sh probes these
                    // for a location where the proot binary actually runs.
                    environment()["EXT_FILES_DIR"] =
                        (appContext.getExternalFilesDir(null) ?: File(runtimeRoot, "ext")).absolutePath
                    environment()["CACHE_DIR"] =
                        (appContext.cacheDir ?: File(runtimeRoot, "cache")).absolutePath
                    environment()["AGENTS_DIR"] =
                        AgentRuntime.agentsDir(appContext).absolutePath
                    environment()["MARKER_FILE"] =
                        File(runtimeRoot, MARKER_NAME).absolutePath
                    redirectErrorStream(true) // merge stderr into stdout for one loop
                }
                .start()

            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { raw ->
                    val line = raw.trimEnd()
                    appendLog(line)
                    when {
                        line.startsWith(MARK_STEP) ->
                            update(step = line.removePrefix(MARK_STEP))
                        line.startsWith(MARK_PROGRESS) ->
                            update(progress = line.removePrefix(MARK_PROGRESS)
                                .trim().toIntOrNull())
                        line.startsWith(MARK_LOG) ->
                            push(line.removePrefix(MARK_LOG))
                                .also { update() }
                        line.startsWith(MARK_ERROR) -> {
                            val msg = line.removePrefix(MARK_ERROR)
                            push("⚠ $msg")
                            update(error = msg)
                        }
                        line == MARK_DONE -> {
                            update(progress = 100)
                            return@useLines
                        }
                        line.isNotBlank() -> {
                            push(line.take(300))
                            update()
                        }
                    }
                }
            }

            val exit = process.waitFor()
            appendLog("=== bootstrap exit=$exit ===")
            if (exit == 0) {
                _state.value = BootstrapState.Ready
            } else {
                val cur = _state.value as? BootstrapState.Running
                val msg = cur?.error
                    ?: "Setup failed (code $exit). Free storage, check connection, tap Retry."
                _state.value = BootstrapState.Failed(
                    message = msg,
                    lines = lines.toList(),
                    retryable = true,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap failed", e)
            appendLog("EXCEPTION: ${Log.getStackTraceString(e)}")
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = BootstrapState.Failed(
                message = e.message ?: "Setup was interrupted.",
                lines = lines.toList(),
                retryable = true,
            )
        }
    }

    /** Copies the asset script to app-private storage and makes it executable. */
    private fun stageScript(): File {
        val out = File(appContext.filesDir, "bin/bootstrap.sh").apply {
            parentFile?.mkdirs()
        }
        appContext.assets.open("bootstrap.sh").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        // chmod 700 — scripts must be executable for ProcessBuilder("sh", …)
        // to be robust across OEM shells; harmless if it fails (we invoke sh).
        runCatching { ProcessBuilder("chmod", "700", out.absolutePath).start().waitFor() }
        return out
    }

    private fun appendLog(line: String) {
        runCatching {
            bootstrapLogFile().appendText(line + "\n")
        }
    }

    sealed interface BootstrapState {
        data object Idle : BootstrapState
        data object Ready : BootstrapState
        data class Running(
            val step: String,
            val progress: Int,
            val lines: List<String>,
            val error: String? = null,
        ) : BootstrapState
        data class Failed(
            val message: String,
            val lines: List<String>,
            val retryable: Boolean,
        ) : BootstrapState
    }

    private companion object {
        const val TAG = "BootstrapManager"
        const val MARKER_NAME = ".bootstrap_done"
        const val MAX_LINES = 60
        const val MARK_STEP = ">>>STEP:"
        const val MARK_PROGRESS = ">>>PROGRESS:"
        const val MARK_LOG = ">>>LOG:"
        const val MARK_ERROR = ">>>ERROR:"
        const val MARK_DONE = ">>>DONE"
    }
}
