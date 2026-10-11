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
            val nativeLibsDir = unpackNativeLibs(File(appContext.filesDir, "nativelibs-root"))
            val agentTarball = try {
                stageAgentPayload(File(runtimeRoot, "payload"))
            } catch (t: Throwable) {
                appendLog(">>>LOG:agent payload staging failed (${t.javaClass.simpleName}: ${t.message})")
                appendLog(">>>LOG:continuing without the bundled agent tree")
                null
            }

            val process = ProcessBuilder("sh", script.absolutePath)
                .directory(runtimeRoot)
                .apply {
                    // Keep PATH functional on-device; script resolves the rest.
                    environment()["RUNTIME_ROOT"] = runtimeRoot.absolutePath
                    environment()["NATIVE_LIB_DIR"] = appContext.applicationInfo.nativeLibraryDir
                    environment()["NATIVE_LIBS_DIR"] = nativeLibsDir.absolutePath
                    environment()["AGENT_TARBALL"] = (agentTarball ?: File(runtimeRoot, "none")).absolutePath
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

    /**
     * Unpacks assets/nativelibs into app-private storage.
     *
     * The bionic Node interpreter ships as a jniLib so that it is extracted to
     * nativeLibraryDir, which is the only app location where execve is still
     * permitted at targetSdk 29+ (execute_no_trans blocks the writable data
     * dir). Its shared libraries cannot go the same route: AGP only packages
     * jniLibs files matching `lib*.so`, so versioned SONAMEs like libz.so.1,
     * libssl.so.3 and libicuuc.so.78 would be silently dropped and the loader
     * would then fail with "library not found".
     *
     * They are shipped as assets instead and written to filesDir, which needs
     * no exec permission - the dynamic linker only reads and mmaps them.
     */
    private fun unpackNativeLibs(targetDir: File): File {
        val dir = File(targetDir, "nativelibs").apply { mkdirs() }
        val names = appContext.assets.list("nativelibs") ?: return dir
        var extracted = 0
        for (name in names) {
            val out = File(dir, name)
            // Cheap freshness check: same size means the bundled asset has not
            // changed, so skip the copy on every boot.
            val assetSize = runCatching {
                appContext.assets.openFd("nativelibs/$name").use { it.length }
            }.getOrDefault(-1L)
            if (out.exists() && assetSize > 0 && out.length() == assetSize) continue
            appContext.assets.open("nativelibs/$name").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            runCatching { out.setReadable(true, false) }
            extracted++
        }
        if (extracted > 0) {
            appendLog(">>>LOG:unpacked $extracted native librar" + if (extracted == 1) "y" else "ies")
        }
        return dir
    }

    /**
     * Stages the dsh agent tree for first-boot extraction by bootstrap.sh.
     *
     * npm cannot install this tree on the device: resolving ~540 packages
     * crashes node on a phone-sized RAM budget, so the tree is assembled on the
     * host (see tools/build-dsh-payload.sh) and shipped as a tarball. It is an
     * asset rather than a jniLib because it is data, not something we exec.
     */
    private fun stageAgentPayload(targetDir: File): File? {
        targetDir.mkdirs()
        val tarball = File(targetDir, "dsh-agent.tar.gz")
        // aapt2 does not preserve asset names verbatim: it decompresses .gz
        // entries and renames them, so a file shipped as dsh-agent.tar.gz lands
        // in the APK as assets/dsh-agent.tar. Hard-coding either name breaks
        // the other way, so resolve by prefix.
        val assetName = appContext.assets.list("")
            ?.firstOrNull { it == "dsh-agent.tar.gz" || it.startsWith("dsh-agent.tar") }
        if (assetName == null) {
            appendLog(">>>LOG:no agent payload bundled; PRoot guest only")
            return null
        }
        // openFd only works on assets aapt2 stored UNCOMPRESSED, so this size
        // probe usually fails. That must not be read as "not bundled" - it is
        // exactly what happened, and the agent was silently skipped.
        val assetSize = runCatching {
            appContext.assets.openFd(assetName).use { it.length }
        }.getOrDefault(-1L)
        if (assetSize > 0L) {
            if (tarball.exists() && tarball.length() == assetSize) return tarball
        } else if (tarball.exists() && tarball.length() > 0L) {
            // Cannot size-check it; the file being present is good enough.
            // bootstrap.sh owns idempotency via the unpacked entry point.
            return tarball
        }
        appContext.assets.open(assetName).use { input ->
            tarball.outputStream().use { output -> input.copyTo(output) }
        }
        appendLog(">>>LOG:staged agent payload ${tarball.length() / 1_048_576} MB")
        return tarball
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
